package io.github.dotnetsupport.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.settings.Unavailable.unavailableButtons
import io.github.dotnetsupport.settings.Unavailable.unavailableCheckBox
import io.github.dotnetsupport.settings.Unavailable.unavailableLegend
import io.github.dotnetsupport.settings.Unavailable.unavailableSpinner
import io.github.dotnetsupport.settings.Unavailable.unavailableTextField

/**
 * Settings | Tools | .NET | Debugger. The plugin has no debugger yet (`DAP_PLAN.md`), so the page is the list of what
 * it is going to honor, with the defaults of Rider: every option is locked until there is something behind it.
 */
class DotNetDebuggerConfigurable(private val project: Project) : BoundConfigurable("Debugger") {
    /** Text, the default of Rider, and whether it is a sub-option of the one above. */
    private class Option(val text: String, val selected: Boolean, val nested: Boolean = false)

    override fun createPanel(): DialogPanel = panel {
        unavailableLegend()
        row {
            comment("The .NET debugger is not a part of the plugin yet. The options that do not depend on the language (the debug window, removing breakpoints) are the ones of the IDE:")
            link("Build, Execution, Deployment | Debugger") {
                // by id: this page is called "Debugger" too
                ShowSettingsUtil.getInstance().showSettingsDialog(project, { (it as? SearchableConfigurable)?.id == "project.propDebugger" }, null)
            }
        }
        for ((title, options) in GROUPS) group(title) {
            for (option in options) {
                if (option.nested) indent { unavailableCheckBox(option.text, option.selected, Unavailable.DEBUGGER) }
                else unavailableCheckBox(option.text, option.selected, Unavailable.DEBUGGER)
            }
            when (title) {
                VALUE_INSPECTIONS -> {
                    unavailableSpinner("Evaluation timeout (ms)", 1000, Unavailable.DEBUGGER)
                    unavailableSpinner("Truncate long strings, threshold", 255, Unavailable.DEBUGGER)
                }
                PIN_TO_TOP -> unavailableSpinner("Maximum recursion level:", 5, Unavailable.DEBUGGER)
                JIT_DEBUGGER -> {
                    unavailableTextField("32-bit:", "", Unavailable.DEBUGGER, columns = 6)
                    unavailableTextField("64-bit:", "", Unavailable.DEBUGGER, columns = 6)
                    unavailableButtons(Unavailable.DEBUGGER, "Set the IDE as the default debugger", "Restore previous default debugger")
                }
                PREDICTIVE -> unavailableSpinner("Predictive debugger timeout (ms)", 3000, Unavailable.DEBUGGER)
            }
        }
    }

    companion object {
        private const val VALUE_INSPECTIONS = "Value Inspections"
        private const val PIN_TO_TOP = "Pin to Top"
        private const val JIT_DEBUGGER = "Just-in-Time Debugger"
        private const val PREDICTIVE = "Predictive Debugger"

        private val GROUPS: List<Pair<String, List<Option>>> = listOf(
            ".NET Languages" to listOf(
                Option("Save all files on debugger launch", true),
                Option("Show breakpoint preview on mouse hover", true),
                Option("Enable external source debug", true),
                Option("Highlight call stacks in 'Debug Output' window", false),
                Option("Show elapsed time between debugger stops", true),
            ),
            "JIT (Excluding Mono)" to listOf(
                Option("Disable JIT optimization on module load", true),
                Option("Use JIT even if pre-compiled assemblies are available", false),
                Option("De-optimize methods on step into and methods with breakpoints (Windows, .NET 8 and later)", true),
            ),
            VALUE_INSPECTIONS to listOf(
                Option("Allow property evaluations and other implicit function calls", true),
                Option("Refresh watched values on debugger pause", false),
                Option("Show hex value for integers", false),
                Option("Show return values", true),
                Option("Show fully qualified type names", false),
                Option("Flatten objects hierarchy", true),
                Option("Show non-public members in a separate group", false),
                Option("Cluster big arrays", true),
                Option("Add raw view for debugger browsable values", false),
                Option("Show compiler-generated members", false),
                Option("Show type variables", true),
                Option("Truncate presentation of long strings", true),
            ),
            "Exceptions" to listOf(Option("Process exceptions outside of my code (excluding Mono)", false)),
            PIN_TO_TOP to listOf(
                Option("'Pin to Top' should change the presentation of debugger objects", true),
                Option("Enable AI 'Pin to Top' suggestions", false),
            ),
            JIT_DEBUGGER to emptyList(),
            "Blazor WASM Debugging" to listOf(
                Option("Enable Blazor WASM Debugging", true),
                Option("Use .NET WASM Debugger 2.0", true, nested = true),
                Option("Enable debugging Blazor WASM Backend", true, nested = true),
                Option("Enable browser logs", false, nested = true),
                Option("Use default command-line arguments when launching browser", false, nested = true),
                Option("Enable splash screen", true, nested = true),
            ),
            PREDICTIVE to listOf(
                Option("Enable Predictive Debugger", true),
                Option("Enable Colorized Mode", true, nested = true),
                Option("Enable gutter line indicator", true, nested = true),
            ),
        )

        /** For tests: how many options the page lists. */
        internal val optionCount: Int get() = GROUPS.sumOf { it.second.size }
    }
}
