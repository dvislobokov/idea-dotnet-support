package io.github.dotnetsupport.run

import com.intellij.execution.ConsoleFolding
import com.intellij.execution.filters.Filter
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project

enum class LogLevel(val attributes: TextAttributesKey) {
    TRACE(ConsoleViewContentType.LOG_VERBOSE_OUTPUT_KEY),
    DEBUG(ConsoleViewContentType.LOG_DEBUG_OUTPUT_KEY),
    INFO(ConsoleViewContentType.LOG_INFO_OUTPUT_KEY),
    WARNING(ConsoleViewContentType.LOG_WARNING_OUTPUT_KEY),
    ERROR(ConsoleViewContentType.LOG_ERROR_OUTPUT_KEY),
}

/**
 * Colors the level of a log line. The console logger of Microsoft.Extensions.Logging drops its own colors when the
 * output is redirected, which is always the case under an IDE, so `info:` and `fail:` look the same. The colors are the
 * ones of Settings | Editor | Color Scheme | Console Colors | Log console.
 */
class LogLevelFilter : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val (range, level) = find(line) ?: return null
        val attributes = EditorColorsManager.getInstance().globalScheme.getAttributes(level.attributes) ?: return null
        val lineStart = entireLength - line.length
        return Filter.Result(lineStart + range.first, lineStart + range.last + 1, null, attributes)
    }

    companion object {
        // "info: Microsoft.Hosting.Lifetime[14]": the simple console formatter, the level is padded to four letters
        private val MICROSOFT = Regex("""^\s*(trce|dbug|info|warn|fail|crit):\s""")

        // "[21:40:01 INF] Started": the default template of Serilog
        private val SERILOG = Regex("""^\[[^\]\s]*[\d:.,]+[^\]]*\s(VRB|DBG|INF|WRN|ERR|FTL)]""")

        // "2024-05-01 10:00:00.1234|WARN|Shop.Orders|...", "[ERROR] ...": NLog, log4net and hand-made formats
        private val BRACKETED = Regex("""[\[|](TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)[\]|]""")

        private val LEVELS = mapOf(
            "trce" to LogLevel.TRACE, "VRB" to LogLevel.TRACE, "TRACE" to LogLevel.TRACE,
            "dbug" to LogLevel.DEBUG, "DBG" to LogLevel.DEBUG, "DEBUG" to LogLevel.DEBUG,
            "info" to LogLevel.INFO, "INF" to LogLevel.INFO, "INFO" to LogLevel.INFO,
            "warn" to LogLevel.WARNING, "WRN" to LogLevel.WARNING, "WARN" to LogLevel.WARNING, "WARNING" to LogLevel.WARNING,
            "fail" to LogLevel.ERROR, "crit" to LogLevel.ERROR, "ERR" to LogLevel.ERROR, "FTL" to LogLevel.ERROR, "ERROR" to LogLevel.ERROR, "FATAL" to LogLevel.ERROR,
        )

        /** The range of the level word inside [line]. */
        fun find(line: String): Pair<IntRange, LogLevel>? {
            val group = (MICROSOFT.find(line) ?: SERILOG.find(line) ?: BRACKETED.find(line))?.groups?.get(1) ?: return null
            return group.range to LEVELS.getValue(group.value)
        }
    }
}

/**
 * Folds the frames of the framework in .NET stack traces: between `Shop.Orders.Load` and `Program.Main` there are
 * usually a dozen lines of `System.Runtime...` and `Microsoft.AspNetCore...` nobody reads.
 */
class DotNetStackTraceFolding : ConsoleFolding() {
    override fun shouldFoldLine(project: Project, line: String): Boolean = isFrameworkFrame(line)

    override fun getPlaceholderText(project: Project, lines: MutableList<String>): String {
        val frames = lines.count { FRAME.containsMatchIn(it) }
        return "   <$frames framework frame${if (frames == 1) "" else "s"}>"
    }

    // a fold belongs to the stack trace, not to the message above it
    override fun shouldBeAttachedToThePreviousLine(): Boolean = false

    companion object {
        // "   at System.Threading.Tasks.Task.Run()" with a localized "at" ("в", "bei"); thread dumps of the plugin have "module!" in front
        private val FRAME = Regex("""^\s+\S{1,4} (?:[\w.]+!)?(?:System|Microsoft)\.[\w.`+<>\[\],|]+\(""")

        // "--- End of stack trace from previous location ---", localized as well: only the shape is matched
        private val SEPARATOR = Regex("""^\s*--- .+ ---\s*$""")

        fun isFrameworkFrame(line: String): Boolean = FRAME.containsMatchIn(line) || SEPARATOR.matches(line.trimEnd('\n', '\r'))
    }
}
