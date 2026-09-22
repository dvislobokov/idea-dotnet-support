package io.github.dotnetsupport.build

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.solution.SolutionService

/** Build / Rebuild / Clean of the selected solution or project. */
abstract class BuildSelectedAction(private val command: DotNetBuildCommand) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = SolutionContext.buildTarget(e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = "${command.title} '${target.nameWithoutExtension}'"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = SolutionContext.buildTarget(e) ?: return
        DotNetBuildService.getInstance(project).run(target, command)
    }
}

class BuildSelected : BuildSelectedAction(DotNetBuildCommand.BUILD)
class RebuildSelected : BuildSelectedAction(DotNetBuildCommand.REBUILD)
class CleanSelected : BuildSelectedAction(DotNetBuildCommand.CLEAN)

/** Solution-wide commands of the main menu. */
abstract class BuildSolutionAction(private val command: DotNetBuildCommand) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = solutionFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DotNetBuildService.getInstance(project).run(solutionFile(e) ?: return, command)
    }

    private fun solutionFile(e: AnActionEvent): VirtualFile? =
        e.project?.let { SolutionService.getInstance(it).solutionFiles().firstOrNull() }
}

class BuildSolution : BuildSolutionAction(DotNetBuildCommand.BUILD)
class RebuildSolution : BuildSolutionAction(DotNetBuildCommand.REBUILD)
class CleanSolution : BuildSolutionAction(DotNetBuildCommand.CLEAN)
class RestoreSolution : BuildSolutionAction(DotNetBuildCommand.RESTORE)

/** "Rerun" button of the Build tool window. */
internal class RerunBuildAction(private val target: VirtualFile, private val rerun: () -> Unit) :
    AnAction("Rerun", null, AllIcons.Actions.Rerun), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = target.isValid
    }

    override fun actionPerformed(e: AnActionEvent) = rerun()
}
