package io.github.dotnetsupport.frameworks.unity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile

/**
 * The console of the Unity editor as the package writes it (UNITY_PLAN.md, step 2; `ConsoleLog.cs`): `Temp/DotNetSupport/console.jsonl`,
 * one event per line, the first line naming the file. Pure: the tests give it the lines and the files.
 */
object UnityConsole {
    enum class Severity { ERROR, WARNING, MESSAGE }

    /** A message of the console ([compiler] false: `Debug.Log` and the like, with the stack) or of the compiler (with the place). */
    data class Entry(
        val severity: Severity, val time: Long, val text: String, val stack: String = "", val playMode: Boolean = false,
        val compiler: Boolean = false, val code: String = "", val file: String = "", val line: Int = 0, val column: Int = 0,
    ) {
        /** What the list shows: the first line of the text; a message of the compiler with its code and place, as the console has it. */
        val summary: String
            get() = if (compiler) "$file($line,$column): ${severity.name.lowercase()} $code: $text".removePrefix("(0,0): ") else text.lineSequence().first()

        /** The whole message, as the details show it. */
        val details: String get() = if (compiler) summary else listOf(text, stack.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    sealed interface Event {
        /** The first line of a file: [id] changes when the package begins the file again, [processId] when another editor does. */
        data class Start(val id: String, val processId: Long) : Event
        data class Message(val entry: Entry) : Event
        /** A compilation began: the messages of the last one are gone, as in the console. */
        data object CompileStarted : Event
        /** The editor leaves edit mode for Play mode: before the first message of the game. */
        data object PlayStarted : Event
    }

    fun parse(line: String): Event? {
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
        return when (json.string("kind")) {
            "start" -> Event.Start(json.string("id"), json.get("pid")?.asLong ?: 0)
            "compile-started" -> Event.CompileStarted
            "play-started" -> Event.PlayStarted
            "log" -> Event.Message(Entry(
                severity = when (json.string("type")) { "Log" -> Severity.MESSAGE; "Warning" -> Severity.WARNING; else -> Severity.ERROR },
                time = json.long("t"), text = json.string("text"), stack = json.string("stack"), playMode = json.get("play")?.asBoolean == true,
            ))
            "compiler" -> Event.Message(Entry(
                severity = when (json.string("type")) { "error" -> Severity.ERROR; "warning" -> Severity.WARNING; else -> Severity.MESSAGE },
                time = json.long("t"), text = json.string("text"), compiler = true, code = json.string("code"), file = json.string("file"),
                line = json.get("line")?.asInt ?: 0, column = json.get("column")?.asInt ?: 0,
            ))
            else -> null
        }
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.long(name: String): Long = get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0

    fun file(root: File): File = File(UnityEditorLink.folder(root), "console.jsonl")

    /**
     * Reads a file of the console on from where it stopped. [read] gives the events of the lines written since (a line not yet ended is
     * left for the next time); a file begun again (another id in its first line, or shorter than what was read) is read from its start,
     * after a [Event.Start] of the new id.
     */
    class Reader(private val file: File) {
        private var id: String? = null
        private var offset = 0L
        private var pending = ByteArray(0)

        fun read(): List<Event> {
            if (!file.isFile) return emptyList()
            return runCatching {
                RandomAccessFile(file, "r").use { input ->
                    val length = input.length()
                    val first = firstLine(input)
                    val startId = (first?.let(::parse) as? Event.Start)?.id
                    if (startId != id || length < offset) {
                        id = startId
                        offset = 0
                        pending = ByteArray(0)
                    }
                    if (length <= offset) return@use emptyList()
                    input.seek(offset)
                    val bytes = ByteArray((length - offset).coerceAtMost(MAX_READ.toLong()).toInt())
                    input.readFully(bytes)
                    offset += bytes.size
                    val text = pending + bytes
                    val end = text.lastIndexOf('\n'.code.toByte())
                    pending = if (end < 0) text else text.copyOfRange(end + 1, text.size)
                    if (end < 0) return@use emptyList()
                    String(text, 0, end, Charsets.UTF_8).split('\n').mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let(::parse) }
                }
            }.getOrDefault(emptyList())
        }

        private fun firstLine(input: RandomAccessFile): String? {
            input.seek(0)
            val head = ByteArray(minOf(input.length(), 512L).toInt())
            input.readFully(head)
            val end = head.indexOf('\n'.code.toByte())
            return if (end < 0) null else String(head, 0, end, Charsets.UTF_8)
        }

        private companion object {
            /** At most this much per read: a log the IDE meets late is taken in pieces, the next read goes on. */
            const val MAX_READ = 4 * 1024 * 1024
        }
    }

    /** A place a line of a stack names: `Player:Update () (at Assets/Scripts/Player.cs:30)`. */
    data class Place(val path: String, val line: Int, val start: Int, val end: Int)

    private val AT = Regex("""\(at ([^()\r\n]+?):(\d+)\)""")

    /** The places of [text] (a stack, or a message the game wrote with one), with their offsets for links. */
    fun places(text: String): List<Place> = AT.findAll(text).map { match ->
        val path = match.groups[1]!!
        Place(path.value, match.groupValues[2].toInt(), path.range.first, match.groups[2]!!.range.last + 1)
    }.toList()

    /**
     * Where a double click on [entry] goes, with its file: the place of a message of the compiler; the first place of the stack in a file
     * of the project [root] (the engine's own frames have none, or a path of the machine that built it; a project copied with its
     * `Library` names the files of the original until it compiles them again).
     */
    fun target(entry: Entry, root: File): Pair<Place, File>? {
        if (entry.compiler) return entry.file.takeIf { it.isNotEmpty() }?.let { path -> resolve(root, path)?.let { Place(path, entry.line, 0, 0) to it } }
        val top = root.absoluteFile.normalize()
        return places(entry.stack).firstNotNullOfOrNull { place -> resolve(root, place.path)?.takeIf { it.absoluteFile.normalize().startsWith(top) }?.let { place to it } }
    }


    /**
     * The file of [path] in the Unity project [root]: relative to the root; `Packages/<name>/…` of a package that is not embedded is in
     * `Library/PackageCache/<name>@<hash>/…`; an absolute path as it is.
     */
    fun resolve(root: File, path: String): File? {
        val normalized = path.replace('\\', '/')
        File(normalized).takeIf { it.isAbsolute && it.isFile }?.let { return it }
        File(root, normalized).takeIf { it.isFile }?.let { return it }
        if (normalized.startsWith("Packages/")) {
            val name = normalized.removePrefix("Packages/").substringBefore('/')
            val rest = normalized.removePrefix("Packages/$name/")
            val cache = File(root, "Library/PackageCache").listFiles { file -> file.isDirectory && file.name.startsWith("$name@") }.orEmpty()
            return cache.map { File(it, rest) }.firstOrNull { it.isFile }
        }
        return null
    }

    /** The rows of the list: [entries] that pass [shown], identical ones as one row with their count when [collapse]. */
    data class Row(val entry: Entry, val count: Int)

    fun rows(entries: List<Entry>, collapse: Boolean, shown: (Entry) -> Boolean): List<Row> {
        val visible = entries.filter(shown)
        if (!collapse) return visible.map { Row(it, 1) }
        val counts = LinkedHashMap<Triple<Severity, String, String>, Pair<Entry, Int>>()
        for (entry in visible) {
            val key = Triple(entry.severity, entry.summary, entry.stack)
            val known = counts[key]
            counts[key] = if (known == null) entry to 1 else known.first to known.second + 1
        }
        return counts.values.map { (entry, count) -> Row(entry, count) }
    }
}
