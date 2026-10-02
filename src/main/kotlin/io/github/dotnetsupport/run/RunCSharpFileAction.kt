package io.github.dotnetsupport.run

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.msbuild.DotNetProjects

/**
 * "Run C# File": `dotnet run <file>` for a standalone .cs file (a file-based app, .NET 10+). Shown only for a .cs file that no project owns —
 * inside a project the project is what `dotnet run` builds. The output goes to a console with a Stop button.
 */
class RunCSharpFileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = targetFile(e)
        e.presentation.isEnabledAndVisible = file != null
        if (file != null) e.presentation.text = "Run '${file.name}' with dotnet"
    }

    /** A .cs file that no `.csproj` owns; `dotnet run <file>` is for file-based apps, not for a file that belongs to a project. */
    private fun targetFile(e: AnActionEvent): VirtualFile? {
        e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (file.isDirectory || !file.extension.equals("cs", ignoreCase = true)) return null
        return file.takeIf { DotNetProjects.findOwningProject(it) == null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = targetFile(e) ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        if (DotNetCli.findExecutable() == null) {
            return DotNetCli.notifyError(project, "Run C# File", "The 'dotnet' executable is not found on PATH.")
        }
        // a file-based app runs from the directory of the file; needs .NET SDK 10 or newer (an older SDK prints its own error to the console)
        val handler = try {
            KillableColoredProcessHandler(DotNetCli.commandLine(file.parent.path, "run", file.name))
        } catch (e: Exception) {
            PluginLog.error(DotNetDebugBuild.LOG_CATEGORY, "`dotnet run ${file.name}` could not be started", e)
            return DotNetCli.notifyError(project, "Run C# File", e.message ?: "'dotnet run' could not be started.")
        }
        PluginLog.info(DotNetDebugBuild.LOG_CATEGORY, "`dotnet run ${file.name}` started in ${file.parent.path}")
        ProcessTerminatedListener.attach(handler)
        RunContentExecutor(project, handler)
            .withTitle("dotnet run ${file.name}")
            .withActivateToolWindow(true)
            .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
            .run()
    }
}
