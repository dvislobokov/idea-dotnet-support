package io.github.dotnetsupport.newproject

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Key
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.nuget.NuGetClient
import io.github.dotnetsupport.nuget.NuGetPackageInfo
import io.github.dotnetsupport.nuget.NuGetService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** A template package of `dotnet new`: a NuGet package ([version] is set) or a local folder. */
class InstalledTemplatePackage(val id: String, val version: String?, val templates: List<String>)

class TemplatePackageUpdate(val id: String, val current: String, val latest: String)

object TemplatePackages {
    private val COLUMNS = Regex("""\s{2,}""")
    private val TEMPLATE = Regex("""^(.+?) \(([^()]+)\)(?: \S+)?$""")

    /**
     * Output of `dotnet new uninstall` without arguments (English: the labels are matched):
     * ```
     *    Avalonia.Templates
     *       Version: 11.0.10
     *       Details:
     *          Author: AvaloniaUI
     *       Templates:
     *          Avalonia .NET App (avalonia.app) C#
     *          Avalonia .NET App (avalonia.app) F#
     *       Uninstall Command:
     *          dotnet new uninstall Avalonia.Templates
     * ```
     */
    fun parseInstalled(output: String): List<InstalledTemplatePackage> {
        class Builder(val id: String) {
            var version: String? = null
            val templates = LinkedHashSet<String>()
        }

        val packages = ArrayList<Builder>()
        var section = ""
        for (line in output.lineSequence()) {
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            val text = line.trim()
            when {
                // the heading "Currently installed items:" has no indent
                indent == 0 -> Unit
                indent <= 3 -> { packages += Builder(text); section = "" }
                indent <= 6 -> {
                    section = text.substringBefore(':')
                    if (section == "Version") packages.lastOrNull()?.version = text.substringAfter(':').trim().ifEmpty { null }
                }
                // one line per language: "Avalonia .NET App (avalonia.app) C#"
                section == "Templates" -> TEMPLATE.find(text)?.let { packages.lastOrNull()?.templates?.add(it.groupValues[1]) }
            }
        }
        return packages.filter { it.id != "(No Items)" }.map { InstalledTemplatePackage(it.id, it.version, it.templates.toList()) }
    }

    /** The table of `dotnet new update --check-only`: `Package  Current  Latest`; empty when everything is up to date. */
    fun parseUpdates(output: String): List<TemplatePackageUpdate> {
        val lines = output.lines()
        val ruler = lines.indexOfFirst { it.isNotBlank() && it.all { c -> c == '-' || c == ' ' } }
        if (ruler < 0) return emptyList()
        return lines.drop(ruler + 1).takeWhile { it.isNotBlank() }.mapNotNull { line ->
            val cells = line.trim().split(COLUMNS)
            if (cells.size == 3) TemplatePackageUpdate(cells[0], cells[1], cells[2]) else null
        }
    }

    private fun english(vararg arguments: String) = DotNetCli.commandLine(null, *arguments).withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en")

    /** Blocking. */
    fun installed(): List<InstalledTemplatePackage> =
        runCatching { parseInstalled(DotNetCli.execute(english("new", "uninstall"), 60_000).stdout) }.getOrDefault(emptyList())

    /** Blocking; asks the feeds. */
    fun updates(): List<TemplatePackageUpdate> =
        runCatching { parseUpdates(DotNetCli.execute(english("new", "update", "--check-only"), 120_000).stdout) }.getOrDefault(emptyList())

    /** What `dotnet new search` looks through: the packages of nuget.org marked as templates. Blocking. */
    fun search(query: String, client: NuGetClient = NuGetClient()): List<NuGetPackageInfo> =
        client.search(query, includePrerelease = false, sources = listOf(NuGetService.NUGET_ORG), take = 50, packageType = "Template")
}

/** Search, install, update and uninstall template packages; opened from the template row of the New Project dialogs. */
class TemplatePackagesDialog(parent: Component) : DialogWrapper(parent, false) {
    /** True when the set of installed templates may have changed. */
    var isChanged = false
        private set

    private val searchField = SearchTextField(false)
    private val foundModel = DefaultListModel<NuGetPackageInfo>()
    private val found = JBList(foundModel)
    private val installedModel = DefaultListModel<InstalledTemplatePackage>()
    private val installedList = JBList(installedModel)
    private val log = JBTextArea(5, 0).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val installButton = JButton("Install")
    private val uninstallButton = JButton("Uninstall")
    private val checkButton = JButton("Check for Updates")
    private val updateButton = JButton("Update All")
    private var updates: Map<String, String> = emptyMap()
    private var isBusy = false

    init {
        title = ".NET Templates"
        init()
        reloadInstalled()
        search()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction.apply { putValue(Action.NAME, "Close") })
    override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

    override fun createCenterPanel(): JComponent {
        found.selectionMode = ListSelectionModel.SINGLE_SELECTION
        found.emptyText.text = "Nothing found"
        found.cellRenderer = object : ColoredListCellRenderer<NuGetPackageInfo>() {
            override fun customizeCellRenderer(list: JList<out NuGetPackageInfo>, value: NuGetPackageInfo, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${value.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (isInstalled(value.id)) append("  installed", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                append("  ${downloads(value.totalDownloads)}" + if (value.authors.isEmpty()) "" else " · ${value.authors}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  " + value.description.lineSequence().firstOrNull().orEmpty().take(120), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        found.addListSelectionListener { updateButtons() }
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) { search(); e.consume() }
            }
        })
        searchField.textEditor.emptyText.text = "Search nuget.org: avalonia, maui, clean architecture... Enter to search"

        installedList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        installedList.emptyText.text = "Only the templates of the SDK are installed"
        installedList.cellRenderer = object : ColoredListCellRenderer<InstalledTemplatePackage>() {
            override fun customizeCellRenderer(list: JList<out InstalledTemplatePackage>, value: InstalledTemplatePackage, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                value.version?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                updates[value.id]?.let { append("  → $it", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) }
                append("  " + value.templates.joinToString(", "), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
        installedList.addListSelectionListener { updateButtons() }

        installButton.addActionListener { found.selectedValue?.let { run("new", "install", it.id) } }
        uninstallButton.addActionListener { installedList.selectedValue?.let { run("new", "uninstall", it.id) } }
        updateButton.addActionListener { run("new", "update") }
        checkButton.addActionListener { checkUpdates() }
        updateButtons()

        val searchTab = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(found), BorderLayout.CENTER)
            add(buttons(installButton), BorderLayout.SOUTH)
        }
        val installedTab = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(ScrollPaneFactory.createScrollPane(installedList), BorderLayout.CENTER)
            add(buttons(uninstallButton, checkButton, updateButton), BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JBTabbedPane().apply {
                addTab("Search", searchTab)
                addTab("Installed", installedTab)
            }, BorderLayout.CENTER)
            add(ScrollPaneFactory.createScrollPane(log), BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(520))
        }
    }

    private fun buttons(vararg buttons: JButton) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { buttons.forEach { add(it) } }

    private fun isInstalled(id: String) = (0 until installedModel.size()).any { installedModel[it].id.equals(id, ignoreCase = true) }

    private fun updateButtons() {
        installButton.isEnabled = !isBusy && found.selectedValue?.let { !isInstalled(it.id) } == true
        uninstallButton.isEnabled = !isBusy && installedList.selectedValue != null
        checkButton.isEnabled = !isBusy && !installedModel.isEmpty
        updateButton.isEnabled = !isBusy && updates.isNotEmpty()
    }

    private fun background(work: () -> Unit) = ApplicationManager.getApplication().executeOnPooledThread(work)
    private fun onEdt(update: () -> Unit) = ApplicationManager.getApplication().invokeLater(update, ModalityState.any())

    private fun search() {
        val query = searchField.text.trim()
        found.setPaintBusy(true)
        background {
            val packages = TemplatePackages.search(query)
            onEdt {
                // a slow answer to an older query must not replace a newer one
                if (query == searchField.text.trim()) {
                    foundModel.clear()
                    packages.forEach(foundModel::addElement)
                }
                found.setPaintBusy(false)
            }
        }
    }

    private fun reloadInstalled() = background {
        val packages = TemplatePackages.installed()
        onEdt {
            installedModel.clear()
            packages.forEach(installedModel::addElement)
            updates = updates.filterKeys { id -> packages.any { it.id == id } }
            found.repaint()
            updateButtons()
        }
    }

    private fun checkUpdates() {
        setBusy(true)
        log.append("> dotnet new update --check-only\n")
        background {
            val available = TemplatePackages.updates()
            onEdt {
                updates = available.associate { it.id to it.latest }
                log.append(if (available.isEmpty()) "All template packages are up to date.\n" else available.joinToString("") { "${it.id}: ${it.current} → ${it.latest}\n" })
                installedList.repaint()
                setBusy(false)
            }
        }
    }

    /** Runs `dotnet <arguments>`, streaming the output into the log of the dialog. */
    private fun run(vararg arguments: String) {
        setBusy(true)
        log.append("> dotnet ${arguments.joinToString(" ")}\n")
        background {
            val exitCode = try {
                val handler = CapturingProcessHandler(DotNetCli.commandLine(null, *arguments))
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        if (outputType !== ProcessOutputTypes.SYSTEM) onEdt { log.append(event.text); log.caretPosition = log.document.length }
                    }
                })
                handler.runProcess(300_000).exitCode
            } catch (e: Exception) {
                onEdt { log.append(e.message.orEmpty() + "\n") }
                -1
            }
            onEdt {
                if (exitCode == 0) {
                    isChanged = true
                    if (arguments.contains("update")) updates = emptyMap()
                }
                setBusy(false)
                reloadInstalled()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        installedList.setPaintBusy(busy)
        updateButtons()
    }

    companion object {
        fun downloads(count: Long): String = when {
            count >= 1_000_000 -> "${count / 1_000_000}M downloads"
            count >= 1_000 -> "${count / 1_000}k downloads"
            else -> "$count downloads"
        }
    }
}
