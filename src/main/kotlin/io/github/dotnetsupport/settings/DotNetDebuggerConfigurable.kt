package io.github.dotnetsupport.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/**
 * Settings | Tools | .NET | Debugger: what the debugger of the plugin (`PLATFORM_DAP_PLAN.md`) honors, in the groups and with the
 * wording of Rider. An option appears here when there is something behind it.
 */
class DotNetDebuggerConfigurable(private val project: Project) : BoundConfigurable("Debugger") {
    private val settings get() = DotNetSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row {
            comment("The options that do not depend on the language (the debug window, removing breakpoints) are the ones of the IDE:")
            link("Build, Execution, Deployment | Debugger") {
                // by id: this page is called "Debugger" too
                ShowSettingsUtil.getInstance().showSettingsDialog(project, { (it as? SearchableConfigurable)?.id == "project.propDebugger" }, null)
            }
        }
        group(".NET Languages") {
            row {
                checkBox("Enable external source debug").bindSelected(settings::debugExternalSource)
                    .comment("Off is \"Just My Code\": steps and stops stay in the code of the solution")
            }
        }
        group("Value Inspections") {
            row {
                checkBox("Allow property evaluations and other implicit function calls").bindSelected(settings::debugAllowImplicitEvaluation)
                    .comment("Off: values are described without running the code of the program (<code>ToString()</code>, getters, <code>[DebuggerDisplay]</code>)")
            }
        }
    }
}
