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
    /** Splits literals into text, escapes and holes ([CSharpHighlightingLexer]); [CSharpLexer] itself keeps them whole for everything else. */
    override fun getHighlightingLexer(): Lexer = CSharpHighlightingLexer()

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

        // inside literals: the keys of Rider (`ReSharper.CSHARP_ESCAPE_CHARACTER_1` / `_2`, `ReSharper.FORMAT_STRING_ITEM` / `_2`)
        val ESCAPE = createTextAttributesKey("CSHARP_ESCAPE_CHARACTER_1", Default.VALID_STRING_ESCAPE)
        val ESCAPE_2 = createTextAttributesKey("CSHARP_ESCAPE_CHARACTER_2", ESCAPE)
        val INVALID_ESCAPE = createTextAttributesKey("CSHARP_INVALID_ESCAPE_CHARACTER", Default.INVALID_STRING_ESCAPE)
        /** Alignment and format of a hole (`,5`, `:N2`) and an item of a format string (`{0,5:N2}` of `string.Format`, [CSharpFormatItemsAnnotator]). */
        val FORMAT_ITEM = createTextAttributesKey("CSHARP_FORMAT_STRING_ITEM", Default.VALID_STRING_ESCAPE)
        /** The second of two format items side by side: `{0}{1}`. */
        val FORMAT_ITEM_2 = createTextAttributesKey("CSHARP_FORMAT_STRING_ITEM_2", FORMAT_ITEM)

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
            CSharpStringTokens.TEXT to STRING,
            CSharpStringTokens.STRING_END to STRING,
            CSharpStringTokens.CHAR_END to STRING,
            CSharpStringTokens.ESCAPE to ESCAPE,
            CSharpStringTokens.ESCAPE_2 to ESCAPE_2,
            CSharpStringTokens.INVALID_ESCAPE to INVALID_ESCAPE,
            CSharpStringTokens.INTERPOLATION_BRACE to BRACES,
            CSharpStringTokens.FORMAT to FORMAT_ITEM,
        )
    }
}

class CSharpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        CSharpSyntaxHighlighter()
}
