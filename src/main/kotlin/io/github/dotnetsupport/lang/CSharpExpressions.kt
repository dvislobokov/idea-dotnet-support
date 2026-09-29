package io.github.dotnetsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The expression that ends at an offset, found by tokens: `person.Friend.Name`, `list[0]`, `Make(1, 2).Result`, `new Foo(x)`, `"text"`,
 * `await Load()`, `!ready`. What the postfix templates and the surrounders work on without a parser: a chain of names, calls, indexers
 * and literals; an operator, a keyword or a statement boundary ends it. Nothing here needs the types of anything.
 */
object CSharpExpressions {
    internal class Token(val type: IElementType, val text: String, val start: Int, val end: Int) {
        val isIdentifier get() = type == CSharpTokenTypes.IDENTIFIER
        val isLiteral get() = type == CSharpTokenTypes.STRING || type == CSharpTokenTypes.CHAR || type == CSharpTokenTypes.NUMBER
        fun isKeyword(vararg words: String) = type == CSharpTokenTypes.KEYWORD && text in words
        fun isOperator(symbol: String) = type == CSharpTokenTypes.OPERATOR && text == symbol
    }

    /** Keywords that are expressions or their parts: `this.Name`, `base.Total`, `default`, `null`, `true`. */
    private val EXPRESSION_KEYWORDS = arrayOf("this", "base", "null", "true", "false", "default", "typeof", "nameof", "sizeof", "int", "string", "bool", "double", "long", "object", "char", "byte", "float", "decimal", "short", "uint", "ulong", "var")

    internal fun tokenize(text: CharSequence): List<Token> {
        val lexer = CSharpLexer()
        lexer.start(text)
        val tokens = ArrayList<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                tokens += Token(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }

    /** The range of the expression ending exactly at [offset], or null when nothing expression-like ends there. */
    fun before(text: CharSequence, offset: Int): TextRange? {
        val tokens = tokenize(text)
        var i = tokens.indexOfLast { it.end == offset }
        if (i < 0) return null
        val end = tokens[i].end
        var start = -1
        // one operand: a name, a literal, a keyword, or a group closed by ) or ]
        fun operand(): Boolean {
            val token = tokens.getOrNull(i) ?: return false
            when {
                token.isIdentifier || token.isLiteral || token.isKeyword(*EXPRESSION_KEYWORDS) -> { start = token.start; i--; return true }
                token.type == CSharpTokenTypes.RPAREN || token.type == CSharpTokenTypes.RBRACKET -> {
                    val open = if (token.type == CSharpTokenTypes.RPAREN) CSharpTokenTypes.LPAREN else CSharpTokenTypes.LBRACKET
                    var depth = 0
                    while (i >= 0) {
                        val t = tokens[i]
                        if (t.type == token.type) depth++
                        if (t.type == open) { depth--; if (depth == 0) { start = t.start; i--; return true } }
                        i--
                    }
                    return false
                }
                else -> return false
            }
        }
        if (!operand()) return null
        // the chain continues to the left: `.Name`, the name of a call `Make(1)` or of an indexer `list[0]`, `?.`
        while (i >= 0) {
            val token = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                // `.Name` and `?.Name` (the lexer gives `?` and `.` apart)
                token.type == CSharpTokenTypes.DOT -> {
                    i--
                    if (tokens.getOrNull(i)?.isOperator("?") == true) i--
                    if (!operand()) break
                }
                (token.isIdentifier || token.isKeyword(*EXPRESSION_KEYWORDS)) && next != null && next.start == start && (next.type == CSharpTokenTypes.LPAREN || next.type == CSharpTokenTypes.LBRACKET) -> { start = token.start; i-- }
                else -> break
            }
        }
        // prefixes: `new Foo(...)`, `await Load()`, `!ready`, `-x`
        val prefix = tokens.getOrNull(i)
        if (prefix != null && (prefix.isKeyword("new", "await") || (prefix.isOperator("!") || prefix.isOperator("-")) && (i == 0 || isBoundary(tokens[i - 1])))) start = prefix.start
        return TextRange(start, end)
    }

    /** True when the expression at [start] is the beginning of a statement: after `;`, `{`, `}`, `=>` or the start of the file. */
    fun startsStatement(text: CharSequence, start: Int): Boolean {
        val before = tokenize(text.subSequence(0, start)).lastOrNull() ?: return true
        return isBoundary(before) || before.isOperator(">") && text.subSequence(0, before.end).endsWith("=>")
    }

    private fun isBoundary(token: Token): Boolean =
        token.type == CSharpTokenTypes.SEMICOLON || token.type == CSharpTokenTypes.LBRACE || token.type == CSharpTokenTypes.RBRACE ||
            token.isKeyword("return", "else", "do", "yield", "throw", "in")

    /** The whitespace at the start of the line [offset] is on. */
    fun indentAt(text: CharSequence, offset: Int): String {
        val lineStart = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
        var end = lineStart
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.substring(lineStart, end)
    }
}
