// Ported from Roslyn src/Compilers/CSharp/Portable/Parser/Lexer.cs, Lexer_StringLiteral.cs, Lexer_RawStringLiteral.cs
// and CharacterInfo.cs at roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * The parts of Roslyn's lexer that are not regular: literals (strings, characters, raw and interpolated strings) and
 * identifiers with escapes. Methods keep Roslyn's names. Only extents and kinds are computed: values and diagnostics
 * are the parser's business; the few diagnostics that change extents (Roslyn's `RecoveringFromRunawayLexing`) are
 * tracked as a flag.
 *
 * Works on [buffer] up to [end] (exclusive): Roslyn's `IsReallyAtEnd`. [pos] is the scanning position.
 */
class CSharpLiteralScanner(private val buffer: CharSequence, private val end: Int, @JvmField var pos: Int) {

    private fun peek(offset: Int = 0): Char {
        val i = pos + offset
        return if (i < end) buffer[i] else INVALID
    }

    private fun atEnd(): Boolean = pos >= end

    private fun consume(ch: Char): Int {
        val start = pos
        while (pos < end && buffer[pos] == ch) pos++
        return pos - start
    }

    /** `ConsumeWhitespace` */
    private fun consumeWhitespace() {
        while (pos < end && isWhitespace(buffer[pos])) pos++
    }

    /** `SlidingTextWindow.AdvancePastNewLine` */
    private fun advancePastNewLine() {
        pos += if (peek() == '\r' && peek(1) == '\n') 2 else 1
    }

    /** `ScanUtf8Suffix` */
    private fun scanUtf8Suffix(): Boolean {
        if ((peek() == 'u' || peek() == 'U') && peek(1) == '8') {
            pos += 2
            return true
        }
        return false
    }

    // ---- Lexer_StringLiteral.cs ----

    /**
     * `Lexer.ScanStringLiteral` (not in a directive): a character or string literal from the quote at [pos], or a raw
     * string literal for three quotes. Unterminated literals stop before a new line or at the end.
     */
    fun scanStringLiteral(): IElementType {
        val quote = peek()
        if (quote == '"' && peek(1) == '"' && peek(2) == '"') return scanRawStringLiteral()
        pos++
        while (true) {
            val ch = peek()
            if (ch == '\\') {
                scanEscapeSequence()
            } else if (ch == quote) {
                pos++
                break
            } else if (isNewLine(ch) || atEnd()) {
                break
            } else {
                pos++
            }
        }
        if (quote == '\'') return SyntaxKind.CharacterLiteralToken
        return if (scanUtf8Suffix()) SyntaxKind.Utf8StringLiteralToken else SyntaxKind.StringLiteralToken
    }

    /**
     * `Lexer.ScanEscapeSequence`: consumes the backslash and the next character whatever it is (a new line too, as
     * Roslyn's `NextChar`), hex digits of `\x`, `\u`, `\U`. Returns the escaped character (for `{`/`}` checks).
     */
    private fun scanEscapeSequence(): Char {
        val start = pos
        pos++ // backslash
        val ch = peek()
        if (!atEnd()) pos++
        return when (ch) {
            'x', 'u', 'U' -> {
                pos = start
                scanUnicodeEscape().toChar()
            }
            '0' -> '\u0000'
            'a' -> '\u0007'
            'b' -> '\u0008'
            'e' -> '\u001b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'v' -> '\u000b'
            else -> ch
        }
    }

    /**
     * `Lexer.ScanUnicodeEscape` (also for `\x`): consumes `\`, the letter and up to 8 (`U`) or 4 hex digits. Returns
     * the escaped UTF-16 character (the high surrogate for a supplementary code point; the letter itself when no hex
     * digit follows, as Roslyn does).
     */
    fun scanUnicodeEscape(): Int {
        pos++ // backslash
        val letter = peek()
        pos++
        if (!isHexDigit(peek())) return letter.code
        val max = if (letter == 'U') 8 else 4
        var value = 0L
        for (i in 0 until max) {
            val c = peek()
            if (!isHexDigit(c)) break
            value = (value shl 4) + Character.digit(c, 16)
            pos++
        }
        if (letter != 'U') return value.toInt()
        if (value > 0x10FFFF) return letter.code
        return if (value > 0xFFFF) Character.highSurrogate(value.toInt()).code else value.toInt()
    }

    /** `Lexer.ScanVerbatimStringLiteral`: `@"..."` (or `@@"`, an error) with `""` escapes, over lines. */
    fun scanVerbatimStringLiteral(): IElementType {
        consume('@')
        pos++ // "
        while (true) {
            val ch = peek()
            if (ch == '"') {
                pos++
                if (peek() == '"') {
                    pos++
                    continue
                }
                break
            }
            if (atEnd()) break
            pos++
        }
        return if (scanUtf8Suffix()) SyntaxKind.Utf8StringLiteralToken else SyntaxKind.StringLiteralToken
    }

    // ---- Lexer_RawStringLiteral.cs ----

    /**
     * `Lexer.ScanStringLiteral(inDirective: true)`: a string in a directive has no escapes and no `u8` suffix; three
     * quotes still scan a raw literal (an error, `ERR_RawStringNotInDirectives`, the kind is `StringLiteralToken`).
     */
    fun scanDirectiveStringLiteral() {
        if (peek(1) == '"' && peek(2) == '"') {
            scanRawStringLiteral(inDirective = true)
            return
        }
        pos++
        while (true) {
            val ch = peek()
            if (ch == '"' && !atEnd()) {
                pos++
                return
            }
            if (isNewLine(ch) || atEnd()) return
            pos++
        }
    }

    /** `Lexer.ScanRawStringLiteral` */
    private fun scanRawStringLiteral(inDirective: Boolean = false): IElementType {
        val startingQuoteCount = consume('"')
        consumeWhitespace()
        val multiLine = isNewLine(peek())
        if (multiLine) scanMultiLineRawStringLiteral(startingQuoteCount) else scanSingleLineRawStringLiteral(startingQuoteCount)
        val utf8 = !inDirective && scanUtf8Suffix()
        return when {
            multiLine && utf8 -> SyntaxKind.Utf8MultiLineRawStringLiteralToken
            multiLine -> SyntaxKind.MultiLineRawStringLiteralToken
            utf8 -> SyntaxKind.Utf8SingleLineRawStringLiteralToken
            else -> SyntaxKind.SingleLineRawStringLiteralToken
        }
    }

    /** `Lexer.ScanSingleLineRawStringLiteral` */
    private fun scanSingleLineRawStringLiteral(startingQuoteCount: Int) {
        while (true) {
            val ch = peek()
            if (isNewLine(ch) || atEnd()) return
            if (ch != '"') {
                pos++
                continue
            }
            if (consume('"') >= startingQuoteCount) return
        }
    }

    /** `Lexer.ScanMultiLineRawStringLiteral` */
    private fun scanMultiLineRawStringLiteral(startingQuoteCount: Int) {
        while (scanMultiLineRawStringLiteralLine(startingQuoteCount)) Unit
    }

    private fun scanMultiLineRawStringLiteralLine(startingQuoteCount: Int): Boolean {
        advancePastNewLine()
        consumeWhitespace()
        if (consume('"') >= startingQuoteCount) return false
        while (true) {
            val ch = peek()
            if (atEnd()) return false
            if (isNewLine(ch)) return true
            if (ch == '"') {
                if (consume('"') >= startingQuoteCount) return false
            } else {
                pos++
            }
        }
    }

    // ---- interpolated strings: Lexer.InterpolatedOrRawStringScanner ----

    /**
     * `Lexer.TryScanAtStringToken`: `@"` or `@$"` (any number of `@`). Returns null, with [pos] unchanged, when the
     * `@` sequence is followed by neither.
     */
    fun tryScanAtStringToken(): InterpolatedString? {
        var index = 0
        while (peek(index) == '@') index++
        return when (peek(index)) {
            '"' -> {
                scanVerbatimStringLiteral()
                NOT_INTERPOLATED
            }
            '$' -> scanInterpolatedStringLiteral()
            else -> null
        }
    }

    /** `Lexer.TryScanInterpolatedString`: `$` followed by `$`, `@` or `"`. Null with [pos] unchanged otherwise. */
    fun tryScanInterpolatedString(): InterpolatedString? {
        val next = peek(1)
        return if (next == '$' || next == '@' || next == '"') scanInterpolatedStringLiteral() else null
    }

    /** `Lexer.ScanInterpolatedStringLiteral` / `ScanInterpolatedOrRawStringLiteralTop`: the whole literal from [pos]. */
    fun scanInterpolatedStringLiteral(): InterpolatedString = InterpolatedScanner().scanStringLiteralTop()

    /** `Lexer.ScanToEndOfLine` */
    private fun scanToEndOfLine() {
        while (!isNewLine(peek()) && !atEnd()) pos++
    }

    /** `Lexer.ScanMultiLineComment` with `/` or `@` (Razor comment) as the delimiter. */
    private fun scanMultiLineComment(delimiter: Char) {
        pos += 2
        while (true) {
            if (atEnd()) return
            if (peek() == '*' && peek(1) == delimiter) {
                pos += 2
                return
            }
            pos++
        }
    }

    /** `Lexer.InterpolatedOrRawStringScanner` for interpolated strings (raw literals without `$` use [scanRawStringLiteral]). */
    private inner class InterpolatedScanner {
        /** Roslyn's `Error != null`: drives `RecoveringFromRunawayLexing`. */
        private var error = false
        private val interpolations = ArrayList<Interpolation>()

        private fun isAtEnd(kind: InterpolatedStringKind): Boolean =
            isAtEnd(allowNewline = kind == InterpolatedStringKind.Verbatim || kind == InterpolatedStringKind.MultiLineRaw)

        private fun isAtEnd(allowNewline: Boolean): Boolean = (!allowNewline && isNewLine(peek())) || atEnd()

        /** `ScanStringLiteralTop` */
        fun scanStringLiteralTop(): InterpolatedString {
            val open = scanOpenQuote()
            val openQuoteEnd = pos
            if (!open.succeeded) {
                return InterpolatedString(open.kind, openQuoteEnd, interpolations, pos, pos)
            }
            scanInterpolatedStringLiteralContents(open.kind, open.dollarCount, open.quoteCount)
            val closeQuoteStart = pos
            scanInterpolatedStringLiteralEnd(open.kind, open.quoteCount)
            return InterpolatedString(open.kind, openQuoteEnd, interpolations, closeQuoteStart, pos)
        }

        private inner class OpenQuote(
            val succeeded: Boolean,
            val kind: InterpolatedStringKind,
            val dollarCount: Int,
            val quoteCount: Int,
        )

        /** `ScanOpenQuote` */
        private fun scanOpenQuote(): OpenQuote {
            val c0 = peek()
            val c1 = peek(1)
            val c2 = peek(2)
            if ((c0 == '$' && c1 == '@' && c2 == '"') || (c0 == '@' && c1 == '$' && c2 == '"')) {
                pos += 3
                return OpenQuote(true, InterpolatedStringKind.Verbatim, 1, 1)
            }
            if (c0 == '$' && c1 == '"' && (c2 != '"' || peek(3) != '"')) {
                pos += 2
                return OpenQuote(true, InterpolatedStringKind.Normal, 1, 1)
            }
            val prefixAtCount = consume('@')
            val dollarCount = consume('$')
            val suffixAtCount = consume('@')
            val quoteCount = consume('"')
            val totalAtCount = prefixAtCount + suffixAtCount
            if (quoteCount == 0) {
                error = true
                val kind = if (totalAtCount == 1 && dollarCount == 1) InterpolatedStringKind.Verbatim else InterpolatedStringKind.SingleLineRaw
                return OpenQuote(false, kind, dollarCount, quoteCount)
            }
            if (totalAtCount > 0) error = true
            if (quoteCount < 3) error = true
            val afterQuotePosition = pos
            consumeWhitespace()
            return if (isNewLine(peek())) {
                advancePastNewLine()
                OpenQuote(true, InterpolatedStringKind.MultiLineRaw, dollarCount, quoteCount)
            } else {
                pos = afterQuotePosition
                OpenQuote(true, InterpolatedStringKind.SingleLineRaw, dollarCount, quoteCount)
            }
        }

        /** `ScanInterpolatedStringLiteralEnd` */
        private fun scanInterpolatedStringLiteralEnd(kind: InterpolatedStringKind, startingQuoteCount: Int) {
            when (kind) {
                InterpolatedStringKind.Normal, InterpolatedStringKind.Verbatim -> {
                    if (peek() != '"') error = true else pos++
                }
                InterpolatedStringKind.SingleLineRaw -> {
                    if (peek() != '"') {
                        error = true
                    } else if (consume('"') > startingQuoteCount) {
                        error = true
                    }
                }
                InterpolatedStringKind.MultiLineRaw -> {
                    if (isAtEnd(kind)) {
                        error = true
                    } else if (peek() == '"') {
                        consume('"')
                        error = true
                    } else {
                        advancePastNewLine()
                        consumeWhitespace()
                        if (consume('"') > startingQuoteCount) error = true
                    }
                }
            }
        }

        /** `ScanInterpolatedStringLiteralContents` */
        private fun scanInterpolatedStringLiteralContents(kind: InterpolatedStringKind, dollarCount: Int, quoteCount: Int) {
            if (checkForIllegalEmptyMultiLineRawStringLiteral(kind, quoteCount)) return
            while (true) {
                if (isAtEnd(kind)) return
                if (isAtEndOfMultiLineRawLiteral(kind, quoteCount)) return
                when (peek()) {
                    '"' -> if (isEndDelimiterOtherwiseConsume(kind, quoteCount)) return
                    '}' -> handleCloseBraceInContent(kind, dollarCount)
                    '{' -> handleOpenBraceInContent(kind, dollarCount)
                    '\\' -> if (kind == InterpolatedStringKind.Normal) {
                        val ch = scanEscapeSequence()
                        if (ch == '{' || ch == '}') error = true
                    } else {
                        pos++
                    }
                    else -> pos++
                }
            }
        }

        /** `CheckForIllegalEmptyMultiLineRawStringLiteral` (consumed whitespace and short quote runs stay consumed). */
        private fun checkForIllegalEmptyMultiLineRawStringLiteral(kind: InterpolatedStringKind, startingQuoteCount: Int): Boolean {
            if (kind == InterpolatedStringKind.MultiLineRaw) {
                consumeWhitespace()
                val beforeQuotesPosition = pos
                if (consume('"') >= startingQuoteCount) {
                    error = true
                    pos = beforeQuotesPosition
                    return true
                }
            }
            return false
        }

        /** `IsAtEndOfMultiLineRawLiteral` */
        private fun isAtEndOfMultiLineRawLiteral(kind: InterpolatedStringKind, startingQuoteCount: Int): Boolean {
            if (kind == InterpolatedStringKind.MultiLineRaw && isNewLine(peek())) {
                val start = pos
                advancePastNewLine()
                consumeWhitespace()
                val closeQuoteCount = consume('"')
                pos = start
                if (closeQuoteCount >= startingQuoteCount) return true
            }
            return false
        }

        /** `IsEndDelimiterOtherwiseConsume` */
        private fun isEndDelimiterOtherwiseConsume(kind: InterpolatedStringKind, startingQuoteCount: Int): Boolean {
            if (kind == InterpolatedStringKind.Normal || kind == InterpolatedStringKind.Verbatim) {
                if (error) return true
                if (kind == InterpolatedStringKind.Normal) return true
                if (peek(1) != '"') return true
                pos += 2
            } else {
                val beforeQuotePosition = pos
                if (consume('"') >= startingQuoteCount) {
                    pos = beforeQuotePosition
                    return true
                }
            }
            return false
        }

        /** `HandleCloseBraceInContent` */
        private fun handleCloseBraceInContent(kind: InterpolatedStringKind, startingDollarSignCount: Int) {
            if (kind == InterpolatedStringKind.Normal || kind == InterpolatedStringKind.Verbatim) {
                pos++
                if (peek() == '}') pos++ else error = true
            } else if (consume('}') >= startingDollarSignCount) {
                error = true
            }
        }

        private fun handleOpenBraceInContent(kind: InterpolatedStringKind, startingDollarSignCount: Int) {
            if (kind == InterpolatedStringKind.Normal || kind == InterpolatedStringKind.Verbatim) {
                handleOpenBraceInNormalOrVerbatimContent(kind)
            } else {
                handleOpenBraceInRawContent(kind, startingDollarSignCount)
            }
        }

        /** `HandleOpenBraceInNormalOrVerbatimContent` */
        private fun handleOpenBraceInNormalOrVerbatimContent(kind: InterpolatedStringKind) {
            if (peek(1) == '{') {
                pos += 2
                return
            }
            val openBracePosition = pos
            pos++
            val colon = scanInterpolatedStringLiteralHoleBalancedText(kind, '}', isHole = true)
            val closeBracePosition = pos
            if (peek() == '}') pos++ else error = true
            interpolations += Interpolation(openBracePosition, openBracePosition + 1, colon, closeBracePosition, pos)
        }

        /** `HandleOpenBraceInRawContent` */
        private fun handleOpenBraceInRawContent(kind: InterpolatedStringKind, startingDollarSignCount: Int) {
            val openBraceCount = consume('{')
            if (openBraceCount < startingDollarSignCount) return
            val afterOpenBracePosition = pos
            if (openBraceCount >= 2 * startingDollarSignCount) error = true
            val colon = scanInterpolatedStringLiteralHoleBalancedText(kind, '}', isHole = true)
            val beforeCloseBracePosition = pos
            val closeBraceCount = consume('}')
            if (closeBraceCount == 0 || closeBraceCount < startingDollarSignCount) {
                error = true
            } else {
                pos = beforeCloseBracePosition + startingDollarSignCount
            }
            interpolations += Interpolation(
                afterOpenBracePosition - startingDollarSignCount, afterOpenBracePosition, colon, beforeCloseBracePosition, pos,
            )
        }

        /** `ScanFormatSpecifier`: from the colon to the `}` (or `"`, or the end), across new lines. */
        private fun scanFormatSpecifier(kind: InterpolatedStringKind) {
            pos++ // :
            while (true) {
                val ch = peek()
                if (ch == '\\' && kind == InterpolatedStringKind.Normal) {
                    val escaped = scanEscapeSequence()
                    if (escaped == '{' || escaped == '}') error = true
                } else if (ch == '"') {
                    if (kind == InterpolatedStringKind.Verbatim && peek(1) == '"') pos += 2 else return
                } else if (ch == '{') {
                    error = true
                    pos++
                } else if (ch == '}') {
                    return
                } else if (isAtEnd(allowNewline = true)) {
                    return
                } else {
                    pos++
                }
            }
        }

        /**
         * `ScanInterpolatedStringLiteralHoleBalancedText`: leaves [pos] on [endingChar] (if any). Returns the position of
         * the format colon of a hole, -1 when there is none.
         */
        private fun scanInterpolatedStringLiteralHoleBalancedText(kind: InterpolatedStringKind, endingChar: Char, isHole: Boolean): Int {
            while (true) {
                val ch = peek()
                if (isAtEnd(allowNewline = true)) return -1
                when (ch) {
                    '#' -> {
                        error = true
                        pos++
                    }
                    '$' -> if (tryScanInterpolatedString() == null) pos++
                    ':' -> if (isHole) {
                        val colon = pos
                        scanFormatSpecifier(kind)
                        return colon
                    } else {
                        pos++
                    }
                    '}', ')', ']' -> {
                        if (ch == endingChar) return -1
                        error = true
                        pos++
                    }
                    '"' -> {
                        if (error) return -1
                        scanStringLiteral()
                    }
                    '\'' -> scanStringLiteral()
                    '@' -> if (tryScanAtStringToken() == null) {
                        if (peek(1) == '*') scanMultiLineComment('@') else pos++
                    }
                    '/' -> when (peek(1)) {
                        '/' -> scanToEndOfLine()
                        '*' -> scanMultiLineComment('/')
                        else -> pos++
                    }
                    '{' -> scanInterpolatedStringLiteralHoleBracketed(kind, '}')
                    '(' -> scanInterpolatedStringLiteralHoleBracketed(kind, ')')
                    '[' -> scanInterpolatedStringLiteralHoleBracketed(kind, ']')
                    else -> pos++
                }
            }
        }

        /** `ScanInterpolatedStringLiteralHoleBracketed` */
        private fun scanInterpolatedStringLiteralHoleBracketed(kind: InterpolatedStringKind, endChar: Char) {
            pos++
            scanInterpolatedStringLiteralHoleBalancedText(kind, endChar, isHole = false)
            if (peek() == endChar) pos++
        }
    }

    // ---- identifiers: Lexer.ScanIdentifier_SlowPath ----

    /** Set by [scanIdentifier]: the identifier had a leading `@` or a unicode escape (never a keyword then). */
    @JvmField var identifierIsVerbatimOrEscaped = false

    /**
     * `Lexer.ScanIdentifier_SlowPath` from [pos] (leading `@`s, unicode escapes, formatting characters). Returns true
     * and moves [pos] past the identifier, or returns false with [pos] unchanged.
     */
    fun scanIdentifier(): Boolean {
        val start = pos
        consume('@')
        var verbatimOrEscaped = pos > start
        var identLength = 0
        while (true) {
            var ch = peek()
            var escaped = false
            var escapeEnd = -1
            if (ch == '\\' && (peek(1) == 'u' || peek(1) == 'U')) {
                val save = pos
                ch = scanUnicodeEscape().toChar()
                escapeEnd = pos
                pos = save
                escaped = true
                // Roslyn sets HasIdentifierEscapeSequence before checking the escaped character.
                verbatimOrEscaped = true
            }
            if (atEnd() && !escaped) break
            val accept = when {
                ch == '$' -> false
                ch == '_' || ch in 'a'..'z' || ch in 'A'..'Z' -> true
                ch in '0'..'9' -> identLength > 0
                ch.code < 128 -> false
                identLength == 0 -> isIdentifierStartCharacter(ch)
                else -> isIdentifierPartCharacter(ch)
            }
            if (!accept) break
            if (escaped) {
                verbatimOrEscaped = true
                pos = escapeEnd
            } else {
                pos++
            }
            // Formatting characters are skipped, not added to the identifier's value.
            if (identLength > 0 && Character.getType(ch) == Character.FORMAT.toInt()) continue
            identLength++
        }
        if (identLength == 0) {
            pos = start
            return false
        }
        identifierIsVerbatimOrEscaped = verbatimOrEscaped
        return true
    }

    companion object {
        /** Roslyn's `SlidingTextWindow.InvalidCharacter`. */
        const val INVALID: Char = '\uFFFF'

        /** Marker result of [tryScanAtStringToken] for a verbatim (not interpolated) string. */
        @JvmField val NOT_INTERPOLATED = InterpolatedString(InterpolatedStringKind.Normal, -1, emptyList(), -1, -1)

        /** `SyntaxFacts.IsNewLine` */
        @JvmStatic
        fun isNewLine(ch: Char): Boolean = ch == '\r' || ch == '\n' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029'

        /** `SyntaxFacts.IsWhitespace` */
        @JvmStatic
        fun isWhitespace(ch: Char): Boolean =
            ch == ' ' || ch == '\t' || ch == '\u000B' || ch == '\u000C' || ch == '\u00A0' || ch == '\uFEFF' ||
                ch == '\u001A' || (ch.code > 255 && Character.getType(ch) == Character.SPACE_SEPARATOR.toInt())

        private fun isHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

        private fun isLetterCategory(type: Int): Boolean = when (type) {
            Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
            Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.LETTER_NUMBER.toInt() -> true
            else -> false
        }

        /** `UnicodeCharacterUtilities.IsIdentifierStartCharacter` (per UTF-16 unit: surrogates are not letters). */
        @JvmStatic
        fun isIdentifierStartCharacter(ch: Char): Boolean = when {
            ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' -> true
            ch.code < 128 -> false
            else -> isLetterCategory(Character.getType(ch))
        }

        /** `UnicodeCharacterUtilities.IsIdentifierPartCharacter` */
        @JvmStatic
        fun isIdentifierPartCharacter(ch: Char): Boolean = when {
            ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch in '0'..'9' -> true
            ch.code < 128 -> false
            else -> {
                val type = Character.getType(ch)
                isLetterCategory(type) || when (type) {
                    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.CONNECTOR_PUNCTUATION.toInt(),
                    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
                    Character.FORMAT.toInt() -> true
                    else -> false
                }
            }
        }
    }
}

/** Roslyn's `Lexer.InterpolatedStringKind`. */
enum class InterpolatedStringKind { Normal, Verbatim, SingleLineRaw, MultiLineRaw }

/**
 * Roslyn's `Lexer.Interpolation`: absolute offsets. [colon] is -1 without a format; the close brace range is empty
 * when the hole is unclosed.
 */
class Interpolation(
    @JvmField val openBraceStart: Int,
    @JvmField val openBraceEnd: Int,
    @JvmField val colon: Int,
    @JvmField val closeBraceStart: Int,
    @JvmField val closeBraceEnd: Int,
)

/** The structure of an interpolated string literal as the parser splits it (`ParseInterpolatedOrRawStringToken`). */
class InterpolatedString(
    @JvmField val kind: InterpolatedStringKind,
    @JvmField val openQuoteEnd: Int,
    @JvmField val interpolations: List<Interpolation>,
    @JvmField val closeQuoteStart: Int,
    @JvmField val closeQuoteEnd: Int,
)
