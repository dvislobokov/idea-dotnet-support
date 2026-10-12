// Ported from Roslyn: the XML documentation comment modes of src/Compilers/CSharp/Portable/Parser/Lexer.cs (LexXmlToken
// .. LexXmlWhitespaceAndNewLineTrivia, ScanXmlCrefToken, ScanIdentifier_CrefSlowPath, TryScanXmlEntity,
// ScanUnicodeEscape) and LexerCache.TryGetKeywordKind, roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c. `LX n` in
// comments are line numbers of Lexer.cs. Departures: docs/csharp-psi/GRAMMAR.md, "Doc comments".
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.doc

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLiteralScanner

/** The XML values of Roslyn's `LexerMode` (LX 19): the parser chooses one per token. */
internal object LexMode {
    const val XmlDocComment = 0x0008
    const val XmlElementTag = 0x0010
    const val XmlAttributeTextQuote = 0x0020
    const val XmlAttributeTextDoubleQuote = 0x0040
    const val XmlCrefQuote = 0x0080
    const val XmlCrefDoubleQuote = 0x0100
    const val XmlNameQuote = 0x0200
    const val XmlNameDoubleQuote = 0x0400
    const val XmlCDataSectionText = 0x0800
    const val XmlCommentText = 0x1000
    const val XmlProcessingInstructionText = 0x2000
    const val XmlCharacter = 0x4000
}

/** Roslyn's `XmlDocCommentLocation` (LX 56): the lexer-driven part of the mode, carried from token to token. */
internal object Location {
    const val Start = 0
    const val Interior = 1
    const val Exterior = 2
    const val End = 4
}

/**
 * A token of a doc comment as Roslyn's lexer makes it: all trivia is leading (`Create(info, leading, null, ...)` in
 * every XML mode). [trivia] holds triples (code, start, end) with the codes [DocToken.EXTERIOR], [DocToken.WHITESPACE],
 * [DocToken.END_OF_LINE]. [value] is the `ValueText` where the parser reads it (identifiers, entities, XML characters),
 * null where it equals the text. [endLocation] is the location after the token, from which the next one is lexed.
 */
internal class DocToken(
    @JvmField val kind: IElementType,
    @JvmField val fullStart: Int,
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val trivia: IntArray?,
    @JvmField val value: String?,
    @JvmField val contextualKind: IElementType?,
    @JvmField val endLocation: Int,
) {
    companion object {
        const val EXTERIOR = 0
        const val WHITESPACE = 1
        const val END_OF_LINE = 2
    }
}

/**
 * Roslyn's lexer in the XML doc comment modes over the text of one doc comment ([text] is the whole comment, offsets
 * are relative to it). [delimited]: `/** */` (`XmlDocCommentStyle.Delimited`), else `///` lines. Each [lex] call starts
 * at a position with a location, as Roslyn's blender re-lexes from the state after the previous token.
 *
 * The end of [text] is Roslyn's `IsReallyAtEnd`: for a `///` comment our token ends before the new line of its last
 * line where Roslyn's continues to the next line and stops there (`XmlDocCommentLocation.End`); the gate normalises
 * that (docs/csharp-psi/GRAMMAR.md, "Doc comments").
 */
internal class DocCommentLexer(private val text: CharSequence, private val delimited: Boolean) {
    private val end = text.length
    private var pos = 0
    private var lexemeStart = 0
    private var mode = LexMode.XmlDocComment
    private var location = Location.Start

    private var trivia = IntArray(12)
    private var triviaSize = 0

    // TokenInfo (LX 96)
    private var kind: IElementType? = null
    private var value: String? = null
    private var contextualKind: IElementType? = null

    /** `Lexer.Lex(mode)` (LX 248) from [position] with the lexer-driven [startLocation]. */
    fun lex(mode: Int, position: Int, startLocation: Int): DocToken {
        this.mode = mode
        pos = position
        location = startLocation
        triviaSize = 0
        kind = null
        value = null
        contextualKind = null
        val fullStart = pos
        when (mode) {
            LexMode.XmlDocComment -> { lexXmlDocCommentLeadingTrivia(); start(); scanXmlToken() } // LexXmlToken LX 2825
            LexMode.XmlElementTag -> { lexXmlDocCommentLeadingTriviaWithWhitespace(); start(); scanXmlElementTagToken() } // LX 3181
            LexMode.XmlAttributeTextQuote, LexMode.XmlAttributeTextDoubleQuote -> {
                lexXmlDocCommentLeadingTrivia(); start(); scanXmlAttributeTextToken() // LX 3367
            }
            LexMode.XmlCDataSectionText -> { lexXmlDocCommentLeadingTrivia(); start(); scanXmlCDataSectionTextToken() } // LX 3989
            LexMode.XmlCommentText -> { lexXmlDocCommentLeadingTrivia(); start(); scanXmlCommentTextToken() } // LX 4111
            LexMode.XmlProcessingInstructionText -> {
                lexXmlDocCommentLeadingTrivia(); start(); scanXmlProcessingInstructionTextToken() // LX 4241
            }
            LexMode.XmlCrefQuote, LexMode.XmlCrefDoubleQuote, LexMode.XmlNameQuote, LexMode.XmlNameDoubleQuote -> {
                lexXmlDocCommentLeadingTriviaWithWhitespace(); start(); scanXmlCrefToken() // LexXmlCrefOrNameToken LX 3576
            }
            LexMode.XmlCharacter -> { lexXmlDocCommentLeadingTriviaWithWhitespace(); start(); scanXmlCharacter() } // LX 3520
            else -> throw IllegalArgumentException("not an XML lexer mode: $mode")
        }
        // `Create` with SyntaxKind.None is a BadToken (LX 327).
        val k = kind ?: SyntaxKind.BadToken
        return DocToken(
            k, fullStart, lexemeStart, pos, if (triviaSize == 0) null else trivia.copyOf(triviaSize), value,
            contextualKind, location,
        )
    }

    // --- text window ----------------------------------------------------------------------------------------------

    private fun peek(n: Int = 0): Char = if (pos + n < end) text[pos + n] else INVALID

    private fun atEnd(): Boolean = pos >= end

    /** `SlidingTextWindow.NextChar`: does not move past the end. */
    private fun nextChar(): Char {
        val c = peek()
        if (pos < end) pos++
        return c
    }

    private fun start() {
        lexemeStart = pos
    }

    private fun lexemeText(): String = text.subSequence(lexemeStart, pos).toString()

    private fun advanceIfMatches(s: String): Boolean {
        for (i in s.indices) if (peek(i) != s[i]) return false
        pos += s.length
        return true
    }

    private fun addTrivia(code: Int, s: Int, e: Int) {
        if (triviaSize + 3 > trivia.size) trivia = trivia.copyOf(trivia.size * 2)
        trivia[triviaSize++] = code
        trivia[triviaSize++] = s
        trivia[triviaSize++] = e
    }

    private fun styleIsDelimited() = delimited

    // --- XmlDocComment mode ---------------------------------------------------------------------------------------

    /** `ScanXmlToken` (LX 2839). */
    private fun scanXmlToken() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val ch = peek()
        when {
            ch == '&' -> { scanXmlEntity(); kind = SyntaxKind.XmlEntityLiteralToken }
            ch == '<' -> scanXmlTagStart()
            ch == '\r' || ch == '\n' -> scanXmlTextLiteralNewLineToken()
            ch == INVALID && atEnd() -> kind = SyntaxKind.EndOfDocumentationCommentToken
            isNewLine(ch) -> scanXmlTextLiteralNewLineToken()
            else -> { scanXmlText(); kind = SyntaxKind.XmlTextLiteralToken }
        }
    }

    /** `ScanXmlTextLiteralNewLineToken` (LX 2892). */
    private fun scanXmlTextLiteralNewLineToken() {
        scanEndOfLine()
        kind = SyntaxKind.XmlTextLiteralNewLineToken
        location = Location.Exterior
    }

    /** `ScanXmlTagStart` (LX 2900). */
    private fun scanXmlTagStart() {
        if (peek(1) == '!') {
            if (peek(2) == '-' && peek(3) == '-') {
                pos += 4
                kind = SyntaxKind.XmlCommentStartToken
            } else if (peek(2) == '[' && peek(3) == 'C' && peek(4) == 'D' && peek(5) == 'A' && peek(6) == 'T' &&
                peek(7) == 'A' && peek(8) == '['
            ) {
                pos += 9
                kind = SyntaxKind.XmlCDataStartToken
            } else {
                pos++
                kind = SyntaxKind.LessThanToken
            }
        } else if (peek(1) == '/') {
            pos += 2
            kind = SyntaxKind.LessThanSlashToken
        } else if (peek(1) == '?') {
            pos += 2
            kind = SyntaxKind.XmlProcessingInstructionStartToken
        } else {
            pos++
            kind = SyntaxKind.LessThanToken
        }
    }

    /** `ScanXmlEntity` (LX 2947): consumes `&...;` (as much as forms an entity), the value is the decoded text. */
    private fun scanXmlEntity() {
        pos++ // &
        val sb = StringBuilder()
        var decoded: String? = null
        var ch = peek()
        if (isXmlNameStartChar(ch)) {
            while (isXmlNameChar(peek().also { ch = it })) {
                pos++
                sb.append(ch)
            }
            decoded = when (sb.toString()) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "apos" -> "'"
                "quot" -> "\""
                else -> null
            }
        } else if (ch == '#') {
            pos++
            val isHex = peek() == 'x'
            var charValue = 0L
            if (isHex) {
                pos++ // x
                while (isHexDigit(peek().also { ch = it })) {
                    pos++
                    if (charValue <= 0x7FFFFFF) charValue = (charValue shl 4) + hexValue(ch)
                }
            } else {
                while (isDecDigit(peek().also { ch = it })) {
                    pos++
                    if (charValue <= 0x7FFFFFF) charValue = (charValue shl 3) + (charValue shl 1) + (ch - '0')
                }
            }
            if (matchesProductionForXmlChar(charValue)) decoded = fromUtf32(charValue)
        }
        if (peek() == ';') pos++
        value = decoded ?: lexemeText()
    }

    /** `ScanXmlText` (LX 3116). */
    private fun scanXmlText() {
        // Collect "]]>" strings into their own XmlText.
        if (peek() == ']' && peek(1) == ']' && peek(2) == '>') {
            pos += 3
            return
        }
        while (true) {
            val ch = peek()
            when {
                ch == INVALID && atEnd() -> return
                ch == '&' || ch == '<' || ch == '\r' || ch == '\n' -> return
                ch == '*' && styleIsDelimited() && peek(1) == '/' -> return
                ch == ']' && peek(1) == ']' && peek(2) == '>' -> return
                isNewLine(ch) -> return
                else -> pos++
            }
        }
    }

    // --- XmlElementTag mode ---------------------------------------------------------------------------------------

    /** `ScanXmlElementTagToken` (LX 3206). */
    private fun scanXmlElementTagToken() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val ch = peek()
        when {
            ch == '<' -> scanXmlTagStart()
            ch == '>' -> { pos++; kind = SyntaxKind.GreaterThanToken }
            ch == '/' && peek(1) == '>' -> { pos += 2; kind = SyntaxKind.SlashGreaterThanToken }
            ch == '"' -> { pos++; kind = SyntaxKind.DoubleQuoteToken }
            ch == '\'' -> { pos++; kind = SyntaxKind.SingleQuoteToken }
            ch == '=' -> { pos++; kind = SyntaxKind.EqualsToken }
            ch == ':' -> { pos++; kind = SyntaxKind.ColonToken }
            ch == INVALID && atEnd() -> kind = SyntaxKind.EndOfDocumentationCommentToken
            isXmlNameStartChar(ch) -> {
                scanXmlName()
                value = lexemeText()
                kind = SyntaxKind.IdentifierToken
            }
            // A new line, whitespace or `*/` here is unreachable in Roslyn (asserted: the leading trivia took it). Ours
            // takes one character as a BadToken, so that the parser always makes progress.
            else -> { pos++; kind = null }
        }
    }

    /** `ScanXmlName` (LX 3310): `:` is a token of its own. */
    private fun scanXmlName() {
        while (true) {
            val ch = peek()
            if (ch != ':' && isXmlNameChar(ch)) pos++ else break
        }
    }

    // --- attribute text, CDATA, comment, processing instruction ---------------------------------------------------

    /** `ScanXmlAttributeTextToken` (LX 3381). */
    private fun scanXmlAttributeTextToken() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val ch = peek()
        when {
            ch == '"' && mode == LexMode.XmlAttributeTextDoubleQuote -> { pos++; kind = SyntaxKind.DoubleQuoteToken }
            ch == '\'' && mode == LexMode.XmlAttributeTextQuote -> { pos++; kind = SyntaxKind.SingleQuoteToken }
            ch == '&' -> { scanXmlEntity(); kind = SyntaxKind.XmlEntityLiteralToken }
            ch == '<' -> { pos++; kind = SyntaxKind.LessThanToken }
            ch == '\r' || ch == '\n' -> scanXmlTextLiteralNewLineToken()
            ch == INVALID && atEnd() -> kind = SyntaxKind.EndOfDocumentationCommentToken
            isNewLine(ch) -> scanXmlTextLiteralNewLineToken()
            else -> { scanXmlAttributeText(); kind = SyntaxKind.XmlTextLiteralToken }
        }
    }

    /** `ScanXmlAttributeText` (LX 3454). */
    private fun scanXmlAttributeText() {
        while (true) {
            val ch = peek()
            when {
                ch == '"' && mode == LexMode.XmlAttributeTextDoubleQuote -> return
                ch == '\'' && mode == LexMode.XmlAttributeTextQuote -> return
                ch == '&' || ch == '<' || ch == '\r' || ch == '\n' -> return
                ch == INVALID && atEnd() -> return
                ch == '*' && styleIsDelimited() && peek(1) == '/' -> return
                isNewLine(ch) -> return
                else -> pos++
            }
        }
    }

    /** `ScanXmlCDataSectionTextToken` (LX 4003) with `ScanXmlCDataSectionText` (LX 4056). */
    private fun scanXmlCDataSectionTextToken() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val ch = peek()
        when {
            ch == ']' && peek(1) == ']' && peek(2) == '>' -> { pos += 3; kind = SyntaxKind.XmlCDataEndToken }
            ch == '\r' || ch == '\n' -> scanXmlTextLiteralNewLineToken()
            ch == INVALID && atEnd() -> kind = SyntaxKind.EndOfDocumentationCommentToken
            isNewLine(ch) -> scanXmlTextLiteralNewLineToken()
            else -> {
                while (true) {
                    val c = peek()
                    if (c == ']' && peek(1) == ']' && peek(2) == '>') break
                    if (c == '\r' || c == '\n' || isNewLine(c)) break
                    if (c == INVALID && atEnd()) break
                    if (c == '*' && styleIsDelimited() && peek(1) == '/') break
                    pos++
                }
                kind = SyntaxKind.XmlTextLiteralToken
            }
        }
    }

    /** `ScanXmlCommentTextToken` (LX 4125) with `ScanXmlCommentText` (LX 4186). */
    private fun scanXmlCommentTextToken() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val ch = peek()
        when {
            ch == '-' && peek(1) == '-' -> {
                if (peek(2) == '>') {
                    pos += 3
                    kind = SyntaxKind.XmlCommentEndToken
                } else {
                    pos += 2
                    kind = SyntaxKind.MinusMinusToken
                }
            }
            ch == '\r' || ch == '\n' -> scanXmlTextLiteralNewLineToken()
            ch == INVALID && atEnd() -> kind = SyntaxKind.EndOfDocumentationCommentToken
            isNewLine(ch) -> scanXmlTextLiteralNewLineToken()
            else -> {
                while (true) {
                    val c = peek()
                    if (c == '-' && peek(1) == '-') break
                    if (c == '\r' || c == '\n' || isNewLine(c)) break
                    if (c == INVALID && atEnd()) break
                    if (c == '*' && styleIsDelimited() && peek(1) == '/') break
                    pos++
                }
                kind = SyntaxKind.XmlTextLiteralToken
            }
        }
    }

    /** `ScanXmlProcessingInstructionTextToken` (LX 4256) with `ScanXmlProcessingInstructionText` (LX 4310). */
    private fun scanXmlProcessingInstructionTextToken() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val ch = peek()
        when {
            ch == '?' && peek(1) == '>' -> { pos += 2; kind = SyntaxKind.XmlProcessingInstructionEndToken }
            ch == '\r' || ch == '\n' -> scanXmlTextLiteralNewLineToken()
            ch == INVALID && atEnd() -> kind = SyntaxKind.EndOfDocumentationCommentToken
            isNewLine(ch) -> scanXmlTextLiteralNewLineToken()
            else -> {
                while (true) {
                    val c = peek()
                    if (c == '?' && peek(1) == '>') break
                    if (c == '\r' || c == '\n' || isNewLine(c)) break
                    if (c == INVALID && atEnd()) break
                    if (c == '*' && styleIsDelimited() && peek(1) == '/') break
                    pos++
                }
                kind = SyntaxKind.XmlTextLiteralToken
            }
        }
    }

    // --- XmlCharacter mode ----------------------------------------------------------------------------------------

    /** `ScanXmlCharacter` (LX 3539): one character or entity (the lookahead of `IsVerbatimCref`). */
    private fun scanXmlCharacter() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val ch = peek()
        when {
            ch == '&' -> { scanXmlEntity(); kind = SyntaxKind.XmlEntityLiteralToken }
            ch == INVALID && atEnd() -> kind = SyntaxKind.EndOfFileToken
            else -> {
                kind = SyntaxKind.XmlTextLiteralToken
                value = nextChar().toString()
            }
        }
    }

    // --- cref and name attribute values ---------------------------------------------------------------------------

    private val inXmlNameAttributeValue get() = mode == LexMode.XmlNameQuote || mode == LexMode.XmlNameDoubleQuote

    /** `ScanXmlCrefToken` (LX 3599): C#-like tokens; `{`/`}` stand for `<`/`>`, entities for their characters. */
    private fun scanXmlCrefToken() {
        if (location == Location.End) {
            kind = SyntaxKind.EndOfDocumentationCommentToken
            return
        }
        val beforeConsumed = pos
        var consumedChar = nextChar()

        // Special characters: if the corresponding XML entities are seen, these actions are not taken.
        when {
            consumedChar == '"' -> if (mode == LexMode.XmlCrefDoubleQuote || mode == LexMode.XmlNameDoubleQuote) {
                kind = SyntaxKind.DoubleQuoteToken
                return
            }
            consumedChar == '\'' -> if (mode == LexMode.XmlCrefQuote || mode == LexMode.XmlNameQuote) {
                kind = SyntaxKind.SingleQuoteToken
                return
            }
            consumedChar == '<' -> {
                kind = null // BadToken: a real `<` is taken as the start of the next element (IsEndOfCrefAttribute)
                return
            }
            consumedChar == INVALID && atEnd() -> {
                kind = SyntaxKind.EndOfDocumentationCommentToken
                return
            }
            consumedChar == '\r' || consumedChar == '\n' || isNewLine(consumedChar) -> {
                pos = beforeConsumed
                scanXmlTextLiteralNewLineToken()
            }
            consumedChar == '&' -> {
                pos = beforeConsumed
                val entity = tryScanXmlEntity()
                if (entity == null) {
                    pos = beforeConsumed
                    scanXmlEntity()
                    kind = SyntaxKind.XmlEntityLiteralToken
                    return
                }
                // TryScanXmlEntity advances even when it fails; on success the character stands for itself.
                consumedChar = entity
            }
            consumedChar == '{' -> consumedChar = '<'
            consumedChar == '}' -> consumedChar = '>'
        }

        if (kind == null) {
            kind = when (consumedChar) {
                '(' -> SyntaxKind.OpenParenToken
                ')' -> SyntaxKind.CloseParenToken
                '[' -> SyntaxKind.OpenBracketToken
                ']' -> SyntaxKind.CloseBracketToken
                ',' -> SyntaxKind.CommaToken
                '.' -> if (advanceIfMatchesChar('.')) SyntaxKind.DotDotToken else SyntaxKind.DotToken
                '?' -> SyntaxKind.QuestionToken
                '&' -> SyntaxKind.AmpersandToken
                '*' -> SyntaxKind.AsteriskToken
                '|' -> SyntaxKind.BarToken
                '^' -> SyntaxKind.CaretToken
                '%' -> SyntaxKind.PercentToken
                '/' -> SyntaxKind.SlashToken
                '~' -> SyntaxKind.TildeToken
                ':' -> if (advanceIfMatchesChar(':')) SyntaxKind.ColonColonToken else SyntaxKind.ColonToken
                '=' -> if (advanceIfMatchesChar('=')) SyntaxKind.EqualsEqualsToken else SyntaxKind.EqualsToken
                '!' -> if (advanceIfMatchesChar('=')) SyntaxKind.ExclamationEqualsToken else SyntaxKind.ExclamationToken
                '>' -> if (advanceIfMatchesChar('=')) SyntaxKind.GreaterThanEqualsToken else SyntaxKind.GreaterThanToken
                '<' -> when {
                    advanceIfMatchesChar('=') -> SyntaxKind.LessThanEqualsToken
                    advanceIfMatchesChar('<') -> SyntaxKind.LessThanLessThanToken
                    else -> SyntaxKind.LessThanToken
                }
                '+' -> if (advanceIfMatchesChar('+')) SyntaxKind.PlusPlusToken else SyntaxKind.PlusToken
                '-' -> if (advanceIfMatchesChar('-')) SyntaxKind.MinusMinusToken else SyntaxKind.MinusToken
                else -> null
            }
        }
        if (kind != null) return

        // An identifier or an unexpected character.
        pos = beforeConsumed
        val ident = scanIdentifierCref()
        if (ident != null && ident.isNotEmpty()) {
            value = ident
            // LexerCache.TryGetKeywordKind: reserved, then contextual keywords, at most MaxKeywordLength (10) characters.
            val keyword = if (!inXmlNameAttributeValue && !identIsVerbatim && !identHasEscape && ident.length <= 10) {
                CSharpSyntaxFacts.getKeywordKind(ident) ?: CSharpSyntaxFacts.getContextualKeywordKind(ident)
            } else {
                null
            }
            if (keyword != null) {
                if (CSharpSyntaxFacts.isContextualKeyword(keyword)) {
                    kind = SyntaxKind.IdentifierToken
                    contextualKind = keyword
                } else {
                    kind = keyword
                }
            } else {
                kind = SyntaxKind.IdentifierToken
            }
        } else if (consumedChar == '@') {
            // `@` not followed by an identifier.
            if (peek() == '@') {
                nextChar()
                value = ""
            } else {
                scanXmlEntity()
            }
            kind = SyntaxKind.IdentifierToken
        } else if (peek() == '&') {
            scanXmlEntity()
            kind = SyntaxKind.XmlEntityLiteralToken
        } else {
            nextChar()
            kind = null // BadToken
        }
    }

    /** `AdvanceIfMatches(char)` (LX 3896): the character, `{`/`}` for `<`/`>`, or an entity standing for it. */
    private fun advanceIfMatchesChar(ch: Char): Boolean {
        val peekCh = peek()
        if (peekCh == ch || (peekCh == '{' && ch == '<') || (peekCh == '}' && ch == '>')) {
            pos++
            return true
        }
        if (peekCh == '&') {
            val p = pos
            if (tryScanXmlEntity() == ch && entitySurrogate == INVALID) return true
            pos = p
        }
        return false
    }

    private var entitySurrogate = INVALID

    /**
     * `TryScanXmlEntity` (LX 4739): the character an entity stands for (the low surrogate in [entitySurrogate]), or
     * null; always advances, even on failure.
     */
    private fun tryScanXmlEntity(): Char? {
        pos++ // &
        entitySurrogate = INVALID
        when (peek()) {
            'l' -> if (advanceIfMatches("lt;")) return '<'
            'g' -> if (advanceIfMatches("gt;")) return '>'
            'a' -> {
                if (advanceIfMatches("amp;")) return '&'
                if (advanceIfMatches("apos;")) return '\''
            }
            'q' -> if (advanceIfMatches("quot;")) return '"'
            '#' -> {
                pos++
                var v = 0L
                if (advanceIfMatches("x")) {
                    while (isHexDigit(peek())) {
                        val d = peek()
                        pos++
                        if (v <= 0x7FFFFFF) v = (v shl 4) + hexValue(d) else return null
                    }
                } else {
                    while (isDecDigit(peek())) {
                        val d = peek()
                        pos++
                        if (v <= 0x7FFFFFF) v = (v shl 3) + (v shl 1) + (d - '0') else return null
                    }
                }
                if (advanceIfMatches(";")) {
                    val chars = charsFromUtf32(v)
                    entitySurrogate = chars.second
                    return chars.first
                }
            }
        }
        return null
    }

    private var identIsVerbatim = false
    private var identHasEscape = false

    /**
     * `ScanIdentifier` in a cref or name attribute value: `ScanIdentifier_CrefSlowPath` (LX 1634), which accepts entities
     * inside the identifier (the fast path only shortcuts plain ASCII identifiers to the same result). Returns the
     * value text, or null with the position unchanged.
     */
    private fun scanIdentifierCref(): String? {
        val start = pos
        val ident = StringBuilder()
        identIsVerbatim = false
        identHasEscape = false
        if (advanceIfMatchesChar('@')) {
            // In name attribute values the `@` is part of the value text (to match dev11).
            if (inXmlNameAttributeValue) ident.append('@') else identIsVerbatim = true
        }
        loop@ while (true) {
            val beforeConsumed = pos
            var consumedChar: Char
            var consumedSurrogate: Char
            if (peek() == '&') {
                val c = tryScanXmlEntity()
                if (c == null) {
                    pos = beforeConsumed
                    break@loop
                }
                consumedChar = c
                consumedSurrogate = entitySurrogate
            } else {
                consumedChar = nextChar()
                consumedSurrogate = INVALID
            }
            var isEscaped = false
            while (true) { // `top:`
                val c = consumedChar
                if (c == '\\' && !isEscaped && pos == beforeConsumed + 1 && (peek() == 'u' || peek() == 'U')) {
                    identHasEscape = true
                    pos = beforeConsumed
                    isEscaped = true
                    val r = scanUnicodeEscape()
                    consumedChar = r.first
                    consumedSurrogate = r.second
                    continue
                }
                when {
                    c == '_' || c in 'a'..'z' || c in 'A'..'Z' -> {}
                    c in '0'..'9' -> if (ident.isEmpty()) {
                        pos = beforeConsumed
                        break@loop
                    }
                    c == ' ' || c == '$' || c == '\t' || c == '.' || c == ';' || c == '(' || c == ')' || c == ',' || c == '<' -> {
                        pos = beforeConsumed
                        break@loop
                    }
                    c == INVALID && atEnd() -> {
                        pos = beforeConsumed
                        break@loop
                    }
                    ident.isEmpty() && c.code > 127 && CSharpLiteralScanner.isIdentifierStartCharacter(c) -> {}
                    ident.isNotEmpty() && c.code > 127 && CSharpLiteralScanner.isIdentifierPartCharacter(c) -> {
                        // Formatting characters are consumed and ignored.
                        if (Character.getType(c) == Character.FORMAT.toInt()) continue@loop
                    }
                    else -> {
                        pos = beforeConsumed
                        break@loop
                    }
                }
                break
            }
            ident.append(consumedChar)
            if (consumedSurrogate != INVALID) ident.append(consumedSurrogate)
        }
        if (ident.isNotEmpty()) return ident.toString()
        pos = start
        return null
    }

    /** `ScanUnicodeEscape(peek: false)` (LX 4630) at a `\`: the character (and the low surrogate) it stands for. */
    private fun scanUnicodeEscape(): Pair<Char, Char> {
        var surrogate = INVALID
        pos++ // \
        var character = peek()
        if (character == 'U') {
            var v = 0L
            pos++
            if (isHexDigit(peek())) {
                for (i in 0 until 8) {
                    character = peek()
                    if (!isHexDigit(character)) break
                    v = (v shl 4) + hexValue(character)
                    pos++
                }
                if (v <= 0x10FFFF) {
                    val chars = charsFromUtf32(v)
                    character = chars.first
                    surrogate = chars.second
                }
            }
        } else {
            var v = 0
            pos++
            if (isHexDigit(peek())) {
                for (i in 0 until 4) {
                    val ch2 = peek()
                    if (!isHexDigit(ch2)) break
                    v = (v shl 4) + hexValue(ch2)
                    pos++
                }
                character = v.toChar()
            }
        }
        return character to surrogate
    }

    // --- trivia ---------------------------------------------------------------------------------------------------

    /**
     * `LexXmlDocCommentLeadingTrivia` (LX 4366): the exterior (`///`, the delimiters of a delimited comment, leading
     * `*`); a no-op unless at the start or the exterior of a line, or before the closing delimiter.
     */
    private fun lexXmlDocCommentLeadingTrivia() {
        val start = pos
        start()
        if (location == Location.Start && delimited) {
            if (peek() == '/' && peek(1) == '*' && peek(2) == '*' && peek(3) != '*') {
                pos += 3
                addTrivia(DocToken.EXTERIOR, lexemeStart, pos)
                location = Location.Interior
                return
            }
        } else if (location == Location.Start || location == Location.Exterior) {
            while (true) {
                val ch = peek()
                when {
                    ch == ' ' || ch == '\t' || ch == '\u000B' || ch == '\u000C' -> pos++
                    ch == '/' && !delimited && peek(1) == '/' && peek(2) == '/' && peek(3) != '/' -> {
                        pos += 3
                        addTrivia(DocToken.EXTERIOR, lexemeStart, pos)
                        location = Location.Interior
                        return
                    }
                    ch == '*' && delimited -> {
                        while (peek() == '*' && peek(1) != '/') pos++
                        if (pos > lexemeStart) addTrivia(DocToken.EXTERIOR, lexemeStart, pos)
                        // On the last line "  */" the `*/` is separate from the whitespace, recognisable as the end.
                        if (peek() == '*' && peek(1) == '/') {
                            val s = pos
                            pos += 2
                            addTrivia(DocToken.EXTERIOR, s, pos)
                            location = Location.End
                        } else {
                            location = Location.Interior
                        }
                        return
                    }
                    isWhitespace(ch) -> pos++
                    else -> {
                        // Not a doc comment line any more (single line): rewind. Delimited: back in the XML text.
                        if (!delimited) {
                            pos = start
                            location = Location.End
                        } else {
                            if (pos > lexemeStart) addTrivia(DocToken.EXTERIOR, lexemeStart, pos)
                            location = Location.Interior
                        }
                        return
                    }
                }
            }
        } else if (location != Location.End && delimited) {
            if (peek() == '*' && peek(1) == '/') {
                pos += 2
                addTrivia(DocToken.EXTERIOR, lexemeStart, pos)
                location = Location.End
            }
        }
    }

    /** `LexXmlDocCommentLeadingTriviaWithWhitespace` (LX 4496). */
    private fun lexXmlDocCommentLeadingTriviaWithWhitespace() {
        while (true) {
            lexXmlDocCommentLeadingTrivia()
            val ch = peek()
            if (location == Location.Interior && (isWhitespace(ch) || isNewLine(ch))) {
                lexXmlWhitespaceAndNewLineTrivia()
            } else {
                break
            }
        }
    }

    /** `LexXmlWhitespaceAndNewLineTrivia` (LX 4519). */
    private fun lexXmlWhitespaceAndNewLineTrivia() {
        start()
        if (location != Location.Interior) return
        val ch = peek()
        when {
            ch == '\r' || ch == '\n' || isNewLine(ch) -> {
                scanEndOfLine()
                addTrivia(DocToken.END_OF_LINE, lexemeStart, pos)
                location = Location.Exterior
            }
            ch == '*' && delimited && peek(1) == '/' -> {} // the end of the comment, not trivia here
            isWhitespace(ch) -> {
                while (isWhitespace(peek())) pos++ // ScanWhitespace (LX 2275)
                addTrivia(DocToken.WHITESPACE, lexemeStart, pos)
            }
        }
    }

    /** `ScanEndOfLine` (LX 2249). */
    private fun scanEndOfLine() {
        val ch = peek()
        if (ch == '\r') {
            pos++
            if (peek() == '\n') pos++
        } else if (ch == '\n' || isNewLine(ch)) {
            pos++
        }
    }

    companion object {
        /** `SlidingTextWindow.InvalidCharacter`. */
        const val INVALID: Char = '￿'

        private fun isNewLine(ch: Char) = CSharpLiteralScanner.isNewLine(ch)
        private fun isWhitespace(ch: Char) = CSharpLiteralScanner.isWhitespace(ch)
        private fun isHexDigit(ch: Char) = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
        private fun isDecDigit(ch: Char) = ch in '0'..'9'
        private fun hexValue(ch: Char): Int = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            else -> ch - 'A' + 10
        }

        /** `Lexer.IsXmlNameStartChar` (LX 3343). */
        fun isXmlNameStartChar(ch: Char) = XmlCharType.isStartNCNameCharXml4e(ch)

        /** `Lexer.IsXmlNameChar` (LX 3354). */
        fun isXmlNameChar(ch: Char) = XmlCharType.isNCNameCharXml4e(ch)

        /** `MatchesProductionForXmlChar` (LX 3103). */
        private fun matchesProductionForXmlChar(v: Long): Boolean =
            v == 0x9L || v == 0xAL || v == 0xDL || v in 0x20L..0xD7FFL || v in 0xE000L..0xFFFDL || v in 0x10000L..0x10FFFFL

        /** `GetCharsFromUtf32`: the high (or only) unit and the low surrogate or [INVALID]. */
        private fun charsFromUtf32(v: Long): Pair<Char, Char> =
            if (v < 0x10000) (v.toInt() and 0xFFFF).toChar() to INVALID
            else (((v - 0x10000) / 0x400 + 0xD800).toInt() and 0xFFFF).toChar() to (((v - 0x10000) % 0x400 + 0xDC00).toInt() and 0xFFFF).toChar()

        private fun fromUtf32(v: Long): String {
            val (high, low) = charsFromUtf32(v)
            return if (low == INVALID) high.toString() else "$high$low"
        }
    }
}
