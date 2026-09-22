package io.github.dotnetsupport.roslyn

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * How long the requests of the IDE to the language server take, since the server of the project started: the measure of LSP_PLAN.md,
 * phase 3 ("before and after every cache"), taken where the user waits, not in a script. Answers served from a cache of the plugin are
 * counted apart. Recorded by [RoslynServerWrapper] for every request of the platform and of the plugin.
 */
@Service(Service.Level.PROJECT)
class RoslynRequestStats {
    /** One method: its durations in order, milliseconds. */
    class Method(val name: String) {
        val durations = mutableListOf<Double>()
        var fromCache = 0
        var failed = 0
    }

    private val methods = ConcurrentHashMap<String, Method>()

    /** While the plugin warms the server up, its requests are counted under their own names: they are not what the user waited for. */
    @Volatile
    var warmingUp = false

    fun record(method: String, milliseconds: Double, fromCache: Boolean = false, failed: Boolean = false) {
        val entry = methods.computeIfAbsent(if (warmingUp) "(warm-up) $method" else method, ::Method)
        synchronized(entry) {
            entry.durations += milliseconds
            if (fromCache) entry.fromCache++
            if (failed) entry.failed++
        }
    }

    fun reset() = methods.clear()

    fun snapshot(): List<Method> = methods.values.sortedBy { it.name }

    fun report(): String = RoslynRequestStats.report(snapshot())

    companion object {
        /** A table: calls, served from a cache, the first call, the median and the slowest of the others. */
        fun report(methods: List<Method>): String = buildString {
            appendLine("%-44s %6s %6s %9s %9s %9s".format("method", "calls", "cache", "first ms", "median", "max"))
            // a dot whatever the locale: the table is pasted into issues
            fun ms(value: Double?) = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"
            for (method in methods) {
                val durations = synchronized(method) { method.durations.toList() }
                if (durations.isEmpty()) continue
                val rest = durations.drop(1).sorted()
                appendLine("%-44s %6d %6d %9s %9s %9s".format(method.name, durations.size, method.fromCache, ms(durations.first()), ms(rest.getOrNull(rest.size / 2)), ms(rest.lastOrNull())))
            }
        }
    }
}

/** Menu .NET | Language Server Timings: the table of [RoslynRequestStats], also put to the clipboard to paste into an issue. */
class ShowRoslynTimingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val report = project.service<RoslynRequestStats>().report()
        CopyPasteManager.getInstance().setContents(StringSelection(report))
        Messages.showInfoMessage(project, "<html><pre>$report</pre>Copied to the clipboard.</html>", "C# Language Server Timings")
    }
}
