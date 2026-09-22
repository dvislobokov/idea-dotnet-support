package io.github.dotnetsupport.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.build.FilePosition
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.cli.CommandOutput
import io.github.dotnetsupport.cli.DotNetCli

/**
 * Shows background `dotnet` commands (new project, `sln add`, `ef migrations`, item templates, ...) as a task of the
 * Build tool window: the output appears while the command runs, the window opens by itself only when it fails.
 */
class BuildViewCommandOutput(private val project: Project, private val title: String) : CommandOutput {
    private val buildId = Any()
    private val pending = StringBuilder()
    private var started = false
    private var failed = false

    private fun view(): BuildViewManager = project.service()

    private fun start(workDirectory: String) {
        if (started) return
        started = true
        val descriptor = DefaultBuildDescriptor(buildId, title, workDirectory, System.currentTimeMillis()).apply {
            isActivateToolWindowWhenAdded = false
            isActivateToolWindowWhenFailed = true
        }
        view().onEvent(buildId, StartBuildEventImpl(descriptor, "running..."))
    }

    override fun commandStarted(command: GeneralCommandLine) {
        if (project.isDisposed) return
        start(command.workDirectory?.path.orEmpty())
        view().onEvent(buildId, OutputBuildEventImpl(buildId, "> ${DotNetCli.displayString(command)}\n", true))
    }

    override fun text(text: String, isError: Boolean) {
        if (project.isDisposed || !started) return
        view().onEvent(buildId, OutputBuildEventImpl(buildId, text, !isError))
        // commands that build (`dotnet ef`, `dotnet new` with restore) report MSBuild diagnostics: make them navigable
        pending.append(text)
        while (true) {
            val lineEnd = pending.indexOf("\n")
            if (lineEnd < 0) break
            reportDiagnostic(pending.substring(0, lineEnd).trimEnd('\r'))
            pending.delete(0, lineEnd + 1)
        }
    }

    private fun reportDiagnostic(line: String) {
        val message = MsBuildOutputParser.parseLine(line) ?: return
        val kind = if (message.isError) MessageEvent.Kind.ERROR else MessageEvent.Kind.WARNING
        val text = listOfNotNull(message.code, message.text).joinToString(": ")
        val file = message.resolveFile()
        view().onEvent(
            buildId,
            if (file != null) FileMessageEventImpl(buildId, kind, "MSBuild", text, line.trim(), FilePosition(file, (message.line - 1).coerceAtLeast(0), (message.column - 1).coerceAtLeast(0)))
            else MessageEventImpl(buildId, kind, "MSBuild", text, line.trim()),
        )
    }

    override fun commandFinished(exitCode: Int) {
        if (exitCode != 0) failed = true
        if (!project.isDisposed && started) view().onEvent(buildId, OutputBuildEventImpl(buildId, "\n", true))
    }

    override fun finished(succeeded: Boolean) {
        if (project.isDisposed || !started) return
        val result = if (succeeded && !failed) SuccessResultImpl() else FailureResultImpl()
        view().onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), if (succeeded && !failed) "finished" else "failed", result))
    }
}
