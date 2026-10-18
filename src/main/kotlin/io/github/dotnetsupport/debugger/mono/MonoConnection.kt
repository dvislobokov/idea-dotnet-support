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
        const val GET_TYPES_FOR_SOURCE_FILE = 11
    }

    object Thread {
        const val GET_FRAME_INFO = 1
        const val GET_NAME = 2
        const val GET_STATE = 3
    }

    object Type {
        const val GET_INFO = 1
        const val GET_METHODS = 2
        const val GET_SOURCE_FILES_2 = 13
    }

    object Method {
        const val GET_NAME = 1
        const val GET_DECLARING_TYPE = 2
        const val GET_DEBUG_INFO = 3
        const val GET_PARAM_INFO = 4
        const val GET_LOCALS_INFO = 5
    }

    object TypeValues {
        const val GET_FIELDS = 3
        const val GET_VALUES = 4
    }

    object StackFrame {
        const val GET_VALUES = 1
        const val GET_THIS = 2
    }

    object ObjectRef {
        const val GET_TYPE = 1
        const val GET_VALUES = 2
    }

    object StringRef {
        const val GET_VALUE = 1
    }

    object ArrayRef {
        const val GET_LENGTH = 1
        const val GET_VALUES = 2
    }

    /** The element types of values (`MonoTypeEnum`), and the three ids of the protocol for what is no type. */
    object ElementType {
        const val VOID = 0x01
        const val BOOLEAN = 0x02
        const val CHAR = 0x03
        const val I1 = 0x04
        const val U1 = 0x05
        const val I2 = 0x06
        const val U2 = 0x07
        const val I4 = 0x08
        const val U4 = 0x09
        const val I8 = 0x0a
        const val U8 = 0x0b
        const val R4 = 0x0c
        const val R8 = 0x0d
        const val STRING = 0x0e
        const val PTR = 0x0f
        const val FNPTR = 0x1b
        const val VALUETYPE = 0x11
        const val CLASS = 0x12
        const val ARRAY = 0x14
        const val I = 0x18
        const val U = 0x19
        const val OBJECT = 0x1c
        const val SZARRAY = 0x1d
        const val NULL = 0xf0
        const val TYPE = 0xf1
        const val PARENT_VTYPE = 0xf2
    }

    /** `FieldAttributes`: what a field of `TYPE.GET_FIELDS` is. */
    const val FIELD_STATIC = 0x10
    const val FIELD_LITERAL = 0x40

    object EventRequest {
        const val SET = 1
        const val CLEAR = 2
    }

    object StepSize {
        const val MIN = 0
        const val LINE = 1
    }

    object StepDepth {
        const val INTO = 0
        const val OVER = 1
        const val OUT = 2
    }

    /** What a step does not stop in: static constructors, `[DebuggerHidden]`, `[DebuggerStepThrough]` (as Rider steps). */
    object StepFilter {
        const val STATIC_CTOR = 1
        const val DEBUGGER_HIDDEN = 2
        const val DEBUGGER_STEP_THROUGH = 4
        const val DEBUGGER_NON_USER_CODE = 8
    }

    /** The line of a sequence point the compiler hides (`#line hidden`): no line of the source. */
    const val HIDDEN_LINE = 0xfeefee

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

/** A frame of a suspended thread: [ilOffset] in [method]; [flags] 1 — a frame of a debugger invoke, 2 — a native transition. */
data class MonoFrame(val id: Int, val method: Int, val ilOffset: Int, val flags: Int)

/** A sequence point: 1-based [line] and [column] of the source at [ilOffset]; [file] null when the runtime gave none. */
data class MonoSequencePoint(val ilOffset: Int, val line: Int, val file: String?, val column: Int, val endLine: Int, val endColumn: Int)

/** The line table of a method, as the runtime read it from the symbols of its assembly; no points — no symbols. */
data class MonoDebugInfo(val maxIlOffset: Int, val points: List<MonoSequencePoint>) {
    val lines: List<MonoSequencePoint> get() = points.filter { it.line != MonoProtocol.HIDDEN_LINE && it.line > 0 }

    /** The statement the IL offset of a frame is in: the last point at or before it. */
    fun at(ilOffset: Int): MonoSequencePoint? = lines.filter { it.ilOffset <= ilOffset }.maxByOrNull { it.ilOffset }
}

/** A filter of an event request (`EVENT_REQUEST.SET`), encoded as the agent reads it. */
sealed class MonoModifier(val kind: Int) {
    abstract fun write(writer: MonoWriter, version: MonoVersion?)

    /** Stop after [count] hits (and then the request is done). */
    class Count(private val count: Int) : MonoModifier(1) {
        override fun write(writer: MonoWriter, version: MonoVersion?) { writer.int(count) }
    }

    class ThreadOnly(private val thread: Int) : MonoModifier(3) {
        override fun write(writer: MonoWriter, version: MonoVersion?) { writer.id(thread) }
    }

    /** The place of a breakpoint. */
    class LocationOnly(private val method: Int, private val ilOffset: Long) : MonoModifier(7) {
        override fun write(writer: MonoWriter, version: MonoVersion?) { writer.id(method).long(ilOffset) }
    }

    /** A step of [thread] by [size] ([MonoProtocol.StepSize]) and [depth] ([MonoProtocol.StepDepth]); the filter from protocol 2.16. */
    class Step(private val thread: Int, private val size: Int, private val depth: Int, private val filter: Int) : MonoModifier(10) {
        override fun write(writer: MonoWriter, version: MonoVersion?) {
            writer.id(thread).int(size).int(depth)
            if (version == null || version.atLeast(2, 16)) writer.int(filter)
        }
    }

    /** Types of these source files (the agent matches a full path or a file name, any case — seen on Unity 6000.6). */
    class SourceFiles(private val files: Collection<String>) : MonoModifier(12) {
        override fun write(writer: MonoWriter, version: MonoVersion?) { writer.int(files.size); files.forEach { writer.string(it) } }
    }
}

data class MonoTypeName(val namespace: String, val name: String, val fullName: String)

/** What `TYPE.GET_INFO` says of a type (the reply goes on with nested types and more; that is not read). [baseType] 0: none. */
data class MonoTypeInfo(
    val name: MonoTypeName, val baseType: Int, val elementType: Int, val rank: Int, val flags: Int,
) {
    val isValueType: Boolean get() = flags and 8 != 0
    val isEnum: Boolean get() = flags and 16 != 0
    val isPrimitive: Boolean get() = flags and 4 != 0
}

data class MonoField(val id: Int, val name: String, val type: Int, val attributes: Int) {
    val isStatic: Boolean get() = attributes and MonoProtocol.FIELD_STATIC != 0
    val isLiteral: Boolean get() = attributes and MonoProtocol.FIELD_LITERAL != 0
}

data class MonoParamInfo(val names: List<String>, val types: List<Int>)

/** The locals of a method: [ranges] — the IL offsets each one lives in (from, to). */
data class MonoLocalsInfo(val names: List<String>, val types: List<Int>, val ranges: List<Pair<Int, Int>>) {
    fun liveAt(ilOffset: Int): List<Int> = names.indices.filter { ilOffset >= ranges[it].first && ilOffset < ranges[it].second }
}

/**
 * A value as the agent writes it (`buffer_add_value_full` of `debugger-agent.c`; read byte for byte against Unity 6000.6): the element
 * type, then an int (32-bit and smaller kinds, boolean and char among them), a long (64-bit, `IntPtr`), a long and the id of the
 * pointed type for a pointer (2.46: `UnityEngine.Object.m_CachedPtr` is an `IntPtr` holding one), an object id (strings, classes, arrays,
 * boxed objects), the fields of a struct, or nothing for null.
 */
sealed class MonoValue {
    data class Primitive(val type: Int, val value: Any) : MonoValue()
    /** A string, an object or an array: [type] its element type ([MonoProtocol.ElementType.STRING], `CLASS`, `SZARRAY`…), [id] the object. */
    data class Ref(val type: Int, val id: Int) : MonoValue()
    data class Struct(val isEnum: Boolean, val type: Int, val fields: List<MonoValue>) : MonoValue()
    /** A pointer; [pointee] the type it points to (protocol 2.46 writes it), 0 when not written. */
    data class Pointer(val type: Int, val address: Long, val pointee: Int = 0) : MonoValue()
    data class TypeRef(val id: Int) : MonoValue()
    data class ParentStruct(val index: Int) : MonoValue()
    data object Null : MonoValue()
    data object Void : MonoValue()
}

object MonoValues {
    fun read(reader: MonoReader, version: MonoVersion?): MonoValue {
        val e = MonoProtocol.ElementType
        return when (val type = reader.byte()) {
            e.BOOLEAN -> MonoValue.Primitive(type, reader.int() != 0)
            e.CHAR -> MonoValue.Primitive(type, reader.int().toChar())
            e.I1 -> MonoValue.Primitive(type, reader.int().toByte())
            e.U1 -> MonoValue.Primitive(type, reader.int() and 0xFF)
            e.I2 -> MonoValue.Primitive(type, reader.int().toShort())
            e.U2 -> MonoValue.Primitive(type, reader.int() and 0xFFFF)
            e.I4 -> MonoValue.Primitive(type, reader.int())
            e.U4 -> MonoValue.Primitive(type, reader.int().toLong() and 0xFFFFFFFFL)
            e.I8 -> MonoValue.Primitive(type, reader.long())
            e.U8 -> MonoValue.Primitive(type, java.lang.Long.toUnsignedString(reader.long()))
            e.R4 -> MonoValue.Primitive(type, java.lang.Float.intBitsToFloat(reader.int()))
            e.R8 -> MonoValue.Primitive(type, java.lang.Double.longBitsToDouble(reader.long()))
            e.I, e.U -> MonoValue.Pointer(type, reader.long())
            e.PTR, e.FNPTR -> MonoValue.Pointer(type, reader.long(), if (version == null || version.atLeast(2, 46)) reader.id() else 0)
            e.STRING, e.CLASS, e.ARRAY, e.OBJECT, e.SZARRAY -> MonoValue.Ref(type, reader.id())
            e.VALUETYPE -> {
                val isEnum = reader.byte() == 1
                val klass = reader.id()
                MonoValue.Struct(isEnum, klass, List(reader.int()) { read(reader, version) })
            }
            e.NULL -> MonoValue.Null
            e.TYPE -> MonoValue.TypeRef(reader.id())
            e.PARENT_VTYPE -> MonoValue.ParentStruct(reader.int())
            e.VOID -> MonoValue.Void
            else -> throw java.io.IOException("A value of element type 0x${type.toString(16)} this client does not know")
        }
    }

    /**
     * `STRING_REF.GET_VALUE`: from protocol 2.41 a byte says the text is UTF-16 (a string UTF-8 cannot hold: a lone surrogate) — then the
     * count of chars and the chars as the runtime keeps them, little-endian — else a string of the protocol (seen on Unity 6000.6: 0, then UTF-8).
     */
    fun readString(reader: MonoReader, version: MonoVersion?): String {
        if (version != null && !version.atLeast(2, 41)) return reader.string()
        if (reader.byte() != 1) return reader.string()
        val chars = CharArray(reader.int()) { (reader.byte() or (reader.byte() shl 8)).toChar() }
        return String(chars)
    }
}

object MonoDebugInfos {
    /**
     * The reply of `METHOD.GET_DEBUG_INFO` (`Method_GetDebugInfo` of `Connection.cs`): the source files of the method (2.13; with an MD5
     * hash from 2.14), then the points — IL offset, line, the index of the file (2.12), column (2.19), end line and column (2.32). Read
     * byte for byte against Unity 6000.6 (protocol 2.58).
     */
    fun read(reply: MonoReader, version: MonoVersion?): MonoDebugInfo {
        fun atLeast(minor: Int) = version == null || version.atLeast(2, minor)
        val maxIl = reply.int()
        val files = if (atLeast(13)) List(reply.int()) { reply.string().also { if (atLeast(14)) repeat(16) { reply.byte() } } } else listOf(reply.string())
        val points = List(reply.int()) {
            val il = reply.int()
            val line = reply.int()
            val file = if (atLeast(12)) reply.int().let { files.getOrNull(it) } else files.firstOrNull()
            val column = if (atLeast(19)) reply.int() else 0
            val (endLine, endColumn) = if (atLeast(32)) reply.int() to reply.int() else -1 to -1
            MonoSequencePoint(il, line, file, column, endLine, endColumn)
        }
        return MonoDebugInfo(maxIl, points)
    }
}

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

    /**
     * The frames of a suspended [thread], the top first — all of them: the agent knows no other range (start 0, length 1 is
     * NOT_IMPLEMENTED, seen on Unity 6000.6).
     */
    fun frames(thread: Int): CompletableFuture<List<MonoFrame>> =
        command(MonoProtocol.Sets.THREAD, MonoProtocol.Thread.GET_FRAME_INFO, MonoWriter().id(thread).int(0).int(-1).toByteArray())
            .thenApply { reply -> List(reply.int()) { MonoFrame(reply.id(), reply.id(), reply.int(), reply.byte()) } }

    /** The loaded types with code in [path] (all the domains). */
    fun typesForSourceFile(path: String, ignoreCase: Boolean = true): CompletableFuture<List<Int>> =
        command(MonoProtocol.Sets.VM, MonoProtocol.Vm.GET_TYPES_FOR_SOURCE_FILE, MonoWriter().string(path).byte(if (ignoreCase) 1 else 0).toByteArray())
            .thenApply { reply -> List(reply.int()) { reply.id() } }

    /** Namespace, name and full name of a type (the reply goes on with more; what is not read is dropped). */
    fun typeName(type: Int): CompletableFuture<MonoTypeName> =
        command(MonoProtocol.Sets.TYPE, MonoProtocol.Type.GET_INFO, MonoWriter().id(type).toByteArray())
            .thenApply { reply -> MonoTypeName(reply.string(), reply.string(), reply.string()) }

    /** The full paths of the source files of a type, as its assembly was compiled. */
    fun typeSourceFiles(type: Int): CompletableFuture<List<String>> =
        command(MonoProtocol.Sets.TYPE, MonoProtocol.Type.GET_SOURCE_FILES_2, MonoWriter().id(type).toByteArray())
            .thenApply { reply -> List(reply.int()) { reply.string() } }

    fun methods(type: Int): CompletableFuture<List<Int>> =
        command(MonoProtocol.Sets.TYPE, MonoProtocol.Type.GET_METHODS, MonoWriter().id(type).toByteArray()).thenApply { reply -> List(reply.int()) { reply.id() } }

    fun methodName(method: Int): CompletableFuture<String> =
        command(MonoProtocol.Sets.METHOD, MonoProtocol.Method.GET_NAME, MonoWriter().id(method).toByteArray()).thenApply { it.string() }

    fun declaringType(method: Int): CompletableFuture<Int> =
        command(MonoProtocol.Sets.METHOD, MonoProtocol.Method.GET_DECLARING_TYPE, MonoWriter().id(method).toByteArray()).thenApply { it.id() }

    fun debugInfo(method: Int): CompletableFuture<MonoDebugInfo> =
        command(MonoProtocol.Sets.METHOD, MonoProtocol.Method.GET_DEBUG_INFO, MonoWriter().id(method).toByteArray()).thenApply { MonoDebugInfos.read(it, version) }

    fun typeInfo(type: Int): CompletableFuture<MonoTypeInfo> =
        command(MonoProtocol.Sets.TYPE, MonoProtocol.Type.GET_INFO, MonoWriter().id(type).toByteArray()).thenApply { reply ->
            val name = MonoTypeName(reply.string(), reply.string(), reply.string())
            reply.id(); reply.id() // assembly, module
            val base = reply.id()
            val element = reply.id()
            reply.int() // token
            val rank = reply.byte()
            reply.int() // attributes
            MonoTypeInfo(name, base, element, rank, reply.byte())
        }

    /** The fields a type declares (not the inherited ones), static and literal among them. */
    fun typeFields(type: Int): CompletableFuture<List<MonoField>> =
        command(MonoProtocol.Sets.TYPE, MonoProtocol.TypeValues.GET_FIELDS, MonoWriter().id(type).toByteArray())
            .thenApply { reply -> List(reply.int()) { MonoField(reply.id(), reply.string(), reply.id(), reply.int()) } }

    /** The values of static fields of [type] (a literal one gives its constant). */
    fun staticValues(type: Int, fields: List<Int>): CompletableFuture<List<MonoValue>> =
        command(MonoProtocol.Sets.TYPE, MonoProtocol.TypeValues.GET_VALUES, MonoWriter().id(type).int(fields.size).apply { fields.forEach { id(it) } }.toByteArray())
            .thenApply { reply -> List(fields.size) { MonoValues.read(reply, version) } }

    fun paramInfo(method: Int): CompletableFuture<MonoParamInfo> =
        command(MonoProtocol.Sets.METHOD, MonoProtocol.Method.GET_PARAM_INFO, MonoWriter().id(method).toByteArray()).thenApply { reply ->
            reply.int() // calling convention
            val count = reply.int()
            reply.int() // generic parameters
            reply.id() // return type
            val types = List(count) { reply.id() }
            MonoParamInfo(List(count) { reply.string() }, types)
        }

    /** `METHOD.GET_LOCALS_INFO`: the scopes (2.43, deltas), then the types, the names and the live ranges of the locals. */
    fun localsInfo(method: Int): CompletableFuture<MonoLocalsInfo> =
        command(MonoProtocol.Sets.METHOD, MonoProtocol.Method.GET_LOCALS_INFO, MonoWriter().id(method).toByteArray()).thenApply { reply ->
            if (version?.atLeast(2, 43) != false) repeat(reply.int()) { reply.int(); reply.int() }
            val count = reply.int()
            val types = List(count) { reply.id() }
            val names = List(count) { reply.string() }
            MonoLocalsInfo(names, types, List(count) { reply.int() to reply.int() })
        }

    /** Values of a frame of a suspended thread: a local by its index, a parameter by `-index - 1`. */
    fun frameValues(thread: Int, frame: Int, positions: List<Int>): CompletableFuture<List<MonoValue>> =
        command(MonoProtocol.Sets.STACK_FRAME, MonoProtocol.StackFrame.GET_VALUES,
            MonoWriter().id(thread).id(frame).int(positions.size).apply { positions.forEach { int(it) } }.toByteArray())
            .thenApply { reply -> List(positions.size) { MonoValues.read(reply, version) } }

    /** `this` of a frame; [MonoValue.Null] in a static method. */
    fun frameThis(thread: Int, frame: Int): CompletableFuture<MonoValue> =
        command(MonoProtocol.Sets.STACK_FRAME, MonoProtocol.StackFrame.GET_THIS, MonoWriter().id(thread).id(frame).toByteArray()).thenApply { MonoValues.read(it, version) }

    fun objectType(obj: Int): CompletableFuture<Int> =
        command(MonoProtocol.Sets.OBJECT_REF, MonoProtocol.ObjectRef.GET_TYPE, MonoWriter().id(obj).toByteArray()).thenApply { it.id() }

    fun objectValues(obj: Int, fields: List<Int>): CompletableFuture<List<MonoValue>> =
        command(MonoProtocol.Sets.OBJECT_REF, MonoProtocol.ObjectRef.GET_VALUES, MonoWriter().id(obj).int(fields.size).apply { fields.forEach { id(it) } }.toByteArray())
            .thenApply { reply -> List(fields.size) { MonoValues.read(reply, version) } }

    fun stringValue(obj: Int): CompletableFuture<String> =
        command(MonoProtocol.Sets.STRING_REF, MonoProtocol.StringRef.GET_VALUE, MonoWriter().id(obj).toByteArray()).thenApply { MonoValues.readString(it, version) }

    /** The lengths of the dimensions of an array (with their lower bounds). */
    fun arrayLengths(obj: Int): CompletableFuture<List<Int>> =
        command(MonoProtocol.Sets.ARRAY_REF, MonoProtocol.ArrayRef.GET_LENGTH, MonoWriter().id(obj).toByteArray())
            .thenApply { reply -> List(reply.int()) { reply.int().also { reply.int() } } }

    /** [count] elements from [index], the array taken as flat. */
    fun arrayValues(obj: Int, index: Int, count: Int): CompletableFuture<List<MonoValue>> =
        command(MonoProtocol.Sets.ARRAY_REF, MonoProtocol.ArrayRef.GET_VALUES, MonoWriter().id(obj).int(index).int(count).toByteArray())
            .thenApply { reply -> List(count) { MonoValues.read(reply, version) } }

    /** An event request; the future ends with its id, which the events it causes carry. */
    fun request(kind: Int, suspendPolicy: Int, vararg modifiers: MonoModifier): CompletableFuture<Int> {
        val body = MonoWriter().byte(kind).byte(suspendPolicy).byte(modifiers.size)
        for (modifier in modifiers) modifier.write(body.byte(modifier.kind), version)
        return command(MonoProtocol.Sets.EVENT_REQUEST, MonoProtocol.EventRequest.SET, body.toByteArray()).thenApply { it.int() }
    }

    fun clearRequest(kind: Int, id: Int): CompletableFuture<MonoReader> =
        command(MonoProtocol.Sets.EVENT_REQUEST, MonoProtocol.EventRequest.CLEAR, MonoWriter().byte(kind).int(id).toByteArray())

    fun suspend(): CompletableFuture<MonoReader> = command(MonoProtocol.Sets.VM, MonoProtocol.Vm.SUSPEND)
    fun resume(): CompletableFuture<MonoReader> = command(MonoProtocol.Sets.VM, MonoProtocol.Vm.RESUME)

    /** Detaches: the agent drops the requests of this client and resumes what it stopped; the runtime goes on. */
    fun dispose(): CompletableFuture<MonoReader> = command(MonoProtocol.Sets.VM, MonoProtocol.Vm.DISPOSE)

    fun close() = fail("The debugger connection was closed")

    val isClosed: Boolean get() = closed.get()

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
