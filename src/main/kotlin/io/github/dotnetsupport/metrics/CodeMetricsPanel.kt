package io.github.dotnetsupport.metrics

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.tree.TreeUtil
import io.github.dotnetsupport.DotNetIcons
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The "Code Metrics" tool window tab: the tree of solution / project / namespace / type / member with the columns of Visual Studio's
 * Code Metrics Results. Double-click or Enter opens the member; the toolbar recalculates, expands, collapses and copies the rows as CSV.
 */
class CodeMetricsPanel(
    private val project: Project,
    private val root: MetricsNode,
    private val recalculate: () -> Unit,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val model = ListTreeTableModelOnColumns(toTree(root), COLUMNS)
    private val table = TreeTable(model).apply {
        setRootVisible(true)
        tree.showsRootHandles = true
        setTreeCellRenderer(NameRenderer())
        columnModel.getColumn(0).preferredWidth = 380
        for (i in 1 until columnModel.columnCount) {
            columnModel.getColumn(i).preferredWidth = 90
            columnModel.getColumn(i).cellRenderer = if (i == 1) MaintainabilityRenderer() else NumberRenderer()
        }
    }

    init {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigate()
        }.installOn(table)
        toolbar = buildToolbar()
        setContent(ScrollPaneFactory.createScrollPane(table))
        // open the tree down to the types, as Visual Studio does
        TreeUtil.expand(table.tree, 3)
    }

    private fun selected(): MetricsNode? =
        (table.tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? MetricsNode

    /** Open the member in the editor; false when the row has no place in code (a container, a project that failed). */
    private fun navigate(): Boolean {
        val node = selected() ?: return false
        val path = node.file?.takeIf { node.line > 0 } ?: return false
        val file = LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(path)) ?: return false
        OpenFileDescriptor(project, file, (node.line - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup(
            action("Recalculate", "Measure the code again", AllIcons.Actions.Refresh) { recalculate() },
            action("Expand All", null, AllIcons.Actions.Expandall) { TreeUtil.expandAll(table.tree) },
            action("Collapse All", null, AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(table.tree, 1) },
            action("Copy as CSV", "Copy every row with the columns of \"Open List in Excel\"", AllIcons.Actions.Copy) {
                CopyPasteManager.getInstance().setContents(StringSelection(CodeMetricsReport.csv(root)))
            },
        )
        val component = ActionManager.getInstance().createActionToolbar("DotNetCodeMetrics", group, true)
        component.targetComponent = table
        return component.component
    }

    private fun action(text: String, description: String?, icon: Icon, run: () -> Unit): AnAction =
        object : AnAction(text, description, icon), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    override fun dispose() {}

    private class NameRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? MetricsNode ?: return
            icon = iconOf(node)
            append(node.name)
            node.error?.let { append("  $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
        }
    }

    /** The maintainability index with the green / yellow / red dot of its band, as the column of Visual Studio. */
    private class MaintainabilityRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.RIGHT; horizontalTextPosition = SwingConstants.LEADING }
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column)
            icon = (value as? String)?.toIntOrNull()?.let { BAND_ICONS[MetricsNode.band(it)] }
            return this
        }
    }

    private class NumberRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.RIGHT }
    }

    companion object {
        private fun toTree(node: MetricsNode): DefaultMutableTreeNode =
            DefaultMutableTreeNode(node).apply { node.children.forEach { add(toTree(it)) } }

        private fun iconOf(node: MetricsNode): Icon = when (node.level) {
            MetricsLevel.SOLUTION -> DotNetIcons.Solution
            MetricsLevel.PROJECT -> node.file?.let { DotNetIcons.forProjectFile(it) } ?: AllIcons.Nodes.Module
            MetricsLevel.NAMESPACE -> AllIcons.Nodes.Package
            MetricsLevel.TYPE -> when (node.kind) {
                "interface" -> AllIcons.Nodes.Interface
                "struct", "record" -> AllIcons.Nodes.Record
                "enum" -> AllIcons.Nodes.Enum
                else -> AllIcons.Nodes.Class
            }
            MetricsLevel.MEMBER -> when (node.kind) {
                "property" -> AllIcons.Nodes.Property
                "field" -> AllIcons.Nodes.Field
                else -> AllIcons.Nodes.Method
            }
        }

        // red, yellow, green — the order of MetricsNode.band (0, 1, 2)
        private val BAND_ICONS = arrayOf(dot(JBColor(0xD64E4E, 0xC75450)), dot(JBColor(0xC0981C, 0xB89B2B)), dot(JBColor(0x59A869, 0x5FAD65)))

        private fun dot(color: Color): Icon = object : Icon {
            override fun getIconWidth() = 8
            override fun getIconHeight() = 8
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = color
                    g2.fillOval(x, y + 1, 8, 8)
                } finally {
                    g2.dispose()
                }
            }
        }

        private fun metric(name: String, value: (MetricsNode) -> Int?): ColumnInfo<DefaultMutableTreeNode, String> =
            object : ColumnInfo<DefaultMutableTreeNode, String>(name) {
                override fun valueOf(item: DefaultMutableTreeNode): String =
                    (item.userObject as? MetricsNode)?.let { value(it)?.toString() }.orEmpty()
            }

        private val COLUMNS: Array<ColumnInfo<*, *>> = arrayOf(
            TreeColumnInfo("Scope"),
            metric("Maintainability Index") { it.maintainability },
            metric("Cyclomatic Complexity") { it.complexity },
            metric("Depth of Inheritance") { it.inheritance },
            metric("Class Coupling") { it.coupling },
            metric("Lines of Source Code") { it.lines },
            metric("Lines of Executable Code") { it.executable },
        )
    }
}
