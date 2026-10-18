package io.github.dotnetsupport.debugger.mono

import com.intellij.openapi.diagnostic.logger
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.time.LocalTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val LOG = logger<MonoConnection>()

/** The agent answered a command with an error code ([MonoProtocol.errorName]: `NOT_SUSPENDED`, `ABSENT_INFORMATION`…). */
class MonoException(val command: String, val code: Int) : Exception("$command: ${MonoProtocol.errorName(code)}")

/** The connection is gone (the runtime exited, the editor closed, [MonoConnection.close]): every command still waiting ends with this. */
class MonoClosedException(message: String) : IOException(message)

/**
 * The wire protocol of the Mono soft debugger (the debugger agent of the runtime, `mono/mini/debugger-agent.c`; the client of reference is
 * `Mono.Debugger.Soft/Connection.cs` of `mono/debugger-libs`), which the Unity editor and players speak over TCP (UNITY_PLAN.md,
 * "Отладчик"). Big-endian packets of an 11-byte header: length, id, flags, then the command set and the command of a command, or the
 * error code of a reply (flag 0x80). Ids of objects, threads, types… are 4 bytes; strings are a length and UTF-8.
 */
object MonoProtocol {
    const val HANDSHAKE = "DWP-Handshake"
    const val HEADER = 11
    const val REPLY_FLAG = 0x80

    /** The version this client speaks: the agent answers in the lower of its own and this one (2.58 is Unity 6000.6's Mono 6.13). */
    const val MAJOR = 2
    const val MINOR = 58

    object Sets {
        const val VM = 1
        const val OBJECT_REF = 9
        const val STRING_REF = 10
        const val THREAD = 11
        const val ARRAY_REF = 13
        const val EVENT_REQUEST = 15
        const val STACK_FRAME = 16
        const val APPDOMAIN = 20
        const val ASSEMBLY = 21
        const val METHOD = 22
        const val TYPE = 23
        const val MODULE = 24
        const val FIELD = 25
        const val EVENT = 64
    }

    object Vm {
        const val VERSION = 1
        const val ALL_THREADS = 2
        const val SUSPEND = 3
        const val RESUME = 4
        const val EXIT = 5
        const val DISPOSE = 6
        const val SET_PROTOCOL_VERSION = 8
    }

    object Thread {
        const val GET_FRAME_INFO = 1
        const val GET_NAME = 2
        const val GET_STATE = 3
    }

    /** The command of the agent carrying events: a composite of one or more. */
    const val EVENT_COMPOSITE = 100

    object EventKind {
        const val VM_START = 0
        const val VM_DEATH = 1
        const val THREAD_START = 2
        const val THREAD_DEATH = 3
        const val APPDOMAIN_CREATE = 4
        const val APPDOMAIN_UNLOAD = 5
        const val METHOD_ENTRY = 6
        const val METHOD_EXIT = 7
        const val ASSEMBLY_LOAD = 8
        const val ASSEMBLY_UNLOAD = 9
        const val BREAKPOINT = 10
        const val STEP = 11
        const val TYPE_LOAD = 12
        const val EXCEPTION = 13
        const val KEEPALIVE = 14
        const val USER_BREAK = 15
        const val USER_LOG = 16
        const val CRASH = 17
    }

    /** How much of the runtime an event stopped: [NONE] — nothing (the editor goes on), [ALL] — every managed thread until a resume. */
    object SuspendPolicy {
        const val NONE = 0
        const val EVENT_THREAD = 1
        const val ALL = 2
    }

    private val ERRORS = mapOf(
        0 to "NONE", 20 to "INVALID_OBJECT", 25 to "INVALID_FIELDID", 30 to "INVALID_FRAMEID", 100 to "NOT_IMPLEMENTED", 101 to "NOT_SUSPENDED",
        102 to "INVALID_ARGUMENT", 103 to "UNLOADED", 104 to "NO_INVOCATION", 105 to "ABSENT_INFORMATION", 106 to "NO_SEQ_POINT_AT_IL_OFFSET",
        107 to "INVOKE_ABORTED", 200 to "LOADER_ERROR",
    )

    fun errorName(code: Int): String = ERRORS[code] ?: "error $code"

    private val SET_NAMES = mapOf(
        Sets.VM to "VM", Sets.OBJECT_REF to "OBJECT_REF", Sets.STRING_REF to "STRING_REF", Sets.THREAD to "THREAD", Sets.ARRAY_REF to "ARRAY_REF",
        Sets.EVENT_REQUEST to "EVENT_REQUEST", Sets.STACK_FRAME to "STACK_FRAME", Sets.APPDOMAIN to "APPDOMAIN", Sets.ASSEMBLY to "ASSEMBLY",
        Sets.METHOD to "METHOD", Sets.TYPE to "TYPE", Sets.MODULE to "MODULE", Sets.FIELD to "FIELD", Sets.EVENT to "EVENT",
    )

    fun commandName(set: Int, command: Int): String = "${SET_NAMES[set] ?: "set $set"}.$command"
}

/** The body of a command, in the encoding of the protocol. */
class MonoWriter {
    private val bytes = ByteArrayOutputStream()

    fun byte(value: Int) = apply { bytes.write(value) }
    fun int(value: Int) = apply { repeat(4) { bytes.write(value ushr (24 - 8 * it)) } }
    fun long(value: Long) = apply { int((value ushr 32).toInt()); int(value.toInt()) }
    fun id(value: Int) = int(value)
    fun string(value: String) = apply { val utf8 = value.toByteArray(Charsets.UTF_8); int(utf8.size); bytes.write(utf8) }
    fun toByteArray(): ByteArray = bytes.toByteArray()
}

/** The body of a reply or an event, read in order. */
class MonoReader(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes)

    val remaining: Int get() = buffer.remaining()
    fun byte(): Int = buffer.get().toInt() and 0xFF
    fun short(): Int = buffer.short.toInt()
    fun int(): Int = buffer.int
    fun long(): Long = buffer.long
    fun id(): Int = buffer.int
    fun string(): String = ByteArray(buffer.int).also(buffer::get).toString(Charsets.UTF_8)
}

data class MonoVersion(val runtime: String, val major: Int, val minor: Int) {
    fun atLeast(major: Int, minor: Int): Boolean = this.major > major || this.major == major && this.minor >= minor
}

/**
 * One event of a composite: [thread] it happened on; [subject] — the domain of `VM_START`, the assembly, type, method, exception object or
 * domain the others name; [location] — the IL offset of a breakpoint or a step; [code] — the exit code of `VM_DEATH`, the level of `USER_LOG`.
 */
data class MonoEvent(
    val kind: Int, val requestId: Int, val thread: Int, val subject: Int? = null, val location: Long? = null, val code: Int? = null,
    val message: String? = null,
)

data class MonoEventSet(val suspendPolicy: Int, val events: List<MonoEvent>)

object MonoEvents {
    /**
     * A composite as the agent writes it (`process_event` of `debugger-agent.c`). The events have no length of their own: a kind this does
     * not know ends the set there (the events before it are kept, and the log says so).
     */
    fun decode(reader: MonoReader, version: MonoVersion?): MonoEventSet {
        val policy = reader.byte()
        val count = reader.int()
        val events = ArrayList<MonoEvent>(count)
        for (i in 0 until count) {
            val kind = reader.byte()
            val request = reader.int()
            val thread = reader.id()
            val k = MonoProtocol.EventKind
            events += when (kind) {
                k.VM_START -> MonoEvent(kind, request, thread, subject = reader.id())
                k.VM_DEATH -> MonoEvent(kind, request, thread, code = if (version == null || version.atLeast(2, 27)) reader.int() else null)
                k.THREAD_START, k.THREAD_DEATH, k.USER_BREAK, k.KEEPALIVE -> MonoEvent(kind, request, thread)
                k.APPDOMAIN_CREATE, k.APPDOMAIN_UNLOAD, k.METHOD_ENTRY, k.METHOD_EXIT, k.ASSEMBLY_LOAD, k.ASSEMBLY_UNLOAD, k.TYPE_LOAD, k.EXCEPTION ->
                    MonoEvent(kind, request, thread, subject = reader.id())
                k.BREAKPOINT, k.STEP -> MonoEvent(kind, request, thread, subject = reader.id(), location = reader.long())
                k.USER_LOG -> MonoEvent(kind, request, thread, code = reader.int(), message = reader.string() + ": " + reader.string())
                k.CRASH -> MonoEvent(kind, request, thread, location = reader.long(), message = reader.string())
                else -> {
                    LOG.warn("Mono debugger: an event of unknown kind $kind in a composite of $count; the rest of it is dropped")
                    break
                }
            }
        }
        return MonoEventSet(policy, events)
    }
}

/**
 * The plugin's own client of the Mono soft debugger over the streams of a connected socket (after [handshake]): commands matched to their
 * replies by id, the events of the agent to [listener] on the reader thread. In the plugin rather than an adapter of its own: no binary to
 * ship, no `dotnet` needed to debug a game.
 */
class MonoConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    /** Every packet both ways, for the debugger log; null when off. */
    private val trace: Writer? = null,
) {
    interface Listener {
        fun events(set: MonoEventSet)

        /** The input has ended or broke: the runtime is gone. */
        fun closed() {}
    }

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pair<String, CompletableFuture<MonoReader>>>()
    private val closed = AtomicBoolean()
    private val writeLock = Any()

    /** What [version] answered: the encoding of some replies and events depends on it. */
    @Volatile var version: MonoVersion? = null
        private set

    fun start(name: String = "mono") {
        Thread({ readLoop() }, "Mono debugger reader: $name").apply { isDaemon = true }.start()
    }

    /** Sends a command; the future ends with the body of the reply, with [MonoException] for an error code, [MonoClosedException] when gone. */
    fun command(set: Int, command: Int, body: ByteArray = ByteArray(0)): CompletableFuture<MonoReader> {
        val name = MonoProtocol.commandName(set, command)
        val future = CompletableFuture<MonoReader>()
        if (closed.get()) return future.apply { completeExceptionally(MonoClosedException("The debuggee is gone ($name)")) }
        val id = ids.getAndIncrement()
        pending[id] = name to future
        val packet = ByteBuffer.allocate(MonoProtocol.HEADER + body.size)
            .putInt(MonoProtocol.HEADER + body.size).putInt(id).put(0).put(set.toByte()).put(command.toByte()).put(body).array()
        try {
            synchronized(writeLock) {
                output.write(packet)
                output.flush()
            }
            trace("-> #$id $name ${body.size} bytes")
        } catch (e: IOException) {
            pending.remove(id)
            future.completeExceptionally(MonoClosedException("The debuggee is gone ($name): ${e.message}"))
        }
        return future
    }

    /** `VM.VERSION`, then `SET_PROTOCOL_VERSION` with the lower of the agent's and [MonoProtocol.MAJOR].[MonoProtocol.MINOR]. */
    fun negotiate(): CompletableFuture<MonoVersion> =
        command(MonoProtocol.Sets.VM, MonoProtocol.Vm.VERSION).thenCompose { reply ->
            val agent = MonoVersion(reply.string(), reply.int(), reply.int())
            val ours = if (agent.atLeast(MonoProtocol.MAJOR, MonoProtocol.MINOR)) agent.copy(major = MonoProtocol.MAJOR, minor = MonoProtocol.MINOR) else agent
            version = ours
            command(MonoProtocol.Sets.VM, MonoProtocol.Vm.SET_PROTOCOL_VERSION, MonoWriter().int(ours.major).int(ours.minor).toByteArray())
                .thenApply { agent }
        }

    fun allThreads(): CompletableFuture<List<Int>> =
        command(MonoProtocol.Sets.VM, MonoProtocol.Vm.ALL_THREADS).thenApply { reply -> List(reply.int()) { reply.id() } }

    fun threadName(thread: Int): CompletableFuture<String> =
        command(MonoProtocol.Sets.THREAD, MonoProtocol.Thread.GET_NAME, MonoWriter().id(thread).toByteArray()).thenApply { it.string() }

    fun suspend(): CompletableFuture<MonoReader> = command(MonoProtocol.Sets.VM, MonoProtocol.Vm.SUSPEND)
    fun resume(): CompletableFuture<MonoReader> = command(MonoProtocol.Sets.VM, MonoProtocol.Vm.RESUME)

    /** Detaches: the agent drops the requests of this client and resumes what it stopped; the runtime goes on. */
    fun dispose(): CompletableFuture<MonoReader> = command(MonoProtocol.Sets.VM, MonoProtocol.Vm.DISPOSE)

    fun close() = fail("The debugger connection was closed")

    private fun readLoop() {
        val data = DataInputStream(input)
        try {
            while (!closed.get()) {
                val length = data.readInt()
                val id = data.readInt()
                val flags = data.readUnsignedByte()
                if (length < MonoProtocol.HEADER) throw IOException("A packet of $length bytes")
                if (flags and MonoProtocol.REPLY_FLAG != 0) {
                    val error = data.readShort().toInt()
                    val body = ByteArray(length - MonoProtocol.HEADER).also(data::readFully)
                    val (name, future) = pending.remove(id) ?: continue
                    trace("<- #$id $name " + if (error == 0) "${body.size} bytes" else MonoProtocol.errorName(error))
                    if (error == 0) future.complete(MonoReader(body)) else future.completeExceptionally(MonoException(name, error))
                } else {
                    val set = data.readUnsignedByte()
                    val command = data.readUnsignedByte()
                    val body = ByteArray(length - MonoProtocol.HEADER).also(data::readFully)
                    trace("<- ${MonoProtocol.commandName(set, command)} ${body.size} bytes")
                    if (set == MonoProtocol.Sets.EVENT && command == MonoProtocol.EVENT_COMPOSITE) {
                        val events = runCatching { MonoEvents.decode(MonoReader(body), version) }
                            .onFailure { LOG.warn("Mono debugger: a composite of events could not be read", it) }.getOrNull()
                        if (events != null) runCatching { listener.events(events) }.onFailure { LOG.error(it) }
                    } else {
                        LOG.warn("Mono debugger: a command of the agent this client does not know: ${MonoProtocol.commandName(set, command)}")
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed.get() && e !is EOFException) LOG.info("Mono debugger: the connection broke: ${e.message}")
        } finally {
            fail("The debuggee is gone")
        }
    }

    private fun fail(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { input.close() }
        for ((name, future) in pending.values) future.completeExceptionally(MonoClosedException("$reason ($name)"))
        pending.clear()
        runCatching { listener.closed() }.onFailure { LOG.error(it) }
    }

    private fun trace(line: String) {
        val writer = trace ?: return
        runCatching { synchronized(writer) { writer.write("${LocalTime.now()} $line\n"); writer.flush() } }
    }

    companion object {
        /** A socket to the agent at [port] of [host], past the handshake; the caller starts a [MonoConnection] on its streams. */
        fun connect(host: String, port: Int, timeoutMs: Int = 5_000): Socket {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                handshake(socket.getInputStream(), socket.getOutputStream())
                socket.soTimeout = 0
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

        /** Both sides send `DWP-Handshake`; anything else is not a Mono debugger agent. */
        fun handshake(input: InputStream, output: OutputStream) {
            val hello = MonoProtocol.HANDSHAKE.toByteArray(Charsets.US_ASCII)
            output.write(hello)
            output.flush()
            val answer = ByteArray(hello.size)
            DataInputStream(input).readFully(answer)
            if (!answer.contentEquals(hello)) throw IOException("Not a Mono debugger agent: it answered ${answer.toString(Charsets.ISO_8859_1)}")
        }
    }
}
