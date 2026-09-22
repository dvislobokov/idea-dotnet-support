package io.github.dotnetsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import io.github.dotnetsupport.lang.CSharpHoverExpression

/** All threads of the program at a stop; the active one is the thread the `stopped` event named, with its top frames already known. */
class DotNetSuspendContext(
    private val process: DotNetDebugProcess, threads: List<Pair<Int, String>>, val activeThreadId: Int?, topFrames: List<JsonObject>,
) : XSuspendContext() {
    private val stacks: List<DotNetExecutionStack> = threads.map { (id, name) ->
        DotNetExecutionStack(process, id, name, if (id == activeThreadId) topFrames else null)
    }
    private val active = stacks.firstOrNull { it.threadId == activeThreadId } ?: stacks.firstOrNull()

    val topFrameId: Int? get() = active?.topFrame?.let { (it as? DotNetStackFrame)?.id }

    override fun getActiveExecutionStack(): XExecutionStack? = active

    // the stopped thread first, as Rider lists it
    override fun getExecutionStacks(): Array<XExecutionStack> = (listOfNotNull(active) + stacks.filter { it !== active }).toTypedArray()
}

/** One thread. Frames come in pages (`stackTrace` with `startFrame` / `levels`): a deep recursion is not read whole at every stop. */
class DotNetExecutionStack(
    private val process: DotNetDebugProcess, val threadId: Int, name: String, private val known: List<JsonObject>?,
) : XExecutionStack(name.ifBlank { "Thread $threadId" }, AllIcons.Debugger.ThreadSuspended) {
    private val top: XStackFrame? = known?.firstOrNull()?.let { DotNetStackFrame(process, it) }

    override fun getTopFrame(): XStackFrame? = top

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        // what the stop has fetched already goes first, without a request
        val ready = known?.drop(firstFrameIndex)?.map { DotNetStackFrame(process, it) }.orEmpty()
        val done = known != null && known.size < DotNetDebugProcess.FIRST_FRAMES
        if (ready.isNotEmpty() || done) container.addStackFrames(ready, done)
        if (!done) page(firstFrameIndex + ready.size, container)
    }

    private fun page(start: Int, container: XStackFrameContainer) {
        if (start >= MAX_FRAMES) return container.addStackFrames(emptyList(), true)
        process.stackTrace(threadId, start, PAGE).whenComplete { frames, error ->
            if (container.isObsolete) return@whenComplete
            if (error != null) return@whenComplete container.errorOccurred(DotNetDebugProcess.errorText(error))
            val last = frames.size < PAGE
            container.addStackFrames(frames.map { DotNetStackFrame(process, it) }, last)
            if (!last) page(start + frames.size, container)
        }
    }

    private companion object {
        const val PAGE = 50
        const val MAX_FRAMES = 2000
    }
}

/**
 * A frame of the protocol. Without a source (external code) it has no position and is gray, as `[External Code]` in Rider. The
 * variables are the scopes of the adapter: the first (the locals) is shown open, the others as groups.
 */
class DotNetStackFrame(private val process: DotNetDebugProcess, private val frame: JsonObject) : XStackFrame() {
    val id: Int = frame.int("id") ?: 0
    private val name = frame.string("name").orEmpty()
    private val path = frame.getAsJsonObject("source")?.string("path")
    private val line = (frame.int("line") ?: 1) - 1
    private val subtle = frame.string("presentationHint") in setOf("subtle", "label") || path == null

    private val position: XSourcePosition? by lazy {
        val file = path?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return@lazy null
        XDebuggerUtil.getInstance().createPosition(file, line.coerceAtLeast(0))
    }

    override fun getSourcePosition(): XSourcePosition? = position

    // the ids of frames are new at every stop: the selection is kept by what the frame is
    override fun getEqualityObject(): Any = "$name|$path"

    override fun getEvaluator(): XDebuggerEvaluator = DotNetEvaluator(process, id)

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(name, if (subtle) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (path != null) component.append("  ${path.substringAfterLast('/').substringAfterLast('\\')}:${line + 1}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) {
        process.connection.request("scopes", json("frameId" to id), DotNetDebugProcess.REQUEST_TIMEOUT_MS).whenComplete { answer, error ->
            if (node.isObsolete) return@whenComplete
            if (error != null) return@whenComplete node.setErrorMessage(DotNetDebugProcess.errorText(error))
            val scopes = answer.objects("scopes")
            val first = scopes.firstOrNull() ?: return@whenComplete node.addChildren(XValueChildrenList.EMPTY, true)
            // the other scopes (statics, registers) as groups; the locals are what a stop is looked at for
            val groups = scopes.drop(1).map { scope -> ScopeGroup(process, scope, id) }
            DotNetValueChildren(process, first.int("variablesReference") ?: 0, first.int("indexedVariables"), id).load(node, 0, groups)
        }
    }
}

private class ScopeGroup(private val process: DotNetDebugProcess, private val scope: JsonObject, private val frameId: Int) : XValueGroup(scope.string("name").orEmpty()) {
    override fun isAutoExpand(): Boolean = scope.bool("expensive") != true
    override fun computeChildren(node: XCompositeNode) =
        DotNetValueChildren(process, scope.int("variablesReference") ?: 0, scope.int("indexedVariables"), frameId).load(node, 0)
}

/**
 * Evaluate, watches, the hover in the editor, conditions of the platform's own: `evaluate` in the frame. The expression under the mouse
 * is found by tokens ([CSharpHoverExpression]): the plugin has no parser, and the evaluator must say what to evaluate there.
 */
class DotNetEvaluator(private val process: DotNetDebugProcess, private val frameId: Int) : XDebuggerEvaluator() {
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        process.evaluate(expression, frameId, "watch").whenComplete { answer, error ->
            if (error != null) callback.errorOccurred(DotNetDebugProcess.errorText(error))
            else callback.evaluated(DotNetValue(process, expression, answer.string("result").orEmpty(), answer.string("type"),
                answer.int("variablesReference") ?: 0, answer.int("indexedVariables"), expression, frameId, answer.getAsJsonObject("presentationHint")))
        }
    }

    override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? =
        CSharpHoverExpression.rangeAt(document.immutableCharSequence, offset)
}
