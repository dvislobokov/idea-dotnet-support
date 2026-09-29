package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.UserDataHolderBase
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpScopeNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams

/**
 * The gray text of a lambda right where a delegate is expected — `serviceProvider => ` after `AddSingleton(` — accepted with Tab, the way
 * the inline completion of the platform shows the suggestions of Full Line and the AI assistant. The same source as the item of the
 * completion list ([RoslynLambdaCompletion]): the signature help of the server for the argument at the caret. Where the parameter is
 * no delegate, the same place gets the arguments that are at hand ([ArgumentSuggestions]).
 */
class RoslynLambdaGhost : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("io.github.dotnetsupport.lambda")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val request = event.toRequest() ?: return false
        val file = request.file as? CSharpFile ?: return false
        return file.project.service<RoslynWorkspace>().isLoaded
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val text = readAction { request.document.immutableCharSequence }
        val offset = request.endOffset
        if (!LambdaSuggestions.atArgumentStart(text, offset)) return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {}
        val file = request.file
        val workspace = file.project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded } ?: return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {}
        val virtualFile = file.virtualFile ?: return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {}
        // the document is read here, under the read action: the request below runs on a thread that has none
        val params = readAction { SignatureHelpParams(client.getDocumentIdentifier(virtualFile), RoslynNavigation.position(request.document, offset)) }
        val ghost = withContext(Dispatchers.IO) {
            val help = runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) } }.getOrNull() ?: return@withContext null
            // a delegate first; otherwise the variables named as the parameters are: `Save(` -> `order, cancellationToken`
            LambdaSuggestions.forHelp(help).firstOrNull()?.head ?: ArgumentSuggestions.forHelp(help, CSharpScopeNames.visibleAt(text, offset))
        }
        return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {
            if (ghost != null) emit(InlineCompletionGrayTextElement(ghost))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 1_500
    }
}

/**
 * The arguments of a call that are at hand: the variables in scope named as the parameters, from the parameter at the caret on and
 * while they go in a row — `Save(` with `order` and `cancellationToken` around gives `order, cancellationToken`. The parameters are
 * the ones of the active overload of the signature help; the names in scope come from the tokens of the file.
 */
object ArgumentSuggestions {
    fun forHelp(help: SignatureHelp, visible: Set<String>): String? {
        val signature = help.signatures.orEmpty().getOrNull(help.activeSignature ?: 0) ?: return null
        return forParameters(RoslynSignatures.parameters(signature), help.activeParameter ?: 0, visible)
    }

    /** [declared]: `Order order`, `CancellationToken cancellationToken = default`, as the label of the signature has them. */
    fun forParameters(declared: List<String>, active: Int, visible: Set<String>): String? {
        val arguments = ArrayList<String>()
        for (parameter in declared.drop(active.coerceAtLeast(0))) {
            val head = parameter.trim()
            // `out` and `ref` want more than a name, `params` takes any number of them
            if (MODIFIED.containsMatchIn(head)) break
            val name = CSharpScopeNames.parameterNames(head).singleOrNull() ?: break
            arguments += listOf(name, "_$name").firstOrNull { it in visible } ?: break
        }
        return arguments.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private val MODIFIED = Regex("""^(?:out|ref|params|this)\s""")
}
