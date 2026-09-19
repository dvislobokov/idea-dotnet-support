package io.github.dotnetsupport.actions

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SOLUTION_EXTENSIONS
import io.github.dotnetsupport.solution.SlnProject
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.DependenciesKey
import io.github.dotnetsupport.view.DependencyGroupKey
import io.github.dotnetsupport.view.ProjectKey
import io.github.dotnetsupport.view.SolutionFolderKey
import io.github.dotnetsupport.view.SolutionKey
import io.github.dotnetsupport.view.resolveFile

/** What a Solution view action applies to: a solution and optionally a folder or a project in it. */
class SolutionContext(
    val solutionFile: VirtualFile,
    val folderId: String? = null,
    val project: SlnProject? = null,
) {
    val projectFile: VirtualFile? get() = project?.resolveFile(solutionFile)

    companion object {
        /** Context of the selected Solution view node; null when something else is selected. */
        fun fromSelection(e: AnActionEvent): SolutionContext? {
            val project = e.project ?: return null
            val item = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.singleOrNull() ?: return null
            return when (val value = (item as? AbstractTreeNode<*>)?.value ?: item) {
                is SolutionKey -> SolutionContext(value.solutionFile)
                is SolutionFolderKey -> SolutionContext(value.solutionFile, folderId = value.folderId)
                is ProjectKey -> SolutionContext(value.solutionFile, project = value.project)
                is DependenciesKey -> forProjectFile(project, value.projectFile)
                is DependencyGroupKey -> forProjectFile(project, value.projectFile)
                else -> null
            }
        }

        fun forProjectFile(project: Project, projectFile: VirtualFile): SolutionContext? {
            val solutions = SolutionService.getInstance(project)
            for (solutionFile in solutions.solutionFiles()) {
                val slnProject = solutions.solution(solutionFile).allProjects.find { it.resolveFile(solutionFile) == projectFile }
                if (slnProject != null) return SolutionContext(solutionFile, project = slnProject)
            }
            return null
        }

        /** Solution or project file to build for the current selection (tree node, file in a project, editor). */
        fun buildTarget(e: AnActionEvent): VirtualFile? {
            fromSelection(e)?.let { context ->
                return if (context.project != null) context.projectFile else context.solutionFile
            }
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
            if (!file.isDirectory && file.extension?.lowercase() in SOLUTION_EXTENSIONS) return file
            return DotNetProjects.findOwningProject(file)
        }
    }
}
