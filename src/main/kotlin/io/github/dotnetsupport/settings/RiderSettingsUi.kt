package io.github.dotnetsupport.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import javax.swing.Icon
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * The settings pages follow the ones of Rider option by option, so that nothing has to be looked for. What the plugin has
 * nothing behind yet is still there, disabled, with a padlock that tells why: a missing option would read as "forgotten".
 */
object Unavailable {
    val ICON: Icon = AllIcons.Nodes.Padlock

    const val DEBUGGER = "Needs the .NET debugger, which the plugin does not have yet (planned: a Debug Adapter Protocol client)"
    const val OWN_ENGINE = "Rider does this with its own NuGet / build engine; the plugin works through the dotnet CLI, which has no such option"
    const val PACKAGES_CONFIG = "Applies to packages.config projects; the plugin manages PackageReference only"
    const val NO_PROJECT_MODEL = "Needs the design-time build of Rider's project model; the plugin reads project files statically"
    const val NO_FORMATTER = "Needs a C# formatter inside the IDE; formatting is done by CSharpier or dotnet format, configured in .editorconfig"

    /** The legend on top of a page that has such rows. */
    fun Panel.unavailableLegend() = row {
        icon(ICON)
        comment("Not available in the plugin yet: shown as in Rider, the reason is in the tooltip of the padlock")
    }

    private fun Row.padlock(reason: String) = icon(ICON).applyToComponent { toolTipText = reason }

    fun Panel.unavailableCheckBox(text: String, selected: Boolean, reason: String): Row = row {
        checkBox(text).applyToComponent { isSelected = selected; toolTipText = reason }.enabled(false)
        padlock(reason)
    }

    fun Panel.unavailableComboBox(label: String, value: String, reason: String, suffix: String? = null): Row = row(label) {
        cell(ComboBox(arrayOf(value))).applyToComponent { toolTipText = reason }.enabled(false)
        suffix?.let { cell(JBLabel(it)).enabled(false) }
        padlock(reason)
    }

    fun Panel.unavailableTextField(label: String, value: String, reason: String, columns: Int = 30): Row = row(label) {
        cell(JBTextField(value, columns)).applyToComponent { toolTipText = reason }.enabled(false)
        padlock(reason)
    }

    fun Panel.unavailableSpinner(label: String, value: Int, reason: String): Row = row(label) {
        cell(JSpinner(SpinnerNumberModel(value, 0, Int.MAX_VALUE, 1))).applyToComponent { toolTipText = reason }.enabled(false)
        padlock(reason)
    }

    fun Panel.unavailableButtons(reason: String, vararg texts: String): Row = row {
        texts.forEach { button(it) {}.enabled(false) }
        padlock(reason)
    }
}
