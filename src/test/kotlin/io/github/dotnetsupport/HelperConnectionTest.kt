package io.github.dotnetsupport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.HelperConnection
import io.github.dotnetsupport.cli.HelperException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** The protocol of the helpers that stay running, against a fake helper on pipes. */
class HelperConnectionTest : BasePlatformTestCase() {
    /** A helper that answers `echo` with its params, `fail` with an error, `die` by ending its output, and ignores `silent`. */
    private class FakeHelper {
        val starts = AtomicInteger()

        fun start(): HelperConnection.Transport {
            starts.incrementAndGet()
            val toHelper = PipedOutputStream()
            val helperInput = PipedInputStream(toHelper, 65536)
            val helperOutput = PipedOutputStream()
            val fromHelper = PipedInputStream(helperOutput, 65536)
            val alive = java.util.concurrent.atomic.AtomicBoolean(true)
            Thread {
                val writer = helperOutput.bufferedWriter()
                fun write(text: String) = synchronized(writer) { writer.write(text + "\n"); writer.flush() }
                try {
                    helperInput.bufferedReader().forEachLine { line ->
                        val request = JsonParser.parseString(line).asJsonObject
                        val id = request.get("id")?.asLong ?: return@forEachLine
                        when (request.get("method").asString) {
                            "echo" -> {
                                write("""{"method":"log","params":{"level":"info","message":"echo $id"}}""")
                                write(JsonObject().apply { addProperty("id", id); add("result", request.get("params")) }.toString())
                            }
                            "fail" -> write("""{"id":$id,"error":{"message":"no such project"}}""")
                            "die" -> { alive.set(false); helperOutput.close(); return@forEachLine }
                            else -> {}
                        }
                    }
                } catch (_: java.io.IOException) {
                }
                alive.set(false)
            }.apply { isDaemon = true }.start()
            return HelperConnection.Transport(fromHelper, toHelper, null, alive::get) { alive.set(false); runCatching { helperOutput.close() } }
        }
    }

    fun testRequestsAreAnsweredByTheirIds() {
        val fake = FakeHelper()
        val connection = HelperConnection("test", fake::start)
        try {
            val params = JsonObject().apply { addProperty("path", "C:\\src\\App.csproj"); addProperty("text", "привет") }
            assertEquals(params, connection.request("echo", params))
            // several at once: each gets its own answer
            val answers = (1..20).toList().parallelStream().map { n ->
                connection.request("echo", JsonObject().apply { addProperty("n", n) }).asJsonObject.get("n").asInt
            }.toList()
            assertEquals((1..20).toList(), answers)
            assertEquals(1, fake.starts.get())
        } finally {
            connection.dispose()
        }
    }

    fun testErrorsTimeoutsAndRestarts() {
        val fake = FakeHelper()
        val connection = HelperConnection("test", fake::start)
        try {
            assertEquals("no such project", failure { connection.request("fail") })
            assertTrue(failure { connection.request("silent", timeoutMs = 300) }!!.contains("no answer"))
            // a helper that dies fails the request at once and is started again by the next one
            assertTrue(failure { connection.request("die", timeoutMs = 10_000) }!!.contains("exited"))
            assertEquals(JsonObject(), connection.request("echo", JsonObject()))
            assertEquals(2, fake.starts.get())
        } finally {
            connection.dispose()
        }
        assertTrue(failure { connection.request("echo") }!!.contains("stopped"))
    }

    fun testAHelperThatCannotStartSaysWhy() {
        val connection = HelperConnection("test") { throw HelperException("MsBuildHost could not be built: no SDK") }
        assertEquals("MsBuildHost could not be built: no SDK", failure { connection.request("echo") })
        connection.dispose()
    }
}

/** The message of the [HelperException] that [block] throws. */
private fun failure(block: () -> Unit): String? {
    try {
        block()
    } catch (e: HelperException) {
        return e.message
    }
    throw AssertionError("no HelperException")
}
