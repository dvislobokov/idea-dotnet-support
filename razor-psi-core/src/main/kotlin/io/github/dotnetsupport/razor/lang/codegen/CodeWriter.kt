package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.syntax.SourceLocation

/**
 * `CodeWriter` (`CodeWriter.cs`, `CodeWriter.IndentCache.cs`, `CodeWriter.WriteInterpolatedStringHandler.cs`; RAZOR_PLAN.md, R3b): the text
 * of the generated C# with its [location] — the absolute index, the line and the character the next write starts at, which the source
 * mappings and the line pragmas take. The compiler keeps pages of string chunks to avoid copies; a [StringBuilder] gives the same text.
 *
 * Indentation is lazy, as in the compiler: a write indents by [currentIndent] only when the last written character is `\n`. That is why
 * an interpolated string of the compiler is not one write: each piece of `$"…{x}…"` is written on its own and a piece that follows a
 * piece ending with `\n` is indented. [writeParts] / [writeLineParts] write the pieces the same way.
 */
class CodeWriter(options: RazorCodeGenerationOptions = RazorCodeGenerationOptions.DEFAULT) : AutoCloseable {
    private val builder = StringBuilder()

    /** `LastChar`: the last character written, null before the first write. */
    var lastChar: Char? = null
        private set

    private var newLineValue: String = setNewLineChecked(options.newLine)
    private var indentSize: Int = 0
    private var indentString: String = ""

    private var absoluteIndex = 0
    private var currentLineIndex = 0
    private var currentLineCharacterIndex = 0

    val indentWithTabs: Boolean = options.indentWithTabs
    val tabSize: Int = options.indentSize

    /** `Dispose`: the compiler returns its pages to the pool; here the text is just dropped. */
    override fun close() {
        builder.setLength(0)
    }

    private fun addTextChunk(value: CharSequence) {
        if (value.isEmpty()) return
        builder.append(value)
        lastChar = value[value.length - 1]
    }

    var currentIndent: Int
        get() = indentSize
        set(value) {
            require(value >= 0) { "currentIndent must not be negative: $value" }
            if (indentSize != value) {
                indentSize = value
                indentString = IndentCache.getIndentString(value, indentWithTabs, tabSize)
            }
        }

    /** `Length`: the absolute index is the length of what has been written. */
    val length: Int get() = absoluteIndex

    var newLine: String
        get() = newLineValue
        set(value) {
            newLineValue = setNewLineChecked(value)
        }

    private fun setNewLineChecked(value: String): String {
        require(value == "\r\n" || value == "\n") { "Invalid newline sequence '$value'. Support newline sequences are '\\r\\n' and '\\n'." }
        return value
    }

    /** `Location`: no file path, as the compiler's. */
    val location: SourceLocation get() = SourceLocation(null, absoluteIndex, currentLineIndex, currentLineCharacterIndex)

    /** The indexer, which the compiler keeps for tests only. */
    operator fun get(index: Int): Char {
        if (index < 0 || index >= builder.length) throw IndexOutOfBoundsException("index")
        return builder[index]
    }

    fun indent(size: Int): CodeWriter {
        if (size == 0 || lastChar != '\n') return this
        val indent = if (size == indentSize) indentString else IndentCache.getIndentString(size, indentWithTabs, tabSize)
        addTextChunk(indent)
        currentLineCharacterIndex += indent.length
        absoluteIndex += indent.length
        return this
    }

    fun write(value: CharSequence): CodeWriter = writeCore(value)

    fun write(value: String, startIndex: Int, count: Int): CodeWriter {
        require(startIndex >= 0) { "startIndex must not be negative" }
        require(count >= 0) { "count must not be negative" }
        require(startIndex <= value.length - count) { "startIndex + count is past the end of the value" }
        return writeCore(value.subSequence(startIndex, startIndex + count))
    }

    /** `Write<T>(T value) where T : IWriteableValue`. */
    fun write(value: IWriteableValue): CodeWriter {
        value.writeTo(this)
        return this
    }

    /**
     * `Write(ref WriteInterpolatedStringHandler)`: each part written on its own as `AppendLiteral` / `AppendFormatted` do it — null skipped,
     * a [CharSequence] written, an [IWriteableValue] writes itself, anything else by `toString()`.
     */
    fun writeParts(vararg parts: Any?): CodeWriter {
        for (part in parts) when (part) {
            null -> {}
            is CharSequence -> write(part)
            is IWriteableValue -> part.writeTo(this)
            else -> write(part.toString())
        }
        return this
    }

    private fun writeCore(value: CharSequence, allowIndent: Boolean = true): CodeWriter {
        if (value.isEmpty()) return this
        if (allowIndent) indent(indentSize)

        val lastChar = this.lastChar
        addTextChunk(value)
        absoluteIndex += value.length

        val end = value.length
        var start = 0
        // A \r\n spread across two writes is one line break: the \n of this write is not counted again.
        if (lastChar == '\r' && value[0] == '\n') start = 1

        while (true) {
            var newLineIndex = -1
            for (i in start until end) {
                val c = value[i]
                if (c == '\r' || c == '\n') {
                    newLineIndex = i
                    break
                }
            }
            if (newLineIndex < 0) break
            currentLineIndex++
            currentLineCharacterIndex = 0
            var next = newLineIndex + 1
            if (next < end && value[next - 1] == '\r' && value[next] == '\n') next++
            start = next
        }

        currentLineCharacterIndex += end - start
        return this
    }

    fun writeLine(): CodeWriter = writeCore(newLineValue, allowIndent = false)

    fun writeLine(value: CharSequence): CodeWriter = writeCore(value).writeLine()

    /** `WriteLine(ref WriteInterpolatedStringHandler)`. */
    fun writeLineParts(vararg parts: Any?): CodeWriter = writeParts(*parts).writeLine()

    /** `GetText`: the whole text (the compiler's `SourceText`). */
    fun getText(): String = builder.toString()

    /** `IndentCache`: the indent strings of spaces, or tabs and the spaces of the remainder. */
    internal object IndentCache {
        const val MAX_TAB_COUNT = 64
        const val MAX_SPACE_COUNT = 128

        private val tabs = "\t".repeat(MAX_TAB_COUNT)
        private val spaces = " ".repeat(MAX_SPACE_COUNT)

        fun getIndentString(size: Int, useTabs: Boolean, tabSize: Int): String {
            if (!useTabs) return sliceOrCreate(size, spaces)
            val tabCount = size / tabSize
            val spaceCount = size % tabSize
            if (spaceCount == 0) return sliceOrCreate(tabCount, tabs)
            return "\t".repeat(tabCount) + " ".repeat(spaceCount)
        }

        private fun sliceOrCreate(length: Int, chars: String): String =
            if (length <= chars.length) chars.substring(0, length) else chars[0].toString().repeat(length)
    }
}

/** `IWriteableValue`: a value that writes itself to a [CodeWriter] (the component writer's variable names). */
interface IWriteableValue {
    fun writeTo(writer: CodeWriter)
}
