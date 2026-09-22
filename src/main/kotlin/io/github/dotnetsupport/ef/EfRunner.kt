package io.github.dotnetsupport.ef

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.dotnetsupport.build.BuildViewCommandOutput
import io.github.dotnetsupport.cli.CommandOutput
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.cli.DotNetToolManifest
import java.io.File

/** How `dotnet ef` is started: the local tool of the repository through `dotnet ef`, otherwise the executable of the global one. */
class EfTool private constructor(private val executable: File?, /** Known for a local tool only. */ val version: String?) {
    val isLocal: Boolean get() = executable == null

    @Throws(ExecutionException::class)
    fun commandLine(command: EfCommand, context: EfContext): GeneralCommandLine {
        val directory = File(context.project).parent
        val arguments = EfCommandBuilder.arguments(command, context).toTypedArray()
        // the global tool may be outside of PATH (~/.dotnet/tools of a shell profile that was not re-read): `dotnet ef` would not find it
        val commandLine = if (executable == null) DotNetCli.commandLine(directory, "ef", *arguments) else DotNetCli.commandLine(directory, *arguments).withExePath(executable.path)
        return commandLine.withEnvironment(EfCommandBuilder.environment(context)).withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en")
    }

    companion object {
        /** The tool for a project in [directory]: the manifest of the repository wins, as the same version the CI uses. */
        fun find(directory: File?): EfTool? {
            DotNetToolManifest.find(directory, DotNetTool.EF.packageId)?.let { (_, version) -> return EfTool(null, version) }
            return DotNetTool.EF.find()?.let { EfTool(it, null) }
        }
    }
}

/** Runs the commands that change something as tasks of the Build tool window and explains the failures it recognizes. */
object EfRunner {
    private const val CONTEXT_CREATION_DOCS = "https://learn.microsoft.com/ef/core/cli/dbcontext-creation"

    fun title(command: EfCommand, context: EfContext): String {
        val words = EfCommandBuilder.arguments(command, context).takeWhile { !it.startsWith("-") }
        // a script is "from to": positional arguments that are not worth a title
        return "dotnet ef " + words.take(if (command is EfCommand.AddMigration) 3 else 2).joinToString(" ")
    }

    /**
     * [reopen] brings back the dialog the command came from, for the failures that are fixed by another choice in it;
     * [onSuccess] runs on EDT after the files of the migrations project are re-read.
     */
    fun run(project: Project, command: EfCommand, context: EfContext, reopen: (() -> Unit)? = null, onSuccess: () -> Unit = {}) {
        val title = title(command, context)
        val directory = File(context.project).parentFile
        val tool = EfTool.find(directory)
            ?: return DotNetTool.EF.offerInstallation(project, title) { run(project, command, context, reopen, onSuccess) }
        val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(tool.commandLine(command, context)) } ?: return

        val captured = StringBuffer()
        val view = BuildViewCommandOutput(project, title)
        val output = object : CommandOutput by view {
            override fun text(text: String, isError: Boolean) {
                captured.append(text)
                view.text(text, isError)
            }
        }
        val retry = { run(project, command, context, reopen, onSuccess) }
        DotNetCli.runInBackground(
            project, title, commands, refresh = listOf(directory), output = output,
            onFailure = { reportProblem(project, title, captured.toString(), command, context, reopen, retry, onSuccess) },
        ) {
            EfOutputParser.outdatedTool(captured.toString())?.let { (toolVersion, runtime) -> offerToolUpdate(project, tool, toolVersion, runtime, directory) }
            EfMigrationsService.getInstance(project).commandFinished(command, context)
            onSuccess()
        }
    }

    /** True when the failure is a known one and a notification with a way out has been shown. */
    internal fun reportProblem(
        project: Project, title: String, output: String, command: EfCommand, context: EfContext,
        reopen: (() -> Unit)?, retry: () -> Unit, onSuccess: () -> Unit = {},
    ): Boolean {
        val problem = EfOutputParser.diagnose(output) ?: return false
        val startupProject = context.startupProject ?: context.project
        val startupName = File(startupProject).nameWithoutExtension
        when (problem) {
            EfProblem.TOOL_MISSING -> DotNetTool.EF.offerInstallation(project, title, retry)
            EfProblem.TOOL_NOT_RESTORED -> notify(project, title, "The <code>dotnet-ef</code> tool of the repository manifest is not restored.") {
                addAction(NotificationAction.createSimpleExpiring("Restore Tools") { runDotNet(project, "dotnet tool restore", File(context.project).parentFile, retry, "tool", "restore") })
            }
            EfProblem.DESIGN_PACKAGE_MISSING -> notify(project, title, "The startup project <b>$startupName</b> does not reference <code>${EfProjects.DESIGN_PACKAGE}</code>.") {
                addAction(NotificationAction.createSimpleExpiring("Add Package") { addDesignPackage(project, startupProject, context.project, retry) })
                if (reopen != null) addAction(NotificationAction.createSimpleExpiring("Choose Another Startup Project...") { reopen() })
            }
            EfProblem.PROVIDER_MISSING -> {
                val provider = (command as? EfCommand.Scaffold)?.provider ?: return false
                notify(project, title, "<b>${File(context.project).nameWithoutExtension}</b> does not reference the database provider <code>$provider</code>.") {
                    addAction(NotificationAction.createSimpleExpiring("Add Package") { addPackage(project, context.project, provider, retry) })
                }
            }
            EfProblem.CANNOT_CREATE_CONTEXT -> notify(
                project, title,
                "EF could not create the <code>DbContext</code> by starting <b>$startupName</b>. Check the startup project and the environment, " +
                    "or add an <code>IDesignTimeDbContextFactory</code> to the migrations project.<br><br>" + lastLines(output),
            ) {
                if (reopen != null) addAction(NotificationAction.createSimpleExpiring("Change Options...") { reopen() })
                addAction(NotificationAction.createSimpleExpiring("Create Design-Time Factory") {
                    LocalFileSystem.getInstance().findFileByPath(context.project)?.let { EfDesignTimeFactory.createFor(project, it, context.dbContext) }
                })
                addAction(NotificationAction.createSimple("Design-Time DbContext Creation") { BrowserUtil.browse(CONTEXT_CREATION_DOCS) })
            }
            EfProblem.MULTIPLE_CONTEXTS -> notify(project, title, "The project has more than one <code>DbContext</code>: choose the one the command is for.") {
                if (reopen != null) addAction(NotificationAction.createSimpleExpiring("Choose DbContext...") { reopen() })
            }
            EfProblem.NO_CONTEXT -> notify(project, title, "No <code>DbContext</code> was found in <b>${File(context.project).nameWithoutExtension}</b>: is it the project with the migrations?") {
                if (reopen != null) addAction(NotificationAction.createSimpleExpiring("Choose Another Project...") { reopen() })
            }
            EfProblem.MIGRATION_APPLIED -> {
                if (command !is EfCommand.RemoveMigration) return false
                notify(project, title, "The last migration is already applied to the database. It can be reverted there and then removed.") {
                    addAction(NotificationAction.createSimpleExpiring("Revert and Remove") { run(project, command.copy(force = true), context, reopen, onSuccess) })
                }
            }
            // the Build tool window has opened with the compiler errors
            EfProblem.BUILD_FAILED -> return false
        }
        return true
    }

    private fun lastLines(output: String): String = EfOutputParser.errorText(output).lines().takeLast(6).joinToString("<br>")

    private fun notify(project: Project, title: String, content: String, type: NotificationType = NotificationType.ERROR, configure: Notification.() -> Unit = {}) =
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP).createNotification(title, content, type).apply(configure).notify(project)

    private fun runDotNet(project: Project, title: String, directory: File?, onSuccess: () -> Unit, vararg arguments: String) {
        val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(DotNetCli.commandLine(directory?.path, *arguments)) } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = listOfNotNull(directory), onSuccess = onSuccess)
    }

    /** The design package of the version EF itself is restored with: a newer one would pull a newer EF into the application. */
    private fun addDesignPackage(project: Project, startupProject: String, migrationsProject: String, onSuccess: () -> Unit) {
        val files = LocalFileSystem.getInstance()
        val version = listOf(startupProject, migrationsProject).firstNotNullOfOrNull { path -> files.findFileByPath(path)?.let { EfProjects.efVersion(project, it) } }
        val arguments = listOf("add", startupProject, "package", EfProjects.DESIGN_PACKAGE) + (version?.let { listOf("--version", it) } ?: emptyList())
        runDotNet(project, "Installing ${EfProjects.DESIGN_PACKAGE}", File(startupProject).parentFile, onSuccess, *arguments.toTypedArray())
    }

    /** A provider of Microsoft is versioned with EF; the others have their own numbering, so the latest one is taken. */
    private fun addPackage(project: Project, projectPath: String, packageId: String, onSuccess: () -> Unit) {
        val version = LocalFileSystem.getInstance().findFileByPath(projectPath)?.let { EfProjects.efVersion(project, it) }?.takeIf { packageId.startsWith("Microsoft.", ignoreCase = true) }
        val arguments = listOf("add", projectPath, "package", packageId) + (version?.let { listOf("--version", it) } ?: emptyList())
        runDotNet(project, "Installing $packageId", File(projectPath).parentFile, onSuccess, *arguments.toTypedArray())
    }

    private fun offerToolUpdate(project: Project, tool: EfTool, toolVersion: String, runtimeVersion: String, directory: File?) =
        notify(project, "dotnet-ef $toolVersion is older than EF Core $runtimeVersion", "Update the tool to get the features and the fixes of the runtime version.", NotificationType.WARNING) {
            addAction(NotificationAction.createSimpleExpiring("Update dotnet-ef") {
                if (tool.isLocal) runDotNet(project, "Updating dotnet-ef", directory, {}, "tool", "update", DotNetTool.EF.packageId)
                else DotNetTool.EF.install(project)
            })
        }
}
