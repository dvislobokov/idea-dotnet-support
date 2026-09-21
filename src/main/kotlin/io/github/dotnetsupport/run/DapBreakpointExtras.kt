package io.github.dotnetsupport.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** What a line breakpoint has beyond the line and the condition, in the terms of the protocol: `hitCondition` and `logMessage`. */
class BreakpointExtras(val hitCondition: String?, val logMessage: String?) {
    val isEmpty: Boolean get() = hitCondition.isNullOrBlank() && logMessage.isNullOrBlank()
}

/** The hit conditions `dotnet-debugger` understands: a positive number, optionally after `==`, `>=`, `>`, `<=`, `<` or `%`. */
object HitCondition {
    private val SYNTAX = Regex("""(==?|>=|>|<=|<|%)?\s*([1-9]\d*)""")

    fun isValid(text: String?): Boolean = text.isNullOrBlank() || SYNTAX.matches(text.trim())

    /** `> = 3` is `>=3` to the adapter as well; blank is no condition. */
    fun normalize(text: String?): String? = text?.replace(" ", "")?.takeIf { it.isNotEmpty() }
}

/**
 * The DAP client of the platform sends `line`, `column` and `condition` of a breakpoint and nothing else, and its breakpoint handler is
 * final. Writing a handler of our own would mean repeating all of its bookkeeping (pending and verified breakpoints, changes while the
 * program runs, temporary breakpoints of Run to Cursor). The messages to the adapter, however, go through a stream the plugin creates
 * itself: a `setBreakpoints` request gets its `hitCondition` / `logMessage` on the way, and everything else passes untouched.
 */
object DapSetBreakpoints {
    /** Adds the extras to the breakpoints of a `setBreakpoints` request; false when [message] is something else or nothing was added. */
    fun addExtras(message: JsonObject, extras: (path: String, line: Int) -> BreakpointExtras?): Boolean {
        if (message.get("command")?.takeIf { it.isJsonPrimitive }?.asString != "setBreakpoints") return false
        val arguments = message.get("arguments") as? JsonObject ?: return false
        val path = (arguments.get("source") as? JsonObject)?.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        val breakpoints = arguments.get("breakpoints")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        var changed = false
        for (breakpoint in breakpoints) {
            val item = breakpoint as? JsonObject ?: continue
            val line = item.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            val extra = extras(path, line)?.takeIf { !it.isEmpty } ?: continue
            HitCondition.normalize(extra.hitCondition)?.let { item.addProperty("hitCondition", it) }
            extra.logMessage?.takeIf { it.isNotBlank() }?.let { item.addProperty("logMessage", it) }
            changed = true
        }
        return changed
    }

    /** The body of one DAP message, rewritten if it is a `setBreakpoints` request with extras; anything unexpected is returned as it came. */
    fun rewrite(body: ByteArray, extras: (path: String, line: Int) -> BreakpointExtras?, onError: (Exception) -> Unit = {}): ByteArray = try {
        // cheap check first: almost every message is something else
        if (!String(body, Charsets.UTF_8).contains("\"setBreakpoints\"")) body
        else {
            val message = JsonParser.parseString(String(body, Charsets.UTF_8)) as? JsonObject
            if (message != null && addExtras(message, extras)) message.toString().toByteArray(Charsets.UTF_8) else body
        }
    } catch (e: Exception) {
        // the message goes out as the client wrote it: a breakpoint without its hit count is better than a broken session
        onError(e)
        body
    }
}

/**
 * A stream of DAP messages (`Content-Length: N\r\n\r\n` and N bytes of JSON) that lets [transform] replace the body of every message.
 * The writer may cut the stream anywhere, so bytes are kept until a message is complete; a stream that does not look like DAP is passed on.
 */
class DapMessageRewritingStream(private val target: OutputStream, private val transform: (ByteArray) -> ByteArray) : OutputStream() {
    private val pending = ByteArrayOutputStream()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        pending.write(b, off, len)
        drain()
    }

    private fun drain() {
        while (true) {
            val bytes = pending.toByteArray()
            val headerEnd = indexOf(bytes, HEADER_END)
            if (headerEnd < 0) {
                if (bytes.size > MAX_HEADER) passThrough(bytes) // not a DAP header: do not hold the stream back
                return
            }
            val length = CONTENT_LENGTH.find(String(bytes, 0, headerEnd, Charsets.US_ASCII))?.groupValues?.get(1)?.toIntOrNull()
            if (length == null) {
                passThrough(bytes)
                return
            }
            val bodyStart = headerEnd + HEADER_END.size
            if (bytes.size < bodyStart + length) return
            val body = transform(bytes.copyOfRange(bodyStart, bodyStart + length))
            target.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            target.write(body)
            pending.reset()
            pending.write(bytes, bodyStart + length, bytes.size - bodyStart - length)
        }
    }

    private fun passThrough(bytes: ByteArray) {
        target.write(bytes)
        pending.reset()
    }

    @Synchronized
    override fun flush() = target.flush()

    override fun close() = target.close()

    private companion object {
        val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val CONTENT_LENGTH = Regex("""(?i)Content-Length:\s*(\d+)""")
        const val MAX_HEADER = 8192

        fun indexOf(bytes: ByteArray, part: ByteArray): Int {
            outer@ for (i in 0..bytes.size - part.size) {
                for (j in part.indices) if (bytes[i + j] != part[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
