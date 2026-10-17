package io.github.dotnetsupport.frameworks.unity

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SolutionService

/**
 * .NET → Open in Unity Editor (UNITY_PLAN.md): the editor of the project's version, as Unity Hub installs it, started on the project —
 * the editor compiles and runs the game, not `dotnet`. The project is the one of the selected file, else the one of the solution.
 */
class OpenInUnityAction : AnAction(UnityIcons.Unity), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val root = e.project?.let { rootOf(it, e) }
        e.presentation.isEnabledAndVisible = root != null
        if (root != null) e.presentation.text = "Open in Unity Editor" + (UnityProjects.editorVersion(root)?.let { " $it" } ?: "")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = rootOf(project, e) ?: return
        val version = UnityProjects.editorVersion(root)
        val editor = version?.let(UnityProjects::editorExecutable)
        if (editor == null) {
            DotNetCli.notifyError(project, "Open in Unity Editor", "Unity ${version ?: "of the project"} is not installed by Unity Hub (${UnityProjects.PROJECT_VERSION_FILE} of ${root.name}).")
            return
        }
        runCatching { GeneralCommandLine(editor.path, "-projectPath", root.path).withWorkDirectory(root.path).createProcess() }
            .onFailure { DotNetCli.notifyError(project, "Open in Unity Editor", it.message ?: it.toString()) }
    }

    private fun rootOf(project: Project, e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(UnityProjects::rootOf)
            ?: SolutionService.getInstance(project).allSolutionFiles().firstNotNullOfOrNull { it.parent?.takeIf(UnityProjects::isUnityRoot) }
}
