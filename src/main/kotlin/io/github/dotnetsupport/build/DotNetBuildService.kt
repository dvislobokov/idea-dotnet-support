package io.github.dotnetsupport.build

import com.intellij.build.BuildViewManager
import com.intellij.build.process.BuildProcessHandler
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SkippedResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetLogs
import io.github.dotnetsupport.settings.DotNetSettings
import java.io.File
import java.io.OutputStream

enum class DotNetBuildCommand(val title: String, vararg val arguments: String) {
    BUILD("Build", "build"),
    REBUILD("Rebuild", "build", "--no-incremental"),
    CLEAN("Clean", "clean"),
    RESTORE("Restore", "restore");

    // Every diagnostic is printed when it happens; the summary would only repeat them.
    fun argumentsFor(target: String): Array<String> =
        arrayOf(*arguments, target, "-nologo") + if (this == RESTORE) emptyArray() else arrayOf("-clp:NoSummary")
}

/** Told when a `dotnet` command of [DotNetBuildService] has finished, on the thread of the process: whatever shows the build output refreshes then. */
fun interface DotNetBuildListener {
    fun buildFinished(target: VirtualFile, succeeded: Boolean)

    companion object {
        @JvmField val TOPIC: Topic<DotNetBuildListener> = Topic.create("DotNet build finished", DotNetBuildListener::class.java)
    }
}

/** Runs `dotnet build` and friends and reports to the Build tool window. */
@Service(Service.Level.PROJECT)
class DotNetBuildService(private val project: Project) {
    /** The processes of the builds that are running: Cancel Build of the Build Solution button stops them. */
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<OSProcessHandler>()
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<OSProcessHandler>()

    /** Whether a build, a clean, a restore or a target started here is running. */
    val isBuilding: Boolean get() = running.any { !it.isProcessTerminated }

    /** Cancel Build (as in Rider): stops every running build of the project. */
    fun cancel() = running.toList().forEach {
        cancelled.add(it)
        it.destroyProcess()
    }


    /**
     * [target] is a solution or a project file. [onFinished] gets whether the build has succeeded, for a launch that waits for it.
     * Called on the EDT (actions, the build before a launch): the documents are saved here, and the rest goes to a pooled thread, because
     * working out the arguments reads files ("Smart Restore" looks into `project.assets.json`), which the platform forbids on the EDT.
     */
    fun run(target: VirtualFile, command: DotNetBuildCommand, saveDocuments: Boolean = true, onFinished: (Boolean) -> Unit = {}) {
        if (saveDocuments) com.intellij.openapi.application.WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) {
                onFinished(false)
                return@executeOnPooledThread
            }
            val arguments = arrayOf(
                *command.argumentsFor(target.path), *DotNetBuildSettings.getInstance(project).buildArguments(command, target),
                *DotNetBuildOptions.getInstance(project).arguments(command, target).toTypedArray(),
            )
            run(target, "${command.title} ${target.name}", arguments, saveDocuments = false, onFinished) { run(target, command) }
        }
    }

    /**
     * Any `dotnet` command that builds [target], e.g. a custom MSBuild target, with the same reporting as a build. A build, a clean or an
     * `msbuild` of a project of the old format goes to `MSBuild.exe` of Visual Studio instead ([VisualStudioToolset]): choosing it runs
     * `vswhere` and reads the projects, so from the EDT the rest moves to a pooled thread.
     */
    fun run(target: VirtualFile, title: String, arguments: Array<String>, saveDocuments: Boolean = true, onFinished: (Boolean) -> Unit = {}, rerun: () -> Unit) {
        if (saveDocuments) com.intellij.openapi.application.WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) application.executeOnPooledThread { start(target, title, arguments, onFinished, rerun) }
        else start(target, title, arguments, onFinished, rerun)
    }

    private fun start(target: VirtualFile, title: String, arguments: Array<String>, onFinished: (Boolean) -> Unit, rerun: () -> Unit) {
        if (project.isDisposed) {
            onFinished(false)
            return
        }
        val workDirectory = target.parent.path
        val buildId = Any()
        val buildView = project.service<BuildViewManager>()
        val descriptor = DefaultBuildDescriptor(buildId, title, workDirectory, System.currentTimeMillis())
            .withRestartAction(RerunBuildAction(target, rerun))
            .apply {
                // By default the Build tool window opens only when a build fails, so a successful one looks like nothing happened.
                isActivateToolWindowWhenAdded = DotNetSettings.getInstance().openBuildWindowOnEveryBuild
                isAutoFocusContent = false
            }

        val handler = try {
            val command = VisualStudioToolset.buildCommandLine(project, target, workDirectory, arguments.toList())
            DotNetLogs.commandStarted(title, command)
            OSProcessHandler(command)
        } catch (e: ExecutionException) {
            DotNetLogs.commandFinished(title, "cannot start: ${e.message}", failed = true)
            DotNetCli.notifyError(project, title, e.message.orEmpty())
            onFinished(false)
            return
        }

        running.add(handler)
        buildView.onEvent(buildId, BuildViewEvents.started(descriptor.withProcessHandler(StopHandle(title, handler), null), "running..."))
        handler.addProcessListener(object : ProcessListener {
            private val reported = HashSet<MsBuildMessage>()
            private val pending = StringBuilder()
            private var errors = 0

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType === ProcessOutputTypes.SYSTEM) return
                buildView.onEvent(buildId, BuildViewEvents.output(buildId, event.text, outputType !== ProcessOutputTypes.STDERR))
                DotNetLogs.command(if (outputType === ProcessOutputTypes.STDERR) "$title | err" else title, event.text)

                // Text arrives in arbitrary chunks, diagnostics are parsed per complete line.
                pending.append(event.text)
                while (true) {
                    val lineEnd = pending.indexOf("\n")
                    if (lineEnd < 0) break
                    report(pending.substring(0, lineEnd).trimEnd('\r'))
                    pending.delete(0, lineEnd + 1)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                running.remove(handler)
                val wasCancelled = cancelled.remove(handler)
                report(pending.toString())
                val failed = event.exitCode != 0
                val result = if (wasCancelled) SkippedResultImpl() else if (failed) FailureResultImpl() else SuccessResultImpl()
                val message = when {
                    wasCancelled -> "cancelled"
                    !failed -> "finished"
                    errors > 0 -> "failed with $errors error${if (errors == 1) "" else "s"}"
                    else -> "failed with exit code ${event.exitCode}"
                }
                buildView.onEvent(buildId, BuildViewEvents.finished(buildId, message, result))
                DotNetLogs.commandFinished(title, "$message (exit code ${event.exitCode})", failed, MsBuildOutputParser.errorSummary(reported))
                // what the compiler has said goes to the editor too; a clean or a restore says nothing about the code
                if (arguments.firstOrNull() in COMPILING_COMMANDS) BuildProblems.getInstance(project).replace(reported)
                VfsUtil.markDirtyAndRefresh(true, true, true, File(workDirectory))
                if (!project.isDisposed) project.messageBus.syncPublisher(DotNetBuildListener.TOPIC).buildFinished(target, !failed)
                onFinished(!failed)
            }

            private fun report(line: String) {
                val message = MsBuildOutputParser.parseLine(line) ?: return
                if (!reported.add(message)) return // multi-targeted projects repeat diagnostics per framework
                if (message.isError) errors++

                val kind = if (message.isError) MessageEvent.Kind.ERROR else MessageEvent.Kind.WARNING
                val text = listOfNotNull(message.code, message.text).joinToString(": ")
                val file = message.resolveFile()
                val position = file?.let { FilePosition(it, (message.line - 1).coerceAtLeast(0), (message.column - 1).coerceAtLeast(0)) }
                buildView.onEvent(buildId, BuildViewEvents.message(buildId, kind, GROUP, text, line.trim(), position))
            }
        })
        handler.startNotify()
    }

    /** What the Stop button of the Build tool window talks to. */
    private class StopHandle(private val title: String, private val process: OSProcessHandler) : BuildProcessHandler() {
        init {
            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) = notifyProcessTerminated(event.exitCode)
            })
        }

        override fun getExecutionName(): String = title
        override fun destroyProcessImpl() = process.destroyProcess()
        override fun detachProcessImpl() = process.detachProcess()
        override fun detachIsDefault(): Boolean = false
        override fun getProcessInput(): OutputStream? = null
    }

    companion object {
        private const val GROUP = "MSBuild"
        private val COMPILING_COMMANDS = setOf("build", "msbuild", "publish")

        fun getInstance(project: Project): DotNetBuildService = project.service()
    }
}
