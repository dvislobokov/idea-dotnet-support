package io.github.dotnetsupport.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * The pieces a literal is split into by [CSharpHighlightingLexer], the lexer of the editor's colors only: [CSharpLexer], which the
 * heuristics and the PSI of the old tree read, keeps every literal one self-contained token. A literal starts with a piece of its own
 * type (`STRING` / `CHAR`: the prefix, the opening quotes and the text up to the first escape or hole), so whatever looks for the start
 * of a literal in the editor's tokens finds it as before; a literal without escapes and holes stays one token.
 */
object CSharpStringTokens {
    /** Plain text between escapes and holes. */
    @JvmField val TEXT = CSharpTokenType("STRING_TEXT")
    /** The last piece of a split string literal: text and the closing quotes. */
    @JvmField val STRING_END = CSharpTokenType("STRING_END")
    @JvmField val CHAR_END = CSharpTokenType("CHAR_END")
    /** A valid escape (`\n`, `A`, `""` of a verbatim string, `{{` of an interpolated one); the second of two adjacent ones is [ESCAPE_2], as in Rider. */
    @JvmField val ESCAPE = CSharpTokenType("STRING_ESCAPE")
    @JvmField val ESCAPE_2 = CSharpTokenType("STRING_ESCAPE_2")
    @JvmField val INVALID_ESCAPE = CSharpTokenType("STRING_INVALID_ESCAPE")
    /** `{` and `}` of a hole (`{{` / `}}` of a `$$"""` raw string). */
    @JvmField val INTERPOLATION_BRACE = CSharpTokenType("INTERPOLATION_BRACE")
    /** Alignment and format of a hole: `,5`, `:N2`, `,-10:yyyy-MM-dd`. */
    @JvmField val FORMAT = CSharpTokenType("FORMAT_SPECIFIER")

    @JvmField val PARTS = TokenSet.create(TEXT, STRING_END, CHAR_END, ESCAPE, ESCAPE_2, INVALID_ESCAPE, INTERPOLATION_BRACE, FORMAT)
    /** The tokens of the editor that are text of a literal, not code: the code of holes is not. */
    @JvmField val LITERAL_TEXT: TokenSet = TokenSet.orSet(CSharpTokenTypes.STRINGS, PARTS)
}

/** A piece of a literal over `[start, end)`. */
class CSharpLiteralPiece(val type: IElementType, val start: Int, val end: Int) {
    override fun toString(): String = "$type $start-$end"
}

/**
 * Splits a literal of [CSharpLexer] into the pieces of [CSharpStringTokens]: escapes of regular strings and chars (valid / invalid),
 * `""` of verbatim strings, `{{` / `}}` of interpolated ones; holes of interpolated strings, raw ones with `$$` too, as their braces,
 * the code inside lexed by [CSharpLexer] (nested literals split the same way) and the alignment and format after it.
 */
object CSharpLiteralSplitter {
    /** The pieces of the literal of [type] (`STRING` or `CHAR`) over `[start, end)` of [text]: in order, without gaps, the first of [type]. */
    fun split(text: CharSequence, type: IElementType, start: Int, end: Int): List<CSharpLiteralPiece> {
        val out = ArrayList<CSharpLiteralPiece>(4)
        val splitter = Splitter(text, start, end, type, out)
        if (type == CSharpTokenTypes.CHAR) splitter.char() else splitter.string()
        return out
    }

    private class Splitter(
        private val text: CharSequence, private val start: Int, private val end: Int, private val first: IElementType,
        private val out: MutableList<CSharpLiteralPiece>,
    ) {
        private var run = start           // start of the pending plain text
        private var closed = false        // the closing quote was seen
        private var lastEscapeEnd = -1    // end of the last valid escape: the next one right there takes the second color
        private var lastWasSecond = false

        fun char() {
            var i = start + 1
            while (i < end) {
                val c = text[i]
                if (c == '\'' && i == end - 1) { closed = true; break }
                i = if (c == '\\') escape(i) else i + 1
            }
            finish(CSharpStringTokens.CHAR_END)
        }

        fun string() {
            var i = start
            var dollars = 0
            var verbatim = false
            while (i < end && (text[i] == '$' || text[i] == '@')) {
                if (text[i] == '$') dollars++ else verbatim = true
                i++
            }
            var quotes = 0
            while (i + quotes < end && text[i + quotes] == '"') quotes++
            if (quotes >= 3 && !verbatim) return raw(i, quotes, dollars)
            i++ // the opening quote
            while (i < end) {
                val c = text[i]
                val next = if (i + 1 < end) text[i + 1] else '\u0000'
                i = when {
                    c == '"' -> if (verbatim && next == '"') piece(i, i + 2, true) else { closed = true; break }
                    c == '\\' && !verbatim -> escape(i)
                    dollars > 0 && c == '{' -> if (next == '{') piece(i, i + 2, true) else hole(i, 1, end)
                    dollars > 0 && c == '}' && next == '}' -> piece(i, i + 2, true)
                    else -> i + 1
                }
            }
            finish(CSharpStringTokens.STRING_END)
        }

        /** `"""…"""`, `$"""…{x}…"""`, `$$"""…{{x}}…"""`: no escapes; a run of braces shorter than the `$`s is text, of more, the last ones open a hole. */
        private fun raw(afterPrefix: Int, quotes: Int, dollars: Int) {
            val contentStart = afterPrefix + quotes
            closed = end - contentStart >= quotes && (end - quotes until end).all { text[it] == '"' }
            val contentEnd = if (closed) end - quotes else end
            var i = contentStart
            while (dollars > 0 && i < contentEnd) {
                if (text[i] != '{') { i++; continue }
                var k = 0
                while (i + k < contentEnd && text[i + k] == '{') k++
                i = if (k >= dollars) hole(i + k - dollars, dollars, contentEnd) else i + k
            }
            finish(CSharpStringTokens.STRING_END)
        }

        /** A hole whose [n] opening braces stand at [at]; its code ends before [limit]. Returns where the text after the hole starts. */
        private fun hole(at: Int, n: Int, limit: Int): Int {
            flush(at)
            add(CSharpStringTokens.INTERPOLATION_BRACE, at, at + n)
            val code = ArrayList<CSharpLiteralPiece>()
            val lexer = CSharpLexer()
            lexer.start(text, at + n, limit, 0)
            var depth = 0
            var alignment = -1
            var format = -1
            var close = -1
            while (true) {
                val type = lexer.tokenType ?: break
                val tokenStart = lexer.tokenStart
                if (depth == 0) {
                    if (type == CSharpTokenTypes.RBRACE) { close = tokenStart; break }
                    if (type == CSharpTokenTypes.COMMA && alignment < 0) alignment = tokenStart
                    if (type == CSharpTokenTypes.OPERATOR && text[tokenStart] == ':') {
                        // `global::` is no format
                        if (tokenStart + 1 < limit && text[tokenStart + 1] == ':') {
                            if (alignment < 0) code += CSharpLiteralPiece(type, tokenStart, lexer.tokenEnd)
                            lexer.advance()
                            if (alignment < 0 && lexer.tokenType != null) code += CSharpLiteralPiece(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd)
                            lexer.advance()
                            continue
                        }
                        format = tokenStart
                        break
                    }
                }
                when (type) {
                    CSharpTokenTypes.LBRACE, CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET -> depth++
                    CSharpTokenTypes.RBRACE, CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET -> if (depth > 0) depth--
                }
                if (alignment < 0) code += CSharpLiteralPiece(type, tokenStart, lexer.tokenEnd)
                lexer.advance()
            }
            for (token in code) {
                if (token.type == CSharpTokenTypes.STRING || token.type == CSharpTokenTypes.CHAR) out += split(text, token.type, token.start, token.end)
                else out += token
            }
            if (format >= 0) {
                close = format + 1
                while (close < limit && text[close] != '}') close++
                if (close == limit) close = -1
            }
            val spec = if (alignment >= 0) alignment else format
            if (spec >= 0) add(CSharpStringTokens.FORMAT, spec, if (close >= 0) close else limit)
            if (close < 0) {
                run = limit
                return limit
            }
            var closeEnd = close + 1
            while (closeEnd < close + n && closeEnd < limit && text[closeEnd] == '}') closeEnd++
            add(CSharpStringTokens.INTERPOLATION_BRACE, close, closeEnd)
            run = closeEnd
            return closeEnd
        }

        /** `\n`, `\x41`, `A`, `\U0001F600`; anything else after `\` is an invalid escape of two characters. */
        private fun escape(at: Int): Int {
            if (at + 1 >= end) return piece(at, end, false)
            return when (text[at + 1]) {
                '\'', '"', '\\', '0', 'a', 'b', 'e', 'f', 'n', 'r', 't', 'v' -> piece(at, at + 2, true)
                'x' -> hex(at + 2, 4).let { piece(at, at + 2 + it, it > 0) }
                'u' -> hex(at + 2, 4).let { piece(at, at + 2 + it, it == 4) }
                'U' -> hex(at + 2, 8).let { piece(at, at + 2 + it, it == 8) }
                else -> piece(at, at + 2, false)
            }
        }

        private fun hex(from: Int, max: Int): Int {
            var k = 0
            while (k < max && from + k < end && text[from + k].let { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) k++
            return k
        }

        /** An escape over `[from, to)`: valid ones alternate the two colors while they stand side by side. */
        private fun piece(from: Int, to: Int, valid: Boolean): Int {
            flush(from)
            val type = when {
                !valid -> CSharpStringTokens.INVALID_ESCAPE
                lastEscapeEnd == from && !lastWasSecond -> CSharpStringTokens.ESCAPE_2
                else -> CSharpStringTokens.ESCAPE
            }
            add(type, from, to)
            lastWasSecond = type == CSharpStringTokens.ESCAPE_2
            lastEscapeEnd = if (valid) to else -1
            run = to
            return to
        }

        private fun flush(upTo: Int) {
            if (run < upTo) add(if (out.isEmpty()) first else CSharpStringTokens.TEXT, run, upTo)
            run = upTo
        }

        private fun finish(endType: IElementType) {
            if (run < end) add(if (out.isEmpty()) first else if (closed) endType else CSharpStringTokens.TEXT, run, end)
            run = end
        }

        private fun add(type: IElementType, from: Int, to: Int) {
            if (to > from) out += CSharpLiteralPiece(type, from, to)
        }
    }
}

/**
 * The lexer of the editor's colors (`CSharpSyntaxHighlighter`): the tokens of [CSharpLexer], every literal split by [CSharpLiteralSplitter].
 * The pieces after the first one of a literal have the state 1: the platform restarts lexing only at a token of state 0, where
 * [CSharpLexer] can restart too.
 */
class CSharpHighlightingLexer : LexerBase() {
    private val base = CSharpLexer()
    private var pieces: List<CSharpLiteralPiece> = emptyList()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        base.start(buffer, startOffset, endOffset, 0)
        load()
    }

    private fun load() {
        val type = base.tokenType
        pieces = if (type == CSharpTokenTypes.STRING || type == CSharpTokenTypes.CHAR) {
            CSharpLiteralSplitter.split(base.bufferSequence, type, base.tokenStart, base.tokenEnd).takeIf { it.size > 1 } ?: emptyList()
        } else emptyList()
        index = 0
    }

    override fun getState(): Int = if (index == 0) 0 else 1
    override fun getTokenType(): IElementType? = if (pieces.isEmpty()) base.tokenType else pieces[index].type
    override fun getTokenStart(): Int = if (pieces.isEmpty()) base.tokenStart else pieces[index].start
    override fun getTokenEnd(): Int = if (pieces.isEmpty()) base.tokenEnd else pieces[index].end
    override fun getBufferSequence(): CharSequence = base.bufferSequence
    override fun getBufferEnd(): Int = base.bufferEnd

    override fun advance() {
        if (index < pieces.size - 1) {
            index++
            return
        }
        base.advance()
        load()
    }
}
