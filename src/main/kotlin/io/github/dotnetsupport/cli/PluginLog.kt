package io.github.dotnetsupport.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessNotCreatedException
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import java.io.IOException
import java.io.UncheckedIOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

private val LOG = logger<PluginLog>()

enum class LogLevel { INFO, WARN, ERROR }

/** One event of the plugin: when, how bad, which part of the plugin ([category]: `dotnet`, `roslyn`, `debugger`, `nuget`...) and what. */
class LogEntry(val time: LocalDateTime, val level: LogLevel, val category: String, val text: String) {
    /** `12:03:44.120 WARN  [roslyn] text`; the lines of a multi-line text after the first are indented under it. */
    fun format(): String = PluginLog.line(time, level, category, text)
}

/**
 * The journal of the plugin: every event worth investigating, one line each, readable without the IDE log. It goes to
 * `~/idea-dotnet-logs/plugin/plugin-<day>.log` (see [DotNetLogs]), to the Plugin Logs tool window (menu .NET), and in short to the
 * log of the IDE. The text says what the plugin did, what came back and what it decided, not where in the code it happened: a failed
 * command or tool is described by its exit code and its last lines, never by a stack trace ([describe]).
 */
object PluginLog {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val KEEP_ENTRIES = 5000

    private val file = DailyLog("plugin", "plugin-")
    private val recent = ArrayDeque<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    fun info(category: String, text: String) = log(LogLevel.INFO, category, text)

    fun warn(category: String, text: String, cause: Throwable? = null) = log(LogLevel.WARN, category, withCause(text, cause), cause)

    fun error(category: String, text: String, cause: Throwable? = null) = log(LogLevel.ERROR, category, withCause(text, cause), cause)

    private fun withCause(text: String, cause: Throwable?): String = if (cause == null) text else "$text: ${describe(cause)}"

    private fun log(level: LogLevel, category: String, text: String, cause: Throwable? = null) {
        val entry = LogEntry(LocalDateTime.now(), level, category, text.trimEnd())
        synchronized(recent) {
            recent.addLast(entry)
            while (recent.size > KEEP_ENTRIES) recent.removeFirst()
        }
        file.append(entry.format() + "\n")
        // the IDE log keeps the short line too, and the full trace of what the plugin did not expect, for the developer
        val ideText = "[$category] ${entry.text}"
        when {
            level == LogLevel.INFO -> LOG.info(ideText)
            cause != null && !isExpected(cause) -> LOG.warn(ideText, cause)
            else -> LOG.warn(ideText)
        }
        listeners.forEach { it(entry) }
    }

    /** What has been logged since the IDE started (the last [KEEP_ENTRIES] entries), oldest first. */
    val entries: List<LogEntry> get() = synchronized(recent) { recent.toList() }

    /** Replays [entries], then follows new ones on the thread they are logged from, until [disposable] goes. */
    fun subscribe(disposable: Disposable, listener: (LogEntry) -> Unit) {
        synchronized(recent) {
            recent.forEach(listener)
            listeners += listener
        }
        Disposer.register(disposable) { listeners -= listener }
    }

    fun line(time: LocalDateTime, level: LogLevel, category: String, text: String): String {
        val head = "${TIME.format(time)} ${level.name.padEnd(5)} [$category] "
        val lines = text.lines()
        return head + lines.first() + lines.drop(1).joinToString("") { "\n" + " ".repeat(head.length) + it }
    }

    /**
     * An exception in one line: the message of an expected failure (a process that could not start, a file that could not be read), the
     * class, the message and the frame of the plugin for anything else. The whole trace is for the IDE log, not for the journal.
     */
    fun describe(e: Throwable): String {
        val message = messageOf(e)
        if (isExpected(e)) return message
        val frame = e.stackTrace.firstOrNull { it.className.startsWith("io.github.dotnetsupport.") }
            ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }.orEmpty()
        // an exception without a message is named once, not "NullPointerException: NullPointerException"
        return if (message == e.javaClass.simpleName) "$message$frame" else "${e.javaClass.simpleName}: $message$frame"
    }

    private fun messageOf(e: Throwable): String {
        val own = e.message?.takeIf { it.isNotBlank() }
        val cause = e.cause?.takeIf { it !== e }?.let(::messageOf)?.takeIf { it.isNotBlank() && it != own }
        return listOfNotNull(own, cause).joinToString(": ").ifBlank { e.javaClass.simpleName }
    }

    /** Failures whose message says it all: an external process or the disk or the network, not the code of the plugin. */
    fun isExpected(e: Throwable): Boolean =
        e is ExecutionException || e is ProcessNotCreatedException || e is IOException || e is UncheckedIOException || e is java.util.concurrent.TimeoutException ||
            e.cause?.let { it !== e && isExpected(it) } == true
}
