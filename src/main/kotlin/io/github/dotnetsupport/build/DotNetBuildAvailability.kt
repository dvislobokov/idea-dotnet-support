package io.github.dotnetsupport.build

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.solution.SolutionLayout
import io.github.dotnetsupport.solution.SolutionService

/**
 * Whether the plugin builds and runs a solution: a framework may say it does not ([SolutionLayout.buildUnavailable]) — Unity compiles
 * the scripts and runs the game in its editor, `dotnet build` / `dotnet run` of its generated projects make nothing anybody uses. The
 * actions of building and running ask here: off in the main menu with the reason, hidden in context menus and the toolbar.
 */
object DotNetBuildAvailability {
    /** Why [file] (a solution or a project file, in the folder of its solution) is not built or run by the plugin; null when it is. */
    fun unavailable(file: VirtualFile?): String? = file?.parent?.let(SolutionLayout::buildUnavailable)

    /** The same for the solution of [project]. */
    fun unavailable(project: Project): String? = unavailable(SolutionService.getInstance(project).solutionFiles().firstOrNull())

    /**
     * Applies [reason] to the presentation of an action: hidden in a context menu, visible and off with the reason in the main menu (the
     * plugin's rule for the actions that do not apply); true when it did, and the action has nothing more to update.
     */
    fun disable(e: AnActionEvent, reason: String?): Boolean {
        reason ?: return false
        if (e.isFromContextMenu) {
            e.presentation.isEnabledAndVisible = false
        } else {
            e.presentation.isVisible = true
            e.presentation.isEnabled = false
            e.presentation.description = reason
        }
        return true
    }
}
