package io.github.dotnetsupport.dap

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.registry.Registry
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What to look at when a debug session misbehaves, all in `<log directory of the IDE>/dotnet-debugger`: the log of the adapter itself
 * (`dotnet-debugger --log=FILE`, one file per session) and, when switched on, the DAP messages both ways as the platform client traces them.
 */
object DotNetDebuggerLogs {
    /** On by default while the debugger is young: a session that went wrong cannot be logged afterwards. */
    const val ADAPTER_LOG_KEY = "dotnet.debugger.adapter.log"

    /** The key of the platform DAP client: a directory for the traces, empty for none. */
    const val PROTOCOL_TRACE_KEY = "dap.message.trace.dir"
    private const val KEEP = 20
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    val directory: Path get() = Path.of(PathManager.getLogPath(), "dotnet-debugger")
    val protocolDirectory: Path get() = directory.resolve("protocol")

    fun adapterLogName(time: LocalDateTime): String = "adapter-${STAMP.format(time)}.log"

    /** The oldest of the adapter logs beyond [keep]; the names sort by time. */
    fun outdated(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith("adapter-") && it.endsWith(".log") }.sortedDescending().drop(keep)

    /** The file for a new session, or null when the log is switched off or the directory cannot be made. */
    fun newAdapterLog(): File? {
        if (!Registry.`is`(ADAPTER_LOG_KEY, true)) return null
        return try {
            val directory = Files.createDirectories(directory)
            val names = directory.toFile().list().orEmpty().toList()
            outdated(names, KEEP - 1).forEach { directory.resolve(it).toFile().delete() }
            directory.resolve(adapterLogName(LocalDateTime.now())).toFile()
        } catch (_: java.io.IOException) {
            null
        }
    }
}

class ShowDebuggerLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(Files.createDirectories(DotNetDebuggerLogs.directory))
    }
}

/** Points the trace of the platform DAP client at the directory of the debugger logs; takes effect from the next session. */
class TraceDebuggerProtocolAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = Registry.stringValue(DotNetDebuggerLogs.PROTOCOL_TRACE_KEY).isNotBlank()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val directory = if (state) Files.createDirectories(DotNetDebuggerLogs.protocolDirectory).toString() else ""
        Registry.get(DotNetDebuggerLogs.PROTOCOL_TRACE_KEY).setValue(directory)
    }
}
