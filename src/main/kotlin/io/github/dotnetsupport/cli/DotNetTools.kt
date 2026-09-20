package io.github.dotnetsupport.cli

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import io.github.dotnetsupport.settings.DotNetSettings
import java.io.File

/**
 * The global tools (`dotnet tool install --global ...`) the plugin drives. Where each one is looked for:
 * the path from Settings | Tools | .NET, then PATH, then `~/.dotnet/tools` (a shell profile that was not re-read
 * after the installation leaves the directory out of PATH).
 */
enum class DotNetTool(val packageId: String, val purpose: String, val documentation: String) {
    COUNTERS("dotnet-counters", ".NET Monitor: GC, allocations, requests, exceptions", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-counters"),
    STACK("dotnet-stack", ".NET Monitor: Thread Dump", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-stack"),
    GCDUMP("dotnet-gcdump", ".NET Monitor: Heap Snapshot", "https://learn.microsoft.com/dotnet/core/diagnostics/dotnet-gcdump"),
    UPGRADE_ASSISTANT("upgrade-assistant", "Analyze Upgrade to Newer .NET", "https://learn.microsoft.com/dotnet/core/porting/upgrade-assistant-overview");

    private val executableName: String get() = if (SystemInfo.isWindows) "$packageId.exe" else packageId

    /** The path set in the settings, when the file is there. */
    fun configured(): File? = DotNetSettings.getInstance().toolPath(this).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

    fun detect(): File? =
        PathEnvironmentVariableUtil.findInPath(executableName) ?: File(System.getProperty("user.home"), ".dotnet/tools/$executableName").takeIf { it.isFile }

    fun find(): File? = configured() ?: detect()

    /** `install` fails for an installed tool and `update` installs a missing one, so `update` serves both. */
    fun installCommand(): List<String> = listOf("tool", "update", "--global", packageId)

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
