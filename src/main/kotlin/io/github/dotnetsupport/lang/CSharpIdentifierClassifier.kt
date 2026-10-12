package io.github.dotnetsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

enum class IdentifierKind { TYPE, METHOD, MEMBER }

/**
 * Guesses what identifiers are from the tokens around them. There is no parser or symbol resolution, so this is
 * a heuristic tuned for idiomatic code: `new Foo`, `Foo bar`, `[Foo]`, `Console.` are types, `Foo(` is a method,
 * `.Foo` is a member. Locals, parameters and everything unclear keep the default color.
 */
object CSharpIdentifierClassifier {
    private class Token(val type: IElementType, val text: String, val start: Int, val end: Int) {
        fun isKeyword(vararg words: String) = type == CSharpTokenTypes.KEYWORD && text in words
        fun isOperator(symbol: String) = type == CSharpTokenTypes.OPERATOR && text == symbol
        val isIdentifier get() = type == CSharpTokenTypes.IDENTIFIER
    }

    private val TYPE_DECLARATION = arrayOf("class", "struct", "interface", "enum", "record")

    // Query keywords and other contextual words the lexer reports as identifiers: `from x in xs` is not `Type name`.
    private val NOT_TYPES = setOf(
        "from", "select", "orderby", "group", "join", "let", "into", "on", "equals", "by", "ascending", "descending",
        "value", "field", "file", "scoped", "unmanaged", "notnull", "managed", "add", "remove", "alias", "extern",
    )
    private const val MAX_GENERIC_TOKENS = 48

    fun classify(text: CharSequence): List<Pair<TextRange, IdentifierKind>> {
        val tokens = tokenize(text)
        val kinds = arrayOfNulls<IdentifierKind>(tokens.size)

        var typeDeclared = false   // `using` before the first type is a directive, after it is a statement
        var inDirective = false    // using / namespace: dotted names are namespaces, not types
        var inTypeHeader = false   // between `class Foo` and its `{`
        var inBaseList = false

        for (i in tokens.indices) {
            val token = tokens[i]
            val previous = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)

            when {
                token.isKeyword("namespace") -> inDirective = true
                token.isKeyword("using") && !typeDeclared && (previous == null || previous.type == CSharpTokenTypes.SEMICOLON || previous.isKeyword("global")) ->
                    inDirective = next?.type != CSharpTokenTypes.LPAREN
                token.isKeyword(*TYPE_DECLARATION) && next?.let { it.isIdentifier || it.isKeyword("struct", "class") } == true -> {
                    typeDeclared = true
                    inTypeHeader = true
                    inBaseList = false
                }
                token.type == CSharpTokenTypes.SEMICOLON || token.type == CSharpTokenTypes.LBRACE -> {
                    inDirective = false
                    inTypeHeader = false
                    inBaseList = false
                }
                inTypeHeader && token.isOperator(":") -> inBaseList = true
                inTypeHeader && token.isKeyword("where") -> inBaseList = false
            }
            if (!token.isIdentifier || kinds[i] != null) continue
            if (inDirective) continue

            val genericEnd = if (next?.isOperator("<") == true) genericArgumentsEnd(tokens, i + 1) else -1
            val afterGeneric = if (genericEnd >= 0) tokens.getOrNull(genericEnd + 1) else null

            val kind: IdentifierKind? = when {
                previous?.isKeyword(*TYPE_DECLARATION) == true -> IdentifierKind.TYPE
                inBaseList -> if (token.text in NOT_TYPES) null else IdentifierKind.TYPE // `where T : notnull`
                previous?.isKeyword("new", "is", "as") == true -> IdentifierKind.TYPE
                previous?.type == CSharpTokenTypes.LPAREN && tokens.getOrNull(i - 2)?.isKeyword("typeof", "default", "sizeof") == true -> IdentifierKind.TYPE
                previous?.type == CSharpTokenTypes.LBRACKET && isAttributeBracket(tokens, i - 1) -> IdentifierKind.TYPE

                next?.type == CSharpTokenTypes.LPAREN -> IdentifierKind.METHOD
                afterGeneric?.type == CSharpTokenTypes.LPAREN -> IdentifierKind.METHOD                  // Get<T>(
                afterGeneric != null && isDeclaredName(afterGeneric) -> IdentifierKind.TYPE             // List<int> items
                afterGeneric != null && previous?.type != CSharpTokenTypes.DOT &&
                    (afterGeneric.type == CSharpTokenTypes.DOT || afterGeneric.type == CSharpTokenTypes.LBRACKET) -> IdentifierKind.TYPE

                token.text in NOT_TYPES -> null
                next != null && isDeclaredName(next) -> IdentifierKind.TYPE                             // Stream input
                isNullableOrArrayDeclaration(tokens, i) -> IdentifierKind.TYPE                          // Foo? x, Foo[] xs

                previous?.type == CSharpTokenTypes.DOT -> IdentifierKind.MEMBER
                next?.type == CSharpTokenTypes.DOT && token.text.first().isUpperCase() -> IdentifierKind.TYPE // Console.
                else -> null
            }
            kinds[i] = kind
            // the arguments of a generic type or method are types
            if (genericEnd >= 0 && (kind == IdentifierKind.TYPE || kind == IdentifierKind.METHOD)) {
                for (j in i + 2 until genericEnd) if (tokens[j].isIdentifier) kinds[j] = IdentifierKind.TYPE
            }
        }
        return tokens.indices.mapNotNull { i -> kinds[i]?.let { TextRange(tokens[i].start, tokens[i].end) to it } }
    }

    private fun tokenize(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                tokens += Token(type, lexer.tokenText, lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }

    /** A name being declared: an identifier that is not a query keyword. `Foo bar`, `Foo Bar(`, `Foo Bar {`. */
    private fun isDeclaredName(token: Token): Boolean = token.isIdentifier && token.text !in NOT_TYPES

    /** Index of the `>` closing the generic argument list that starts at [open], or -1 when it is a comparison. */
    private fun genericArgumentsEnd(tokens: List<Token>, open: Int): Int {
        var depth = 0
        for (i in open until minOf(tokens.size, open + MAX_GENERIC_TOKENS)) {
            val token = tokens[i]
            when {
                token.isOperator("<") -> depth++
                token.isOperator(">") -> if (--depth == 0) return i
                token.isIdentifier || token.type == CSharpTokenTypes.COMMA || token.type == CSharpTokenTypes.DOT ||
                    token.type == CSharpTokenTypes.LBRACKET || token.type == CSharpTokenTypes.RBRACKET || token.isOperator("?") ||
                    token.type == CSharpTokenTypes.LPAREN || token.type == CSharpTokenTypes.RPAREN || // tuples
                    (token.type == CSharpTokenTypes.KEYWORD && token.text !in setOf("new", "is", "as", "return")) -> {}
                else -> return -1
            }
        }
        return -1
    }

    /** `Foo? x` (but not the ternary `a ? b : c`) and `Foo[] xs`, `Foo[,] grid`. */
    private fun isNullableOrArrayDeclaration(tokens: List<Token>, index: Int): Boolean {
        var i = index + 1
        if (tokens.getOrNull(i)?.isOperator("?") == true) {
            val name = tokens.getOrNull(i + 1) ?: return false
            val after = tokens.getOrNull(i + 2) ?: return false
            return isDeclaredName(name) && (after.isOperator("=") || after.type in DECLARATION_END || after.type == CSharpTokenTypes.LPAREN || after.type == CSharpTokenTypes.LBRACE)
        }
        if (tokens.getOrNull(i)?.type != CSharpTokenTypes.LBRACKET) return false
        i++
        while (tokens.getOrNull(i)?.type == CSharpTokenTypes.COMMA) i++
        return tokens.getOrNull(i)?.type == CSharpTokenTypes.RBRACKET && tokens.getOrNull(i + 1)?.let(::isDeclaredName) == true
    }

    /** `[` opens an attribute list unless it follows something indexable. */
    private fun isAttributeBracket(tokens: List<Token>, bracket: Int): Boolean {
        val before = tokens.getOrNull(bracket - 1) ?: return true
        return when (before.type) {
            CSharpTokenTypes.IDENTIFIER, CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET, CSharpTokenTypes.STRING,
            CSharpTokenTypes.NUMBER, CSharpTokenTypes.OPERATOR, CSharpTokenTypes.DOT -> false
            CSharpTokenTypes.KEYWORD -> before.text !in setOf("new", "this", "base", "return", "in", "is") && before.text !in PREDEFINED_TYPES
            else -> true
        }
    }

    private val DECLARATION_END = setOf(CSharpTokenTypes.SEMICOLON, CSharpTokenTypes.COMMA, CSharpTokenTypes.RPAREN)
    private val PREDEFINED_TYPES = setOf(
        "bool", "byte", "sbyte", "char", "decimal", "double", "float", "int", "uint", "long", "ulong", "short", "ushort", "object", "string",
    )
}
