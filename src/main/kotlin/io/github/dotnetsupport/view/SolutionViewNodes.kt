package io.github.dotnetsupport.view

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.NestingTreeStructureProvider
import com.intellij.ide.projectView.impl.nodes.AbstractPsiBasedNode
import com.intellij.ide.projectView.impl.nodes.ProjectViewDirectoryHelper
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
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

}

/**
 * A node that stands for a file which is not shown by itself: the solution and the project. PSI-based, because that is
 * what the drag source of the project view asks a node to be before a drag starts: dragged into the editor, the node
 * opens its file, as in Rider. Unlike a regular PSI node it stays in the tree when the file is missing (a project that
 * the solution lists, but that is not on disk).
 */
abstract class SolutionFileNode<T : Any>(project: Project, value: T, settings: ViewSettings?) : AbstractPsiBasedNode<T>(project, value, settings) {
    protected val nodeProject: Project get() = project!!
    protected val solutions: SolutionService get() = SolutionService.getInstance(nodeProject)

    protected abstract val file: VirtualFile?
    protected abstract fun children(): Collection<AbstractTreeNode<*>>
    protected abstract fun present(presentation: PresentationData)

    override fun extractPsiFromValue(): PsiElement? = file?.takeIf { it.isValid }?.let { PsiManager.getInstance(nodeProject).findFile(it) }
    override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> = children()
    override fun updateImpl(data: PresentationData) = present(data)
    override fun getVirtualFile(): VirtualFile? = file

    // the base class drops a node without PSI; these nodes say "not found" instead
    override fun validate(): Boolean = value != null
    override fun isValid(): Boolean = value != null
    override fun update(data: PresentationData) = if (extractPsiFromValue() == null) present(data) else super.update(data)

    // the name of a project is not the place for the VCS color of its .csproj
    override fun getFileStatus(): FileStatus = FileStatus.NOT_CHANGED
}

internal fun folderChildren(project: Project, settings: ViewSettings?, solutionFile: VirtualFile, folder: SlnFolder): List<AbstractTreeNode<*>> {
    val result = ArrayList<AbstractTreeNode<*>>()
    folder.folders.mapTo(result) { SolutionFolderNode(project, SolutionFolderKey(solutionFile, it.id), settings) }
    folder.projects.mapTo(result) { DotNetProjectNode(project, ProjectKey(solutionFile, it), settings) }
    val psiManager = PsiManager.getInstance(project)
    folder.files
        .mapNotNull { solutionFile.parent?.findFileByRelativePath(it) }
        .mapNotNull { psiManager.findFile(it) }
        .mapTo(result) { PsiFileNode(project, it, settings) }
    return result
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
    SolutionFileNode<SolutionKey>(project, key, settings) {

    private val solution: Solution get() = solutions.solution(value.solutionFile)
    override val file: VirtualFile get() = value.solutionFile

    override fun children(): Collection<AbstractTreeNode<*>> = folderChildren(nodeProject, settings, value.solutionFile, solution.root)

    override fun contains(file: VirtualFile): Boolean =
        value.solutionFile.parent?.let { VfsUtilCore.isAncestor(it, file, false) } == true ||
            solution.root.contains(value.solutionFile, file)

    override fun present(presentation: PresentationData) {
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
        folder?.let { folderChildren(nodeProject, settings, value.solutionFile, it) }.orEmpty()

    override fun contains(file: VirtualFile): Boolean = folder?.contains(value.solutionFile, file) == true
    override fun getTypeSortWeight(sortByType: Boolean): Int = FOLDER_WEIGHT

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.Folder)
        presentation.presentableText = folder?.name ?: value.folderId
    }
}

class DotNetProjectNode(project: Project, key: ProjectKey, settings: ViewSettings?) :
    SolutionFileNode<ProjectKey>(project, key, settings) {

    private val solutionFile: VirtualFile get() = value.solutionFile
    private val projectFile: VirtualFile? get() = value.project.resolveFile(solutionFile)
    override val file: VirtualFile? get() = projectFile

    override fun children(): Collection<AbstractTreeNode<*>> {
        val projectFile = projectFile ?: return emptyList()
        val directory = projectFile.parent?.let { PsiManager.getInstance(nodeProject).findDirectory(it) } ?: return emptyList()

        // Projects nested into this project's directory are shown as separate nodes of the solution.
        val otherProjectDirs = solutions.solution(solutionFile).allProjects
            .mapNotNullTo(HashSet()) { it.resolveFile(solutionFile)?.parent }
            .apply { remove(projectFile.parent) }

        // "Show All Files" of Rider: build output and the project file itself are hidden unless asked for
        val showAll = SolutionViewSettings.isShowAllFiles(nodeProject)
        val content = ProjectViewDirectoryHelper.getInstance(nodeProject).getDirectoryChildren(directory, settings, true).map(::shortNamed).filter { child ->
            val file = (child as? ProjectViewNode<*>)?.virtualFile
            when {
                file == null -> true
                file == projectFile -> showAll
                file.isDirectory -> file !in otherProjectDirs && (showAll || file.name.lowercase() !in HIDDEN_PROJECT_DIRECTORIES)
                else -> true
            }
        }
        // The platform nests files (appsettings.Development.json under appsettings.json) only below directory nodes;
        // the project directory is represented by this node, so its direct children are nested here.
        val nested = NestingTreeStructureProvider().modify(PsiDirectoryNode(nodeProject, directory, settings), content, settings)
        return listOf(DependenciesNode(nodeProject, DependenciesKey(projectFile), settings)) + nested
    }

    /**
     * A folder right under the project. With the Java plugin around (IntelliJ IDEA) a directory whose parent node is not a
     * directory is presented as a package with its full name, `src.App.Models`; here it is a folder of a .NET project.
     */
    private fun shortNamed(node: AbstractTreeNode<*>): AbstractTreeNode<*> {
        val directory = (node as? PsiDirectoryNode)?.value ?: return node
        return object : PsiDirectoryNode(nodeProject, directory, settings) {
            override fun updateImpl(data: PresentationData) {
                super.updateImpl(data)
                data.clearText()
                data.presentableText = directory.name
                data.locationString = null
            }
        }
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

    override fun present(presentation: PresentationData) {
        val projectFile = projectFile
        presentation.setIcon(DotNetIcons.Project)
        presentation.presentableText = value.project.name
        presentation.locationString =
            if (projectFile == null) "not found: ${value.project.path}"
            else solutions.msBuildProject(projectFile).targetFrameworks.joinToString(", ").ifEmpty { null }
    }
}
