package io.github.dotnetsupport.roslyn

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable
import io.github.dotnetsupport.lsp.RoslynPhase
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.monitor.ProcessSampler
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The C# language server in the status bar for as long as its process lives, whatever is open in the editor: the widget of language
 * services of the platform shows a server only next to a file of its language, and with every tab closed there was no telling
 * whether the server was there (reported). A click shows what the server is doing and what it costs — the CPU and the memory of its
 * process tree, live — with Restart, the log, the timings and the settings next to them.
 */
class RoslynStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "C# Language Server"
    override fun isAvailable(project: Project): Boolean = project.service<RoslynWorkspace>().isServerRunning
    override fun createWidget(project: Project): StatusBarWidget = RoslynStatusWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "DotNet.Roslyn.Status"

        /** The server has started, stopped or moved on with the loading: the widget comes, goes or changes its text. */
        fun refresh(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                project.service<StatusBarWidgetsManager>().updateWidget(RoslynStatusWidgetFactory::class.java)
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
            }, ModalityState.any())
        }
    }
}

class RoslynStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    private val workspace get() = project.service<RoslynWorkspace>()

    override fun ID(): String = RoslynStatusWidgetFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() = Unit

    override fun getIcon(): Icon = DotNetIcons.CSharp
    override fun getSelectedValue(): String = RoslynStatusText.widget(workspace.phase, workspace.target)
    override fun getTooltipText(): String = "C# Language Server: " + RoslynStatusText.status(workspace.phase, workspace.target)
    override fun getPopup(): JBPopup = RoslynStatusPopup(project).create()
}

/** What a click on the widget shows. The numbers are measured while it is open, once a second, off the UI thread. */
class RoslynStatusPopup(private val project: Project) {
    private val workspace get() = project.service<RoslynWorkspace>()
    private val status = JBLabel()
    private val cpu = JBLabel(RoslynStatusText.MEASURING)
    private val memory = JBLabel(RoslynStatusText.MEASURING)
    private val process = JBLabel()
    private val usage = RoslynServerUsage()

    fun create(): JBPopup {
        var popup: JBPopup? = null
        fun action(id: String) {
            popup?.cancel()
            val action = ActionManager.getInstance().getAction(id) ?: return
            ActionUtil.invokeAction(action, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project), ActionPlaces.STATUS_BAR_PLACE, null, null)
        }
        val content: JComponent = panel {
            row { label("C# Language Server").bold() }
            row("Status:") { cell(status) }
            row("CPU:") { cell(cpu) }
            row("Memory:") { cell(memory) }
            row("Process:") { cell(process) }
            separator()
            row {
                link("Restart") { action("DotNet.Roslyn.Restart") }
                if (workspace.knownSolutions.size > 1) link("Select Solution...") { popup?.cancel(); workspace.chooseSolution() }
                link("Log") { popup?.cancel(); workspace.showLog() }
                link("Timings") { action("DotNet.Roslyn.Timings") }
                link("Settings...") { popup?.cancel(); ShowSettingsUtil.getInstance().showSettingsDialog(project, RoslynLanguageServerConfigurable::class.java) }
            }
        }.apply { border = JBUI.Borders.empty(10, 14) }

        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(true).setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true).createPopup()
        popup = created
        refresh()
        val timer = Timer(RoslynServerUsage.PERIOD_MS) { refresh() }.apply { start() }
        Disposer.register(created) { timer.stop() }
        return created
    }

    private fun refresh() {
        status.text = RoslynStatusText.status(workspace.phase, workspace.target)
        val root = workspace.serverProcess
        ApplicationManager.getApplication().executeOnPooledThread {
            val sample = usage.sample(root)
            ApplicationManager.getApplication().invokeLater({
                cpu.text = RoslynStatusText.cpu(sample?.cpuPercent)
                memory.text = sample?.let { RoslynStatusText.memory(it.memoryBytes) } ?: RoslynStatusText.STOPPED
                process.text = sample?.let { RoslynStatusText.process(it.pid, it.processes, it.uptime) } ?: RoslynStatusText.STOPPED
            }, ModalityState.any())
        }
    }
}

/**
 * The CPU and the memory of the server and of everything it has started: on Windows the tool is a `.cmd` that starts the server, and
 * the server starts the MSBuild hosts that load the projects, so one process would show a part of the cost.
 */
class RoslynServerUsage {
    class Sample(val pid: Long, val processes: Int, val memoryBytes: Long, /** Null for the first sample: a share needs two. */ val cpuPercent: Double?, val uptime: Duration?)

    private var previousCpu: Duration? = null
    private var previousAt = 0L
    private var previousPid = -1L

    @Synchronized
    fun sample(root: ProcessHandle?): Sample? {
        if (root == null || !root.isAlive) return null
        val tree = ProcessSampler.tree(root.pid())
        if (tree.isEmpty()) return null
        val now = System.nanoTime()
        val usage = ProcessSampler.sample(tree)
        val before = previousCpu.takeIf { previousPid == root.pid() }
        val percent = before?.let { ProcessSampler.cpuPercent(it, usage.cpuTime, now - previousAt) }
        previousCpu = usage.cpuTime
        previousAt = now
        previousPid = root.pid()
        val started = root.info().startInstant().orElse(null)
        return Sample(root.pid(), usage.processes, usage.workingSetBytes, percent, started?.let { Duration.between(it, Instant.now()) })
    }

    companion object {
        const val PERIOD_MS = 1_000
    }
}

/** The words of the widget and of its popup. */
object RoslynStatusText {
    const val MEASURING = "measuring..."
    const val STOPPED = "not running"

    /** Short, for the status bar: `Roslyn: loading Shop.sln...`, `Roslyn: Shop.sln`. */
    fun widget(phase: RoslynPhase, target: String?): String = "Roslyn" + RoslynPolicy.statusText(phase, target)

    /** A sentence, for the popup and the tooltip. */
    fun status(phase: RoslynPhase, target: String?): String = when (phase) {
        RoslynPhase.STARTING -> "Starting"
        RoslynPhase.CHOOSING_SOLUTION -> "Waiting for a solution to be selected"
        RoslynPhase.LOADING -> if (target != null) "Loading $target" else "Loading the projects of the folder"
        RoslynPhase.READY -> if (target != null) "Loaded $target" else "Loaded the projects of the folder"
    }

    /** A share of the whole machine, as the task manager of the system shows it. */
    fun cpu(percent: Double?): String = if (percent == null) MEASURING else String.format(Locale.ROOT, "%.1f %%", percent)

    fun memory(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return if (megabytes < 1024) String.format(Locale.ROOT, "%.0f MB", megabytes) else String.format(Locale.ROOT, "%.2f GB", megabytes / 1024)
    }

    fun uptime(uptime: Duration): String {
        val seconds = uptime.seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        }
    }

    fun process(pid: Long, processes: Int, uptime: Duration?): String = listOfNotNull(
        "PID $pid",
        if (processes > 1) "$processes processes" else null,
        uptime?.let { "running " + uptime(it) },
    ).joinToString(", ")
}
