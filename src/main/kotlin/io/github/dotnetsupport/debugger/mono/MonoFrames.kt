package io.github.dotnetsupport.debugger.mono

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import java.util.concurrent.TimeUnit

/**
 * The managed threads of the runtime at a stop (all of them are suspended); [activeThread] — the one of the event, with its top frame;
 * [steppingThread] — the id the agent stopped on, for the step requests (it may be missing from the list of threads, see the context).
 */
class MonoSuspendContext(
    process: MonoDebugProcess, threads: List<Pair<Int, String>>, val activeThread: Int?, top: MonoStackFrame?, val steppingThread: Int? = activeThread,
) : XSuspendContext() {
    private val stacks = threads.map { (id, name) -> MonoExecutionStack(process, id, name, if (id == activeThread) top else null) }
    private val active = stacks.firstOrNull { it.thread == activeThread } ?: stacks.firstOrNull()

    override fun getActiveExecutionStack(): XExecutionStack? = active
    override fun getExecutionStacks(): Array<XExecutionStack> = (listOfNotNull(active) + stacks.filter { it !== active }).toTypedArray()
}

/** One thread; its frames are read when it is looked at (`THREAD.GET_FRAME_INFO`), the names and lines of their methods once per method. */
class MonoExecutionStack(private val process: MonoDebugProcess, val thread: Int, name: String, private val top: MonoStackFrame?) :
    XExecutionStack(name.ifBlank { "Thread $thread" }, AllIcons.Debugger.ThreadSuspended) {
    override fun getTopFrame(): XStackFrame? = top

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val frames = process.connection.frames(thread).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS)
                container.addStackFrames(frames.drop(firstFrameIndex).map(process::stackFrame), true)
            } catch (e: Exception) {
                container.errorOccurred(MonoDebugProcess.errorText(e))
            }
        }
    }
}

/** A frame: its method, and the statement its IL offset is in when the method has symbols (Unity's own methods have none). */
class MonoStackFrame(val frame: MonoFrame, private val method: MonoMethodInfo) : XStackFrame() {
    private val point: MonoSequencePoint? = method.debugInfo?.at(frame.ilOffset)

    private val position: XSourcePosition? by lazy {
        val path = point?.file ?: return@lazy null
        val file = LocalFileSystem.getInstance().findFileByPath(path.replace('\\', '/')) ?: return@lazy null
        XDebuggerUtil.getInstance().createPosition(file, (point.line - 1).coerceAtLeast(0))
    }

    override fun getSourcePosition(): XSourcePosition? = position

    // the ids of frames are new at every stop: the selection is kept by what the frame is
    override fun getEqualityObject(): Any = "${method.type.fullName}.${method.name}"

    override fun customizePresentation(component: ColoredTextContainer) {
        val name = "${method.type.name}.${method.name}"
        component.append(name, if (position == null) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        point?.file?.let { component.append("  ${MonoBreakpointLocations.fileName(it)}:${point.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (method.type.namespace.isNotEmpty()) component.append("  ${method.type.namespace}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) {
        node.addChildren(XValueChildrenList.EMPTY, true)
        node.setMessage("The Unity debugger does not show variables yet", null, SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }
}
