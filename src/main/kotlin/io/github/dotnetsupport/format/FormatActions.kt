package io.github.dotnetsupport.format

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SolutionService
import java.io.File

/**
 * Formatting of a whole project or solution with the formatter chosen for the repository, and the check a CI would run.
 * CSharpier works on the directory; `dotnet format` on the project, and here it is the full one (style and analyzers
 * included), unlike the whitespace-only pass behind Reformat Code.
 */
abstract class FormatTargetAction(private val verify: Boolean) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = target(e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = (if (verify) "Verify Formatting of" else "Format") + " '${target.nameWithoutExtension}'"
    }

    private fun target(e: AnActionEvent): VirtualFile? =
        SolutionContext.buildTarget(e) ?: e.project?.let { SolutionService.getInstance(it).solutionFiles().firstOrNull() }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        ApplicationManager.getApplication().executeOnPooledThread { run(project, VfsUtilCore.virtualToIoFile(target)) }
    }

    private fun run(project: Project, target: File) {
        val title = (if (verify) "Verifying the formatting of " else "Formatting ") + target.name
        val command = try {
            commandLine(project, target, verify)
        } catch (e: CSharpierUnavailable) {
            return DotNetCli.notifyError(project, title, e.message.orEmpty())
        } catch (e: ExecutionException) { // no SDK
            return DotNetCli.notifyError(project, title, e.message.orEmpty())
        } ?: return DotNetCli.notifyInfo(project, title, "No formatter is selected in Settings | Tools | .NET.")
        DotNetCli.runInBackground(project, title, listOf(command), refresh = listOf(target.parentFile)) {
            DotNetCli.notifyInfo(project, title, if (verify) "Everything is formatted." else "Done.")
        }
    }

    companion object {
        /** Null when formatting is switched off. Blocking: CSharpier may have to be asked for its version. */
        fun commandLine(project: Project, target: File, verify: Boolean): GeneralCommandLine? {
            val directory = target.parentFile
            return when (DotNetFormattingSettings.getInstance(project).resolve(directory, runCatching { target.readText() }.getOrNull())) {
                FormatterChoice.CSHARPIER -> CSharpierLocator.find(directory).let { if (verify) it.check(directory) else it.format(directory) }
                FormatterChoice.DOTNET_FORMAT ->
                    DotNetCli.commandLine(directory.path, *listOfNotNull("format", target.path, "--verify-no-changes".takeIf { verify }).toTypedArray())
                else -> null
            }
        }
    }
}

class FormatSelectedAction : FormatTargetAction(verify = false)
class VerifyFormattingAction : FormatTargetAction(verify = true)
