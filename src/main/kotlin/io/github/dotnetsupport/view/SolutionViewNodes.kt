package io.github.dotnetsupport.view

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.ProjectViewDirectoryHelper
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.solution.SlnFolder
import io.github.dotnetsupport.solution.SlnProject
import io.github.dotnetsupport.solution.Solution
import io.github.dotnetsupport.solution.SolutionService

/*
 * Node values are small immutable keys, not the parsed model: the tree matches nodes by value when it is
 * rebuilt, so a stable key keeps expansion and selection, and the model is looked up again on every update.
 */

data class SolutionKey(val solutionFile: VirtualFile)
data class SolutionFolderKey(val solutionFile: VirtualFile, val folderId: String)
data class ProjectKey(val solutionFile: VirtualFile, val project: SlnProject)

private const val FOLDER_WEIGHT = 1
private const val PROJECT_WEIGHT = 2

private val HIDDEN_PROJECT_DIRECTORIES = setOf("bin", "obj")

abstract class SolutionViewNode<T : Any>(project: Project, value: T, settings: ViewSettings?) :
    ProjectViewNode<T>(project, value, settings) {

    protected val nodeProject: Project get() = project!!
    protected val solutions: SolutionService get() = SolutionService.getInstance(nodeProject)

    /** File opened by "Jump to Source" on this node. */
    protected open val navigationFile: VirtualFile? get() = null

    override fun canNavigate(): Boolean = navigationFile != null
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun navigate(requestFocus: Boolean) {
        navigationFile?.let { OpenFileDescriptor(nodeProject, it).navigate(requestFocus) }
    }

    protected fun folderChildren(solutionFile: VirtualFile, folder: SlnFolder): List<AbstractTreeNode<*>> {
        val result = ArrayList<AbstractTreeNode<*>>()
        folder.folders.mapTo(result) { SolutionFolderNode(nodeProject, SolutionFolderKey(solutionFile, it.id), settings) }
        folder.projects.mapTo(result) { DotNetProjectNode(nodeProject, ProjectKey(solutionFile, it), settings) }
        val psiManager = PsiManager.getInstance(nodeProject)
        folder.files
            .mapNotNull { solutionFile.parent?.findFileByRelativePath(it) }
            .mapNotNull { psiManager.findFile(it) }
            .mapTo(result) { PsiFileNode(nodeProject, it, settings) }
        return result
    }
}

fun SlnProject.resolveFile(solutionFile: VirtualFile): VirtualFile? =
    solutionFile.parent?.findFileByRelativePath(path)?.takeIf { !it.isDirectory }

private fun SlnFolder.contains(solutionFile: VirtualFile, file: VirtualFile): Boolean =
    projects.any { project -> project.resolveFile(solutionFile)?.parent?.let { VfsUtilCore.isAncestor(it, file, false) } == true } ||
        files.any { solutionFile.parent?.findFileByRelativePath(it) == file } ||
        folders.any { it.contains(solutionFile, file) }

class SolutionRootNode(project: Project, settings: ViewSettings?) : SolutionViewNode<Project>(project, project, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val solutionFiles = solutions.solutionFiles()
        if (solutionFiles.isNotEmpty()) return solutionFiles.map { SolutionNode(nodeProject, SolutionKey(it), settings) }

        // Not a .NET directory: behave like the regular project view.
        val baseDir = nodeProject.guessProjectDir()?.let { PsiManager.getInstance(nodeProject).findDirectory(it) }
        return listOfNotNull(baseDir?.let { PsiDirectoryNode(nodeProject, it, settings) })
    }

    override fun contains(file: VirtualFile): Boolean = true
    override fun update(presentation: PresentationData) {}
}

class SolutionNode(project: Project, key: SolutionKey, settings: ViewSettings?) :
    SolutionViewNode<SolutionKey>(project, key, settings) {

    private val solution: Solution get() = solutions.solution(value.solutionFile)
    override val navigationFile: VirtualFile get() = value.solutionFile
    override fun getVirtualFile(): VirtualFile = value.solutionFile

    override fun getChildren(): Collection<AbstractTreeNode<*>> = folderChildren(value.solutionFile, solution.root)

    override fun contains(file: VirtualFile): Boolean =
        value.solutionFile.parent?.let { VfsUtilCore.isAncestor(it, file, false) } == true ||
            solution.root.contains(value.solutionFile, file)

    override fun update(presentation: PresentationData) {
        val count = solution.allProjects.size
        presentation.setIcon(DotNetIcons.Solution)
        presentation.presentableText = value.solutionFile.nameWithoutExtension
        presentation.locationString = if (count == 1) "1 project" else "$count projects"
    }
}

class SolutionFolderNode(project: Project, key: SolutionFolderKey, settings: ViewSettings?) :
    SolutionViewNode<SolutionFolderKey>(project, key, settings) {

    private val folder: SlnFolder? get() = solutions.solution(value.solutionFile).findFolder(value.folderId)

    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        folder?.let { folderChildren(value.solutionFile, it) }.orEmpty()

    override fun contains(file: VirtualFile): Boolean = folder?.contains(value.solutionFile, file) == true
    override fun getTypeSortWeight(sortByType: Boolean): Int = FOLDER_WEIGHT

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.Folder)
        presentation.presentableText = folder?.name ?: value.folderId
    }
}

class DotNetProjectNode(project: Project, key: ProjectKey, settings: ViewSettings?) :
    SolutionViewNode<ProjectKey>(project, key, settings) {

    private val solutionFile: VirtualFile get() = value.solutionFile
    private val projectFile: VirtualFile? get() = value.project.resolveFile(solutionFile)
    override val navigationFile: VirtualFile? get() = projectFile
    override fun getVirtualFile(): VirtualFile? = projectFile

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val projectFile = projectFile ?: return emptyList()
        val directory = projectFile.parent?.let { PsiManager.getInstance(nodeProject).findDirectory(it) } ?: return emptyList()

        // Projects nested into this project's directory are shown as separate nodes of the solution.
        val otherProjectDirs = solutions.solution(solutionFile).allProjects
            .mapNotNullTo(HashSet()) { it.resolveFile(solutionFile)?.parent }
            .apply { remove(projectFile.parent) }

        val result = ArrayList<AbstractTreeNode<*>>()
        result += DependenciesNode(nodeProject, DependenciesKey(projectFile), settings)
        ProjectViewDirectoryHelper.getInstance(nodeProject).getDirectoryChildren(directory, settings, true).filterTo(result) { child ->
            val file = (child as? ProjectViewNode<*>)?.virtualFile
            when {
                file == null -> true
                file == projectFile -> false
                file.isDirectory -> file.name.lowercase() !in HIDDEN_PROJECT_DIRECTORIES && file !in otherProjectDirs
                else -> true
            }
        }
        return result
    }

    override fun contains(file: VirtualFile): Boolean =
        projectFile?.parent?.let { VfsUtilCore.isAncestor(it, file, false) } == true

    override fun getTypeSortWeight(sortByType: Boolean): Int = PROJECT_WEIGHT

    /**
     * The node stands for the project directory as well: when a file or a folder is created or deleted
     * right in it, the project view looks for the node representing that directory to rebuild its children.
     */
    override fun canRepresent(element: Any?): Boolean {
        if (super.canRepresent(element)) return true
        val directory = projectFile?.parent ?: return false
        return when (element) {
            is VirtualFile -> element == directory
            is PsiDirectory -> element.virtualFile == directory
            else -> false
        }
    }

    override fun update(presentation: PresentationData) {
        val projectFile = projectFile
        presentation.setIcon(DotNetIcons.Project)
        presentation.presentableText = value.project.name
        presentation.locationString =
            if (projectFile == null) "not found: ${value.project.path}"
            else solutions.msBuildProject(projectFile).targetFrameworks.joinToString(", ").ifEmpty { null }
    }
}
