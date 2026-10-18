package io.github.dotnetsupport.frameworks.unity

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import io.github.dotnetsupport.frameworks.unity.UnityConsole.Row
import io.github.dotnetsupport.frameworks.unity.UnityConsole.Severity
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The window "Unity" of a Unity project: the log of the editor's console (UNITY_PLAN.md, step 2), as Rider's "Unity Log" — the messages
 * with their icons, the stack of the selected one with links into the code, filters by severity, collapse, search, Clear and Clear on Play.
 */
class UnityLogToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = UnityEditorConnection.getInstance(project).root()?.path?.let(::File)
        val panel = UnityLogPanel(project, root, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "Log", false))
    }

    override fun shouldBeAvailable(project: Project): Boolean = UnityEditorConnection.getInstance(project).root() != null

    companion object {
        const val ID = "Unity"

        /** The window becomes available once the solution turns out to be Unity's (it is read after the window was asked). */
        fun makeAvailable(project: Project) {
            ToolWindowManager.getInstance(project).getToolWindow(ID)?.takeIf { !it.isAvailable }?.setAvailable(true, null)
        }

        fun show(project: Project) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            if (!window.isAvailable) window.setAvailable(true, null)
            window.activate(null)
        }
    }
}

class UnityLogPanel(private val project: Project, private val root: File?, parent: Disposable) : SimpleToolWindowPanel(false, true) {
    private val service = UnityConsoleService.getInstance(project)
    private val properties = PropertiesComponent.getInstance(project)
    private val model = CollectionListModel<Row>()
    private val list = JBList(model)
    private val details: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
        setViewer(true)
        root?.let { filters(listOf(UnityPlaceFilter(project, it))) }
    }.console
    private val search = SearchTextField(false)
    private var shownInDetails: Row? = null

    private var collapse by flag("collapse", false)
    private var autoScroll by flag("autoScroll", true)
    private val shown = mutableMapOf(
        Severity.ERROR to properties.getBoolean(key("errors"), true),
        Severity.WARNING to properties.getBoolean(key("warnings"), true),
        Severity.MESSAGE to properties.getBoolean(key("messages"), true),
    )

    init {
        Disposer.register(parent, details)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = Renderer()
        list.emptyText.text = "No messages from the Unity editor"
        list.emptyText.appendSecondaryText("Messages appear while the editor is connected (see the status bar)", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        list.addListSelectionListener { if (!it.valueIsAdjusting) showDetails(list.selectedValue) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigate(list.selectedValue)
        }.installOn(list)
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && navigate(list.selectedValue)) e.consume()
            }
        })
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        val splitter = OnePixelSplitter(true, "DotNet.UnityLog.splitter", 0.65f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(list, true)
            secondComponent = details.component
        }
        setContent(JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        })
        toolbar = ActionManager.getInstance().createActionToolbar("DotNetUnityLog", actions(), false).also { it.targetComponent = list }.component
        service.subscribe(parent, ::refresh)
        refresh()
    }

    private fun key(name: String) = "dotnet.unity.log.$name"

    private fun flag(name: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean = properties.getBoolean(key(name), default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = properties.setValue(key(name), value, default)
    }

    private fun refresh() {
        val query = search.text.trim()
        val rows = UnityConsole.rows(service.entries(), collapse) { entry ->
            shown[entry.severity] == true && (query.isEmpty() || entry.details.contains(query, ignoreCase = true))
        }
        val selected = list.selectedValue
        val atBottom = list.lastVisibleIndex >= model.size - 1
        model.replaceAll(rows)
        val keep = selected?.let { old -> rows.indexOfFirst { it.entry === old.entry } }?.takeIf { it >= 0 }
        when {
            keep != null -> list.selectedIndex = keep
            else -> showDetails(null)
        }
        if (autoScroll && atBottom && rows.isNotEmpty()) list.ensureIndexIsVisible(rows.size - 1)
    }

    private fun showDetails(row: Row?) {
        if (row?.entry === shownInDetails?.entry) return
        shownInDetails = row
        details.clear()
        row ?: return
        val type = when (row.entry.severity) {
            Severity.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
            Severity.WARNING -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            Severity.MESSAGE -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        details.print(row.entry.details + "\n", type)
        details.scrollTo(0)
    }

    private fun navigate(row: Row?): Boolean {
        val (place, ioFile) = row?.let { current -> root?.let { UnityConsole.target(current.entry, it) } } ?: return false
        val file = LocalFileSystem.getInstance().findFileByIoFile(ioFile) ?: return false
        OpenFileDescriptor(project, file, (place.line - 1).coerceAtLeast(0), (row.entry.column - 1).coerceAtLeast(0)).navigate(true)
        return true
    }

    private fun actions() = DefaultActionGroup(
        object : AnAction("Clear", "Clear the log; the errors of the last compilation stay, as in the console of Unity", AllIcons.Actions.GC), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = service.clear()
        },
        Separator.getInstance(),
        severity(Severity.ERROR, "errors", "Errors", AllIcons.General.Error),
        severity(Severity.WARNING, "warnings", "Warnings", AllIcons.General.Warning),
        severity(Severity.MESSAGE, "messages", "Messages", AllIcons.General.Information),
        Separator.getInstance(),
        toggle("Collapse", "Show identical messages as one, with their count", AllIcons.Actions.Collapseall, { collapse }) { collapse = it },
        toggle("Clear on Play", "Clear the log when the editor enters Play mode", AllIcons.Actions.Restart, { service.clearOnPlay }) { service.clearOnPlay = it },
        toggle("Autoscroll to the End", "Follow new messages while the list is at its end", AllIcons.RunConfigurations.Scroll_down, { autoScroll }) { autoScroll = it },
    )

    private fun severity(severity: Severity, name: String, title: String, icon: javax.swing.Icon) = object : ToggleAction(title, null, icon), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = shown[severity] == true
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            shown[severity] = state
            properties.setValue(key(name), state, true)
            refresh()
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.text = "Show $title (${service.count(severity)})"
        }
    }

    private fun toggle(text: String, description: String, icon: javax.swing.Icon, get: () -> Boolean, set: (Boolean) -> Unit) =
        object : ToggleAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = get()
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                set(state)
                refresh()
            }
        }

    private class Renderer : ColoredListCellRenderer<Row>() {
        private val time = SimpleDateFormat("HH:mm:ss")

        override fun customizeCellRenderer(list: JList<out Row>, value: Row?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            val entry = value.entry
            icon = when (entry.severity) {
                Severity.ERROR -> AllIcons.General.Error
                Severity.WARNING -> AllIcons.General.Warning
                Severity.MESSAGE -> AllIcons.General.Information
            }
            if (entry.time > 0) append(time.format(Date(entry.time)) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(entry.summary)
            if (value.count > 1) append("  ×${value.count}", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            toolTipText = if (entry.playMode) "Play mode" else null
        }
    }
}

/** Links in the details: the places of a stack (`(at Assets/Player.cs:30)`) and of a message of the compiler (`Assets/Player.cs(27,40)`). */
class UnityPlaceFilter(private val project: Project, private val root: File) : Filter {
    private val compilerPlace = Regex("""^([^()\r\n]+\.cs)\((\d+),(\d+)\)""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val start = entireLength - line.length
        val items = ArrayList<Filter.ResultItem>()
        for (place in UnityConsole.places(line)) link(place.path, place.line, 1)?.let { items += Filter.ResultItem(start + place.start, start + place.end, it) }
        compilerPlace.find(line)?.let { match ->
            link(match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toInt())?.let { items += Filter.ResultItem(start, start + match.range.last + 1, it) }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun link(path: String, line: Int, column: Int): OpenFileHyperlinkInfo? {
        val file = UnityConsole.resolve(root, path)?.let { LocalFileSystem.getInstance().findFileByIoFile(it) } ?: return null
        return OpenFileHyperlinkInfo(project, file, (line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0))
    }
}

/** Menu .NET | Show Unity Log, and the popup of the status bar's widget. */
class UnityShowLogAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && UnityEditorConnection.getInstance(project).root() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        UnityLogToolWindowFactory.show(e.project ?: return)
    }
}
