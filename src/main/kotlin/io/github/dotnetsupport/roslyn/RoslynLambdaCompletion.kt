package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lang.CSharpExpressions
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpLambdaNames
import io.github.dotnetsupport.lang.LambdaSuggestion
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams

/**
 * A lambda where a delegate is expected, first in the list, as Rider offers it: at an argument whose parameter is `Func<IServiceProvider, object>`
 * the list starts with `serviceProvider => `, at `Action` with `() => `, at `EventHandler` with `(sender, e) => `. The parameter types
 * come from the signature help of the server (the active overload first, the other overloads after it); the names of the lambda
 * parameters are made from the types, as Rider names them. Roslyn itself offers nothing of the kind.
 */
class RoslynLambdaCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        // the native list makes its own lambdas from the plugin's semantics (NativeCSharpLambdas)
        if (CSharpFeatures.native(CSharpFeature.COMPLETION, file.project)) return
        val workspace = file.project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded } ?: return
        // only where an argument begins: right after `(` or `,`, or a name that is being typed there
        val text = parameters.editor.document.charsSequence
        val start = parameters.offset - result.prefixMatcher.prefix.length
        if (!LambdaSuggestions.atArgumentStart(text, start)) return
        val help = signatureHelp(client, file, parameters.offset) ?: return
        val indent = CSharpExpressions.indentAt(text, start)
        for ((index, lambda) in LambdaSuggestions.forHelp(help).withIndex()) {
            val priority = LambdaPriority.BASE - index
            result.addElement(PrioritizedLookupElement.withPriority(lambda.inlineElement(), priority))
            result.addElement(PrioritizedLookupElement.withPriority(lambda.blockElement(indent), priority - 0.5))
        }
    }

    private fun signatureHelp(client: com.intellij.platform.lsp.api.LspClient, file: PsiFile, offset: Int): SignatureHelp? {
        val virtualFile = file.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val params = SignatureHelpParams(client.getDocumentIdentifier(virtualFile), RoslynNavigation.position(document, offset))
        return runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) } }.getOrNull()?.takeIf { it.signatures.orEmpty().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 1_500
    }
}

object LambdaPriority {
    /** Above everything the server sends (see RoslynCompletionPolicy.priority) and above its preselected item. */
    const val BASE = 200.0
}

/** The lambdas of the signature help of the server; the names are [CSharpLambdaNames]'. */
object LambdaSuggestions {
    /** The lambdas for the parameter at the caret, the one of the active overload first, without repeats. */
    fun forHelp(help: SignatureHelp): List<LambdaSuggestion> {
        val signatures = help.signatures.orEmpty()
        val active = help.activeSignature ?: 0
        val ordered = listOfNotNull(signatures.getOrNull(active)) + signatures.filterIndexed { i, _ -> i != active }
        val parameter = help.activeParameter ?: 0
        return ordered.mapNotNull { signature ->
            val parameters = RoslynSignatures.parameters(signature)
            val declared = parameters.getOrNull(parameter) ?: parameters.lastOrNull()?.takeIf { it.startsWith("params ") } ?: return@mapNotNull null
            forParameter(declared)
        }.distinctBy { it.head }
    }

    fun forParameter(declared: String): LambdaSuggestion? = CSharpLambdaNames.forParameter(declared)

    fun splitGenericArguments(text: String): List<String> = CSharpLambdaNames.splitGenericArguments(text)

    fun names(types: List<String>): List<String> = CSharpLambdaNames.names(types)

    fun atArgumentStart(text: CharSequence, offset: Int): Boolean = CSharpLambdaNames.atArgumentStart(text, offset)
}
