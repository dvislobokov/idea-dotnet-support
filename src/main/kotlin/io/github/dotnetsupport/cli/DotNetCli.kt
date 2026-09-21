package io.github.dotnetsupport.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import io.github.dotnetsupport.build.BuildViewCommandOutput
import io.github.dotnetsupport.settings.DotNetSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Where the output of background `dotnet` commands goes while they run. Called on a background thread. */
interface CommandOutput {
    fun commandStarted(command: GeneralCommandLine)
    fun text(text: String, isError: Boolean)
    fun commandFinished(exitCode: Int)

    /** All commands are done, or the run has stopped at a failure or a cancellation. */
    fun finished(succeeded: Boolean) {}
}

object DotNetCli {
    private const val TIMEOUT_MS = 10 * 60 * 1000

    /** The executable to run: the one from Settings | Tools | .NET, otherwise the auto-detected one. */
    fun findExecutable(): String? =
        DotNetSettings.getInstance().dotnetPath.takeIf { it.isNotEmpty() && File(it).isFile } ?: detectExecutable()

    /** PATH first, then the default installation directories. */
    fun detectExecutable(): String? {
        val name = if (SystemInfo.isWindows) "dotnet.exe" else "dotnet"
        PathEnvironmentVariableUtil.findInPath(name)?.let { return it.path }
        val wellKnown = if (SystemInfo.isWindows) {
            listOfNotNull(System.getenv("ProgramFiles")?.let { "$it\\dotnet\\dotnet.exe" })
        } else {
            listOf("/usr/local/share/dotnet/dotnet", "/usr/share/dotnet/dotnet", "/usr/lib/dotnet/dotnet", System.getProperty("user.home") + "/.dotnet/dotnet")
        }
        return wellKnown.firstOrNull { File(it).canExecute() }
    }

    @Throws(ExecutionException::class)
    fun commandLine(workDirectory: String?, vararg arguments: String): GeneralCommandLine {
        val executable = findExecutable()
            ?: throw ExecutionException("The 'dotnet' executable is not found. Install the .NET SDK and make sure it is on PATH.")
        return GeneralCommandLine(executable)
            .withParameters(*arguments)
            .withWorkDirectory(workDirectory)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment("DOTNET_NOLOGO", "1")
            .withEnvironment("DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1")
    }

    // --connection: a connection string of `dotnet ef`, usually with a password in it
    private val SECRET_OPTIONS = setOf("-p", "--password", "--api-key", "-k", "--connection")

    /** The command line for logs and progress texts: values of password-like options are masked. */
    fun displayString(command: GeneralCommandLine): String {
        val parameters = command.parametersList.list
        val masked = parameters.mapIndexed { i, parameter ->
            // `ef dbcontext scaffold <connection string>` is positional; `Name=ConnectionStrings:Default` is a reference, not a secret
            val scaffoldConnection = i > 1 && parameters[i - 1] == "scaffold" && parameters[i - 2] == "dbcontext" && !parameter.startsWith("Name=", ignoreCase = true)
            if (i > 0 && parameters[i - 1] in SECRET_OPTIONS || scaffoldConnection) "********" else parameter
        }
        // other tools (upgrade-assistant) go through the same runner
        val executable = File(command.exePath).nameWithoutExtension
        return (listOf(executable) + masked).joinToString(" ") { if (' ' in it) "\"$it\"" else it }
    }

    /** Runs a short command and captures its output. Must not be called on EDT. */
    @Throws(ExecutionException::class)
    fun execute(commandLine: GeneralCommandLine, timeoutMs: Int = TIMEOUT_MS): ProcessOutput =
        CapturingProcessHandler(commandLine).runProcess(timeoutMs)

    /**
     * Runs [commands] one after another in a background task, streaming what they print into [output] as it arrives;
     * stops at the first failure and reports it. [refresh] are re-read from disk afterwards, then [onSuccess] is invoked on EDT.
     * Without an explicit [output] the commands show up as a task of the Build tool window.
     */
    fun runInBackground(
        project: Project,
        title: String,
        commands: List<GeneralCommandLine>,
        refresh: List<File> = emptyList(),
        output: CommandOutput = BuildViewCommandOutput(project, title),
        /** Called on the background thread with the output of the failed command; true when it has reported the failure itself. */
        onFailure: (ProcessOutput) -> Boolean = { false },
        onSuccess: () -> Unit = {},
    ) {
        // callers that had to block to build the commands (a tool asked for its version) come from a pooled thread
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeLater({ runInBackground(project, title, commands, refresh, output, onFailure, onSuccess) }, project.disposed)
            return
        }
        // saving documents and starting a task are for EDT
        FileDocumentManager.getInstance().saveAllDocuments()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val succeeded = runCommands(indicator)
                output.finished(succeeded)
                refreshFiles(refresh)
                if (succeeded) ApplicationManager.getApplication().invokeLater(onSuccess, project.disposed)
            }

            private fun runCommands(indicator: ProgressIndicator): Boolean {
                for (command in commands) {
                    if (indicator.isCanceled) return false
                    indicator.text2 = displayString(command)
                    output.commandStarted(command)
                    val result = try {
                        val handler = CapturingProcessHandler(command)
                        handler.addProcessListener(object : ProcessListener {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                if (outputType !== ProcessOutputTypes.SYSTEM) output.text(event.text, outputType === ProcessOutputTypes.STDERR)
                            }
                        })
                        // cancelling the progress kills the process
                        handler.runProcessWithProgressIndicator(indicator, TIMEOUT_MS, true)
                    } catch (e: ExecutionException) {
                        output.text(e.message.orEmpty() + "\n", true)
                        notifyError(project, title, e.message.orEmpty())
                        return false
                    }
                    output.commandFinished(result.exitCode)
                    if (result.isCancelled) return false
                    if (result.exitCode != 0) {
                        if (onFailure(result)) return false
                        val details = (result.stderr.ifBlank { result.stdout }).trim().lines().takeLast(15).joinToString("\n")
                        notifyError(project, "$title: exit code ${result.exitCode}", details)
                        return false
                    }
                }
                return true
            }
        })
    }

    /** Command lines cannot be built without the SDK; reports that instead of throwing. */
    fun commandLinesOrNotify(project: Project, title: String, build: () -> List<GeneralCommandLine>): List<GeneralCommandLine>? = try {
        build()
    } catch (e: ExecutionException) {
        notifyError(project, title, e.message.orEmpty())
        null
    }

    private fun refreshFiles(files: List<File>) {
        if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *files.toTypedArray())
    }

    fun notifyError(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content.replace("\n", "<br>"), NotificationType.ERROR)
            .notify(project)
    }

    fun notifyInfo(project: Project, title: String, content: String = "") {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content.replace("\n", "<br>"), NotificationType.INFORMATION)
            .notify(project)
    }

    const val NOTIFICATION_GROUP = ".NET"
}
