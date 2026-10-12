package io.github.dotnetsupport.lang

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Extend / Shrink Selection (Ctrl+W) for C#: on top of the word and line the platform already offers, it adds the contents of every
 * enclosing `()`, `[]`, `{}` and the string / comment the caret is in, and the same with the brackets, so the selection grows structurally
 * without a parser. The platform merges these with the ranges of other handlers and picks the next larger one on each keystroke.
 * With `EDITING` native and a file of the native tree the ranges come from the tree instead ([NativeCSharpSelection]).
 */
class CSharpSelectioner : ExtendWordSelectionHandler {
    override fun canSelect(e: PsiElement): Boolean = e.containingFile is CSharpFile

    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): MutableList<TextRange> {
        val file = e.containingFile
        return if (NativeCSharpEditing.usable(file)) NativeCSharpSelection.ranges(file as CSharpFile, cursorOffset) else CSharpSelection.ranges(editorText, cursorOffset)
    }
}

object CSharpSelection {
    /** The content and the whole span of every bracket pair enclosing the caret, plus the string / comment token it is in. */
    fun ranges(text: CharSequence, caret: Int): MutableList<TextRange> {
        val result = ArrayList<TextRange>()
        val lexer = CSharpLexer()
        lexer.start(text)
        // a stack of (start, end) of the open brackets seen so far; the matching close pairs with the nearest open
        val opens = ArrayDeque<Pair<Int, Int>>()
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            when (type) {
                CSharpTokenTypes.LBRACE, CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET -> opens.addLast(start to end)
                CSharpTokenTypes.RBRACE, CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET -> {
                    val open = opens.removeLastOrNull()
                    if (open != null && caret >= open.first && caret <= end) {
                        if (open.second < start) result.add(TextRange(open.second, start)) // inside the brackets
                        result.add(TextRange(open.first, end)) // with the brackets
                    }
                }
                CSharpTokenTypes.STRING, CSharpTokenTypes.CHAR, CSharpTokenTypes.LINE_COMMENT, CSharpTokenTypes.BLOCK_COMMENT, CSharpTokenTypes.DOC_COMMENT ->
                    if (caret > start && caret < end) result.add(TextRange(start, end))
                else -> {}
            }
            lexer.advance()
        }
        return result
    }
}
