package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.razor.lang.codegen.CSharpIdentifier
import io.github.dotnetsupport.razor.lang.codegen.isNullOrDotNetWhiteSpace
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentTypeArgumentIntermediateNode

/**
 * The type name rewriters of the generic type pass (`Language/TypeNameRewriter.cs`, `CSharp/GenericTypeNameRewriter.cs`,
 * `CSharp/GlobalQualifiedTypeNameRewriter.cs`) and the two other Roslyn calls of that pass (`ParseTypeName` for the type parameters of
 * a type, `ParseExpression(...) is LambdaExpressionSyntax`). The compiler parses with Roslyn; here [CSharpTypeNames] is a parser of the
 * type syntax alone (names, generic names, qualified and alias-qualified names, predefined, array, nullable, pointer, tuple and ref
 * types) with Roslyn's trivia and skipped-token rules, so that `ToFullString` of an unchanged tree is the text.
 */

/** `TypeNameRewriter`: Razor.Language doesn't reference Microsoft.CodeAnalysis.CSharp in the compiler, hence the indirection. */
abstract class TypeNameRewriter {
    abstract fun rewrite(typeName: String): String

    abstract fun rewriteComponentTypeName(node: ComponentIntermediateNode)
}

/**
 * `GenericTypeNameRewriter`: the type parameters bound to type arguments replaced by the arguments (`object` for a binding without a
 * valid one), at the top level (`TItem`) or inside generic names (`List<TItem>`); a name on either side of a dot is never replaced.
 */
class GenericTypeNameRewriter(private val bindings: Map<String, ComponentTypeArgumentIntermediateNode?>) : TypeNameRewriter() {
    override fun rewrite(typeName: String): String = rewrite(typeName, ArrayList())

    override fun rewriteComponentTypeName(node: ComponentIntermediateNode) {
        val usedBindings = ArrayList<ComponentTypeArgumentIntermediateNode?>()
        node.typeName = rewrite(node.typeName ?: "", usedBindings)
        // A binding without a node is a null element, as in the compiler (the writer then never writes the type arguments: RZ10001).
        @Suppress("UNCHECKED_CAST")
        node.orderedTypeArguments = usedBindings as List<ComponentTypeArgumentIntermediateNode>
    }

    private fun rewrite(typeName: String, usedTypeArguments: MutableList<ComponentTypeArgumentIntermediateNode?>): String {
        val parsed = CSharpTypeNames.parseTypeName(typeName)
        val builder = StringBuilder()
        visit(parsed, builder, usedTypeArguments)
        return builder.toString()
    }

    /** `Visitor.Visit`: an identifier name outside a qualified name is replaced when bound; anything else is visited (rewritten) child by child. */
    private fun visit(node: CSharpTypeNames.Node, builder: StringBuilder, usedBindings: MutableList<ComponentTypeArgumentIntermediateNode?>) {
        if (node is CSharpTypeNames.IdentifierName && node.parent !is CSharpTypeNames.QualifiedName) {
            if (bindings.containsKey(node.identifier.text)) {
                val binding = bindings[node.identifier.text]
                usedBindings.add(binding)

                // Without a valid replacement, object: reasonable code at least, compared to leaving the type parameter in place. The
                // diagnostics for missing/invalid type parameters are reported anyway.
                val content = binding?.value?.content
                val replacement = if (!content.isNullOrDotNetWhiteSpace()) content!! else "object"
                builder.append(node.identifier.leading).append(replacement).append(node.identifier.trailing)
                return
            }
        }

        // VisitGenericName rewrites the type arguments only, as the default rewriter does for the other nodes' children.
        for (element in node.elements) {
            when (element) {
                is CSharpTypeNames.Token -> element.appendFullTo(builder)
                is CSharpTypeNames.Node -> visit(element, builder, usedBindings)
            }
        }
    }
}

/**
 * `GlobalQualifiedTypeNameRewriter`: `global::` in front of the type names, for code generated in another namespace than the user's,
 * where names clash easily. The component's type parameters ([ignore]) are left alone; a qualified name that is not part of another one
 * gets the prefix unless its leftmost part is alias-qualified; a lone identifier only at the top level.
 */
class GlobalQualifiedTypeNameRewriter(ignore: Collection<String>) : TypeNameRewriter() {
    private val ignore: Set<String> = HashSet(ignore)

    override fun rewrite(typeName: String): String {
        val parsed = CSharpTypeNames.parseTypeName(typeName)
        val builder = StringBuilder()
        visit(parsed, builder)
        return builder.toString()
    }

    override fun rewriteComponentTypeName(node: ComponentIntermediateNode) {
        node.typeName = rewrite(node.typeName ?: "")
    }

    private fun visit(node: CSharpTypeNames.Node, builder: StringBuilder) {
        when {
            node is CSharpTypeNames.QualifiedName && node.parent !is CSharpTypeNames.QualifiedName -> {
                // Rewritten postorder (the names of generic type arguments first), then re-parsed from its text with the trivia of the node.
                val inner = StringBuilder()
                visitChildren(node, inner)
                val leading = node.firstToken().leading
                val trailing = node.lastToken().trailing
                val text = inner.substring(leading.length, inner.length - trailing.length)
                builder.append(leading)
                if (!isGloballyQualified(node)) builder.append("global::")
                builder.append(text).append(trailing)
            }

            node is CSharpTypeNames.IdentifierName -> {
                if (ignore.contains(node.toString()) || node.parent != null) {
                    node.appendFullTo(builder)
                } else {
                    // AliasQualifiedName(IdentifierName(global), node): the new `::` token has no trivia.
                    builder.append("global::")
                    node.appendFullTo(builder)
                }
            }

            else -> visitChildren(node, builder)
        }
    }

    private fun visitChildren(node: CSharpTypeNames.Node, builder: StringBuilder) {
        for (element in node.elements) {
            when (element) {
                is CSharpTypeNames.Token -> element.appendFullTo(builder)
                is CSharpTypeNames.Node -> visit(element, builder)
            }
        }
    }

    private fun isGloballyQualified(node: CSharpTypeNames.QualifiedName): Boolean {
        var candidate: CSharpTypeNames.QualifiedName? = node
        while (candidate != null) {
            if (candidate.left is CSharpTypeNames.AliasQualifiedName) return true
            candidate = candidate.left as? CSharpTypeNames.QualifiedName
        }
        return false
    }
}

/**
 * The C# type syntax of `SyntaxFactory.ParseTypeName` (with `consumeFullText`), as far as the component code generation looks at it:
 * the node kinds with their parents, the tokens with Roslyn's trivia (trailing: the whitespace up to and including the end of the line;
 * leading: the rest), the tokens that do not parse skipped into the trailing trivia of the last token of the type.
 */
object CSharpTypeNames {
    /** A token: [text] (empty for a missing token) and its trivia. */
    class Token(val kind: Kind, val text: String, val leading: String, var trailing: String) {
        enum class Kind { Identifier, Punctuation, Other, EndOfFile }

        fun appendFullTo(builder: StringBuilder) {
            builder.append(leading).append(text).append(trailing)
        }

        val isIdentifier: Boolean get() = kind == Kind.Identifier

        fun isPunctuation(value: String): Boolean = kind == Kind.Punctuation && text == value

        override fun toString(): String = text
    }

    /** A node: its tokens and child nodes in order. */
    sealed class Node {
        var parent: Node? = null
            internal set

        abstract val elements: List<Any>

        fun firstToken(): Token {
            val first = elements.first()
            return if (first is Token) first else (first as Node).firstToken()
        }

        fun lastToken(): Token {
            val last = elements.last()
            return if (last is Token) last else (last as Node).lastToken()
        }

        fun appendFullTo(builder: StringBuilder) {
            for (element in elements) if (element is Token) element.appendFullTo(builder) else (element as Node).appendFullTo(builder)
        }

        fun toFullString(): String = StringBuilder().also { appendFullTo(it) }.toString()

        /** `ToString`: the text without the leading trivia of the first token and the trailing trivia of the last. */
        override fun toString(): String {
            val full = toFullString()
            return full.substring(firstToken().leading.length, full.length - lastToken().trailing.length)
        }

        /** `DescendantNodesAndSelf`: pre-order. */
        fun descendantNodesAndSelf(): List<Node> {
            val result = ArrayList<Node>()
            fun walk(node: Node) {
                result.add(node)
                for (element in node.elements) if (element is Node) walk(element)
            }
            walk(this)
            return result
        }

        protected fun adopt(vararg children: Node?) {
            for (child in children) child?.parent = this
        }
    }

    class IdentifierName(val identifier: Token) : Node() {
        override val elements: List<Any> = listOf(identifier)
    }

    class TypeArgumentList(val lessThan: Token, val arguments: List<Node>, val separators: List<Token>, val greaterThan: Token) : Node() {
        override val elements: List<Any> = buildList {
            add(lessThan)
            for (i in arguments.indices) {
                add(arguments[i])
                if (i < separators.size) add(separators[i])
            }
            add(greaterThan)
        }

        init {
            adopt(*arguments.toTypedArray())
        }
    }

    class GenericName(val identifier: Token, val typeArgumentList: TypeArgumentList) : Node() {
        override val elements: List<Any> = listOf(identifier, typeArgumentList)

        init {
            adopt(typeArgumentList)
        }
    }

    class QualifiedName(val left: Node, val dot: Token, val right: Node) : Node() {
        override val elements: List<Any> = listOf(left, dot, right)

        init {
            adopt(left, right)
        }
    }

    class AliasQualifiedName(val alias: IdentifierName, val colonColon: Token, val name: Node) : Node() {
        override val elements: List<Any> = listOf(alias, colonColon, name)

        init {
            adopt(alias, name)
        }
    }

    /** `OmittedTypeArgumentSyntax`: the empty argument of `List<>`. */
    class OmittedTypeArgument(val token: Token) : Node() {
        override val elements: List<Any> = listOf(token)
    }

    class PredefinedType(val keyword: Token) : Node() {
        override val elements: List<Any> = listOf(keyword)
    }

    /** `ArrayTypeSyntax`: the element type and the tokens of its rank specifiers. */
    class ArrayType(val elementType: Node, val rankSpecifiers: List<Token>) : Node() {
        override val elements: List<Any> = listOf<Any>(elementType) + rankSpecifiers

        init {
            adopt(elementType)
        }
    }

    class NullableType(val elementType: Node, val question: Token) : Node() {
        override val elements: List<Any> = listOf(elementType, question)

        init {
            adopt(elementType)
        }
    }

    class PointerType(val elementType: Node, val star: Token) : Node() {
        override val elements: List<Any> = listOf(elementType, star)

        init {
            adopt(elementType)
        }
    }

    class TupleElement(val type: Node, val identifier: Token?) : Node() {
        override val elements: List<Any> = listOfNotNull(type, identifier)

        init {
            adopt(type)
        }
    }

    class TupleType(val open: Token, val tupleElements: List<TupleElement>, val separators: List<Token>, val close: Token) : Node() {
        override val elements: List<Any> = buildList {
            add(open)
            for (i in tupleElements.indices) {
                add(tupleElements[i])
                if (i < separators.size) add(separators[i])
            }
            add(close)
        }

        init {
            adopt(*tupleElements.toTypedArray())
        }
    }

    class RefType(val refKeyword: Token, val readOnlyKeyword: Token?, val type: Node) : Node() {
        override val elements: List<Any> = listOfNotNull(refKeyword, readOnlyKeyword, type)

        init {
            adopt(type)
        }
    }

    private val predefinedTypes = setOf(
        "bool", "byte", "char", "decimal", "double", "float", "int", "long", "object", "sbyte", "short", "string", "uint", "ulong", "ushort", "void",
    )

    /** `SyntaxFactory.ParseTypeName(text)`: the type, the tokens that do not parse skipped into the trailing trivia of its last token. */
    fun parseTypeName(text: String): Node {
        val tokens = lex(text)
        val parser = Parser(tokens)
        val type = parser.parseType()
        // ConsumeUnexpectedTokens: the rest (and the end of file's leading trivia is not part of the node).
        val skipped = StringBuilder()
        while (!parser.current.isEnd) {
            parser.current.token.appendFullTo(skipped)
            parser.advance()
        }
        if (skipped.isNotEmpty()) {
            val last = type.lastToken()
            last.trailing += skipped
        }
        return type
    }

    /**
     * `SyntaxFactory.ParseExpression(text) is LambdaExpressionSyntax`: whether the expression starts as a lambda — attribute lists, the
     * `static` / `async` modifiers, then `x =>`, `(...) =>` or an explicit return type `T (...) =>`. A lambda's body takes the rest of the
     * expression, so the start decides.
     */
    fun isLambdaExpression(text: String): Boolean {
        val tokens = lex(text)
        var pos = 0
        fun at(i: Int): Token = tokens[minOf(i, tokens.size - 1)]

        // Attribute lists of a lambda ([A] x => ...); without a lambda after them this is a collection expression.
        while (at(pos).isPunctuation("[")) {
            pos = matchingClose(tokens, pos, "[", "]") ?: return false
            pos++
        }

        // Modifiers: `static` / `async` not followed by `=>` (then they are the parameter of a simple lambda).
        while (at(pos).isIdentifier && (at(pos).text == "static" || at(pos).text == "async") && !at(pos + 1).isPunctuation("=>")) pos++

        // x => ...
        if (isParameterIdentifier(at(pos)) && at(pos + 1).isPunctuation("=>")) return true

        // (...) => ...
        if (at(pos).isPunctuation("(")) {
            val close = matchingClose(tokens, pos, "(", ")") ?: return false
            return at(close + 1).isPunctuation("=>")
        }

        // T (...) => ...: an explicit return type.
        val parser = Parser(tokens.subList(pos, tokens.size))
        if (!parser.tryParseTypeStrict()) return false
        val afterType = pos + parser.position
        if (!at(afterType).isPunctuation("(")) return false
        val close = matchingClose(tokens, afterType, "(", ")") ?: return false
        return at(close + 1).isPunctuation("=>")
    }

    private fun isParameterIdentifier(token: Token): Boolean =
        token.isIdentifier && (token.text.startsWith("@") || CSharpSyntaxFacts.getKeywordKind(token.text) == null)

    /** The index of the token that closes the bracket at [open], counting all bracket kinds. */
    private fun matchingClose(tokens: List<Token>, open: Int, openText: String, closeText: String): Int? {
        var depth = 0
        var i = open
        while (i < tokens.size && tokens[i].kind != Token.Kind.EndOfFile) {
            val t = tokens[i]
            if (t.kind == Token.Kind.Punctuation) {
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> {
                        depth--
                        if (depth == 0) return if (t.text == closeText && tokens[open].text == openText) i else null
                    }
                }
            }
            i++
        }
        return null
    }

    // Lexing

    private fun isTriviaWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == ' ' || c == '﻿' ||
        (c.code > 0x7F && Character.getType(c).toByte() == Character.SPACE_SEPARATOR)

    private fun isNewLine(c: Char): Boolean = c == '\r' || c == '\n' || c == '\u0085' || c == ' ' || c == ' '

    private fun lex(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0

        fun scanLeading(): String {
            val start = i
            while (i < text.length) {
                val c = text[i]
                if (isTriviaWhitespace(c) || isNewLine(c)) {
                    i++
                } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                    while (i < text.length && !isNewLine(text[i])) i++
                } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                } else {
                    break
                }
            }
            return text.substring(start, i)
        }

        fun scanTrailing(): String {
            val start = i
            while (i < text.length) {
                val c = text[i]
                if (isTriviaWhitespace(c)) {
                    i++
                } else if (isNewLine(c)) {
                    i += if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                    break
                } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                    while (i < text.length && !isNewLine(text[i])) i++
                } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                } else {
                    break
                }
            }
            return text.substring(start, i)
        }

        while (true) {
            val leading = scanLeading()
            if (i >= text.length) {
                tokens.add(Token(Token.Kind.EndOfFile, "", leading, ""))
                return tokens
            }
            val start = i
            val c = text[i]
            val kind: Token.Kind
            if (CSharpIdentifier.isIdentifierStartCharacter(c) || (c == '@' && i + 1 < text.length && CSharpIdentifier.isIdentifierStartCharacter(text[i + 1]))) {
                i++
                while (i < text.length && CSharpIdentifier.isIdentifierPartCharacter(text[i])) i++
                kind = Token.Kind.Identifier
            } else if (c == '"' || c == '\'' || (c == '@' && i + 1 < text.length && text[i + 1] == '"') || (c == '$' && i + 1 < text.length && (text[i + 1] == '"' || text[i + 1] == '@'))) {
                i = skipStringLiteral(text, i)
                kind = Token.Kind.Other
            } else if (c.isDigit()) {
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                kind = Token.Kind.Other
            } else {
                val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                i += if (two == "::" || two == "=>") 2 else 1
                kind = Token.Kind.Punctuation
            }
            val tokenText = text.substring(start, i)
            val trailing = scanTrailing()
            tokens.add(Token(kind, tokenText, leading, trailing))
        }
    }

    private fun skipStringLiteral(text: String, start: Int): Int {
        var i = start
        var verbatim = false
        while (i < text.length && (text[i] == '@' || text[i] == '$')) {
            if (text[i] == '@') verbatim = true
            i++
        }
        if (i >= text.length) return i
        val quote = text[i]
        i++
        while (i < text.length) {
            val c = text[i]
            if (c == quote) {
                if (verbatim && i + 1 < text.length && text[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            if (!verbatim && c == '\\') {
                i += 2
                continue
            }
            if (!verbatim && isNewLine(c)) return i
            i++
        }
        return i
    }

    // Parsing

    private class Parser(private val tokens: List<Token>) {
        var position = 0
            private set

        class Current(val token: Token) {
            val isEnd: Boolean get() = token.kind == Token.Kind.EndOfFile
        }

        val current: Current get() = Current(peek(0))

        fun peek(offset: Int): Token = tokens[minOf(position + offset, tokens.size - 1)]

        fun advance() {
            if (position < tokens.size - 1) position++
        }

        private fun eat(): Token = peek(0).also { advance() }

        /** A missing token (zero width, no trivia), as Roslyn's `CreateMissingToken`. */
        private fun missing(): Token = Token(Token.Kind.Identifier, "", "", "")

        /** `ParseType`: `ref`, maybe `readonly`, then the type; never fails (a missing identifier when nothing parses). */
        fun parseType(): Node = tryParseType() ?: IdentifierName(missing())

        /** Whether a type parses at the current position (for the explicit return type of a lambda). */
        fun tryParseTypeStrict(): Boolean = tryParseType() != null

        private fun tryParseType(): Node? {
            val start = position
            val t = peek(0)
            if (t.isIdentifier && t.text == "ref") {
                val refKeyword = eat()
                val readOnlyKeyword = if (peek(0).isIdentifier && peek(0).text == "readonly") eat() else null
                val type = tryParseTypeCore()
                if (type == null) {
                    position = start
                    return null
                }
                return RefType(refKeyword, readOnlyKeyword, type)
            }
            return tryParseTypeCore()
        }

        /** `ParseTypeCore`: the underlying type, then `?`, `*` and the array rank specifiers. */
        private fun tryParseTypeCore(): Node? {
            var type = tryParseUnderlyingType() ?: return null
            while (true) {
                val t = peek(0)
                when {
                    t.isPunctuation("?") && type !is NullableType -> type = NullableType(type, eat())
                    t.isPunctuation("*") && type !is ArrayType -> type = PointerType(type, eat())
                    t.isPunctuation("[") -> {
                        val rankTokens = ArrayList<Token>()
                        while (peek(0).isPunctuation("[")) {
                            val save = position
                            val rank = tryParseRankSpecifier()
                            if (rank == null) {
                                position = save
                                break
                            }
                            rankTokens.addAll(rank)
                        }
                        if (rankTokens.isEmpty()) return type
                        type = ArrayType(type, rankTokens)
                    }

                    else -> return type
                }
            }
        }

        /** An opening bracket up to the matching closing one: the sizes are expressions, kept as tokens. */
        private fun tryParseRankSpecifier(): List<Token>? {
            val result = ArrayList<Token>()
            var depth = 0
            while (true) {
                val t = peek(0)
                if (t.kind == Token.Kind.EndOfFile) return null
                result.add(eat())
                if (t.kind == Token.Kind.Punctuation) {
                    when (t.text) {
                        "[", "(", "{" -> depth++
                        "]", ")", "}" -> {
                            depth--
                            if (depth == 0) return if (t.text == "]") result else null
                        }
                    }
                }
            }
        }

        /** `ParseUnderlyingType`: a predefined type, a (qualified) name, or a tuple. */
        private fun tryParseUnderlyingType(): Node? {
            val t = peek(0)
            if (t.isIdentifier && !t.text.startsWith("@") && t.text in predefinedTypes) return PredefinedType(eat())
            if (t.isIdentifier && (t.text == "global" || isTrueIdentifier(t))) return tryParseQualifiedName()
            if (t.isPunctuation("(")) return tryParseTupleType()
            return null
        }

        private fun isTrueIdentifier(t: Token): Boolean = t.isIdentifier && (t.text.startsWith("@") || CSharpSyntaxFacts.getKeywordKind(t.text) == null)

        /** `ParseQualifiedName`: an alias-qualified or simple name, then `.` names. */
        private fun tryParseQualifiedName(): Node? {
            var name: Node = tryParseAliasQualifiedName() ?: return null
            while (peek(0).isPunctuation(".") || peek(0).isPunctuation("::")) {
                val save = position
                val dot = eat()
                val right = tryParseSimpleName()
                if (right == null) {
                    position = save
                    break
                }
                name = QualifiedName(name, dot, right)
            }
            return name
        }

        private fun tryParseAliasQualifiedName(): Node? {
            val name = tryParseSimpleName() ?: return null
            if (name is IdentifierName && peek(0).isPunctuation("::")) {
                val save = position
                val colonColon = eat()
                val right = tryParseSimpleName()
                if (right == null) {
                    position = save
                    return name
                }
                return AliasQualifiedName(name, colonColon, right)
            }
            return name
        }

        /** `ParseSimpleName`: an identifier, generic when a type argument list follows. */
        private fun tryParseSimpleName(): Node? {
            val t = peek(0)
            if (!t.isIdentifier || !(t.text == "global" || isTrueIdentifier(t))) return null
            val identifier = eat()
            if (peek(0).isPunctuation("<")) {
                val save = position
                val typeArguments = tryParseTypeArgumentList()
                if (typeArguments != null) return GenericName(identifier, typeArguments)
                position = save
            }
            return IdentifierName(identifier)
        }

        /** `<` types (or omitted arguments) separated by `,`, then `>`; null when that is not what follows. */
        private fun tryParseTypeArgumentList(): TypeArgumentList? {
            val lessThan = eat()
            val arguments = ArrayList<Node>()
            val separators = ArrayList<Token>()
            while (true) {
                val t = peek(0)
                if (t.isPunctuation(",") || t.isPunctuation(">")) {
                    // An omitted type argument (`List<>`, `Dictionary<,>`).
                    arguments.add(OmittedTypeArgument(missing()))
                } else {
                    arguments.add(tryParseType() ?: return null)
                }
                val next = peek(0)
                if (next.isPunctuation(",")) {
                    separators.add(eat())
                    continue
                }
                if (next.isPunctuation(">")) return TypeArgumentList(lessThan, arguments, separators, eat())
                return null
            }
        }

        /** `ParseTupleType`: `(` elements of a type and an optional name `)`. */
        private fun tryParseTupleType(): Node? {
            val start = position
            val open = eat()
            val elements = ArrayList<TupleElement>()
            val separators = ArrayList<Token>()
            while (true) {
                val type = tryParseType()
                if (type == null) {
                    position = start
                    return null
                }
                val name = if (isTrueIdentifier(peek(0))) eat() else null
                elements.add(TupleElement(type, name))
                val next = peek(0)
                if (next.isPunctuation(",")) {
                    separators.add(eat())
                    continue
                }
                if (next.isPunctuation(")")) return TupleType(open, elements, separators, eat())
                position = start
                return null
            }
        }
    }
}
