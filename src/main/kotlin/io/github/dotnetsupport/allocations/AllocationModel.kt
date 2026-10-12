package io.github.dotnetsupport.allocations

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** What a line of the source has allocated in the last seconds, as the watcher measures it (allocwatch/Program.cs). */
class AllocationLine(
    val file: String,
    /** From 1, as the debug information has it. */
    val line: Int,
    val method: String,
    val bytesPerSecond: Long,
    val objectsPerSecond: Long,
    /** Of everything that is charged to a line of the sources, 0..100. */
    val share: Double,
    /** Since the watcher was attached. */
    val totalBytes: Long,
    /** The types of the objects and their shares of the bytes of the line, the largest first. */
    val types: Map<String, Int>,
)

sealed class AllocationMessage {
    class Started(val pid: Long) : AllocationMessage()
    class Stopped(val reason: String) : AllocationMessage()
    class Snapshot(val seconds: Double, val window: Double, val bytesPerSecond: Long, val events: Long, val lines: List<AllocationLine>) : AllocationMessage()
}

object AllocationReports {
    /** A line of the output of the watcher; null for what is not one. */
    fun parse(line: String): AllocationMessage? {
        if (!line.startsWith("{")) return null
        val row = runCatching { JsonParser.parseString(line) as? JsonObject }.getOrNull() ?: return null
        return when {
            row.has("started") -> AllocationMessage.Started(row.long("pid"))
            row.has("stopped") -> AllocationMessage.Stopped(row.get("reason")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
            row.has("lines") -> AllocationMessage.Snapshot(row.double("seconds"), row.double("window"), row.long("bytesPerSecond"), row.long("events"),
                row.getAsJsonArray("lines").mapNotNull { (it as? JsonObject)?.let(::line) })
            else -> null
        }
    }

    private fun line(row: JsonObject): AllocationLine? {
        val file = row.get("file")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val types = LinkedHashMap<String, Int>()
        (row.get("types") as? JsonObject)?.entrySet()?.forEach { (type, share) -> types[type] = runCatching { share.asDouble.toInt() }.getOrDefault(0) }
        return AllocationLine(file, row.long("line").toInt(), row.get("method")?.asString.orEmpty(), row.long("bytesPerSecond"), row.long("objectsPerSecond"),
            row.double("share"), row.long("totalBytes"), types)
    }

    private fun JsonObject.long(name: String): Long = runCatching { get(name)?.asDouble?.toLong() }.getOrNull() ?: 0
    private fun JsonObject.double(name: String): Double = runCatching { get(name)?.asDouble }.getOrNull() ?: 0.0
}

/** The numbers as the editor writes them. */
object AllocationText {
    private val ALIASES = mapOf(
        "System.String" to "string", "System.Int32" to "int", "System.Int64" to "long", "System.Boolean" to "bool", "System.Double" to "double",
        "System.Decimal" to "decimal", "System.Object" to "object", "System.Byte" to "byte", "System.Char" to "char", "System.Single" to "float",
        "System.Int16" to "short", "System.UInt32" to "uint", "System.UInt64" to "ulong",
    )
    private val CLOSURE = Regex("""^<>c(?:__DisplayClass.*)?$""")
    private val ARITY = Regex("""`\d+""")

    /** `19.3 MB/s`, `905 KB/s`, `120 B/s` */
    fun rate(bytesPerSecond: Long): String = size(bytesPerSecond) + "/s"

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    /** `18.4K obj/s`, `21 obj/s` */
    fun objects(perSecond: Long): String = when {
        perSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM obj/s", perSecond / 1e6)
        perSecond >= 1_000 -> String.format(Locale.ROOT, "%.1fK obj/s", perSecond / 1e3)
        else -> "$perSecond obj/s"
    }

    fun share(percent: Double): String = if (percent >= 10 || percent == 0.0) String.format(Locale.ROOT, "%.0f%%", percent) else String.format(Locale.ROOT, "%.1f%%", percent)

    /**
     * The name of a type as C# writes it, from the one of the runtime: `System.Byte[]` -> `byte[]`,
     * `System.Collections.Generic.List`1[System.Int32]` -> `List<int>`, `<>c__DisplayClass8_0` -> `closure`.
     */
    fun type(runtimeName: String): String {
        val name = runtimeName.trim()
        if (name.isEmpty() || name == "?") return "object"
        val array = Regex("""(\[,*\])+$""").find(name)?.value.orEmpty()
        val element = name.removeSuffix(array)
        val open = element.indexOf('[')
        if (open > 0 && element.endsWith("]")) {
            val arguments = splitArguments(element.substring(open + 1, element.length - 1)).joinToString(", ") { type(it) }
            return simple(element.substring(0, open)) + "<" + arguments + ">" + array
        }
        return simple(element) + array
    }

    private fun simple(name: String): String {
        ALIASES[name]?.let { return it }
        val last = name.substringAfterLast('+').substringAfterLast('.').replace(ARITY, "")
        return if (CLOSURE.matches(last)) "closure" else last
    }

    private fun splitArguments(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in text.withIndex()) {
            when (c) {
                '[' -> depth++
                ']' -> depth--
                ',' -> if (depth == 0) { parts += text.substring(start, i); start = i + 1 }
            }
        }
        parts += text.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** At the end of a line: `19.3 MB/s, 18.4K obj/s, byte[]  95%`. */
    fun line(line: AllocationLine): String {
        val types = line.types.keys.take(2).joinToString(", ") { type(it) } + if (line.types.size > 2) ", ..." else ""
        return listOf(rate(line.bytesPerSecond), objects(line.objectsPerSecond), types).filter { it.isNotEmpty() }.joinToString(", ") + "  " + share(line.share)
    }

    /** At the declaration of a method with several lines that allocate: `allocates 20.2 MB/s  99%`. */
    fun total(bytesPerSecond: Long, share: Double): String = "allocates " + rate(bytesPerSecond) + "  " + share(share)

    /** The tooltip of the stripe in the gutter. */
    fun tooltip(line: AllocationLine): String = buildString {
        append("<html><b>").append(rate(line.bytesPerSecond)).append("</b>, ").append(objects(line.objectsPerSecond)).append(", ").append(share(line.share)).append(" of what the sources allocate<br>")
        for ((type, share) in line.types) append(escape(type(type))).append(": ").append(share).append("%<br>")
        append(size(line.totalBytes)).append(" since the watcher was attached<br>")
        append("<span style='color:gray'>").append(escape(line.method)).append("<br>A sample: the runtime reports about every 100 KB a thread allocates.</span></html>")
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Which of the processes of a run is the program: `dotnet run` is a launcher, MSBuild and the compiler server are its helpers. */
object AllocationTargets {
    class Candidate(val pid: Long, val commandLine: String, val startedAtMs: Long)

    private val HELPERS = listOf("MSBuild.dll", "VBCSCompiler", "NuGet.Build.Tasks", "csc.dll", "conhost.exe")
    private val LAUNCHER = Regex("""\bdotnet(?:\.exe)?"?\s+(?:run|watch|build|test|msbuild)\b""", RegexOption.IGNORE_CASE)
    // the host with no arguments runs nothing: it is what is known of a process whose command line the system has not told
    private val BARE_HOST = Regex("""^"?(?:[^"]*[\\/])?dotnet(?:\.exe)?"?\s*$""", RegexOption.IGNORE_CASE)

    fun isProgram(commandLine: String): Boolean =
        commandLine.isNotBlank() && HELPERS.none { commandLine.contains(it, ignoreCase = true) } && !LAUNCHER.containsMatchIn(commandLine) && !BARE_HOST.matches(commandLine.trim())

    /** The ones to offer to the watcher, the likeliest first: the newest of what is neither a launcher nor a helper of the build. */
    fun order(processes: List<Candidate>): List<Long> = processes.filter { isProgram(it.commandLine) }.sortedByDescending { it.startedAtMs }.map { it.pid }

    /**
     * The command line of a process: Java tells none on Windows ([fromJava] is only the path of the executable there, or nothing),
     * the list of processes of the platform does ([fromSystem]).
     */
    fun commandLine(fromJava: String?, fromSystem: String?): String = fromSystem?.takeIf { it.isNotBlank() } ?: fromJava.orEmpty()

    /** A path as the watcher prints it and as the IDE has it are the same file. */
    fun key(path: String, caseSensitive: Boolean): String = path.replace('\\', '/').let { if (caseSensitive) it else it.lowercase(Locale.ROOT) }
}
