package io.github.dotnetsupport.view

import com.intellij.build.BuildContentManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.dotnetsupport.solution.SolutionService

/** Tool windows a .NET developer expects to find on the stripes of a project with a solution, the way Rider has them. */
class ToolWindowsSetup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (SolutionService.getInstance(project).solutionFiles().isEmpty()) return
        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) return@invokeLater
            keepBuildWindow(project)
            showUnitTestsButton(project)
        }
    }

    /**
     * The platform hides the Build tool window, stripe button included, as soon as its last tab is closed, and brings it
     * back only with the next build. With a solution the window is a permanent place, as in Rider.
     */
    private fun keepBuildWindow(project: Project) {
        val window = BuildContentManager.getInstance(project).getOrCreateToolWindow()
        keepAvailable(window)
        window.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                // after the platform has reacted to the empty content
                if (window.contentManager.contentCount == 0) ApplicationManager.getApplication().invokeLater({ keepAvailable(window) }, project.disposed)
            }
        })
    }

    private fun keepAvailable(window: ToolWindow) {
        window.setToHideOnEmptyContent(false)
        window.isAvailable = true
        window.isShowStripeButton = true
    }

    /** Once: afterwards the place of the button and whether it is shown belong to the user. */
    private fun showUnitTestsButton(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(UNIT_TESTS_PLACED_KEY)) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(UNIT_TESTS_ID) ?: return
        properties.setValue(UNIT_TESTS_PLACED_KEY, true)
        // a layout saved by an earlier version of the plugin kept the window in the secondary group, where it was easy to miss
        window.setAnchor(ToolWindowAnchor.LEFT, null)
        window.setSplitMode(false, null)
        window.isAvailable = true
        window.isShowStripeButton = true
    }

    companion object {
        const val UNIT_TESTS_ID = "Unit Tests"
        private const val UNIT_TESTS_PLACED_KEY = "dotnet.unit.tests.window.placed"
    }
}
