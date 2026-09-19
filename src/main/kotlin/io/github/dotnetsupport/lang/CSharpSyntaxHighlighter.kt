package io.github.dotnetsupport.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class CSharpSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = CSharpLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    companion object {
        val KEYWORD = createTextAttributesKey("CSHARP_KEYWORD", Default.KEYWORD)
        val STRING = createTextAttributesKey("CSHARP_STRING", Default.STRING)
        val NUMBER = createTextAttributesKey("CSHARP_NUMBER", Default.NUMBER)
        val LINE_COMMENT = createTextAttributesKey("CSHARP_LINE_COMMENT", Default.LINE_COMMENT)
        val BLOCK_COMMENT = createTextAttributesKey("CSHARP_BLOCK_COMMENT", Default.BLOCK_COMMENT)
        val DOC_COMMENT = createTextAttributesKey("CSHARP_DOC_COMMENT", Default.DOC_COMMENT)
        val PREPROCESSOR = createTextAttributesKey("CSHARP_PREPROCESSOR", Default.METADATA)
        val BRACES = createTextAttributesKey("CSHARP_BRACES", Default.BRACES)
        val PARENTHESES = createTextAttributesKey("CSHARP_PARENTHESES", Default.PARENTHESES)
        val BRACKETS = createTextAttributesKey("CSHARP_BRACKETS", Default.BRACKETS)
        val SEMICOLON = createTextAttributesKey("CSHARP_SEMICOLON", Default.SEMICOLON)
        val COMMA = createTextAttributesKey("CSHARP_COMMA", Default.COMMA)
        val DOT = createTextAttributesKey("CSHARP_DOT", Default.DOT)
        val OPERATOR = createTextAttributesKey("CSHARP_OPERATOR", Default.OPERATION_SIGN)
        val BAD_CHARACTER = createTextAttributesKey("CSHARP_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

        private val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            CSharpTokenTypes.KEYWORD to KEYWORD,
            CSharpTokenTypes.STRING to STRING,
            CSharpTokenTypes.CHAR to STRING,
            CSharpTokenTypes.NUMBER to NUMBER,
            CSharpTokenTypes.LINE_COMMENT to LINE_COMMENT,
            CSharpTokenTypes.BLOCK_COMMENT to BLOCK_COMMENT,
            CSharpTokenTypes.DOC_COMMENT to DOC_COMMENT,
            CSharpTokenTypes.PREPROCESSOR to PREPROCESSOR,
            CSharpTokenTypes.LBRACE to BRACES,
            CSharpTokenTypes.RBRACE to BRACES,
            CSharpTokenTypes.LPAREN to PARENTHESES,
            CSharpTokenTypes.RPAREN to PARENTHESES,
            CSharpTokenTypes.LBRACKET to BRACKETS,
            CSharpTokenTypes.RBRACKET to BRACKETS,
            CSharpTokenTypes.SEMICOLON to SEMICOLON,
            CSharpTokenTypes.COMMA to COMMA,
            CSharpTokenTypes.DOT to DOT,
            CSharpTokenTypes.OPERATOR to OPERATOR,
            TokenType.BAD_CHARACTER to BAD_CHARACTER,
        )
    }
}

class CSharpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        CSharpSyntaxHighlighter()
}
