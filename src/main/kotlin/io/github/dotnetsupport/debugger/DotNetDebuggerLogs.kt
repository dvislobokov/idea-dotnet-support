package io.github.dotnetsupport.debugger

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.registry.Registry
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What to look at when a debug session misbehaves, all in `~/idea-dotnet-logs/dotnet-debugger` (see `DotNetLogs`): the log of the adapter itself
 * (`dotnet-debugger --log=FILE`, one file per session) and, when switched on, every DAP message both ways (`protocol/`).
 */
object DotNetDebuggerLogs {
    /** On by default while the debugger is young: a session that went wrong cannot be logged afterwards. */
    const val ADAPTER_LOG_KEY = "dotnet.debugger.adapter.log"

    /** The messages of the protocol, per session; takes effect from the next session. */
    const val PROTOCOL_TRACE_KEY = "dotnet.debugger.protocol.trace"
    private const val KEEP = 20
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    val directory: Path get() = io.github.dotnetsupport.cli.DotNetLogs.directory("dotnet-debugger")
    val protocolDirectory: Path get() = directory.resolve("protocol")

    fun adapterLogName(time: LocalDateTime): String = "adapter-${STAMP.format(time)}.log"

    /** The oldest of the logs beyond [keep]; the names sort by time. */
    fun outdated(names: List<String>, keep: Int = KEEP, prefix: String = "adapter-"): List<String> =
        names.filter { it.startsWith(prefix) && it.endsWith(".log") }.sortedDescending().drop(keep)

    /** The file for a new session, or null when the log is switched off or the directory cannot be made. */
    fun newAdapterLog(): File? {
        if (!Registry.`is`(ADAPTER_LOG_KEY, true)) return null
        return newFile(directory, "adapter-")
    }

    /** Where the messages of a new session go, or null when the trace is off. */
    fun newProtocolTrace(): Writer? {
        if (!Registry.`is`(PROTOCOL_TRACE_KEY, false)) return null
        return newFile(protocolDirectory, "protocol-")?.bufferedWriter()
    }

    private fun newFile(directory: Path, prefix: String): File? = try {
        Files.createDirectories(directory)
        outdated(directory.toFile().list().orEmpty().toList(), KEEP - 1, prefix).forEach { directory.resolve(it).toFile().delete() }
        directory.resolve("$prefix${STAMP.format(LocalDateTime.now())}.log").toFile()
    } catch (_: java.io.IOException) {
        null
    }
}

class ShowDebuggerLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(Files.createDirectories(DotNetDebuggerLogs.directory))
    }
}

/** Every DAP message of the sessions that start from now on, next to the debugger logs. */
class TraceDebuggerProtocolAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = Registry.`is`(DotNetDebuggerLogs.PROTOCOL_TRACE_KEY, false)

    override fun setSelected(e: AnActionEvent, state: Boolean) = Registry.get(DotNetDebuggerLogs.PROTOCOL_TRACE_KEY).setValue(state)
}
