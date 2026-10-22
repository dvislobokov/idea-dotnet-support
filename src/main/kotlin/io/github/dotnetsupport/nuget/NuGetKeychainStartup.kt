package io.github.dotnetsupport.nuget

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.solution.SolutionService

/**
 * At the opening of a solution: the credential provider in NuGet's folder is brought up to date when the plugin keeps passwords (a new
 * version of the plugin, another SDK), and passwords lying in clear text in a `nuget.config` of the solution are offered a move to the
 * keychain of the OS — nothing is moved without the user's click.
 */
class NuGetKeychainStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (DotNetCli.findExecutable() == null || SolutionService.getInstance(project).solutionFiles().isEmpty()) return
        if (NuGetCredentialStore.hasAny()) NuGetKeychain.availability()
        if (PropertiesComponent.getInstance(project).getBoolean(DISMISS_KEY)) return
        val clearText = runCatching { NuGetService.getInstance(project).clearTextCredentials() }
            .onFailure { PluginLog.warn(NuGetClient.LOG_CATEGORY, "nuget.config files could not be read for clear-text passwords", it) }.getOrNull().orEmpty()
        if (clearText.isEmpty() || !NuGetKeychain.isReady()) return
        offer(project, clearText)
    }

    private fun offer(project: Project, entries: List<NuGetService.ClearTextCredentials>) {
        val feeds = entries.joinToString(", ") { "<b>${StringUtil.escapeXmlEntities(it.sourceName)}</b>" }
        val files = entries.map { it.file.path }.distinct().joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("NuGet passwords in clear text",
                "$feeds: the password lies in clear text in<br>$files<br>Move it to the keychain of the system? <code>dotnet</code> keeps finding it there, " +
                    "in the IDE and in a terminal; the file loses only the credentials.", NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Move to Keychain") { ApplicationManager.getApplication().executeOnPooledThread { move(project, entries) } })
            .addAction(NotificationAction.createSimpleExpiring("Don't Ask Again") { PropertiesComponent.getInstance(project).setValue(DISMISS_KEY, true) })
            .notify(project)
    }

    private fun move(project: Project, entries: List<NuGetService.ClearTextCredentials>) {
        val moved = ArrayList<String>()
        for (entry in entries) {
            try {
                NuGetCredentialStore.set(entry.url, entry.user, entry.password, keychain = true)
            } catch (e: Exception) {
                DotNetCli.notifyError(project, "The password of ${entry.sourceName} is not moved", e.message ?: e.toString())
                continue
            }
            // into the keychain first: a failure there leaves the file as it was
            val text = entry.file.readText()
            entry.file.writeText(NuGetConfigEditor.removeCredentials(text, entry.sourceName))
            LocalFileSystem.getInstance().findFileByIoFile(entry.file)?.let { VfsUtil.markDirtyAndRefresh(true, false, false, it) }
            PluginLog.info(NuGetClient.LOG_CATEGORY, "the password of ${entry.sourceName} is moved from ${entry.file} to the keychain of the OS")
            moved += entry.sourceName
        }
        if (moved.isNotEmpty()) DotNetCli.notifyInfo(project, "NuGet passwords are in the keychain", moved.distinct().joinToString(", "))
    }

    companion object {
        const val DISMISS_KEY = "io.github.dotnetsupport.nuget.clearTextPasswords.dismissed"
    }
}
