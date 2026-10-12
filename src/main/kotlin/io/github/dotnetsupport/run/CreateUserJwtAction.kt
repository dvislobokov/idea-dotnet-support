package io.github.dotnetsupport.run

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile

/**
 * "Insert Development JWT": `dotnet user-jwts create` for the ASP.NET Core project and the token it prints goes into the `.http` file as an
 * `Authorization: Bearer ...` header at the caret, so a request can be tried against a `[Authorize]` endpoint without hand-making a token.
 */
class CreateUserJwtAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null && isHttpFile(e.getData(CommonDataKeys.VIRTUAL_FILE))
    }

    private fun isHttpFile(file: VirtualFile?): Boolean = file != null && !file.isDirectory && file.extension?.lowercase() in setOf("http", "rest")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val httpFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (DotNetCli.findExecutable() == null) return DotNetCli.notifyError(project, TITLE, "The 'dotnet' executable is not found on PATH.")

        object : Task.Backgroundable(project, "Creating a development JWT", true) {
            private var token: String? = null
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                val webProject = webProjectFor(project, httpFile)
                if (webProject == null) {
                    error = "No ASP.NET Core project (Microsoft.NET.Sdk.Web) was found for the token."
                    return
                }
                // `--output token` prints the token alone; the fallback still picks the one line that looks like a JWT (two dots)
                val result = runCatching {
                    DotNetCli.execute(DotNetCli.commandLine(webProject.parent.path, "user-jwts", "create", "--project", webProject.path, "--output", "token"), 60_000)
                }.getOrNull() ?: run { error = "'dotnet user-jwts' could not be started."; return }
                if (result.exitCode != 0) {
                    error = result.stderr.ifBlank { result.stdout }.trim().take(600).ifBlank { "exit code ${result.exitCode}" }
                    return
                }
                token = result.stdout.lines().map { it.trim() }.lastOrNull { line -> line.isNotEmpty() && line.count { it == '.' } == 2 }
                    ?: result.stdout.trim().ifBlank { null }
            }

            override fun onSuccess() {
                val jwt = token ?: run {
                    io.github.dotnetsupport.cli.PluginLog.warn(DotNetDebugBuild.LOG_CATEGORY, "$TITLE: ${error ?: "'dotnet user-jwts' did not return a token"}")
                    return DotNetCli.notifyError(project, TITLE, error ?: "'dotnet user-jwts' did not return a token.")
                }
                insertAtCaretLine(project, editor, "Authorization: Bearer $jwt")
            }
        }.queue()
    }

    /** The project that owns the `.http` file when it is a web project, otherwise the first web project of the solution. */
    private fun webProjectFor(project: Project, httpFile: VirtualFile): VirtualFile? {
        val solutions = SolutionService.getInstance(project)
        DotNetProjects.findOwningProject(httpFile)?.takeIf { solutions.msBuildProject(it).isWebSdk }?.let { return it }
        return solutions.solutionFiles().firstNotNullOfOrNull { sln ->
            solutions.solution(sln).allProjects.mapNotNull { it.resolveFile(sln) }.firstOrNull { solutions.msBuildProject(it).isWebSdk }
        }
    }

    private fun insertAtCaretLine(project: Project, editor: Editor, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            val lineStart = document.getLineStartOffset(document.getLineNumber(editor.caretModel.offset))
            val inserted = "$text\n"
            document.insertString(lineStart, inserted)
            editor.caretModel.moveToOffset(lineStart + inserted.length)
        }
    }

    private companion object {
        const val TITLE = "Create Development JWT"
    }
}
