package io.github.dotnetsupport

import com.intellij.ide.projectView.ViewSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.view.DotNetProjectNode
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.SolutionNode
import io.github.dotnetsupport.view.SolutionViewPane
import io.github.dotnetsupport.solution.SolutionService

class EditProjectFileTest : BasePlatformTestCase() {
    /** Stands for the private drag source of the platform: the pane recognizes it by the class name on the stack. */
    private class FakeDragSource(private val pane: SolutionViewPane) {
        fun elements(node: Any): List<PsiElement> = pane.getElementsFromNode(node)
    }

    fun testEditActionAndDragOfProjectAndSolutionNodes() {
        val projectFile = myFixture.addFileToProject("EditMe/EditMe.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>").virtualFile
        val program = myFixture.addFileToProject("EditMe/Program.cs", "class Program { }").virtualFile
        val solutionFile = myFixture.addFileToProject("EditMe.slnx", "<Solution><Project Path=\"EditMe/EditMe.csproj\"/></Solution>").virtualFile
        val pane = SolutionViewPane(project)
        try {
            val slnProject = SolutionService.getInstance(project).solution(solutionFile).allProjects.single()
            val solutionNode = SolutionNode(project, SolutionKey(solutionFile), ViewSettings.DEFAULT)
            val projectNode = DotNetProjectNode(project, ProjectKey(solutionFile, slnProject), ViewSettings.DEFAULT)

            val action = ActionManager.getInstance().getAction("DotNet.EditProjectFile")
            fun textFor(selected: Any): String? {
                val context = SimpleDataContext.builder().add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.SELECTED_ITEMS, arrayOf(selected)).build()
                val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, null)
                action.update(event)
                return event.presentation.text.takeIf { event.presentation.isEnabledAndVisible }
            }
            assertEquals("Edit 'EditMe.csproj'", textFor(projectNode))
            assertEquals("Edit 'EditMe.slnx'", textFor(solutionNode))
            assertNull("a file inside the project opens by itself", textFor(psiManager.findFile(program)!!))

            // the drag source of the project view starts a drag for PSI-based nodes only
            assertTrue(projectNode is com.intellij.ide.projectView.impl.nodes.AbstractPsiBasedNode<*> && solutionNode is com.intellij.ide.projectView.impl.nodes.AbstractPsiBasedNode<*>)
            assertEquals(projectFile, projectNode.virtualFile)
            assertTrue(projectNode.canNavigate())
            // a project of the solution that is not on disk stays in the tree and says so
            val missing = DotNetProjectNode(project, ProjectKey(solutionFile, io.github.dotnetsupport.solution.SlnProject("Gone", "Gone/Gone.csproj", "id")), ViewSettings.DEFAULT)
            missing.update()
            assertTrue(missing.presentation.locationString.orEmpty().startsWith("not found"))
            assertTrue(missing.children.isEmpty())

            // dragged into the editor the node is its file...
            assertEquals(listOf(projectFile), FakeDragSource(pane).elements(projectNode).map { it.containingFile.virtualFile })
            assertEquals(listOf(solutionFile), FakeDragSource(pane).elements(solutionNode).map { it.containingFile.virtualFile })
            // ...but not for Delete, Rename or Move, which would take the PSI of the selection the same way
            assertTrue(pane.getElementsFromNode(projectNode).isEmpty())

            // the eye of Rider: Show All Files among the title buttons of the Project tool window
            val titleButtons = ActionManager.getInstance().getAction("ProjectViewToolbar") as DefaultActionGroup
            assertTrue(titleButtons.childActionsOrStubs.any { ActionManager.getInstance().getId(it) == "DotNet.ShowAllFiles" })

            val popup = ActionManager.getInstance().getAction("DotNet.SolutionViewPopup") as DefaultActionGroup
            assertTrue(popup.childActionsOrStubs.any { ActionManager.getInstance().getId(it) == "DotNet.EditProjectFile" })
        } finally {
            Disposer.dispose(pane)
            runWriteAction { solutionFile.delete(this) }
        }
    }
}
