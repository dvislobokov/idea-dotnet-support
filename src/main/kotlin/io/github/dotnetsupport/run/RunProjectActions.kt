package io.github.dotnetsupport.run

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService

/**
 * Run / Debug of the project selected in the Solution view (or the one that owns the current file). A node of the
 * Solution view is not a file, so the context actions of the platform do not show up on it; these do the same:
 * reuse the run configuration of the project, or make a temporary one.
 */
abstract class RunProjectAction(private val verb: String, private val executor: () -> Executor) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val target = project?.let { RunProjectTarget.from(it, e) }
        // The main menu keeps its items in place. A popup has them on project nodes only: files get Run from the platform.
        e.presentation.isVisible = !e.isFromContextMenu || (target != null && SolutionContext.fromSelection(e) != null)
        e.presentation.text = if (target != null) "$verb '${target.projectFile.nameWithoutExtension}'" else "$verb Project"
        e.presentation.isEnabled = target != null && ProgramRunner.getRunner(executor().id, target.settings(project).configuration) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = RunProjectTarget.from(project, e)?.settings(project) ?: return
        val runManager = RunManager.getInstance(project)
        if (runManager.allSettings.none { it === settings }) runManager.setTemporaryConfiguration(settings)
        runManager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, executor())
    }
}

class RunSelectedProjectAction : RunProjectAction("Run", DefaultRunExecutor::getRunExecutorInstance)

/** Enabled once something can debug a .NET run configuration. */
class DebugSelectedProjectAction : RunProjectAction("Debug", DefaultDebugExecutor::getDebugExecutorInstance)

/** An executable or a test project with the command that runs it. */
class RunProjectTarget(val projectFile: VirtualFile, val command: DotNetCommand) {
    /** The selected configuration when it runs this project, else any that does, else a new one that is not registered yet. */
    fun settings(project: Project): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        fun runsIt(settings: RunnerAndConfigurationSettings?): Boolean {
            val options = (settings?.configuration as? DotNetRunConfiguration)?.options ?: return false
            return options.projectPath == projectFile.path && options.command == command && options.testFilter.isNullOrBlank()
        }
        runManager.selectedConfiguration?.takeIf(::runsIt)?.let { return it }
        runManager.allSettings.firstOrNull(::runsIt)?.let { return it }

        val profile = if (command == DotNetCommand.RUN) LaunchSettings.profiles(projectFile).firstOrNull() else null
        val name = projectFile.nameWithoutExtension + profile?.let { ": ${it.name}" }.orEmpty()
        return runManager.createConfiguration(name, DotNetConfigurationType.instance.factory).also { settings ->
            (settings.configuration as DotNetRunConfiguration).options.apply {
                projectPath = projectFile.path
                command = this@RunProjectTarget.command
                launchProfile = profile?.name
                openBrowser = profile?.launchBrowser == true
            }
        }
    }

    companion object {
        fun from(project: Project, e: AnActionEvent): RunProjectTarget? {
            val selection = SolutionContext.fromSelection(e)
            // a solution or a solution folder is selected: nothing to guess from the file behind it
            if (selection != null && selection.project == null) return null
            val projectFile = selection?.projectFile
                ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(DotNetProjects::findOwningProject)
                ?: return null
            val msBuildProject = SolutionService.getInstance(project).msBuildProject(projectFile)
            return when {
                msBuildProject.isTestProject -> RunProjectTarget(projectFile, DotNetCommand.TEST)
                msBuildProject.isRunnable -> RunProjectTarget(projectFile, DotNetCommand.RUN)
                else -> null
            }
        }
    }
}
