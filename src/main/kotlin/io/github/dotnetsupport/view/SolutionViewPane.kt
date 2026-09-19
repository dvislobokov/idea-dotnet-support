package io.github.dotnetsupport.view

import com.intellij.ide.SelectInTarget
import com.intellij.ide.impl.ProjectViewSelectInTarget
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.AbstractProjectViewPaneWithAsyncSupport
import com.intellij.ide.projectView.impl.ProjectAbstractTreeStructureBase
import com.intellij.ide.projectView.impl.ProjectTreeStructure
import com.intellij.ide.projectView.impl.ProjectViewTree
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.MSBUILD_EXTENSIONS
import io.github.dotnetsupport.run.DotNetRunConfigurationGenerator
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

    /** Solution and project nodes are not directories, but "New" on them means "in the directory of the solution / project". */
    override fun getSelectedDirectoriesInAmbiguousCase(userObject: Any?): Array<PsiDirectory> {
        val file = (userObject as? ProjectViewNode<*>)?.virtualFile
        val directory = file?.takeIf { it.isValid }?.let { if (it.isDirectory) it else it.parent }
        val psiDirectory = directory?.let { PsiManager.getInstance(myProject).findDirectory(it) }
        return if (psiDirectory != null) arrayOf(psiDirectory) else super.getSelectedDirectoriesInAmbiguousCase(userObject)
    }

    companion object {
        const val ID = "DotNetSolutionView"

        // Must be unique among all panes; the platform ones use small numbers.
        private const val WEIGHT = 42
    }
}

/** Rebuilds the Solution pane when a solution or MSBuild file changes on disk, or the content of a project root changes. */
class SolutionFilesListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        // New projects and launch profiles get their run configurations.
        if (events.any { affectsSolutionStructure(it.path) || it.path.endsWith("/launchSettings.json") }) {
            DotNetRunConfigurationGenerator.getInstance(project).schedule()
        }
        if (events.none { affectsSolutionStructure(it.path) || changesProjectRoot(it) }) return
        ApplicationManager.getApplication().invokeLater({
            ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID)?.updateFromRoot(true)
        }, project.disposed)
    }

    /** Something appeared in or disappeared from a project directory, whose node is not a regular directory node. */
    private fun changesProjectRoot(event: VFileEvent): Boolean {
        if (event !is VFileCreateEvent && event !is VFileDeleteEvent && event !is VFileMoveEvent && event !is VFileCopyEvent) return false
        val parents = when (event) {
            is VFileCreateEvent -> listOf(event.parent)
            is VFileCopyEvent -> listOf(event.newParent)
            is VFileMoveEvent -> listOf(event.oldParent, event.newParent)
            else -> listOfNotNull(event.file?.parent)
        }
        return parents.any { parent -> parent.isValid && parent.children.any(DotNetProjects::isProjectFile) }
    }

    private fun affectsSolutionStructure(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension in SOLUTION_EXTENSIONS || extension in MSBUILD_EXTENSIONS
    }
}
