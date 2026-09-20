package io.github.dotnetsupport.view

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.solution.SolutionService

/** Switches the Project tool window to the Solution pane the first time a directory with a solution is opened. */
class SolutionViewActivator : ProjectActivity {
    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(ACTIVATED_KEY) || !DotNetSettings.getInstance().switchToSolutionView) return
        if (SolutionService.getInstance(project).solutionFiles().isEmpty()) return

        // Only once: after that the choice of the pane belongs to the user.
        properties.setValue(ACTIVATED_KEY, true)
        ToolWindowManager.getInstance(project).invokeLater {
            val projectView = ProjectView.getInstance(project)
            if (projectView.getProjectViewPaneById(SolutionViewPane.ID) != null) projectView.changeView(SolutionViewPane.ID)
        }
    }

    private companion object {
        const val ACTIVATED_KEY = "dotnet.solution.view.activated"
    }
}
