package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.SourceLocation

/**
 * `SeekableTextReader`: the source the tokenizers read character by character, with the line and character of [position] kept up to
 * date ([location]); [position] can be moved back (the parsers put tokens back). Lines are the compiler's (`SourceText.Lines`): `\r\n`,
 * `\r`, `\n`, `\u0085`, ` `, ` ` end a line.
 */
class SeekableTextReader(val text: CharSequence, val filePath: String? = null) {
    /** The start offset of every line, the first being 0. */
    val lineStarts: IntArray = computeLineStarts(text)

    val length: Int get() = text.length

    private var _position = 0
    private var line = 0

    var position: Int
        get() = _position
        set(value) {
            if (_position != value) {
                _position = value
                line = lineOf(value)
            }
        }

    val location: SourceLocation get() = SourceLocation(filePath, _position, line, _position - lineStarts[line])

    /** The character at [position], -1 at the end. */
    fun peek(): Int = if (_position < text.length) text[_position].code else -1

    fun read(): Int {
        val c = peek()
        if (c != -1) {
            _position++
            if (line + 1 < lineStarts.size && lineStarts[line + 1] <= _position) line++
        }
        return c
    }

    /** The line of [offset] (0-based). */
    fun lineOf(offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    fun locationOf(offset: Int): SourceLocation {
        val l = lineOf(offset)
        return SourceLocation(filePath, offset, l, offset - lineStarts[l])
    }

    companion object {
        fun computeLineStarts(text: CharSequence): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r') {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    starts += i + 1
                } else if (c == '\n' || c == '\u0085' || c == ' ' || c == ' ') starts += i + 1
                i++
            }
            return starts.toIntArray()
        }
    }
}

/** `SourceLocationTracker.Advance`: [location] moved past [text], lines counted as the compiler counts them (a `\r\n` is one line break). */
object SourceLocationTracker {
    fun advance(location: SourceLocation, text: String): SourceLocation {
        var absoluteIndex = location.absoluteIndex
        var lineIndex = location.lineIndex
        var characterIndex = location.characterIndex
        for (i in text.indices) {
            val c = text[i]
            val next = if (i < text.length - 1) text[i + 1] else '\u0000'
            absoluteIndex++
            if (isNewLine(c) && (c != '\r' || next != '\n')) {
                lineIndex++
                characterIndex = 0
            } else characterIndex++
        }
        return SourceLocation(location.filePath, absoluteIndex, lineIndex, characterIndex)
    }

    private fun isNewLine(c: Char) = c == '\r' || c == '\n' || c == '\u0085' || c == ' ' || c == ' '
}
