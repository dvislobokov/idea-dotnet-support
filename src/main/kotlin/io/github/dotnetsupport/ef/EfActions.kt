package io.github.dotnetsupport.ef

import com.intellij.ide.scratch.ScratchRootType
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.msbuild.DotNetProjects
import java.io.File

/** The project an EF action is invoked for: the node of the Solution view, or the project of the selected file. */
private fun selectedProjectFile(e: AnActionEvent): VirtualFile? =
    SolutionContext.fromSelection(e)?.projectFile ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(DotNetProjects::findOwningProject)

/** A context menu offers EF for a project that uses it; the main menu for a solution where some project does. */
private fun isEfAvailable(e: AnActionEvent): Boolean {
    val project = e.project ?: return false
    val selected = selectedProjectFile(e)
    return if (e.isFromContextMenu) selected != null && EfProjects.usesEf(project, selected) else EfProjects.migrationsProjects(project).isNotEmpty()
}

/** .NET | EF Core, and the same submenu of a project in the Solution view. */
class EfCoreGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = e.project != null && (!e.isFromContextMenu || isEfAvailable(e))
        e.presentation.isEnabled = e.presentation.isVisible
    }
}

/** A `dotnet ef` command: asks for its options in [EfCommandDialog] and runs it as a task of the Build tool window. */
sealed class EfAction(text: String, private val kind: EfCommandKind) : AnAction(text), DumbAware {
    class AddMigration : EfAction("Add Migration...", EfCommandKind.ADD)
    class RemoveMigration : EfAction("Remove Last Migration...", EfCommandKind.REMOVE)
    class UpdateDatabase : EfAction("Update Database...", EfCommandKind.UPDATE)
    class GenerateScript : EfAction("Generate SQL Script...", EfCommandKind.SCRIPT)
    class DropDatabase : EfAction("Drop Database...", EfCommandKind.DROP)
    class ScaffoldDbContext : EfAction("Scaffold DbContext from Database...", EfCommandKind.SCAFFOLD)
    class CreateBundle : EfAction("Create Migration Bundle...", EfCommandKind.BUNDLE)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val available = isEfAvailable(e)
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = available else e.presentation.isEnabled = available
    }

    override fun actionPerformed(e: AnActionEvent) {
        EfCommands.ask(e.project ?: return, kind, selectedProjectFile(e))
    }
}

/** Installs the global `dotnet-ef`, or updates it to the latest version. */
class InstallEfToolAction : AnAction("Install or Update dotnet-ef"), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DotNetTool.EF.install(project) { DotNetCli.notifyInfo(project, "dotnet-ef is up to date") }
    }
}

/** What happens around a command: the dialog before it, and what its result is turned into. */
object EfCommands {
    fun ask(project: Project, kind: EfCommandKind, initialProject: VirtualFile?, preset: EfPreset = EfPreset()) {
        // reading the sources of every EF project: quick, but not for EDT
        val sources = ProgressManager.getInstance().runProcessWithProgressSynchronously<Map<VirtualFile, EfProjectSources>, RuntimeException>({
            EfProjects.migrationsProjects(project).associateWith { EfProjectSources(EfSources.dbContexts(it), EfSources.migrations(it).map { file -> file.migration }) }
        }, "Looking for DbContext Classes and Migrations", true, project)
        if (sources.isEmpty()) return DotNetCli.notifyInfo(project, "EF Core", "No project of the solution references Entity Framework Core.")

        val dialog = EfCommandDialog(project, kind, sources, initialProject, preset)
        if (!dialog.showAndGet()) return
        val context = dialog.context
        val reopen = { ask(project, kind, LocalFileSystem.getInstance().findFileByPath(context.project)) }
        when (val command = dialog.command) {
            is EfCommand.AddMigration -> EfRunner.run(project, command, context, reopen) { migrationAdded(project, command, context) }
            is EfCommand.Script -> script(project, command, context, reopen)
            EfCommand.DropDatabase -> dropDatabase(project, context, reopen)
            is EfCommand.Scaffold -> EfRunner.run(project, command, context, reopen) { scaffolded(project, command, context) }
            is EfCommand.Bundle -> bundle(project, command, context, reopen)
            else -> EfRunner.run(project, command, context, reopen) { DotNetCli.notifyInfo(project, "${EfRunner.title(command, context)}: done") }
        }
    }

    /** Opens the new migration and points at what it deletes: a rename is scaffolded as drop + add, and that loses the data. */
    private fun migrationAdded(project: Project, command: EfCommand.AddMigration, context: EfContext) {
        val projectFile = LocalFileSystem.getInstance().findFileByPath(context.project) ?: return
        val source = EfSources.migrations(projectFile).lastOrNull { it.migration.name == command.name }?.source ?: return
        FileEditorManager.getInstance(project).openFile(source, true)
        val text = runCatching { VfsUtilCore.loadText(source) }.getOrNull() ?: return
        val destructive = EfSources.destructiveOperations(text)
        if (destructive.isEmpty()) return
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification(
                "Migration '${command.name}' may lose data",
                destructive.take(8).joinToString("<br>") + "<br><br>A renamed column or table is scaffolded as a drop and an add: replace them with <code>RenameColumn</code> / <code>RenameTable</code>.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimple("Show in Migration") {
                val offset = text.indexOf("migrationBuilder.Drop").coerceAtLeast(0)
                OpenFileDescriptor(project, source, offset).navigate(true)
            })
            .notify(project)
    }

    /** Opens the generated context when its name is known; the tool derives it from the database otherwise. */
    private fun scaffolded(project: Project, command: EfCommand.Scaffold, context: EfContext) {
        val directory = LocalFileSystem.getInstance().findFileByPath(context.project)?.parent
        val folder = (command.contextDir ?: command.outputDir)?.let { directory?.findFileByRelativePath(it.replace('\\', '/')) } ?: directory
        val contextFile = command.contextName?.let { folder?.findChild("$it.cs") }
        if (contextFile != null) FileEditorManager.getInstance(project).openFile(contextFile, true)
        DotNetCli.notifyInfo(project, "DbContext is scaffolded", "Entities: ${command.outputDir ?: "the project folder"}. Scaffold again with \"Overwrite existing files\" after the database changes.")
    }

    /** An explicit output always: the notification has to know where the bundle is. */
    private fun bundle(project: Project, command: EfCommand.Bundle, context: EfContext, reopen: () -> Unit) {
        val name = if (SystemInfo.isWindows && command.runtime?.startsWith("win") != false) "efbundle.exe" else "efbundle"
        val requested = command.output?.let(::File)
        val file = when {
            requested == null -> File(File(context.project).parentFile, name)
            requested.isDirectory -> File(requested, name)
            else -> requested
        }
        EfRunner.run(project, command.copy(output = file.path), context, reopen) {
            NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
                .createNotification("Migration bundle is created", "${file.name} applies the migrations by itself: <code>${file.name} --connection \"...\"</code>", NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimple(RevealFileAction.getActionName()) { RevealFileAction.openFile(file) })
                .notify(project)
        }
    }

    /** `--output` always: the standard output of the tool is mixed with the build messages. No path means a scratch file. */
    private fun script(project: Project, command: EfCommand.Script, context: EfContext, reopen: () -> Unit) {
        val requested = command.output?.let(::File)?.let { if (it.isDirectory) File(it, "migrations.sql") else it }
        val file = requested ?: FileUtil.createTempFile("ef-migrations", ".sql", true)
        EfRunner.run(project, command.copy(output = file.path), context, reopen) {
            val script = if (requested != null) LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            else ScratchRootType.getInstance().createScratchFile(project, "migrations.sql", null, runCatching { file.readText() }.getOrDefault("")).also { file.delete() }
            if (script != null) FileEditorManager.getInstance(project).openFile(script, true)
        }
    }

    /** Asks the tool which database the options lead to, and deletes it only after its name is typed in. */
    private fun dropDatabase(project: Project, context: EfContext, reopen: () -> Unit) {
        val title = EfRunner.title(EfCommand.ContextInfo, context)
        val tool = EfTool.find(File(context.project).parentFile) ?: return DotNetTool.EF.offerInstallation(project, title) { dropDatabase(project, context, reopen) }
        val commandLine = DotNetCli.commandLinesOrNotify(project, title) { listOf(tool.commandLine(EfCommand.ContextInfo, context)) }?.single() ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Looking up the database", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching { DotNetCli.execute(commandLine) }.getOrElse { return DotNetCli.notifyError(project, title, it.message.orEmpty()) }
                val output = result.stdout + "\n" + result.stderr
                val info = EfOutputParser.contextInfo(output)
                if (result.exitCode != 0 || info == null) {
                    if (!EfRunner.reportProblem(project, title, output, EfCommand.ContextInfo, context, reopen, { dropDatabase(project, context, reopen) })) {
                        DotNetCli.notifyError(project, title, EfOutputParser.errorText(output).lines().takeLast(15).joinToString("\n"))
                    }
                    return
                }
                ApplicationManager.getApplication().invokeLater({
                    // the project is built by the lookup
                    if (!project.isDisposed && confirmDrop(project, info, context)) EfRunner.run(project, EfCommand.DropDatabase, context.copy(noBuild = true), reopen) {
                        DotNetCli.notifyInfo(project, "Database '${info.databaseName.orEmpty()}' is dropped")
                    }
                }, ModalityState.any())
            }
        })
    }

    private fun confirmDrop(project: Project, info: EfContextInfo, context: EfContext): Boolean {
        val database = info.databaseName?.takeIf { it.isNotBlank() }
        val details = listOfNotNull(
            "Database: ${database ?: "unknown"}", info.dataSource?.let { "Server: $it" }, info.providerName?.let { "Provider: $it" },
            info.type?.let { "DbContext: $it" }, "Environment: ${context.environment ?: "default"}",
        ).joinToString("\n")
        if (database == null) {
            return Messages.showYesNoDialog(project, "$details\n\nThe database and all its data will be deleted.", "Drop Database", "Drop", Messages.getCancelButton(), Messages.getWarningIcon()) == Messages.YES
        }
        val validator = object : InputValidatorEx {
            override fun getErrorText(input: String): String? = if (input.trim() == database) null else "Type '$database' to confirm"
            override fun checkInput(input: String): Boolean = getErrorText(input) == null
            override fun canClose(input: String): Boolean = checkInput(input)
        }
        return Messages.showInputDialog(project, "$details\n\nThe database and all its data will be deleted. Type its name to confirm:", "Drop Database", Messages.getWarningIcon(), "", validator) != null
    }
}
