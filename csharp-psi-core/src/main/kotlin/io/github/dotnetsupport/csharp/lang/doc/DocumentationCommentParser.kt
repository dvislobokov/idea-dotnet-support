// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/DocumentationCommentParser.cs, roslynCommit
// 35d9211b841e7613c1d2f8f5af6d628ace696c4c, with the parts of SyntaxParser.cs it relies on (token window, modes, reset
// points, AddTrailingSkippedSyntax, ConvertToKeyword). `DCP n` in comments are line numbers of
// DocumentationCommentParser.cs, `SP n` of SyntaxParser.cs. Diagnostics are not ported: Roslyn reports none in a doc
// comment with DocumentationMode.Parse, the mode of the IDE and of the oracle (docs/csharp-psi/GRAMMAR.md, "Doc comments").
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.doc

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind

/** An element of the green tree the parser builds; [DocCommentTreeBuilder] turns it into the AST. */
internal sealed class DocGreen

/**
 * A token: [start]..[end] is its text (Roslyn's `Span`), [fullStart]..[start] its leading trivia, described by
 * [trivia] (see [DocToken]). [missing]: inserted by recovery, zero width. A token made of several lexer tokens (`>>`,
 * `+=`, ...) spans them all. [skipped]: Roslyn's trailing `SkippedTokensTrivia` (AddTrailingSkippedSyntax), in order.
 */
internal class DocGreenToken(
    @JvmField val kind: IElementType,
    @JvmField val fullStart: Int,
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val trivia: IntArray?,
    @JvmField val missing: Boolean,
    @JvmField val contextualKind: IElementType? = null,
) : DocGreen() {
    @JvmField var skipped: ArrayList<DocGreenToken>? = null

    /** `FullWidth == 0`: a missing token without skipped syntax. */
    val fullWidthIsZero: Boolean get() = end == fullStart && skipped.isNullOrEmpty()

    override fun toString() = "$kind $start-$end${if (missing) " missing" else ""}"

    companion object {
        fun of(t: DocToken) = DocGreenToken(t.kind, t.fullStart, t.start, t.end, t.trivia, false, t.contextualKind)
        fun missing(kind: IElementType) = DocGreenToken(kind, -1, -1, -1, null, true)
    }
}

/** A node; lists (Roslyn's `SyntaxList`, separated lists) are flattened into [children], as `roslyndump` shows them. */
internal class DocGreenNode(@JvmField val kind: IElementType, @JvmField val children: List<DocGreen>) : DocGreen() {
    override fun toString() = "$kind"
}

/**
 * `DocumentationCommentParser` over the text of one doc comment ([text]: the whole `///` run or `/** */` comment,
 * offsets relative to it). [parseDocumentationComment] returns the `SingleLineDocumentationCommentTrivia` or
 * `MultiLineDocumentationCommentTrivia` node.
 *
 * The token window follows Roslyn's `SyntaxParser` with a blender (the doc comment parser allows mode resets): a
 * token is lexed from the end of the previous token in the window, with the location (start, interior, exterior, end
 * of the comment) that token left; changing the mode drops the tokens from the current one on (they are lexed again
 * in the new mode), while `Reset` restores the position and the mode and keeps the tokens already lexed, even those
 * lexed in another mode (SP 170, 229).
 */
internal class DocumentationCommentParser(private val text: CharSequence, private val isDelimited: Boolean) {
    private val lexer = DocCommentLexer(text, isDelimited)
    private val tokens = ArrayList<DocToken>()
    private var tokenCount = 0
    private var tokenOffset = 0
    private var mode = LexMode.XmlDocComment

    /**
     * Nesting of XML elements, type argument lists and extension member crefs: Roslyn's recursion has no guard of its
     * own here, the JVM's stack is smaller. Past [MAX_DEPTH] the parse gives up: [xmlDepthExceeded] ends all
     * element content (the rest of the comment becomes the `XmlText` of `ParseRemainder`), [crefDepthExceeded] ends
     * the cref (a missing name; the rest of the value is skipped as by `ParseCrefAttributeValue`). A departure,
     * docs/csharp-psi/GRAMMAR.md, "Doc comments".
     */
    private var depth = 0
    private var xmlDepthExceeded = false
    private var crefDepthExceeded = false

    // --- SyntaxParser: token window --------------------------------------------------------------------------------

    private val currentToken: DocToken
        get() {
            if (tokenOffset >= tokenCount) addNewToken()
            return tokens[tokenOffset]
        }

    private val currentKind: IElementType get() = currentToken.kind

    private fun addNewToken() {
        val prev = if (tokenCount > 0) tokens[tokenCount - 1] else null
        var t = lexer.lex(mode, prev?.end ?: 0, prev?.endLocation ?: Location.Start)
        if (t.end == t.fullStart && t.kind != SyntaxKind.EndOfDocumentationCommentToken && t.kind != SyntaxKind.EndOfFileToken) {
            // No progress (a state Roslyn's lexer does not reach on a real doc comment, e.g. `/***/` parsed as one):
            // one character as a BadToken, so the parser always moves.
            t = if (t.fullStart >= text.length) {
                DocToken(SyntaxKind.EndOfDocumentationCommentToken, t.fullStart, t.fullStart, t.fullStart, null, null, null, Location.End)
            } else {
                val e = if (Character.isHighSurrogate(text[t.fullStart]) && t.fullStart + 2 <= text.length) t.fullStart + 2 else t.fullStart + 1
                DocToken(SyntaxKind.BadToken, t.fullStart, t.fullStart, e, null, null, null, Location.Interior)
            }
        }
        if (tokenCount < tokens.size) tokens[tokenCount] = t else tokens.add(t)
        tokenCount++
    }

    /** `PeekToken(n)` (SP 466). */
    private fun peekToken(n: Int): DocToken {
        while (tokenOffset + n >= tokenCount) addNewToken()
        return tokens[tokenOffset + n]
    }

    /** The `Mode` setter (SP 229): a different mode drops the tokens from the current one on. */
    private fun setModeRaw(value: Int) {
        if (mode != value) {
            mode = value
            tokenCount = tokenOffset
        }
    }

    /** `SetMode` (DCP 52): location and style are lexer-driven here, so only the lexer mode is stored. */
    private fun setMode(value: Int): Int {
        val tmp = mode
        setModeRaw(value)
        return tmp
    }

    /** `ResetMode` (DCP 59). */
    private fun resetMode(value: Int) = setModeRaw(value)

    private class ResetPoint(val position: Int, val mode: Int)

    private fun getResetPoint() = ResetPoint(tokenOffset, mode)

    /** `Reset` (SP 170): no truncation, tokens of another mode stay in the window. */
    private fun reset(point: ResetPoint) {
        if (point.position >= tokenCount) peekToken(point.position - tokenOffset)
        mode = point.mode
        tokenOffset = point.position
    }

    private fun eatToken(): DocGreenToken {
        val t = currentToken
        tokenOffset++
        return DocGreenToken.of(t)
    }

    /** `EatToken(kind)` (SP 521): the token, or a missing one without consuming anything. */
    private fun eatToken(kind: IElementType): DocGreenToken =
        if (currentKind == kind) eatToken() else DocGreenToken.missing(kind)

    /** `TryEatToken(kind)` (SP 497). */
    private fun tryEatToken(kind: IElementType): DocGreenToken? = if (currentKind == kind) eatToken() else null

    /** `AddTrailingSkippedSyntax` (SP 980): the skipped tokens go into the trailing trivia of the last token. */
    private fun addTrailingSkippedSyntax(node: DocGreen, skipped: List<DocGreenToken>) {
        val last = lastToken(node)
        val list = last.skipped ?: ArrayList<DocGreenToken>().also { last.skipped = it }
        list.addAll(skipped)
    }

    /** Iterative: crefs like `A.A.A...` nest as deep as they are long. */
    private fun lastToken(node: DocGreen): DocGreenToken {
        var n = node
        while (n is DocGreenNode) n = n.children.last()
        return n as DocGreenToken
    }

    /** `NoTriviaBetween` (LanguageParser): no trailing trivia on the first token, no leading trivia on the second. */
    private fun noTriviaBetween(token1: DocGreenToken, token2: DocToken): Boolean =
        token1.skipped.isNullOrEmpty() && token2.fullStart == token2.start

    /** `ConvertToKeyword` (SP 1104). */
    private fun convertToKeyword(token: DocGreenToken): DocGreenToken {
        val ck = token.contextualKind
        if (ck == null || ck == token.kind) return token
        return DocGreenToken(ck, token.fullStart, token.start, token.end, token.trivia, token.missing).also { it.skipped = token.skipped }
    }

    /** `SyntaxFactory.Token(leading of first, kind, text of all, trailing of last)` over merged operator tokens. */
    private fun merge(kind: IElementType, first: DocGreenToken, last: DocGreenToken): DocGreenToken {
        val start = if (first.missing) last.start else first.start
        val fullStart = if (first.missing) last.fullStart else first.fullStart
        val trivia = if (first.missing) last.trivia else first.trivia
        return DocGreenToken(kind, fullStart, start, last.end, trivia, false).also { it.skipped = last.skipped }
    }

    private fun node(kind: IElementType, vararg children: DocGreen?): DocGreenNode = DocGreenNode(kind, children.filterNotNull())

    private fun node(kind: IElementType, children: List<DocGreen?>): DocGreenNode = DocGreenNode(kind, children.filterNotNull())

    // --- DocumentationCommentParser --------------------------------------------------------------------------------

    /** `ParseDocumentationComment` (DCP 64). */
    fun parseDocumentationComment(): DocGreenNode {
        val nodes = ArrayList<DocGreen>()
        parseXmlNodes(nodes)
        // It's possible that we finish parsing the xml, and we are still left in the middle of an Xml comment
        // (`/// <goo></goo></uhoh>`): the rest is one XmlText.
        if (currentKind != SyntaxKind.EndOfDocumentationCommentToken) parseRemainder(nodes)
        val eoc = eatToken(SyntaxKind.EndOfDocumentationCommentToken)
        val kind = if (isDelimited) SyntaxKind.MultiLineDocumentationCommentTrivia else SyntaxKind.SingleLineDocumentationCommentTrivia
        return DocGreenNode(kind, nodes + eoc)
    }

    /** `ParseRemainder` (DCP 99). */
    private fun parseRemainder(nodes: MutableList<DocGreen>) {
        val saveMode = setMode(LexMode.XmlCDataSectionText)
        val textTokens = ArrayList<DocGreen>()
        while (currentKind != SyntaxKind.EndOfDocumentationCommentToken) textTokens += eatToken()
        nodes += node(SyntaxKind.XmlText, textTokens)
        resetMode(saveMode)
    }

    /** `ParseXmlNodes` (DCP 131). */
    private fun parseXmlNodes(nodes: MutableList<DocGreen>) {
        while (true) nodes += parseXmlNode() ?: return
    }

    /** `ParseXmlNode` (DCP 145). */
    private fun parseXmlNode(): DocGreen? = if (xmlDepthExceeded) null else when (currentKind) {
        SyntaxKind.XmlTextLiteralToken, SyntaxKind.XmlTextLiteralNewLineToken, SyntaxKind.XmlEntityLiteralToken -> parseXmlText()
        SyntaxKind.LessThanToken -> if (depth >= MAX_DEPTH) {
            xmlDepthExceeded = true
            null
        } else {
            depth++
            try { parseXmlElement() } finally { depth-- }
        }
        SyntaxKind.XmlCommentStartToken -> parseXmlComment()
        SyntaxKind.XmlCDataStartToken -> parseXmlCDataSection()
        SyntaxKind.XmlProcessingInstructionStartToken -> parseXmlProcessingInstruction()
        else -> null // EndOfDocumentationCommentToken; anything else: the caller deals with it
    }

    /** `IsXmlNodeStartOrStop` (DCP 169). */
    private fun isXmlNodeStartOrStop(): Boolean = when (currentKind) {
        SyntaxKind.LessThanToken, SyntaxKind.LessThanSlashToken, SyntaxKind.XmlCommentStartToken,
        SyntaxKind.XmlCDataStartToken, SyntaxKind.XmlProcessingInstructionStartToken, SyntaxKind.GreaterThanToken,
        SyntaxKind.SlashGreaterThanToken, SyntaxKind.EndOfDocumentationCommentToken -> true
        else -> false
    }

    /** `ParseXmlText` (DCP 187). */
    private fun parseXmlText(): DocGreen {
        val textTokens = ArrayList<DocGreen>()
        while (currentKind == SyntaxKind.XmlTextLiteralToken || currentKind == SyntaxKind.XmlTextLiteralNewLineToken ||
            currentKind == SyntaxKind.XmlEntityLiteralToken
        ) {
            textTokens += eatToken()
        }
        return node(SyntaxKind.XmlText, textTokens)
    }

    /** `ParseXmlElement` (DCP 202). */
    private fun parseXmlElement(): DocGreen {
        val lessThan = eatToken(SyntaxKind.LessThanToken) // guaranteed
        val saveMode = setMode(LexMode.XmlElementTag)
        val name = parseXmlName()
        val attrs = ArrayList<DocGreen>()
        parseXmlAttributes(name, attrs)

        if (currentKind == SyntaxKind.GreaterThanToken) {
            val startTag = node(SyntaxKind.XmlElementStartTag, listOf(lessThan, name) + attrs + eatToken())
            setMode(LexMode.XmlDocComment)
            val nodes = ArrayList<DocGreen>()
            parseXmlNodes(nodes)

            val endName: DocGreenNode
            val greaterThan: DocGreenToken
            // Roslyn reports XML_EndTagExpected on the missing `</` itself (DCP 231).
            val lessThanSlash = eatToken(SyntaxKind.LessThanSlashToken)
            if (lessThanSlash.missing) {
                resetMode(saveMode)
                endName = node(SyntaxKind.XmlName, DocGreenToken.missing(SyntaxKind.IdentifierToken))
                greaterThan = DocGreenToken.missing(SyntaxKind.GreaterThanToken)
            } else {
                setMode(LexMode.XmlElementTag)
                endName = parseXmlName()
                // We either have the > or we don't (DCP 258).
                if (currentKind != SyntaxKind.GreaterThanToken) {
                    skipBadTokens(endName, null, { currentKind != SyntaxKind.GreaterThanToken }, { isXmlNodeStartOrStop() })
                }
                greaterThan = eatToken(SyntaxKind.GreaterThanToken)
            }
            val endTag = node(SyntaxKind.XmlElementEndTag, lessThanSlash, endName, greaterThan)
            resetMode(saveMode)
            return node(SyntaxKind.XmlElement, listOf(startTag) + nodes + endTag)
        } else {
            val slashGreater = eatToken(SyntaxKind.SlashGreaterThanToken)
            resetMode(saveMode)
            return node(SyntaxKind.XmlEmptyElement, listOf(lessThan, name) + attrs + slashGreater)
        }
    }

    /** `ParseXmlAttributes` (DCP 323). */
    private fun parseXmlAttributes(elementName: DocGreenNode, attrs: MutableList<DocGreen>) {
        while (true) {
            if (currentKind == SyntaxKind.IdentifierToken) {
                attrs += parseXmlAttribute(elementName)
            } else {
                // Roslyn's "not expected" test compares the token kind with SyntaxKind.IdentifierName, a node kind
                // (DCP 344): always true, so bad tokens are skipped up to the end of the tag and the loop ends.
                val skip = skipBadTokens(elementName, attrs, { true }) {
                    currentKind == SyntaxKind.GreaterThanToken || currentKind == SyntaxKind.SlashGreaterThanToken ||
                        currentKind == SyntaxKind.LessThanToken || currentKind == SyntaxKind.LessThanSlashToken ||
                        currentKind == SyntaxKind.EndOfDocumentationCommentToken || currentKind == SyntaxKind.EndOfFileToken
                }
                if (skip == SkipResult.Abort) break
            }
        }
    }

    private enum class SkipResult { Continue, Abort }

    /** `SkipBadTokens` (DCP 371): skipped tokens go to the last element of [list], or to [startNode]. */
    private fun skipBadTokens(
        startNode: DocGreen,
        list: List<DocGreen>?,
        isNotExpected: () -> Boolean,
        abort: () -> Boolean,
    ): SkipResult {
        var badTokens: ArrayList<DocGreenToken>? = null
        var result = SkipResult.Continue
        while (isNotExpected()) {
            if (abort()) {
                result = SkipResult.Abort
                break
            }
            val bad = badTokens ?: ArrayList<DocGreenToken>().also { badTokens = it }
            bad += eatToken()
        }
        val bad = badTokens
        if (bad != null && bad.isNotEmpty()) {
            addTrailingSkippedSyntax(if (list.isNullOrEmpty()) startNode else list.last(), bad)
            return result
        }
        // Somehow we did not consume anything, so tell caller to abort parse rule.
        return SkipResult.Abort
    }

    /** `ParseXmlAttribute` (DCP 438). */
    private fun parseXmlAttribute(elementName: DocGreenNode): DocGreen {
        val attrName = parseXmlName()
        val equals = eatToken(SyntaxKind.EqualsToken)
        if (equals.missing) {
            when (currentKind) {
                // Guess that the user just forgot the '=' (DCP 452).
                SyntaxKind.SingleQuoteToken, SyntaxKind.DoubleQuoteToken -> {}
                // Don't consume the quotes: this attribute is done (DCP 458).
                else -> return node(
                    SyntaxKind.XmlTextAttribute, attrName, equals,
                    DocGreenToken.missing(SyntaxKind.DoubleQuoteToken), DocGreenToken.missing(SyntaxKind.DoubleQuoteToken),
                )
            }
        }

        val localName = xmlNameLocalName(attrName)
        val attrNameText = localName.valueText()
        val hasNoPrefix = xmlNamePrefix(attrName) == null
        if (hasNoPrefix && attrNameText == "cref" && !isVerbatimCref()) {
            // DocumentationCommentXmlNames.AttributeEquals: ordinal.
            val startQuote = parseXmlAttributeStartQuote()
            val quoteKind = startQuote.kind
            val saveMode = setMode(if (quoteKind == SyntaxKind.SingleQuoteToken) LexMode.XmlCrefQuote else LexMode.XmlCrefDoubleQuote)
            val cref = parseCrefAttributeValue()
            resetMode(saveMode)
            val endQuote = parseXmlAttributeEndQuote(quoteKind)
            return node(SyntaxKind.XmlCrefAttribute, attrName, equals, startQuote, cref, endQuote)
        } else if (hasNoPrefix && attrNameText == "name" && xmlElementSupportsNameAttribute(elementName)) {
            val startQuote = parseXmlAttributeStartQuote()
            val quoteKind = startQuote.kind
            val saveMode = setMode(if (quoteKind == SyntaxKind.SingleQuoteToken) LexMode.XmlNameQuote else LexMode.XmlNameDoubleQuote)
            val identifier = parseNameAttributeValue()
            resetMode(saveMode)
            val endQuote = parseXmlAttributeEndQuote(quoteKind)
            return node(SyntaxKind.XmlNameAttribute, attrName, equals, startQuote, identifier, endQuote)
        } else {
            val textTokens = ArrayList<DocGreen>()
            val (startQuote, endQuote) = parseXmlAttributeText(textTokens)
            return node(SyntaxKind.XmlTextAttribute, listOf(attrName, equals, startQuote) + textTokens + endQuote)
        }
    }

    private fun xmlNamePrefix(name: DocGreenNode): DocGreenNode? =
        name.children.firstOrNull { it is DocGreenNode && it.kind == SyntaxKind.XmlPrefix } as DocGreenNode?

    private fun xmlNameLocalName(name: DocGreenNode): DocGreenToken = name.children.last() as DocGreenToken

    private fun DocGreenToken.valueText(): String =
        if (missing) "" else text.subSequence(start, end).toString() // XmlElementTag identifiers: ValueText == Text

    /** `XmlElementSupportsNameAttribute` (DCP 502): `DocumentationCommentXmlNames.ElementEquals` ignores case in C#. */
    private fun xmlElementSupportsNameAttribute(elementName: DocGreenNode): Boolean {
        if (xmlNamePrefix(elementName) != null) return false
        val localName = xmlNameLocalName(elementName).valueText()
        return localName.equals("param", ignoreCase = true) || localName.equals("paramref", ignoreCase = true) ||
            localName.equals("typeparam", ignoreCase = true) || localName.equals("typeparamref", ignoreCase = true)
    }

    /**
     * `IsVerbatimCref` (DCP 517): a cref whose value starts with one character and a colon (`T:System.String`) is
     * taken as text. Looks ahead in XmlCharacter mode and resets, keeping those tokens in the window.
     */
    private fun isVerbatimCref(): Boolean {
        var isVerbatim = false
        val resetPoint = getResetPoint()
        val openQuote = eatToken(if (currentKind == SyntaxKind.SingleQuoteToken) SyntaxKind.SingleQuoteToken else SyntaxKind.DoubleQuoteToken)
        // NOTE: Don't need to save mode, since we're already using a reset point.
        setMode(LexMode.XmlCharacter)
        var current = currentToken
        val quoteText = CSharpSyntaxFacts.getText(openQuote.kind)
        if ((current.kind == SyntaxKind.XmlTextLiteralToken || current.kind == SyntaxKind.XmlEntityLiteralToken) &&
            current.valueText() != quoteText && current.valueText() != ":"
        ) {
            eatToken()
            current = currentToken
            if ((current.kind == SyntaxKind.XmlTextLiteralToken || current.kind == SyntaxKind.XmlEntityLiteralToken) &&
                current.valueText() == ":"
            ) {
                isVerbatim = true
            }
        }
        reset(resetPoint)
        return isVerbatim
    }

    private fun DocToken.valueText(): String = value ?: text.subSequence(start, end).toString()

    private fun DocToken.textOf(): CharSequence = text.subSequence(start, end)

    /** `ParseXmlAttributeText` (DCP 589): returns the quotes, fills [textTokens]. */
    private fun parseXmlAttributeText(textTokens: MutableList<DocGreen>): Pair<DocGreenToken, DocGreenToken> {
        val startQuote = parseXmlAttributeStartQuote()
        val quoteKind = startQuote.kind
        // NOTE: Being lenient and just using EatToken: if the start quote is missing, the end quote is too (DCP 594).
        val endQuote: DocGreenToken
        if (startQuote.missing && startQuote.fullWidthIsZero) {
            endQuote = DocGreenToken.missing(quoteKind)
        } else {
            val saveMode = setMode(if (quoteKind == SyntaxKind.SingleQuoteToken) LexMode.XmlAttributeTextQuote else LexMode.XmlAttributeTextDoubleQuote)
            while (currentKind == SyntaxKind.XmlTextLiteralToken || currentKind == SyntaxKind.XmlTextLiteralNewLineToken ||
                currentKind == SyntaxKind.XmlEntityLiteralToken || currentKind == SyntaxKind.LessThanToken
            ) {
                textTokens += eatToken()
            }
            resetMode(saveMode)
            endQuote = parseXmlAttributeEndQuote(quoteKind)
        }
        return startQuote to endQuote
    }

    /** `ParseXmlAttributeStartQuote` (DCP 629). */
    private fun parseXmlAttributeStartQuote(): DocGreenToken {
        if (isNonAsciiQuotationMark(currentToken)) return skipNonAsciiQuotationMark()
        val quoteKind = if (currentKind == SyntaxKind.SingleQuoteToken) SyntaxKind.SingleQuoteToken else SyntaxKind.DoubleQuoteToken
        return eatToken(quoteKind)
    }

    /** `ParseXmlAttributeEndQuote` (DCP 648). */
    private fun parseXmlAttributeEndQuote(quoteKind: IElementType): DocGreenToken {
        if (isNonAsciiQuotationMark(currentToken)) return skipNonAsciiQuotationMark()
        return eatToken(quoteKind)
    }

    /** `SkipNonAsciiQuotationMark` (DCP 663): a missing `"` with the quotation mark skipped after it. */
    private fun skipNonAsciiQuotationMark(): DocGreenToken {
        val quote = DocGreenToken.missing(SyntaxKind.DoubleQuoteToken)
        addTrailingSkippedSyntax(quote, listOf(eatToken()))
        return quote
    }

    /** `IsNonAsciiQuotationMark` (DCP 676) with `SyntaxFacts.IsNonAsciiQuotationMark`. */
    private fun isNonAsciiQuotationMark(token: DocToken): Boolean {
        if (token.end - token.start != 1) return false
        val c = text[token.start]
        return c == '‘' || c == '’' || c == '“' || c == '”'
    }

    /** `ParseXmlName` (DCP 681). */
    private fun parseXmlName(): DocGreenNode {
        var id = eatToken(SyntaxKind.IdentifierToken)
        var prefix: DocGreenNode? = null
        if (currentKind == SyntaxKind.ColonToken) {
            val colon = eatToken()
            prefix = node(SyntaxKind.XmlPrefix, id, colon)
            id = eatToken(SyntaxKind.IdentifierToken)
        }
        return node(SyntaxKind.XmlName, prefix, id)
    }

    /** `ParseXmlComment` (DCP 720). */
    private fun parseXmlComment(): DocGreen {
        val start = eatToken(SyntaxKind.XmlCommentStartToken)
        val saveMode = setMode(LexMode.XmlCommentText)
        val textTokens = ArrayList<DocGreen>()
        while (currentKind == SyntaxKind.XmlTextLiteralToken || currentKind == SyntaxKind.XmlTextLiteralNewLineToken ||
            currentKind == SyntaxKind.MinusMinusToken
        ) {
            textTokens += eatToken()
        }
        val end = eatToken(SyntaxKind.XmlCommentEndToken)
        resetMode(saveMode)
        return node(SyntaxKind.XmlComment, listOf(start) + textTokens + end)
    }

    /** `ParseXmlCDataSection` (DCP 747). */
    private fun parseXmlCDataSection(): DocGreen {
        val start = eatToken(SyntaxKind.XmlCDataStartToken)
        val saveMode = setMode(LexMode.XmlCDataSectionText)
        val textTokens = ArrayList<DocGreen>()
        while (currentKind == SyntaxKind.XmlTextLiteralToken || currentKind == SyntaxKind.XmlTextLiteralNewLineToken) {
            textTokens += eatToken()
        }
        val end = eatToken(SyntaxKind.XmlCDataEndToken)
        resetMode(saveMode)
        return node(SyntaxKind.XmlCDataSection, listOf(start) + textTokens + end)
    }

    /** `ParseXmlProcessingInstruction` (DCP 763). */
    private fun parseXmlProcessingInstruction(): DocGreen {
        val start = eatToken(SyntaxKind.XmlProcessingInstructionStartToken)
        val saveMode = setMode(LexMode.XmlElementTag) // this mode accepts names
        val name = parseXmlName()
        setMode(LexMode.XmlProcessingInstructionText) // this mode consumes text
        val textTokens = ArrayList<DocGreen>()
        while (currentKind == SyntaxKind.XmlTextLiteralToken || currentKind == SyntaxKind.XmlTextLiteralNewLineToken) {
            textTokens += eatToken()
        }
        val end = eatToken(SyntaxKind.XmlProcessingInstructionEndToken)
        resetMode(saveMode)
        return node(SyntaxKind.XmlProcessingInstruction, listOf(start, name) + textTokens + end)
    }

    // --- Cref (DCP 855) --------------------------------------------------------------------------------------------

    /** `ParseCrefAttributeValue` (DCP 887). */
    private fun parseCrefAttributeValue(): DocGreen {
        crefDepthExceeded = false
        val type = parseCrefType(typeArgumentsMustBeIdentifiers = true, checkForMember = true)
        val result: DocGreen = when {
            type == null -> parseMemberCref()
            isEndOfCrefAttribute -> node(SyntaxKind.TypeCref, type)
            type.kind != SyntaxKind.QualifiedName && currentKind == SyntaxKind.OpenParenToken -> {
                // Special case for crefs like "string()" and "A::B()".
                val parameters = parseCrefParameterList()
                node(SyntaxKind.NameMemberCref, type, parameters)
            }
            else -> {
                val dot = eatToken(SyntaxKind.DotToken)
                val member = parseMemberCref()
                node(SyntaxKind.QualifiedCref, type, dot, member)
            }
        }
        if (!isEndOfCrefAttribute) {
            val badTokens = ArrayList<DocGreenToken>()
            while (!isEndOfCrefAttribute) badTokens += eatToken()
            addTrailingSkippedSyntax(result, badTokens)
        }
        return result
    }

    /** `ParseMemberCref` (DCP 938). */
    private fun parseMemberCref(): DocGreenNode = when {
        currentKind == SyntaxKind.ThisKeyword -> parseIndexerMemberCref()
        currentKind == SyntaxKind.OperatorKeyword -> parseOperatorMemberCref()
        currentKind == SyntaxKind.ExplicitKeyword || currentKind == SyntaxKind.ImplicitKeyword -> parseConversionOperatorMemberCref()
        currentKind == SyntaxKind.IdentifierToken && currentToken.contextualKind == SyntaxKind.ExtensionKeyword &&
            !crefDepthExceeded && depth < MAX_DEPTH -> {
            depth++
            try { parsePossibleExtensionMemberCref() } finally { depth-- }
        }
        else -> parseNameMemberCref()
    }

    /** `ParseNameMemberCref` (DCP 960). */
    private fun parseNameMemberCref(): DocGreenNode {
        val name = parseCrefName(typeArgumentsMustBeIdentifiers = true)
        val parameters = parseCrefParameterList()
        return node(SyntaxKind.NameMemberCref, name, parameters)
    }

    /** `ParseIndexerMemberCref` (DCP 971). */
    private fun parseIndexerMemberCref(): DocGreenNode {
        val thisKeyword = eatToken()
        val parameters = parseBracketedCrefParameterList()
        return node(SyntaxKind.IndexerMemberCref, thisKeyword, parameters)
    }

    /** `ParsePossibleExtensionMemberCref` (DCP 985). */
    private fun parsePossibleExtensionMemberCref(): DocGreenNode {
        val identifierToken = eatToken()
        val typeArguments = if (currentKind == SyntaxKind.LessThanToken) parseTypeArguments(typeArgumentsMustBeIdentifiers = true) else null
        val parameters = if (currentKind == SyntaxKind.OpenParenToken) parseCrefParameterList() else null
        if (parameters == null || currentKind != SyntaxKind.DotToken) {
            val name = if (typeArguments != null) node(SyntaxKind.GenericName, identifierToken, typeArguments)
            else node(SyntaxKind.IdentifierName, identifierToken)
            return node(SyntaxKind.NameMemberCref, name, parameters)
        }
        val dotToken = eatToken(SyntaxKind.DotToken)
        val member = parseMemberCref()
        return node(SyntaxKind.ExtensionMemberCref, convertToKeyword(identifierToken), typeArguments, parameters, dotToken, member)
    }

    /** `ParseOperatorMemberCref` (DCP 1016). */
    private fun parseOperatorMemberCref(): DocGreenNode {
        val operatorKeyword = eatToken()
        val checkedKeyword = tryEatCheckedKeyword(operatorKeyword)

        var operatorToken: DocGreenToken
        if (CSharpSyntaxFacts.isAnyOverloadableOperator(currentKind)) {
            operatorToken = eatToken()
        } else {
            operatorToken = DocGreenToken.missing(SyntaxKind.PlusToken)
            // Consume the bad token if it is an operator of some kind (DCP 1035).
            if (CSharpSyntaxFacts.isUnaryOperatorDeclarationToken(currentKind) || CSharpSyntaxFacts.isBinaryExpressionOperatorToken(currentKind)) {
                addTrailingSkippedSyntax(operatorToken, listOf(eatToken()))
            }
        }

        // Have to fake >>/>>> because it looks like the closing of nested type parameter lists (e.g. A<A<T>>).
        // Have to fake >= so the lexer doesn't mishandle >>= (DCP 1046).
        if (operatorToken.kind == SyntaxKind.GreaterThanToken && noTriviaBetween(operatorToken, currentToken)) {
            if (currentKind == SyntaxKind.GreaterThanToken) {
                val operatorToken2 = eatToken()
                if (noTriviaBetween(operatorToken2, currentToken) &&
                    (currentKind == SyntaxKind.GreaterThanToken || currentKind == SyntaxKind.GreaterThanEqualsToken)
                ) {
                    val operatorToken3 = eatToken()
                    operatorToken = if (operatorToken3.kind == SyntaxKind.GreaterThanToken) {
                        merge(SyntaxKind.GreaterThanGreaterThanGreaterThanToken, operatorToken, operatorToken3)
                    } else {
                        merge(SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken, operatorToken, operatorToken3)
                    }
                } else {
                    operatorToken = merge(SyntaxKind.GreaterThanGreaterThanToken, operatorToken, operatorToken2)
                }
            } else if (currentKind == SyntaxKind.EqualsToken) {
                operatorToken = merge(SyntaxKind.GreaterThanEqualsToken, operatorToken, eatToken())
            } else if (currentKind == SyntaxKind.GreaterThanEqualsToken) {
                operatorToken = merge(SyntaxKind.GreaterThanGreaterThanEqualsToken, operatorToken, eatToken())
            }
        }

        val compound = when (operatorToken.kind) {
            SyntaxKind.PlusToken -> SyntaxKind.PlusEqualsToken
            SyntaxKind.MinusToken -> SyntaxKind.MinusEqualsToken
            SyntaxKind.AsteriskToken -> SyntaxKind.AsteriskEqualsToken
            SyntaxKind.SlashToken -> SyntaxKind.SlashEqualsToken
            SyntaxKind.PercentToken -> SyntaxKind.PercentEqualsToken
            SyntaxKind.AmpersandToken -> SyntaxKind.AmpersandEqualsToken
            SyntaxKind.BarToken -> SyntaxKind.BarEqualsToken
            SyntaxKind.CaretToken -> SyntaxKind.CaretEqualsToken
            SyntaxKind.LessThanLessThanToken -> SyntaxKind.LessThanLessThanEqualsToken
            SyntaxKind.GreaterThanGreaterThanToken -> SyntaxKind.GreaterThanGreaterThanEqualsToken
            SyntaxKind.GreaterThanGreaterThanGreaterThanToken -> SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken
            else -> null
        }
        // tryParseCompoundAssignmentOperatorToken (DCP 1157).
        if (compound != null && noTriviaBetween(operatorToken, currentToken) && currentKind == SyntaxKind.EqualsToken) {
            operatorToken = merge(compound, operatorToken, eatToken())
        }

        val parameters = parseCrefParameterList()
        return node(SyntaxKind.OperatorMemberCref, operatorKeyword, checkedKeyword, operatorToken, parameters)
    }

    /** `TryEatCheckedKeyword` (DCP 1176): `unchecked` is skipped after `operator`. */
    private fun tryEatCheckedKeyword(operatorKeyword: DocGreenToken): DocGreenToken? {
        if (currentKind == SyntaxKind.UncheckedKeyword) {
            addTrailingSkippedSyntax(operatorKeyword, listOf(eatToken()))
            return null
        }
        return tryEatToken(SyntaxKind.CheckedKeyword)
    }

    /** `ParseConversionOperatorMemberCref` (DCP 1205). */
    private fun parseConversionOperatorMemberCref(): DocGreenNode {
        val implicitOrExplicit = eatToken()
        val operatorKeyword = eatToken(SyntaxKind.OperatorKeyword)
        val checkedKeyword = tryEatCheckedKeyword(operatorKeyword)
        val type = parseCrefType(typeArgumentsMustBeIdentifiers = false)!!
        val parameters = parseCrefParameterList()
        return node(SyntaxKind.ConversionOperatorMemberCref, implicitOrExplicit, operatorKeyword, checkedKeyword, type, parameters)
    }

    /** `ParseCrefParameterList` (DCP 1224). */
    private fun parseCrefParameterList(): DocGreenNode? = parseBaseCrefParameterList(useSquareBrackets = false)

    /** `ParseBracketedCrefParameterList` (DCP 1232). */
    private fun parseBracketedCrefParameterList(): DocGreenNode? = parseBaseCrefParameterList(useSquareBrackets = true)

    /** `ParseBaseCrefParameterList` (DCP 1240). */
    private fun parseBaseCrefParameterList(useSquareBrackets: Boolean): DocGreenNode? {
        val openKind = if (useSquareBrackets) SyntaxKind.OpenBracketToken else SyntaxKind.OpenParenToken
        val closeKind = if (useSquareBrackets) SyntaxKind.CloseBracketToken else SyntaxKind.CloseParenToken
        if (currentKind != openKind) return null
        val open = eatToken(openKind)
        val list = ArrayList<DocGreen>()
        while (!crefDepthExceeded && (currentKind == SyntaxKind.CommaToken || isPossibleCrefParameter())) {
            list += parseCrefParameter()
            if (currentKind != closeKind) {
                val comma = eatToken(SyntaxKind.CommaToken)
                // Only do this if it won't be last in the list.
                if (!comma.missing || isPossibleCrefParameter()) list += comma
            }
        }
        // NOTE: nothing follows a cref parameter list, so there's no reason to recover here.
        val close = eatToken(closeKind)
        val kind = if (useSquareBrackets) SyntaxKind.CrefBracketedParameterList else SyntaxKind.CrefParameterList
        return node(kind, listOf(open) + list + close)
    }

    /** `IsPossibleCrefParameter` (DCP 1293). */
    private fun isPossibleCrefParameter(): Boolean = when (val kind = currentKind) {
        SyntaxKind.RefKeyword, SyntaxKind.OutKeyword, SyntaxKind.InKeyword, SyntaxKind.IdentifierToken -> true
        else -> CSharpSyntaxFacts.isPredefinedType(kind)
    }

    /** `ParseCrefParameter` (DCP 1314). */
    private fun parseCrefParameter(): DocGreenNode {
        var refKindOpt: DocGreenToken? = null
        when (currentKind) {
            SyntaxKind.RefKeyword, SyntaxKind.OutKeyword, SyntaxKind.InKeyword -> refKindOpt = eatToken()
        }
        var readOnlyOpt: DocGreenToken? = null
        if (currentKind == SyntaxKind.ReadOnlyKeyword && refKindOpt != null) {
            if (refKindOpt.kind != SyntaxKind.RefKeyword) {
                // `readonly` after `in` or `out` is skipped trivia of the previous keyword.
                addTrailingSkippedSyntax(refKindOpt, listOf(eatToken()))
            } else {
                readOnlyOpt = eatToken()
            }
        }
        val type = parseCrefType(typeArgumentsMustBeIdentifiers = false)!!
        return node(SyntaxKind.CrefParameter, refKindOpt, readOnlyOpt, type)
    }

    /** `ParseCrefName` (DCP 1350). */
    private fun parseCrefName(typeArgumentsMustBeIdentifiers: Boolean): DocGreenNode {
        val identifierToken = eatToken(SyntaxKind.IdentifierToken)
        if (currentKind != SyntaxKind.LessThanToken) return node(SyntaxKind.IdentifierName, identifierToken)
        return node(SyntaxKind.GenericName, identifierToken, parseTypeArguments(typeArgumentsMustBeIdentifiers))
    }

    /** `ParseTypeArguments` (DCP 1362). */
    private fun parseTypeArguments(typeArgumentsMustBeIdentifiers: Boolean): DocGreenNode {
        val open = eatToken()
        val list = ArrayList<DocGreen>()
        while (true) {
            list += parseCrefType(typeArgumentsMustBeIdentifiers)!!
            if (crefDepthExceeded) break
            val currentKind = currentKind
            if (currentKind == SyntaxKind.CommaToken || currentKind == SyntaxKind.IdentifierToken ||
                CSharpSyntaxFacts.isPredefinedType(currentKind)
            ) {
                // NOTE: if the current token is an identifier or predefined type, then we're actually inserting a
                // missing comma.
                list += eatToken(SyntaxKind.CommaToken)
            } else {
                break
            }
        }
        val close = eatToken(SyntaxKind.GreaterThanToken)
        return node(SyntaxKind.TypeArgumentList, listOf(open) + list + close)
    }

    /** `ParseCrefType` (DCP 1420): null only with [checkForMember] (the caller parses a member instead). */
    private fun parseCrefType(typeArgumentsMustBeIdentifiers: Boolean, checkForMember: Boolean = false): DocGreenNode? {
        if (crefDepthExceeded || depth >= MAX_DEPTH) {
            crefDepthExceeded = true
            return node(SyntaxKind.IdentifierName, DocGreenToken.missing(SyntaxKind.IdentifierToken))
        }
        depth++
        try {
            val typeWithoutSuffix = parseCrefTypeHelper(typeArgumentsMustBeIdentifiers, checkForMember)
            return if (typeArgumentsMustBeIdentifiers) typeWithoutSuffix else parseCrefTypeSuffix(typeWithoutSuffix!!)
        } finally {
            depth--
        }
    }

    /** `ParseCrefTypeHelper` (DCP 1440). */
    private fun parseCrefTypeHelper(typeArgumentsMustBeIdentifiers: Boolean, checkForMember: Boolean): DocGreenNode? {
        var leftName: DocGreenNode
        if (CSharpSyntaxFacts.isPredefinedType(currentKind)) {
            // e.g. "int": you can only dot into a predefined type once.
            return node(SyntaxKind.PredefinedType, eatToken())
        } else if (currentKind == SyntaxKind.IdentifierToken && peekToken(1).kind == SyntaxKind.ColonColonToken) {
            // e.g. "A::B"
            var alias = eatToken()
            if (alias.contextualKind == SyntaxKind.GlobalKeyword) alias = convertToKeyword(alias)
            val colonColon = eatToken()
            val name = parseCrefName(typeArgumentsMustBeIdentifiers)
            leftName = node(SyntaxKind.AliasQualifiedName, node(SyntaxKind.IdentifierName, alias), colonColon, name)
        } else {
            // e.g. "A"
            val resetPoint = getResetPoint()
            leftName = parseCrefName(typeArgumentsMustBeIdentifiers)
            if (checkForMember && (isMissing(leftName) || currentKind != SyntaxKind.DotToken)) {
                // If this isn't the first part of a dotted name, then we prefer to represent it as a MemberCrefSyntax.
                reset(resetPoint)
                return null
            }
        }

        while (!crefDepthExceeded && currentKind == SyntaxKind.DotToken) {
            // NOTE: we make a lot of these, but we'll reset, at most, one time.
            val resetPoint = getResetPoint()
            val dot = eatToken()
            val rightName = parseCrefName(typeArgumentsMustBeIdentifiers)
            if (checkForMember && (isMissing(rightName) || currentKind != SyntaxKind.DotToken)) {
                reset(resetPoint) // Go back to before the dot - it must have been the trailing dot.
                return leftName
            }
            leftName = node(SyntaxKind.QualifiedName, leftName, dot, rightName)
        }
        return leftName
    }

    /**
     * `GreenNode.IsMissing`: all tokens are missing (skipped trivia on a missing token does not change that).
     * Iterative: cref names nest as deep as their type arguments.
     */
    private fun isMissing(node: DocGreen): Boolean {
        val stack = ArrayList<DocGreen>().apply { add(node) }
        while (stack.isNotEmpty()) {
            when (val n = stack.removeAt(stack.size - 1)) {
                is DocGreenToken -> if (!n.missing) return false
                is DocGreenNode -> stack.addAll(n.children)
            }
        }
        return true
    }

    /** `ParseCrefTypeSuffix` (DCP 1514): nullable, pointer and array suffixes. */
    private fun parseCrefTypeSuffix(type0: DocGreenNode): DocGreenNode {
        var type = type0
        if (currentKind == SyntaxKind.QuestionToken) type = node(SyntaxKind.NullableType, type, eatToken())
        while (currentKind == SyntaxKind.AsteriskToken) type = node(SyntaxKind.PointerType, type, eatToken())
        if (currentKind == SyntaxKind.OpenBracketToken) {
            val rankList = ArrayList<DocGreen>()
            while (currentKind == SyntaxKind.OpenBracketToken) {
                val open = eatToken()
                val dimensionList = ArrayList<DocGreen>()
                while (currentKind != SyntaxKind.CloseBracketToken) {
                    if (currentKind == SyntaxKind.CommaToken) {
                        // NOTE: trivia will be attached to comma, not omitted array size
                        dimensionList += omittedArraySize()
                        dimensionList += eatToken()
                    } else {
                        break
                    }
                }
                // Don't end on a comma. If the omitted size would be the only element, then skip it unless sizes
                // were expected.
                if (dimensionList.size and 1 == 0) dimensionList += omittedArraySize()
                val close = eatToken(SyntaxKind.CloseBracketToken)
                rankList += node(SyntaxKind.ArrayRankSpecifier, listOf(open) + dimensionList + close)
            }
            type = node(SyntaxKind.ArrayType, listOf(type) + rankList)
        }
        return type
    }

    private fun omittedArraySize(): DocGreenNode =
        node(SyntaxKind.OmittedArraySizeExpression, DocGreenToken(SyntaxKind.OmittedArraySizeExpressionToken, -1, -1, -1, null, false))

    /** `IsEndOfCrefAttribute` (DCP 1586). */
    private val isEndOfCrefAttribute: Boolean
        get() = when (currentKind) {
            SyntaxKind.SingleQuoteToken -> mode == LexMode.XmlCrefQuote
            SyntaxKind.DoubleQuoteToken -> mode == LexMode.XmlCrefDoubleQuote
            SyntaxKind.EndOfFileToken, SyntaxKind.EndOfDocumentationCommentToken -> true
            // A real '<' (not &lt;, etc) is the beginning of the next XML element.
            SyntaxKind.BadToken -> currentToken.textOf().contentEquals("<") || isNonAsciiQuotationMark(currentToken)
            else -> false
        }

    // --- Name attribute values (DCP 1630) --------------------------------------------------------------------------

    /** `ParseNameAttributeValue` (DCP 1632): never reports a parse error, the name fails to bind later. */
    private fun parseNameAttributeValue(): DocGreenNode {
        val identifierToken = eatToken(SyntaxKind.IdentifierToken)
        if (!isEndOfNameAttribute) {
            val badTokens = ArrayList<DocGreenToken>()
            while (!isEndOfNameAttribute) badTokens += eatToken()
            addTrailingSkippedSyntax(identifierToken, badTokens)
        }
        return node(SyntaxKind.IdentifierName, identifierToken)
    }

    /** `IsEndOfNameAttribute` (DCP 1654). */
    private val isEndOfNameAttribute: Boolean
        get() = when (currentKind) {
            SyntaxKind.SingleQuoteToken -> mode == LexMode.XmlNameQuote
            SyntaxKind.DoubleQuoteToken -> mode == LexMode.XmlNameDoubleQuote
            SyntaxKind.EndOfFileToken, SyntaxKind.EndOfDocumentationCommentToken -> true
            SyntaxKind.BadToken -> currentToken.textOf().contentEquals("<") || isNonAsciiQuotationMark(currentToken)
            else -> false
        }

    private companion object {
        /** See [depth]: real doc comments nest a few levels; ~5 frames per level stay far below the JVM's stack. */
        const val MAX_DEPTH = 200
    }
}
