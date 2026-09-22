package io.github.dotnetsupport.build

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.actions.SolutionContext
import io.github.dotnetsupport.cli.CommandOutput
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.solution.SolutionService
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

class PerformanceEntry(val name: String, val milliseconds: Long, val calls: Int)

/** What `-clp:PerformanceSummary` prints at the end of a build. */
class BuildPerformance(val projects: List<PerformanceEntry>, val targets: List<PerformanceEntry>, val tasks: List<PerformanceEntry>) {
    val isEmpty: Boolean get() = projects.isEmpty() && targets.isEmpty() && tasks.isEmpty()

    companion object {
        private val ROW = Regex("""^(\s*)(\d+) ms\s+(.+?)\s+(\d+) calls\s*$""")

        /**
         * Parses the English output (the build is started with `DOTNET_CLI_UI_LANGUAGE=en`: the localized summaries
         * reorder the columns). Entries are sorted by time, the slowest first.
         */
        fun parse(output: String): BuildPerformance {
            val sections = HashMap<String, MutableList<PerformanceEntry>>()
            var current: MutableList<PerformanceEntry>? = null
            var projectIndent = -1
            for (line in output.lineSequence()) {
                val header = line.trim()
                if (header.endsWith("Performance Summary:")) {
                    current = sections.getOrPut(header.removeSuffix(" Performance Summary:")) { ArrayList() }
                    projectIndent = -1
                    continue
                }
                val row = ROW.find(line) ?: continue
                val (indent, milliseconds, name, calls) = row.destructured
                // under "Project Performance Summary" every project is followed by its targets, indented deeper
                if (header.isNotEmpty() && current === sections["Project"]) {
                    if (projectIndent < 0) projectIndent = indent.length
                    if (indent.length > projectIndent) continue
                }
                current?.add(PerformanceEntry(name, milliseconds.toLong(), calls.toInt()))
            }
            fun section(name: String) = sections[name].orEmpty().sortedByDescending { it.milliseconds }
            return BuildPerformance(section("Project"), section("Target"), section("Task"))
        }
    }
}

/** "What is slow in my build": a full rebuild with the MSBuild performance summary, shown as tables. */
class BuildPerformanceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    private fun target(e: AnActionEvent): VirtualFile? =
        SolutionContext.buildTarget(e) ?: e.project?.let { SolutionService.getInstance(it).solutionFiles().firstOrNull() }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        val title = "Measuring the build of ${target.name}"
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            val configuration = DotNetBuildSettings.getInstance(project).buildArguments(DotNetBuildCommand.REBUILD, target)
            // an incremental build that has nothing to do measures nothing
            listOf(
                DotNetCli.commandLine(target.parent.path, "build", target.path, "--no-incremental", "-nologo", "-clp:PerformanceSummary", *configuration)
                    .withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en")
            )
        } ?: return

        val capture = Capture(BuildViewCommandOutput(project, title))
        DotNetCli.runInBackground(project, title, commands, output = capture) {
            val performance = BuildPerformance.parse(capture.text.toString())
            if (performance.isEmpty) DotNetCli.notifyError(project, title, "The build printed no performance summary.")
            else PerformanceDialog(project, target.name, performance).show()
        }
    }

    /** Streams into the Build tool window and keeps the text for the summary. */
    private class Capture(private val delegate: CommandOutput) : CommandOutput by delegate {
        val text = StringBuffer()

        override fun text(text: String, isError: Boolean) {
            this.text.append(text)
            delegate.text(text, isError)
        }
    }

    private class PerformanceDialog(project: Project, targetName: String, private val performance: BuildPerformance) : DialogWrapper(project, false, IdeModalityType.MODELESS) {
        init {
            title = "Build Performance: $targetName"
            init()
        }

        override fun createActions(): Array<Action> = arrayOf(okAction.apply { putValue(Action.NAME, "Close") })

        override fun createCenterPanel(): JComponent = JBTabbedPane().apply {
            addTab("Targets", table(performance.targets))
            addTab("Tasks", table(performance.tasks))
            addTab("Projects", table(performance.projects))
            preferredSize = Dimension(JBUI.scale(720), JBUI.scale(480))
        }

        private fun table(entries: List<PerformanceEntry>): JComponent {
            val total = entries.sumOf { it.milliseconds }.coerceAtLeast(1)
            val model = object : AbstractTableModel() {
                override fun getRowCount(): Int = entries.size
                override fun getColumnCount(): Int = 4
                override fun getColumnName(column: Int): String = listOf("Name", "Time, ms", "Share", "Calls")[column]
                override fun getValueAt(row: Int, column: Int): Any = entries[row].let {
                    listOf(it.name, it.milliseconds, "${it.milliseconds * 100 / total}%", it.calls)[column]
                }
            }
            return ScrollPaneFactory.createScrollPane(JBTable(model).apply { columnModel.getColumn(0).preferredWidth = JBUI.scale(420) })
        }
    }
}
