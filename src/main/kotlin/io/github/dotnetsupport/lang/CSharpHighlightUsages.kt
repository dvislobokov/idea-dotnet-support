package io.github.dotnetsupport.lang

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer
import io.github.dotnetsupport.lsp.RoslynServerStatus

/**
 * Highlights the other occurrences of the identifier under the caret, by matching the identifier token's text in the file. A fallback for
 * while the Roslyn server does not yet cover the file (`documentHighlight` is wired and more accurate once it does, so this yields then).
 */
class CSharpHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactoryBase() {
    override fun createHighlightUsagesHandler(editor: Editor, psiFile: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (psiFile !is CSharpFile) return null
        val file = psiFile.virtualFile
        if (file != null && RoslynServerStatus.covers(psiFile.project, file)) return null // the server answers with documentHighlight
        val ranges = CSharpUsageHighlights.occurrences(editor.document.immutableCharSequence, editor.caretModel.offset)
        return if (ranges.isEmpty()) null else Handler(editor, psiFile, ranges)
    }

    private class Handler(editor: Editor, private val psiFile: PsiFile, private val ranges: List<TextRange>) :
        HighlightUsagesHandlerBase<PsiElement>(editor, psiFile) {
        override fun getTargets(): List<PsiElement> = listOf(psiFile)
        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) = selectionConsumer.consume(targets)
        override fun computeUsages(targets: List<PsiElement>) {
            ranges.forEach(myReadUsages::add)
        }
    }
}

object CSharpUsageHighlights {
    /** Ranges of every identifier token with the same text as the one the caret is on; empty when the caret is not on an identifier. */
    fun occurrences(text: CharSequence, caret: Int): List<TextRange> {
        val tokens = CSharpExpressions.tokenize(text)
        val at = tokens.firstOrNull { it.isIdentifier && caret >= it.start && caret <= it.end } ?: return emptyList()
        return tokens.filter { it.isIdentifier && it.text == at.text }.map { TextRange(it.start, it.end) }
    }
}
