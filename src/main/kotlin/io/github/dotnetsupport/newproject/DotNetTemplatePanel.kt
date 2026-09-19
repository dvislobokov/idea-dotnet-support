package io.github.dotnetsupport.newproject

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Panel
import javax.swing.DefaultComboBoxModel

class DotNetTemplateSettings(
    val template: DotNetTemplate,
    /** Null when the template has a single language. */
    val language: String?,
    /** Null for the default framework of the template. */
    val framework: String?,
) {
    val projectExtension: String
        get() = when (language ?: template.defaultLanguage) {
            "F#" -> "fsproj"
            "VB" -> "vbproj"
            else -> "csproj"
        }

    /** Arguments of `dotnet new` for a project [name] created in [outputDirectory]. */
    fun newArguments(name: String, outputDirectory: String): List<String> = buildList {
        add("new"); add(template.shortName)
        add("-n"); add(name)
        add("-o"); add(outputDirectory)
        language?.let { add("-lang"); add(it) }
        framework?.let { add("-f"); add(it) }
    }
}

/** Template / language / framework rows shared by the New Project wizard and the "Add New Project" dialog. */
class DotNetTemplatePanel {
    private val templateCombo = ComboBox(DefaultComboBoxModel(DotNetTemplates.BUILT_IN.toTypedArray())).apply { isSwingPopup = false }
    private val languageCombo = ComboBox<String>()
    private val frameworkCombo = ComboBox(arrayOf(DEFAULT_FRAMEWORK)).apply { isEditable = true }

    init {
        templateCombo.addActionListener { updateLanguages() }
        updateLanguages()
        loadFromCli()
    }

    fun addRows(panel: Panel) = with(panel) {
        row("Template:") { cell(templateCombo) }
        row("Language:") { cell(languageCombo) }
        row("Framework:") { cell(frameworkCombo).comment("Not every template supports every framework") }
    }

    val settings: DotNetTemplateSettings
        get() {
            val template = templateCombo.selectedItem as DotNetTemplate
            val framework = (frameworkCombo.editor.item as? String).orEmpty().trim()
            return DotNetTemplateSettings(
                template,
                language = (languageCombo.selectedItem as? String)?.takeIf { template.languages.size > 1 },
                framework = framework.takeIf { it.isNotEmpty() && it != DEFAULT_FRAMEWORK },
            )
        }

    private fun updateLanguages() {
        val template = templateCombo.selectedItem as? DotNetTemplate ?: return
        val previous = languageCombo.selectedItem
        languageCombo.model = DefaultComboBoxModel(template.languages.toTypedArray())
        languageCombo.selectedItem = previous?.takeIf { it in template.languages } ?: template.defaultLanguage ?: template.languages.firstOrNull()
        languageCombo.isEnabled = template.languages.size > 1
    }

    /** The built-in list is replaced with the templates and SDKs that are really installed. */
    private fun loadFromCli() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val templates = DotNetTemplates.loadProjectTemplates()
            val frameworks = DotNetTemplates.loadFrameworks()
            ApplicationManager.getApplication().invokeLater({
                val selected = (templateCombo.selectedItem as? DotNetTemplate)?.shortName
                templateCombo.model = DefaultComboBoxModel(templates.toTypedArray())
                templateCombo.selectedItem = templates.find { it.shortName == selected } ?: templates.find { it.shortName == "console" } ?: templates.firstOrNull()
                updateLanguages()

                val framework = frameworkCombo.editor.item
                frameworkCombo.model = DefaultComboBoxModel((listOf(DEFAULT_FRAMEWORK) + frameworks).toTypedArray())
                frameworkCombo.editor.item = framework
            }, ModalityState.any())
        }
    }

    private companion object {
        const val DEFAULT_FRAMEWORK = "(template default)"
    }
}
