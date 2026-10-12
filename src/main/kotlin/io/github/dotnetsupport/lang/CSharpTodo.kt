package io.github.dotnetsupport.lang

import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.cache.impl.BaseFilterLexer
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer
import com.intellij.psi.impl.cache.impl.todo.LexerBasedTodoIndexer
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * TODO / FIXME of C# comments for the TODO tool window, its counts and the highlighting in the editor: the index counts them over the tokens
 * of the lexer ([CSharpTodoIndexer]), the search finds them in a file ([CSharpIndexPatternBuilder]). Only comments: a `TODO` in a string or a
 * name is not one. The patterns are those of Settings | Editor | TODO.
 */
class CSharpIndexPatternBuilder : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? = if (file is CSharpFile) CSharpLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? = if (file is CSharpFile) CSharpTokenTypes.COMMENTS else null

    /** Where the text of a comment starts: after its two or three slashes, or after the slash and the star of a block comment. */
    override fun getCommentStartDelta(tokenType: IElementType): Int = CSharpTodo.startDelta(tokenType)

    override fun getCommentEndDelta(tokenType: IElementType): Int = CSharpTodo.endDelta(tokenType)
}

class CSharpTodoIndexer : LexerBasedTodoIndexer() {
    override fun createLexer(consumer: OccurrenceConsumer): Lexer = CSharpTodoLexer(consumer)
}

/** Counts the TODO patterns in the comments, nothing else: the index of words has its own scanner. */
private class CSharpTodoLexer(consumer: OccurrenceConsumer) : BaseFilterLexer(CSharpLexer(), consumer) {
    override fun advance() {
        if (myDelegate.tokenType in CSharpTokenTypes.COMMENTS) advanceTodoItemCountsInToken()
        myDelegate.advance()
    }
}

object CSharpTodo {
    fun startDelta(tokenType: IElementType): Int = when (tokenType) {
        CSharpTokenTypes.DOC_COMMENT -> 3
        CSharpTokenTypes.LINE_COMMENT, CSharpTokenTypes.BLOCK_COMMENT -> 2
        else -> 0
    }

    fun endDelta(tokenType: IElementType): Int = if (tokenType == CSharpTokenTypes.BLOCK_COMMENT) 2 else 0
}
