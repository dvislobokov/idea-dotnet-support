package io.github.dotnetsupport.debugger.mono

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.debugger.DebugAdapterProcess
import io.github.dotnetsupport.debugger.DotNetDebuggerLogs
import io.github.dotnetsupport.debugger.DotNetEditorsProvider
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<MonoDebugProcess>()

/** What a frame shows: the method, its type, its line table (null without symbols). */
data class MonoMethodInfo(val name: String, val type: MonoTypeName, val debugInfo: MonoDebugInfo?)

/**
 * A debug session over the Mono soft debugger protocol ([MonoConnection]), attached to a running Mono runtime at [host]:[port] — the Unity
 * editor (UNITY_PLAN.md, "Отладчик"). Line breakpoints of `.cs` files, the threads and their frames, Pause, Resume, the steps, Run to
 * Cursor. Stop only detaches: the runtime is never ended from here. The variables come later.
 *
 * The events of the agent are handled one after another on [worker], never on the reader thread: handling them sends commands and waits.
 */
class MonoDebugProcess(session: XDebugSession, private val host: String, private val port: Int, private val title: String) :
    XDebugProcess(session), MonoConnection.Listener {
    private val worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("Mono debugger", 1)
    private val handler = MonoProcessHandler()
    private val editors = DotNetEditorsProvider()
    val breakpoints = MonoLineBreakpointHandler(this)
    private val methods = ConcurrentHashMap<Int, MonoMethodInfo>()
    private val shutdown = AtomicBoolean()
    private val stopped = AsyncPromise<Any>()
    private var socket: Socket? = null

    @Volatile lateinit var connection: MonoConnection
        private set

    /** Negotiated: the commands of the breakpoints may go. */
    @Volatile var connected = false
        private set

    /** The thread of `VM_START`: the main thread, the one Pause shows. */
    @Volatile private var mainThread: Int? = null
    @Volatile private var stepRequest: Int? = null
    @Volatile private var runToRequests: List<Int> = emptyList()

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editors
    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(breakpoints)
    override fun doGetProcessHandler(): ProcessHandler = handler
    override fun createConsole(): ExecutionConsole = super.createConsole().also { (it as? ConsoleView)?.attachToProcess(handler) }

    // the handler is started by the platform: a session without an execution environment does it itself
    override fun sessionInitialized() {
        worker.execute {
            try {
                val socket = MonoConnection.connect(host, port).also { this.socket = it }
                connection = MonoConnection(socket.getInputStream(), socket.getOutputStream(), this, DotNetDebuggerLogs.newProtocolTrace()).also { it.start(title) }
                val version = connection.negotiate().get(TIMEOUT_S, TimeUnit.SECONDS)
                PluginLog.info(DebugAdapterProcess.LOG_CATEGORY, "attached to $title at $host:$port: ${version.runtime}, protocol ${version.major}.${version.minor}")
                print("Attached to $title (${version.runtime}, protocol ${version.major}.${version.minor})\n", ProcessOutputTypes.SYSTEM)
                connected = true
                breakpoints.connected()
            } catch (e: Exception) {
                PluginLog.warn(DebugAdapterProcess.LOG_CATEGORY, "cannot attach to $title at $host:$port: ${errorText(e)}")
                print("Cannot attach to $title at $host:$port: ${errorText(e)}\n", ProcessOutputTypes.STDERR)
                end()
            }
        }
    }

    /** Work that sends commands and waits for them: one at a time, in order. */
    fun onWorker(task: () -> Unit) {
        if (shutdown.get()) return
        worker.execute {
            if (shutdown.get()) return@execute
            try {
                task()
            } catch (e: Exception) {
                if (!shutdown.get()) LOG.info("Mono debugger: ${errorText(e)}")
            }
        }
    }

    // --- events of the agent ---

    override fun events(set: MonoEventSet) = onWorker { handle(set) }

    override fun closed() {
        if (shutdown.get()) return
        print("The debuggee is gone\n", ProcessOutputTypes.SYSTEM)
        end()
    }

    private fun handle(set: MonoEventSet) {
        val k = MonoProtocol.EventKind
        var stop: MonoEvent? = null
        for (event in set.events) {
            when (event.kind) {
                k.VM_START -> mainThread = event.thread
                k.VM_DEATH -> { print("The runtime has exited\n", ProcessOutputTypes.SYSTEM); end(); return }
                k.TYPE_LOAD -> event.subject?.let(breakpoints::typeLoaded)
                k.BREAKPOINT, k.STEP, k.USER_BREAK -> if (stop == null) stop = event
            }
        }
        val first = stop
        if (first == null) {
            if (set.suspendPolicy != MonoProtocol.SuspendPolicy.NONE) resumeQuietly()
            return
        }
        val requests = set.events.filter { it.kind == k.BREAKPOINT }.map { it.requestId }
        val breakpoint = requests.firstNotNullOfOrNull(breakpoints::find)
        val ours = first.kind != k.BREAKPOINT || breakpoint != null || requests.any { it in runToRequests }
        clearStep()
        clearRunTo()
        // a request of a breakpoint just removed: the stop is nobody's
        if (!ours) return resumeQuietly()
        val context = context(first.thread, first.subject)
        if (breakpoint == null) session.positionReached(context)
        else if (!session.breakpointReached(breakpoint, null, context)) resumeQuietly()
    }

    /**
     * All the threads at a stop, the active one with its top frame resolved (the platform asks for it at once). The active thread is the
     * one whose top frame is in [method] of the event: the thread an event names may be the `Thread` object of another domain, without
     * frames of its own (seen on Unity 6000.6 in Play mode: the breakpoint event said thread 42, `Awake` was on the frames of thread 1,
     * the main thread). Without a method (Pause), the event's thread, else the first one with frames.
     */
    private fun context(eventThread: Int?, method: Int? = null): MonoSuspendContext {
        val threads = connection.allThreads().get(TIMEOUT_S, TimeUnit.SECONDS)
        val named = threads.map { id ->
            val name = runCatching { connection.threadName(id).get(TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull().orEmpty()
            id to name.ifBlank { if (id == mainThread) "Main Thread" else "" }
        }
        fun top(thread: Int): MonoFrame? = runCatching { connection.frames(thread).get(TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull()?.firstOrNull()
        var active = eventThread ?: threads.firstOrNull()
        var top = active?.let(::top)
        if (top == null || method != null && top.method != method) {
            val found = (listOfNotNull(mainThread) + threads).asSequence().distinct()
                .mapNotNull { thread -> top(thread)?.let { thread to it } }
                .firstOrNull { (_, frame) -> method == null || frame.method == method }
            if (found != null) {
                active = found.first
                top = found.second
            }
        }
        // the thread of an event may be missing from ALL_THREADS altogether (seen on Unity 6000.6: the event's thread had the frame of the
        // breakpoint, the list did not have it, the main thread of the list had the same frame): the listed thread with that top frame
        // stands for it, else it is shown as a thread of its own
        var listed = named
        val current = active
        val stepping = active
        if (current != null && threads.none { it == current }) {
            val same = top?.let { frame -> threads.firstOrNull { thread -> top(thread)?.let { it.method == frame.method && it.ilOffset == frame.ilOffset } == true } }
            if (same != null) active = same
            else listed = listOf(current to (if (current == mainThread) "Main Thread" else "")) + named
        }
        return MonoSuspendContext(this, listed, active, top?.let(::stackFrame), stepping)
    }

    fun stackFrame(frame: MonoFrame): MonoStackFrame = MonoStackFrame(frame, method(frame.method))

    /** The name, type and line table of a method, asked once (the ids of the agent are new for the methods of a reloaded domain). */
    fun method(id: Int): MonoMethodInfo = methods.getOrPut(id) {
        val name = connection.methodName(id).get(TIMEOUT_S, TimeUnit.SECONDS)
        val type = connection.declaringType(id).thenCompose(connection::typeName).get(TIMEOUT_S, TimeUnit.SECONDS)
        MonoMethodInfo(name, type, runCatching { connection.debugInfo(id).get(TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull())
    }

    fun debugInfo(method: Int): MonoDebugInfo? = method(method).debugInfo

    // --- commands of the IDE ---

    override fun resume(context: XSuspendContext?) = onWorker { resumeQuietly() }

    private fun resumeQuietly() {
        // NOT_SUSPENDED: the runtime goes on already (an event of policy NONE, a resume twice)
        runCatching { connection.resume().get(TIMEOUT_S, TimeUnit.SECONDS) }
    }

    override fun startPausing() = onWorker {
        connection.suspend().get(TIMEOUT_S, TimeUnit.SECONDS)
        session.positionReached(context(mainThread))
    }

    override fun startStepOver(context: XSuspendContext?) = step(MonoProtocol.StepDepth.OVER, context)
    override fun startStepInto(context: XSuspendContext?) = step(MonoProtocol.StepDepth.INTO, context)
    override fun startStepOut(context: XSuspendContext?) = step(MonoProtocol.StepDepth.OUT, context)

    /** A step request of the thread of the stop, then the runtime resumed: the `STEP` event is the next stop (another breakpoint may come first). */
    private fun step(depth: Int, context: XSuspendContext?) = onWorker {
        val thread = (context as? MonoSuspendContext)?.steppingThread ?: return@onWorker resumeQuietly()
        clearStep()
        val filter = MonoProtocol.StepFilter.STATIC_CTOR or MonoProtocol.StepFilter.DEBUGGER_HIDDEN or MonoProtocol.StepFilter.DEBUGGER_STEP_THROUGH
        stepRequest = connection.request(MonoProtocol.EventKind.STEP, MonoProtocol.SuspendPolicy.ALL,
            MonoModifier.Step(thread, MonoProtocol.StepSize.LINE, depth, filter)).get(TIMEOUT_S, TimeUnit.SECONDS)
        resumeQuietly()
    }

    private fun clearStep() {
        val request = stepRequest ?: return
        stepRequest = null
        runCatching { connection.clearRequest(MonoProtocol.EventKind.STEP, request).get(TIMEOUT_S, TimeUnit.SECONDS) }
    }

    /** Run to Cursor: breakpoints of one stop at the line, in the code loaded now. */
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) = onWorker {
        val requests = breakpoints.locations(position.file.path, position.line + 1).map { (method, il) ->
            connection.request(MonoProtocol.EventKind.BREAKPOINT, MonoProtocol.SuspendPolicy.ALL, MonoModifier.LocationOnly(method, il)).get(TIMEOUT_S, TimeUnit.SECONDS)
        }
        if (requests.isEmpty()) session.reportMessage("No code of the loaded assemblies at this line", com.intellij.openapi.ui.MessageType.WARNING)
        runToRequests = requests
        resumeQuietly()
    }

    private fun clearRunTo() {
        val requests = runToRequests
        runToRequests = emptyList()
        requests.forEach { runCatching { connection.clearRequest(MonoProtocol.EventKind.BREAKPOINT, it).get(TIMEOUT_S, TimeUnit.SECONDS) } }
    }

    override fun stopAsync(): Promise<Any> {
        AppExecutorUtil.getAppExecutorService().execute { end() }
        return stopped
    }

    /**
     * The end, once, whoever asks: the runtime resumed (a game left at a breakpoint would hold the editor) and `VM.DISPOSE` — without it the
     * agent of Unity does not listen for the next debugger (UNITY_PLAN.md, "Отладчик") — then the socket closed.
     */
    private fun end() {
        if (!shutdown.compareAndSet(false, true)) return
        try {
            if (connected && !connection.isClosed) {
                runCatching { connection.resume().get(SHORT_TIMEOUT_S, TimeUnit.SECONDS) }
                runCatching { connection.dispose().get(SHORT_TIMEOUT_S, TimeUnit.SECONDS) }
            }
        } finally {
            if (::connection.isInitialized) connection.close()
            runCatching { socket?.close() }
            worker.shutdownNow()
            handler.finish()
            stopped.setResult(Unit)
            // a session ending by itself (the editor closed, the attach failed) is ended in the IDE too
            if (!session.isStopped) session.stop()
        }
    }

    fun print(text: String, type: Key<*>) = handler.notifyTextAvailable(text, type)

    /** Stop and Detach both detach: the editor goes on. */
    private inner class MonoProcessHandler : ProcessHandler() {
        override fun destroyProcessImpl() = AppExecutorUtil.getAppExecutorService().execute { end() }
        override fun detachProcessImpl() = AppExecutorUtil.getAppExecutorService().execute { end() }
        override fun detachIsDefault(): Boolean = true
        override fun getProcessInput(): OutputStream? = null

        fun finish() {
            if (!isProcessTerminated) notifyProcessDetached()
        }
    }

    companion object {
        const val TIMEOUT_S = 10L
        private const val SHORT_TIMEOUT_S = 3L

        fun errorText(error: Throwable): String {
            val cause = generateSequence(error) { it.cause }.firstOrNull { it is MonoException || it is MonoClosedException || it is java.util.concurrent.TimeoutException } ?: error
            return when (cause) {
                is java.util.concurrent.TimeoutException -> "The debugger has not answered in time"
                else -> cause.message ?: cause.javaClass.simpleName
            }
        }
    }
}
