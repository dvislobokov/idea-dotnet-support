package io.github.dotnetsupport.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.DotNetBundle

/**
 * Settings | .NET | Debugger: what the debugger of the plugin (`PLATFORM_DAP_PLAN.md`) honors, in the groups and with the
 * wording of Rider. An option appears here when there is something behind it.
 */
class DotNetDebuggerConfigurable(private val project: Project) : BoundConfigurable(DotNetBundle.message("page.debugger")) {
    private val settings get() = DotNetSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(DotNetBundle.message("debugger.common"))
            link(DotNetBundle.message("debugger.common.link")) {
                // by id: this page is called "Debugger" too
                ShowSettingsUtil.getInstance().showSettingsDialog(project, { (it as? SearchableConfigurable)?.id == "project.propDebugger" }, null)
            }
        }
        group(DotNetBundle.message("debugger.languages")) {
            row {
                checkBox(DotNetBundle.message("debugger.external")).bindSelected(settings::debugExternalSource)
                    .comment(DotNetBundle.message("debugger.external.comment"))
            }
        }
        group(DotNetBundle.message("debugger.values")) {
            row {
                checkBox(DotNetBundle.message("debugger.implicit")).bindSelected(settings::debugAllowImplicitEvaluation)
                    .comment(DotNetBundle.message("debugger.implicit.comment"))
            }
        }
    }
}
