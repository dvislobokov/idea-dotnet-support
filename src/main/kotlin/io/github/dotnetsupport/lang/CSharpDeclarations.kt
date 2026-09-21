package io.github.dotnetsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

enum class DeclarationKind(val title: String, val isType: Boolean = false) {
    NAMESPACE("namespace"),
    CLASS("class", true), STRUCT("struct", true), INTERFACE("interface", true), ENUM("enum", true), RECORD("record", true), DELEGATE("delegate", true),
    CONSTRUCTOR("constructor"), METHOD("method"), OPERATOR("operator"), PROPERTY("property"), INDEXER("indexer"), FIELD("field"), EVENT("event"), ENUM_MEMBER("enum member"),
}

/**
 * A declaration found by [CSharpDeclarations]. [range] runs from the first token of the declaration (its attributes
 * included) to its closing brace or semicolon; [body] is the `{ ... }` of it, braces included.
 */
class CSharpDeclarationInfo(
    val kind: DeclarationKind,
    val name: String,
    val nameRange: TextRange,
    val range: TextRange,
    val body: TextRange?,
    /** `(int a, string b)` of a method, as written. */
    val parameters: String?,
    /** The return type of a method, the type of a property or a field. */
    val type: String?,
    val modifiers: Set<String>,
    val children: List<CSharpDeclarationInfo>,
) {
    /** `Total(decimal discount): decimal`, `Name: string`, `Order`. */
    val presentation: String get() = name + parameters.orEmpty() + type?.let { ": $it" }.orEmpty()

    fun flatten(): Sequence<CSharpDeclarationInfo> = sequenceOf(this) + children.asSequence().flatMap { it.flatten() }
}

class CSharpFileStructure(val declarations: List<CSharpDeclarationInfo>, /** The block of using directives at the top of the file. */ val usings: TextRange?) {
    fun all(): Sequence<CSharpDeclarationInfo> = declarations.asSequence().flatMap { it.flatten() }

    /** Declarations around [offset], the outermost first. */
    fun pathTo(offset: Int): List<CSharpDeclarationInfo> {
        val path = ArrayList<CSharpDeclarationInfo>()
        var level = declarations
        while (true) {
            val next = level.firstOrNull { it.range.containsOffset(offset) } ?: return path
            path += next
            level = next.children
        }
    }

    /** `Shop.Orders.OrderService.Total` for the path of a declaration. */
    fun qualifiedName(declaration: CSharpDeclarationInfo): String = pathTo(declaration.nameRange.startOffset).joinToString(".") { it.name }
}

/**
 * Namespaces, types and members of a C# file, found by tokens and the balance of braces: there is no parser. The header
 * of a member is everything up to its `{`, `;`, `=` or `=>`; what kind of member it is follows from the keywords and the
 * parentheses in it. Bodies are skipped, so locals and local functions are not seen. Tuned for code that compiles; on
 * code that is being typed it degrades to fewer declarations, never to an exception or an endless loop.
 */
object CSharpDeclarations {
    private class Token(val type: IElementType, val text: String, val start: Int, val end: Int) {
        fun isKeyword(word: String) = type == CSharpTokenTypes.KEYWORD && text == word
        fun isOperator(symbol: String) = type == CSharpTokenTypes.OPERATOR && text == symbol
        val isIdentifier get() = type == CSharpTokenTypes.IDENTIFIER
    }

    private val TYPE_KEYWORDS = mapOf(
        "class" to DeclarationKind.CLASS, "struct" to DeclarationKind.STRUCT, "interface" to DeclarationKind.INTERFACE,
        "enum" to DeclarationKind.ENUM, "record" to DeclarationKind.RECORD,
    )
    private val MODIFIERS = setOf(
        "public", "private", "protected", "internal", "static", "abstract", "virtual", "override", "sealed", "async", "readonly", "const", "partial",
        "extern", "unsafe", "volatile", "new", "required", "file", "ref",
    )

    fun scan(text: CharSequence): CSharpFileStructure {
        val tokens = tokenize(text)
        val scanner = Scanner(text, tokens)
        val declarations = scanner.members(0, tokens.size, enclosingType = null)
        return CSharpFileStructure(declarations, scanner.usings)
    }

    private fun tokenize(text: CharSequence): List<Token> {
        val lexer = CSharpLexer()
        lexer.start(text)
        val tokens = ArrayList<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            // directives do not take part in declarations: `#if` branches are all scanned as if they were active
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                tokens += Token(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }

    private class Scanner(private val text: CharSequence, private val tokens: List<Token>) {
        var usings: TextRange? = null

        /** Members between the tokens [from] and [to]; [enclosingType] is null at the level of a file or a namespace. */
        fun members(from: Int, to: Int, enclosingType: String?): List<CSharpDeclarationInfo> = membersOf(from, to, enclosingType, null, topOfFile = true)

        private fun membersOf(from: Int, to: Int, typeName: String?, typeKind: DeclarationKind?, topOfFile: Boolean = false): List<CSharpDeclarationInfo> {
            val result = ArrayList<CSharpDeclarationInfo>()
            var i = from
            while (i < to) {
                val start = i
                i = skipAttributes(i, to)
                if (i >= to) break
                val token = tokens[i]
                if (token.type == CSharpTokenTypes.RBRACE || token.type == CSharpTokenTypes.SEMICOLON) {
                    i++ // a stray brace of unfinished code, or an empty statement
                    continue
                }
                if (token.isKeyword("using") || (token.isKeyword("global") && tokens.getOrNull(i + 1)?.isKeyword("using") == true) || token.isKeyword("extern") && tokens.getOrNull(i + 1)?.text == "alias") {
                    val end = skipTo(i, to) { it.type == CSharpTokenTypes.SEMICOLON }
                    // the directives on top of the file, not `using (...)` / `using var` of a top-level program
                    val afterUsing = tokens.getOrNull(i + (if (token.isKeyword("global")) 2 else 1))
                    if (topOfFile && result.isEmpty() && afterUsing?.type != CSharpTokenTypes.LPAREN && afterUsing?.isKeyword("var") != true) {
                        usings = TextRange(usings?.startOffset ?: tokens[i].start, tokens[minOf(end, to - 1)].end)
                    }
                    i = end + 1
                    continue
                }

                val headerEnd = headerEnd(i, to)
                val terminator = tokens.getOrNull(headerEnd)
                val header = tokens.subList(i, minOf(headerEnd, to))
                val declaration = declaration(header, start, headerEnd, to, typeName, typeKind)
                i = maxOf(declaration?.second ?: bodyEnd(headerEnd, to, terminator), start + 1)
                // a file-scoped namespace takes the rest of the file
                declaration?.first?.let { result += it }
            }
            return result
        }

        /** The index of the token that ends the header at [from]: `{`, `;`, `=` or `=>` outside of parentheses and brackets. */
        private fun headerEnd(from: Int, to: Int): Int {
            var depth = 0
            var i = from
            // `operator ==(`: the symbol of an overloaded operator is not the end of the header
            var operatorSymbol = false
            while (i < to) {
                val token = tokens[i]
                if (token.isKeyword("operator")) operatorSymbol = true
                if (token.type == CSharpTokenTypes.LPAREN) operatorSymbol = false
                when (token.type) {
                    CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET -> depth++
                    CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET -> depth = maxOf(0, depth - 1)
                    CSharpTokenTypes.LBRACE, CSharpTokenTypes.SEMICOLON -> if (depth == 0) return i
                    CSharpTokenTypes.RBRACE -> if (depth == 0) return i
                    else -> if (depth == 0 && !operatorSymbol && token.isOperator("=")) return i
                }
                i++
            }
            return to
        }

        /** The lexer gives operators character by character: `=>` is `=` with `>` right after it. */
        private fun isArrow(at: Int): Boolean =
            tokens.getOrNull(at)?.isOperator("=") == true && tokens.getOrNull(at + 1)?.let { it.isOperator(">") && it.start == tokens[at].end } == true

        /** The index after the body that starts at the terminator [at]: a brace block, or an expression up to its `;`. */
        private fun bodyEnd(at: Int, to: Int, terminator: Token?): Int = when {
            terminator == null -> to
            terminator.type == CSharpTokenTypes.LBRACE -> {
                val close = matchingBrace(at, to)
                // `{ get; } = new();` of a property
                if (tokens.getOrNull(close + 1)?.isOperator("=") == true) skipTo(close + 1, to) { it.type == CSharpTokenTypes.SEMICOLON } + 1 else close + 1
            }
            terminator.type == CSharpTokenTypes.SEMICOLON || terminator.type == CSharpTokenTypes.RBRACE -> at + 1
            else -> skipTo(at, to) { it.type == CSharpTokenTypes.SEMICOLON } + 1
        }

        /** The index of the first token at or after [from] that satisfies [stop] outside of any brackets; [to] when there is none. */
        private fun skipTo(from: Int, to: Int, stop: (Token) -> Boolean): Int {
            var depth = 0
            var i = from
            while (i < to) {
                val token = tokens[i]
                if (depth == 0 && stop(token)) return i
                when (token.type) {
                    CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET, CSharpTokenTypes.LBRACE -> depth++
                    CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET, CSharpTokenTypes.RBRACE -> {
                        if (depth == 0) return i // the end of the enclosing block: unfinished code
                        depth--
                    }
                }
                i++
            }
            return to
        }

        private fun matchingBrace(open: Int, to: Int): Int {
            var depth = 0
            for (i in open until to) {
                when (tokens[i].type) {
                    CSharpTokenTypes.LBRACE -> depth++
                    CSharpTokenTypes.RBRACE -> if (--depth == 0) return i
                }
            }
            return to - 1
        }

        private fun skipAttributes(from: Int, to: Int): Int {
            var i = from
            while (i < to && tokens[i].type == CSharpTokenTypes.LBRACKET) {
                var depth = 0
                while (i < to) {
                    if (tokens[i].type == CSharpTokenTypes.LBRACKET) depth++
                    if (tokens[i].type == CSharpTokenTypes.RBRACKET && --depth == 0) break
                    i++
                }
                i++
            }
            return minOf(i, to)
        }

        /** The declaration of [header] (null for what is not one: a statement of a top-level program) and the index after it. */
        private fun declaration(header: List<Token>, start: Int, headerEnd: Int, to: Int, typeName: String?, typeKind: DeclarationKind?): Pair<CSharpDeclarationInfo?, Int>? {
            if (header.isEmpty()) return null
            val terminator = tokens.getOrNull(headerEnd)?.takeIf { headerEnd < to }
            val startOffset = tokens[start].start
            val modifiers = header.takeWhile { it.type == CSharpTokenTypes.KEYWORD || it.text == "file" }.filter { it.text in MODIFIERS }.mapTo(LinkedHashSet()) { it.text }

            fun build(kind: DeclarationKind, name: Token, nameText: String, end: Int, body: TextRange?, parameters: String?, type: String?, children: List<CSharpDeclarationInfo>): Pair<CSharpDeclarationInfo, Int> {
                val last = tokens[(end - 1).coerceIn(start, tokens.size - 1)]
                return CSharpDeclarationInfo(kind, nameText, TextRange(name.start, name.end), TextRange(startOffset, maxOf(last.end, name.end)), body, parameters, type, modifiers, children) to end
            }

            // namespace A.B { ... }  |  namespace A.B;
            val namespaceAt = header.indexOfFirst { it.isKeyword("namespace") }
            if (namespaceAt >= 0 && typeName == null) {
                val nameTokens = header.drop(namespaceAt + 1).takeWhile { it.isIdentifier || it.type == CSharpTokenTypes.DOT }
                val first = nameTokens.firstOrNull() ?: return null
                val name = nameTokens.joinToString("") { it.text }
                val nameToken = Token(first.type, name, first.start, nameTokens.last().end)
                return if (terminator?.type == CSharpTokenTypes.LBRACE) {
                    val close = matchingBrace(headerEnd, to)
                    build(DeclarationKind.NAMESPACE, nameToken, name, close + 1, braces(headerEnd, close), null, null, membersOf(headerEnd + 1, close, null, null))
                } else {
                    build(DeclarationKind.NAMESPACE, nameToken, name, to, null, null, null, membersOf(minOf(headerEnd + 1, to), to, null, null))
                }
            }

            // class / struct / interface / enum / record, before any `where T : class`
            val constraintsAt = header.indexOfFirst { it.isKeyword("where") }.let { if (it < 0) header.size else it }
            val typeAt = header.take(constraintsAt).indexOfFirst { token -> token.type == CSharpTokenTypes.KEYWORD && token.text in TYPE_KEYWORDS && header.none { it.type == CSharpTokenTypes.LPAREN && it.start < token.start } }
            if (typeAt >= 0) {
                val keyword = header[typeAt]
                // `record struct Point`, `record class Person`
                val nameAt = if (keyword.text == "record" && header.getOrNull(typeAt + 1)?.let { it.isKeyword("struct") || it.isKeyword("class") } == true) typeAt + 2 else typeAt + 1
                val name = header.getOrNull(nameAt)?.takeIf { it.isIdentifier } ?: return null
                val kind = TYPE_KEYWORDS.getValue(keyword.text)
                val primary = header.getOrNull(nameAt + 1)?.takeIf { it.type == CSharpTokenTypes.LPAREN }?.let { parenthesized(header, nameAt + 1) }
                if (terminator?.type != CSharpTokenTypes.LBRACE) return build(kind, name, name.text, bodyEnd(headerEnd, to, terminator), null, primary, null, emptyList())
                val close = matchingBrace(headerEnd, to)
                val children = if (kind == DeclarationKind.ENUM) enumMembers(headerEnd + 1, close) else membersOf(headerEnd + 1, close, name.text, kind)
                return build(kind, name, name.text, close + 1, braces(headerEnd, close), primary, null, children)
            }

            val candidates = callLikeNames(header)
            if (header.any { it.isKeyword("delegate") } && candidates.isNotEmpty()) {
                val (nameAt, parenAt) = candidates.first()
                return build(DeclarationKind.DELEGATE, header[nameAt], header[nameAt].text, bodyEnd(headerEnd, to, terminator), null, parenthesized(header, parenAt), typeOf(header, nameAt), emptyList())
            }
            if (typeName == null) return null // top-level statements, assembly attributes

            val end = bodyEnd(headerEnd, to, terminator)
            val body = terminator?.takeIf { it.type == CSharpTokenTypes.LBRACE }?.let { braces(headerEnd, matchingBrace(headerEnd, to)) }

            val eventAt = header.indexOfFirst { it.isKeyword("event") }
            if (eventAt >= 0) {
                val name = header.lastOrNull { it.isIdentifier } ?: return null
                return build(DeclarationKind.EVENT, name, name.text, end, body, null, typeOf(header, header.indexOf(name), after = eventAt + 1), emptyList())
            }
            val operatorAt = header.indexOfFirst { it.isKeyword("operator") }
            if (operatorAt >= 0) {
                val parenAt = (operatorAt + 1 until header.size).firstOrNull { header[it].type == CSharpTokenTypes.LPAREN } ?: return null
                val symbol = header.subList(operatorAt + 1, parenAt).joinToString("") { it.text }
                return build(DeclarationKind.OPERATOR, header[operatorAt], "operator $symbol", end, body, parenthesized(header, parenAt), typeOf(header, operatorAt).takeIf { !header.any { it.isKeyword("implicit") || it.isKeyword("explicit") } }, emptyList())
            }
            val thisAt = header.indexOfFirst { it.isKeyword("this") }
            if (thisAt >= 0 && header.getOrNull(thisAt + 1)?.type == CSharpTokenTypes.LBRACKET) {
                val close = (thisAt + 1 until header.size).lastOrNull { header[it].type == CSharpTokenTypes.RBRACKET } ?: header.size - 1
                val parameters = text.subSequence(header[thisAt + 1].start, header[close].end).toString().collapseWhitespace()
                return build(DeclarationKind.INDEXER, header[thisAt], "this", end, body, parameters, typeOf(header, thisAt), emptyList())
            }
            if (candidates.isNotEmpty()) {
                val (nameAt, parenAt) = candidates.first()
                val name = header[nameAt]
                val isDestructor = header.getOrNull(nameAt - 1)?.isOperator("~") == true
                val type = typeOf(header, nameAt)
                val kind = if ((name.text == typeName && type == null) || isDestructor) DeclarationKind.CONSTRUCTOR else DeclarationKind.METHOD
                return build(kind, name, (if (isDestructor) "~" else "") + name.text, end, body, parenthesized(header, parenAt), type, emptyList())
            }
            // no parameters: a property with accessors or an expression body, otherwise a field
            val name = header.lastOrNull { it.isIdentifier } ?: return null
            if (header.size < 2 && modifiers.isEmpty()) return null // a lone identifier: unfinished code
            val isProperty = terminator?.type == CSharpTokenTypes.LBRACE || isArrow(headerEnd)
            val nameAt = header.indexOf(name)
            // `int a, b;`: the first declarator names the field
            val first = header.indexOfFirst { it.type == CSharpTokenTypes.COMMA }.takeIf { it > 0 && !isProperty && angleDepthAt(header, it) == 0 }?.let { comma -> header.take(comma).lastOrNull { it.isIdentifier } } ?: name
            return build(if (isProperty) DeclarationKind.PROPERTY else DeclarationKind.FIELD, first, first.text, end, body, null, typeOf(header, header.indexOf(first).takeIf { it >= 0 } ?: nameAt), emptyList())
        }

        private fun enumMembers(from: Int, to: Int): List<CSharpDeclarationInfo> {
            val result = ArrayList<CSharpDeclarationInfo>()
            var i = from
            while (i < to) {
                i = skipAttributes(i, to)
                val name = tokens.getOrNull(i)?.takeIf { i < to && it.isIdentifier }
                val end = skipTo(i, to) { it.type == CSharpTokenTypes.COMMA }
                if (name != null) {
                    val last = tokens[(end - 1).coerceAtLeast(i)]
                    result += CSharpDeclarationInfo(DeclarationKind.ENUM_MEMBER, name.text, TextRange(name.start, name.end), TextRange(name.start, last.end), null, null, null, emptySet(), emptyList())
                }
                i = maxOf(end, i) + 1
            }
            return result
        }

        /** `Name(` and `Name<T>(` outside of other parentheses: (index of the name, index of the parenthesis). A tuple type has no name before it. */
        private fun callLikeNames(header: List<Token>): List<Pair<Int, Int>> {
            val result = ArrayList<Pair<Int, Int>>()
            var depth = 0
            for ((i, token) in header.withIndex()) {
                when (token.type) {
                    CSharpTokenTypes.LPAREN -> {
                        if (depth == 0) nameBefore(header, i)?.let { result += it to i }
                        depth++
                    }
                    CSharpTokenTypes.RPAREN -> depth = maxOf(0, depth - 1)
                    CSharpTokenTypes.LBRACKET -> depth++
                    CSharpTokenTypes.RBRACKET -> depth = maxOf(0, depth - 1)
                    else -> if (depth == 0 && token.isKeyword("where")) return result
                }
            }
            return result
        }

        private fun nameBefore(header: List<Token>, parenAt: Int): Int? {
            var i = parenAt - 1
            if (i >= 0 && header[i].type == CSharpTokenTypes.OPERATOR && header[i].text.all { it == '>' }) {
                // type parameters of a generic method: back over the balanced <...>
                var depth = 0
                while (i >= 0) {
                    val token = header[i]
                    if (token.type == CSharpTokenTypes.OPERATOR && token.text.all { it == '>' }) depth += token.text.length
                    else if (token.type == CSharpTokenTypes.OPERATOR && token.text.all { it == '<' }) depth -= token.text.length
                    if (depth <= 0) break
                    i--
                }
                i--
            }
            return i.takeIf { it >= 0 && header[it].isIdentifier }
        }

        private fun angleDepthAt(header: List<Token>, index: Int): Int =
            header.take(index).sumOf { token -> if (token.type != CSharpTokenTypes.OPERATOR) 0 else token.text.count { it == '<' } - token.text.count { it == '>' } }

        /** The tokens between the modifiers and the name at [nameAt], as written: the type of the member. */
        private fun typeOf(header: List<Token>, nameAt: Int, after: Int = 0): String? {
            var from = after
            while (from < nameAt && (header[from].text in MODIFIERS || header[from].isKeyword("event") || header[from].isKeyword("delegate")) && header[from].type == CSharpTokenTypes.KEYWORD) from++
            // `void IFoo.Bar()`: the interface of an explicit implementation is not a part of the type
            var to = nameAt
            while (to - 2 >= from && header[to - 1].type == CSharpTokenTypes.DOT && header[to - 2].isIdentifier && to - 2 > from) to -= 2
            if (to > from && header[to - 1].isOperator("~")) to--
            if (from >= to) return null
            return text.subSequence(header[from].start, header[to - 1].end).toString().collapseWhitespace()
        }

        private fun parenthesized(header: List<Token>, openAt: Int): String {
            var depth = 0
            for (i in openAt until header.size) {
                if (header[i].type == CSharpTokenTypes.LPAREN) depth++
                if (header[i].type == CSharpTokenTypes.RPAREN && --depth == 0) return text.subSequence(header[openAt].start, header[i].end).toString().collapseWhitespace()
            }
            return text.subSequence(header[openAt].start, header.last().end).toString().collapseWhitespace()
        }

        private fun braces(open: Int, close: Int): TextRange = TextRange(tokens[open].start, tokens[close.coerceIn(open, tokens.size - 1)].end)

        private fun String.collapseWhitespace(): String = replace(WHITESPACE, " ")
    }

    private val WHITESPACE = Regex("""\s+""")
}
