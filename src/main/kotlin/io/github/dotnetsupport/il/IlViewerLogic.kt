package io.github.dotnetsupport.il

import com.intellij.openapi.util.TextRange
import io.github.dotnetsupport.lang.CSharpDeclarationInfo
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.DeclarationKind

/** What the IL Viewer shows. */
sealed class IlViewState {
    /** Nothing to show: not a C# file, or a file no project owns; [hint] says what to do. */
    data class Empty(val hint: String) : IlViewState()

    /** The project has no assembly yet (or MSBuild cannot tell where it is). */
    data class NotBuilt(val projectPath: String) : IlViewState()

    /** The helper has failed; the details are in the journal of the plugin. */
    data class Failed(val message: String, val projectPath: String?) : IlViewState()

    /** [stale]: the source or the project has changed after the build the IL comes from. */
    data class Shown(val request: IlRequest, val answer: IlAnswer, val projectPath: String, val stale: Boolean) : IlViewState()

    companion object {
        const val NOT_CSHARP = "Open a C# file to see the IL of the code at the caret"
        const val NO_PROJECT = "The file is not a part of a .NET project"
        const val NOT_TRUSTED = "Trust the project to see its IL: finding its assembly runs its MSBuild logic"
        const val NOTHING_HERE = "No IL at the caret: move it into a type or a member"
        const val NOT_BUILT = "Build the project to see its IL"
        const val STALE = "Source changed after the last build"
    }
}

/** The pure part of the IL Viewer: names for the helper, the order of the bodies, the stale build, lines of IL to lines of C# and back. */
object IlViewerLogic {
    /** The metadata name of the type around [offset] (`Shop.Orders+Line`, generics with their arity: ``Cache`1``) and the member, as the helper wants them. */
    fun names(text: CharSequence, offset: Int): Pair<String?, String?> {
        val path = CSharpDeclarations.scan(text).pathTo(offset)
        val types = path.filter { it.kind.isType }
        if (types.isEmpty()) return null to null
        val namespace = path.takeWhile { !it.kind.isType }.filter { it.kind == DeclarationKind.NAMESPACE }.joinToString(".") { it.name }
        val typeName = (if (namespace.isEmpty()) "" else "$namespace.") + types.joinToString("+") { it.name + arity(text, it) }
        val member = path.lastOrNull()?.takeIf { !it.kind.isType && it.kind != DeclarationKind.NAMESPACE && path.indexOf(it) > path.indexOf(types.last()) }
        return typeName to member?.let { memberName(text, it) }
    }

    /** `` `2`` for `class Map<K, V>`: the count of type parameters right after the name. */
    private fun arity(text: CharSequence, type: CSharpDeclarationInfo): String {
        var i = type.nameRange.endOffset
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '<') return ""
        var depth = 0
        var count = 1
        while (i < text.length) {
            when (text[i]) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> if (--depth == 0) return "`$count"
                ',' -> if (depth == 1) count++
                '{', ';' -> return ""
            }
            i++
        }
        return ""
    }

    private fun memberName(text: CharSequence, member: CSharpDeclarationInfo): String = when (member.kind) {
        DeclarationKind.CONSTRUCTOR -> when {
            member.name.startsWith("~") -> "Finalize"
            "static" in member.modifiers -> ".cctor"
            else -> ".ctor"
        }
        DeclarationKind.INDEXER -> "Item"
        DeclarationKind.OPERATOR -> operatorName(text, member)
        else -> member.name
    }

    /** `op_Addition` for `operator +`, as the compiler names them in metadata. */
    private fun operatorName(text: CharSequence, member: CSharpDeclarationInfo): String {
        val symbol = member.name.removePrefix("operator").trim()
        val unary = member.parameters?.removeSurrounding("(", ")")?.let { it.isNotBlank() && topLevelCommas(it) == 0 } == true
        (if (unary) UNARY_OPERATORS[symbol] else null)?.let { return it }
        BINARY_OPERATORS[symbol]?.let { return it }
        UNARY_OPERATORS[symbol]?.let { return it }
        val header = text.subSequence(member.range.startOffset, member.nameRange.startOffset)
        return if (Regex("\\bexplicit\\b").containsMatchIn(header)) "op_Explicit" else "op_Implicit"
    }

    private fun topLevelCommas(parameters: String): Int {
        var depth = 0
        return parameters.count { c ->
            when (c) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
            }
            c == ',' && depth == 0
        }
    }

    private val BINARY_OPERATORS = mapOf(
        "+" to "op_Addition", "-" to "op_Subtraction", "*" to "op_Multiply", "/" to "op_Division", "%" to "op_Modulus", "&" to "op_BitwiseAnd",
        "|" to "op_BitwiseOr", "^" to "op_ExclusiveOr", "<<" to "op_LeftShift", ">>" to "op_RightShift", ">>>" to "op_UnsignedRightShift",
        "==" to "op_Equality", "!=" to "op_Inequality", "<" to "op_LessThan", ">" to "op_GreaterThan", "<=" to "op_LessThanOrEqual", ">=" to "op_GreaterThanOrEqual",
    )
    private val UNARY_OPERATORS = mapOf(
        "+" to "op_UnaryPlus", "-" to "op_UnaryNegation", "!" to "op_LogicalNot", "~" to "op_OnesComplement", "++" to "op_Increment", "--" to "op_Decrement",
        "true" to "op_True", "false" to "op_False",
    )

    /** The body at the caret first, the others in the order of the helper. */
    fun ordered(bodies: List<IlBody>): List<IlBody> = bodies.filter { it.atCaret } + bodies.filter { !it.atCaret }

    /**
     * Which of [bodies] (already [ordered]) to show: the one the user chose before ([previous]) while it is still there and the caret has
     * not moved to another member ([sameMember]), else the first.
     */
    fun choose(bodies: List<IlBody>, previous: String?, sameMember: Boolean): Int =
        if (sameMember && previous != null) bodies.indexOfFirst { it.name == previous }.coerceAtLeast(0) else 0

    /** The build is older than the source: a file or the project saved after it, or a change of the source not saved yet. */
    fun isStale(assemblyModified: Long, sourceModified: Long, projectModified: Long, sourceUnsaved: Boolean): Boolean =
        sourceUnsaved || sourceModified > assemblyModified || projectModified > assemblyModified

    /** `METHOD` → `method`, `STATE_MACHINE` → `state machine`, for the list of bodies. */
    fun kindTitle(kind: IlBody.Kind): String = kind.name.lowercase().replace('_', ' ')

    /**
     * The lines of IL (0-based, of [IlBody.text]) compiled from source [line] (1-based), the first to scroll to first. A sequence point
     * covers its own line and the instructions after it up to the next sequence point or the end of the instructions.
     */
    fun ilLines(body: IlBody, line: Int): List<Int> {
        val lines = body.text.lines()
        return body.mapping.filter { line in it.startLine..it.endLine }.flatMap { block(lines, body.mapping, it) }.distinct().sorted()
    }

    /** The source range of IL [textLine]: of the sequence point whose instructions hold it; null outside of any. */
    fun sourceRange(body: IlBody, textLine: Int): IlLineMapping? {
        val lines = body.text.lines()
        return body.mapping.filter { it.textLine <= textLine }.maxByOrNull { it.textLine }?.takeIf { textLine in block(lines, body.mapping, it) }
    }

    /** The IL lines of [point]: from its line while the lines are instructions (`IL_xxxx:`) and no other sequence point begins. */
    private fun block(lines: List<String>, mapping: List<IlLineMapping>, point: IlLineMapping): IntRange {
        val starts = mapping.mapTo(HashSet()) { it.textLine }
        var end = point.textLine
        while (end + 1 < lines.size && end + 1 !in starts && INSTRUCTION.containsMatchIn(lines[end + 1])) end++
        return point.textLine..end
    }

    private val INSTRUCTION = Regex("^\\s*IL_[0-9a-fA-F]+:")

    /** The offsets of [point] in a source of [text]: lines and columns are 1-based, the end column is exclusive, as in a PDB. */
    fun sourceTextRange(text: CharSequence, point: IlLineMapping): TextRange? {
        val starts = lineStarts(text)
        fun offset(line: Int, column: Int): Int? {
            val start = starts.getOrNull(line - 1) ?: return null
            val lineEnd = starts.getOrNull(line)?.let { it - 1 } ?: text.length
            return (start + column - 1).coerceIn(start, lineEnd.coerceAtLeast(start))
        }
        val start = offset(point.startLine, point.startColumn) ?: return null
        val end = offset(point.endLine, point.endColumn) ?: text.length
        return if (end >= start) TextRange(start, end) else null
    }

    private fun lineStarts(text: CharSequence): List<Int> {
        val result = arrayListOf(0)
        for (i in text.indices) if (text[i] == '\n') result += i + 1
        return result
    }
}
