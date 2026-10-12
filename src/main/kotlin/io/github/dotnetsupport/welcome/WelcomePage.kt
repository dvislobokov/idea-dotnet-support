package io.github.dotnetsupport.welcome

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.cli.PluginLog
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefProxySettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The pages about the plugin, shown in an editor tab: what it can do (docs/demo.html, packed as welcome/index.html by the build) and
 * its documentation (docs/guide.html, welcome/guide.html). The first one opens once after the plugin is installed and again after an
 * update, the way the IDE shows its own What's New; later both are in the .NET menu.
 *
 * The pages lead to each other by ordinary links, so they are shown as files: written for the theme of the IDE into its caches and
 * opened by their address. A page given to the browser as a text has no address a link could be relative to.
 */
object WelcomePage {
    /** The category of the journal of the plugin for the pages about it. */
    const val LOG_CATEGORY = "welcome"
    const val TITLE = "C# Project Support"
    const val INDEX = "index.html"
    const val GUIDE = "guide.html"
    val PAGES: List<String> = listOf(INDEX, GUIDE)
    const val SHOWN_VERSION_KEY = "dotnet.welcome.shown.version"
    private const val PLUGIN_ID = "io.github.dotnetsupport"

    fun pluginVersion(): String? = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version

    /** The page opens on a new installation (no record) and when the first two numbers change (`0.1.x` to `0.2.x`); other releases only notify. */
    fun isNewFor(shownVersion: String?, currentVersion: String?): Boolean =
        currentVersion != null && (shownVersion == null || majorMinor(shownVersion) != majorMinor(currentVersion))

    private fun majorMinor(version: String) = version.split('.').take(2)

    /** Any change of the version, to be recorded and announced. */
    fun isUpdate(shownVersion: String?, currentVersion: String?): Boolean = currentVersion != null && shownVersion != currentVersion

    /** The page as the IDE shows it: in the theme of the IDE, without the links that lead into the repository. */
    fun html(dark: Boolean, page: String = INDEX): String? {
        val text = WelcomePage::class.java.getResourceAsStream("/welcome/$page")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        return forIde(text, dark)
    }

    /** In the repository the first page is demo.html, in the plugin index.html: the links between the pages are told so. */
    fun forIde(page: String, dark: Boolean): String =
        page.replaceFirst("<html lang=\"ru\">", "<html lang=\"ru\" data-host=\"ide\" data-theme=\"${if (dark) "dark" else "light"}\">")
            .replace("href=\"demo.html", "href=\"$INDEX")

    /** Both pages as files in [directory], for the theme; null when the plugin has no pages (a build without them). */
    fun write(directory: Path, dark: Boolean): Path? {
        Files.createDirectories(directory)
        for (page in PAGES) Files.writeString(directory.resolve(page), html(dark, page) ?: return null)
        return directory
    }

    /** `file:///C:/.../guide.html#settings`. */
    fun address(directory: Path, page: String, anchor: String? = null): String = directory.resolve(page).toUri().toString() + anchor?.let { "#$it" }.orEmpty()

    /**
     * [inBrowser]: the system browser and not a tab of the editor, for what is opened from a modal dialog (the Settings), behind which
     * a tab would not be seen.
     */
    fun open(project: Project, page: String = INDEX, anchor: String? = null, inBrowser: Boolean = false) {
        val dark = !JBColor.isBright()
        val directory = try {
            write(Path.of(PathManager.getSystemPath(), "dotnet-support", "welcome", if (dark) "dark" else "light"), dark)
        } catch (e: IOException) {
            PluginLog.warn(LOG_CATEGORY, "the pages of the plugin could not be written", e)
            null
        }
        if (directory == null) {
            PluginLog.warn(LOG_CATEGORY, "no /welcome/$page in the plugin")
            return
        }
        // no embedded browser in this IDE (a remote session, a runtime without JCEF): the system one
        if (inBrowser || !JBCefApp.isSupported()) BrowserUtil.browse(address(directory, page, anchor))
        else {
            // the first browser of the session builds JBCefApp in a class initializer that asks for the proxy services: created here first,
            // they are not created inside <clinit> (SEVERE "JBCefApp$Holder <clinit> requests ProxyMigrationService" otherwise)
            runCatching { JBCefProxySettings.getInstance() }
            HTMLEditorProvider.openEditor(project, TITLE, HTMLEditorProvider.Request.url(address(directory, page, anchor)))
        }
    }
}

class WelcomePageActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return
        val properties = PropertiesComponent.getInstance()
        val version = WelcomePage.pluginVersion()
        val shown = properties.getValue(WelcomePage.SHOWN_VERSION_KEY)
        if (!WelcomePage.isUpdate(shown, version)) return
        // recorded before it is shown: two projects opening together show it once
        properties.setValue(WelcomePage.SHOWN_VERSION_KEY, version)
        if (!WelcomePage.isNewFor(shown, version)) {
            application.invokeLater({
                if (project.isDisposed) return@invokeLater
                NotificationGroupManager.getInstance().getNotificationGroup(".NET")
                    .createNotification("C# Project Support updated to $version", "", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimpleExpiring("What's New") { WelcomePage.open(project) })
                    .notify(project)
            }, ModalityState.nonModal())
            return
        }
        application.invokeLater({
            if (project.isDisposed) return@invokeLater
            runCatching { WelcomePage.open(project) }.onFailure { failure ->
                PluginLog.warn(WelcomePage.LOG_CATEGORY, "the welcome page could not be opened", failure)
                NotificationGroupManager.getInstance().getNotificationGroup(".NET")
                    .createNotification("C# Project Support is installed", "See what it can do: .NET | Welcome to C# Project Support.", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimpleExpiring("Open") { WelcomePage.open(project) })
                    .notify(project)
            }
        }, ModalityState.nonModal())
    }
}

class ShowWelcomePageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        WelcomePage.open(e.project ?: return)
    }
}

class ShowDocumentationAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        WelcomePage.open(e.project ?: return, WelcomePage.GUIDE)
    }
}
