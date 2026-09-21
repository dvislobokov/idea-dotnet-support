package io.github.dotnetsupport.nuget

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import io.github.dotnetsupport.solution.SolutionService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.Alarm
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBImageIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.msbuild.TargetFrameworks
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class NuGetToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = toolWindow.contentManager
        val packages = NuGetPanel(project, toolWindow)
        manager.addContent(manager.factory.createContent(packages, PACKAGES, false))
        // a changed source list changes what the search finds
        manager.addContent(manager.factory.createContent(NuGetSourcesPanel(project) { packages.loadSourcesAndReload() }, SOURCES, false))
        manager.addContent(manager.factory.createContent(NuGetFoldersPanel(project), FOLDERS, false))
        manager.addContent(manager.factory.createContent(NuGetLogPanel(project, toolWindow.disposable), LOG, false))
    }

    companion object {
        const val ID = "NuGet"
        const val PACKAGES = "Packages"
        const val SOURCES = "Sources"
        const val FOLDERS = "Folders"
        const val LOG = "Log"
    }
}

/** Where packages are managed: the whole solution ([file] is null) or one project. */
private class Scope(val name: String, val file: VirtualFile?) {
    override fun toString(): String = name
}

/** A row of the list: a section header, or a package that is installed somewhere in the scope and / or found in the feeds. */
private sealed class Row {
    class Header(val title: String) : Row()

    class Package(val id: String, val installedIn: Map<VirtualFile, InstalledPackage>, val found: NuGetPackageInfo?, @Volatile var latest: String? = null) : Row() {
        val isInstalled: Boolean get() = installedIn.isNotEmpty()

        /** One version, or "multiple" when the projects of the solution disagree. */
        val installedVersion: String?
            get() = installedIn.values.mapNotNull { it.version }.distinct().let { if (it.size > 1) "multiple" else it.firstOrNull() }

        val hasUpdate: Boolean
            get() {
                val newest = latest?.let(NuGetVersion::parse) ?: return false
                return installedIn.values.any { installed -> installed.version?.let(NuGetVersion::parse)?.let { it < newest } == true }
            }
    }
}

/**
 * Rider-like NuGet window. Left: one list with the installed packages of the scope on top and the packages of the feeds
 * below, each row with its actions. Right: the card of the selected package with the version selector, the projects it
 * can be installed into, its dependencies per target framework and links.
 */
private class NuGetPanel(private val project: Project, toolWindow: ToolWindow) : JPanel(BorderLayout()) {
    private val service = NuGetService.getInstance(project)
    private val scopeCombo = ComboBox<Scope>()
    private val searchField = SearchTextField(false)
    private val prerelease = JBCheckBox("Prerelease")
    private val listModel = DefaultListModel<Row>()
    private val list = JBList(listModel)
    private val details = ViewportWidthPanel()
    private val versionCombo = ComboBox<String>()

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, toolWindow.disposable)
    private val icons = PackageIcons { list.repaint(); details.repaint() }
    // responses of outdated requests are dropped; the list and the card are requested independently
    private val sourceRequests = AtomicInteger()
    private val listRequests = AtomicInteger()
    private val latestRequests = AtomicInteger()
    private val detailsRequests = AtomicInteger()
    @Volatile private var sources: List<String> = listOf(NuGetService.NUGET_ORG)
    private var shownDetails: NuGetPackageDetails? = null
    // remembered while the window lives, as in Rider: whoever wants the details keeps them open for every package
    private var infoExpanded = false
    private var dependenciesExpanded = false

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JBLabel("Packages for:")); add(scopeCombo)
            searchField.textEditor.columns = 30
            searchField.textEditor.emptyText.text = "Search packages"
            add(searchField); add(prerelease)
            add(ActionLink("Refresh") { loadSourcesAndReload() })
        }, BorderLayout.NORTH)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RowRenderer()
        list.fixedCellHeight = JBUI.scale(ROW_HEIGHT)
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = rowActionAt(e)?.invoke() ?: Unit
        })
        list.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                list.cursor = java.awt.Cursor.getPredefinedCursor(if (rowActionAt(e) != null) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR)
            }
        })
        val detailsPane = ScrollPaneFactory.createScrollPane(details, true)
        add(JBSplitter(false, 0.5f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(list)
            secondComponent = detailsPane
        }, BorderLayout.CENTER)
        add(sideToolbar(detailsPane), BorderLayout.WEST)

        prerelease.isSelected = NuGetSettings.getInstance().includePrerelease
        scopeCombo.addActionListener { reload() }
        prerelease.addActionListener { reload() }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ reload() }, SEARCH_DELAY_MS)
            }
        })
        list.addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }
        versionCombo.addActionListener { if (versionCombo.isPopupVisible || versionCombo.hasFocus()) selectedPackage()?.let { showDetails() } }

        service.requestListeners += ::selectRequestedProject
        service.packagesChangedListeners += ::reload
        reloadScopes()
        loadSourcesAndReload()
    }

    /** The vertical toolbar of Rider's window: Restore, Upgrade, the details pane, Settings, Help. */
    private fun sideToolbar(detailsPane: JComponent): JComponent {
        fun action(text: String, description: String, icon: javax.swing.Icon, perform: () -> Unit): AnAction = object : AnAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = perform()
        }
        val restore = action("Restore", "dotnet restore for the solution, or for the project chosen in \"Packages for\"", AllIcons.Actions.Download) {
            val target = (scopeCombo.selectedItem as? Scope)?.file ?: SolutionService.getInstance(project).solutionFiles().firstOrNull()
            if (target != null) service.restore(listOf(target))
        }
        val toggleDetails = object : ToggleAction("Show Package Details", "Show or hide the card of the selected package", AllIcons.Actions.PreviewDetails), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = detailsPane.isVisible
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                detailsPane.isVisible = state
                detailsPane.parent?.revalidate()
            }
        }
        val settings = action("NuGet Settings", "Settings | Tools | .NET | NuGet", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, NuGetSettingsConfigurable::class.java)
        }
        val help = action("Help", "NuGet in the .NET CLI", AllIcons.Actions.Help) { BrowserUtil.browse("https://learn.microsoft.com/nuget/consume-packages/install-use-packages-dotnet-cli") }
        val group = DefaultActionGroup(restore)
        ActionManager.getInstance().getAction("DotNet.NuGet.UpgradeSolution")?.let(group::add)
        group.addAll(toggleDetails, settings, help)
        return ActionManager.getInstance().createActionToolbar("DotNetNuGetSide", group, false).also { it.targetComponent = this }.component
    }

    // ---- scope and data ----

    private fun scopeProjects(): List<Pair<String, VirtualFile>> {
        val scope = scopeCombo.selectedItem as? Scope
        return service.projects().filter { scope?.file == null || it.second == scope.file }
    }

    private fun reloadScopes() {
        val scopes = listOf(Scope("Solution", null)) + service.projects().map { Scope(it.first, it.second) }
        scopeCombo.model = DefaultComboBoxModel(scopes.toTypedArray())
        selectRequestedProject()
    }

    private fun selectRequestedProject() {
        if (service.solutionRequested) {
            service.solutionRequested = false
            if (scopeCombo.itemCount > 0) scopeCombo.selectedIndex = 0
            searchField.text = ""
            return
        }
        val requested = service.requestedProject ?: return
        if ((0 until scopeCombo.itemCount).none { scopeCombo.getItemAt(it).file == requested }) reloadScopes()
        (0 until scopeCombo.itemCount).firstOrNull { scopeCombo.getItemAt(it).file == requested }?.let { scopeCombo.selectedIndex = it }
        searchField.text = ""
    }

    fun loadSourcesAndReload() = background(sourceRequests, { service.sources() }) { sources = it; reload() }

    private fun reload() {
        val query = searchField.text.trim()
        val includePrerelease = prerelease.isSelected
        val selectedId = selectedPackage()?.id

        // package id -> the projects of the scope that reference it
        val installed = LinkedHashMap<String, MutableMap<VirtualFile, InstalledPackage>>()
        for ((_, file) in scopeProjects()) for (pkg in service.installed(file)) installed.getOrPut(pkg.id.lowercase()) { LinkedHashMap() }[file] = pkg
        val installedRows = installed.values
            .map { Row.Package(it.values.first().id, it, null) }
            .filter { query.isEmpty() || it.id.contains(query, ignoreCase = true) }
            .sortedBy { it.id.lowercase() }
            .toMutableList()

        fun show(available: List<Row.Package>?) {
            listModel.clear()
            if (installedRows.isNotEmpty()) {
                listModel.addElement(Row.Header("Installed Packages (${installedRows.size})"))
                installedRows.forEach(listModel::addElement)
            }
            listModel.addElement(Row.Header(if (available == null) "Available Packages: loading..." else "Available Packages (${available.size})"))
            available?.forEach(listModel::addElement)
            val index = (0 until listModel.size()).firstOrNull { (listModel[it] as? Row.Package)?.id.equals(selectedId, ignoreCase = true) }
                ?: (0 until listModel.size()).firstOrNull { listModel[it] is Row.Package }
            if (index != null) list.selectedIndex = index else showDetails()
        }

        show(null)
        // an empty query returns the most popular packages, as nuget.org does
        background(listRequests, { service.client.search(query, includePrerelease, sources) }) { found ->
            // what is installed gets its feed metadata (icon, description); the rest is "available"
            val byId = found.associateBy { it.id.lowercase() }
            installedRows.replaceAll { row -> byId[row.id.lowercase()]?.let { Row.Package(row.id, row.installedIn, it, row.latest) } ?: row }
            show(found.filter { it.id.lowercase() !in installed }.map { Row.Package(it.id, emptyMap(), it, latest = it.version) })
        }
        background(latestRequests, { installedRows.associate { it.id to NuGetVersion.latest(service.client.versions(it.id, sources), includePrerelease) } }) { latest ->
            (0 until listModel.size()).mapNotNull { listModel[it] as? Row.Package }.filter { it.isInstalled }.forEach { it.latest = latest[it.id] ?: it.latest }
            list.repaint()
        }
    }

    // ---- list ----

    private fun selectedPackage(): Row.Package? = list.selectedValue as? Row.Package

    /** Actions of a row are icons at its right edge: [update] [install | remove]. Returns what a click at [e] does. */
    private fun rowActionAt(e: MouseEvent): (() -> Unit)? {
        val index = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return null
        val bounds = list.getCellBounds(index, index)?.takeIf { it.contains(e.point) } ?: return null
        val row = listModel[index] as? Row.Package ?: return null
        val fromRight = bounds.x + bounds.width - e.x
        val slot = JBUI.scale(ACTION_SLOT)
        return when {
            fromRight in 0 until slot -> if (row.isInstalled) ({ remove(row, row.installedIn.keys.toList()) }) else primaryInstall(row)
            fromRight in slot until slot * 2 && row.hasUpdate -> ({ install(row, row.installedIn.keys.toList(), row.latest) })
            else -> null
        }
    }

    /** "+" installs into the project of the scope; for the solution scope the projects are chosen in the card. */
    private fun primaryInstall(row: Row.Package): (() -> Unit)? {
        val target = (scopeCombo.selectedItem as? Scope)?.file ?: return null
        return { install(row, listOf(target), row.latest ?: row.found?.version) }
    }

    private inner class RowRenderer : ListCellRenderer<Row> {
        private val text = SimpleColoredComponent()
        private val version = SimpleColoredComponent()
        private val update = JBLabel()
        private val primary = JBLabel()
        private val panel = JPanel(BorderLayout()).apply {
            add(text, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(version)
                for (label in listOf(update, primary)) add(label.apply { preferredSize = JBUI.size(ACTION_SLOT, ROW_HEIGHT); horizontalAlignment = JBLabel.CENTER })
            }, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(list: JList<out Row>, row: Row, index: Int, selected: Boolean, focused: Boolean): Component {
            text.clear(); version.clear()
            update.icon = null; primary.icon = null
            panel.background = if (selected && row is Row.Package) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val foreground = if (selected && row is Row.Package) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            val gray = if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foreground) else SimpleTextAttributes.GRAYED_ATTRIBUTES
            text.isOpaque = false; version.isOpaque = false

            when (row) {
                is Row.Header -> {
                    text.icon = null
                    text.append(row.title, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UIUtil.getContextHelpForeground()))
                }
                is Row.Package -> {
                    text.icon = icons.get(row.found?.iconUrl, LIST_ICON)
                    text.iconTextGap = JBUI.scale(8)
                    text.append(row.id, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, foreground))
                    row.found?.description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { text.append("   $it", gray) }

                    if (row.isInstalled) {
                        version.append(row.installedVersion.orEmpty(), gray)
                        if (row.hasUpdate) {
                            version.append("  →  ${row.latest}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (selected) foreground else JBUI.CurrentTheme.Link.Foreground.ENABLED))
                            update.icon = AllIcons.Actions.Upload
                        } else version.append("  ", gray)
                        primary.icon = AllIcons.General.Remove
                    } else {
                        version.append("${row.found?.version.orEmpty()}  ", gray)
                        if ((scopeCombo.selectedItem as? Scope)?.file != null) primary.icon = AllIcons.General.Add
                    }
                }
            }
            return panel
        }
    }

    // ---- card ----

    private fun showDetails() {
        val row = selectedPackage()
        details.removeAll()
        shownDetails = null
        if (row == null) {
            details.add(JBLabel("Select a package", JBLabel.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
            details.revalidate(); details.repaint()
            return
        }

        val knownVersions = (row.found?.versions.orEmpty() + row.installedIn.values.mapNotNull { it.version } + listOfNotNull(row.latest)).distinct()
        if (versionCombo.getClientProperty(PACKAGE_KEY) != row.id) {
            versionCombo.putClientProperty(PACKAGE_KEY, row.id)
            setVersions(row, knownVersions)
            // the search result lists only the latest versions, an installed package none at all
            background(detailsRequests, { service.client.versions(row.id, sources) }) { all ->
                if (selectedPackage()?.id == row.id && all.isNotEmpty()) { setVersions(row, all); renderCard(row) }
            }
        }
        renderCard(row)
        loadNuspec(row)
    }

    private fun setVersions(row: Row.Package, versions: List<String>) {
        val installed = row.installedIn.values.mapNotNull { it.version }
        val sorted = versions.mapNotNull(NuGetVersion::parse).filter { prerelease.isSelected || !it.isPrerelease || it.text in installed }
            .distinct().sortedDescending().map { it.text }
        val previous = versionCombo.selectedItem as? String
        versionCombo.model = DefaultComboBoxModel(sorted.toTypedArray())
        versionCombo.selectedItem = previous?.takeIf { it in sorted } ?: row.latest?.takeIf { it in sorted } ?: sorted.firstOrNull()
    }

    private fun loadNuspec(row: Row.Package) {
        val version = versionCombo.selectedItem as? String ?: return
        background(detailsRequests, { service.client.details(row.id, version, sources) ?: error("no nuspec") }) { nuspec ->
            if (selectedPackage()?.id == row.id) { shownDetails = nuspec; renderCard(row) }
        }
    }

    /**
     * The card is a vertical stack that fills the width of the panel (GridBag with weightx = 1: without weights
     * GridBag centers its content, which is what made the first version of this card look scattered).
     */
    private fun renderCard(row: Row.Package) {
        val nuspec = shownDetails
        val info = row.found
        val content = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(8, 12) }
        var gridY = 0
        // not named `add`: a local function would win over JPanel.add inside the `apply` blocks below
        fun stack(component: JComponent, top: Int = 0, left: Int = 0) {
            content.add(component, GridBagConstraints().apply {
                gridx = 0; gridy = gridY++; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST
                insets = JBUI.insets(top, left, 0, 0)
            })
        }

        // header: icon, name, "verified" mark
        stack(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel(icons.get(info?.iconUrl, CARD_ICON)).apply { border = JBUI.Borders.emptyRight(8) })
            add(JBLabel(row.id).apply { font = JBUI.Fonts.label().asBold().biggerOn(1f) })
            if (info?.isVerified == true) add(JBLabel(AllIcons.General.InspectionsOK).apply { border = JBUI.Borders.emptyLeft(6); toolTipText = "The owner of the package ID prefix is verified" })
        })

        // version selector with the actions that apply to every project at once
        val selectedVersion = versionCombo.selectedItem as? String
        val allProjects = service.projects().map { it.second }
        val installedIn = allProjects.filter { installedPackage(row, it) != null }
        val outdated = installedIn.filter { installedPackage(row, it)?.version != selectedVersion }
        stack(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel("Version").apply { font = JBUI.Fonts.label().asBold(); border = JBUI.Borders.empty(0, 16, 0, 8) }, BorderLayout.WEST)
            add(versionCombo, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(iconButton(AllIcons.General.Add, "Install $selectedVersion into all projects", selectedVersion != null && installedIn.size < allProjects.size) {
                    install(row, allProjects - installedIn.toSet(), selectedVersion)
                })
                add(iconButton(AllIcons.Actions.Upload, "Change to $selectedVersion in all projects that have the package", selectedVersion != null && outdated.isNotEmpty()) {
                    install(row, outdated, selectedVersion)
                })
                add(iconButton(AllIcons.Actions.GC, "Remove from all projects", installedIn.isNotEmpty()) { remove(row, installedIn) })
            }, BorderLayout.EAST)
        }, top = 10)

        // collapsible sections; the collapsed header carries a one-line summary
        val description = (nuspec?.description ?: info?.description).orEmpty().trim()
        stack(sectionHeader("Info", description.lineSequence().firstOrNull().orEmpty(), infoExpanded) { infoExpanded = !infoExpanded; renderCard(row) }, top = 8)
        if (infoExpanded) {
            if (description.isNotEmpty()) stack(wrappedText(description), top = 4, left = SECTION_INDENT)
            val authors = info?.authors?.takeIf { it.isNotBlank() } ?: nuspec?.authors.orEmpty()
            val facts = listOfNotNull(
                authors.ifBlank { null }?.let { "Authors: $it" },
                info?.totalDownloads?.takeIf { it > 0 }?.let { "Downloads: " + NumberFormat.getCompactNumberInstance().format(it) },
                nuspec?.license?.takeIf { !it.startsWith("http") }?.let { "License: $it" },
                info?.tags?.takeIf { it.isNotEmpty() }?.let { "Tags: " + it.joinToString(", ") },
            )
            facts.forEach { stack(JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }, top = 4, left = SECTION_INDENT) }
            stack(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                val links = listOfNotNull(
                    (nuspec?.projectUrl ?: info?.projectUrl)?.let { "Project page" to it },
                    info?.licenseUrl?.let { "License" to it },
                    "nuget.org" to "https://www.nuget.org/packages/${row.id}",
                )
                links.forEach { (title, url) -> add(ActionLink(title) { BrowserUtil.browse(url) }.apply { border = JBUI.Borders.emptyRight(16) }) }
            }, top = 6, left = SECTION_INDENT)
        }

        val groups = nuspec?.dependencyGroups.orEmpty()
        val dependenciesSummary = when {
            nuspec == null -> "loading..."
            groups.all { it.second.isEmpty() } -> "None"
            else -> groups.flatMap { it.second }.map { it.substringBefore(' ') }.distinct().let { names -> names.take(3).joinToString(", ") + if (names.size > 3) ", +${names.size - 3}" else "" }
        }
        stack(sectionHeader("Dependencies", dependenciesSummary, dependenciesExpanded) { dependenciesExpanded = !dependenciesExpanded; renderCard(row) }, top = 4)
        if (dependenciesExpanded) {
            for ((framework, packages) in groups) {
                stack(JBLabel(if (framework.isEmpty()) "Any framework" else TargetFrameworks.displayName(framework)).apply { foreground = UIUtil.getContextHelpForeground() }, top = 4, left = SECTION_INDENT)
                (packages.ifEmpty { listOf("no dependencies") }).forEach { stack(JBLabel(it), top = 2, left = SECTION_INDENT * 2) }
            }
        }

        stack(projectsTable(row), top = 12)

        details.removeAll()
        details.add(content, BorderLayout.NORTH)
        details.revalidate(); details.repaint()
    }

    private fun installedPackage(row: Row.Package, projectFile: VirtualFile): InstalledPackage? =
        // a project outside of the scope may have the package too: the card shows the whole solution
        row.installedIn[projectFile] ?: service.installed(projectFile).find { it.id.equals(row.id, ignoreCase = true) }

    /** `> Title  summary` that toggles on click. */
    private fun sectionHeader(title: String, summary: String, expanded: Boolean, toggle: () -> Unit): JComponent =
        SimpleColoredComponent().apply {
            isOpaque = false
            icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            iconTextGap = JBUI.scale(8)
            append(title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (!expanded && summary.isNotBlank()) append("  $summary", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggle()
            })
        }

    private fun wrappedText(text: String): JComponent = com.intellij.ui.components.JBTextArea(text).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false; border = null
        font = JBUI.Fonts.label()
    }

    /** A bordered icon button like the ones of the Rider NuGet window; a disabled action keeps its place, so the columns stay aligned. */
    private fun iconButton(icon: Icon, tooltip: String, enabled: Boolean, action: () -> Unit): JComponent =
        JBLabel(if (enabled) icon else com.intellij.openapi.util.IconLoader.getDisabledIcon(icon)).apply {
            horizontalAlignment = JBLabel.CENTER
            preferredSize = JBUI.size(BUTTON_SIZE, BUTTON_SIZE)
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
            if (enabled) {
                toolTipText = tooltip
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = action()
                })
            }
        }

    /**
     * Every project of the solution: name on the left, the installed version in a column that starts in the middle,
     * the actions for the version selected above at the right edge.
     */
    private fun projectsTable(row: Row.Package): JComponent = JPanel(GridBagLayout()).apply {
        isOpaque = false
        val selectedVersion = versionCombo.selectedItem as? String
        for ((index, entry) in service.projects().withIndex()) {
            val (name, file) = entry
            val installed = installedPackage(row, file)
            fun cell(column: Int, weight: Double, component: JComponent, anchorTo: Int = GridBagConstraints.WEST) = add(component, GridBagConstraints().apply {
                gridx = column; gridy = index; weightx = weight
                fill = if (weight > 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
                anchor = anchorTo
                insets = JBUI.insets(3, 0)
            })
            cell(0, 1.0, JBLabel(name, DotNetIcons.forProjectFile(file.name), JBLabel.LEFT).apply { iconTextGap = JBUI.scale(8) })
            cell(1, 1.0, JBLabel(installed?.version.orEmpty()))

            val current = installed?.version
            val isDowngrade = current != null && selectedVersion != null &&
                NuGetVersion.parse(selectedVersion)?.let { v -> NuGetVersion.parse(current)?.let { v < it } } == true
            cell(2, 0.0, JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                if (installed == null) {
                    add(iconButton(AllIcons.General.Add, "Install $selectedVersion", selectedVersion != null) { install(row, listOf(file), selectedVersion) })
                } else {
                    add(iconButton(
                        if (isDowngrade) AllIcons.Actions.Download else AllIcons.Actions.Upload,
                        (if (isDowngrade) "Downgrade to " else "Update to ") + selectedVersion,
                        selectedVersion != null && selectedVersion != current,
                    ) { install(row, listOf(file), selectedVersion) })
                    add(iconButton(AllIcons.Actions.GC, "Remove from $name", true) { remove(row, listOf(file)) })
                }
            }, GridBagConstraints.EAST)
        }
    }

    // ---- operations ----

    private fun install(row: Row.Package, projects: List<VirtualFile>, version: String?) {
        service.install(projects, row.id, version ?: return) { reload() }
    }

    private fun remove(row: Row.Package, projects: List<VirtualFile>) {
        if (projects.size > 1) {
            val answer = Messages.showYesNoDialog(project, "Remove ${row.id} from ${projects.size} projects?", "Remove Package", Messages.getQuestionIcon())
            if (answer != Messages.YES) return
        }
        service.remove(projects, row.id) { reload() }
    }

    /** Runs [task] on a pooled thread and [onResult] on EDT, unless a newer request of the same kind has been made meanwhile. */
    private fun <T> background(requests: AtomicInteger, task: () -> T, onResult: (T) -> Unit) {
        val request = requests.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(task).getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ if (request == requests.get() && !project.isDisposed) onResult(result) }, ModalityState.any())
        }
    }

    private companion object {
        const val SEARCH_DELAY_MS = 400
        const val ROW_HEIGHT = 28
        const val ACTION_SLOT = 26
        const val LIST_ICON = 16
        const val CARD_ICON = 24
        const val BUTTON_SIZE = 26
        const val SECTION_INDENT = 24
        const val PACKAGE_KEY = "dotnet.nuget.package"
    }
}

/** Content of a scroll pane that is as wide as the viewport (so that text wraps and right-aligned buttons reach the edge) and scrolls only vertically. */
private class ViewportWidthPanel : JPanel(BorderLayout()), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/** Package icons from the feeds, downloaded once per size and scaled; the NuGet logo until (and unless) they arrive. */
private class PackageIcons(private val onLoaded: () -> Unit) {
    private val cache = ConcurrentHashMap<String, Icon>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun get(url: String?, size: Int): Icon {
        val fallback = if (size <= 16) DotNetIcons.NuGet else com.intellij.util.IconUtil.scale(DotNetIcons.NuGet, null, size / 16f)
        if (url == null) return fallback
        val key = "$size|$url"
        cache[key]?.let { return it }
        if (loading.add(key)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val icon = runCatching {
                    val bytes = HttpRequests.request(url).connectTimeout(10_000).readTimeout(20_000).readBytes(null)
                    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("not an image")
                    JBImageIcon(ImageUtil.scaleImage(image, JBUI.scale(size), JBUI.scale(size)))
                }.getOrNull() ?: return@executeOnPooledThread // stays in `loading`: a broken icon is not requested again
                cache[key] = icon
                ApplicationManager.getApplication().invokeLater(onLoaded, ModalityState.any())
            }
        }
        return fallback
    }
}

/** "Manage NuGet Packages" on a project of the Solution view. */
class ManageNuGetPackagesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && SolutionContext.fromSelection(e)?.projectFile != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = NuGetService.getInstance(project)
        service.requestedProject = SolutionContext.fromSelection(e)?.projectFile ?: return
        ToolWindowManager.getInstance(project).getToolWindow(NuGetToolWindowFactory.ID)?.activate {
            service.requestListeners.toList().forEach { it() }
        }
    }
}
