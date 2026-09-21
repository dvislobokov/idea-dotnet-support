package io.github.dotnetsupport.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import io.github.dotnetsupport.settings.DotNetSettings
import java.io.File

/** Local tools of a repository: `dotnet-tools.json`, which `dotnet <command>` resolves from the working directory upwards. */
object DotNetToolManifest {
    // SDK 10 writes the manifest next to the sources, older ones into .config
    private val LOCATIONS = listOf("dotnet-tools.json", ".config/dotnet-tools.json")

    /** The version of [packageId] in `{ "tools": { "dotnet-ef": { "version": "9.0.0", ... } } }`; the package id is case-insensitive. */
    fun parse(json: String, packageId: String): String? {
        val tools = runCatching { (JsonParser.parseString(json) as? JsonObject)?.get("tools") as? JsonObject }.getOrNull() ?: return null
        val entry = tools.entrySet().firstOrNull { it.key.equals(packageId, ignoreCase = true) }?.value as? JsonObject ?: return null
        return entry.get("version")?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** The nearest manifest at or above [directory] that lists [packageId]: its directory and the version of the tool. */
    fun find(directory: File?, packageId: String): Pair<File, String>? = generateSequence(directory) { it.parentFile }.firstNotNullOfOrNull { dir ->
        LOCATIONS.firstNotNullOfOrNull { name ->
            File(dir, name).takeIf { it.isFile }?.let { manifest -> parse(runCatching { manifest.readText() }.getOrDefault(""), packageId) }
        }?.let { dir to it }
    }
}

/**
 * The global tools (`dotnet tool install --global ...`) the plugin drives. Where each one is looked for:
 * the path from Settings | Tools | .NET, then PATH, then `~/.dotnet/tools` (a shell profile that was not re-read
 * after the installation leaves the directory out of PATH).
 */
enum class DotNetTool(val packageId: String, val purpose: String, val documentation: String, command: String? = null, private val olderCommands: List<String> = emptyList()) {
    COUNTERS("dotnet-counters", ".NET Monitor: GC, allocations, requests, exceptions", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-counters"),
    STACK("dotnet-stack", ".NET Monitor: Thread Dump", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-stack"),
    GCDUMP("dotnet-gcdump", ".NET Monitor: Heap Snapshot", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-gcdump"),
    UPGRADE_ASSISTANT("upgrade-assistant", "Analyze Upgrade to Newer .NET", "https://learn.microsoft.com/dotnet/core/porting/upgrade-assistant-overview"),

    // the package and the command it installs are named differently
    DEBUGGER("dotnet-debugger-dap", "Debug: the debug adapter (DAP) behind the Debug button", "https://github.com/dvislobokov/dotnet-debugger", command = "dotnet-debugger"),

    ROSLYN_LANGUAGE_SERVER("roslyn-language-server", "C# language server (Roslyn): Settings | Tools | .NET | Language Server", "https://www.nuget.org/packages/roslyn-language-server"),

    // a tool from the manifest of a repository wins over this one, see EfTool
    EF("dotnet-ef", "EF Core: migrations and database commands", "https://learn.microsoft.com/ef/core/cli/dotnet"),

    // 1.x installs `csharpier`, 0.x installed `dotnet-csharpier`; a tool from the manifest of a repository wins over this one
    CSHARPIER("csharpier", "Reformat Code with CSharpier", "https://csharpier.com", olderCommands = listOf("dotnet-csharpier"));

    /** The executable the package puts into the tools directory. */
    val command: String = command ?: packageId

    /** On Windows a tool is an `.exe` shim, or a `.cmd` one when the package is specific to a runtime (`roslyn-language-server`). */
    private val executableNames: List<String> get() = (listOf(command) + olderCommands).flatMap { executableNames(it, SystemInfo.isWindows) }

    /** The path set in the settings, when the file is there. */
    fun configured(): File? = DotNetSettings.getInstance().toolPath(this).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

    fun detect(): File? = executableNames.firstNotNullOfOrNull { name ->
        PathEnvironmentVariableUtil.findInPath(name) ?: File(System.getProperty("user.home"), ".dotnet/tools/$name").takeIf { it.isFile }
    }

    fun find(): File? = configured() ?: detect()

    /** `install` fails for an installed tool and `update` installs a missing one, so `update` serves both. */
    fun installCommand(): List<String> = listOf("tool", "update", "--global", packageId)

    /**
     * Installs or updates the tool on the calling (background) thread, handing over what `dotnet` prints as it arrives.
     * For places with their own progress UI, such as the settings page: a modal dialog hides the Build tool window.
     * Returns the exit code, or -1 with the reason passed to [onText] when `dotnet` cannot be started.
     */
    fun installBlocking(onText: (String) -> Unit): Int = try {
        val handler = CapturingProcessHandler(DotNetCli.commandLine(null, *installCommand().toTypedArray()).withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en"))
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType !== ProcessOutputTypes.SYSTEM) onText(event.text)
            }
        })
        handler.runProcess(600_000).exitCode
    } catch (e: Exception) {
        onText(e.message.orEmpty())
        -1
    }

    /** Installs or updates the tool in a background task; [onSuccess] runs on EDT. */
    fun install(project: Project, onSuccess: () -> Unit = {}) {
        val title = "Installing $packageId"
        val commands = DotNetCli.commandLinesOrNotify(project, title) { listOf(DotNetCli.commandLine(null, *installCommand().toTypedArray())) } ?: return
        DotNetCli.runInBackground(project, title, commands, onSuccess = onSuccess)
    }

    /** "The tool is not installed" with the buttons to install it and to read about it. */
    fun offerInstallation(project: Project, title: String, onInstalled: () -> Unit = {}) {
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification(title, "The <code>$packageId</code> global tool is not installed.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Install") { install(project, onInstalled) })
            .addAction(NotificationAction.createSimple("About the Tool") { BrowserUtil.browse(documentation) })
            .addAction(NotificationAction.createSimple("Configure...") {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, io.github.dotnetsupport.settings.DotNetSettingsConfigurable::class.java)
            })
            .notify(project)
    }
}

/** The file names a tool called [command] may have in a tools directory. */
fun executableNames(command: String, windows: Boolean): List<String> = if (windows) listOf("$command.exe", "$command.cmd") else listOf(command)
