package io.github.dotnetsupport.debugger

import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import io.github.dotnetsupport.monitor.RunningDotNetProcesses
import io.github.dotnetsupport.run.ListeningUrlListener
import io.github.dotnetsupport.run.TestHostDebug
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<DotNetDebugProcess>()

/**
 * A debug session of `dotnet-debugger` on the XDebugger API of the platform, which every IDE has; the DAP module of the platform is not
 * needed. The order of the protocol: `initialize`, `launch` / `attach`, then on the `initialized` event the breakpoints and
 * `configurationDone` (the response to `launch` may come only after that).
 */
class DotNetDebugProcess(
    session: XDebugSession,
    private val adapter: DebugAdapterProcess,
    private val start: DebugStart,
    trace: Writer?,
) : XDebugProcess(session), DapConnection.Listener {
    val connection = DapConnection(adapter.input, adapter.output, this, trace)
    private val handler = DebuggeeProcessHandler()
    private val lineBreakpoints = DotNetLineBreakpointHandler(this)
    private val exceptionBreakpoints = DotNetExceptionBreakpointHandler(this)
    private val editors = DotNetEditorsProvider()

    @Volatile var capabilities: JsonObject = JsonObject()
        private set

    /** The breakpoints go to the adapter as they change only after the first full list, which is sent on `initialized`. */
    @Volatile var configured: Boolean = false
        private set

    /** The thread of the last stop: the one the steps and Resume are for. */
    @Volatile var stoppedThreadId: Int? = null
        private set

    @Volatile private var exitCode: Int? = null
    private val initialBreakSkipped = AtomicBoolean()
    private val shutdown = AtomicBoolean()
    private val stopped = AsyncPromise<Any>()
    @Volatile private var forgetDebuggee: (() -> Unit)? = null

    init {
        if (start.openBrowser) handler.addProcessListener(ListeningUrlListener(start.launchUrl))
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editors
    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(lineBreakpoints, exceptionBreakpoints)
    override fun doGetProcessHandler(): ProcessHandler = handler

    /**
     * A session started without an execution result gets a console from the platform that is attached to nothing (seen live: the output
     * of the program never showed). The output of the program comes as `output` events, which the handler turns into its text.
     */
    override fun createConsole(): ExecutionConsole = super.createConsole().also { (it as? ConsoleView)?.attachToProcess(handler) }

    override fun sessionInitialized() {
        handler.startNotify()
        connection.start()
        connection.request("initialize", json(
            "clientID" to "intellij", "clientName" to "IntelliJ Platform", "adapterID" to "coreclr", "locale" to "en-us",
            "pathFormat" to "path", "linesStartAt1" to true, "columnsStartAt1" to true,
            "supportsVariableType" to true, "supportsVariablePaging" to true, "supportsRunInTerminalRequest" to false,
        )).thenCompose { answer ->
            capabilities = answer
            connection.request(if (start.attach) "attach" else "launch", DapConnection.GSON.toJsonTree(start.arguments))
        }.whenComplete { _, error ->
            if (error != null && !shutdown.get()) {
                print("Cannot start debugging: ${errorText(error)}\n", ProcessOutputTypes.STDERR)
                session.stop()
            }
        }
    }

    // --- events of the adapter ---

    override fun event(event: String, body: JsonObject) {
        when (event) {
            "initialized" -> configure()
            "stopped" -> stoppedEvent(body)
            "continued" -> if (!session.isStopped) session.sessionResumed()
            "output" -> output(body)
            "process" -> body.int("systemProcessId")?.let { debuggee(it.toLong()) }
            "breakpoint" -> body.getAsJsonObject("breakpoint")?.let(lineBreakpoints::update)
            "exited" -> exitCode = body.int("exitCode")
            "terminated" -> AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = false, programGone = true) }
        }
    }

    override fun closed() {
        // the adapter has exited or was killed: whatever state the session is in, it is over
        AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = false, programGone = true) }
    }

    private fun configure() {
        // first: a breakpoint set while the lists below are on their way sends its file itself instead of waiting for the next change
        configured = true
        CompletableFuture.allOf(lineBreakpoints.sendAll(), exceptionBreakpoints.send())
            .handle { _, _ -> null }
            .thenCompose { connection.request("configurationDone") }
            .exceptionally { error -> LOG.info("configurationDone: ${errorText(error)}"); null }
    }

    private fun stoppedEvent(body: JsonObject) {
        val threadId = body.int("threadId")
        val reason = body.string("reason")
        if (start.skipInitialBreak && TestHostDebug.isInitialBreak(reason, body.string("description")) && initialBreakSkipped.compareAndSet(false, true)) {
            resumeThread(threadId)
            return
        }
        stoppedThreadId = threadId
        lineBreakpoints.clearTemporary()
        val threads = connection.request("threads").thenApply { answer ->
            answer.objects("threads").mapNotNull { thread -> thread.int("id")?.let { it to thread.string("name").orEmpty() } }
        }
        val top = if (threadId == null) CompletableFuture.completedFuture(emptyList()) else stackTrace(threadId, 0, FIRST_FRAMES)
        threads.thenCombine(top) { list, frames -> list to frames }.whenComplete { result, error ->
            if (error != null) return@whenComplete LOG.info("Cannot show the stop: ${errorText(error)}")
            val (list, frames) = result
            // the thread of the event, not a guess: .NET thread ids are the ones of the OS and come in no particular order
            val active = threadId ?: list.firstOrNull()?.first
            val context = DotNetSuspendContext(this, list.ifEmpty { listOfNotNull(active?.let { it to "Thread $it" }) }, active, frames)
            if (reason == "exception" && threadId != null) printException(threadId)
            reached(context, body)
        }
    }

    /** A breakpoint of the IDE when the stop is one: the platform then logs, counts and decides whether to stay suspended. */
    private fun reached(context: DotNetSuspendContext, body: JsonObject) {
        val breakpoint = body.getAsJsonArray("hitBreakpointIds")?.firstNotNullOfOrNull { lineBreakpoints.find(it.asInt) }
            ?: if (body.string("reason") == "exception") exceptionBreakpoints.first() else null
        if (breakpoint == null) return session.positionReached(context)
        val expression = breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }
        val logged = if (expression == null) CompletableFuture.completedFuture<String?>(null)
        else context.topFrameId?.let { frame -> evaluate(expression, frame, "repl").handle { answer, error -> answer?.string("result") ?: error?.let(::errorText) } }
            ?: CompletableFuture.completedFuture(null)
        logged.thenAccept { value -> if (!session.breakpointReached(breakpoint, value, context)) resume(context) }
    }

    private fun printException(threadId: Int) {
        connection.request("exceptionInfo", json("threadId" to threadId)).thenAccept { info ->
            val details = info.getAsJsonObject("details")
            val type = details?.string("fullTypeName") ?: info.string("exceptionId") ?: "Exception"
            val message = details?.string("message") ?: info.string("description").orEmpty()
            // an unhandled exception stops the adapter after the stack has unwound: only [External Code] is left in the frames, and the
            // trace of the exception is what tells where it came from (its `file:line` are links in the console)
            val trace = details?.string("stackTrace")?.trimEnd()?.let { "\n$it" }.orEmpty()
            print("$type: $message$trace\n", ProcessOutputTypes.STDERR)
        }
    }

    private fun output(body: JsonObject) {
        val text = body.string("output") ?: return
        when (body.string("category")) {
            "telemetry" -> Unit
            "stderr" -> print(text, ProcessOutputTypes.STDERR)
            "stdout", null -> print(text, ProcessOutputTypes.STDOUT)
            else -> print(text, ProcessOutputTypes.SYSTEM)
        }
    }

    fun print(text: String, type: Key<*>) = handler.notifyTextAvailable(text, type)

    /** The program under the debugger: for the .NET Monitor, and so that nobody attaches to it a second time (that kills it on Windows). */
    private fun debuggee(processId: Long) {
        DebuggedProcesses.add(processId)
        val forgetMonitor = RunningDotNetProcesses.getInstance(session.project).started(start.name, processId)
        forgetDebuggee = { forgetMonitor(); DebuggedProcesses.remove(processId) }
    }

    // --- requests the frames and values make ---

    fun stackTrace(threadId: Int, startFrame: Int, levels: Int): CompletableFuture<List<JsonObject>> =
        connection.request("stackTrace", json("threadId" to threadId, "startFrame" to startFrame, "levels" to levels), REQUEST_TIMEOUT_MS)
            .thenApply { it.objects("stackFrames") }

    fun evaluate(expression: String, frameId: Int?, context: String): CompletableFuture<JsonObject> =
        connection.request("evaluate", json("expression" to expression, "frameId" to frameId, "context" to context), EVALUATE_TIMEOUT_MS)

    // --- commands of the IDE ---

    private fun threadOf(context: XSuspendContext?): Int? = (context as? DotNetSuspendContext)?.activeThreadId ?: stoppedThreadId

    override fun resume(context: XSuspendContext?) = resumeThread(threadOf(context))

    private fun resumeThread(threadId: Int?) {
        connection.request("continue", json("threadId" to (threadId ?: 0)))
    }

    override fun startStepOver(context: XSuspendContext?) = step("next", context)
    override fun startStepInto(context: XSuspendContext?) = step("stepIn", context)
    override fun startStepOut(context: XSuspendContext?) = step("stepOut", context)

    private fun step(command: String, context: XSuspendContext?) {
        val threadId = threadOf(context) ?: return
        connection.request(command, json("threadId" to threadId)).exceptionally { error ->
            session.reportMessage("${command.replaceFirstChar(Char::uppercase)} failed: ${errorText(error)}", com.intellij.openapi.ui.MessageType.WARNING)
            null
        }
    }

    override fun startPausing() {
        connection.request("pause", json("threadId" to (stoppedThreadId ?: 0)))
    }

    /** Run to Cursor: DAP has no request of its own, so a breakpoint of one stop, sent with the others of its file. */
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        lineBreakpoints.runTo(position.file.path, position.line).thenRun { resume(context) }
    }

    override fun stopAsync(): Promise<Any> {
        AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = start.attach, programGone = false) }
        return stopped
    }

    /**
     * The end of the session, once, whoever asks. A launched program is ended (`terminate`, then `disconnect`); a process the debugger
     * was attached to is only let go: continued first when it stands at a breakpoint (seen live: the adapter detaching from a stopped
     * process takes it down), then `disconnect` without ending it. Then the adapter is killed, whatever it has answered.
     */
    private fun shutdown(detach: Boolean, programGone: Boolean) {
        if (!shutdown.compareAndSet(false, true)) return
        try {
            if (!programGone && !connection.isClosed) {
                if (detach || start.attach) {
                    if (session.isSuspended) runCatching { connection.request("continue", json("threadId" to (stoppedThreadId ?: 0)), SHORT_TIMEOUT_MS).get() }
                    runCatching { connection.request("disconnect", json("terminateDebuggee" to false), SHORT_TIMEOUT_MS).get() }
                } else {
                    if (capabilities.bool("supportsTerminateRequest") == true) runCatching { connection.request("terminate", null, SHORT_TIMEOUT_MS).get() }
                    runCatching { connection.request("disconnect", json("terminateDebuggee" to true), SHORT_TIMEOUT_MS).get() }
                }
            }
        } finally {
            connection.close()
            adapter.stop()
            forgetDebuggee?.invoke()
            handler.finish(exitCode)
            stopped.setResult(Unit)
        }
    }

    /** Stop and Detach of the IDE come here through the process handler of the session. */
    private inner class DebuggeeProcessHandler : ProcessHandler() {
        override fun destroyProcessImpl() {
            AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = start.attach, programGone = false) }
        }

        override fun detachProcessImpl() {
            AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = true, programGone = false) }
        }

        override fun detachIsDefault(): Boolean = start.attach
        override fun getProcessInput(): OutputStream? = null

        fun finish(code: Int?) {
            if (!isProcessTerminated) notifyProcessTerminated(code ?: 0)
        }
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        /** Implicit evaluation (`ToString()`, getters) may take seconds per value; a getter that never returns must not hold the session forever. */
        const val EVALUATE_TIMEOUT_MS = 30_000L
        private const val SHORT_TIMEOUT_MS = 3_000L
        const val FIRST_FRAMES = 20

        /** The text of the adapter as it is, not the class of the exception around it. */
        fun errorText(error: Throwable): String {
            val cause = generateSequence(error) { it.cause }.firstOrNull { it is DapException || it is DapClosedException || it is java.util.concurrent.TimeoutException } ?: error
            return when (cause) {
                is java.util.concurrent.TimeoutException -> "The debugger has not answered in time"
                else -> cause.message ?: cause.javaClass.simpleName
            }
        }

        fun samePath(a: String, b: String): Boolean = FileUtil.pathsEqual(FileUtil.toSystemIndependentName(a), FileUtil.toSystemIndependentName(b))

        fun pathOf(fileUrl: String): String = VfsUtilCore.urlToPath(fileUrl)
    }
}
