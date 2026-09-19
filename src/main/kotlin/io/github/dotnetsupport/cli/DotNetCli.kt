package io.github.dotnetsupport.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.charset.StandardCharsets

object DotNetCli {
    private const val TIMEOUT_MS = 10 * 60 * 1000

    fun findExecutable(): String? {
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

    /** Runs a short command and captures its output. Must not be called on EDT. */
    @Throws(ExecutionException::class)
    fun execute(commandLine: GeneralCommandLine, timeoutMs: Int = TIMEOUT_MS): ProcessOutput =
        CapturingProcessHandler(commandLine).runProcess(timeoutMs)

    /**
     * Runs [commands] one after another in a background task; stops at the first failure and reports it.
     * [refresh] are re-read from disk afterwards, then [onSuccess] is invoked on EDT.
     */
    fun runInBackground(
        project: Project,
        title: String,
        commands: List<GeneralCommandLine>,
        refresh: List<File> = emptyList(),
        onSuccess: () -> Unit = {},
    ) {
        FileDocumentManager.getInstance().saveAllDocuments()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                for (command in commands) {
                    indicator.checkCanceled()
                    indicator.text2 = command.commandLineString
                    val output = try {
                        execute(command)
                    } catch (e: ExecutionException) {
                        notifyError(project, title, e.message.orEmpty())
                        return
                    }
                    if (output.exitCode != 0) {
                        val details = (output.stderr.ifBlank { output.stdout }).trim().lines().takeLast(15).joinToString("\n")
                        notifyError(project, "$title: exit code ${output.exitCode}", details)
                        refreshFiles(refresh)
                        return
                    }
                }
                refreshFiles(refresh)
                ApplicationManager.getApplication().invokeLater(onSuccess, project.disposed)
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
            .createNotification(title, content.replace("
", "<br>"), NotificationType.INFORMATION)
            .notify(project)
    }

    const val NOTIFICATION_GROUP = ".NET"
}
