package io.github.dotnetsupport.razor.lang.syntax

import java.security.MessageDigest

/**
 * `RazorSourceDocument`: the text of a Razor file with its path (`FilePath`, for `#line` and the mappings) and the relative path
 * (`RelativePath`, the identity in the project: the namespace, the metadata attributes). The lines are computed once, as
 * `SourceText.Lines`; the checksum is SHA-256 of the UTF-8 bytes, as the SDK's source generator reads files.
 */
class RazorSourceDocument(val text: CharSequence, val filePath: String?, val relativePath: String? = filePath, val hasBom: Boolean = false) {
    private val lineStarts: IntArray = computeLineStarts(text)

    val length: Int get() = text.length
    val lineCount: Int get() = lineStarts.size

    /** `GetFilePathForDisplay`: the relative path when there is one. */
    val filePathForDisplay: String? get() = relativePath ?: filePath

    /** `Text.Lines.GetLinePosition(offset)`: the 0-based line and character of [offset]; past the end — of the end. */
    fun linePosition(offset: Int): LinePosition {
        val at = offset.coerceIn(0, text.length)
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= at) low = mid else high = mid - 1
        }
        return LinePosition(low, at - lineStarts[low])
    }

    fun location(offset: Int): SourceLocation {
        val position = linePosition(offset)
        return SourceLocation(filePath, offset, position.line, position.character)
    }

    /** `GetSourceSpan` of a node at [start] of [width]: the location, the line count and the end character the compiler attaches. */
    fun span(start: Int, width: Int): SourceSpan {
        val location = location(start)
        val end = linePosition(start + width)
        return SourceSpan(location.filePath, location.absoluteIndex, location.lineIndex, location.characterIndex, width, end.line - location.lineIndex, end.character)
    }

    /**
     * The SHA-256 checksum of the UTF-8 bytes of the file, lower-case hex, as `RazorSourceChecksumAttribute` and `#pragma checksum` print it.
     * The compiler hashes the stream, so a file with a UTF-8 BOM ([hasBom]) hashes the BOM too.
     */
    val checksum: String by lazy { checksumBytes.joinToString("") { "%02x".format(it) } }

    /** `Text.GetChecksum()`: the SHA-256 bytes. */
    val checksumBytes: List<Byte> by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        if (hasBom) digest.update(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        digest.digest(text.toString().toByteArray(Charsets.UTF_8)).toList()
    }

    data class LinePosition(val line: Int, val character: Int)

    companion object {
        /** `\r\n`, `\r` and `\n` end a line, as `SourceText` counts them. */
        fun computeLineStarts(text: CharSequence): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r') {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    starts += i + 1
                } else if (c == '\n') starts += i + 1
                i++
            }
            return starts.toIntArray()
        }
    }
}
