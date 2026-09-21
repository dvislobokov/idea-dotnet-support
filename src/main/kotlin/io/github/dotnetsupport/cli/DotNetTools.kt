package io.github.dotnetsupport.cli

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

/**
 * The global tools (`dotnet tool install --global ...`) the plugin drives. Where each one is looked for:
 * the path from Settings | Tools | .NET, then PATH, then `~/.dotnet/tools` (a shell profile that was not re-read
 * after the installation leaves the directory out of PATH).
 */
enum class DotNetTool(val packageId: String, val purpose: String, val documentation: String, command: String? = null) {
    COUNTERS("dotnet-counters", ".NET Monitor: GC, allocations, requests, exceptions", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-counters"),
    STACK("dotnet-stack", ".NET Monitor: Thread Dump", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-stack"),
    GCDUMP("dotnet-gcdump", ".NET Monitor: Heap Snapshot", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-gcdump"),
    UPGRADE_ASSISTANT("upgrade-assistant", "Analyze Upgrade to Newer .NET", "https://learn.microsoft.com/dotnet/core/porting/upgrade-assistant-overview"),

    // the package and the command it installs are named differently
    DEBUGGER("dotnet-debugger-dap", "Debug: the debug adapter (DAP) behind the Debug button", "https://github.com/dvislobokov/dotnet-debugger", command = "dotnet-debugger");

    /** The executable the package puts into the tools directory. */
    val command: String = command ?: packageId

    private val executableName: String get() = if (SystemInfo.isWindows) "$command.exe" else command

    /** The path set in the settings, when the file is there. */
    fun configured(): File? = DotNetSettings.getInstance().toolPath(this).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

    fun detect(): File? =
        PathEnvironmentVariableUtil.findInPath(executableName) ?: File(System.getProperty("user.home"), ".dotnet/tools/$executableName").takeIf { it.isFile }

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
