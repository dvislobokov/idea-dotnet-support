package io.github.dotnetsupport.view

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.LayeredIcon
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.ProjectContent
import io.github.dotnetsupport.solution.SolutionService

/*
 * The content of a project as the SDK sees it, on top of the files on disk (see ProjectContent):
 *  - files and folders the project file takes out of the default globs are hidden ("Show All Files" brings them back, painted as ignored);
 *  - a file with <DependentUpon> is nested under the file it names, like the designer file of a form;
 *  - files included from outside of the project directory appear where their Link puts them, with a link badge.
 */

/** Applies to the Solution view only: the settings of its nodes are the tree structure of the pane. */
internal fun ViewSettings?.isSolutionView(): Boolean = this is SolutionTreeStructure

internal fun contentOf(project: Project, projectFile: VirtualFile): ProjectContent = ProjectContent(SolutionService.getInstance(project).msBuildProject(projectFile))

class ProjectContentStructureProvider : TreeStructureProvider {
    override fun modify(parent: AbstractTreeNode<*>, children: MutableCollection<AbstractTreeNode<*>>, settings: ViewSettings?): MutableCollection<AbstractTreeNode<*>> {
        if (!settings.isSolutionView()) return children
        val project = parent.project ?: return children
        val directory = when (parent) {
            is PsiDirectoryNode -> parent.virtualFile
            is DotNetProjectNode -> parent.virtualFile?.parent
            else -> null
        } ?: return children
        val projectFile = DotNetProjects.findOwningProject(directory) ?: return children
        val projectDirectory = projectFile.parent ?: return children
        val content = contentOf(project, projectFile)
        val showAll = SolutionViewSettings.isShowAllFiles(project)

        fun relative(file: VirtualFile): String? = VfsUtilCore.getRelativePath(file, projectDirectory, '/')

        val kept = ArrayList<AbstractTreeNode<*>>(children.size)
        for (child in children) {
            val file = (child as? ProjectViewNode<*>)?.virtualFile
            val path = file?.let(::relative)
            val excluded = path != null && !showAll && (if (file.isDirectory) content.isExcludedDirectory(path) else content.isExcluded(path))
            if (!excluded) kept += child
        }

        // <DependentUpon>: the nested file goes under its parent, when both are here
        val byName = kept.filterIsInstance<PsiFileNode>().associateBy { it.virtualFile?.name?.lowercase() }
        val nested = LinkedHashMap<PsiFileNode, MutableList<AbstractTreeNode<*>>>()
        for (child in kept) {
            val file = (child as? ProjectViewNode<*>)?.virtualFile?.takeIf { !it.isDirectory } ?: continue
            val parentName = relative(file)?.let(content::dependentParent) ?: continue
            val parentNode = byName[parentName.lowercase()]?.takeIf { it !== child } ?: continue
            nested.getOrPut(parentNode) { ArrayList() } += child
        }
        if (nested.isEmpty()) return kept
        val hidden = nested.values.flatten().toSet()
        return kept.mapNotNullTo(ArrayList()) { child ->
            when {
                child in hidden -> null
                child is PsiFileNode && child in nested -> DependentFilesNode(project, child.value, settings, nested.getValue(child))
                else -> child
            }
        }
    }
}

/** A file with the files that depend on it below: `Form1.cs` with `Form1.Designer.cs`. */
class DependentFilesNode(project: Project, file: PsiFile, settings: ViewSettings?, private val nested: List<AbstractTreeNode<*>>) : PsiFileNode(project, file, settings) {
    override fun getChildrenImpl(): Collection<AbstractTreeNode<*>> = nested
    override fun isAlwaysLeaf(): Boolean = false
    override fun contains(file: VirtualFile): Boolean = super.contains(file) || nested.any { (it as? ProjectViewNode<*>)?.contains(file) == true }
}

/** A file from outside of the project directory, at the place its `Link` names. */
class LinkedFileNode(project: Project, file: PsiFile, settings: ViewSettings?, private val source: String) : PsiFileNode(project, file, settings) {
    override fun updateImpl(data: PresentationData) {
        super.updateImpl(data)
        data.setIcon(LayeredIcon.layeredIcon(arrayOf(data.getIcon(false) ?: AllIcons.FileTypes.Any_type, AllIcons.Nodes.Symlink)))
        data.locationString = source
    }
}

data class LinkedFolderKey(val projectFile: VirtualFile, val path: String)

/** A folder that exists only in the project file: the `Link` paths of linked files. */
class LinkedFolderNode(project: Project, key: LinkedFolderKey, settings: ViewSettings?) : SolutionViewNode<LinkedFolderKey>(project, key, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        linkedNodes(nodeProject, value.projectFile, contentOf(nodeProject, value.projectFile).linkedFiles(value.projectFile.parent ?: return emptyList()), value.path, settings)

    override fun contains(file: VirtualFile): Boolean =
        contentOf(nodeProject, value.projectFile).linkedFiles(value.projectFile.parent ?: return false).any { it.path.startsWith("${value.path}/") && it.file == file }

    override fun getTypeSortWeight(sortByType: Boolean): Int = 1 // among the folders

    override fun update(presentation: PresentationData) {
        presentation.setIcon(LayeredIcon.layeredIcon(arrayOf(AllIcons.Nodes.Folder, AllIcons.Nodes.Symlink)))
        presentation.presentableText = value.path.substringAfterLast('/')
    }
}

/** The linked files and folders right under [folder] (`""` for the root of the project). */
internal fun linkedNodes(project: Project, projectFile: VirtualFile, files: List<ProjectContent.LinkedFile>, folder: String, settings: ViewSettings?): List<AbstractTreeNode<*>> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val here = files.filter { it.path.startsWith(prefix, ignoreCase = true) }.map { it to it.path.substring(prefix.length) }
    val psiManager = PsiManager.getInstance(project)
    val result = ArrayList<AbstractTreeNode<*>>()
    here.filter { '/' !in it.second }.mapNotNullTo(result) { (linked, _) ->
        psiManager.findFile(linked.file)?.let { LinkedFileNode(project, it, settings, source(projectFile, linked.file)) }
    }
    here.filter { '/' in it.second }.map { it.second.substringBefore('/') }.distinctBy { it.lowercase() }
        .mapTo(result) { LinkedFolderNode(project, LinkedFolderKey(projectFile, prefix + it), settings) }
    return result
}

/** Where a linked file really is, relative to the project directory when possible. */
private fun source(projectFile: VirtualFile, file: VirtualFile): String {
    val projectDirectory = projectFile.parent ?: return file.path
    val common = generateSequence(projectDirectory) { it.parent }.firstOrNull { VfsUtilCore.isAncestor(it, file, false) } ?: return file.path
    val up = generateSequence(projectDirectory) { it.parent }.takeWhile { it != common }.count()
    return "../".repeat(up) + VfsUtilCore.getRelativePath(file, common, '/')
}
