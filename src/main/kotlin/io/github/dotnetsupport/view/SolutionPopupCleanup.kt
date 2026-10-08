package io.github.dotnetsupport.view

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Anchor
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import com.intellij.openapi.project.DumbAware

/**
 * Rider has no grey Cut / Copy / Paste and no second Rename on a solution, a solution folder or a project: the platform ones act on files and
 * are disabled there, and Rename… duplicates Rename Project…. The platform actions are not ours to remove, so in `ProjectViewPopupMenu` each is
 * replaced with a [HideOnSolutionNodes] group around the same action: files and ordinary folders keep them untouched.
 */
class SolutionPopupCleanup : DynamicActionConfigurationCustomizer {
    override fun registerActions(actionManager: ActionManager) {
        val popup = actionManager.getAction(POPUP) as? DefaultActionGroup ?: return
        for ((id, before) in WRAPPED) {
            // by id: a child may still be a stub of an action not created yet
            val child = popup.childActionsOrStubs.firstOrNull { actionManager.getId(it) == id } ?: continue
            val action = actionManager.getAction(id) ?: continue
            popup.remove(child, actionManager)
            popup.addAction(HideOnSolutionNodes(action), Constraints(Anchor.BEFORE, before), actionManager)
        }
    }

    override fun unregisterActions(actionManager: ActionManager) {
        val popup = actionManager.getAction(POPUP) as? DefaultActionGroup ?: return
        for (wrapper in popup.childActionsOrStubs.filterIsInstance<HideOnSolutionNodes>()) {
            val before = WRAPPED.firstOrNull { actionManager.getAction(it.first) === wrapper.inner }?.second
            popup.remove(wrapper, actionManager)
            popup.addAction(wrapper.inner, before?.let { Constraints(Anchor.BEFORE, it) } ?: Constraints.FIRST, actionManager)
        }
    }

    companion object {
        const val POPUP = "ProjectViewPopupMenu"

        /** The action and the sibling it stands before in the popup, so the wrapper keeps its place. */
        val WRAPPED = listOf("CutCopyPasteGroup" to "ProjectViewEditSource", "RenameElement" to "ProjectViewPopupMenuRefactoringGroup")

        /** True when everything selected is a solution, a solution folder or a project node. */
        fun onlySolutionNodes(e: AnActionEvent): Boolean {
            val items = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.takeIf { it.isNotEmpty() } ?: return false
            return items.all { item ->
                when ((item as? AbstractTreeNode<*>)?.value ?: item) {
                    is SolutionKey, is SolutionFolderKey, is ProjectKey -> true
                    else -> false
                }
            }
        }
    }
}

/** Shows [inner] as it is, except on solution, solution folder and project nodes. */
class HideOnSolutionNodes(val inner: AnAction) : DefaultActionGroup(inner), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = !SolutionPopupCleanup.onlySolutionNodes(e)
    }
}
