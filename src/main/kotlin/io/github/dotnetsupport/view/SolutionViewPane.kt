package io.github.dotnetsupport.view

import com.intellij.ide.SelectInTarget
import com.intellij.ide.impl.ProjectViewSelectInTarget
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.AbstractProjectViewPaneWithAsyncSupport
import com.intellij.ide.projectView.impl.ProjectAbstractTreeStructureBase
import com.intellij.ide.projectView.impl.ProjectTreeStructure
import com.intellij.ide.projectView.impl.ProjectViewTree
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.msbuild.MSBUILD_EXTENSIONS
import io.github.dotnetsupport.solution.SOLUTION_EXTENSIONS
import javax.swing.Icon
import javax.swing.tree.DefaultTreeModel

/** Rider-like "Solution" pane of the Project tool window. */
class SolutionViewPane(project: Project) : AbstractProjectViewPaneWithAsyncSupport(project) {
    override fun getTitle(): String = "Solution"
    override fun getIcon(): Icon = DotNetIcons.Solution
    override fun getId(): String = ID
    override fun getWeight(): Int = WEIGHT

    override fun createSelectInTarget(): SelectInTarget = object : ProjectViewSelectInTarget(myProject) {
        override fun toString(): String = title
        override fun getMinorViewId(): String = ID
        override fun getWeight(): Float = WEIGHT.toFloat()
    }

    override fun createStructure(): ProjectAbstractTreeStructureBase = object : ProjectTreeStructure(myProject, ID) {
        override fun createRoot(project: Project, settings: ViewSettings): AbstractTreeNode<*> =
            SolutionRootNode(project, settings)
    }

    override fun createTree(treeModel: DefaultTreeModel): ProjectViewTree = object : ProjectViewTree(treeModel) {
        override fun toString(): String = "$title ${super.toString()}"
    }

    companion object {
        const val ID = "DotNetSolutionView"

        // Must be unique among all panes; the platform ones use small numbers.
        private const val WEIGHT = 42
    }
}

/** Rebuilds the Solution pane when a solution or MSBuild file changes on disk. */
class SolutionFilesListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.none { affectsSolutionStructure(it.path) }) return
        ApplicationManager.getApplication().invokeLater({
            ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID)?.updateFromRoot(true)
        }, project.disposed)
    }

    private fun affectsSolutionStructure(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension in SOLUTION_EXTENSIONS || extension in MSBUILD_EXTENSIONS
    }
}
