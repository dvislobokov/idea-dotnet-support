package io.github.dotnetsupport.monitor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Memory Dump of the .NET Monitor: `dotnet-dump collect`, then one `dotnet-dump analyze` behind [MemoryDumpDialog]. */
object MemoryDumps {
    private val TIME = DateTimeFormatter.ofPattern("HHmmss")

    fun collectCommand(tool: File, pid: Long, file: File): GeneralCommandLine =
        GeneralCommandLine(tool.path, "collect", "--process-id", pid.toString(), "--output", file.path, "--type", "Heap").withCharset(Charsets.UTF_8)

    /** A dump takes as much disk as the process takes memory: it lives in the temporary directory of the IDE while its dialog is open. */
    fun dumpFile(processTitle: String, pid: Long, at: LocalTime = LocalTime.now()): File =
        File(PathManager.getTempPath(), "dotnet-dumps/${FileUtil.sanitizeFileName(processTitle.substringBefore(" ("), false)}-$pid-${at.format(TIME)}.dmp")

    fun take(project: Project, pid: Long, processTitle: String) {
        val tool = DotNetTool.DUMP.find() ?: return DotNetTool.DUMP.offerInstallation(project, "Memory Dump") { take(project, pid, processTitle) }
        val file = dumpFile(processTitle, pid)
        val title = "Taking a memory dump of $processTitle"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var analyzer: DumpAnalyzer? = null
            private var types: List<SosType> = emptyList()
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                file.parentFile.mkdirs()
                val command = collectCommand(tool, pid, file)
                indicator.text2 = DotNetCli.displayString(command)
                val collected = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, 600_000, true)
                if (collected.exitCode != 0 || collected.isTimeout || !file.isFile) {
                    failure = (collected.stderr.ifBlank { collected.stdout }).trim().lines().takeLast(8).joinToString("\n").ifEmpty { "exit code ${collected.exitCode}" }
                    return
                }
                indicator.text = "Reading the memory dump of $processTitle"
                val opened = DumpAnalyzer(tool, file).also { analyzer = it }
                try {
                    opened.loaded.get(180, TimeUnit.SECONDS)
                    types = Sos.heapStat(opened.run("dumpheap -stat").get(600, TimeUnit.SECONDS))
                    if (types.isEmpty()) failure = "The dump has no managed heap: is it a .NET process?"
                } catch (e: Exception) {
                    failure = (e.cause ?: e).message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                val opened = analyzer
                val problem = failure
                if (problem != null || opened == null) {
                    discard(opened, file)
                    return DotNetCli.notifyError(project, title, problem ?: "No dump")
                }
                MemoryDumpDialog(project, processTitle, opened, types).show()
            }

            override fun onCancel() = discard(analyzer, file)
            override fun onThrowable(error: Throwable) {
                discard(analyzer, file)
                super.onThrowable(error)
            }
        })
    }

    fun discard(analyzer: DumpAnalyzer?, file: File) {
        ApplicationManager.getApplication().executeOnPooledThread {
            analyzer?.dispose()
            FileUtil.delete(file)
        }
    }
}

/**
 * The heap of a memory dump as dotMemory shows it, on the SOS commands: the types (`dumpheap -stat`), the objects of one (`dumpheap -mt`),
 * for an object who keeps it alive (`gcroot`), its fields (`dumpobj`) and what it keeps alive itself (`objsize`); any other SOS command
 * in the console tab (`dumpasync`, `syncblk`, `clrstack -all`...).
 */
class MemoryDumpDialog(
    private val project: Project, processTitle: String, private val analyzer: DumpAnalyzer, types: List<SosType>,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val typeModel = TypeModel(types)
    private val typeTable = JBTable(typeModel)
    private val typeSorter = TableRowSorter(typeModel)
    private val objectModel = ObjectModel()
    private val objectTable = JBTable(objectModel)
    private val objectsNote = JBLabel()
    private val filter = SearchTextField(false)
    private val objectTitle = JBLabel("Select an object").apply { componentStyle = UIUtil.ComponentStyle.LARGE }
    private val objectSummary = JBLabel()
    private val back = JButton(AllIcons.Actions.Back).apply { toolTipText = "Back to the previous object"; isEnabled = false }
    private val rootsModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val roots = Tree(rootsModel)
    private val fieldModel = FieldModel()
    private val fieldTable = JBTable(fieldModel)
    private val command = JBTextField()
    private val console = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create("Monospaced", font.size) }
    private val history = ArrayDeque<String>()
    private var current: String? = null

    init {
        title = "Memory of $processTitle, ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}"
        init()
        Disposer.register(disposable) { MemoryDumps.discard(analyzer, analyzer.dump) }
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Save Dump As...") {
            override fun actionPerformed(e: ActionEvent) = saveDump()
        },
        okAction.apply { putValue(Action.NAME, "Close") },
    )

    override fun createCenterPanel(): JComponent {
        val total = typeModel.types.sumOf { it.totalSize }
        val summary = JBLabel("<html>${ChartFormats.bytes(total.toDouble())} in ${String.format("%,d", typeModel.types.sumOf { it.count })} objects of " +
            "${typeModel.types.size} types. Pick a type, then an object: who holds it and what is in it.</html>")

        typeTable.rowSorter = typeSorter
        typeTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        for (column in 1..2) typeTable.columnModel.getColumn(column).cellRenderer = NumberRenderer(bytes = column == 2)
        typeTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(380)
        typeSorter.toggleSortOrder(2)
        typeSorter.toggleSortOrder(2) // the largest first
        typeTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) selectedType()?.let(::showObjects) }
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val text = filter.text.trim()
                typeSorter.rowFilter = if (text.isEmpty()) null else RowFilter.regexFilter("(?i)" + Regex.escape(text), 0)
            }
        })
        filter.textEditor.emptyText.text = "Filter types"

        objectTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        objectTable.columnModel.getColumn(1).cellRenderer = NumberRenderer(bytes = true)
        objectTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) objectModel.objects.getOrNull(objectTable.selectedRow)?.let { open(it.address, remember = false) } }

        roots.isRootVisible = false
        roots.showsRootHandles = true
        roots.cellRenderer = RootRenderer()
        onDoubleClick(roots) { (roots.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject.let { it as? SosRootStep }?.let { open(it.address) } }
        fieldTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(220)
        fieldTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(220)
        onDoubleClick(fieldTable) { fieldModel.fields.getOrNull(fieldTable.selectedRow)?.reference?.let(::open) }
        back.addActionListener { history.removeLastOrNull()?.let { open(it, remember = false) } }

        command.emptyText.text = "SOS command: dumpasync, syncblk, clrstack -all, finalizequeue, dumpheap -strings..."
        command.addActionListener { runCommand() }

        val left = JBSplitter(true, 0.62f).apply {
            firstComponent = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(filter, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(typeTable), BorderLayout.CENTER)
            }
            secondComponent = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(objectsNote, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(objectTable), BorderLayout.CENTER)
            }
        }
        val tabs = JBTabbedPane().apply {
            addTab("Who Holds It", ScrollPaneFactory.createScrollPane(roots))
            addTab("Fields", ScrollPaneFactory.createScrollPane(fieldTable))
            addTab("SOS Console", JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(command, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(console), BorderLayout.CENTER)
            })
        }
        val right = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(2))).apply {
                add(back, BorderLayout.WEST)
                add(objectTitle, BorderLayout.CENTER)
                add(objectSummary, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(summary, BorderLayout.NORTH)
            add(JBSplitter(false, 0.45f).apply { firstComponent = left; secondComponent = right }, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(1200), JBUI.scale(700))
        }
    }

    private fun selectedType(): SosType? = typeTable.selectedRow.takeIf { it >= 0 }?.let { typeModel.types[typeTable.convertRowIndexToModel(it)] }

    private fun showObjects(type: SosType) {
        objectsNote.text = "Objects of ${type.name}: reading..."
        analyzer.run("dumpheap -mt ${type.methodTable}").whenComplete { output, error ->
            onEdt {
                if (selectedType() !== type) return@onEdt
                val objects = output?.let(Sos::objects).orEmpty()
                objectModel.objects = objects.take(MAX_OBJECTS)
                objectModel.fireTableDataChanged()
                objectsNote.text = when {
                    error != null -> "Objects of ${type.name}: ${error.cause?.message ?: error.message}"
                    objects.size > MAX_OBJECTS -> "The first $MAX_OBJECTS of ${String.format("%,d", objects.size)} objects of ${type.name}"
                    else -> "${String.format("%,d", objects.size)} objects of ${type.name}"
                }
                if (objects.isNotEmpty()) objectTable.setRowSelectionInterval(0, 0)
            }
        }
    }

    /** An object: who keeps it alive, its fields, what it keeps alive. [remember]: Back returns to the object shown before. */
    private fun open(address: String, remember: Boolean = true) {
        if (address == current) return
        if (remember) current?.let { history.addLast(it) }
        current = address
        back.isEnabled = history.isNotEmpty()
        objectTitle.text = address
        objectSummary.text = "Reading..."
        rootsModel.setRoot(DefaultMutableTreeNode("Looking for the roots..."))
        fieldModel.fields = emptyList()
        fieldModel.fireTableDataChanged()

        val details = analyzer.run("dumpobj $address")
        val size = analyzer.run("objsize $address")
        val holders = analyzer.run("gcroot $address")
        details.thenCombine(size.handle { output, _ -> output }) { output, retained -> output to retained }.whenComplete { result, error ->
            onEdt {
                if (current != address) return@onEdt
                val parsed = result?.first?.let(Sos::dumpObj)
                if (parsed == null) {
                    objectSummary.text = error?.let { (it.cause ?: it).message } ?: "Not an object"
                    return@onEdt
                }
                objectTitle.text = "${parsed.type}  $address"
                val retained = result.second?.let(Sos::total)?.second
                objectSummary.text = "<html>${ChartFormats.bytes(parsed.size.toDouble())} itself" +
                    (retained?.let { ", keeps alive ${ChartFormats.bytes(it.toDouble())}" } ?: "") +
                    (parsed.stringValue?.let { " · <code>${StringUtil.escapeXmlEntities(it.take(200))}</code>" } ?: "") + "</html>"
                fieldModel.fields = parsed.fields
                fieldModel.fireTableDataChanged()
            }
        }
        holders.whenComplete { output, error ->
            onEdt {
                if (current != address) return@onEdt
                rootsModel.setRoot(rootsTree(output?.let(Sos::gcRoots), error))
                for (row in 0 until 200) if (row < roots.rowCount) roots.expandRow(row)
            }
        }
    }

    private fun rootsTree(paths: List<SosRootPath>?, error: Throwable?): DefaultMutableTreeNode {
        val top = DefaultMutableTreeNode()
        when {
            error != null -> top.add(DefaultMutableTreeNode((error.cause ?: error).message))
            paths.isNullOrEmpty() -> top.add(DefaultMutableTreeNode("No roots: nothing keeps the object alive, the next garbage collection frees it"))
            else -> for (path in paths) {
                // the root first, then each object that refers to the next, down to the object itself: read top-down as in dotMemory
                val rootNode = DefaultMutableTreeNode(path.root)
                var parent = rootNode
                for (step in path.steps) DefaultMutableTreeNode(step).also { parent.add(it); parent = it }
                top.add(rootNode)
            }
        }
        return top
    }

    private fun runCommand() {
        val text = command.text.trim().takeIf { it.isNotEmpty() } ?: return
        console.append("> $text\n")
        analyzer.run(text).whenComplete { output, error ->
            onEdt {
                console.append((output ?: "Error: ${(error?.cause ?: error)?.message}") + "\n\n")
                console.caretPosition = console.document.length
            }
        }
        command.selectAll()
    }

    private fun saveDump() {
        val descriptor = FileSaverDescriptor("Save Memory Dump", "The dump opens in Visual Studio, WinDbg, PerfView and dotnet-dump analyze", "dmp")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(analyzer.dump.name)?.file ?: return
        val source = analyzer.dump
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Saving the memory dump", false) {
            override fun run(indicator: ProgressIndicator) = FileUtil.copy(source, target)
            override fun onThrowable(error: Throwable) = DotNetCli.notifyError(project, "Save Memory Dump", error.message.orEmpty())
        })
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())

    private fun onDoubleClick(component: JComponent, action: () -> Unit) {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                action()
                return true
            }
        }.installOn(component)
    }

    private class RootRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is SosRootStep -> {
                    append(item.type)
                    append("  ${item.address}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    item.note?.let { append("  ($it)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                    icon = AllIcons.Debugger.Value
                }
                is String -> {
                    append(item, if (leaf) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    icon = if (leaf) null else AllIcons.Nodes.Static
                }
            }
        }
    }

    private class NumberRenderer(private val bytes: Boolean) : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.RIGHT
        }

        override fun setValue(value: Any?) {
            val number = value as? Long ?: 0
            text = if (bytes) ChartFormats.bytes(number.toDouble()) else String.format("%,d", number)
        }
    }

    private class TypeModel(val types: List<SosType>) : AbstractTableModel() {
        override fun getRowCount(): Int = types.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = listOf("Type", "Objects", "Bytes")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) String::class.java else java.lang.Long::class.java
        override fun getValueAt(row: Int, column: Int): Any = types[row].let { if (column == 0) it.name else if (column == 1) it.count else it.totalSize }
    }

    private class ObjectModel : AbstractTableModel() {
        var objects: List<SosObject> = emptyList()
        override fun getRowCount(): Int = objects.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = listOf("Address", "Size")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) String::class.java else java.lang.Long::class.java
        override fun getValueAt(row: Int, column: Int): Any = objects[row].let { if (column == 0) it.address else it.size }
    }

    private class FieldModel : AbstractTableModel() {
        var fields: List<SosField> = emptyList()
        override fun getRowCount(): Int = fields.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = listOf("Field", "Type", "Value (double-click a reference to open it)")[column]
        override fun getValueAt(row: Int, column: Int): Any = fields[row].let {
            when (column) {
                0 -> if (it.isStatic) "${it.name} (static)" else it.name
                1 -> it.type
                else -> if (it.isValueType || it.reference != null) it.value else "null"
            }
        }
    }

    private companion object {
        const val MAX_OBJECTS = 1000
    }
}

