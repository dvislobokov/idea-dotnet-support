package io.github.dotnetsupport.run

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.resolveFile

/**
 * Run / Debug of the project selected in the Solution view (or the one that owns the current file). A node of the
 * Solution view is not a file, so the context actions of the platform do not show up on it; these do the same:
 * reuse the run configuration of the project, or make a temporary one.
 */
abstract class RunProjectAction(private val verb: String, private val executor: () -> Executor) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val several = project?.let { RunProjectTarget.allSelected(it, e) }.orEmpty()
        if (several.size > 1) {
            e.presentation.isEnabledAndVisible = several.all { ProgramRunner.getRunner(executor().id, it.settings(project!!).configuration) != null }
            e.presentation.text = "$verb ${several.size} Projects"
            return
        }
        val target = project?.let { RunProjectTarget.from(it, e) }
        // The main menu keeps its items in place. A popup has them on project nodes only: files get Run from the platform.
        e.presentation.isVisible = !e.isFromContextMenu || (target != null && SolutionContext.fromSelection(e) != null)
        e.presentation.text = if (target != null) "$verb '${target.projectFile.nameWithoutExtension}'" else "$verb Project"
        e.presentation.isEnabled = target != null && ProgramRunner.getRunner(executor().id, target.settings(project).configuration) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val several = RunProjectTarget.allSelected(project, e)
        if (several.size > 1) return launchTogether(project, several)
        val settings = RunProjectTarget.from(project, e)?.settings(project) ?: return
        register(project, settings)
        RunManager.getInstance(project).selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, executor())
    }

    private fun register(project: Project, settings: RunnerAndConfigurationSettings) {
        val runManager = RunManager.getInstance(project)
        if (runManager.allSettings.none { it === settings }) runManager.setTemporaryConfiguration(settings)
    }

    /**
     * Several projects selected in the Solution view, as Rider's "Run Multiple Projects". Their builds would fight over the shared
     * dependencies, so the projects are built one after another first, and `dotnet run` does not build again; a debug launch builds
     * before it starts anyway, and those builds wait for each other (see [DotNetDebugBuild.build]).
     */
    private fun launchTogether(project: Project, targets: List<RunProjectTarget>) {
        val executor = executor()
        val settings = targets.map { it.settings(project).also { settings -> register(project, settings) } }
        fun launch(prebuilt: Boolean) = settings.forEach { each ->
            val environment = ExecutionEnvironmentBuilder.createOrNull(executor, each)?.build() ?: return@forEach
            if (prebuilt) environment.putUserData(DotNetRunConfiguration.PREBUILT, true)
            ProgramRunnerUtil.executeConfiguration(environment, false, true)
        }
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) return launch(prebuilt = false)
        object : Task.Backgroundable(project, "Building ${targets.size} projects", true) {
            override fun run(indicator: ProgressIndicator) {
                for (target in targets) {
                    indicator.checkCanceled()
                    indicator.text = "Building ${target.projectFile.name}"
                    if (!DotNetDebugBuild.build(project, target.projectFile)) return
                }
                ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) launch(prebuilt = true) }, ModalityState.nonModal())
            }
        }.queue()
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
        /** The runnable and test projects among the selected nodes of the Solution view; empty when anything else is selected too. */
        fun allSelected(project: Project, e: AnActionEvent): List<RunProjectTarget> {
            val items = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.takeIf { it.size > 1 } ?: return emptyList()
            val keys = items.map { (it as? AbstractTreeNode<*>)?.value ?: it }
            if (keys.any { it !is ProjectKey }) return emptyList()
            return keys.mapNotNull { key -> (key as ProjectKey).project.resolveFile(key.solutionFile)?.let { of(project, it) } }
        }

        private fun of(project: Project, projectFile: VirtualFile): RunProjectTarget? {
            val msBuildProject = SolutionService.getInstance(project).msBuildProject(projectFile)
            return when {
                msBuildProject.isTestProject -> RunProjectTarget(projectFile, DotNetCommand.TEST)
                msBuildProject.isRunnable -> RunProjectTarget(projectFile, DotNetCommand.RUN)
                else -> null
            }
        }

        fun from(project: Project, e: AnActionEvent): RunProjectTarget? {
            val selection = SolutionContext.fromSelection(e)
            // a solution or a solution folder is selected: nothing to guess from the file behind it
            if (selection != null && selection.project == null) return null
            val projectFile = selection?.projectFile
                ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(DotNetProjects::findOwningProject)
                ?: return null
            return of(project, projectFile)
        }
    }
}
