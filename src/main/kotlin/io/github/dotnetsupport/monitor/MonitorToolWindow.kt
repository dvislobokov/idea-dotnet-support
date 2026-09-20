package io.github.dotnetsupport.monitor

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.cli.DotNetCli
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class MonitorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MonitorPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }

    companion object {
        const val ID = ".NET Monitor"
        private const val REVEALED_KEY = "dotnet.monitor.window.revealed"

        /**
         * In the new UI a tool window gets its stripe button when it is shown for the first time, so the window opens
         * once, with the first .NET process started in the project; afterwards it opens only when asked.
         */
        fun revealOnce(project: Project) {
            val properties = PropertiesComponent.getInstance(project)
            if (properties.getBoolean(REVEALED_KEY)) return
            properties.setValue(REVEALED_KEY, true)
            ApplicationManager.getApplication().invokeLater({
                ToolWindowManager.getInstance(project).getToolWindow(ID)?.show()
            }, project.disposed)
        }
    }
}

/** Tools | .NET | Monitor .NET Process */
class ShowMonitorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(MonitorToolWindowFactory.ID)?.activate(null)
    }
}

class MonitorPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
    private val processes = ComboBox<MonitorTarget>().apply { prototypeDisplayValue = MonitorTarget(0, "awesomeProject.Web: https (123456)", false) }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val installLink = ActionLink("Install dotnet-counters") { installCounters() }
    private var session: MonitorSession? = null
    private var updatingList = false

    private val cpu = TimeSeriesChart("CPU", ChartFormats::percent, fixedMax = 100.0, "of ${Runtime.getRuntime().availableProcessors()} cores" to CPU_COLOR)
    private val memory = TimeSeriesChart("Memory", ChartFormats::bytes, null, "working set" to MEMORY_COLOR, "GC heap" to HEAP_COLOR)
    private val allocations = TimeSeriesChart("Allocation rate", { ChartFormats.bytes(it) + "/s" }, null, "allocated" to HEAP_COLOR)
    private val gcPause = TimeSeriesChart("Time in GC", ChartFormats::percent, null, "pause" to GC_COLOR)
    private val gcCount = TimeSeriesChart("GC collections", { ChartFormats.number(it) + "/s" }, null, "all generations" to GC_COLOR)
    private val requests = TimeSeriesChart("Active requests", ChartFormats::number, null, "server" to REQUEST_COLOR, "HttpClient" to CLIENT_COLOR)
    private val duration = TimeSeriesChart("Request duration", { ChartFormats.number(it) + " ms" }, null, "p95" to REQUEST_COLOR)
    private val errors = TimeSeriesChart("Exceptions and contention", { ChartFormats.number(it) + "/s" }, null, "exceptions" to ERROR_COLOR, "lock contentions" to CLIENT_COLOR)
    private val queue = TimeSeriesChart("Thread pool queue", ChartFormats::number, null, "work items" to CPU_COLOR)
    private val charts = listOf(cpu, memory, allocations, gcPause, gcCount, requests, duration, errors, queue)

    init {
        Disposer.register(parent, this)
        val refresh = JButton("Refresh").apply { addActionListener { reloadProcesses(select = null) } }
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            add(JBLabel("Process:"))
            add(processes)
            add(refresh)
            add(status)
            add(installLink)
        }
        val grid = JPanel(GridLayout(0, 3, JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            charts.forEach { add(it) }
        }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(grid, true), BorderLayout.CENTER)

        processes.addActionListener { if (!updatingList) (processes.selectedItem as? MonitorTarget)?.let(::monitor) }
        RunningDotNetProcesses.getInstance(project).subscribe(this) { started ->
            ApplicationManager.getApplication().invokeLater({ reloadProcesses(select = started) }, project.disposed)
        }
        reloadProcesses(select = RunningDotNetProcesses.getInstance(project).targets().firstOrNull())
    }

    /** The processes started from the IDE first, then the other .NET processes of the machine (they need `dotnet-counters ps`). */
    private fun reloadProcesses(select: MonitorTarget?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val tool = DotNetCounters.findExecutable()
            val own = RunningDotNetProcesses.getInstance(project).targets()
            val ownPids = own.flatMap { target -> ProcessSampler.tree(target.pid).map { it.pid() } }.toSet()
            val others = tool?.let(DotNetCounters::processes).orEmpty()
                .filter { it.pid !in ownPids && it.pid != ProcessHandle.current().pid() && it.name != DotNetCounters.PACKAGE }
                .map { MonitorTarget(it.pid, it.toString(), withChildren = false) }
            ApplicationManager.getApplication().invokeLater({
                installLink.isVisible = tool == null
                if (tool == null) status.text = "CPU and memory only: the counters of the runtime need the dotnet-counters tool."
                val current = select ?: session?.target ?: processes.selectedItem as? MonitorTarget
                updatingList = true
                processes.model = DefaultComboBoxModel((own + others).toTypedArray())
                processes.selectedItem = current?.takeIf { it in own + others }
                updatingList = false
                val selected = processes.selectedItem as? MonitorTarget
                if (selected != null && selected != session?.target) monitor(selected)
            }, project.disposed)
        }
    }

    private fun monitor(target: MonitorTarget) {
        if (session?.target == target) return
        session?.dispose()
        charts.forEach { it.clear() }
        if (DotNetCounters.findExecutable() != null) status.text = "Attaching to ${target.title}..."
        val started = MonitorSession(
            target, DotNetCounters.findExecutable(),
            onSample = { sample -> ApplicationManager.getApplication().invokeLater({ show(target, sample) }, project.disposed) },
            onStatus = { text -> ApplicationManager.getApplication().invokeLater({ if (session?.target == target && text.isNotEmpty()) status.text = text }, project.disposed) },
            onEnd = { ApplicationManager.getApplication().invokeLater({ if (session?.target == target) { status.text = "${target.title} has exited"; session = null } }, project.disposed) },
        )
        session = started
        started.start()
    }

    private fun show(target: MonitorTarget, sample: MonitorSample) {
        if (session?.target != target) return
        val runtime = sample.runtime
        cpu.add(sample.cpuPercent)
        memory.add(sample.workingSetBytes.toDouble(), runtime?.gcHeapBytes)
        allocations.add(runtime?.allocatedBytesPerSecond)
        gcPause.add(runtime?.gcPausePercent)
        gcCount.add(runtime?.gcCollectionsPerSecond)
        requests.add(runtime?.activeRequests, runtime?.activeClientRequests)
        duration.add(runtime?.requestDurationP95Ms)
        errors.add(runtime?.exceptionsPerSecond, runtime?.lockContentionsPerSecond)
        queue.add(runtime?.threadPoolQueueLength)
        if (runtime != null && installLink.isVisible.not()) {
            status.text = listOfNotNull(
                "${sample.processes} process${if (sample.processes == 1) "" else "es"}",
                sample.threads?.let { "$it threads" },
            ).joinToString(", ")
        }
    }

    private fun installCounters() {
        val commands = DotNetCli.commandLinesOrNotify(project, "Install dotnet-counters") {
            listOf(DotNetCli.commandLine(null, "tool", "install", "--global", DotNetCounters.PACKAGE))
        } ?: return
        DotNetCli.runInBackground(project, "Installing ${DotNetCounters.PACKAGE}", commands) {
            val target = session?.target
            session?.dispose()
            session = null
            reloadProcesses(select = target)
        }
    }

    override fun dispose() {
        session?.dispose()
        session = null
    }

    private companion object {
        val CPU_COLOR = JBColor(Color(0x3574F0), Color(0x548AF7))
        val MEMORY_COLOR = JBColor(Color(0x208A3C), Color(0x5FAD65))
        val HEAP_COLOR = JBColor(Color(0xC77D00), Color(0xF2C55C))
        val GC_COLOR = JBColor(Color(0x834DF0), Color(0xA571E6))
        val REQUEST_COLOR = JBColor(Color(0x0D7F91), Color(0x24A3B8))
        val CLIENT_COLOR = JBColor(Color(0x6C707E), Color(0x9DA0A8))
        val ERROR_COLOR = JBColor(Color(0xDB3B4B), Color(0xF75464))
    }
}

object ChartFormats {
    fun percent(value: Double): String = if (value < 10) String.format("%.1f%%", value) else "${value.toLong()}%"

    fun number(value: Double): String = when {
        value >= 100 || value == Math.floor(value) -> value.toLong().toString()
        value >= 1 -> String.format("%.1f", value)
        else -> String.format("%.2f", value)
    }

    fun bytes(value: Double): String = when {
        value >= 1024.0 * 1024 * 1024 -> String.format("%.2f GB", value / (1024.0 * 1024 * 1024))
        value >= 1024.0 * 1024 -> String.format("%.1f MB", value / (1024.0 * 1024))
        value >= 1024 -> String.format("%.0f KB", value / 1024)
        else -> "${value.toLong()} B"
    }

    /** The top of the scale: 1, 2 or 5 times a power of ten, not below the largest value. */
    fun niceMax(value: Double): Double {
        if (value <= 0 || value.isNaN()) return 1.0
        val power = Math.pow(10.0, Math.floor(Math.log10(value)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * power }.first { it >= value }
    }
}

/** The last five minutes of up to two series, one sample per second; a missing value (null) leaves a gap. */
class TimeSeriesChart(
    private val title: String,
    private val format: (Double) -> String,
    private val fixedMax: Double?,
    vararg series: Pair<String, Color>,
) : JComponent() {
    private val labels = series.map { it.first }
    private val colors = series.map { it.second }
    private val values = series.map { DoubleArray(CAPACITY) { Double.NaN } }
    private var size = 0

    init {
        preferredSize = Dimension(JBUI.scale(260), JBUI.scale(130))
        minimumSize = Dimension(JBUI.scale(180), JBUI.scale(110))
    }

    fun add(vararg sample: Double?) {
        values.forEachIndexed { index, array ->
            System.arraycopy(array, 1, array, 0, CAPACITY - 1)
            array[CAPACITY - 1] = sample.getOrNull(index) ?: Double.NaN
        }
        size = (size + 1).coerceAtMost(CAPACITY)
        repaint()
    }

    fun clear() {
        values.forEach { it.fill(Double.NaN) }
        size = 0
        repaint()
    }

    fun latest(seriesIndex: Int): Double? = values[seriesIndex][CAPACITY - 1].takeIf { !it.isNaN() }

    /** "working set 78.4 MB · GC heap 19.6 MB"; "no data" before the first value. */
    fun legend(): String = labels.indices.mapNotNull { index -> latest(index)?.let { "${labels[index]} ${format(it)}" } }.joinToString(" · ").ifEmpty { "no data" }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val metrics = g.fontMetrics
            val header = metrics.height * 2 + JBUI.scale(4)
            val left = JBUI.scale(2)
            val plotWidth = width - left * 2
            val plotHeight = height - header - JBUI.scale(2)
            if (plotWidth <= 0 || plotHeight <= 0) return

            g.color = UIUtil.getLabelForeground()
            g.font = font.deriveFont(java.awt.Font.BOLD)
            g.drawString(title, left, metrics.ascent)
            g.font = font
            g.color = UIUtil.getContextHelpForeground()
            g.drawString(legend(), left, metrics.height + metrics.ascent)

            val max = fixedMax ?: ChartFormats.niceMax(values.maxOf { array -> array.filter { !it.isNaN() }.maxOrNull() ?: 0.0 })
            val scaleText = format(max)
            g.drawString(scaleText, width - left - metrics.stringWidth(scaleText), metrics.ascent)

            g.color = JBColor.border()
            for (line in 0..2) {
                val y = header + plotHeight * line / 2
                g.drawLine(left, y, left + plotWidth, y)
            }

            values.forEachIndexed { index, array ->
                val path = Path2D.Double()
                var drawing = false
                var firstX = 0.0
                var lastX = 0.0
                for (i in 0 until CAPACITY) {
                    val value = array[i]
                    if (value.isNaN()) { drawing = false; continue }
                    val x = left + plotWidth * i.toDouble() / (CAPACITY - 1)
                    val y = header + plotHeight * (1 - (value / max).coerceIn(0.0, 1.0))
                    if (drawing) path.lineTo(x, y) else { path.moveTo(x, y); if (firstX == 0.0) firstX = x }
                    drawing = true
                    lastX = x
                }
                if (lastX == 0.0) return@forEachIndexed
                g.color = colors[index]
                g.stroke = BasicStroke(JBUI.scale(1).toFloat() * 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
        } finally {
            g.dispose()
        }
    }

    companion object {
        const val CAPACITY = 300
    }
}
