package io.github.dotnetsupport

import io.github.dotnetsupport.debugger.mono.MonoBreakpointLocations
import io.github.dotnetsupport.debugger.mono.MonoClosedException
import io.github.dotnetsupport.debugger.mono.MonoConnection
import io.github.dotnetsupport.debugger.mono.MonoDebugInfos
import io.github.dotnetsupport.debugger.mono.MonoEventSet
import io.github.dotnetsupport.debugger.mono.MonoEvents
import io.github.dotnetsupport.debugger.mono.MonoException
import io.github.dotnetsupport.debugger.mono.MonoModifier
import io.github.dotnetsupport.debugger.mono.MonoProtocol
import io.github.dotnetsupport.debugger.mono.MonoReader
import io.github.dotnetsupport.debugger.mono.MonoSequencePoint
import io.github.dotnetsupport.debugger.mono.MonoVersion
import io.github.dotnetsupport.debugger.mono.MonoWriter
import io.github.dotnetsupport.frameworks.unity.UnityDebugger
import io.github.dotnetsupport.frameworks.unity.UnityEditorLink
import junit.framework.TestCase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.StringWriter
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The plugin's client of the Mono soft debugger (UNITY_PLAN.md, "Отладчик") against a fake agent on a local socket that answers as the
 * agent of Unity 6000.6 did in the probe: the handshake, the `VM_START` it sends at once, the version, the threads. No Unity here.
 */
class MonoConnectionTest : TestCase() {
    /** A fake agent: the handshake, then [answer] gives the body of the reply to each command (null — the error code [error]). */
    private class FakeAgent(val answer: (set: Int, command: Int, body: MonoReader) -> ByteArray?, val error: Int = 101) {
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<String>()
        val accepted = CountDownLatch(1)
        lateinit var out: DataOutputStream

        init {
            Thread({
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    out = DataOutputStream(socket.getOutputStream())
                    val hello = ByteArray(13).also(input::readFully)
                    out.write(hello)
                    // what Unity 6000.6 sent right after the handshake: a VM_START, suspend policy NONE, thread 1, domain 1
                    packet(0, 0, MonoProtocol.Sets.EVENT, MonoProtocol.EVENT_COMPOSITE,
                        MonoWriter().byte(0).int(1).byte(MonoProtocol.EventKind.VM_START).int(0).id(1).id(1).toByteArray())
                    accepted.countDown()
                    try {
                        while (true) {
                            val length = input.readInt()
                            val id = input.readInt()
                            input.readByte()
                            val set = input.readUnsignedByte()
                            val command = input.readUnsignedByte()
                            val body = ByteArray(length - 11).also(input::readFully)
                            received += MonoProtocol.commandName(set, command)
                            if (set == MonoProtocol.Sets.VM && command == MonoProtocol.Vm.EXIT) return@use
                            val reply = answer(set, command, MonoReader(body))
                            synchronized(this) {
                                out.writeInt(11 + (reply?.size ?: 0)); out.writeInt(id); out.writeByte(0x80); out.writeShort(if (reply == null) error else 0)
                                if (reply != null) out.write(reply)
                                out.flush()
                            }
                        }
                    } catch (_: IOException) {
                    }
                }
            }, "fake Mono agent").apply { isDaemon = true }.start()
        }

        fun packet(id: Int, flags: Int, set: Int, command: Int, body: ByteArray) = synchronized(this) {
            out.writeInt(11 + body.size); out.writeInt(id); out.writeByte(flags); out.writeByte(set); out.writeByte(command); out.write(body); out.flush()
        }
    }

    private class Events : MonoConnection.Listener {
        val sets = LinkedBlockingQueue<MonoEventSet>()
        val closed = CountDownLatch(1)
        override fun events(set: MonoEventSet) { sets += set }
        override fun closed() = closed.countDown()
    }

    private fun unity(set: Int, command: Int, body: MonoReader): ByteArray? = when {
        set == MonoProtocol.Sets.VM && command == MonoProtocol.Vm.VERSION -> MonoWriter().string("mono 6.13.0 (Visual Studio built mono)").int(2).int(58).toByteArray()
        set == MonoProtocol.Sets.VM && command == MonoProtocol.Vm.SET_PROTOCOL_VERSION -> ByteArray(0).also { assertEquals(2, body.int()); assertEquals(58, body.int()) }
        set == MonoProtocol.Sets.VM && command == MonoProtocol.Vm.ALL_THREADS -> MonoWriter().int(2).id(1).id(3).toByteArray()
        set == MonoProtocol.Sets.THREAD && command == MonoProtocol.Thread.GET_NAME -> MonoWriter().string(if (body.id() == 3) "Timer-Scheduler" else "").toByteArray()
        set == MonoProtocol.Sets.VM && command == MonoProtocol.Vm.DISPOSE -> ByteArray(0)
        else -> null
    }

    fun testHandshakeVersionThreadsAndDispose() {
        val agent = FakeAgent(::unity)
        val events = Events()
        val trace = StringWriter()
        val socket = MonoConnection.connect("127.0.0.1", agent.server.localPort)
        val connection = MonoConnection(socket.getInputStream(), socket.getOutputStream(), events, trace).apply { start("test") }
        val start = events.sets.poll(5, TimeUnit.SECONDS)!!
        assertEquals("the editor is not stopped by the attach", MonoProtocol.SuspendPolicy.NONE, start.suspendPolicy)
        assertEquals(MonoProtocol.EventKind.VM_START, start.events.single().kind)
        assertEquals(1, start.events.single().subject)

        assertEquals(MonoVersion("mono 6.13.0 (Visual Studio built mono)", 2, 58), connection.negotiate().get(5, TimeUnit.SECONDS))
        assertEquals(MonoVersion("mono 6.13.0 (Visual Studio built mono)", 2, 58), connection.version)
        val threads = connection.allThreads().get(5, TimeUnit.SECONDS)
        assertEquals(listOf(1, 3), threads)
        assertEquals(listOf("", "Timer-Scheduler"), threads.map { connection.threadName(it).get(5, TimeUnit.SECONDS) })

        val error = runCatching { connection.resume().get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue("an error code is the command's failure, by name: $error", (error as ExecutionException).cause is MonoException)
        assertEquals("VM.4: NOT_SUSPENDED", error.cause!!.message)
        connection.dispose().get(5, TimeUnit.SECONDS)
        assertEquals(listOf("VM.1", "VM.8", "VM.2", "THREAD.2", "THREAD.2", "VM.4", "VM.6"), agent.received.toList())
        assertTrue(trace.toString(), trace.toString().contains("<- #2 VM.8 0 bytes"))
        connection.close()
        assertTrue(events.closed.await(5, TimeUnit.SECONDS))
        socket.close()
    }

    fun testWhatIsWaitingWhenTheDebuggeeGoesFailsAsClosed() {
        // the agent closes the socket at VM.EXIT, without a reply: the runtime has exited
        val agent = FakeAgent(::unity)
        val events = Events()
        val socket = MonoConnection.connect("127.0.0.1", agent.server.localPort)
        val connection = MonoConnection(socket.getInputStream(), socket.getOutputStream(), events).apply { start("test") }
        val exit = connection.command(MonoProtocol.Sets.VM, MonoProtocol.Vm.EXIT, MonoWriter().int(0).toByteArray())
        assertTrue((runCatching { exit.get(5, TimeUnit.SECONDS) }.exceptionOrNull() as ExecutionException).cause is MonoClosedException)
        assertTrue(events.closed.await(5, TimeUnit.SECONDS))
        assertTrue("a command after it fails at once",
            (runCatching { connection.allThreads().get(1, TimeUnit.SECONDS) }.exceptionOrNull() as ExecutionException).cause is MonoClosedException)
        socket.close()
    }

    fun testNotAnAgent() {
        val server = ServerSocket(0)
        Thread({ server.accept().use { it.getOutputStream().write("HTTP/1.1 400\r\n".toByteArray()) } }).apply { isDaemon = true }.start()
        val error = runCatching { MonoConnection.connect("127.0.0.1", server.localPort) }.exceptionOrNull()
        assertTrue("$error", error is IOException && error.message!!.startsWith("Not a Mono debugger agent"))
    }

    fun testEventsOfAComposite() {
        val body = MonoWriter().byte(MonoProtocol.SuspendPolicy.ALL).int(4)
            .byte(MonoProtocol.EventKind.BREAKPOINT).int(7).id(1).id(42).long(12)
            .byte(MonoProtocol.EventKind.USER_LOG).int(0).id(1).int(1).string("Debugger").string("hello")
            .byte(MonoProtocol.EventKind.VM_DEATH).int(0).id(1).int(3)
            // a kind this client does not know: the set ends there, the events before it stay
            .byte(99).int(0).id(1).toByteArray()
        val set = MonoEvents.decode(MonoReader(body), MonoVersion("mono", 2, 58))
        assertEquals(MonoProtocol.SuspendPolicy.ALL, set.suspendPolicy)
        assertEquals(listOf(MonoProtocol.EventKind.BREAKPOINT, MonoProtocol.EventKind.USER_LOG, MonoProtocol.EventKind.VM_DEATH), set.events.map { it.kind })
        assertEquals(42, set.events[0].subject)
        assertEquals(12L, set.events[0].location)
        assertEquals("Debugger: hello", set.events[1].message)
        assertEquals(3, set.events[2].code)
    }

    fun testTheDebugInfoOfAMethod() {
        // GameBootstrap.Start of nine-to-fire as Unity 6000.6 answered it (protocol 2.58): one file with its hash, points with columns and ends
        val file = """C:\Users\dvislobokov\unity-probe\nine-to-fire-probe\Assets\Game\Boot\GameBootstrap.cs"""
        val reply = MonoWriter().int(16).int(1).string(file).apply { repeat(16) { byte(it) } }.int(3)
            .int(0).int(61).int(0).int(9).int(61).int(10)
            .int(1).int(62).int(0).int(13).int(62).int(30)
            .int(8).int(MonoProtocol.HIDDEN_LINE).int(0).int(0).int(MonoProtocol.HIDDEN_LINE).int(0).toByteArray()
        val reader = MonoReader(reply)
        val info = MonoDebugInfos.read(reader, MonoVersion("mono", 2, 58))
        assertEquals("read to the last byte", 0, reader.remaining)
        assertEquals(16, info.maxIlOffset)
        assertEquals(listOf(61, 62), info.lines.map { it.line })
        assertEquals(file, info.points[1].file)
        assertEquals("a hidden point is no statement: the frame stays on the last line", 62, info.at(12)!!.line)
        assertEquals(61, info.at(0)!!.line)
    }

    fun testWhereABreakpointGoes() {
        fun points(vararg lines: Int, at: Int = 0) = lines.mapIndexed { i, line -> MonoSequencePoint(at + i * 4, line, "A.cs", 1, line, 2) }
        val awake = 3 to points(47, 48, 50, 51, 59)
        val start = 4 to points(61, 62, 63, 64)
        val lambda = 9 to points(50, at = 100)
        assertEquals("the statement of the line", listOf(3 to 4L), MonoBreakpointLocations.choose(48, listOf(awake, start)))
        assertEquals("a line without a statement: the next one in the same method", listOf(3 to 8L), MonoBreakpointLocations.choose(49, listOf(awake, start)))
        assertEquals("between two methods: nowhere", emptyList<Pair<Int, Long>>(), MonoBreakpointLocations.choose(60, listOf(awake, start)))
        assertEquals("a lambda on the line: the statement holding it", listOf(3 to 8L), MonoBreakpointLocations.choose(50, listOf(lambda, awake)))
        assertEquals("no code of the file", emptyList<Pair<Int, Long>>(), MonoBreakpointLocations.choose(10, listOf(awake)))
        assertTrue(MonoBreakpointLocations.samePath("""C:\Game\Assets\Player.cs""", "c:/game/assets/player.cs"))
        assertFalse(MonoBreakpointLocations.samePath("""C:\Game\Assets\Player.cs""", "C:/Other/Assets/Player.cs"))
    }

    fun testTheModifiersOfARequest() {
        fun bytes(modifier: MonoModifier, version: MonoVersion?) = MonoWriter().also { modifier.write(it, version) }.toByteArray().toList()
        assertEquals("thread, size, depth and the filter of 2.16", MonoWriter().id(39).int(1).int(1).int(7).toByteArray().toList(),
            bytes(MonoModifier.Step(39, MonoProtocol.StepSize.LINE, MonoProtocol.StepDepth.OVER, 7), MonoVersion("mono", 2, 58)))
        assertEquals("no filter before 2.16", MonoWriter().id(39).int(1).int(1).toByteArray().toList(),
            bytes(MonoModifier.Step(39, MonoProtocol.StepSize.LINE, MonoProtocol.StepDepth.OVER, 7), MonoVersion("mono", 2, 15)))
        assertEquals(MonoWriter().int(2).string("A.cs").string("B.cs").toByteArray().toList(), bytes(MonoModifier.SourceFiles(listOf("A.cs", "B.cs")), null))
        assertEquals(MonoWriter().id(3).long(1).toByteArray().toList(), bytes(MonoModifier.LocationOnly(3, 1), null))
    }

    fun testAnEventRequestOnTheWire() {
        val bodies = LinkedBlockingQueue<ByteArray>()
        val agent = FakeAgent({ set, command, body ->
            if (set == MonoProtocol.Sets.EVENT_REQUEST && command == MonoProtocol.EventRequest.SET) {
                bodies += ByteArray(body.remaining).also { for (i in it.indices) it[i] = body.byte().toByte() }
                MonoWriter().int(12).toByteArray()
            } else unity(set, command, body)
        })
        val socket = MonoConnection.connect("127.0.0.1", agent.server.localPort)
        val connection = MonoConnection(socket.getInputStream(), socket.getOutputStream(), Events()).apply { start("test") }
        connection.negotiate().get(5, TimeUnit.SECONDS)
        assertEquals(12, connection.request(MonoProtocol.EventKind.BREAKPOINT, MonoProtocol.SuspendPolicy.ALL, MonoModifier.LocationOnly(3, 1)).get(5, TimeUnit.SECONDS))
        // what the probe sent and Unity took: kind, policy, one modifier of kind 7, method, IL offset
        assertEquals(MonoWriter().byte(10).byte(2).byte(1).byte(7).id(3).long(1).toByteArray().toList(), bodies.poll(5, TimeUnit.SECONDS)!!.toList())
        connection.close()
        socket.close()
    }

    fun testUnityIsReadyToAttachAfterTheSwitch() {
        fun link(mode: String, compiling: Boolean, compiledAt: Long, status: UnityEditorLink.Status = UnityEditorLink.Status.CONNECTED) = UnityEditorLink.Link(
            java.io.File("."), status, UnityEditorLink.EditorState("1.4.0", "6000.6.0f1", 1, false, false, compiling, false, 0, compiledAt, 0, null, mode), 1)
        assertFalse("still Release", UnityDebugger.ready(link("Release", false, 2_000), 1_000))
        assertFalse("compiling", UnityDebugger.ready(link("Debug", true, 0), 1_000))
        assertFalse("the compilation of before the switch", UnityDebugger.ready(link("Debug", false, 500), 1_000))
        assertFalse("reloading the domain", UnityDebugger.ready(link("Debug", false, 2_000, UnityEditorLink.Status.BUSY), 1_000))
        assertTrue(UnityDebugger.ready(link("Debug", false, 2_000), 1_000))
    }

    fun testThePortOfTheEditorsAgent() {
        assertEquals("seen on Unity 6000.6", 56276, UnityEditorLink.debuggerPort(28276))
        assertEquals(56000, UnityEditorLink.debuggerPort(7000))
    }
}
