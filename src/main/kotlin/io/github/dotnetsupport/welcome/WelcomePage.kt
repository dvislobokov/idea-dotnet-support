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
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import java.nio.file.Files

/**
 * The page about the plugin (docs/demo.html, packed as welcome/index.html by the build), shown in an editor tab once after the plugin
 * is installed and again after an update, the way the IDE shows its own What's New. Later it is in the .NET menu.
 */
object WelcomePage {
    private val LOG = logger<WelcomePage>()
    const val TITLE = "C# Project Support"
    const val RESOURCE = "/welcome/index.html"
    const val SHOWN_VERSION_KEY = "dotnet.welcome.shown.version"
    private const val PLUGIN_ID = "io.github.dotnetsupport"

    fun pluginVersion(): String? = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version

    /** Once per version: a new installation has no record, an update has the record of the version before. */
    fun isNewFor(shownVersion: String?, currentVersion: String?): Boolean = currentVersion != null && shownVersion != currentVersion

    /** The page as the IDE shows it: in the theme of the IDE, without the links that lead into the repository. */
    fun html(dark: Boolean): String? {
        val page = WelcomePage::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        return forIde(page, dark)
    }

    fun forIde(page: String, dark: Boolean): String =
        page.replaceFirst("<html lang=\"ru\">", "<html lang=\"ru\" data-host=\"ide\" data-theme=\"${if (dark) "dark" else "light"}\">")

    fun open(project: Project) {
        val html = html(!JBColor.isBright())
        if (html == null) {
            LOG.warn("No $RESOURCE in the plugin")
            return
        }
        if (JBCefApp.isSupported()) {
            HTMLEditorProvider.openEditor(project, TITLE, html)
        } else {
            // no embedded browser in this IDE (a remote session, a runtime without JCEF): the system one
            val file = Files.createTempFile("csharp-project-support", ".html")
            Files.writeString(file, html)
            file.toFile().deleteOnExit()
            BrowserUtil.browse(file)
        }
    }
}

class WelcomePageActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return
        val properties = PropertiesComponent.getInstance()
        val version = WelcomePage.pluginVersion()
        if (!WelcomePage.isNewFor(properties.getValue(WelcomePage.SHOWN_VERSION_KEY), version)) return
        // recorded before it is shown: two projects opening together show it once
        properties.setValue(WelcomePage.SHOWN_VERSION_KEY, version)
        application.invokeLater({
            if (project.isDisposed) return@invokeLater
            runCatching { WelcomePage.open(project) }.onFailure { failure ->
                logger<WelcomePageActivity>().warn("The welcome page could not be opened", failure)
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
