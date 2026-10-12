package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.solution.SlnProject
import io.github.dotnetsupport.view.HideOnSolutionNodes
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.SolutionKey

/** The popup of a project node: the everyday commands on top, the rarer under Tools, no platform Cut / Copy / Paste and second Rename. */
class SolutionViewPopupTest : BasePlatformTestCase() {
    private val actions get() = ActionManager.getInstance()

    private fun ids(group: String) = (actions.getAction(group) as DefaultActionGroup).childActionsOrStubs.mapNotNull { actions.getId(it) }

    fun testTheRarerCommandsAreUnderTools() {
        val top = ids("DotNet.SolutionViewPopup")
        val tools = ids("DotNet.ProjectTools")
        assertEquals(
            listOf("DotNet.Publish", "DotNet.RunMsBuildTarget", "DotNet.AnalyzeUpgrade", "DotNet.CalculateCodeMetrics", "DotNet.CodeAnalysis",
                "DotNet.FormatSelected", "DotNet.VerifyFormatting"),
            tools,
        )
        for (id in tools - "DotNet.CodeAnalysis") assertFalse(id, id in top)
        assertTrue("DotNet.ProjectTools" in top)
        for (id in listOf("DotNet.AddToSolution", "DotNet.BuildSelected", "DotNet.RebuildSelected", "DotNet.CleanSelected", "DotNet.RunProject", "DotNet.DebugProject",
            "DotNet.ManageNuGet", "DotNet.EditProjectFile", "DotNet.ProjectProperties", "DotNet.ReloadProject", "DotNet.RenameProject",
            "DotNet.MoveToSolutionFolder", "DotNet.RemoveFromSolution")) assertTrue(id, id in top)
    }

    fun testPlatformCutCopyPasteAndRenameAreHiddenOnSolutionNodesOnly() {
        val popup = actions.getAction("ProjectViewPopupMenu") as DefaultActionGroup
        val wrappers = popup.childActionsOrStubs.filterIsInstance<HideOnSolutionNodes>()
        assertEquals(listOf("CutCopyPasteGroup", "RenameElement"), wrappers.map { actions.getId(it.inner) })

        val sln = myFixture.addFileToProject("popup/App.sln", "").virtualFile
        val slnProject = SlnProject("App", "App/App.csproj", "{1}")
        fun visible(item: Any?): Boolean {
            val wrapper = wrappers.first()
            val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            if (item != null) builder.add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(item))
            val event = AnActionEvent.createEvent(wrapper, builder.build(), null, "ProjectViewPopup", ActionUiKind.POPUP, null)
            wrapper.update(event)
            return event.presentation.isVisible
        }
        assertFalse(visible(ProjectKey(sln, slnProject)))
        assertFalse(visible(SolutionKey(sln)))
        assertTrue("a file keeps them", visible(sln))
        assertTrue(visible(null))
    }
}
