package io.github.dotnetsupport.run

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.dashboard.RunDashboardCustomizationBuilder
import com.intellij.execution.dashboard.RunDashboardCustomizer
import com.intellij.execution.dashboard.RunDashboardDefaultTypesProvider
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.ide.BrowserUtil
import com.intellij.execution.dashboard.RunDashboardManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import io.github.dotnetsupport.aspire.AspireDashboard

/*
 * The Services tool window (the run dashboard of the platform) lists the ".NET Project" configurations, as Rider and the Spring Boot
 * support of IntelliJ IDEA do: every service of a solution with its console, status and restart in one place, and the address an
 * ASP.NET Core application listens on as a link next to its name.
 */

/** Configurations of this type go to the Services tool window without the user adding the type there. */
class DotNetRunDashboardTypes : RunDashboardDefaultTypesProvider {
    override fun getDefaultTypeIds(project: Project): Collection<String> = listOf(TYPE_ID)

    companion object {
        /** The id of [DotNetConfigurationType]. */
        const val TYPE_ID = "DotNetProjectRunConfiguration"
    }
}

/** The first "Now listening on" address of a process, kept on its handler for the dashboard. */
class ListeningAddressRecorder(private val handler: ProcessHandler) : ProcessListener {
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        // the login link of the dashboard of an Aspire AppHost, token and all: kept here only, never logged
        if (handler.getUserData(AspireDashboard.KEY) == null) AspireDashboard.loginUrl(event.text)?.let { handler.putUserData(AspireDashboard.KEY, it); refresh() }
        if (handler.getUserData(KEY) != null) return
        ListeningUrl.parse(event.text)?.let { handler.putUserData(KEY, ListeningUrl.browserUrl(it, null)); refresh() }
    }

    /** The row is drawn again on the events of the process only; the output that brings an address is not one of them. */
    private fun refresh() = ApplicationManager.getApplication().invokeLater {
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.forEach { RunDashboardManager.getInstance(it).updateDashboard(false) }
    }

    companion object {
        val KEY: Key<String> = Key.create("dotnet.listening.url")

        fun attach(handler: ProcessHandler) = handler.addProcessListener(ListeningAddressRecorder(handler))
    }
}

/**
 * The row of a .NET service: the address it listens on, as a link that opens the browser, and for `dotnet watch` the Hot Reload state
 * ([HotReloadTracker]), with a Restart link when a change needs one.
 */
class DotNetRunDashboardCustomizer : RunDashboardCustomizer() {
    override fun isApplicable(settings: RunnerAndConfigurationSettings, descriptor: RunContentDescriptor?): Boolean = settings.configuration is DotNetRunConfiguration

    override fun updatePresentation(customizationBuilder: RunDashboardCustomizationBuilder, settings: RunnerAndConfigurationSettings, descriptor: RunContentDescriptor?): Boolean {
        val handler = descriptor?.processHandler?.takeIf { !it.isProcessTerminated } ?: return false
        val dashboard = handler.getUserData(AspireDashboard.KEY)
        // the address an AppHost listens on is its dashboard, which asks for the token without the login link
        val url = handler.getUserData(ListeningAddressRecorder.KEY).takeIf { dashboard == null }
        val status = handler.getUserData(HotReloadTracker.KEY)
        if (url == null && status == null && dashboard == null) return false
        if (dashboard != null) {
            customizationBuilder.addText("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            customizationBuilder.link("Open Dashboard") { BrowserUtil.browse(dashboard) }
        }
        if (url != null) {
            customizationBuilder.addText("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            customizationBuilder.link(url) { BrowserUtil.browse(url) }
        }
        if (status != null) {
            customizationBuilder.addText("  Hot Reload: ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            customizationBuilder.addText(status.state.text, attributes(status.state))
            val environment = handler.getUserData(HotReloadTracker.ENVIRONMENT)
            if (environment != null && status.state == HotReloadState.RESTART_NEEDED) {
                customizationBuilder.addText("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                customizationBuilder.link("Restart") { HotReloadTracker.restart(environment) }
            }
        }
        return true
    }

    /**
     * `addLink` of the split Services view only makes a text fragment of the same value clickable; the fragment itself is added here
     * (without it the row showed nothing, seen live in 2026.1).
     */
    private fun RunDashboardCustomizationBuilder.link(text: String, action: () -> Unit) {
        addText(text, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES)
        addLink(text) { action() }
    }

    private fun attributes(state: HotReloadState): SimpleTextAttributes = when (state.level) {
        LogLevel.ERROR -> SimpleTextAttributes.ERROR_ATTRIBUTES
        LogLevel.WARNING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("Label.warningForeground", JBColor(0xA36200, 0xE5A549)))
        null -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    }
}
