package io.github.dotnetsupport.razor

import io.github.dotnetsupport.razor.lang.codegen.RazorCSharpDocument
import io.github.dotnetsupport.razor.lang.syntax.RazorSourceDocument
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/**
 * The compiler tests' `SourceMappingsSerializer` (`.mappings.txt`): every mapping by the generated position, its original span and text
 * then its generated span and text, `|text|` each, a blank line after. The C# asserts the two texts equal ignoring case; here a mapping
 * whose texts differ goes to [onMismatch] (as razordump reports it) and the serialization goes on. Line breaks LF.
 */
object RazorMappingsSerializer {
    fun serialize(csharpDocument: RazorCSharpDocument, sourceDocument: RazorSourceDocument, onMismatch: (String) -> Unit = {}): String {
        val builder = StringBuilder()

        for (sourceMapping in csharpDocument.sourceMappingsSortedByGenerated) {
            builder.append("Source Location: ")
            val sourceCode = codeForSpan(sourceMapping.originalSpan, sourceDocument.text)
            appendMappingLocation(builder, sourceMapping.originalSpan, sourceCode)

            builder.append("Generated Location: ")
            val generatedCode = codeForSpan(sourceMapping.generatedSpan, csharpDocument.text)
            appendMappingLocation(builder, sourceMapping.generatedSpan, generatedCode)

            if (!sourceCode.equals(generatedCode, ignoreCase = true)) onMismatch("${sourceMapping.originalSpan} -> ${sourceMapping.generatedSpan}")
            builder.append('\n')
        }

        return builder.toString()
    }

    private fun appendMappingLocation(builder: StringBuilder, location: SourceSpan, content: String) {
        builder.append(location.toString()).append('\n').append('|').append(content).append("|\n")
    }

    /** `SourceText.ToString(span.AsTextSpan())`; a span past the end (a broken mapping) is cut at the end rather than thrown on. */
    private fun codeForSpan(location: SourceSpan, content: CharSequence): String {
        val start = location.absoluteIndex.coerceIn(0, content.length)
        val end = (location.absoluteIndex + location.length).coerceIn(start, content.length)
        return content.subSequence(start, end).toString()
    }
}
