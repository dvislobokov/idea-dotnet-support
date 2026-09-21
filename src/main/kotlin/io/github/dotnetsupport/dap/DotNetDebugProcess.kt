package io.github.dotnetsupport.dap

import com.intellij.execution.ExecutionResult
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapEventConsumer
import com.intellij.platform.dap.DapStackFrame
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DapThread
import com.intellij.platform.dap.DapVariable
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.platform.dap.xdebugger.DapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DapXSuspendContext
import com.intellij.platform.dap.xdebugger.DefaultDapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import io.github.dotnetsupport.lang.CSharpHoverExpression
import io.github.dotnetsupport.monitor.RunningDotNetProcesses
import io.github.dotnetsupport.run.DotNetRunConfiguration
import io.github.dotnetsupport.run.ListeningUrlListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import org.eclipse.lsp4j.debug.ContinueArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.ExitedEventArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.ProcessEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.StoppedEventArgumentsReason
import org.eclipse.lsp4j.debug.TerminatedEventArguments

/** The debug process of the platform; exists for [DotNetPresentationFactory] only. */
class DotNetDebugProcess(
    session: XDebugSession, dapDebugSession: DapDebugSession, xDebugProcessScope: CoroutineScope, globalScope: CoroutineScope,
    debugAdapterDescriptor: DebugAdapterDescriptor<*>, executionEnvironment: ExecutionEnvironment, executionResult: ExecutionResult?,
    startRequestType: DapStartRequest, startRequestArguments: Map<String, Any?>, private val stopped: StoppedThread,
) : DapXDebugProcess(
    session, dapDebugSession, xDebugProcessScope, globalScope, debugAdapterDescriptor, executionEnvironment, executionResult, startRequestType, startRequestArguments,
) {
    private val attached = startRequestType == DapStartRequest.Attach

    /** C# fragments with completion from the stopped program instead of the plain text of the platform, see [DotNetEditorsProvider]. */
    private val editors = DotNetEditorsProvider()

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editors

    override val presentationFactory: DapXDebuggerPresentationFactory =
        DotNetPresentationFactory(stopped) { (session.currentStackFrame as? DefaultDapXStackFrame)?.frame?.id }

    /** The handler of line breakpoints is the one of the platform; exception breakpoints it leaves to the debugger. */
    private val handlers: Array<XBreakpointHandler<*>> by lazy { arrayOf(*super.getBreakpointHandlers(), DotNetExceptionBreakpointHandler(dapDebugSession)) }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = handlers

    /** `launchBrowser` of the profile: under a debugger the output of the program comes as events of the protocol, not from a process handler. */
    private val browser = (executionEnvironment.runProfile as? DotNetRunConfiguration)?.takeIf { it.options.openBrowser }
        ?.let { ListeningUrlListener(it.launchProfile()?.launchUrl) }

    /**
     * Stop of the platform is `terminate`, then `disconnect`, whatever the session was started with, and the adapter ends the program on
     * `terminate`. A process the debugger was attached to is not ours to end: it is detached first, and what the platform sends
     * afterwards finds nothing to end.
     */
    override fun stopAsync(): Promise<Any> {
        // The process has ended by itself (a test host after its tests): the connection is closed, a command would never run.
        if (!attached || dapDebugSession.sessionStopped.isCompleted) return super.stopAsync()
        val detached = AsyncPromise<Any>()
        // and whatever happens to the command, the session must not stay half-stopped forever
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ if (!detached.isDone) detached.setResult(Unit) }, DETACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        dapDebugSession.commandProcessor.submitCommand {
            try {
                // Seen live: the adapter detaching from a process that stands at a breakpoint takes the process down with it, while a
                // running one survives. So the program is let go first; the adapter stops it itself for the moment of the detach.
                if (session.isSuspended) stopped.id?.let { server.continue_(ContinueArguments().apply { threadId = it }).await() }
                server.disconnect(DisconnectArguments().apply { terminateDebuggee = false }).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.info("Detach failed, the session is stopped anyway: ${e.message}")
            } finally {
                if (!detached.isDone) detached.setResult(Unit)
            }
        }
        return detached.thenAsync { super.stopAsync() }
    }

    override fun formatAndPrintOutput(output: OutputEventArguments) {
        super.formatAndPrintOutput(output)
        browser?.textAvailable(output.output.orEmpty())
    }
}

/**
 * On `stopped` the platform looks the stopped thread up with a binary search by id in the list the adapter has answered `threads` with,
 * and does not sort the list. The ids of .NET threads are the ones of the OS, in the order of creation, so the search misses, and the
 * active thread becomes the first paused one: a thread pool worker with no frames of the user, or a thread that has exited since,
 * and then a step is sent for a thread the adapter does not know ("nothing happens" on F8). The states of the threads are no help:
 * the platform sets them asynchronously, after the context may have been made. So the id is taken from the event itself, see [StoppedThread].
 */
class DotNetPresentationFactory(private val stopped: StoppedThread, private val currentFrameId: () -> Int?) : DefaultDapXDebuggerPresentationFactory() {
    override fun createSuspendContext(commandProcessor: DapCommandProcessor, threads: List<DapThread>, activeThread: DapThread?): DapXSuspendContext =
        super.createSuspendContext(commandProcessor, threads, StoppedThread.choose(threads.map { it to it.id }, stopped.id) ?: activeThread)

    override fun createStackFrame(commandProcessor: DapCommandProcessor, thread: DapThread, frame: DapStackFrame): XStackFrame =
        DotNetStackFrame(this, commandProcessor, thread, frame)

    override fun createValue(commandProcessor: DapCommandProcessor, variable: DapVariable, icon: Icon?): XNamedValue =
        DotNetValue(this, commandProcessor, variable, icon, currentFrameId)
}

/** The frame of the platform with an evaluator that knows what is under the mouse, see [HoverEvaluator]. */
class DotNetStackFrame(factory: DapXDebuggerPresentationFactory, commandProcessor: DapCommandProcessor, thread: DapThread, frame: DapStackFrame) :
    DefaultDapXStackFrame(factory, commandProcessor, thread, frame) {
    private val hoverEvaluator by lazy { HoverEvaluator(super.getEvaluator()) }

    override fun getEvaluator(): XDebuggerEvaluator = hoverEvaluator
}

/**
 * The evaluator of the platform evaluates and nothing else: asked which expression is under the mouse it answers "none", so resting
 * the mouse on a variable in the editor shows nothing. The class is final, hence a wrapper; the expression is found by tokens.
 */
class HoverEvaluator(private val delegate: XDebuggerEvaluator) : XDebuggerEvaluator() {
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        delegate.evaluate(expression, callback, expressionPosition)

    override fun evaluate(expression: XExpression, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        delegate.evaluate(expression, callback, expressionPosition)

    override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? =
        CSharpHoverExpression.rangeAt(document.immutableCharSequence, offset)
}

private val LOG = logger<DotNetDebugProcess>()
private const val DETACH_TIMEOUT_SECONDS = 3L

/**
 * Tells the .NET Monitor about the program under the debugger: it is the adapter that starts it, so there is no process handler
 * to take the process id from; the adapter reports the id in the `process` event.
 */
class MonitoredDebuggee(private val name: String, private val processes: RunningDotNetProcesses) {
    @Volatile private var gone: (() -> Unit)? = null

    fun recording(consumer: DapEventConsumer): DapEventConsumer = object : DapEventConsumer by consumer {
        override fun process(args: ProcessEventArguments?) {
            args?.systemProcessId?.let { processId ->
                DebuggedProcesses.add(processId.toLong())
                val forgetMonitor = processes.started(name, processId.toLong())
                gone = { forgetMonitor(); DebuggedProcesses.remove(processId.toLong()) }
            }
            consumer.process(args)
        }

        override fun exited(args: ExitedEventArguments?) {
            forget()
            consumer.exited(args)
        }

        override fun terminated(args: TerminatedEventArguments?) {
            forget()
            consumer.terminated(args)
        }
    }

    /** Also for a session that ends without the events: the adapter was killed. */
    fun forget() {
        gone?.invoke()
        gone = null
    }
}

/** The thread of the last `stopped` event: written when the event arrives, before the platform starts processing it. */
class StoppedThread {
    @Volatile var id: Int? = null

    /** The consumer of the platform with [id] kept up to date; everything is passed on untouched. */
    fun recording(consumer: DapEventConsumer): DapEventConsumer = object : DapEventConsumer by consumer {
        override fun stopped(args: StoppedEventArguments?) {
            id = args?.threadId
            consumer.stopped(args?.let(::forPlatform))
        }
    }

    /**
     * A stop at an exception with `allThreadsStopped` makes the platform ask `exceptionInfo` of every thread; the adapter rightly answers
     * "No exception is being processed on this thread" for all but one, the platform does not catch that, and the stop is never shown:
     * the program stands still while the IDE thinks it runs. Told that one thread has stopped, the platform asks that thread only.
     * The price: during such a stop the other threads are listed as running. Resume and the steps continue all threads anyway.
     */
    fun forPlatform(args: StoppedEventArguments): StoppedEventArguments {
        if (args.reason == StoppedEventArgumentsReason.EXCEPTION && args.allThreadsStopped == true) args.allThreadsStopped = false
        return args
    }

    companion object {
        /** The one of [threads] (each with its id) with [stoppedId]; null when the event named no thread or the thread is not listed. */
        fun <T> choose(threads: List<Pair<T, Int>>, stoppedId: Int?): T? = threads.firstOrNull { it.second == stoppedId }?.first
    }
}
