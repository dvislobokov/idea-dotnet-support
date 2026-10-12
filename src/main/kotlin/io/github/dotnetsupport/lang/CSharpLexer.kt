package io.github.dotnetsupport.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Hand-written C# lexer. Every token is self-contained (strings, including interpolated and raw ones,
 * are single tokens), so the lexer is stateless and can be restarted from any token boundary.
 */
class CSharpLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val start = tokenStart
        val c = buffer[start]
        when {
            c.isWhitespace() -> token(TokenType.WHITE_SPACE, skipWhile(start) { it.isWhitespace() })
            c == '/' && charAt(start + 1) == '/' -> {
                val isDoc = charAt(start + 2) == '/' && charAt(start + 3) != '/'
                token(if (isDoc) CSharpTokenTypes.DOC_COMMENT else CSharpTokenTypes.LINE_COMMENT, lineEnd(start))
            }
            c == '/' && charAt(start + 1) == '*' -> {
                val close = indexOf("*/", start + 2)
                token(CSharpTokenTypes.BLOCK_COMMENT, if (close < 0) bufferEnd else close + 2)
            }
            c == '#' && isFirstOnLine(start) -> token(CSharpTokenTypes.PREPROCESSOR, lineEnd(start))
            c == '"' || ((c == '$' || c == '@') && isStringStart(start)) -> token(CSharpTokenTypes.STRING, scanString(start))
            c == '\'' -> token(CSharpTokenTypes.CHAR, scanChar(start))
            c.isDigit() || (c == '.' && charAt(start + 1).isDigit()) -> token(CSharpTokenTypes.NUMBER, scanNumber(start))
            c == '@' && isIdentifierStart(charAt(start + 1)) ->
                token(CSharpTokenTypes.IDENTIFIER, skipWhile(start + 1, ::isIdentifierPart))
            isIdentifierStart(c) -> {
                val end = skipWhile(start, ::isIdentifierPart)
                val isKeyword = buffer.subSequence(start, end).toString() in CSharpTokenTypes.KEYWORDS
                token(if (isKeyword) CSharpTokenTypes.KEYWORD else CSharpTokenTypes.IDENTIFIER, end)
            }
            else -> token(punctuation(c), start + 1)
        }
    }

    private fun token(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = end.coerceIn(tokenStart + 1, bufferEnd)
    }

    private fun punctuation(c: Char): IElementType = when (c) {
        '{' -> CSharpTokenTypes.LBRACE
        '}' -> CSharpTokenTypes.RBRACE
        '(' -> CSharpTokenTypes.LPAREN
        ')' -> CSharpTokenTypes.RPAREN
        '[' -> CSharpTokenTypes.LBRACKET
        ']' -> CSharpTokenTypes.RBRACKET
        ';' -> CSharpTokenTypes.SEMICOLON
        ',' -> CSharpTokenTypes.COMMA
        '.' -> CSharpTokenTypes.DOT
        '+', '-', '*', '/', '%', '&', '|', '^', '!', '~', '=', '<', '>', '?', ':' -> CSharpTokenTypes.OPERATOR
        else -> TokenType.BAD_CHARACTER
    }

    /** Char at [offset], or NUL when out of the lexed range. */
    private fun charAt(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private inline fun skipWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && predicate(buffer[i])) i++
        return i
    }

    private fun lineEnd(from: Int): Int = skipWhile(from) { it != '\n' && it != '\r' }

    private fun indexOf(text: String, from: Int): Int {
        var i = from
        while (i + text.length <= bufferEnd) {
            if (buffer.startsWith(text, i)) return i
            i++
        }
        return -1
    }

    private fun isFirstOnLine(offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0) {
            val c = buffer[i]
            if (c == '\n' || c == '\r') return true
            if (!c.isWhitespace()) return false
            i--
        }
        return true
    }

    /** `"`, `@"`, `$"`, `$@"`, `@$"`, `$$"""` ... */
    private fun isStringStart(offset: Int): Boolean =
        charAt(skipWhile(offset) { it == '$' || it == '@' }) == '"'

    private fun scanString(start: Int): Int {
        var i = start
        var interpolated = false
        var verbatim = false
        while (charAt(i) == '$' || charAt(i) == '@') {
            if (buffer[i] == '$') interpolated = true else verbatim = true
            i++
        }
        val quotes = skipWhile(i) { it == '"' } - i
        if (quotes >= 3 && !verbatim) {
            val close = indexOf("\"".repeat(quotes), i + quotes)
            return if (close < 0) bufferEnd else close + quotes
        }

        i++ // opening quote
        var holeDepth = 0
        while (i < bufferEnd) {
            val c = buffer[i]
            // An unterminated regular string must not swallow the rest of the file while typing.
            if (!verbatim && (c == '\n' || c == '\r')) return i
            if (holeDepth > 0) {
                when {
                    c == '{' -> holeDepth++
                    c == '}' -> holeDepth--
                    c == '"' || ((c == '$' || c == '@') && isStringStart(i)) -> {
                        i = scanString(i)
                        continue
                    }
                    c == '\'' -> {
                        i = scanChar(i)
                        continue
                    }
                }
                i++
                continue
            }
            when {
                c == '"' -> if (verbatim && charAt(i + 1) == '"') i += 2 else return i + 1
                c == '\\' && !verbatim -> i += 2
                c == '{' && interpolated -> if (charAt(i + 1) == '{') i += 2 else {
                    holeDepth++
                    i++
                }
                else -> i++
            }
        }
        return bufferEnd
    }

    private fun scanChar(start: Int): Int {
        var i = start + 1
        while (i < bufferEnd) {
            when (buffer[i]) {
                '\\' -> i += 2
                '\'' -> return i + 1
                '\n', '\r' -> return i
                else -> i++
            }
        }
        return bufferEnd
    }

    private fun scanNumber(start: Int): Int {
        val hex = buffer[start] == '0' && (charAt(start + 1) == 'x' || charAt(start + 1) == 'X')
        var i = start
        while (i < bufferEnd) {
            val c = buffer[i]
            when {
                c.isLetterOrDigit() || c == '_' -> i++
                c == '.' && charAt(i + 1).isDigit() -> i++
                (c == '+' || c == '-') && !hex && i > start && (buffer[i - 1] == 'e' || buffer[i - 1] == 'E') -> i++
                else -> break
            }
        }
        return i
    }

    private fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()
    private fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
