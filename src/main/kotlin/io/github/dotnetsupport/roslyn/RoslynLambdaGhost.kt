package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
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
import io.github.dotnetsupport.lang.CSharpGhostText
import io.github.dotnetsupport.lang.CSharpLocalCalls
import io.github.dotnetsupport.lang.CSharpScopeNames
import io.github.dotnetsupport.lang.GhostPlace
import io.github.dotnetsupport.suggest.SuggestionRules
import io.github.dotnetsupport.suggest.SuggestionStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        if (request.file !is CSharpFile) return false
        // where an argument begins, and nowhere else: a method of this file is answered from its text, before the solution is loaded
        return LambdaSuggestions.atArgumentStart(request.document.immutableCharSequence, request.endOffset)
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val text = readAction { request.document.immutableCharSequence }
        val offset = request.endOffset
        if (!LambdaSuggestions.atArgumentStart(text, offset)) return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {}
        val file = request.file
        val visible = CSharpScopeNames.visibleAt(text, offset)
        // a method of this very file: its parameters are in the text, the server need not be asked (and at the first argument it often
        // cannot answer yet: `(` is typed in no time, and the question gets to the server before the change of the document does —
        // reported: `Save(` offered nothing, `Save(order, ` offered the token)
        val local = CSharpLocalCalls.at(text, offset)?.let { call -> ArgumentSuggestions.forParameters(call.parameters, call.active, visible) }
        val workspace = file.project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded }
        val virtualFile = file.virtualFile
        val ghost = if (local != null) CSharpGhostText.Ghost(SuggestionRules.ARGUMENTS, local)
        else if (client == null || virtualFile == null) null
        else {
            // the document is read here, under the read action: the request below runs on a thread that has none
            val params = readAction { SignatureHelpParams(client.getDocumentIdentifier(virtualFile), RoslynNavigation.position(request.document, offset)) }
            var help: SignatureHelp? = null
            for (wait in RETRY_AFTER_MS) {
                // a new letter typed meanwhile cancels this request: the delay is where it is noticed
                if (wait > 0) delay(wait)
                help = withContext(Dispatchers.IO) {
                    runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) } }.getOrNull()
                }?.takeIf { it.signatures.orEmpty().isNotEmpty() }
                if (help != null) break
            }
            // a delegate first; otherwise the variables named as the parameters are: `Save(` -> `order, cancellationToken`
            help?.let { found ->
                LambdaSuggestions.forHelp(found).firstOrNull()?.head?.let { CSharpGhostText.Ghost(SuggestionRules.LAMBDA, it) }
                    ?: ArgumentSuggestions.forHelp(found, visible)?.let { CSharpGhostText.Ghost(SuggestionRules.ARGUMENTS, it) }
            }
        }
        if (ghost != null) {
            shownRule = ghost.rule
            SuggestionStats.getInstance().shown(ghost.rule, readAction { GhostPlace.of(request) })
        }
        return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {
            if (ghost != null) emit(InlineCompletionGrayTextElement(ghost.text))
        }
    }

    @Volatile
    private var shownRule: String? = null

    override val insertHandler: InlineCompletionInsertHandler = object : InlineCompletionInsertHandler {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)
            shownRule?.let { SuggestionStats.getInstance().accepted(it) }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 1_500

        /** The first question at once, the next ones when the server has had the time to take the change in. */
        val RETRY_AFTER_MS = longArrayOf(0, 150, 350)
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
