// Ported from Roslyn: src/Compilers/CSharp/Portable/Parser/Lexer_StringLiteral.cs (ScanStringLiteral, ScanEscapeSequence,
// ScanVerbatimStringLiteral, ScanRawInterpolatedStringLiteralEnd), Lexer.cs (ScanUnicodeEscape, ScanNumericLiteral,
// ScanNumericLiteralSingleInteger, GetValueUInt64, GetValueDouble, GetValueSingle, GetValueDecimal, ScanSyntaxToken),
// roslynCommit 35d9211b841e7613c1d2f8f5af6d628ace696c4c.
// Roslyn: Copyright (c) .NET Foundation and Contributors, MIT License (NOTICE.md).
package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpErrorCode
import java.math.BigDecimal
import java.math.BigInteger

/** A diagnostic of Roslyn's lexer on a token: [offset] and [width] relative to the token start (`SyntaxDiagnosticInfo`). */
class CSharpLexerDiagnostic(val code: CSharpErrorCode, val offset: Int, val width: Int, val args: List<Any> = emptyList())

/**
 * The errors Roslyn's lexer puts on a token, from the token's text alone (our lexer computes extents and kinds only).
 * The parser needs [hasError] for `ContainsDiagnostics` decisions on the nodes around the token
 * (`looksLikeVariableInitializer` of `ParseVariableDeclarator` and the like), counted when the token is eaten
 * (`SyntaxParser.advance`); the editor shows [of]. Covered: character and regular string literals (`ERR_NewlineInConst`,
 * `ERR_IllegalEscape`, `ERR_EmptyCharConst`, `ERR_TooManyCharsInConst`), verbatim strings (`ERR_UnterminatedStringLit`,
 * `ERR_IllegalAtSequence`), numeric literals (`ERR_InvalidNumber`, `ERR_InvalidReal`, `ERR_IntOverflow`,
 * `ERR_FloatOverflow`). [of] adds what [hasError] leaves to the parser's other checks: bad characters
 * (`ERR_UnexpectedCharacter`, `ERR_ExpectedVerbatimLiteral`) and raw strings (`ERR_UnterminatedRawString`,
 * `ERR_TooManyQuotesForRawString`). Not covered (docs/csharp-psi/GRAMMAR.md): interpolated strings, identifiers with
 * escapes, the indentation rules of multi-line raw strings. Language-version diagnostics are `SyntaxParser`'s.
 */
object CSharpLexerDiagnostics {
    fun hasError(kind: IElementType, text: CharSequence): Boolean = literal(kind, text, null)

    /** The diagnostics of a token of [kind] with [text], in Roslyn's order. */
    fun of(kind: IElementType, text: CharSequence): List<CSharpLexerDiagnostic> {
        val out = ArrayList<CSharpLexerDiagnostic>(1)
        when (kind) {
            TokenType.BAD_CHARACTER -> badCharacter(text, out)
            SyntaxKind.RazorContentToken -> out += CSharpLexerDiagnostic(CSharpErrorCode.ERR_ExpectedVerbatimLiteral, 1, 1)
            SyntaxKind.IdentifierToken -> {
                // ScanIdentifier_SlowPath: `@@x` is an identifier with ERR_IllegalAtSequence on the `@`s
                var at = 0
                while (at < text.length && text[at] == '@') at++
                if (at >= 2) out += CSharpLexerDiagnostic(CSharpErrorCode.ERR_IllegalAtSequence, 0, at)
            }
            SyntaxKind.SingleLineRawStringLiteralToken, SyntaxKind.MultiLineRawStringLiteralToken,
            SyntaxKind.Utf8SingleLineRawStringLiteralToken, SyntaxKind.Utf8MultiLineRawStringLiteralToken -> rawString(text, out)
            else -> literal(kind, text, out)
        }
        return out
    }

    private fun literal(kind: IElementType, text: CharSequence, out: MutableList<CSharpLexerDiagnostic>?): Boolean = when (kind) {
        SyntaxKind.CharacterLiteralToken -> quotedLiteralHasError(text, '\'', out)
        SyntaxKind.StringLiteralToken, SyntaxKind.Utf8StringLiteralToken -> when {
            text.isEmpty() -> false
            text[0] == '@' -> verbatimHasError(text, out)
            text[0] == '"' && text.length >= 3 && text[1] == '"' && text[2] == '"' -> false // raw: not covered
            text[0] == '"' -> quotedLiteralHasError(text, '"', out)
            else -> false
        }
        SyntaxKind.NumericLiteralToken -> numericHasError(text, out)
        else -> false
    }

    /** `ScanSyntaxToken`, default case and `@`: a bad `@` sequence or an unexpected character (zero-width at its start). */
    private fun badCharacter(text: CharSequence, out: MutableList<CSharpLexerDiagnostic>) {
        if (text.isEmpty()) return
        if (text[0] == '@') {
            out += CSharpLexerDiagnostic(CSharpErrorCode.ERR_ExpectedVerbatimLiteral, 0, 0)
            return
        }
        // ObjectDisplay.FormatLiteral(EscapeNonPrintableCharacters) of an unescaped character
        val shown = if (text[0] != '\\' && text.length == 1 && (Character.isISOControl(text[0]) || Character.getType(text[0]) == Character.FORMAT.toInt())) {
            "\\u%04x".format(text[0].code)
        } else {
            text.toString()
        }
        out += CSharpLexerDiagnostic(CSharpErrorCode.ERR_UnexpectedCharacter, 0, 0, listOf(shown))
    }

    /**
     * `ParseRawStringToken` rescans the token text as a raw interpolated string (`ScanRawInterpolatedStringLiteralEnd`): an
     * unterminated literal is reported zero-width at the end of the token, excess closing quotes on themselves.
     */
    private fun rawString(text: CharSequence, out: MutableList<CSharpLexerDiagnostic>) {
        var body = text.length
        if (body >= 2 && (text[body - 2] == 'u' || text[body - 2] == 'U') && text[body - 1] == '8') body -= 2
        var open = 0
        while (open < body && text[open] == '"') open++
        var close = 0
        while (close < body - open && text[body - 1 - close] == '"') close++
        val lastNewLine = (body - 1 downTo open).firstOrNull { isNewLine(text[it]) }
        val terminated = close >= open && if (lastNewLine != null) {
            // the closing line: whitespace, then the quotes
            (lastNewLine + 1 until body - close).all { CSharpLiteralScanner.isWhitespace(text[it]) }
        } else {
            body - close > open
        }
        if (!terminated) {
            out += CSharpLexerDiagnostic(CSharpErrorCode.ERR_UnterminatedRawString, text.length, 0)
        } else if (close > open) {
            out += CSharpLexerDiagnostic(CSharpErrorCode.ERR_TooManyQuotesForRawString, body - (close - open), close - open)
        }
    }

    /** `ScanStringLiteral` of a `'` or `"` literal (not in a directive). */
    private fun quotedLiteralHasError(text: CharSequence, quote: Char, out: MutableList<CSharpLexerDiagnostic>?): Boolean {
        var error = false
        var length = 0 // UTF-16 length of the value
        var i = 1
        var terminated = false
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\\' -> {
                    val (next, chars, bad) = escape(text, i)
                    if (bad) {
                        error = true
                        out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_IllegalEscape, i, next - i))
                    }
                    length += chars
                    i = next
                }
                ch == quote -> {
                    terminated = true
                    i++
                    break
                }
                isNewLine(ch) -> break
                else -> {
                    length++
                    i++
                }
            }
        }
        if (!terminated) {
            error = true // ERR_NewlineInConst (also at the end of the text)
            out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_NewlineInConst, 0, 0))
        }
        if (quote == '\'' && length != 1) {
            error = true
            out?.add(CSharpLexerDiagnostic(if (length != 0) CSharpErrorCode.ERR_TooManyCharsInConst else CSharpErrorCode.ERR_EmptyCharConst, 0, 0))
        }
        return error
    }

    /** `ScanEscapeSequence` at [start] (a `\`): the index after it, the UTF-16 chars it yields, whether it is illegal. */
    private fun escape(text: CharSequence, start: Int): Triple<Int, Int, Boolean> {
        if (start + 1 >= text.length) return Triple(text.length, 1, true)
        return when (text[start + 1]) {
            '\'', '"', '\\', '0', 'a', 'b', 'e', 'f', 'n', 'r', 't', 'v' -> Triple(start + 2, 1, false)
            'U' -> {
                var i = start + 2
                if (i >= text.length || !isHex(text[i])) return Triple(i, 1, true)
                var value = 0L
                var bad = false
                for (n in 0 until 8) {
                    if (i >= text.length || !isHex(text[i])) {
                        bad = true
                        break
                    }
                    value = (value shl 4) + Character.digit(text[i], 16)
                    i++
                }
                if (value > 0x10FFFF) bad = true
                Triple(i, if (!bad && value > 0xFFFF) 2 else 1, bad)
            }
            'u', 'x' -> {
                val isU = text[start + 1] == 'u'
                var i = start + 2
                if (i >= text.length || !isHex(text[i])) return Triple(i, 1, true)
                var bad = false
                for (n in 0 until 4) {
                    if (i >= text.length || !isHex(text[i])) {
                        if (isU) bad = true
                        break
                    }
                    i++
                }
                Triple(i, 1, bad)
            }
            // ERR_IllegalEscape; the escape still consumes the character after `\`.
            else -> Triple(start + 2, 1, true)
        }
    }

    /** `ScanVerbatimStringLiteral` */
    private fun verbatimHasError(text: CharSequence, out: MutableList<CSharpLexerDiagnostic>?): Boolean {
        var i = 0
        while (i < text.length && text[i] == '@') i++
        if (i >= 2) {
            out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_IllegalAtSequence, 0, i))
            if (out == null) return true
        }
        val atSequence = i >= 2
        if (i >= text.length || text[i] != '"') return atSequence
        i++
        while (i < text.length) {
            if (text[i] == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    i += 2
                    continue
                }
                return atSequence
            }
            i++
        }
        out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_UnterminatedStringLit, 0, 0))
        return true // ERR_UnterminatedStringLit
    }

    /** `ScanNumericLiteral` and the value conversions (`GetValueUInt64`, `GetValueDouble`, `GetValueSingle`, `GetValueDecimal`). */
    private fun numericHasError(text: CharSequence, out: MutableList<CSharpLexerDiagnostic>?): Boolean {
        var i = 0
        var isHex = false
        var isBinary = false
        if (text.length >= 2 && text[0] == '0') {
            when (text[1]) {
                'x', 'X' -> { isHex = true; i = 2 }
                'b', 'B' -> { isBinary = true; i = 2 }
            }
        }
        val digits = StringBuilder()
        var wrongUnderscore = false

        fun singleInteger() {
            if (i < text.length && text[i] == '_' && !isHex && !isBinary) wrongUnderscore = true
            var lastUnderscore = false
            while (i < text.length) {
                val ch = text[i]
                if (ch == '_') {
                    lastUnderscore = true
                } else if (if (isHex) isHex(ch) else if (isBinary) ch == '0' || ch == '1' else ch in '0'..'9') {
                    digits.append(ch)
                    lastUnderscore = false
                } else {
                    break
                }
                i++
            }
            if (lastUnderscore) wrongUnderscore = true
        }

        var real = false
        var suffix = ' '
        var invalidReal = false
        if (isHex || isBinary) {
            singleInteger()
        } else {
            singleInteger()
            if (i < text.length && text[i] == '.' && i + 1 < text.length && text[i + 1] in '0'..'9') {
                real = true
                digits.append('.')
                i++
                singleInteger()
            }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                real = true
                digits.append('e')
                i++
                if (i < text.length && (text[i] == '-' || text[i] == '+')) digits.append(text[i++])
                if (i < text.length && (text[i] in '0'..'9' || text[i] == '_')) singleInteger() else { invalidReal = true; digits.append('0') }
            }
            if (i < text.length) {
                when (text[i]) {
                    'f', 'F', 'd', 'D', 'm', 'M' -> { real = true; suffix = text[i].lowercaseChar() }
                }
            }
        }
        if (wrongUnderscore || invalidReal) {
            // ScanNumericLiteral: ERR_InvalidReal zero-width while scanning, ERR_InvalidNumber over the literal afterwards
            if (invalidReal) out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_InvalidReal, 0, 0))
            if (wrongUnderscore) out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_InvalidNumber, 0, text.length))
            return true
        }
        val value = digits.toString()
        if (real) {
            val overflow = when (suffix) {
                'm' -> runCatching { BigDecimal(value).abs() > MAX_DECIMAL }.getOrDefault(true)
                'f' -> value.toFloatOrNull()?.isInfinite() ?: true
                else -> value.toDoubleOrNull()?.isInfinite() ?: true
            }
            if (overflow && out != null) {
                out += when (suffix) {
                    'm' -> CSharpLexerDiagnostic(CSharpErrorCode.ERR_FloatOverflow, 0, text.length, listOf("decimal"))
                    'f' -> CSharpLexerDiagnostic(CSharpErrorCode.ERR_FloatOverflow, 0, 0, listOf("float"))
                    else -> CSharpLexerDiagnostic(CSharpErrorCode.ERR_FloatOverflow, 0, 0, listOf("double"))
                }
            }
            return overflow
        }
        if (value.isEmpty()) {
            out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_InvalidNumber, 0, 0))
            return true
        }
        val radix = if (isHex) 16 else if (isBinary) 2 else 10
        val overflow = BigInteger(value, radix) > MAX_ULONG
        if (overflow) out?.add(CSharpLexerDiagnostic(CSharpErrorCode.ERR_IntOverflow, 0, 0))
        return overflow // ERR_IntOverflow
    }

    private val MAX_ULONG = BigInteger("18446744073709551615")
    private val MAX_DECIMAL = BigDecimal("79228162514264337593543950335")

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** `SyntaxFacts.IsNewLine` */
    private fun isNewLine(c: Char) = c == '\r' || c == '\n' || c == '\u0085' || c == ' ' || c == ' '
}
