package io.github.dotnetsupport.view

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import io.github.dotnetsupport.solution.SolutionService
import java.io.File

/**
 * A project just added to a solution is selected and expanded in the Solution view, as in Rider: before, the solution node stayed as it
 * was and the new project had to be looked for (DEV_JOURNEY 2.3).
 */
object SolutionViewReveal {
    /** After the solution file on disk has the project: refreshed first, the tree rebuilt, then the node selected and expanded. */
    fun revealProject(project: Project, solutionFile: VirtualFile, projectFile: File) {
        LocalFileSystem.getInstance().refreshFiles(listOf(solutionFile), true, false) {
            ApplicationManager.getApplication().invokeLater({
                val created = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectFile) ?: return@invokeLater
                val pane = ProjectView.getInstance(project).getProjectViewPaneById(SolutionViewPane.ID) ?: return@invokeLater
                pane.updateFromRoot(true).doWhenDone {
                    val tree = pane.tree ?: return@doWhenDone
                    TreeUtil.promiseSelect(tree) { path -> visit(project, TreeUtil.getLastUserObject(path), created) }
                        .onSuccess { path -> tree.expandPath(path) }
                }
            }, project.disposed)
        }
    }

    /** The way down to the node of [projectFile]: only into the solution and the folders that hold it. */
    fun visit(project: Project, node: Any?, projectFile: VirtualFile): TreeVisitor.Action {
        val solutions = SolutionService.getInstance(project)
        fun holds(solutionFile: VirtualFile, projects: List<io.github.dotnetsupport.solution.SlnProject>) =
            projects.any { it.resolveFile(solutionFile) == projectFile }
        return when (val value = (node as? AbstractTreeNode<*>)?.value ?: node) {
            is ProjectKey -> if (value.project.resolveFile(value.solutionFile) == projectFile) TreeVisitor.Action.INTERRUPT else TreeVisitor.Action.SKIP_CHILDREN
            is SolutionKey -> if (holds(value.solutionFile, solutions.solution(value.solutionFile).allProjects)) TreeVisitor.Action.CONTINUE else TreeVisitor.Action.SKIP_CHILDREN
            is SolutionFolderKey -> {
                val folder = solutions.solution(value.solutionFile).findFolder(value.folderId)
                if (folder != null && holds(value.solutionFile, folder.allProjects())) TreeVisitor.Action.CONTINUE else TreeVisitor.Action.SKIP_CHILDREN
            }
            is Project, null -> TreeVisitor.Action.CONTINUE
            else -> TreeVisitor.Action.SKIP_CHILDREN
        }
    }
}
