package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * [CSharpIdioms] as grey text at the caret: on the empty line inside `if (name == null)` shows `throw new
 * ArgumentNullException(nameof(name));`, in an empty constructor shows the `this.x = x` assignments, Tab accepts. The mechanism
 * is the inline completion of the platform (the one AI assistants use); here the suggestions are rules, so they are instant,
 * work offline and never invent anything. C# has fewer one-shape idioms than Go, so this is the argument guards and the
 * constructor assignments — only where the text alone leaves no doubt.
 */
class CSharpInlineIdiomsProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID get() = InlineCompletionProviderID("io.github.dotnetsupport.idioms")

    /** A new line, or a character typed at the start of one (the beginning of the suggestion: `t`, `th`, `thr`...). */
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (event !is InlineCompletionEvent.DocumentChange || !DotNetSettings.getInstance().inlineIdioms) return false
        return event.toRequest()?.file is CSharpFile
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val text = readAction {
            val options = CodeStyle.getIndentOptions(request.file)
            val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
            CSharpIdioms.suggest(request.document.immutableCharSequence, request.endOffset, unit)
        } ?: return InlineCompletionSuggestion.Empty
        return InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(text)) }
    }
}

/**
 * The lines C# leaves no choice about: the throw inside an argument guard whose subject is a parameter, and the assignments
 * that fill an empty constructor from its parameters. By the text alone — the `if` above the caret or the constructor around
 * it, and the members of the enclosing type found by [CSharpDeclarations]; without types only these one-meaning shapes are taken.
 */
object CSharpIdioms {
    /**
     * What to insert at [offset], or null. The caret has to be on a line of its own; what is typed there already must be the
     * beginning of the suggestion. [unit] is one level of indentation.
     */
    fun suggest(text: CharSequence, offset: Int, unit: String = "    "): String? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        if (text.subSequence(offset, lineEnd).isNotBlank()) return null
        val before = text.subSequence(lineStart, offset).toString()
        val typed = before.trimStart()

        val lines = linesBefore(text, lineStart)
        if (lines.isEmpty()) return null
        val structure = CSharpDeclarations.scan(text)
        val path = structure.pathTo(offset)

        val suggestion = guard(lines, path) ?: constructorAssignments(lines, path) ?: return null
        // the indent one level in: what the caret already has, or the opener plus one unit
        val indent = before.takeWhile { it == ' ' || it == '\t' }.ifEmpty { suggestion.openerIndent + unit }
        val body = suggestion.lines.joinToString("\n$indent")

        // not twice: the line below may already be the first line of the suggestion
        val below = text.subSequence(lineEnd, text.length).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (below != null && below == suggestion.lines.first().trim()) return null
        if (!body.startsWith(typed) || body == typed) return null
        return (if (before.isEmpty()) indent else "") + body.substring(typed.length)
    }

    /** A ready suggestion: its [lines] (the first is what the caret line becomes) and the indent of the block it opens. */
    private class Suggestion(val lines: List<String>, val openerIndent: String)

    // --- argument guards ---

    private val IF_LINE = Regex("""^if\s*\((.*)\)\s*\{?\s*$""")
    private val NULL_CHECK = Regex("""^([\w.]+)\s*(?:==\s*null|is\s+null)$""")
    private val EMPTY_CHECK = Regex("""^(?:string\.)?IsNullOrEmpty\s*\(\s*([\w.]+)\s*\)$""")
    private val WHITESPACE_CHECK = Regex("""^(?:string\.)?IsNullOrWhiteSpace\s*\(\s*([\w.]+)\s*\)$""")
    private val RANGE_CHECK = Regex("""^([\w.]+)\s*(?:<=?|>=?)\s*\S.*$""")
    private val DISPOSED_CHECK = Regex("""^_?[dD]isposed$""")

    /** The throw of the guard the caret stands in, or null; the `if` is the line above, or above the `{` that opens its block. */
    private fun guard(lines: List<Line>, path: List<CSharpDeclarationInfo>): Suggestion? {
        val above = lines.first()
        val (openerIndent, ifLine) = if (above.code == "{") (lines.getOrNull(1) ?: return null).let { it.indent to it.code } else above.indent to above.code
        val condition = IF_LINE.matchEntire(ifLine)?.groupValues?.get(1)?.trim() ?: return null

        val parameters = path.lastOrNull { it.parameters != null }?.let { CSharpDocComments.parameterNames(it.parameters).toSet() }.orEmpty()
        fun isParameter(name: String) = name.substringBefore('.') in parameters
        fun one(vararg text: String) = Suggestion(listOf(*text), openerIndent)

        NULL_CHECK.matchEntire(condition)?.let { if (isParameter(it.groupValues[1])) return one("throw new ArgumentNullException(nameof(${it.groupValues[1]}));") }
        EMPTY_CHECK.matchEntire(condition)?.let { if (isParameter(it.groupValues[1])) return one("throw new ArgumentException(\"Value cannot be null or empty.\", nameof(${it.groupValues[1]}));") }
        WHITESPACE_CHECK.matchEntire(condition)?.let { if (isParameter(it.groupValues[1])) return one("throw new ArgumentException(\"Value cannot be null or whitespace.\", nameof(${it.groupValues[1]}));") }
        RANGE_CHECK.matchEntire(condition)?.let { if (isParameter(it.groupValues[1])) return one("throw new ArgumentOutOfRangeException(nameof(${it.groupValues[1]}));") }
        // a disposed flag is a field, not a parameter; the type name is what ObjectDisposedException wants
        if (DISPOSED_CHECK.matches(condition)) path.lastOrNull { it.kind.isType }?.let { return one("throw new ObjectDisposedException(nameof(${it.name}));") }
        return null
    }

    // --- constructor guards and assignments ---

    /** In an empty constructor body: `ArgumentNullException.ThrowIfNull(p)` for the reference parameters, then `field = p;` for the assignable ones. */
    private fun constructorAssignments(lines: List<Line>, path: List<CSharpDeclarationInfo>): Suggestion? {
        val above = lines.first()
        // the body must be empty here: the line above is the brace or header that opens it
        if (above.code != "{" && !above.code.endsWith("{")) return null
        val constructor = path.lastOrNull()?.takeIf { it.kind == DeclarationKind.CONSTRUCTOR } ?: return null
        val type = path.lastOrNull { it.kind.isType } ?: return null
        val parameters = parseParameters(constructor.parameters)
        if (parameters.isEmpty()) return null

        val members = type.children.filter { (it.kind == DeclarationKind.FIELD || it.kind == DeclarationKind.PROPERTY) && "static" !in it.modifiers && "const" !in it.modifiers }
        // null checks first, as Rider generates them: only reference parameters can be null
        val guards = parameters.filter { isReferenceType(it.type) }.map { "ArgumentNullException.ThrowIfNull(${it.name});" }
        val assignments = parameters.mapNotNull { parameter ->
            val member = members.firstOrNull { normalize(it.name) == normalize(parameter.name) } ?: return@mapNotNull null
            if (member.name == parameter.name) "this.${member.name} = ${parameter.name};" else "${member.name} = ${parameter.name};"
        }
        val body = guards + assignments
        if (body.isEmpty()) return null
        return Suggestion(body, above.indent)
    }

    private class Parameter(val type: String, val name: String)

    /** `(ILogger logger, int retries = 0)` -> the (type, name) of each parameter; modifiers and default values dropped. */
    private fun parseParameters(parameters: String?): List<Parameter> {
        val inner = parameters?.trim()?.removePrefix("(")?.removeSuffix(")")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val result = ArrayList<Parameter>()
        var depth = 0
        val current = StringBuilder()
        for (c in "$inner,") {
            when {
                c in "<([{" -> depth++
                c in ">)]}" -> depth--
            }
            if (c == ',' && depth <= 0) {
                parameter(current.toString())?.let { result += it }
                current.clear()
            } else current.append(c)
        }
        return result
    }

    private val PARAMETER_MODIFIERS = setOf("params", "ref", "out", "in", "this", "scoped", "readonly")

    private fun parameter(part: String): Parameter? {
        val words = part.substringBefore('=').trim().split(Regex("\\s+")).filter { it.isNotBlank() && it !in PARAMETER_MODIFIERS }
        if (words.size < 2) return null
        return Parameter(words.dropLast(1).joinToString(" "), words.last())
    }

    private val VALUE_TYPES = setOf(
        "int", "long", "short", "byte", "sbyte", "uint", "ulong", "ushort", "bool", "char", "float", "double", "decimal", "nint", "nuint",
        "Int32", "Int64", "Int16", "Byte", "SByte", "UInt32", "UInt64", "UInt16", "Boolean", "Char", "Single", "Double", "Decimal", "IntPtr", "UIntPtr",
        "DateTime", "DateTimeOffset", "TimeSpan", "Guid",
    )

    /** Whether `ThrowIfNull` fits the type: arrays and most named types yes, the primitive value types, nullable values and generic parameters no. */
    private fun isReferenceType(type: String): Boolean {
        val t = type.trim()
        if (t.endsWith("]")) return true // an array is a reference
        if (t.endsWith("*")) return false // a pointer is not null-checked like this
        val base = t.removeSuffix("?").substringAfterLast('.')
        if (base in VALUE_TYPES) return false
        // a generic type parameter (T, TKey, ...) may be a value type: do not guess a null check for it
        if (base.length == 1 && base[0].isUpperCase() || Regex("^T[A-Z]").containsMatchIn(base)) return false
        return true
    }

    /** `_port`, `m_port`, `Port`, `port` all reduce to `port`, so a parameter finds its backing member whatever the convention. */
    private fun normalize(name: String): String = name.lowercase().trimStart('_').removePrefix("m_")

    // --- text ---

    private class Line(val indent: String, val code: String)

    /** The non-blank lines above [lineStart], nearest first, trailing comments cut off; a few are enough to see the `if` and its `{`. */
    private fun linesBefore(text: CharSequence, lineStart: Int): List<Line> {
        val result = ArrayList<Line>()
        var end = lineStart - 1
        while (end > 0 && result.size < MAX_LINES_BACK) {
            var start = end
            while (start > 0 && text[start - 1] != '\n') start--
            val raw = text.subSequence(start, end).toString().trimEnd('\r')
            val code = raw.trim().let { if (it.startsWith("//")) "" else it.substringBefore(" //").trim() }
            if (code.isNotEmpty()) result += Line(raw.takeWhile { it == ' ' || it == '\t' }, code)
            end = start - 1
        }
        return result
    }

    private const val MAX_LINES_BACK = 4
}
