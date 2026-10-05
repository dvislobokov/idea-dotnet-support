package io.github.dotnetsupport.actions

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import io.github.dotnetsupport.solution.SlnFolder
import io.github.dotnetsupport.solution.SlnProject
import io.github.dotnetsupport.solution.Solution
import io.github.dotnetsupport.solution.SolutionEditor
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.solution.isSolutionFilterFile
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.SolutionFolderKey
import io.github.dotnetsupport.view.SolutionKey

/**
 * Moving projects between solution folders, as Rider's "Move to Folder..." and drag and drop in its Solution view: an edit of the
 * solution file ([SolutionEditor.moveProject]), not `dotnet sln`, which has no command for it.
 */
object SolutionFolderMove {
    /** A place a project can go: [folderId] null is the root of the solution. [title] is the path of the folder, `src/Libraries`. */
    data class Target(val folderId: String?, val title: String)

    const val ROOT_TITLE = "(solution root)"

    /** Every folder of [solution] and its root, without the place where all of [projects] already are. */
    fun targets(solution: Solution, projects: List<SlnProject>): List<Target> {
        val result = ArrayList<Target>()
        fun walk(folder: SlnFolder, prefix: String) {
            for (child in folder.folders.sortedBy { it.name.lowercase() }) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                result += Target(child.id, path)
                walk(child, path)
            }
        }
        result += Target(null, ROOT_TITLE)
        walk(solution.root, "")
        val current = projects.map { folderOf(solution, it) }.distinct().singleOrNull()
        return result.filter { current == null || it.folderId != current.id.ifEmpty { null } }
    }

    /** The folder that holds [project]; the root for a top-level one. */
    fun folderOf(solution: Solution, project: SlnProject): SlnFolder {
        fun find(folder: SlnFolder): SlnFolder? = if (folder.projects.any { it.id == project.id }) folder else folder.folders.firstNotNullOfOrNull(::find)
        return find(solution.root) ?: solution.root
    }

    /**
     * What a drop of [sources] (values of the dragged nodes) on [target] (value of the node under the mouse) moves: the projects and the
     * folder id (null for the root), or null when it is not a move of projects within one solution.
     */
    fun dropOf(sources: List<Any?>, target: Any?): Pair<List<ProjectKey>, String?>? {
        val projects = sources.map { it as? ProjectKey ?: return null }.ifEmpty { return null }
        val solutionFile = projects.map { it.solutionFile }.distinct().singleOrNull() ?: return null
        if (isSolutionFilterFile(solutionFile)) return null
        return when (target) {
            is SolutionFolderKey -> if (target.solutionFile == solutionFile) projects to target.folderId else null
            is SolutionKey -> if (target.solutionFile == solutionFile) projects to null else null
            else -> null
        }
    }

    /** The edit, in one undoable command; the Solution view follows the file. */
    fun move(project: Project, solutionFile: VirtualFile, projects: List<SlnProject>, folderId: String?) {
        val document = FileDocumentManager.getInstance().getDocument(solutionFile) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Move to Solution Folder", null, {
            var text = document.text
            for (slnProject in projects) text = SolutionEditor.moveProject(text, solutionFile.extension, slnProject, folderId)
            if (text != document.text) {
                document.setText(text.replace("\r\n", "\n"))
                FileDocumentManager.getInstance().saveDocument(document)
            }
        })
    }
}

/** "Move to Solution Folder..." on projects of the Solution view: a list of the folders of the solution, as Rider's "Move to Folder". */
class MoveToSolutionFolderAction : SolutionAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && selectedProjects(e).let { it.isNotEmpty() && SolutionFolderMove.dropOf(it, SolutionKey(it.first().solutionFile)) != null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projects = selectedProjects(e).ifEmpty { return }
        val solutionFile = projects.first().solutionFile
        val targets = SolutionFolderMove.targets(SolutionService.getInstance(project).solution(solutionFile), projects.map { it.project })
        JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
            .setTitle(if (projects.size == 1) "Move '${projects.single().project.name}' to Folder" else "Move ${projects.size} Projects to Folder")
            .setRenderer(SimpleListCellRenderer.create { label, value, _ ->
                label.text = value.title
                label.icon = if (value.folderId == null) io.github.dotnetsupport.DotNetIcons.Solution else com.intellij.icons.AllIcons.Nodes.Folder
            })
            .setNamerForFiltering { it.title }
            .setItemChosenCallback { SolutionFolderMove.move(project, solutionFile, projects.map { p -> p.project }, it.folderId) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    override fun perform(project: Project, context: SolutionContext) = Unit

    private fun selectedProjects(e: AnActionEvent): List<ProjectKey> =
        e.getData(PlatformCoreDataKeys.SELECTED_ITEMS).orEmpty().map { item -> ((item as? AbstractTreeNode<*>)?.value ?: item) as? ProjectKey ?: return emptyList() }
}
