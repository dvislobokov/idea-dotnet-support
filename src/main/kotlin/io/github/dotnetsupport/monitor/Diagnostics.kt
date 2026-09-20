package io.github.dotnetsupport.monitor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.unscramble.AnalyzeStacktraceUtil
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetTool
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/** Snapshots of a running process taken with the diagnostic tools: no debugger, the process keeps running. */
object Diagnostics {
    fun threadDumpCommand(tool: File, pid: Long) = GeneralCommandLine(tool.path, "report", "--process-id", pid.toString()).withCharset(Charsets.UTF_8)
    fun heapReportCommand(tool: File, pid: Long) = GeneralCommandLine(tool.path, "report", "--process-id", pid.toString()).withCharset(Charsets.UTF_8)

    /** `dotnet-stack report`: the stacks of all managed threads, shown as a console tab with links to the code of the project. */
    fun threadDump(project: Project, pid: Long, processTitle: String) {
        val tool = DotNetTool.STACK.find() ?: return DotNetTool.STACK.offerInstallation(project, "Thread Dump") { threadDump(project, pid, processTitle) }
        run(project, "Taking a thread dump of $processTitle", threadDumpCommand(tool, pid)) { output ->
            val threads = ThreadDump.parse(output)
            if (threads.isEmpty()) return@run false
            // the console gets the filters of every ConsoleFilterProvider: ThreadDumpFilter makes the frames clickable
            AnalyzeStacktraceUtil.addConsole(project, null, "Threads of $processTitle", ThreadDump.render(threads, processTitle))
            true
        }
    }

    /** `dotnet-gcdump report`: objects of the managed heap by type. The tool makes the process run a full garbage collection. */
    fun heapSnapshot(project: Project, pid: Long, processTitle: String, onTaken: (HeapSnapshot) -> Unit = { HeapSnapshotDialog(project, pid, processTitle).show() }) {
        val tool = DotNetTool.GCDUMP.find() ?: return DotNetTool.GCDUMP.offerInstallation(project, "Heap Snapshot") { heapSnapshot(project, pid, processTitle, onTaken) }
        run(project, "Taking a heap snapshot of $processTitle", heapReportCommand(tool, pid)) { output ->
            val snapshot = HeapSnapshot.parse(output, processTitle) ?: return@run false
            HeapSnapshots.getInstance(project).add(pid, snapshot)
            onTaken(snapshot)
            true
        }
    }

    /** [show] gets the output on EDT and says whether it made sense of it. */
    private fun run(project: Project, title: String, command: GeneralCommandLine, show: (String) -> Boolean) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var output = ""
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text2 = DotNetCli.displayString(command)
                try {
                    val result = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, 120_000, true)
                    output = result.stdout
                    if (result.exitCode != 0 || result.isTimeout) failure = (result.stderr.ifBlank { result.stdout }).trim().lines().takeLast(8).joinToString("\n").ifEmpty { "exit code ${result.exitCode}" }
                } catch (e: Exception) {
                    failure = e.message.orEmpty()
                }
            }

            override fun onSuccess() {
                val problem = failure ?: if (show(output)) return else "The tool printed nothing the plugin understands:\n" + output.trim().lines().take(6).joinToString("\n")
                DotNetCli.notifyError(project, title, problem)
            }
        })
    }
}

/** The heap by type, with the difference from an earlier snapshot of the same process: what grows is what leaks. */
class HeapSnapshotDialog(private val project: Project, private val pid: Long, private val processTitle: String) :
    DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val model = HeapTableModel()
    private val table = JBTable(model)
    private val sorter = TableRowSorter(model)
    private val summary = JBLabel()
    private val current = ComboBox<HeapSnapshot>()
    private val baseline = ComboBox<Any>()
    private val filter = SearchTextField(false)
    private var updating = false

    init {
        title = "Heap of $processTitle"
        init()
        reload(selectNewest = true)
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Take Another Snapshot") {
            override fun actionPerformed(e: ActionEvent) = Diagnostics.heapSnapshot(project, pid, processTitle) { if (!isDisposed) reload(selectNewest = true) }
        },
        okAction.apply { putValue(Action.NAME, "Close") },
    )

    override fun createCenterPanel(): JComponent {
        table.rowSorter = sorter
        for (column in 2..5) table.columnModel.getColumn(column).cellRenderer = NumberRenderer(column)
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(420)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(200)
        sorter.toggleSortOrder(3)
        sorter.toggleSortOrder(3) // the largest first

        current.addActionListener { if (!updating) refreshTable() }
        baseline.addActionListener { if (!updating) refreshTable() }
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val text = filter.text.trim()
                sorter.rowFilter = if (text.isEmpty()) null else RowFilter.regexFilter("(?i)" + Regex.escape(text), 0, 1)
            }
        })
        filter.textEditor.emptyText.text = "Filter by type or assembly"

        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JBLabel("Snapshot:"))
            add(current)
            add(JBLabel("compared with:"))
            add(baseline)
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(top, BorderLayout.NORTH)
            add(summary, BorderLayout.CENTER)
            add(filter, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(980), JBUI.scale(560))
        }
    }

    private fun reload(selectNewest: Boolean) {
        val snapshots = HeapSnapshots.getInstance(project).of(pid)
        updating = true
        val selected = if (selectNewest) snapshots.lastOrNull() else current.selectedItem
        current.model = DefaultComboBoxModel(snapshots.toTypedArray())
        current.selectedItem = selected
        updating = false
        refreshTable()
    }

    private fun refreshTable() {
        val snapshots = HeapSnapshots.getInstance(project).of(pid)
        val shown = current.selectedItem as? HeapSnapshot ?: return
        // only the older ones make a baseline; the previous one by default
        val older = snapshots.takeWhile { it !== shown }
        updating = true
        val previousChoice = baseline.selectedItem
        baseline.model = DefaultComboBoxModel((listOf<Any>(NO_BASELINE) + older.reversed()).toTypedArray())
        baseline.selectedItem = previousChoice?.takeIf { it in older } ?: older.lastOrNull() ?: NO_BASELINE
        updating = false

        val base = baseline.selectedItem as? HeapSnapshot
        model.rows = if (base != null) shown.compareWith(base) else shown.types.map { HeapTypeDifference(it, 0, 0) }
        model.hasBaseline = base != null
        model.fireTableDataChanged()
        summary.text = summaryText(shown, base)
    }

    private inner class NumberRenderer(private val column: Int) : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.RIGHT
        }

        override fun setValue(value: Any?) {
            val number = value as? Long ?: 0
            val isDelta = column >= 4
            text = when {
                isDelta && !model.hasBaseline -> ""
                isDelta && number == 0L -> ""
                column == 3 || column == 5 -> (if (isDelta && number > 0) "+" else if (number < 0) "-" else "") + ChartFormats.bytes(Math.abs(number).toDouble())
                else -> (if (isDelta && number > 0) "+" else "") + String.format("%,d", number)
            }
        }
    }

    private class HeapTableModel : AbstractTableModel() {
        var rows: List<HeapTypeDifference> = emptyList()
        var hasBaseline = false

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 6
        override fun getColumnName(column: Int): String = listOf("Type", "Assembly", "Objects", "Bytes", "Δ Objects", "Δ Bytes")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column < 2) String::class.java else java.lang.Long::class.java
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
            when (column) {
                0 -> it.type.name
                1 -> it.type.module
                2 -> it.type.count
                3 -> it.type.bytes
                4 -> it.countDelta
                else -> it.bytesDelta
            }
        }
    }

    companion object {
        private const val NO_BASELINE = "(nothing)"

        fun summaryText(shown: HeapSnapshot, base: HeapSnapshot?): String {
            val now = "${ChartFormats.bytes(shown.totalBytes.toDouble())} in ${String.format("%,d", shown.objects)} objects of ${shown.types.size} types"
            if (base == null) return "<html>$now. Take another snapshot later to see what grows.</html>"
            val delta = shown.totalBytes - base.totalBytes
            val sign = if (delta >= 0) "+" else "-"
            return "<html>$now; <b>$sign${ChartFormats.bytes(Math.abs(delta).toDouble())}</b> since ${base.time}. Sort by Δ Bytes to see what has grown.</html>"
        }
    }
}
