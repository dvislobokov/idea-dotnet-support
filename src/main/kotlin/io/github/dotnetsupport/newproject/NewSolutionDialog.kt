package io.github.dotnetsupport.newproject

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SeparatorWithText
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.DotNetIcons
import java.awt.BorderLayout
import java.awt.Component
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

/**
 * File | New | New Solution..., a copy of the New Solution window of Rider: kinds of projects on the left (Empty Solution, Project Type,
 * Other, Custom Templates), the form of the chosen kind on the right. The templates, SDKs and options come from `dotnet new` as in the
 * New Project wizard ([DotNetTemplatePanel]); the solution is created by [DotNetProjectCreator.createSolution].
 *
 * [loadFromCli] is false in tests: the built-in templates are shown and no `dotnet` runs.
 *
 * With [addTo] it is the dialog of Add | New Project... of the Solution view, as in Rider, where both are one window: the same kinds,
 * templates, frameworks and options, without the solution name, Empty Solution and the repository; Create returns [addProjectRequest].
 */
class NewSolutionDialog(
    private val project: Project?,
    private val loadFromCli: Boolean = true,
    private val addTo: AddProjectTarget? = null,
) : DialogWrapper(project, true) {
    /** Where Add | New Project puts the project: [baseDirectory] is the solution directory, [destination] the solution and its folder. */
    class AddProjectTarget(val baseDirectory: String, val destination: String?)

    /** What Create of Add | New Project gives: the project [directory] (`<base>/<name>`), its [name] and the template with its options. */
    class AddProjectRequest(val directory: File, val name: String, val template: DotNetTemplateSettings)

    private val isAddMode: Boolean get() = addTo != null

    /** An item of the left column. */
    sealed interface Entry {
        data object EmptySolution : Entry
        data class Header(val title: String) : Entry
        data class Kind(val category: TemplateCategory) : Entry
        /** A template of "Custom Templates": in Rider each one is an item of the left column. */
        data class Custom(val template: DotNetTemplate) : Entry
    }

    private var templates: List<DotNetTemplate> = DotNetTemplates.BUILT_IN
    private var sdks: List<String> = emptyList()
    private var selectedSdk: String? = null
    private var identities: Map<String, TemplateIdentities.Identity> = emptyMap()

    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Search" }
    private val leftModel = CollectionListModel<Entry>()
    private val leftList = JBList(leftModel)
    private var current: Entry? = null

    private val solutionName = JBTextField()
    private val projectName = JBTextField()
    private val directory = TextFieldWithBrowseButton()
    private val createdIn = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL; foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val sameDirectory = JBCheckBox("Put solution and project in the same directory")
    private val createDirectory = JBCheckBox("Create directory for the solution", true)
    private val git = JBCheckBox("Create Git repository")
    private val frameworkCombo = ComboBox<String>()
    // shown when there is one SDK to choose from too, as in Rider: an ActionLink hides itself when disabled by default
    private val sdkLink = ActionLink("SDK") { showSdkPopup() }.apply { setDropDownLinkIcon(); autoHideOnDisable = false }
    private lateinit var languageButton: SegmentedButton<String>
    private val templateModel = CollectionListModel<DotNetTemplate>()
    private val templateList = JBList(templateModel)
    // wraps to the width of the column: a label was cut off at the right ("…does not have any") and never made the dialog wider
    private lateinit var templateDescription: JEditorPane
    private val typeLabel = JBLabel()
    private val mainOptions = JPanel(BorderLayout())
    private val advancedOptions = JPanel(BorderLayout())
    private val shortNameLabel = JBLabel()
    private val identityLabel = JBLabel()
    private val groupLabel = JBLabel()
    private val authorLabel = JBLabel()
    private val classificationsLabel = JBLabel()
    private val optionsForm = TemplateOptionsForm()

    private lateinit var solutionRow: Row
    private lateinit var repositoryRow: Row
    private lateinit var projectRow: Row
    private lateinit var sameDirectoryRow: Row
    private lateinit var createDirectoryRow: Row
    private lateinit var frameworkRow: Row
    private lateinit var languageRow: Row
    private lateinit var templateRow: Row
    private lateinit var descriptionRow: Row
    private lateinit var typeRow: Row
    private lateinit var mainOptionsRow: Row
    private lateinit var identityRow: Row
    private lateinit var groupRow: Row
    private lateinit var authorRow: Row
    private lateinit var descriptionGroup: CollapsibleRow
    private lateinit var advancedGroup: CollapsibleRow

    /** The names follow the template (and the project name the solution name) until the user types into them. */
    private var solutionNameEdited = false
    private var projectNameEdited = false
    private var updating = false
    private var language: String? = null
    private var shownOptionsKey: String? = null

    /** `dotnet new <t> --help` one at a time: a kind of projects may hold a dozen templates whose frameworks are needed at once. */
    private val helpExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("New Solution: dotnet new --help", 1)

    init {
        title = if (isAddMode) "New Project" else "New Solution"
        setOKButtonText("Create")
        directory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(if (isAddMode) "Project Directory" else "Solution Directory"))
        directory.text = addTo?.baseDirectory ?: ProjectUtil.getBaseDir()
        leftList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        leftList.cellRenderer = LeftRenderer()
        templateList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        templateList.cellRenderer = TemplateRenderer()
        init()
        listen()
        rebuildLeft()
        select(leftModel.items.firstOrNull { it is Entry.Kind } ?: Entry.EmptySolution)
        if (loadFromCli) loadFromCli()
    }

    override fun getDimensionServiceKey(): String = if (isAddMode) "DotNet.NewProjectDialog" else "DotNet.NewSolutionDialog"
    override fun getPreferredFocusedComponent(): JComponent = if (isAddMode) projectName else solutionName

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineRight(JBColor.border())
            preferredSize = JBUI.size(250, 600)
            add(search.apply { border = JBUI.Borders.empty(6) }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(leftList, true), BorderLayout.CENTER)
            add(ActionLink("Install Templates...") { installTemplates() }.apply { border = JBUI.Borders.empty(10, 24) }, BorderLayout.SOUTH)
        }
        val form = panel {
            solutionRow = row(label("Solution name:", 'S')) { cell(solutionName).columns(COLUMNS_MEDIUM) }.visible(!isAddMode)
            projectRow = row(label("Project name:", 'P')) { cell(projectName).columns(COLUMNS_MEDIUM) }
            if (isAddMode) row(label("Directory:", 'D')) { cell(directory).columns(COLUMNS_LARGE) }
            else row(label("Solution directory:", 'D', 9)) { cell(directory).columns(COLUMNS_LARGE) }
            row("") { cell(createdIn) }
            createDirectoryRow = row("") { cell(createDirectory) }
            sameDirectoryRow = row("") { cell(sameDirectory) }
            repositoryRow = row("") { cell(git) }.visible(!isAddMode)
            addTo?.destination?.let { destination -> row(label("Add to:")) { label(destination) } }
            frameworkRow = row(label("Target framework:")) {
                cell(frameworkCombo)
                label("from")
                cell(sdkLink)
            }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
            languageRow = row(label("Language:")) { languageButton = segmentedButton(listOf("C#")) { text = it } }
            templateRow = row(label("Template:")) {
                cell(ScrollPaneFactory.createScrollPane(templateList).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER })
                    .align(AlignX.FILL)
            }
            descriptionRow = row("") {
                templateDescription = text("", MAX_LINE_LENGTH_WORD_WRAP).align(AlignX.FILL)
                    .applyToComponent { font = JBFont.small(); foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }.component
            }
            typeRow = row(label("Type:")) { cell(typeLabel) }
            mainOptionsRow = row("") { cell(mainOptions).align(AlignX.FILL) }
            descriptionGroup = collapsibleGroup("Template description") {
                row(label("Short name:")) { cell(shortNameLabel) }
                identityRow = row(label("Identity:")) { cell(identityLabel) }
                groupRow = row(label("Group ID:")) { cell(groupLabel) }
                authorRow = row(label("Author:")) { cell(authorLabel) }
                row(label("Classifications:")) { cell(classificationsLabel) }
            }.apply { expanded = true }
            advancedGroup = collapsibleGroup("Advanced Settings") {
                row { cell(advancedOptions).align(AlignX.FILL) }
            }.apply { expanded = true }
        }
        // the border on a panel of the DSL cut the texts of its cells by its width: it goes on a wrapper
        val padded = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(12, 16); add(form, BorderLayout.NORTH) }
        val right = ScrollPaneFactory.createScrollPane(padded, true).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(1000, 700)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.CENTER)
        }
    }

    /** Labels keep their colon, as in Rider: the Kotlin UI DSL of the new UI drops it from a `row("Text:")`. */
    private fun label(text: String, mnemonic: Char? = null, mnemonicIndex: Int = 0): JBLabel = JBLabel(text).apply {
        if (mnemonic != null) {
            displayedMnemonic = mnemonic.code
            displayedMnemonicIndex = mnemonicIndex
        }
    }

    private fun listen() {
        leftList.addListSelectionListener { e ->
            if (e.valueIsAdjusting || updating) return@addListSelectionListener
            val entry = leftList.selectedValue
            if (entry is Entry.Header || entry == null) {
                // a header is not a choice: back to what was chosen
                updating = true
                current?.let { leftList.setSelectedValue(it, false) }
                updating = false
            } else if (entry != current) select(entry)
        }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildLeft()
        })
        templateList.addListSelectionListener { e -> if (!e.valueIsAdjusting && !updating) templateChanged() }
        frameworkCombo.addActionListener { if (!updating) frameworkChanged() }
        languageButton.whenItemSelected { if (!updating && it != language) { language = it; optionsChanged() } }
        solutionName.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!updating) solutionNameEdited = true
                if (!projectNameEdited) withoutEvents { projectName.text = solutionName.text }
                updateCreatedIn()
            }
        })
        projectName.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!updating) projectNameEdited = true
                updateCreatedIn()
            }
        })
        directory.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateCreatedIn()
        })
        for (box in listOf(sameDirectory, createDirectory)) box.addActionListener { updateCreatedIn() }
    }

    private inline fun withoutEvents(action: () -> Unit) {
        val was = updating
        updating = true
        try { action() } finally { updating = was }
    }

    // ---- the left column

    /** The kinds that have templates matching the search; Empty Solution while the search is empty or names it. */
    private fun rebuildLeft() {
        val query = search.text.orEmpty()
        val found = templates.filter { NewSolution.matches(it, query) }
        val entries = buildList {
            if (!isAddMode && (query.isBlank() || "Empty Solution".contains(query.trim(), ignoreCase = true))) add(Entry.EmptySolution)
            for ((group, categories) in NewSolution.categories(found).groupBy { it.group }) {
                add(Entry.Header(group.title))
                for (category in categories) {
                    if (category == TemplateCategory.CUSTOM) NewSolution.templatesOf(category, found).forEach { add(Entry.Custom(it)) }
                    else add(Entry.Kind(category))
                }
            }
        }
        withoutEvents {
            leftModel.replaceAll(entries)
            current?.takeIf { it in entries }?.let { leftList.setSelectedValue(it, true) }
        }
        val selected = current
        if (selected == null || selected !in entries) entries.firstOrNull { it !is Entry.Header }?.let(::select)
        else if (selected !is Entry.EmptySolution) fillTemplates(selected)
    }

    private fun select(entry: Entry) {
        current = entry
        withoutEvents { leftList.setSelectedValue(entry, true) }
        val empty = entry is Entry.EmptySolution
        projectRow.visible(!empty)
        sameDirectoryRow.visible(!empty && !isAddMode)
        createDirectoryRow.visible(empty && !isAddMode)
        frameworkRow.visible(!empty)
        languageRow.visible(!empty)
        descriptionGroup.visible(!empty)
        advancedGroup.visible(!empty)
        if (empty) {
            templateRow.visible(false); descriptionRow.visible(false); typeRow.visible(false); mainOptionsRow.visible(false)
            defaultNames(null)
        } else fillTemplates(entry)
        updateCreatedIn()
    }

    private fun fillTemplates(entry: Entry) {
        val query = search.text.orEmpty()
        val list = when (entry) {
            is Entry.Kind -> NewSolution.templatesOf(entry.category, templates).filter { NewSolution.matches(it, query) }
            is Entry.Custom -> listOf(entry.template)
            else -> emptyList()
        }
        // supported first, the rest below "Not supported for the selected Target Framework"
        val ordered = list.filter { isSupported(it) } + list.filterNot { isSupported(it) }
        val previous = templateList.selectedValue?.shortName
        withoutEvents {
            templateModel.replaceAll(ordered)
            val keep = ordered.firstOrNull { it.shortName == previous } ?: ordered.firstOrNull { isSupported(it) } ?: ordered.firstOrNull()
            templateList.setSelectedValue(keep, true)
            templateList.visibleRowCount = (ordered.size + if (ordered.any { !isSupported(it) }) 1 else 0).coerceIn(1, 12)
        }
        // one template: no list, a "Type:" line, as Rider shows Console
        val single = ordered.size == 1
        templateRow.visible(!single && ordered.isNotEmpty())
        descriptionRow.visible(!single && ordered.isNotEmpty())
        typeRow.visible(single)
        mainOptionsRow.visible(true)
        loadSupport(ordered)
        templateChanged()
    }

    // ---- the template

    private val selectedTemplate: DotNetTemplate?
        get() = if (current is Entry.Kind || current is Entry.Custom) templateList.selectedValue ?: templateModel.items.singleOrNull() else null

    /** The language argument: null for a template of one language. */
    private fun languageOf(template: DotNetTemplate, chosen: String?): String? =
        if (template.languages.size > 1) (chosen?.takeIf { it in template.languages } ?: template.defaultLanguage ?: template.languages.first()) else null

    private fun templateChanged() {
        val template = selectedTemplate ?: return
        typeLabel.text = template.name
        val languages = template.languages.ifEmpty { listOfNotNull(template.defaultLanguage).ifEmpty { listOf("C#") } }
        withoutEvents {
            languageButton.items = languages
            language = language?.takeIf { it in languages } ?: template.defaultLanguage?.takeIf { it in languages } ?: languages.first()
            languageButton.selectedItem = language
        }
        shortNameLabel.text = template.shortName
        classificationsLabel.text = template.tags.joinToString("; ")
        defaultNames(template)
        optionsChanged()
    }

    /** The options, the description and the author come with `dotnet new <t> --help` of the template in the chosen language. */
    private fun optionsChanged() {
        val template = selectedTemplate ?: return
        val lang = languageOf(template, language)
        val identity = identities[TemplateIdentities.key(template.shortName, lang ?: template.defaultLanguage ?: template.languages.firstOrNull())]
            ?: identities[TemplateIdentities.key(template.shortName, "")]
        identityLabel.text = identity?.identity.orEmpty()
        groupLabel.text = identity?.groupIdentity.orEmpty()
        identityRow.visible(identity != null)
        groupRow.visible(identity?.groupIdentity != null)
        val key = TemplateIdentities.key(template.shortName, lang)
        val help = TemplateHelpCache.cached(template.shortName, lang)
        if (help == null) {
            shownOptionsKey = null
            describe(if (loadFromCli) "Loading the description of the template..." else "")
            authorRow.visible(false)
            showOptions(emptyList())
            if (loadFromCli) helpExecutor.execute {
                TemplateHelpCache.load(template.shortName, lang)
                ApplicationManager.getApplication().invokeLater({ if (!isDisposed && selectedTemplate == template) { optionsChanged(); templateList.repaint() } }, ModalityState.any())
            }
            return
        }
        describe(help.description)
        authorLabel.text = help.author
        authorRow.visible(help.author.isNotEmpty())
        if (key != shownOptionsKey) {
            shownOptionsKey = key
            showOptions(help.options)
        }
    }

    private fun showOptions(options: List<TemplateOption>) {
        optionsForm.reset(options)
        val (main, advanced) = KnownTemplateOptions.split(options)
        fun fill(holder: JPanel, subset: List<TemplateOption>) {
            holder.removeAll()
            if (subset.isNotEmpty()) {
                holder.add(optionsForm.rowsOf(subset, KnownTemplateOptions::labelOf, KnownTemplateOptions::suggestionsOf, contextHelp = true), BorderLayout.CENTER)
            }
            holder.revalidate()
            holder.repaint()
        }
        fill(mainOptions, main)
        fill(advancedOptions, advanced)
        mainOptionsRow.visible(main.isNotEmpty())
        advancedGroup.visible(advanced.isNotEmpty() && !isEmptySolution)
    }

    // ---- frameworks and SDKs

    private val framework: String? get() = frameworkCombo.selectedItem as? String

    private fun isSupported(template: DotNetTemplate): Boolean =
        NewSolution.supports(TemplateHelpCache.cached(template.shortName, languageOf(template, template.defaultLanguage))?.frameworks, framework)

    /** The frameworks of the templates of the kind, for the "not supported" section: one `--help` each, in the background. */
    private fun loadSupport(shown: List<DotNetTemplate>) {
        if (!loadFromCli) return
        for (template in shown) {
            val lang = languageOf(template, template.defaultLanguage)
            if (TemplateHelpCache.cached(template.shortName, lang) != null) continue
            helpExecutor.execute {
                TemplateHelpCache.load(template.shortName, lang)
                ApplicationManager.getApplication().invokeLater({
                    val shown = current
                    if (!isDisposed && shown != null && templateModel.items.contains(template)) fillTemplates(shown)
                }, ModalityState.any())
            }
        }
    }

    private fun frameworkChanged() {
        // the newest SDK that builds the framework; another one is pinned by global.json only when the user picks it
        selectedSdk = SdkVersions.sdksFor(framework, sdks).firstOrNull()
        updateSdkLink()
        current?.takeUnless { it is Entry.EmptySolution }?.let(::fillTemplates)
    }

    private fun updateSdkLink() {
        val sdk = selectedSdk
        sdkLink.text = if (sdk == null) "SDK" else SdkVersions.label(sdk, sdks)
        // a link even with one SDK, as in Rider: the popup shows which one builds the project
        sdkLink.isEnabled = SdkVersions.sdksFor(framework, sdks).isNotEmpty()
    }

    private fun showSdkPopup() {
        val choices = SdkVersions.sdksFor(framework, sdks)
        if (choices.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create { label, value, _ ->
                label.text = "SDK $value" + if (value == choices.first()) " (default)" else ""
            })
            .setSelectedValue(selectedSdk, true)
            .setItemChosenCallback { selectedSdk = it; updateSdkLink() }
            .createPopup()
            .showUnderneathOf(sdkLink)
    }

    private fun setFrameworks(monikers: List<String>) {
        withoutEvents {
            frameworkCombo.model = DefaultComboBoxModel(monikers.toTypedArray())
            frameworkCombo.selectedItem = monikers.firstOrNull()
        }
        frameworkChanged()
    }

    // ---- names and paths

    private fun defaultNames(template: DotNetTemplate?) {
        if (solutionNameEdited) return
        val parent = directory.text.trim()
        val name = NewSolution.defaultName(NewSolution.defaultBaseName(template)) { parent.isNotEmpty() && File(parent, it).exists() }
        withoutEvents { solutionName.text = name }
        if (!projectNameEdited) withoutEvents { projectName.text = name }
        updateCreatedIn()
    }

    private val isEmptySolution: Boolean get() = current is Entry.EmptySolution

    private fun updateCreatedIn() {
        if (isAddMode) {
            createdIn.text = NewSolution.projectCreatedIn(directory.text.trim(), projectName.text)
            return
        }
        createdIn.text = NewSolution.createdIn(
            directory.text.trim(), solutionName.text, projectName.text.takeUnless { isEmptySolution }, sameDirectory.isSelected, createDirectory.isSelected,
        )
    }

    // ---- loading

    private fun loadFromCli() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = DotNetTemplates.loadProjectTemplates()
            val versions = DotNetTemplates.loadSdkVersions()
            val monikers = DotNetTemplates.loadFrameworks()
            val ids = try {
                TemplateIdentities.cacheFile(File(System.getProperty("user.home")), versions.firstOrNull())?.let { TemplateIdentities.parse(it.readText()) }.orEmpty()
            } catch (e: Exception) {
                emptyMap()
            }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                templates = loaded
                sdks = versions
                identities = ids
                setFrameworks(monikers)
                rebuildLeft()
            }, ModalityState.any())
        }
    }

    private fun installTemplates() {
        val dialog = TemplatePackagesDialog(contentPanel)
        dialog.show()
        if (dialog.isChanged) {
            TemplateHelpCache.clear()
            loadFromCli()
        }
    }

    // ---- the result

    override fun doValidate(): ValidationInfo? {
        val template = selectedTemplate
        val problem = if (isAddMode) NewSolution.validateProject(
            projectName.text, directory.text.trim(), template != null, isNonEmptyDirectory = { it.isDirectory && !it.list().isNullOrEmpty() },
        ) else NewSolution.validate(
            solutionName.text, projectName.text.takeUnless { isEmptySolution }, directory.text.trim(), sameDirectory.isSelected, template != null,
            isNonEmptyDirectory = { it.isDirectory && !it.list().isNullOrEmpty() }, createDirectory = !isEmptySolution || createDirectory.isSelected,
        )
        if (problem != null) {
            val component = when (problem.field) {
                NewSolution.Field.SOLUTION_NAME -> solutionName
                NewSolution.Field.PROJECT_NAME -> projectName
                NewSolution.Field.DIRECTORY -> directory.textField
                NewSolution.Field.TEMPLATE -> templateList
            }
            return ValidationInfo(problem.message, component)
        }
        if (template != null && !isSupported(template)) return ValidationInfo("The template does not support ${framework.orEmpty()}", templateList)
        return null
    }

    private fun templateSettings(template: DotNetTemplate): DotNetTemplateSettings {
        val lang = languageOf(template, language)
        val help = TemplateHelpCache.cached(template.shortName, lang)
        return DotNetTemplateSettings(template, lang, framework?.takeIf { f -> help != null && f in help.frameworks }, optionsForm.arguments)
    }

    /** What Create of Add | New Project does; null before a template is chosen or out of that mode. */
    fun addProjectRequest(): AddProjectRequest? {
        if (!isAddMode) return null
        val template = selectedTemplate ?: return null
        val name = projectName.text.trim()
        return AddProjectRequest(File(directory.text.trim(), name), name, templateSettings(template))
    }

    /** What Create does; null before a template is chosen. */
    fun request(): NewSolution.Request? {
        val template = selectedTemplate
        if (!isEmptySolution && template == null) return null
        val settings = template?.let(::templateSettings)
        return NewSolution.Request(
            parent = directory.text.trim(),
            solutionName = solutionName.text,
            projectName = projectName.text.takeUnless { isEmptySolution },
            sameDirectory = sameDirectory.isSelected,
            git = git.isSelected,
            template = settings,
            pinnedSdk = selectedSdk?.takeIf { !isEmptySolution && it != sdks.firstOrNull() },
            createDirectory = !isEmptySolution || createDirectory.isSelected,
        )
    }

    override fun doOKAction() {
        if (isAddMode) {
            // the caller adds the project: addProjectRequest()
            if (addProjectRequest() != null) super.doOKAction()
            return
        }
        val request = request() ?: return
        super.doOKAction()
        // after the dialog is gone: opening the solution may close the frame of the current project
        ApplicationManager.getApplication().invokeLater({ DotNetProjectCreator.createSolution(project?.takeUnless { it.isDisposed }, request) }, ModalityState.nonModal())
    }

    override fun dispose() {
        helpExecutor.shutdownNow()
        super.dispose()
    }

    /** The description of the template as plain text: the pane is HTML, a `<T>` of the text must stay text. */
    fun describe(text: String) {
        if (!::templateDescription.isInitialized) return
        templateDescription.text = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")
    }

    // ---- for tests

    fun descriptionComponent(): JEditorPane = templateDescription

    /** The titles of the left column as shown: headers and kinds. */
    fun leftTitles(): List<String> = leftModel.items.map { titleOf(it) }

    fun selectKind(category: TemplateCategory?) = select(if (category == null) Entry.EmptySolution else Entry.Kind(category))

    fun shownTemplates(): List<String> = templateModel.items.map { it.shortName }

    fun isProjectNameShown(): Boolean = projectName.isVisible

    fun isSolutionNameShown(): Boolean = solutionName.isVisible

    /** The options of the template the form shows now (those whose condition holds). */
    fun shownOptionNames(): List<String> = optionsForm.shownOptions.map { it.name }

    fun selectTemplate(shortName: String) { templateModel.items.firstOrNull { it.shortName == shortName }?.let { templateList.setSelectedValue(it, true) } }

    fun validationMessage(): String? = doValidate()?.message

    fun setSearch(text: String) { search.text = text }

    fun setNames(solution: String, projectName: String?) {
        solutionName.text = solution
        projectName?.let { this.projectName.text = it }
    }

    fun setDirectory(path: String) { directory.text = path }

    fun createdInText(): String = createdIn.text

    // ---- rendering

    private fun titleOf(entry: Entry): String = when (entry) {
        Entry.EmptySolution -> "Empty Solution"
        is Entry.Header -> entry.title
        is Entry.Kind -> entry.category.title
        is Entry.Custom -> entry.template.name
    }

    private inner class LeftRenderer : ColoredListCellRenderer<Entry>() {
        override fun customizeCellRenderer(list: JList<out Entry>, value: Entry, index: Int, selected: Boolean, hasFocus: Boolean) {
            when (value) {
                is Entry.Header -> {
                    append(value.title, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    ipad = JBUI.insets(if (index == 0) 4 else 14, 6, 2, 6)
                }
                Entry.EmptySolution -> { icon = DotNetIcons.Solution; append("Empty Solution"); ipad = JBUI.insets(3, 10) }
                is Entry.Kind -> { icon = value.category.icon; append(value.category.title); ipad = JBUI.insets(3, 10) }
                is Entry.Custom -> { icon = TemplateCategory.CUSTOM.icon; append(value.template.name); ipad = JBUI.insets(3, 10) }
            }
        }
    }

    /** Templates with the icon of their kind; the unsupported ones gray, under a "Not supported for the selected Target Framework" line. */
    private inner class TemplateRenderer : ListCellRenderer<DotNetTemplate> {
        private val label = object : ColoredListCellRenderer<DotNetTemplate>() {
            override fun customizeCellRenderer(list: JList<out DotNetTemplate>, value: DotNetTemplate, index: Int, selected: Boolean, hasFocus: Boolean) {
                val supported = isSupported(value)
                val icon = NewSolution.categoryOf(value)?.icon ?: DotNetIcons.Project
                this.icon = if (supported) icon else IconLoader.getDisabledIcon(icon)
                append(value.name, if (supported) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        private val separator = SeparatorWithText().apply { caption = "Not supported for the selected Target Framework" }
        private val panel = JPanel(BorderLayout())

        override fun getListCellRendererComponent(list: JList<out DotNetTemplate>, value: DotNetTemplate, index: Int, selected: Boolean, focused: Boolean): Component {
            val row = label.getListCellRendererComponent(list, value, index, selected, focused)
            val model = list.model
            val firstUnsupported = !isSupported(value) && (index == 0 || isSupported(model.getElementAt(index - 1)))
            if (!firstUnsupported) return row
            panel.removeAll()
            panel.background = list.background
            panel.add(separator, BorderLayout.NORTH)
            panel.add(row, BorderLayout.CENTER)
            return panel
        }
    }
}
