package io.github.dotnetsupport.roslyn

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.util.function.Function
import javax.swing.JComponent

/**
 * A file of a project that the loaded solution does not have, though another solution of the folder does (`ShopApi/` next to
 * `DebugPlayground.sln`): the server does not know the file, so its list, its types and its errors are not there, and nothing said why.
 * A file of no project at all is [io.github.dotnetsupport.lang.CSharpEditorBanners]' business.
 */
class RoslynUnloadedSolutionBanner : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!isCSharpSource(file) || PropertiesComponent.getInstance(project).getBoolean(HIDDEN_KEY)) return null
        val workspace = project.service<RoslynWorkspace>()
        val loaded = workspace.loadedSolution ?: return null
        val owner = DotNetProjects.findOwningProject(file) ?: return null
        val other = solutionToLoad(owner.path, loaded, solutionProjects(project)) ?: return null
        val name = other.substringAfterLast('/')
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = "This file belongs to $name, which is not loaded: library members, completion and errors are limited."
                createActionLabel("Load $name") { workspace.loadSolution(other) }
                createActionLabel("Don't Show Again") {
                    PropertiesComponent.getInstance(project).setValue(HIDDEN_KEY, true)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }

    companion object {
        const val HIDDEN_KEY = "dotnet.banner.unloadedSolution"

        /** The solutions of the opened folder (absolute paths) with the paths of their projects that are on disk. */
        fun solutionProjects(project: Project): Map<String, List<String>> = SolutionService.getInstance(project).let { service ->
            service.solutionFiles().associate { solution -> solution.path to service.solution(solution).allProjects.mapNotNull { it.resolveFile(solution)?.path } }
        }

        /**
         * The solution to offer for a file of [projectFile]: null when the [loaded] solution has the project, or no solution of the folder
         * does; otherwise the first of [solutions] (path -> paths of its projects, in the order of the Solution view) that has it.
         */
        fun solutionToLoad(projectFile: String, loaded: String, solutions: Map<String, Collection<String>>): String? {
            fun has(solution: String) = solutions.entries.firstOrNull { FileUtil.pathsEqual(it.key, solution) }?.value.orEmpty().any { FileUtil.pathsEqual(it, projectFile) }
            if (has(loaded)) return null
            return solutions.keys.firstOrNull { !FileUtil.pathsEqual(it, loaded) && has(it) }
        }
    }
}
