package io.github.dotnetsupport.sdk

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import io.github.dotnetsupport.solution.SolutionService

/**
 * Tells about a broken .NET environment when a solution is opened, before the first build fails with a cryptic message:
 * no `dotnet` at all, a `global.json` that asks for an SDK which is not installed, or no .NET 10 SDK for the C# language server.
 */
class SdkCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (SolutionService.getInstance(project).solutionFiles().isEmpty()) return
        val group = NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)

        val dotnet = DotNetCli.findExecutable()
        if (dotnet == null) {
            PluginLog.error(DotNetSdks.LOG_CATEGORY, "dotnet is not found: not in the settings, not on PATH, not in the default folders; the search folders are ${io.github.dotnetsupport.settings.DotNetSettings.getInstance().dotnetSearchPaths}")
            group.createNotification(".NET SDK is not found", "The 'dotnet' executable is neither on PATH nor in the default installation directory.", NotificationType.WARNING)
                .addAction(NotificationAction.createSimple("Configure...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java) })
                .addAction(NotificationAction.createSimple("Download .NET") { BrowserUtil.browse(DOWNLOAD_URL) })
                .notify(project)
            return
        }

        // the tools are looked up now, so that the log says where each one is (or was looked for) before anything needs it
        io.github.dotnetsupport.cli.DotNetTool.entries.forEach { it.find() }

        val installed = DotNetSdks.installed().map { it.version }
        val runtimes = DotNetRuntimes.installed()
        PluginLog.info(DotNetSdks.LOG_CATEGORY, "dotnet: $dotnet\n  SDKs: ${installed.joinToString(", ").ifEmpty { "none listed" }}\n  runtimes: ${runtimes.joinToString(", ") { "${it.name} ${it.version}" }.ifEmpty { "none listed" }}")
        // the C# language server (Roslyn) runs on .NET 10; people who stay on .NET 8 / 9 keep hitting a server that will not start, and a
        // balloon is not enough — a modal dialog says it plainly (and only when the server is on and no .NET 10 SDK or runtime is present)
        if (installed.isNotEmpty() && missingDotNet10(installed) && !DotNetRuntimes.hasNetCoreApp(runtimes, SERVER_SDK_MAJOR) && RoslynLanguageServerSettings.getInstance().state.enabled) {
            PluginLog.warn(DotNetSdks.LOG_CATEGORY, "no .NET $SERVER_SDK_MAJOR SDK or runtime for the C# language server: the dialog is shown")
            warnNoDotNet10(project, installed)
        }

        val (file, globalJson) = GlobalJson.find(project.guessProjectDir()) ?: return
        if (installed.isEmpty() || globalJson.resolve(installed) != null) return

        val required = globalJson.version
        PluginLog.warn(DotNetSdks.LOG_CATEGORY, "global.json of ${file.path} requires SDK ${required ?: "?"} (rollForward: ${globalJson.rollForward}), installed: ${installed.joinToString(", ")}")
        group.createNotification(
            "global.json requires .NET SDK ${required ?: ""}".trim(),
            "No installed SDK satisfies it (rollForward: ${globalJson.rollForward}). Installed: ${installed.joinToString(", ")}. Builds and restores will fail.",
            NotificationType.WARNING,
        )
            .addAction(NotificationAction.createSimple("Download SDK") { BrowserUtil.browse(if (required == null) DOWNLOAD_URL else "$DOWNLOAD_URL/dotnet/${required.major}.${required.minor}") })
            .addAction(NotificationAction.createSimple("Open global.json") { OpenFileDescriptor(project, file).navigate(true) })
            .notify(project)
    }

    /** A modal dialog, not a balloon: the warning is ignored otherwise and the server simply never works. */
    private fun warnNoDotNet10(project: Project, installed: List<SdkVersion>) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val options = arrayOf("Download .NET 10 SDK", "Open .NET Settings", "Continue Anyway")
            val choice = Messages.showDialog(
                project,
                "The C# language server (Roslyn) runs on .NET $SERVER_SDK_MAJOR. The installed SDKs are ${installed.joinToString(", ")}, so the " +
                    "server will not install or start on this machine — completion, errors, navigation and refactorings stay unavailable until the " +
                    ".NET $SERVER_SDK_MAJOR SDK is installed.\n\nInstall the .NET $SERVER_SDK_MAJOR SDK, then use .NET | Reload Solution.",
                "C# Support Requires the .NET $SERVER_SDK_MAJOR SDK",
                options, 0, Messages.getWarningIcon(),
            )
            when (choice) {
                0 -> BrowserUtil.browse("$DOWNLOAD_URL/dotnet/$SERVER_SDK_MAJOR.0")
                1 -> ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java)
            }
        }, ModalityState.nonModal())
    }

    companion object {
        private const val DOWNLOAD_URL = "https://dotnet.microsoft.com/download"

        /** `roslyn-language-server` 5.x is built for .NET 10 (kept in step with RoslynWorkspace.SERVER_RUNTIME in the content module). */
        const val SERVER_SDK_MAJOR = 10

        /** No installed SDK can run the server: none is .NET [SERVER_SDK_MAJOR] or newer. Pure, for the test. */
        fun missingDotNet10(installed: List<SdkVersion>): Boolean = installed.none { it.major >= SERVER_SDK_MAJOR }
    }
}
