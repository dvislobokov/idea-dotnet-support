package io.github.dotnetsupport.roslyn

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lang.CSharpFile
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation

/**
 * Parameter Info (Ctrl+P) as in Rider: every overload is a row, the parameter at the caret is highlighted, the overload that fits is marked.
 * The handler of the platform for LSP shows only the active signature of the answer, and only its parameters: for `Console.WriteLine(|)` that
 * is `WriteLine()` without parameters, a hint of 44 x 28 pixels that looked like nothing at all (reported, seen in the log). Registered for C#
 * before that one; without a loaded server it answers nothing and the platform's handler takes over.
 */
class RoslynParameterInfoHandler : ParameterInfoHandler<PsiFile, SignatureInformation>, DumbAware {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiFile? {
        val help = signatureHelp(context.file, context.offset) ?: return null
        context.itemsToShow = help.signatures.toTypedArray()
        return context.file
    }

    override fun showParameterInfo(element: PsiFile, context: CreateParameterInfoContext) = context.showHint(element, context.offset, this)

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiFile? = context.file.takeIf { context.objectsToView.isNotEmpty() }

    /** Asked again as the caret moves: the parameter at the caret and the overload that fits; no answer means the caret has left the call. */
    override fun updateParameterInfo(element: PsiFile, context: UpdateParameterInfoContext) {
        if (context.parameterOwner != null && context.parameterOwner != element) return context.removeHint()
        context.parameterOwner = element
        val help = signatureHelp(context.file, context.offset)
        if (help == null) return context.removeHint()
        context.setCurrentParameter(help.activeParameter ?: 0)
        val active = help.signatures.getOrNull(help.activeSignature ?: 0)?.label
        context.highlightedParameter = context.objectsToView.firstOrNull { (it as? SignatureInformation)?.label == active }
    }

    override fun updateUI(signature: SignatureInformation, context: ParameterInfoUIContext) {
        val parameters = RoslynSignatures.parameters(signature)
        val current = signature.activeParameter ?: context.currentParameterIndex
        if (parameters.isEmpty()) {
            // an argument typed already: an overload without parameters does not fit
            context.setupUIComponentPresentation(NO_PARAMETERS, -1, -1, current > 0, false, false, context.defaultParameterColor)
            return
        }
        val text = parameters.joinToString(", ")
        val range = RoslynSignatures.rangeOf(parameters, current)
        // more arguments than parameters (and no `params`): this overload does not fit, as Rider greys it
        val fits = current < parameters.size || parameters.last().startsWith("params ")
        context.setupUIComponentPresentation(text, range?.first ?: -1, range?.last?.plus(1) ?: -1, !fits, false, false, context.defaultParameterColor)
    }

    private fun signatureHelp(file: PsiFile, offset: Int): SignatureHelp? {
        if (file !is CSharpFile) return null
        val workspace = file.project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull() ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!workspace.isLoaded) return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val params = SignatureHelpParams(client.getDocumentIdentifier(virtualFile), RoslynNavigation.position(document, offset))
        val answer = runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) } }.getOrNull()
        return answer?.takeIf { it.signatures.orEmpty().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000
        const val NO_PARAMETERS = "<no parameters>"
    }
}

/** The parameters of a signature of Roslyn as the hint shows them: `string format, object? arg0`. */
object RoslynSignatures {
    /**
     * From the label of the signature (`void Console.WriteLine(string format, object? arg0)`): Roslyn names a parameter by its name only
     * (`format`), so the type comes from the label. A label given as offsets is cut out of the label as well.
     */
    fun parameters(signature: SignatureInformation): List<String> {
        val label = signature.label.orEmpty()
        val byOffsets = signature.parameters.orEmpty().mapNotNull { parameter ->
            parameter.label?.takeIf { it.isRight }?.right?.let { label.substring(it.first.coerceIn(0, label.length), it.second.coerceIn(0, label.length)) }
        }
        if (byOffsets.isNotEmpty() && byOffsets.size == signature.parameters.size) return byOffsets
        return splitParameterList(label)
    }

    /** What is between the parentheses of the call part (`(` after the name, generic arguments aside), split at the top-level commas. */
    fun splitParameterList(label: String): List<String> {
        val open = label.indexOf('(').takeIf { it >= 0 } ?: return emptyList()
        val close = label.lastIndexOf(')').takeIf { it > open } ?: return emptyList()
        val inside = label.substring(open + 1, close)
        if (inside.isBlank()) return emptyList()
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in inside.withIndex()) {
            when (c) {
                '<', '(', '[', '{' -> depth++
                '>', ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { parts += inside.substring(start, i).trim(); start = i + 1 }
            }
        }
        parts += inside.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }

    /** Where the parameter [index] is in `parameters.joinToString(", ")`; the last one for a `params` array the caret has gone past. */
    fun rangeOf(parameters: List<String>, index: Int): IntRange? {
        val target = when {
            index < 0 -> return null
            index in parameters.indices -> index
            parameters.isNotEmpty() && parameters.last().startsWith("params ") -> parameters.lastIndex
            else -> return null
        }
        val start = parameters.take(target).sumOf { it.length + 2 }
        return start until start + parameters[target].length
    }
}
