package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * `SourceMapping` (`SourceMapping.cs`): a span of the Razor file and the span of the generated C# it became. Equality is of both spans
 * (by `SourceSpan` equality, which leaves out the line count and the end character, as the compiler's).
 */
class SourceMapping(val originalSpan: SourceSpan, val generatedSpan: SourceSpan) {
    override fun equals(other: Any?): Boolean = other is SourceMapping && originalSpan == other.originalSpan && generatedSpan == other.generatedSpan

    override fun hashCode(): Int = originalSpan.hashCode() * 31 + generatedSpan.hashCode()

    override fun toString(): String = "$originalSpan -> $generatedSpan"
}

/**
 * `LinePragma` (`CodeGeneration/LinePragma.cs`): a `#line` region of the generated code — the lines of the Razor file it maps from
 * [startLineIndex] and the line of the generated code it ended on ([endLineIndex]); [isEnhanced] for `#line (l,c)-(l,c)`.
 */
data class LinePragma(val isEnhanced: Boolean, val filePath: String, val startLineIndex: Int, val endLineIndex: Int) {
    init {
        require(filePath.isNotBlank()) { "filePath must not be null or white space" }
        require(startLineIndex >= 0) { "startLineIndex must not be negative" }
        require(endLineIndex >= 0) { "endLineIndex must not be negative" }
    }

    val lineCount: Int get() = endLineIndex - startLineIndex

    override fun toString(): String = "Line index $startLineIndex, Count $lineCount - $filePath"
}

/** `SourceSpan.CompareByStartThenLength`. */
fun SourceSpan.compareByStartThenLength(other: SourceSpan): Int {
    val cmpByStart = absoluteIndex.compareTo(other.absoluteIndex)
    return if (cmpByStart != 0) cmpByStart else length.compareTo(other.length)
}
