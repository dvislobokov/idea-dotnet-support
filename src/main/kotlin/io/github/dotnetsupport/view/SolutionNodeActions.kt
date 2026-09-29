package io.github.dotnetsupport.view

import com.intellij.ide.DeleteProvider
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import io.github.dotnetsupport.actions.RemoveProjectFromSolutionAction
import io.github.dotnetsupport.actions.RenameProjectAction
import io.github.dotnetsupport.actions.SolutionContext

/*
 * The keys of the tree do their share of the standard actions: F2 on a project is Rename Project, Delete on a project is
 * Remove from Solution (the files stay on disk). The platform asks its rename handlers and the delete provider of the pane.
 */

/** The selected project node, when exactly one is selected. */
internal fun selectedProjectKey(dataContext: DataContext): ProjectKey? {
    val item = dataContext.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.singleOrNull() ?: return null
    return ((item as? AbstractTreeNode<*>)?.value ?: item) as? ProjectKey
}

/** Shift+F6 / F2 on the node of a project: the Rename Project dialog. */
class ProjectNodeRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean =
        dataContext.getData(CommonDataKeys.PROJECT) != null && selectedProjectKey(dataContext) != null && !SolutionContext(selectedProjectKey(dataContext)!!.solutionFile).isFilter

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        val key = dataContext?.let(::selectedProjectKey) ?: return
        RenameProjectAction.renameWithDialog(project, SolutionContext(key.solutionFile, project = key.project))
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = invoke(project, null, null, dataContext)
}

/** Delete on the node of a project: Remove from Solution, with its question; the files are not touched. */
class ProjectNodeDeleteProvider : DeleteProvider {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun canDeleteElement(dataContext: DataContext): Boolean =
        selectedProjectKey(dataContext)?.let { !SolutionContext(it.solutionFile).isFilter } == true

    override fun deleteElement(dataContext: DataContext) {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return
        val key = selectedProjectKey(dataContext) ?: return
        RemoveProjectFromSolutionAction.removeWithQuestion(project, SolutionContext(key.solutionFile, project = key.project))
    }
}
