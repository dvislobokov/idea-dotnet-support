package io.github.dotnetsupport.view

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

object SolutionViewSettings {
    private const val SHOW_ALL_FILES = "dotnet.solution.view.showAllFiles"

    fun isShowAllFiles(project: Project): Boolean = PropertiesComponent.getInstance(project).getBoolean(SHOW_ALL_FILES)

    fun setShowAllFiles(project: Project, value: Boolean) {
        PropertiesComponent.getInstance(project).setValue(SHOW_ALL_FILES, value)
        ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID)?.updateFromRoot(true)
    }
}

/** View options of the Project tool window, offered while the Solution pane is the current one. */
class ShowAllFilesAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = e.project?.let { ProjectView.getInstance(it).currentViewId } == SolutionViewPane.ID
    }

    override fun isSelected(e: AnActionEvent): Boolean = e.project?.let(SolutionViewSettings::isShowAllFiles) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.let { SolutionViewSettings.setShowAllFiles(it, state) }
    }
}
