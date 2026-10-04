package io.github.dotnetsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * What a usage of a symbol does, as the groups of Find Usages in Rider show it. The C# server answers `textDocument/references` with bare
 * locations (no kind: the kinds Roslyn knows go only to a client that says it is Visual Studio, see tools/roslyn-lsp/README.md), so the kind
 * is read from the tokens around the location. The names follow Rider; Read / Write / Name match the kinds Roslyn gives Visual Studio.
 */
enum class CSharpUsageKind(val title: String) {
    DECLARATION("Declaration"),
    READ("Read access"),
    WRITE("Write access"),
    INVOCATION("Invocation"),
    NAMEOF("Usage in nameof"),
    ATTRIBUTE("Usage in attribute"),
    NEW("New instance creation"),
    BASE_TYPE("Usage in base type list"),
    USING("Usage in using directive"),
    TYPEOF("Usage in typeof"),
    TYPE_CHECK("Type check (is / as)"),
    CAST("Type cast"),
    TYPE_ARGUMENT("Usage in type argument"),
    DECLARATION_TYPE("Usage in declaration type"),
    COMMENT("Usage in documentation"),
    STRING("Usage in string"),
}

/**
 * The kind of a usage by its neighbours, without a parser: `x = ` and `x += ` write, `x++` and `ref x` / `out x` write, `x(` calls,
 * `new X`, `typeof(X)`, `nameof(x)`, `is X`, `(X)y`, `: X` in a type header, `[X(...)]`, `List<X>`, `X name`. Whatever else is a read.
 * One [Analysis] per text: the file is lexed once for all of its usages.
 */
object CSharpUsageKinds {
    fun classify(text: CharSequence, range: TextRange, structure: CSharpFileStructure = CSharpSyntaxModel.current.declarations(text)): CSharpUsageKind = Analysis(text, structure).kindOf(range)

    internal class Token(val type: IElementType, val text: String, val start: Int, val end: Int) {
        val isIdentifier get() = type == CSharpTokenTypes.IDENTIFIER
        fun isKeyword(vararg words: String) = type == CSharpTokenTypes.KEYWORD && text in words
        fun isOperator(symbol: String) = type == CSharpTokenTypes.OPERATOR && text == symbol
    }

    /** Words of query expressions: the lexer has them as identifiers, and `select x` is not a declaration of `x`. */
    private val QUERY_WORDS = setOf("select", "orderby", "by", "on", "equals", "ascending", "descending", "group", "into", "await", "from", "let", "join")

    /** Keywords that name a type: `int x`, `string? y`. */
    private val TYPE_KEYWORDS = setOf("bool", "byte", "char", "decimal", "double", "dynamic", "float", "int", "long", "object", "sbyte", "short", "string", "uint", "ulong", "ushort", "var", "void")

    class Analysis(private val text: CharSequence, private val structure: CSharpFileStructure = CSharpSyntaxModel.current.declarations(text)) {
        /** Every token but white space; comments and strings stay, so a usage inside one is told apart. */
        private val all: List<Token> = lex(text)
        /** The code alone: what the neighbours of a usage are looked for in. */
        private val code: List<Token> = all.filter { it.type !in CSharpTokenTypes.COMMENTS && it.type != CSharpTokenTypes.PREPROCESSOR }
        private val declarationNames: Set<TextRange> = structure.all().filter { it.kind != DeclarationKind.NAMESPACE }.mapTo(HashSet()) { it.nameRange }

        fun kindOf(range: TextRange): CSharpUsageKind {
            val token = tokenAt(all, range.startOffset) ?: return CSharpUsageKind.READ
            if (token.type in CSharpTokenTypes.COMMENTS) return CSharpUsageKind.COMMENT
            if (token.type in CSharpTokenTypes.STRINGS) return if (token.text.trimStart('@').startsWith("$")) inInterpolation(range) else CSharpUsageKind.STRING
            if (range in declarationNames || TextRange(token.start, token.end) in declarationNames) return CSharpUsageKind.DECLARATION
            val at = code.indexOfFirst { it.start == token.start }.takeIf { it >= 0 } ?: return CSharpUsageKind.READ
            return Context(at).kind()
        }

        /** `$"{X}"`, `$"{X(1)}"`: the lexer keeps an interpolated string whole, so only a call is told from a read there. */
        private fun inInterpolation(range: TextRange): CSharpUsageKind {
            var i = range.endOffset
            while (i < text.length && text[i].isWhitespace()) i++
            return if (i < text.length && text[i] == '(') CSharpUsageKind.INVOCATION else CSharpUsageKind.READ
        }

        private inner class Context(private val at: Int) {
            /** The first token of `this.a.b.X`: the qualifiers before the usage belong to it. */
            private val first: Int = run {
                var i = at
                while (i >= 2 && code[i - 1].type == CSharpTokenTypes.DOT && (code[i - 2].isIdentifier || code[i - 2].isKeyword("this", "base", *TYPE_KEYWORDS.toTypedArray()))) i -= 2
                // `a?.X`
                while (i >= 3 && code[i - 1].type == CSharpTokenTypes.DOT && code[i - 2].isOperator("?") && code[i - 3].isIdentifier) i -= 3
                i
            }
            /** The last token of the usage: its type arguments `X<int, string>` included. */
            private val last: Int = typeArgumentsEnd(at) ?: at

            private fun token(index: Int): Token? = code.getOrNull(index)
            private val before: Token? get() = token(first - 1)
            private val after: Token? get() = token(last + 1)

            fun kind(): CSharpUsageKind = when {
                inParenthesesOf("nameof") -> CSharpUsageKind.NAMEOF
                inUsingDirective() -> CSharpUsageKind.USING
                isAttributeName() -> CSharpUsageKind.ATTRIBUTE
                before?.isKeyword("new") == true -> CSharpUsageKind.NEW
                inParenthesesOf("typeof") -> CSharpUsageKind.TYPEOF
                before?.isKeyword("is", "as") == true || before?.isKeyword("not") == true && token(first - 2)?.isKeyword("is") == true -> CSharpUsageKind.TYPE_CHECK
                inBaseList() -> CSharpUsageKind.BASE_TYPE
                isCast() -> CSharpUsageKind.CAST
                inTypeArguments() -> CSharpUsageKind.TYPE_ARGUMENT
                isLocalDeclaration() -> CSharpUsageKind.DECLARATION
                before?.isKeyword("ref", "out") == true -> CSharpUsageKind.WRITE
                isPrefixIncrement() || isAssigned() || isPostfixIncrement() -> CSharpUsageKind.WRITE
                after?.type == CSharpTokenTypes.LPAREN -> CSharpUsageKind.INVOCATION
                isDeclarationType() -> CSharpUsageKind.DECLARATION_TYPE
                else -> CSharpUsageKind.READ
            }

            /** `nameof(X)`, `nameof(A.X)`, `typeof(X)`. */
            private fun inParenthesesOf(keyword: String): Boolean {
                if (before?.type != CSharpTokenTypes.LPAREN || token(first - 2)?.isKeyword(keyword) != true) return false
                // the usage may be the qualifier: `nameof(X.Count)`, `typeof(X.Nested)`
                var i = last + 1
                while (token(i)?.type == CSharpTokenTypes.DOT && token(i + 1)?.isIdentifier == true) i = typeArgumentsEnd(i + 1)?.plus(1) ?: (i + 2)
                return token(i)?.type == CSharpTokenTypes.RPAREN
            }

            /** `using X;`, `using static A.X;`, `using Alias = A.X;`, `global using X;` — and not the `using (var x = ...)` statement. */
            private fun inUsingDirective(): Boolean {
                var i = first - 1
                while (i >= 0) {
                    val token = code[i]
                    when {
                        token.type == CSharpTokenTypes.SEMICOLON || token.type == CSharpTokenTypes.LBRACE || token.type == CSharpTokenTypes.RBRACE -> return false
                        token.type == CSharpTokenTypes.LPAREN || token.type == CSharpTokenTypes.RPAREN -> return false
                        token.isKeyword("using") -> return token(i - 1).let { it == null || it.type == CSharpTokenTypes.SEMICOLON || it.type == CSharpTokenTypes.RBRACE || it.type == CSharpTokenTypes.LBRACE || it.isKeyword("global") }
                    }
                    i--
                }
                return false
            }

            /** `[X]`, `[X(...)]`, `[A, X(...)]`, `[return: X]`: the name right after `[` or `,` of an attribute list, before `(`, `]` or `,`. */
            private fun isAttributeName(): Boolean {
                val next = after ?: return false
                if (next.type != CSharpTokenTypes.LPAREN && next.type != CSharpTokenTypes.RBRACKET && next.type != CSharpTokenTypes.COMMA) return false
                var i = first - 1
                while (i >= 0) {
                    val token = code[i]
                    when {
                        token.type == CSharpTokenTypes.LBRACKET -> return startsAttributeList(i)
                        token.isOperator(":") && token(i - 1)?.isKeyword("return", "assembly", "module", "field", "method", "param", "property", "type", "event") == true -> i -= 2
                        token.type == CSharpTokenTypes.COMMA -> i = skipAttributeBackwards(i - 1) ?: return false
                        else -> return false
                    }
                }
                return false
            }

            /** From the end of `X(...)` or `X` before a `,` back to the token before it. */
            private fun skipAttributeBackwards(from: Int): Int? {
                var i = from
                if (token(i)?.type == CSharpTokenTypes.RPAREN) i = matchingOpen(i, CSharpTokenTypes.LPAREN, CSharpTokenTypes.RPAREN)?.minus(1) ?: return null
                while (i >= 0 && (code[i].isIdentifier || code[i].type == CSharpTokenTypes.DOT)) i--
                return i
            }

            /**
             * A `[` that opens attributes: after `;`, `{`, `}`, `]`, or after `(` / `,` of a parameter list when a type follows the `]`
             * (`M([FromBody] Order order)`); not after an expression (`a[`), `=`, or a collection expression passed as an argument (`M([a, b])`).
             */
            private fun startsAttributeList(bracket: Int): Boolean {
                val previous = token(bracket - 1) ?: return true
                if (previous.type == CSharpTokenTypes.LPAREN || previous.type == CSharpTokenTypes.COMMA) {
                    val close = matchingClose(bracket) ?: return false
                    return token(close + 1)?.let { it.isIdentifier || it.type == CSharpTokenTypes.KEYWORD && (it.text in TYPE_KEYWORDS || it.text in setOf("ref", "out", "in", "params", "this")) } == true
                }
                return previous.type == CSharpTokenTypes.SEMICOLON || previous.type == CSharpTokenTypes.LBRACE || previous.type == CSharpTokenTypes.RBRACE ||
                    previous.type == CSharpTokenTypes.RBRACKET
            }

            private fun matchingClose(open: Int): Int? {
                var depth = 0
                for (i in open until code.size) {
                    when (code[i].type) {
                        CSharpTokenTypes.LBRACKET -> depth++
                        CSharpTokenTypes.RBRACKET -> if (--depth == 0) return i
                    }
                }
                return null
            }

            /** `class A : X`, `class A : B, X`, `interface I<T> : X<T> where T : Y` — the header of a type before its `{`. */
            private fun inBaseList(): Boolean {
                var i = first - 1
                var depth = 0
                var colon = false
                while (i >= 0) {
                    val token = code[i]
                    when {
                        token.type == CSharpTokenTypes.RPAREN || token.isOperator(">") -> depth++
                        token.type == CSharpTokenTypes.LPAREN || token.isOperator("<") -> if (depth == 0) return false else depth--
                        depth > 0 -> {}
                        token.type == CSharpTokenTypes.SEMICOLON || token.type == CSharpTokenTypes.LBRACE || token.type == CSharpTokenTypes.RBRACE || token.isOperator("=") -> return false
                        token.isKeyword("where") -> return false
                        token.isOperator(":") -> colon = true
                        token.isKeyword("class", "struct", "interface", "record", "enum") -> return colon
                    }
                    i--
                }
                return false
            }

            /** `(X)value`, `(X?)value`, `(A.X)value`: parentheses around the type alone, and something to convert right after. */
            private fun isCast(): Boolean {
                if (before?.type != CSharpTokenTypes.LPAREN) return false
                var close = last + 1
                while (token(close)?.let { it.isOperator("?") || it.type == CSharpTokenTypes.LBRACKET || it.type == CSharpTokenTypes.RBRACKET } == true) close++
                if (token(close)?.type != CSharpTokenTypes.RPAREN) return false
                val operand = token(close + 1) ?: return false
                val call = token(first - 2)?.let { it.isIdentifier || it.type == CSharpTokenTypes.RPAREN || it.type == CSharpTokenTypes.RBRACKET || it.isKeyword("if", "while", "for", "foreach", "switch", "using", "lock", "return", "catch", "when") } == true
                return !call && (operand.isIdentifier || operand.type == CSharpTokenTypes.LPAREN || operand.type in CSharpTokenTypes.STRINGS || operand.type == CSharpTokenTypes.NUMBER ||
                    operand.isKeyword("this", "base", "new", "null", "default", "true", "false", "typeof", "await"))
            }

            /** `List<X>`, `Dictionary<string, X>`, `Make<X>()`. */
            private fun inTypeArguments(): Boolean {
                var i = first - 1
                while (i >= 0) {
                    val token = code[i]
                    when {
                        token.isOperator("<") -> return code.getOrNull(i - 1)?.isIdentifier == true && typeArgumentsEnd(i - 1) != null
                        token.type == CSharpTokenTypes.COMMA || token.type == CSharpTokenTypes.DOT || token.isIdentifier || token.isOperator("?") ||
                            token.type == CSharpTokenTypes.LBRACKET || token.type == CSharpTokenTypes.RBRACKET || token.type == CSharpTokenTypes.KEYWORD && token.text in TYPE_KEYWORDS -> i--
                        token.isOperator(">") -> i = matchingOpenAngle(i)?.minus(1) ?: return false
                        else -> return false
                    }
                }
                return false
            }

            /** `int x = `, `Order order;`, `List<int> items,` `(string name)`, `foreach (var item in`, `int Twice(` — locals, parameters and local functions the declaration scanner does not see. */
            private fun isLocalDeclaration(): Boolean {
                if (first != at) return false
                val previous = before ?: return false
                val next = after
                val typeBefore = previous.isIdentifier && previous.text !in QUERY_WORDS ||
                    previous.type == CSharpTokenTypes.KEYWORD && previous.text in TYPE_KEYWORDS ||
                    previous.isOperator("?") && token(first - 2)?.let { it.isIdentifier || it.type == CSharpTokenTypes.KEYWORD && it.text in TYPE_KEYWORDS || it.isOperator(">") } == true && adjacent(first - 2, first - 1) ||
                    previous.isOperator(">") && matchingOpenAngle(first - 1) != null ||
                    previous.type == CSharpTokenTypes.RBRACKET && token(first - 2)?.type == CSharpTokenTypes.LBRACKET
                if (!typeBefore) return false
                // `(`: a local function, `int Twice(int x) => ...`
                return next == null || next.type == CSharpTokenTypes.SEMICOLON || next.type == CSharpTokenTypes.COMMA || next.type == CSharpTokenTypes.RPAREN ||
                    next.type == CSharpTokenTypes.LPAREN || next.isKeyword("in") || next.isOperator("=") && !isOperatorPair(last + 1, "=") && !isOperatorPair(last + 1, ">")
            }

            /** `x = `, `x += `, `x ??= `, `x <<= `: an assignment operator right after the usage, and not `==` or `=>`. */
            private fun isAssigned(): Boolean {
                val next = last + 1
                val operator = token(next) ?: return false
                if (operator.type != CSharpTokenTypes.OPERATOR) return false
                if (operator.text == "=") return !isOperatorPair(next, "=") && !isOperatorPair(next, ">")
                // the operator ends with `=` right after it: `+=`, `??=`, `<<=` (but not `<=`, `>=`, `!=`)
                var i = next
                while (token(i + 1)?.let { it.type == CSharpTokenTypes.OPERATOR && adjacent(i, i + 1) } == true) i++
                if (i == next || !token(i)!!.isOperator("=") || isOperatorPair(i, "=")) return false
                val symbols = (next until i).joinToString("") { code[it].text }
                return symbols in setOf("+", "-", "*", "/", "%", "&", "|", "^", "??", "<<", ">>", ">>>")
            }

            private fun isPostfixIncrement(): Boolean = after?.let { it.isOperator("+") || it.isOperator("-") } == true && isOperatorPair(last + 1, after!!.text)

            private fun isPrefixIncrement(): Boolean {
                val previous = before ?: return false
                if (!previous.isOperator("+") && !previous.isOperator("-")) return false
                return token(first - 2)?.isOperator(previous.text) == true && adjacent(first - 2, first - 1)
            }

            /** `X name`, `X? name`, `X[] name`, `X<int> name`: a type in the declaration of something. */
            private fun isDeclarationType(): Boolean {
                var i = last + 1
                while (token(i)?.let { it.isOperator("?") || it.type == CSharpTokenTypes.LBRACKET && token(i + 1)?.type == CSharpTokenTypes.RBRACKET || it.type == CSharpTokenTypes.RBRACKET } == true) i++
                val name = token(i) ?: return false
                return name.isIdentifier && name.text !in QUERY_WORDS || name.isKeyword("this", "operator") && i > last + 1
            }

            /** The token at [index] is the first half of a two-character operator: `==`, `=>`, `++`. */
            private fun isOperatorPair(index: Int, second: String): Boolean = token(index + 1)?.isOperator(second) == true && adjacent(index, index + 1)

            private fun adjacent(left: Int, right: Int): Boolean = code[left].end == code[right].start
        }

        /** The index of `>` closing the type arguments right after the name at [name], when there are any. */
        private fun typeArgumentsEnd(name: Int): Int? {
            if (code.getOrNull(name + 1)?.isOperator("<") != true) return null
            var depth = 0
            var i = name + 1
            while (i < code.size) {
                val token = code[i]
                when {
                    token.isOperator("<") -> depth++
                    token.isOperator(">") -> if (--depth == 0) return i
                    token.isIdentifier || token.type == CSharpTokenTypes.COMMA || token.type == CSharpTokenTypes.DOT || token.isOperator("?") ||
                        token.type == CSharpTokenTypes.LBRACKET || token.type == CSharpTokenTypes.RBRACKET || token.type == CSharpTokenTypes.KEYWORD && token.text in TYPE_KEYWORDS -> {}
                    else -> return null
                }
                i++
            }
            return null
        }

        /** The index of `<` that the `>` at [close] closes, when what is between looks like type arguments (and not like `a > b`). */
        private fun matchingOpenAngle(close: Int): Int? {
            var depth = 0
            var i = close
            while (i >= 0) {
                val token = code[i]
                when {
                    token.isOperator(">") -> depth++
                    token.isOperator("<") -> if (--depth == 0) return i.takeIf { code.getOrNull(it - 1)?.isIdentifier == true }
                    token.isIdentifier || token.type == CSharpTokenTypes.COMMA || token.type == CSharpTokenTypes.DOT || token.isOperator("?") ||
                        token.type == CSharpTokenTypes.LBRACKET || token.type == CSharpTokenTypes.RBRACKET || token.type == CSharpTokenTypes.KEYWORD && token.text in TYPE_KEYWORDS -> {}
                    else -> return null
                }
                i--
            }
            return null
        }

        private fun matchingOpen(close: Int, open: IElementType, closing: IElementType): Int? {
            var depth = 0
            for (i in close downTo 0) {
                when (code[i].type) {
                    closing -> depth++
                    open -> if (--depth == 0) return i
                }
            }
            return null
        }
    }

    private fun tokenAt(tokens: List<Token>, offset: Int): Token? {
        var low = 0
        var high = tokens.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val token = tokens[middle]
            when {
                offset < token.start -> high = middle - 1
                offset >= token.end -> low = middle + 1
                else -> return token
            }
        }
        return null
    }

    private fun lex(text: CharSequence): List<Token> {
        val lexer = CSharpLexer()
        lexer.start(text)
        val tokens = ArrayList<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE) tokens += Token(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }
}
