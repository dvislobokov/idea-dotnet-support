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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.UserDataHolderBase
import io.github.dotnetsupport.lang.CSharpFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.SignatureHelpParams

/**
 * The gray text of a lambda right where a delegate is expected — `serviceProvider => ` after `AddSingleton(` — accepted with Tab, the way
 * the inline completion of the platform shows the suggestions of Full Line and the AI assistant. The same source as the item of the
 * completion list ([RoslynLambdaCompletion]): the signature help of the server for the argument at the caret.
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
        val lambda = withContext(Dispatchers.IO) {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@withContext null
            val params = SignatureHelpParams(client.getDocumentIdentifier(virtualFile), RoslynNavigation.position(document, offset))
            val help = runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) } }.getOrNull()
            help?.let(LambdaSuggestions::forHelp)?.firstOrNull()
        }
        return InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {
            if (lambda != null) emit(InlineCompletionGrayTextElement(lambda.head))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 1_500
    }
}
