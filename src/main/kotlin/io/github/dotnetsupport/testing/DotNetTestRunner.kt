package io.github.dotnetsupport.testing

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import io.github.dotnetsupport.run.DotNetCommand
import io.github.dotnetsupport.run.DotNetProcessAttacher
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.view.ToolWindowsSetup

/**
 * Runs `dotnet test` configurations like the default runner does, but sends the results to the Unit Tests tool window
 * instead of Run: every run is a session tab next to the Explorer, as in Rider.
 */
class DotNetTestRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "DotNetTestRunner"

    /** Debug is the same run with the test host waiting for a debugger, so it needs somebody to attach one. */
    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        profile is DotNetRunConfiguration && profile.options.command == DotNetCommand.TEST &&
            (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID && DotNetProcessAttacher.find() != null)

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        com.intellij.openapi.application.WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
        val result = state.execute(environment.executor, this) ?: return null
        val project = environment.project
        val descriptor = RunContentBuilder(result, environment).showRunContent(sessionToReuse(project, environment.runProfile.name) ?: environment.contentToReuse)
        // The run content manager accepts any tool window whose content manager exists; otherwise it falls back to Run.
        if (ToolWindowManager.getInstance(project).getToolWindow(ToolWindowsSetup.UNIT_TESTS_ID)?.contentManager != null) {
            descriptor.contentToolWindowId = ToolWindowsSetup.UNIT_TESTS_ID
            keepWindowDecoration(project, result.processHandler)
        }
        return descriptor
    }

    /**
     * The run content manager dresses the window that receives the content as the window of the executor: the stripe
     * title becomes "Run" and the icon becomes the Run icon, again at the start and at the end of the process. Every
     * time our title and the flask are put back, after the platform has had its turn in the event queue.
     */
    private fun keepWindowDecoration(project: Project, process: ProcessHandler?) {
        fun restoreLater(isRunning: Boolean) = ApplicationManager.getApplication().invokeLater({
            ApplicationManager.getApplication().invokeLater({ UnitTestsWindowDecoration.restore(project, isRunning) }, project.disposed)
        }, project.disposed)

        restoreLater(process?.isProcessTerminated == false)
        process?.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) = restoreLater(true)
            override fun processTerminated(event: ProcessEvent) = restoreLater(false)
        })
    }

    /**
     * A finished, unpinned session of the same configuration: its tab is reused, otherwise every rerun would add one.
     * The platform looks for tabs to reuse only in the tool window of the executor.
     */
    private fun sessionToReuse(project: Project, name: String): RunContentDescriptor? {
        val contents = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowsSetup.UNIT_TESTS_ID)?.contentManagerIfCreated?.contents.orEmpty()
        return contents.filter { !it.isPinned }
            .mapNotNull { RunContentManagerImpl.getRunContentDescriptorByContent(it) }
            .firstOrNull { it.displayName == name && it.processHandler?.isProcessTerminated != false }
    }
}

/** Title and icon of the Unit Tests tool window, see [DotNetTestRunner]. */
object UnitTestsWindowDecoration {
    private val ICON = IconLoader.getIcon("/icons/unitTestsToolWindow.svg", UnitTestsWindowDecoration::class.java)

    fun restore(project: Project, isRunning: Boolean) {
        if (project.isDisposed) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowsSetup.UNIT_TESTS_ID) ?: return
        window.stripeTitle = ToolWindowsSetup.UNIT_TESTS_ID
        // the green dot of a running process, as on the Run button
        window.setIcon(if (isRunning) ExecutionUtil.getLiveIndicator(ICON) else ICON)
    }

    /** True when the platform has dressed the window as "Run" and nobody has undone it yet. */
    fun isOverridden(project: Project): Boolean =
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowsSetup.UNIT_TESTS_ID)?.stripeTitle?.let { it != ToolWindowsSetup.UNIT_TESTS_ID } == true
}
