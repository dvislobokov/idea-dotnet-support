package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Completion of the server with what a client of Roslyn adds itself, as Rider and VS Code do: a method comes as its bare name
 * (`WriteLine`, plain text), so a chosen method got no parentheses, the caret stayed after the name and Ctrl+P had no argument list to
 * show the overloads for (reported). Now `()` go after it with the caret inside, and the parameter info pops up at once.
 */
class RoslynCompletionSupport : LspCompletionSupport() {
    override fun createLookupElement(parameters: CompletionParameters, item: CompletionItem): LookupElement? {
        val element = super.createLookupElement(parameters, item) ?: return null
        if (!RoslynCompletionPolicy.isCallable(item.kind)) return element
        // the insertion of the platform first (the text edit of the item, the `using` of its resolve), then the parentheses
        return LookupElementDecorator.withInsertHandler(element) { context: InsertionContext, decorator: LookupElementDecorator<LookupElement> ->
            decorator.delegate.handleInsert(context)
            addParentheses(context)
        }
    }

    private fun addParentheses(context: InsertionContext) {
        val document = context.document
        val offset = context.tailOffset
        val text = document.charsSequence
        val start = context.startOffset
        if (!RoslynCompletionPolicy.addsParentheses(context.completionChar, text, start, offset)) return
        // "(" typed to choose the item: it is the one of the pair, not a second one
        if (context.completionChar == '(') context.setAddCompletionChar(false)
        if (offset < text.length && text[offset] == '(') {
            context.editor.caretModel.moveToOffset(offset + 1)
        } else {
            document.insertString(offset, "()")
            context.editor.caretModel.moveToOffset(offset + 1)
        }
        context.commitDocument()
        AutoPopupController.getInstance(context.project).autoPopupParameterInfo(context.editor, null)
    }
}

object RoslynCompletionPolicy {
    private val CALLABLE = setOf(CompletionItemKind.Method, CompletionItemKind.Function)
    private val SUBSCRIPTION = Regex("""[+-]=\s*$""")

    /** Methods, extension methods included (Roslyn sends them as `Method`); a constructor comes as its type, which is not called by name. */
    fun isCallable(kind: CompletionItemKind?): Boolean = kind in CALLABLE

    /**
     * Whether a chosen method gets `()`. Only when it is chosen by Enter, Tab or `(` — a `.` or `;` typed to choose it means the user goes
     * on typing as they want. Not after `+=` / `-=`: that subscribes the method to an event, a method group without a call.
     */
    fun addsParentheses(completionChar: Char, text: CharSequence, nameStart: Int, nameEnd: Int): Boolean {
        if (completionChar != Lookup.NORMAL_SELECT_CHAR && completionChar != Lookup.REPLACE_SELECT_CHAR && completionChar != '(') return false
        if (nameStart !in 0..text.length || nameEnd !in nameStart..text.length) return false
        val lineStart = text.lastIndexOf('\n', nameStart - 1) + 1
        return !SUBSCRIPTION.containsMatchIn(text.subSequence(lineStart, nameStart))
    }
}
