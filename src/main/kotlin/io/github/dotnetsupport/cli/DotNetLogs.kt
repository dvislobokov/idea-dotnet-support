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
 * `plugin` (the journal of the plugin, see [PluginLog]), `commands` (every `dotnet` command the plugin runs with its output),
 * `dotnet-debugger`, `roslyn-language-server` and `DotNetBuild` in it. Not the log directory of the IDE: that one is per IDE and
 * version, and hard to find.
 */
object DotNetLogs {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    val root: Path
        get() = if (ApplicationManager.getApplication()?.isUnitTestMode == true) Path.of(PathManager.getTempPath(), "idea-dotnet-logs")
        else Path.of(System.getProperty("user.home"), "idea-dotnet-logs")

    fun directory(name: String): Path = root.resolve(name)

    val commandsDirectory: Path get() = directory("commands")

    val pluginDirectory: Path get() = directory("plugin")

    private val commands = DailyLog("commands", "commands-")

    fun commandLogName(day: LocalDate): String = commands.fileName(day)

    /** The lines of one command in the log: when it started, what it printed and when, how it ended. Timestamps show where it stood still. */
    fun line(time: LocalTime, tag: String, text: String): String = "${TIME.format(time)} [$tag] $text"

    /** Appends to the log of today, flushed at once: a command that hangs must have its lines on disk while it hangs. */
    fun command(tag: String, text: String) {
        val now = LocalTime.now()
        commands.append(text.trimEnd('\n', '\r').lines().joinToString("") { line(now, tag, it.trimEnd('\r')) + "\n" })
    }

    fun commandStarted(tag: String, command: GeneralCommandLine) {
        val text = "> ${DotNetCli.displayString(command)}   (in ${command.workDirectory?.path ?: "."})"
        command(tag, text)
        PluginLog.info(CATEGORY_COMMANDS, "$tag: $text")
    }

    /** How a command ended, in the command log and in the journal: a failure carries the last lines the command printed, nothing else does. */
    fun commandFinished(tag: String, result: String, failed: Boolean, lastLines: String = "") {
        command(tag, result + if (failed && lastLines.isNotBlank()) "\n$lastLines" else "")
        if (failed) PluginLog.warn(CATEGORY_COMMANDS, "$tag: $result" + if (lastLines.isNotBlank()) "\n$lastLines" else "")
        else PluginLog.info(CATEGORY_COMMANDS, "$tag: $result")
    }

    /** The command logs older than two weeks. */
    fun outdated(names: List<String>, today: LocalDate): List<String> = commands.outdated(names, today)

    /** The category of the journal for the commands: what ran, where, and how it ended; their output is in `commands`. */
    const val CATEGORY_COMMANDS = "dotnet"
}

/**
 * A log with a file per day under [DotNetLogs.root]`/`[directory], `<prefix><yyyyMMdd>.log`, kept for two weeks. Appended and flushed at
 * once, so that what hangs or crashes has its lines on disk.
 */
class DailyLog(private val directory: String, private val prefix: String) {
    private val lock = Any()
    private var writer: Writer? = null
    private var writerDay: LocalDate? = null

    fun fileName(day: LocalDate): String = "$prefix${DAY.format(day)}.log"

    fun append(text: String) {
        synchronized(lock) {
            try {
                val out = writer() ?: return
                out.write(text)
                out.flush()
            } catch (e: java.io.IOException) {
                LOG.info("Cannot write the log $directory: ${e.message}")
                writer = null
            }
        }
    }

    private fun writer(): Writer? {
        val today = LocalDate.now()
        if (writer != null && writerDay == today) return writer
        runCatching { writer?.close() }
        val folder = DotNetLogs.directory(directory)
        Files.createDirectories(folder)
        outdated(folder.toFile().list().orEmpty().toList(), today).forEach { File(folder.toFile(), it).delete() }
        writerDay = today
        writer = Files.newBufferedWriter(folder.resolve(fileName(today)), Charsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        return writer
    }

    /** The files of this log older than two weeks. */
    fun outdated(names: List<String>, today: LocalDate): List<String> {
        val oldest = fileName(today.minusDays(KEEP_DAYS.toLong()))
        return names.filter { it.startsWith(prefix) && it.endsWith(".log") && it < oldest }
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        const val KEEP_DAYS = 14
    }
}

/** Menu .NET | Open Logs Folder: the folder with every log of the plugin. */
class ShowPluginLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(Files.createDirectories(DotNetLogs.root))
    }
}
