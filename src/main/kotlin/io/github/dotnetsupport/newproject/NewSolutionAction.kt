package io.github.dotnetsupport.newproject

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * File | New | New Solution..., .NET | New Solution... and New Solution of the Welcome screen: the New Solution window of Rider
 * ([NewSolutionDialog]). Works without a project: the new solution opens as a project of its own.
 */
class NewSolutionAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // File | New and the New of the Project view popup are the same group: a new solution has nothing to do with the selected folder
        e.presentation.isEnabledAndVisible = !e.isFromContextMenu
    }

    override fun actionPerformed(e: AnActionEvent) {
        NewSolutionDialog(e.project).show()
    }
}
