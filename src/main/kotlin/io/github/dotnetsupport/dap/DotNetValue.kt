package io.github.dotnetsupport.dap

import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapVariable
import com.intellij.platform.dap.xdebugger.AbstractDapXValue
import com.intellij.platform.dap.xdebugger.DapXDebuggerPresentationFactory
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.debug.SetExpressionArguments
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import javax.swing.Icon

/**
 * A value of the variables view that can be changed (Set Value, F2). The value of the platform (`DefaultDapXValue`, final) has no
 * modifier at all: the client does not know the `setVariable` / `setExpression` requests. Looks exactly like the one of the platform.
 *
 * The request is `setExpression` and not `setVariable`: the latter needs the reference of the container the variable is listed in,
 * which the model of the platform keeps to itself, while the expression of a variable (`evaluateName`) and the frame are known.
 */
class DotNetValue(
    factory: DapXDebuggerPresentationFactory, commandProcessor: DapCommandProcessor, variable: DapVariable, icon: Icon?, private val currentFrameId: () -> Int?,
) : AbstractDapXValue(factory, commandProcessor, variable, icon) {

    override fun createValuePresentation(variable: DapVariable, hasChildren: Boolean, isLazy: Boolean): XValuePresentation =
        if (variable.value.isNotEmpty()) XRegularValuePresentation(variable.value, variable.type) else XRegularValuePresentation("", variable.type, "")

    /** Nothing to assign to without an expression: `[External Code]`, groups like Raw View, results the adapter gives no expression for. */
    override fun getModifier(): XValueModifier? = variable.evaluateName?.takeIf { it.isNotBlank() }?.let(::Modifier)

    private inner class Modifier(private val expression: String) : XValueModifier() {
        override fun calculateInitialValueEditorText(callback: XInitialValueCallback) = callback.setValue(variable.value)

        override fun setValue(newValue: XExpression, callback: XModificationCallback) {
            commandProcessor.submitCommand {
                try {
                    server.setExpression(setExpressionArguments(expression, newValue.expression, currentFrameId())).await()
                    callback.valueModified() // the views are rebuilt, the new value comes from the adapter
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    callback.errorOccurred(errorText(e))
                }
            }
        }
    }

    companion object {
        fun setExpressionArguments(expression: String, value: String, frameId: Int?): SetExpressionArguments = SetExpressionArguments().also {
            it.expression = expression
            it.value = value.trim()
            it.frameId = frameId
        }

        /** The text of the adapter as it is ("Cannot convert 'abc' to int"), not the class of the exception around it. */
        fun errorText(e: Throwable): String {
            val cause = generateSequence(e) { it.cause }.firstOrNull { it is ResponseErrorException } ?: e
            return (cause as? ResponseErrorException)?.responseError?.message ?: cause.message ?: cause.javaClass.simpleName
        }
    }
}
