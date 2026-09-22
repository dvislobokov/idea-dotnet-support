package io.github.dotnetsupport.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class CSharpCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class CSharpBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(CSharpTokenTypes.LBRACE, CSharpTokenTypes.RBRACE, true),
            BracePair(CSharpTokenTypes.LPAREN, CSharpTokenTypes.RPAREN, false),
            BracePair(CSharpTokenTypes.LBRACKET, CSharpTokenTypes.RBRACKET, false),
        )
    }
}

class CSharpQuoteHandler : SimpleTokenSetQuoteHandler(CSharpTokenTypes.STRINGS)
