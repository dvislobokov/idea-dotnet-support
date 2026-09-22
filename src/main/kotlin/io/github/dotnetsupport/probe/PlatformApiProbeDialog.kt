package io.github.dotnetsupport.probe

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

/** .NET → Probe Platform LSP / DAP API: a diagnostic for trying the plugin in another IDE or without a license. */
class PlatformApiProbeAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        // extensions of the points are instantiated along the way: not on the EDT
        val report = ProgressManager.getInstance().runProcessWithProgressSynchronously<ProbeReport, RuntimeException>({ PlatformApiProbe.run(project) }, "Probing Platform API", true, project)
        PlatformApiProbeDialog(project, report).show()
    }
}

class PlatformApiProbeDialog(project: Project?, private val report: ProbeReport) : DialogWrapper(project) {
    private val checks = ListTableModel<ProbeCheck>(
        column("Area", 50) { it.area.uppercase() },
        column("Kind", 110) { it.kind },
        column("Name", 430) { it.name },
        column("Status", 70) { it.status.name },
        column("Details", 430) { if (it.missing.isEmpty()) it.details else it.details + ": " + it.missing.joinToString(", ") },
    )
    private val problemsOnly = JBCheckBox("Problems only", report.checks.any { it.status != ProbeStatus.OK })

    init {
        title = "Platform LSP / DAP API"
        setOKButtonText("Close")
        init()
    }

    override fun getDimensionServiceKey(): String = "DotNet.PlatformApiProbe"

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createLeftSideActions(): Array<Action> = arrayOf(object : AbstractAction("Copy as JSON") {
        override fun actionPerformed(e: ActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(PlatformApiProbe.toJson(report)))
    })

    override fun createCenterPanel(): JComponent {
        val table = JBTable(checks).apply {
            setDefaultRenderer(Any::class.java, StatusRenderer(checks))
            autoCreateRowSorter = true
            checks.columnInfos.forEachIndexed { index, info -> columnModel.getColumn(index).preferredWidth = JBUI.scale((info as SizedColumn).width) }
        }
        TableSpeedSearch.installOn(table)
        problemsOnly.addActionListener { fill() }
        fill()

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)
            add(JBLabel(summary(report)), BorderLayout.CENTER)
            add(problemsOnly, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(1100, 600)
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        }
    }

    private fun fill() {
        checks.items = if (problemsOnly.isSelected) report.checks.filter { it.status != ProbeStatus.OK } else report.checks
    }

    private abstract class SizedColumn(name: String, val width: Int) : ColumnInfo<ProbeCheck, String>(name)

    private fun column(name: String, width: Int, value: (ProbeCheck) -> String): ColumnInfo<ProbeCheck, String> = object : SizedColumn(name, width) {
        override fun valueOf(item: ProbeCheck): String = value(item)
    }

    private class StatusRenderer(private val model: ListTableModel<ProbeCheck>) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
            val component = super.getTableCellRendererComponent(table, value, selected, focused, row, column)
            val check = model.getItem(table.convertRowIndexToModel(row))
            if (!selected) component.foreground = when (check.status) {
                ProbeStatus.OK -> table.foreground
                ProbeStatus.PARTIAL -> JBColor(0x8A5A00, 0xF0C674)
                ProbeStatus.MISSING, ProbeStatus.ERROR -> JBColor.RED
            }
            toolTipText = value?.toString()
            return component
        }
    }

    companion object {
        /** "IntelliJ IDEA 2026.1.4 (IU-261…), license: none — LSP: module is there, 129 of 129 classes; DAP: …" */
        fun summary(report: ProbeReport): String {
            val areas = report.checks.map { it.area }.distinct().joinToString("&nbsp;&nbsp;|&nbsp;&nbsp;") { area ->
                val points = report.of(area, PlatformApiProbe.EXTENSION_POINT)
                val classes = report.of(area, PlatformApiProbe.CLASS)
                val module = when (points.count { it.status == ProbeStatus.OK }) {
                    0 -> "<b>module is absent</b>"
                    points.size -> "module is there"
                    else -> "module is there, some extension points are not"
                }
                val own = report.of(area, PlatformApiProbe.PLUGIN_MODULE).firstOrNull()?.let { if (it.status == ProbeStatus.OK) ", the module of the plugin is loaded" else ", <b>the module of the plugin is not loaded</b>" }
                "<b>${area.uppercase()}</b>: $module, ${classes.count { it.status == ProbeStatus.OK }} of ${classes.size} classes match${own.orEmpty()}"
            }
            val ide = report.ide
            return "<html>${ide["name"]} (${ide["build"]}), license: ${ide["license"]}, Ultimate module: ${ide["ultimateModule"]}<br>$areas" +
                "<br><font color=gray>Compared with the API of ${report.expectedSource}</font></html>"
        }
    }
}
