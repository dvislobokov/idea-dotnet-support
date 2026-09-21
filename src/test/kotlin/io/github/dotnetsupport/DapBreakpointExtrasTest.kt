package io.github.dotnetsupport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.dotnetsupport.run.BreakpointExtras
import io.github.dotnetsupport.run.DapMessageRewritingStream
import io.github.dotnetsupport.run.DapSetBreakpoints
import io.github.dotnetsupport.run.HitCondition
import junit.framework.TestCase
import java.io.ByteArrayOutputStream

/** Hit counts and log messages of breakpoints: added to the `setBreakpoints` requests the DAP client of the platform writes. */
class DapBreakpointExtrasTest : TestCase() {
    private val extras: (String, Int) -> BreakpointExtras? = { path, line ->
        when {
            path.endsWith("Scenarios.cs") && line == 98 -> BreakpointExtras(" > = 2 ", "")
            path.endsWith("Scenarios.cs") && line == 110 -> BreakpointExtras("", "greeting = {greeting}")
            path.endsWith("Scenarios.cs") && line == 60 -> BreakpointExtras("", " ")
            else -> null
        }
    }

    private fun frame(json: String): ByteArray = ("Content-Length: ${json.toByteArray(Charsets.UTF_8).size}\r\n\r\n").toByteArray(Charsets.US_ASCII) + json.toByteArray(Charsets.UTF_8)

    /** The bodies of the messages in [bytes]; fails when the framing is broken. */
    private fun bodies(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        var offset = 0
        while (offset < bytes.size) {
            val headerEnd = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n", offset)
            assertTrue("no header at $offset", headerEnd >= 0)
            val length = Regex("""Content-Length: (\d+)""").find(String(bytes, offset, headerEnd - offset, Charsets.US_ASCII))!!.groupValues[1].toInt()
            result += String(bytes, headerEnd + 4, length, Charsets.UTF_8)
            offset = headerEnd + 4 + length
        }
        return result
    }

    fun testHitConditionSyntax() {
        for (valid in listOf("", "  ", "5", "==5", "= 5", ">= 3", ">3", "<=10", "< 2", "% 10")) assertTrue(valid, HitCondition.isValid(valid))
        for (invalid in listOf("0", "-1", "abc", "5 times", ">=", "!= 3", "3 >", "1.5")) assertFalse(invalid, HitCondition.isValid(invalid))
        assertEquals(">=3", HitCondition.normalize(" > = 3 "))
        assertNull(HitCondition.normalize("  "))
    }

    fun testExtrasAreAddedToSetBreakpoints() {
        val request = JsonParser.parseString(
            """{"type":"request","seq":5,"command":"setBreakpoints","arguments":{"source":{"name":"Scenarios.cs","path":"C:/repo/Console/Scenarios.cs"},
               "breakpoints":[{"line":60},{"line":98,"condition":"i > 0"},{"line":110},{"line":200}],"lines":[60,98,110,200],"sourceModified":false}}""",
        ) as JsonObject
        assertTrue(DapSetBreakpoints.addExtras(request, extras))
        val breakpoints = request.getAsJsonObject("arguments").getAsJsonArray("breakpoints").map { it.asJsonObject }
        assertEquals("""{"line":60}""", breakpoints[0].toString()) // blank extras are no extras
        assertEquals("""{"line":98,"condition":"i > 0","hitCondition":">=2"}""", breakpoints[1].toString())
        assertEquals("""{"line":110,"logMessage":"greeting = {greeting}"}""", breakpoints[2].toString())
        assertEquals("""{"line":200}""", breakpoints[3].toString())

        // nothing to add, another request, a broken one: untouched
        assertFalse(DapSetBreakpoints.addExtras(JsonParser.parseString("""{"command":"setBreakpoints","arguments":{"source":{"path":"/repo/Other.cs"},"breakpoints":[{"line":98}]}}""") as JsonObject, extras))
        assertFalse(DapSetBreakpoints.addExtras(JsonParser.parseString("""{"command":"next","arguments":{"threadId":1}}""") as JsonObject, extras))
        assertFalse(DapSetBreakpoints.addExtras(JsonParser.parseString("""{"command":"setBreakpoints"}""") as JsonObject, extras))
        val garbage = "not json, mentions \"setBreakpoints\"".toByteArray()
        assertSame(garbage, DapSetBreakpoints.rewrite(garbage, extras))
    }

    fun testStreamKeepsTheFramingWhateverTheChunks() {
        val set = """{"type":"request","seq":5,"command":"setBreakpoints","arguments":{"source":{"path":"C:/repo/Scenarios.cs"},"breakpoints":[{"line":98}]}}"""
        val next = """{"type":"request","seq":6,"command":"next","arguments":{"threadId":1},"note":"кириллица"}"""
        val input = frame(next) + frame(set) + frame(next)

        for (chunk in listOf(1, 7, 64, input.size)) {
            val target = ByteArrayOutputStream()
            val stream = DapMessageRewritingStream(target) { DapSetBreakpoints.rewrite(it, extras) }
            var offset = 0
            while (offset < input.size) {
                val size = minOf(chunk, input.size - offset)
                stream.write(input, offset, size)
                offset += size
            }
            stream.flush()
            val messages = bodies(target.toByteArray())
            assertEquals("chunk $chunk", 3, messages.size)
            assertEquals(next, messages[0])
            assertEquals(">=2", (JsonParser.parseString(messages[1]) as JsonObject).getAsJsonObject("arguments").getAsJsonArray("breakpoints")[0].asJsonObject.get("hitCondition").asString)
            assertEquals(next, messages[2])
        }
    }

    fun testStreamDoesNotHoldBackWhatIsNotDap() {
        val target = ByteArrayOutputStream()
        val stream = DapMessageRewritingStream(target) { it }
        val junk = ByteArray(10_000) { 'x'.code.toByte() }
        stream.write(junk)
        assertEquals(10_000, target.size())
        // a header without a length is passed on as well
        val odd = "X-Header: 1\r\n\r\n{}".toByteArray()
        stream.write(odd)
        assertEquals(10_000 + odd.size, target.size())
    }
}
