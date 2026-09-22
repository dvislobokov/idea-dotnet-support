package io.github.dotnetsupport.sdk

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import io.github.dotnetsupport.solution.SolutionService

/**
 * Tells about a broken .NET environment when a solution is opened, before the first build fails with a cryptic message:
 * no `dotnet` at all, or a `global.json` that asks for an SDK which is not installed.
 */
class SdkCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (SolutionService.getInstance(project).solutionFiles().isEmpty()) return
        val group = NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)

        if (DotNetCli.findExecutable() == null) {
            group.createNotification(".NET SDK is not found", "The 'dotnet' executable is neither on PATH nor in the default installation directory.", NotificationType.WARNING)
                .addAction(NotificationAction.createSimple("Configure...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java) })
                .addAction(NotificationAction.createSimple("Download .NET") { BrowserUtil.browse(DOWNLOAD_URL) })
                .notify(project)
            return
        }

        val (file, globalJson) = GlobalJson.find(project.guessProjectDir()) ?: return
        val installed = DotNetSdks.installed().map { it.version }
        if (installed.isEmpty() || globalJson.resolve(installed) != null) return

        val required = globalJson.version
        group.createNotification(
            "global.json requires .NET SDK ${required ?: ""}".trim(),
            "No installed SDK satisfies it (rollForward: ${globalJson.rollForward}). Installed: ${installed.joinToString(", ")}. Builds and restores will fail.",
            NotificationType.WARNING,
        )
            .addAction(NotificationAction.createSimple("Download SDK") { BrowserUtil.browse(if (required == null) DOWNLOAD_URL else "$DOWNLOAD_URL/dotnet/${required.major}.${required.minor}") })
            .addAction(NotificationAction.createSimple("Open global.json") { OpenFileDescriptor(project, file).navigate(true) })
            .notify(project)
    }

    private companion object {
        const val DOWNLOAD_URL = "https://dotnet.microsoft.com/download"
    }
}
