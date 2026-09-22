package io.github.dotnetsupport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.dotnetsupport.debugger.DapClosedException
import io.github.dotnetsupport.debugger.DapConnection
import io.github.dotnetsupport.debugger.DapException
import io.github.dotnetsupport.debugger.DapFraming
import io.github.dotnetsupport.debugger.DebugTerminal
import io.github.dotnetsupport.debugger.StackFrames
import io.github.dotnetsupport.debugger.DotNetDebugProcess
import io.github.dotnetsupport.debugger.DotNetExceptionBreakpointHandler
import io.github.dotnetsupport.debugger.DotNetLineBreakpointHandler
import io.github.dotnetsupport.debugger.DotNetValue
import io.github.dotnetsupport.debugger.int
import io.github.dotnetsupport.debugger.json
import io.github.dotnetsupport.debugger.string
import io.github.dotnetsupport.run.DotNetExceptionFilter
import io.github.dotnetsupport.run.HitCondition
import junit.framework.TestCase
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.StringWriter
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The plugin's own DAP client against a fake adapter on pipes: what the protocol and `dotnet-debugger` demand of it
 * (`dap-probe/FINDINGS.md`). No process is started.
 */
class DapClientTest : TestCase() {
    /** A fake adapter: reads what the client sends, writes what the test tells it to. */
    private inner class FakeAdapter {
        val toClient = PipedOutputStream()
        val fromClient = PipedInputStream(1 shl 20)
        val clientOutput = PipedOutputStream(fromClient)
        val clientInput = PipedInputStream(toClient, 1 shl 20)
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val trace = StringWriter()
        @Volatile var closed = false
        @Volatile var answerToRequest: JsonObject? = json("shellProcessId" to 1)
        @Volatile var failRequest: Exception? = null

        val connection = DapConnection(clientInput, clientOutput, object : DapConnection.Listener {
            override fun event(event: String, body: JsonObject) { events.put(event to body) }
            override fun request(command: String, arguments: JsonObject): JsonObject? = failRequest?.let { throw it } ?: answerToRequest
            override fun closed() { closed = true }
        }, trace)

        fun received(): JsonObject = JsonParser.parseString(DapFraming.read(fromClient)!!).asJsonObject

        fun send(json: String) {
            toClient.write(DapFraming.frame(json))
            toClient.flush()
        }

        fun respond(request: JsonObject, success: Boolean = true, body: String = "{}", message: String? = null) =
            send("""{"seq":100,"type":"response","request_seq":${request.int("seq")},"command":"${request.string("command")}","success":$success,"body":$body${message?.let { ",\"message\":\"$it\"" }.orEmpty()}}""")
    }

    fun testFramingWhateverTheChunks() {
        val two = DapFraming.frame("""{"seq":1,"text":"привет"}""") + DapFraming.frame("{}")
        val input = ByteArrayInputStream(two)
        assertEquals("""{"seq":1,"text":"привет"}""", DapFraming.read(input))
        assertEquals("{}", DapFraming.read(input))
        assertNull("the end between messages", DapFraming.read(input))
        // other headers are allowed, the length counts bytes, not characters
        assertEquals("ж", DapFraming.read(ByteArrayInputStream("Content-Type: x\r\nContent-Length: 2\r\n\r\nж".toByteArray())))
        assertThrows { DapFraming.read(ByteArrayInputStream("Content-Length: 10\r\n\r\n{}".toByteArray())) }
    }

    /** `dotnet-debugger` answers out of order: the responses are matched by `request_seq`. */
    fun testResponsesOutOfOrder() {
        val adapter = FakeAdapter()
        adapter.connection.start("test")
        val slow = adapter.connection.request("stackTrace", json("threadId" to 1))
        val fast = adapter.connection.request("threads")
        val first = adapter.received()
        val second = adapter.received()
        assertEquals(listOf("stackTrace", "threads"), listOf(first.string("command"), second.string("command")))
        adapter.respond(second, body = """{"threads":[{"id":7,"name":"Main Thread"}]}""")
        adapter.respond(first, body = """{"stackFrames":[]}""")
        assertEquals(7, fast.get(5, TimeUnit.SECONDS).getAsJsonArray("threads")[0].asJsonObject.int("id"))
        assertTrue(slow.get(5, TimeUnit.SECONDS).has("stackFrames"))
        adapter.connection.close()
    }

    fun testErrorsEventsAndRequestsOfTheAdapter() {
        val adapter = FakeAdapter()
        adapter.connection.start("test")

        // the text of the adapter, with its variables filled in
        val failed = adapter.connection.request("setExpression", json("expression" to "x", "value" to "abc"))
        adapter.respond(adapter.received(), success = false, body = """{"error":{"id":1,"format":"Cannot convert '{value}' to int","variables":{"value":"abc"}}}""")
        val error = assertThrowsExecution { failed.get(5, TimeUnit.SECONDS) }
        assertEquals("Cannot convert 'abc' to int", DotNetDebugProcess.errorText(error))
        assertTrue(error.cause is DapException)
        val plain = adapter.connection.request("next")
        adapter.respond(adapter.received(), success = false, message = "Thread 5 is not known")
        assertEquals("Thread 5 is not known", DotNetDebugProcess.errorText(assertThrowsExecution { plain.get(5, TimeUnit.SECONDS) }))

        adapter.send("""{"seq":5,"type":"event","event":"stopped","body":{"reason":"breakpoint","threadId":53520}}""")
        val (event, body) = adapter.events.poll(5, TimeUnit.SECONDS)!!
        assertEquals("stopped" to 53520, event to body.int("threadId"))

        // a request of the adapter is answered: with the body the listener gives, or refused
        adapter.send("""{"seq":6,"type":"request","command":"runInTerminal","arguments":{"args":["a"]}}""")
        val answered = adapter.received()
        assertEquals(listOf("response", "6", "true"), listOf(answered.string("type"), answered.get("request_seq").asString, answered.get("success").asString))
        adapter.answerToRequest = null
        adapter.send("""{"seq":7,"type":"request","command":"startDebugging","arguments":{}}""")
        assertEquals(false, adapter.received().get("success").asBoolean)
        // a request the client could not do: the adapter gets the reason, and shows it as the reason the launch failed
        adapter.failRequest = IllegalStateException("Cannot run program \"dotnet-debugger\"")
        adapter.send("""{"seq":8,"type":"request","command":"runInTerminal","arguments":{"args":["dotnet-debugger"]}}""")
        val refused = adapter.received()
        assertEquals(false, refused.get("success").asBoolean)
        assertEquals("Cannot run program \"dotnet-debugger\"", refused.string("message"))

        // every message both ways goes to the trace
        assertTrue(adapter.trace.toString().contains("-> {\"seq\":1,\"type\":\"request\",\"command\":\"setExpression\""))
        assertTrue(adapter.trace.toString().contains("<- {\"seq\":5,\"type\":\"event\",\"event\":\"stopped\""))
        adapter.connection.close()
    }

    /** The adapter is gone: what was waiting ends at once, and so does whatever is asked afterwards. */
    fun testClosedConnection() {
        val adapter = FakeAdapter()
        adapter.connection.start("test")
        val waiting = adapter.connection.request("evaluate", json("expression" to "slow"))
        adapter.received()
        adapter.toClient.close()
        assertTrue(assertThrowsExecution { waiting.get(5, TimeUnit.SECONDS) }.cause is DapClosedException)
        val deadline = System.currentTimeMillis() + 5000
        while (!adapter.closed && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(adapter.closed)
        assertTrue(assertThrowsExecution { adapter.connection.request("threads").get(1, TimeUnit.SECONDS) }.cause is DapClosedException)
        // a busy adapter answers nothing at all: a request with a timeout gives up
        val busy = FakeAdapter()
        busy.connection.start("busy")
        assertEquals("The debugger has not answered in time", DotNetDebugProcess.errorText(assertThrowsExecution { busy.connection.request("disconnect", null, 100).get(5, TimeUnit.SECONDS) }))
        busy.connection.close()
    }

    /** The breakpoints of `setBreakpoints`: 1-based lines, the condition, the hit count and the log message as the adapter takes them. */
    fun testBreakpointRequests() {
        assertEquals(mapOf("line" to 61), DotNetLineBreakpointHandler.breakpointJson(60, null, null, null))
        assertEquals(mapOf("line" to 1, "condition" to "i == 1", "hitCondition" to ">=3", "logMessage" to "total = {total}"),
            DotNetLineBreakpointHandler.breakpointJson(0, " i == 1 ", " > = 3 ", " total = {total} "))
        // what the adapter would refuse the whole breakpoint for is not sent
        assertEquals(mapOf("line" to 5), DotNetLineBreakpointHandler.breakpointJson(4, " ", "abc", ""))

        for (valid in listOf("", "  ", "5", "==5", "= 5", ">= 3", ">3", "<=10", "< 2", "% 10")) assertTrue(valid, HitCondition.isValid(valid))
        for (invalid in listOf("0", "-1", "abc", "5 times", ">=", "!= 3", "3 >", "1.5")) assertFalse(invalid, HitCondition.isValid(invalid))
    }

    /** One filter per "break when" with the types as its condition; an empty list is still sent (see the handler). */
    fun testExceptionBreakpointRequests() {
        val none = DotNetExceptionBreakpointHandler.arguments(emptyList(), filterOptions = true)
        assertEquals(0, none.getAsJsonArray("filters").size())
        assertEquals(0, none.getAsJsonArray("filterOptions").size())
        val two = DotNetExceptionBreakpointHandler.arguments(listOf(
            " Playground.Lib.ShopException " to setOf(DotNetExceptionFilter.THROWN),
            null to setOf(DotNetExceptionFilter.USER_UNHANDLED, DotNetExceptionFilter.UNHANDLED),
        ), filterOptions = true)
        assertEquals(
            """[{"filterId":"all","condition":"Playground.Lib.ShopException"},{"filterId":"user-unhandled"},{"filterId":"unhandled"}]""",
            two.getAsJsonArray("filterOptions").toString(),
        )
        // an adapter without filter options gets the plain ids
        val plain = DotNetExceptionBreakpointHandler.arguments(listOf("A" to setOf(DotNetExceptionFilter.THROWN), "B" to setOf(DotNetExceptionFilter.THROWN)), filterOptions = false)
        assertEquals("""["all"]""", plain.getAsJsonArray("filters").toString())
        assertFalse(plain.has("filterOptions"))
    }

    fun testSetValueRequest() {
        val arguments = DotNetValue.setExpressionArguments("person.Age", " 37 ", 12)
        assertEquals("""{"expression":"person.Age","value":"37","frameId":12}""", arguments.toString())
        assertFalse("no frame, no frameId", DotNetValue.setExpressionArguments("x", "1", null).has("frameId"))
        assertEquals("boom", DotNetDebugProcess.errorText(IllegalStateException("boom")))
    }

    /** The async call stack comes after a frame with the hint `label`: it is not shown, it is the caption above the next frame. */
    fun testAsyncCallStackLabel() {
        fun frame(id: Int, name: String, hint: String? = null) = json("id" to id, "name" to name, "presentationHint" to hint)
        val frames = StackFrames { frame, caption -> "${frame.string("name")}${caption?.let { " [$it]" }.orEmpty()}" }
        assertEquals(listOf("MoveNext()", "Start()"), frames.next(listOf(frame(1, "MoveNext()"), frame(2, "Start()"))))
        // the label ends one page, its frame begins the next
        assertEquals(listOf("[External Code]"), frames.next(listOf(frame(3, "[External Code]", "subtle"), frame(4, "[Async Call Stack]", "label"))))
        assertEquals(listOf("Program.Main() [Async Call Stack]", "Program.Run()"), frames.next(listOf(frame(5, "Program.Main()"), frame(6, "Program.Run()"))))

        // the frames the platform has already got are skipped by what is shown, the labels do not count
        val rest = StackFrames(skip = 2) { frame, caption -> frame.int("id") to caption }
        assertEquals(listOf(4 to "Async Call Stack", 5 to null),
            rest.next(listOf(frame(1, "a"), frame(2, "b"), frame(3, "[Async Call Stack]", "label"), frame(4, "c"), frame(5, "d"))))
    }

    /** The program started for `runInTerminal`: its console is switched to UTF-8 by the adapter's own helper mode, input is re-encoded. */
    fun testTerminal() {
        val exe = listOf("C:/tools/dotnet-debugger.exe", "--run-in-terminal", "50123", "token", "--", "C:/app/App.exe", "--run-in-terminal")
        assertEquals(listOf("C:/tools/dotnet-debugger.exe", "--console-utf8", "42"), DebugTerminal.utf8ConsoleCommand(exe, 42))
        val dll = listOf("C:/dotnet/dotnet.exe", "C:/tools/dotnet-debugger.dll", "--run-in-terminal", "1", "t", "--", "App.exe")
        assertEquals(listOf("C:/dotnet/dotnet.exe", "C:/tools/dotnet-debugger.dll", "--console-utf8", "7"), DebugTerminal.utf8ConsoleCommand(dll, 7))
        assertNull("not the helper of this adapter", DebugTerminal.utf8ConsoleCommand(listOf("App.exe", "a"), 1))

        val cp1251 = charset("windows-1251")
        assertEquals("привет", String(DebugTerminal.transcode("привет".toByteArray(cp1251), cp1251, Charsets.UTF_8), Charsets.UTF_8))
    }

    private fun assertThrowsExecution(block: () -> Unit): ExecutionException {
        try {
            block()
        } catch (e: ExecutionException) {
            return e
        }
        fail("no exception")
        throw IllegalStateException()
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail("no exception")
    }
}
