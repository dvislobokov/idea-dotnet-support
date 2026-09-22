package io.github.dotnetsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.ThreeState
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XInlineDebuggerDataCallback
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import io.github.dotnetsupport.lang.CSharpInlineValues
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import javax.swing.Icon

/**
 * A variable, a member, an element or the result of an expression. Children come from `variables` of its reference, in pages.
 * Changed with `setExpression` (Set Value, F2): it needs only the expression of the variable (`evaluateName`) and the frame.
 */
class DotNetValue(
    private val process: DotNetDebugProcess,
    name: String,
    private val value: String,
    private val type: String?,
    private val reference: Int,
    private val indexed: Int?,
    private val evaluateName: String?,
    private val frameId: Int,
    hint: JsonObject?,
) : XNamedValue(name) {
    private val kind = hint?.string("kind")

    override fun computePresentation(node: XValueNode, place: XValuePlace) =
        node.setPresentation(icon(), XRegularValuePresentation(value, type?.takeIf { it.isNotBlank() }), reference > 0)

    private fun icon(): Icon = when {
        kind == "property" -> AllIcons.Nodes.Property
        kind == "method" -> AllIcons.Nodes.Method
        kind == "class" || kind == "baseClass" -> AllIcons.Nodes.Class
        kind == "data" || name.startsWith("[") -> AllIcons.Debugger.Value
        else -> AllIcons.Nodes.Variable
    }

    override fun computeChildren(node: XCompositeNode) = DotNetValueChildren(process, reference, indexed, frameId).load(node, 0)

    /** Add to Watches and Copy Reference take the expression of the adapter, not the name of the row (`[0]`, `Name`). */
    override fun calculateEvaluationExpression(): Promise<XExpression> =
        resolvedPromise(XExpressionImpl.fromText(evaluateName?.takeIf { it.isNotBlank() } ?: name))

    /**
     * Values in the editor, next to the code ("Show values inline"): a local or a parameter tells on which lines it belongs. `person.Name`
     * of an expanded object is not looked for.
     */
    override fun computeInlineDebuggerData(callback: XInlineDebuggerDataCallback): ThreeState {
        if (evaluateName?.takeIf { it.isNotBlank() } != name) return ThreeState.NO
        val position = process.session.currentPosition ?: return ThreeState.NO
        val document = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(position.file) }
            ?: return ThreeState.NO
        val lines = CSharpInlineValues.lines(document.immutableCharSequence, name, position.line)
        for (line in lines) XDebuggerUtil.getInstance().createPosition(position.file, line)?.let(callback::computed)
        return if (lines.isEmpty()) ThreeState.NO else ThreeState.YES
    }

    /** Nothing to assign to without an expression: `[External Code]`, groups like Raw View, results the adapter gives no expression for. */
    override fun getModifier(): XValueModifier? = evaluateName?.takeIf { it.isNotBlank() }?.let(::Modifier)

    private inner class Modifier(private val expression: String) : XValueModifier() {
        override fun calculateInitialValueEditorText(callback: XInitialValueCallback) = callback.setValue(value)

        override fun setValue(newValue: XExpression, callback: XModificationCallback) {
            process.connection.request("setExpression", setExpressionArguments(expression, newValue.expression, frameId), DotNetDebugProcess.REQUEST_TIMEOUT_MS)
                .whenComplete { _, error ->
                    // the views are rebuilt, the new value comes from the adapter
                    if (error == null) callback.valueModified() else callback.errorOccurred(DotNetDebugProcess.errorText(error))
                }
        }
    }

    companion object {
        fun setExpressionArguments(expression: String, value: String, frameId: Int?): JsonObject =
            json("expression" to expression, "value" to value.trim(), "frameId" to frameId)

        fun of(process: DotNetDebugProcess, variable: JsonObject, frameId: Int) = DotNetValue(
            process, variable.string("name").orEmpty(), variable.string("value").orEmpty(), variable.string("type"),
            variable.int("variablesReference") ?: 0, variable.int("indexedVariables"), variable.string("evaluateName"), frameId,
            variable.getAsJsonObject("presentationHint"),
        )
    }
}

/**
 * The children of a reference, a page at a time and always with `start` / `count`: without them a big collection kills the adapter on
 * Linux and blocks it for minutes on Windows (`dap-probe/FINDINGS.md`). "Show more" asks for the next page.
 */
class DotNetValueChildren(private val process: DotNetDebugProcess, private val reference: Int, private val indexed: Int?, private val frameId: Int) {
    /** [groups] (the other scopes of a frame) go below the variables of the first page. */
    fun load(node: XCompositeNode, start: Int, groups: List<XValueGroup> = emptyList()) {
        if (reference <= 0) return node.addChildren(XValueChildrenList().also { list -> groups.forEach(list::addBottomGroup) }, true)
        process.connection.request("variables", json("variablesReference" to reference, "start" to start, "count" to PAGE), DotNetDebugProcess.REQUEST_TIMEOUT_MS)
            .whenComplete { answer, error ->
                if (node.isObsolete) return@whenComplete
                if (error != null) return@whenComplete node.setErrorMessage(DotNetDebugProcess.errorText(error))
                val variables = answer.objects("variables")
                val children = XValueChildrenList(variables.size)
                variables.forEach { children.add(DotNetValue.of(process, it, frameId)) }
                groups.forEach(children::addBottomGroup)
                val more = variables.size >= PAGE
                node.addChildren(children, !more)
                if (more) {
                    val shown = start + variables.size
                    node.tooManyChildren(indexed?.let { (it - shown).coerceAtLeast(1) } ?: PAGE) { load(node, shown) }
                }
            }
    }

    companion object {
        const val PAGE = 100
    }
}
