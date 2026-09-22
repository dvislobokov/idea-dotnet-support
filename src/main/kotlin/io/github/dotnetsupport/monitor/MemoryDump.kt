package io.github.dotnetsupport.monitor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val LOG = logger<DumpAnalyzer>()

/** A type in `dumpheap -stat`: the method table is what the other commands take (`dumpheap -mt`). */
class SosType(val methodTable: String, val name: String, val count: Long, val totalSize: Long)

class SosObject(val address: String, val methodTable: String, val size: Long)

/** One way an object is kept alive: the root (a handle, a local of a frame, the finalizer queue), then object after object down to it. */
class SosRootPath(val root: String, val steps: List<SosRootStep>)

/** [note]: what SOS says of the reference, e.g. `static variable: ...` - its own guess, shown as it is. */
class SosRootStep(val address: String, val type: String, val note: String?)

class SosField(val name: String, val type: String, val isValueType: Boolean, val isStatic: Boolean, val value: String) {
    /** The address of the object the field refers to, for a reference that is not null. */
    val reference: String? get() = if (isValueType || value.trimStart('0').isEmpty() || !HEX.matches(value)) null else value

    private companion object {
        val HEX = Regex("[0-9a-fA-F]{8,16}")
    }
}

class SosObjectDetails(val type: String, val size: Long, val stringValue: String?, val fields: List<SosField>)

/**
 * The output of the SOS commands of `dotnet-dump analyze` (probed with dotnet-dump 10.0, see `ROADMAP.md`): tables aligned by spaces,
 * numbers with `,` as the thousands separator whatever the culture, addresses in hex without `0x`.
 */
object Sos {
    private val TYPE_ROW = Regex("""^\s*([0-9a-fA-F]{6,16})\s+([\d,]+)\s+([\d,]+)\s+(\S.*?)\s*$""")
    private val OBJECT_ROW = Regex("""^\s*([0-9a-fA-F]{6,16})\s+([0-9a-fA-F]{6,16})\s+([\d,]+)\b.*$""")
    private val STEP = Regex("""^\s*->\s+([0-9a-fA-F]{6,16})\s+(.+?)\s*$""")
    private val NOTE = Regex("""^(.*?)\s+\(([^()]*(?:\([^()]*\))?[^()]*)\)$""")
    private val FIELD_ROW = Regex("""^\s*[0-9a-fA-F]{8,16}\s+[0-9a-fA-F]{6,8}\s+[0-9a-fA-F]+\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s*$""")
    private val FRAME_POINTERS = Regex("""^[0-9a-fA-F]{8,16}\s+[0-9a-fA-F]{8,16}\s+(?=\S)""")
    private val TOTAL = Regex("""Total\s+([\d,]+)\s+objects?,\s+([\d,]+)\s+bytes""")

    /** `dumpheap -stat`: the types, the largest first. */
    fun heapStat(output: String): List<SosType> = rows(output, after = "Statistics:").mapNotNull { line ->
        TYPE_ROW.matchEntire(line)?.destructured?.let { (mt, count, size, name) -> SosType(mt, name, number(count), number(size)) }
    }.sortedByDescending { it.totalSize }.toList()

    /** `dumpheap -mt <mt>`: the objects, until its statistics. */
    fun objects(output: String): List<SosObject> = output.lineSequence().takeWhile { !it.trimStart().startsWith("Statistics:") }
        .mapNotNull { OBJECT_ROW.matchEntire(it)?.destructured?.let { (address, mt, size) -> SosObject(address, mt, number(size)) } }.toList()

    /** `Total 1,418 objects, 143,720 bytes` of `dumpheap` and `objsize`. */
    fun total(output: String): Pair<Long, Long>? = TOTAL.find(output)?.destructured?.let { (objects, bytes) -> number(objects) to number(bytes) }

    /**
     * `gcroot <address>`: a section per kind of root (`HandleTable:`, `Thread 1a2b:`, `Finalizer Queue:`), in it the root in a line or
     * two (a handle; a frame and its register or stack slot), then `-> address type (note)` down to the object.
     * ```
     * HandleTable:
     *     00000228058a13e8 (strong handle)
     *           -> 022807400028     System.Object[]
     *           -> 022809c48e28     Playground.PriceWatcher
     * Found 1 unique roots.
     * ```
     */
    fun gcRoots(output: String): List<SosRootPath> {
        val paths = ArrayList<SosRootPath>()
        var section = ""
        val root = ArrayList<String>()
        val steps = ArrayList<SosRootStep>()
        fun flush() {
            if (steps.isNotEmpty()) paths += SosRootPath(listOf(section, root.joinToString(" ")).filter { it.isNotBlank() }.joinToString(": "), steps.toList())
            steps.clear()
            root.clear()
        }
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(">") && !trimmed.startsWith("->") || trimmed.startsWith("Found ") || trimmed.startsWith("Caching GC roots") ||
                trimmed.startsWith("Subsequent runs")) continue
            val step = STEP.matchEntire(line)
            if (step != null) {
                val (address, rest) = step.destructured
                val note = NOTE.matchEntire(rest)
                steps += SosRootStep(address, (note?.groupValues?.get(1) ?: rest).trim(), note?.groupValues?.get(2)?.trim())
                continue
            }
            if (steps.isNotEmpty()) flush()
            // a frame of a stack root starts with its stack pointer and instruction pointer: of no use to the reader
            if (!line.startsWith(" ") && trimmed.endsWith(":")) section = trimmed.removeSuffix(":") else root += trimmed.replace(FRAME_POINTERS, "")
        }
        flush()
        return paths
    }

    /** `dumpobj <address>`: the type, the size, the text of a string, the fields. */
    fun dumpObj(output: String): SosObjectDetails? {
        var type: String? = null
        var size = 0L
        var text: String? = null
        val fields = ArrayList<SosField>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Name:") -> type = trimmed.removePrefix("Name:").trim()
                trimmed.startsWith("Size:") -> size = number(trimmed.removePrefix("Size:").trim().substringBefore('('))
                trimmed.startsWith("String:") -> text = trimmed.removePrefix("String:").trim()
                else -> FIELD_ROW.matchEntire(line)?.destructured?.let { (fieldType, vt, attr, value, name) ->
                    fields += SosField(name, fieldType, vt == "Yes" || vt == "1", attr == "static", value)
                }
            }
        }
        return type?.let { SosObjectDetails(it, size, text, fields) }
    }

    /** The command echoed first (`> dumpheap -stat`) is not part of the answer. */
    fun withoutEcho(output: String): String = output.lineSequence().dropWhile { it.isBlank() || it.startsWith("> ") }.joinToString("\n").trimEnd()

    private fun rows(output: String, after: String): Sequence<String> {
        val lines = output.lineSequence()
        return if (output.contains(after)) lines.dropWhile { !it.trimStart().startsWith(after) }.drop(1) else lines
    }

    private fun number(text: String): Long = text.filter { it.isDigit() }.toLongOrNull() ?: 0
}

/** The answers of `dotnet-dump analyze` from its output as it arrives: each one ends with a marker line, of success or of failure. */
class SosAnswers {
    class Answer(val text: String, val failed: Boolean)

    private val buffer = StringBuilder()

    fun feed(text: String): List<Answer> {
        buffer.append(text)
        val answers = ArrayList<Answer>()
        while (true) {
            val ok = buffer.indexOf(OK)
            val error = buffer.indexOf(ERROR)
            val (at, marker) = listOf(ok to OK, error to ERROR).filter { it.first >= 0 }.minByOrNull { it.first } ?: break
            answers += Answer(Sos.withoutEcho(buffer.substring(0, at)), marker == ERROR)
            buffer.delete(0, at + marker.length)
        }
        return answers
    }

    companion object {
        const val OK = "<END_COMMAND_OUTPUT>"
        const val ERROR = "<END_COMMAND_ERROR>"
    }
}

class SosException(message: String) : Exception(message)

/**
 * One `dotnet-dump analyze` for a dump, kept open: the dump is loaded once, each command takes milliseconds after that (a new process per
 * command would load it again). Commands go through its standard input one at a time; with the input redirected it ends every answer with
 * a marker instead of a prompt ([SosAnswers]). The first answer is the banner, when the dump has been loaded.
 */
class DumpAnalyzer(tool: File, val dump: File) : Disposable {
    private val process: Process = GeneralCommandLine(tool.path, "analyze", dump.path).withCharset(Charsets.UTF_8)
        .withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en").withRedirectErrorStream(true).createProcess()
    private val waiting = ArrayDeque<CompletableFuture<String>>()
    private val answers = SosAnswers()
    private val lock = Any()
    @Volatile private var closed = false

    /** The dump is loaded and commands can be sent. */
    val loaded: CompletableFuture<String> = CompletableFuture<String>().also { waiting.add(it) }

    init {
        Thread({ read() }, "dotnet-dump analyze ${dump.name}").apply { isDaemon = true }.start()
    }

    /** The answer without the echo of the command; a command SOS refuses ends with [SosException] and its text. */
    fun run(command: String, timeoutSeconds: Long = 300): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        synchronized(lock) {
            if (closed) return future.apply { completeExceptionally(SosException("The analysis of the dump has ended")) }
            waiting.add(future)
            try {
                process.outputStream.write((command.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            } catch (e: Exception) {
                waiting.remove(future)
                future.completeExceptionally(SosException("Cannot send $command: ${e.message}"))
            }
        }
        // an answer that never comes leaves the next ones out of step: the session ends
        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete { _, error -> if (error is java.util.concurrent.TimeoutException) dispose() }
    }

    private fun read() {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                val chunk = CharArray(64 * 1024)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    for (answer in answers.feed(String(chunk, 0, count))) {
                        val future = synchronized(lock) { waiting.poll() } ?: continue
                        if (answer.failed) future.completeExceptionally(SosException(answer.text.lines().firstOrNull { it.isNotBlank() }?.removePrefix("ERROR:")?.trim() ?: "The command failed"))
                        else future.complete(answer.text)
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) LOG.info("dotnet-dump analyze: ${e.message}")
        } finally {
            dispose()
        }
    }

    override fun dispose() {
        val left = synchronized(lock) {
            if (closed) return
            closed = true
            waiting.toList().also { waiting.clear() }
        }
        left.forEach { it.completeExceptionally(SosException("The analysis of the dump has ended")) }
        runCatching { process.outputStream.write("exit\n".toByteArray()); process.outputStream.flush() }
        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) OSProcessUtil.killProcessTree(process)
    }
}
