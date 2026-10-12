package io.github.dotnetsupport.newproject

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.ScreenUtil
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities

class DotNetTemplateSettings(
    val template: DotNetTemplate,
    /** Null when the template has a single language. */
    val language: String?,
    /** Null for the default framework of the template. */
    val framework: String?,
    /** Options of the template that differ from their defaults, as arguments: `--test-runner MSTest --sdk`. */
    val templateOptions: List<String> = emptyList(),
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
        addAll(templateOptions)
    }
}

/**
 * Template / language / framework rows shared by the New Project wizard and the "Add New Project" dialog, plus the options of the chosen
 * template (`dotnet new <template> --help`): the test runner of `mstest`, `--use-program-main` of `console`, the authentication of `webapi`,
 * whatever a template of nuget.org declares.
 */
class DotNetTemplatePanel {
    private val templateCombo = ComboBox(DefaultComboBoxModel(DotNetTemplates.BUILT_IN.toTypedArray())).apply { isSwingPopup = false }
    private val languageCombo = ComboBox<String>()
    private val frameworkCombo = ComboBox(arrayOf(DEFAULT_FRAMEWORK)).apply { isEditable = true }
    private val optionsPanel = JPanel(BorderLayout())
    private val optionsStatus = JBLabel("")

    /** The options of the template shown now and the controls holding their values. */
    private val optionsForm = TemplateOptionsForm()
    private var installedFrameworks: List<String> = emptyList()

    init {
        templateCombo.addActionListener { updateLanguages(); loadOptions() }
        languageCombo.addActionListener { loadOptions() }
        updateLanguages()
        loadFromCli()
    }

    fun addRows(panel: Panel) = with(panel) {
        row("Template:") { cell(templateCombo) }
        // Only the installed templates are listed: the rest of nuget.org is one dialog away. A row of its own:
        // next to the combo box the link did not fit into the narrow New Project dialog.
        row("") {
            link("More templates...") {
                val dialog = TemplatePackagesDialog(templateCombo)
                dialog.show()
                if (dialog.isChanged) { TemplateHelpCache.clear(); loadFromCli() }
            }
        }
        row("Language:") { cell(languageCombo) }
        row("Framework:") { cell(frameworkCombo).comment("Not every template supports every framework") }
        row { cell(optionsStatus) }
        row { cell(optionsPanel).align(AlignX.FILL) }
    }

    val settings: DotNetTemplateSettings
        get() {
            val template = templateCombo.selectedItem as DotNetTemplate
            val framework = (frameworkCombo.editor.item as? String).orEmpty().trim()
            return DotNetTemplateSettings(
                template,
                language = (languageCombo.selectedItem as? String)?.takeIf { template.languages.size > 1 },
                framework = framework.takeIf { it.isNotEmpty() && it != DEFAULT_FRAMEWORK },
                templateOptions = optionsForm.arguments,
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
                installedFrameworks = frameworks
                templateCombo.model = DefaultComboBoxModel(templates.toTypedArray())
                templateCombo.selectedItem = templates.find { it.shortName == selected } ?: templates.find { it.shortName == "console" } ?: templates.firstOrNull()
                updateLanguages()
                setFrameworks(frameworks)
                loadOptions()
            }, ModalityState.any())
        }
    }

    private fun setFrameworks(frameworks: List<String>) {
        val framework = frameworkCombo.editor.item
        frameworkCombo.model = DefaultComboBoxModel((listOf(DEFAULT_FRAMEWORK) + frameworks).toTypedArray())
        frameworkCombo.editor.item = framework
    }

    /** `dotnet new <template> --help` for the chosen template and language, once per pair; the rows are rebuilt when it arrives. */
    private fun loadOptions() {
        val template = templateCombo.selectedItem as? DotNetTemplate ?: return
        val language = (languageCombo.selectedItem as? String)?.takeIf { template.languages.size > 1 }
        TemplateHelpCache.cached(template.shortName, language)?.let { return showOptions(it.options, it.frameworks) }
        optionsStatus.text = "Loading the options of the template..."
        showOptions(emptyList(), emptyList())
        ApplicationManager.getApplication().executeOnPooledThread {
            val help = TemplateHelpCache.load(template.shortName, language)
            ApplicationManager.getApplication().invokeLater({
                if ((templateCombo.selectedItem as? DotNetTemplate)?.shortName == template.shortName) showOptions(help.options, help.frameworks)
            }, ModalityState.any())
        }
    }

    private fun showOptions(loaded: List<TemplateOption>, frameworks: List<String>) {
        optionsStatus.text = if (loaded.isEmpty()) "" else "Options of the template:"
        // the frameworks the template names, else the installed SDKs
        if (frameworks.isNotEmpty()) setFrameworks(frameworks) else if (installedFrameworks.isNotEmpty()) setFrameworks(installedFrameworks)
        optionsPanel.removeAll()
        if (loaded.isNotEmpty()) optionsPanel.add(TemplateOptionsView.scrolled(optionsForm.rows(loaded), screenOf(optionsPanel).height), BorderLayout.CENTER)
        else optionsForm.rows(emptyList())
        optionsPanel.revalidate()
        optionsPanel.repaint()
        // the dialog was packed before the rows came
        SwingUtilities.getWindowAncestor(optionsPanel)?.let { window -> if (window.isShowing) fit(window) }
    }

    /** The window grows to hold the rows, never beyond its screen, and stays on it; it does not shrink back under the hands. */
    private fun fit(window: Window) {
        val screen = ScreenUtil.getScreenRectangle(window)
        val size = TemplateOptionsLayout.windowSize(window.size, window.preferredSize, screen.size)
        val bounds = Rectangle(window.location, size)
        ScreenUtil.moveToFit(bounds, screen, null)
        window.bounds = bounds
        window.validate()
    }

    private fun screenOf(component: JComponent): Rectangle =
        if (component.isShowing) ScreenUtil.getScreenRectangle(component)
        else if (GraphicsEnvironment.isHeadless()) Rectangle(0, 0, 1920, 1080)
        else GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds

    private companion object {
        const val DEFAULT_FRAMEWORK = "(template default)"
    }
}

/**
 * The rows of the options of a template and the values set in them: the template panel and the New Solution dialog show the same.
 * The panel writes the description under each control; the dialog, as Rider, puts it behind a (?) and may split the options into
 * several places ([reset] once, then [rowsOf] for each place).
 */
class TemplateOptionsForm {
    var options: List<TemplateOption> = emptyList()
        private set
    private val controls = HashMap<String, () -> String>()
    private val rowsByOption = HashMap<String, Row>()

    /** What the controls hold now: option name -> value. */
    val values: Map<String, String> get() = controls.mapValues { it.value() }

    /** The options that apply now ([TemplateOptionConditions]): the rows of the others are hidden. */
    val shownOptions: List<TemplateOption> get() = TemplateOptionConditions.shown(options, values)

    /** The options that differ from their defaults, as arguments of `dotnet new`; an option hidden by its condition is not passed. */
    val arguments: List<String> get() = TemplateOptions.arguments(shownOptions, values)

    /** Rows for [loaded], which become the [options] whose values [arguments] reads. */
    fun rows(loaded: List<TemplateOption>): JComponent {
        reset(loaded)
        return rowsOf(loaded)
    }

    /** Forgets the controls: the [loaded] options are what [arguments] reads from now on. */
    fun reset(loaded: List<TemplateOption>) {
        options = loaded
        controls.clear()
        rowsByOption.clear()
    }

    /** Shows the rows of the options that apply to the values set now, hides the rest: after every change of a control. */
    fun updateVisibility() {
        val shown = shownOptions.mapTo(HashSet()) { it.name }
        for ((name, row) in rowsByOption) row.visible(name in shown)
    }

    private fun changed() = updateVisibility()

    /**
     * Rows for [subset] of the [options]: [label] names an option, [suggestions] turns a text option into an editable combo box whose
     * first item stands for "not set" (`Default for chosen framework` of `--langVersion`); [contextHelp] puts the description behind a (?).
     */
    fun rowsOf(
        subset: List<TemplateOption>,
        label: (TemplateOption) -> String = { it.label },
        suggestions: (TemplateOption) -> List<String> = { emptyList() },
        contextHelp: Boolean = false,
    ): JComponent = panel {
        // the dialog of Rider keeps the colon the new UI drops from `row("Text:")`
        fun labeled(option: TemplateOption, init: Row.() -> Unit): Row =
            if (contextHelp) row(JBLabel("${label(option)}:"), init) else row("${label(option)}:", init)
        for (option in subset) {
            val hint = listOfNotNull(option.description.takeIf { it.isNotEmpty() }, option.enabledIf?.let { "Applies when: $it" }).joinToString(" ")
            fun <T : JComponent> Row.described(cell: Cell<T>, extra: String = "") {
                val text = (hint + extra).trim()
                if (text.isEmpty()) return
                if (contextHelp) contextHelp(text) else cell.comment(text)
            }
            rowsByOption[option.name] = when (option.kind) {
                TemplateOption.Kind.BOOL -> row {
                    val box = JBCheckBox(label(option), option.isBoolDefaultTrue)
                    controls[option.name] = { box.isSelected.toString() }
                    box.addActionListener { changed() }
                    described(cell(box))
                }
                TemplateOption.Kind.CHOICE -> labeled(option) {
                    if (option.multiple) {
                        val field = JBTextField(option.default.orEmpty())
                        controls[option.name] = { field.text }
                        described(cell(field).align(AlignX.FILL), " Several values separated by ;: ${option.choices.joinToString(", ") { it.value }}")
                    } else {
                        val combo = ComboBox(option.choices.map { it.value }.toTypedArray())
                        combo.selectedItem = option.choices.firstOrNull { it.value.equals(option.default, ignoreCase = true) }?.value ?: option.choices.firstOrNull()?.value
                        // the description in the list only: in the box itself it made the box, and the dialog with it, as wide as the longest one
                        combo.isSwingPopup = false
                        combo.renderer = com.intellij.ui.SimpleListCellRenderer.create { label, value, index ->
                            val choice = option.choices.firstOrNull { it.value == value }
                            label.text = if (index < 0 || choice?.description.isNullOrEmpty()) value.orEmpty() else "$value — ${choice!!.description}"
                        }
                        controls[option.name] = { combo.selectedItem as? String ?: "" }
                        combo.addActionListener { changed() }
                        described(cell(combo))
                    }
                }
                TemplateOption.Kind.TEXT -> labeled(option) {
                    val items = suggestions(option)
                    if (items.isNotEmpty()) {
                        val combo = ComboBox(items.toTypedArray()).apply { isEditable = true }
                        controls[option.name] = { (combo.editor.item as? String).orEmpty().trim().takeIf { it != items.first() }.orEmpty() }
                        described(cell(combo).columns(COLUMNS_MEDIUM))
                    } else {
                        val field = JBTextField(option.default.orEmpty())
                        controls[option.name] = { field.text }
                        described(cell(field).align(AlignX.FILL))
                    }
                }
            }
        }
        updateVisibility()
    }
}

/**
 * The rows of a template with many options (`webapi` has a dozen, a template of nuget.org any number) did not fit the screen: the
 * dialog grew with them and its buttons went below the edge. The rows scroll now, in a viewport that is never higher than
 * [TemplateOptionsLayout.viewportHeight] lets it be.
 */
object TemplateOptionsView {
    fun scrolled(rows: JComponent, screenHeight: Int): JComponent {
        val view = ViewportWidePanel(rows)
        val scroll = ScrollPaneFactory.createScrollPane(view, true)
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scroll.verticalScrollBar.unitIncrement = JBUI.scale(TemplateOptionsLayout.WHEEL_STEP)
        scroll.isOpaque = false
        scroll.viewport.isOpaque = false
        val content = view.preferredSize
        val height = TemplateOptionsLayout.viewportHeight(content.height, screenHeight, JBUI.scale(TemplateOptionsLayout.MAX_HEIGHT))
        val scrollBar = if (height < content.height) scroll.verticalScrollBar.preferredSize.width else 0
        scroll.preferredSize = Dimension(minOf(content.width, JBUI.scale(TemplateOptionsLayout.MAX_WIDTH)) + scrollBar, height)
        return scroll
    }

    /** As wide as the viewport, so a long comment wraps instead of asking for a horizontal scroll bar. */
    private class ViewportWidePanel(content: JComponent) : JPanel(BorderLayout()), Scrollable {
        init {
            isOpaque = false
            add(content, BorderLayout.NORTH)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(TemplateOptionsLayout.WHEEL_STEP)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            (visibleRect.height - JBUI.scale(TemplateOptionsLayout.WHEEL_STEP)).coerceAtLeast(1)
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}

/** How much room the options of a template may take: numbers only, the panel applies them. */
object TemplateOptionsLayout {
    /** Unscaled pixels. */
    const val MAX_HEIGHT = 320
    const val MAX_WIDTH = 620
    const val WHEEL_STEP = 20

    /** The share of the screen height the rows may take, and the one a window may. */
    private const val ROWS_SHARE = 0.4
    private const val WINDOW_SHARE = 0.9

    /** The height of the viewport for rows [contentHeight] high: all of them while they fit, [maxHeight] or 40% of the screen at most. */
    fun viewportHeight(contentHeight: Int, screenHeight: Int, maxHeight: Int): Int =
        minOf(contentHeight, maxHeight, (screenHeight * ROWS_SHARE).toInt()).coerceAtLeast(0)

    /** The size of the window: what it [wanted] where that is more than it has [current]ly, within 90% of the [screen]. */
    fun windowSize(current: Dimension, wanted: Dimension, screen: Dimension): Dimension = Dimension(
        maxOf(current.width, wanted.width).coerceAtMost((screen.width * WINDOW_SHARE).toInt()),
        maxOf(current.height, wanted.height).coerceAtMost((screen.height * WINDOW_SHARE).toInt()),
    )
}
