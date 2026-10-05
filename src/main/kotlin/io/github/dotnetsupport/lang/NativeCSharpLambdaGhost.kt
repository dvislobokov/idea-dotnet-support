package io.github.dotnetsupport.lang

import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.UserDataHolderBase
import io.github.dotnetsupport.suggest.SuggestionRules
import io.github.dotnetsupport.suggest.SuggestionStats

/**
 * The gray text where an argument begins, on the plugin's semantics (0.1.86; before, only the server's signature help gave it —
 * `RoslynLambdaGhost`): a lambda when the parameter is a delegate (`Register(` -> `serviceProvider => `), else the variables at hand named
 * or typed as the parameters (`Save(` -> `order, cancellationToken`). Behind [CSharpFeature.DOCUMENTATION] = NATIVE, as the parameter
 * info it comes from; with the switch on the server the module `roslyn` answers.
 */
class NativeCSharpLambdaGhost : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = ID
    override val providerPresentation = CSharpGhostTextProvider.presentation()

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val request = event.toRequest() ?: return false
        val file = request.file as? CSharpFile ?: return false
        if (!CSharpFeatures.native(CSharpFeature.DOCUMENTATION, file.project)) return false
        return CSharpLambdaNames.atArgumentStart(request.document.immutableCharSequence, request.endOffset)
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val file = request.file as? CSharpFile
        // the tree of the text with the `(` just typed: the semantics read the committed one
        val ghost = if (file == null) null else constrainedReadAction(ReadConstraint.withDocumentsCommitted(file.project)) {
            suggestion(file, request.document.immutableCharSequence, request.endOffset)
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

    companion object {
        val ID = InlineCompletionProviderID("io.github.dotnetsupport.nativeLambda")

        /** The gray text at [offset] of [file]: a lambda for a delegate parameter first, else the arguments at hand. */
        fun suggestion(file: CSharpFile, text: CharSequence, offset: Int): CSharpGhostText.Ghost? {
            if (!CSharpLambdaNames.atArgumentStart(text, offset) || DumbService.isDumb(file.project)) return null
            NativeCSharpLambdas.at(file, offset).firstOrNull()?.let { return CSharpGhostText.Ghost(SuggestionRules.LAMBDA, it.head) }
            val symbols = CSharpScopeTypes.at(text, offset)
            // a method of this very file: its parameters are in the text
            CSharpLocalCalls.at(text, offset)?.let { call -> CSharpArguments.list(call.parameters, call.active, symbols)?.let { return CSharpGhostText.Ghost(SuggestionRules.ARGUMENTS, it) } }
            val list = NativeCSharpParameterInfo.listAt(file, offset) ?: return null
            val rows = NativeCSharpParameterInfo.rows(file, list)
            val row = rows.firstOrNull { it.chosen } ?: rows.singleOrNull() ?: return null
            return CSharpArguments.list(row.parameters, NativeCSharpParameterInfo.argumentIndex(list, offset), symbols)?.let { CSharpGhostText.Ghost(SuggestionRules.ARGUMENTS, it) }
        }

        /**
         * The gray text of the arguments for the caret of [editor], asked for by the plugin itself: a method chosen in the completion list
         * is not typed, so nothing tells the inline completion that an argument list has just begun.
         */
        fun offer(editor: Editor) {
            ApplicationManager.getApplication().invokeLater({
                if (editor.isDisposed) return@invokeLater
                val handler = InlineCompletion.getHandlerOrNull(editor) ?: return@invokeLater
                runCatching { handler.invokeEvent(InlineCompletionEvent.ManualCall(editor, ID, UserDataHolderBase())) }
            }, ModalityState.nonModal())
        }
    }
}
