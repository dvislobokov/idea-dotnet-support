package io.github.dotnetsupport.coverage

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import java.awt.event.MouseEvent
import javax.swing.table.AbstractTableModel

/** Per-file summary of the last coverage run; double click opens the file with the gutter stripes. */
class CoverageToolWindowFactory : ToolWindowFactory, DumbAware {
    // appears with the first coverage run
    override fun shouldBeAvailable(project: Project): Boolean = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = DotNetCoverageService.getInstance(project)
        val model = CoverageTableModel(project)
        val table = JBTable(model).apply {
            emptyText.text = "Run tests with coverage to see the results"
            columnModel.getColumn(0).preferredWidth = 420
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.selectedRow.takeIf { it >= 0 }?.let(table::convertRowIndexToModel) ?: return false
                val file = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(model.files[row].path)) ?: return false
                OpenFileDescriptor(project, file).navigate(true)
                return true
            }
        }.installOn(table)

        val clear = object : AnAction("Clear Coverage", "Remove the coverage stripes from the editors", AllIcons.Actions.GC), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = service.report.files.isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) = service.clear()
        }
        val panel = SimpleToolWindowPanel(false, true).apply {
            setContent(ScrollPaneFactory.createScrollPane(table))
            toolbar = ActionManager.getInstance().createActionToolbar("DotNetCoverage", DefaultActionGroup(clear), false).also { it.targetComponent = table }.component
        }
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        fun refresh() {
            model.reload()
            val report = service.report
            content.displayName = if (report.totalLines == 0) "" else "${service.title}: ${percent(report.coveredLines, report.totalLines)} of lines"
        }
        service.addListener(::refresh)
        refresh()
    }

    private class CoverageTableModel(private val project: Project) : AbstractTableModel() {
        var files: List<FileCoverage> = emptyList()
            private set

        fun reload() {
            files = DotNetCoverageService.getInstance(project).report.files.sortedBy { it.coveredLines.toDouble() / it.totalLines.coerceAtLeast(1) }
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = files.size
        override fun getColumnCount(): Int = COLUMNS.size
        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getValueAt(row: Int, column: Int): Any {
            val file = files[row]
            return when (column) {
                0 -> relativePath(file.path)
                1 -> percent(file.coveredLines, file.totalLines)
                2 -> "${file.coveredLines} / ${file.totalLines}"
                else -> if (file.totalBranches == 0) "" else percent(file.coveredBranches, file.totalBranches)
            }
        }

        private fun relativePath(path: String): String {
            val base = project.guessProjectDir()?.path ?: return path
            return FileUtil.getRelativePath(base, FileUtil.toSystemIndependentName(path), '/') ?: path
        }
    }

    companion object {
        const val ID = ".NET Coverage"
        private val COLUMNS = listOf("File", "Lines", "Covered / Total", "Branches")

        fun percent(covered: Int, total: Int): String = if (total == 0) "n/a" else "${covered * 100 / total}%"
    }
}
