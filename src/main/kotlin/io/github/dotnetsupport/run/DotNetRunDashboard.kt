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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes

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
        if (handler.getUserData(KEY) != null) return
        ListeningUrl.parse(event.text)?.let { handler.putUserData(KEY, ListeningUrl.browserUrl(it, null)) }
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
        val url = handler.getUserData(ListeningAddressRecorder.KEY)
        val status = handler.getUserData(HotReloadTracker.KEY)
        if (url == null && status == null) return false
        if (url != null) {
            customizationBuilder.addText("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            customizationBuilder.addLink(url) { BrowserUtil.browse(url) }
        }
        if (status != null) {
            customizationBuilder.addText("  Hot Reload: ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            customizationBuilder.addText(status.state.text, attributes(status.state))
            val environment = handler.getUserData(HotReloadTracker.ENVIRONMENT)
            if (environment != null && status.state == HotReloadState.RESTART_NEEDED) {
                customizationBuilder.addText("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                customizationBuilder.addLink("Restart") { HotReloadTracker.restart(environment) }
            }
        }
        return true
    }

    private fun attributes(state: HotReloadState): SimpleTextAttributes = when (state.level) {
        LogLevel.ERROR -> SimpleTextAttributes.ERROR_ATTRIBUTES
        LogLevel.WARNING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("Label.warningForeground", JBColor(0xA36200, 0xE5A549)))
        null -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    }
}
