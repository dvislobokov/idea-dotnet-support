package io.github.dotnetsupport.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val LOG = logger<DotNetLogs>()

/**
 * Every log of the plugin, in one folder of the home directory the user can find and send (asked for): `~/idea-dotnet-logs`, with
 * `commands` (every `dotnet` command the plugin runs), `dotnet-debugger`, `roslyn-language-server` and `DotNetBuild` in it. Not the log
 * directory of the IDE: that one is per IDE and version, and hard to find.
 */
object DotNetLogs {
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val KEEP_DAYS = 14

    val root: Path
        get() = if (ApplicationManager.getApplication()?.isUnitTestMode == true) Path.of(PathManager.getTempPath(), "idea-dotnet-logs")
        else Path.of(System.getProperty("user.home"), "idea-dotnet-logs")

    fun directory(name: String): Path = root.resolve(name)

    val commandsDirectory: Path get() = directory("commands")

    fun commandLogName(day: LocalDate): String = "commands-${DAY.format(day)}.log"

    /** The lines of one command in the log: when it started, what it printed and when, how it ended. Timestamps show where it stood still. */
    fun line(time: LocalTime, tag: String, text: String): String = "${TIME.format(time)} [$tag] $text"

    private val lock = Any()
    private var writer: Writer? = null
    private var writerDay: LocalDate? = null

    /** Appends to the log of today, flushed at once: a command that hangs must have its lines on disk while it hangs. */
    fun command(tag: String, text: String) {
        val now = LocalTime.now()
        val lines = text.trimEnd('\n', '\r').lines().joinToString("") { line(now, tag, it.trimEnd('\r')) + "\n" }
        synchronized(lock) {
            try {
                val out = commandWriter() ?: return
                out.write(lines)
                out.flush()
            } catch (e: java.io.IOException) {
                LOG.info("Cannot write the command log: ${e.message}")
                writer = null
            }
        }
    }

    fun commandStarted(tag: String, command: GeneralCommandLine) =
        command(tag, "> ${DotNetCli.displayString(command)}   (in ${command.workDirectory?.path ?: "."})")

    private fun commandWriter(): Writer? {
        val today = LocalDate.now()
        if (writer != null && writerDay == today) return writer
        runCatching { writer?.close() }
        Files.createDirectories(commandsDirectory)
        outdated(commandsDirectory.toFile().list().orEmpty().toList(), today).forEach { File(commandsDirectory.toFile(), it).delete() }
        writerDay = today
        writer = Files.newBufferedWriter(commandsDirectory.resolve(commandLogName(today)), Charsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        return writer
    }

    /** The command logs older than two weeks. */
    fun outdated(names: List<String>, today: LocalDate): List<String> {
        val oldest = commandLogName(today.minusDays(KEEP_DAYS.toLong()))
        return names.filter { it.startsWith("commands-") && it.endsWith(".log") && it < oldest }
    }
}

/** Menu .NET | Show Plugin Logs: the folder with every log of the plugin. */
class ShowPluginLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(Files.createDirectories(DotNetLogs.root))
    }
}
