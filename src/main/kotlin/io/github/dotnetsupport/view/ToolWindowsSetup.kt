package io.github.dotnetsupport.view

import com.intellij.build.BuildContentManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.dotnetsupport.monitor.MonitorToolWindowFactory
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.testing.UnitTestsWindowDecoration

/** Tool windows a .NET developer expects to find on the stripes of a project with a solution, the way Rider has them. */
class ToolWindowsSetup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (SolutionService.getInstance(project).solutionFiles().isEmpty()) return
        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) return@invokeLater
            keepBuildWindow(project)
            showUnitTestsButton(project)
            moveMonitorToTheRight(project)
            keepUnitTestsDecoration(project)
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

    /** The first version of the monitor lived at the bottom; a layout saved then would keep it there. Once. */
    private fun moveMonitorToTheRight(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(MONITOR_PLACED_KEY)) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(MonitorToolWindowFactory.ID) ?: return
        properties.setValue(MONITOR_PLACED_KEY, true)
        if (window.anchor != ToolWindowAnchor.RIGHT) window.setAnchor(ToolWindowAnchor.RIGHT, null)
    }

    /** A safety net for [io.github.dotnetsupport.testing.DotNetTestRunner]: a leftover "Run" title is undone whenever the tool windows change. */
    private fun keepUnitTestsDecoration(project: Project) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(UNIT_TESTS_ID) ?: return
        project.messageBus.connect(window.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                if (UnitTestsWindowDecoration.isOverridden(project)) UnitTestsWindowDecoration.restore(project, isRunning = false)
            }
        })
    }

    private fun keepAvailable(window: ToolWindow) {
        window.setToHideOnEmptyContent(false)
        if (!window.isAvailable) window.isAvailable = true
    }

    /**
     * Layouts saved by the earlier versions of the plugin keep the window on the left; once, it is moved to the bottom,
     * where Rider has it (the Explorer tab and the sessions of the test runs). Afterwards its place belongs to the user.
     * The stripe button itself needs no help: the platform creates it at registration.
     */
    private fun showUnitTestsButton(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(UNIT_TESTS_PLACED_KEY)) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(UNIT_TESTS_ID) ?: return
        properties.setValue(UNIT_TESTS_PLACED_KEY, true)
        // layouts saved by the earlier versions of the plugin had it on the left
        window.setAnchor(ToolWindowAnchor.BOTTOM, null)
        window.setSplitMode(false, null)
        if (!window.isAvailable) window.isAvailable = true
    }

    companion object {
        const val UNIT_TESTS_ID = "Unit Tests"
        private const val MONITOR_PLACED_KEY = "dotnet.monitor.window.on.the.right"
        private const val UNIT_TESTS_PLACED_KEY = "dotnet.unit.tests.window.at.bottom"
    }
}
