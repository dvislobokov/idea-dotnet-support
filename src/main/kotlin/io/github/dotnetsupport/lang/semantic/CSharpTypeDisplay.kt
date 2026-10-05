package io.github.dotnetsupport.lang.semantic

import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.index.IndexedTypeRef

/**
 * A [SemanticType] as Roslyn's `ToDisplayString` writes it (the format of the semantic oracle, `roslyndump semantics`): fully qualified
 * without `global::`, keywords for the special types, `T?` for `Nullable<T>`, tuple syntax for `ValueTuple`s, `Outer<A>.Inner` for nested
 * types. Null when a part of the type is unknown: no answer is better than a wrong one.
 */
object CSharpTypeDisplay {
    /** `System.Int32` -> `int`: the special types Roslyn writes as keywords (`nint` too, since C# 11 makes it `IntPtr`). */
    val KEYWORDS: Map<String, String> = mapOf(
        "System.Boolean" to "bool", "System.Byte" to "byte", "System.SByte" to "sbyte", "System.Char" to "char", "System.Int16" to "short",
        "System.UInt16" to "ushort", "System.Int32" to "int", "System.UInt32" to "uint", "System.Int64" to "long", "System.UInt64" to "ulong",
        "System.Single" to "float", "System.Double" to "double", "System.Decimal" to "decimal", "System.String" to "string", "System.Object" to "object",
        "System.Void" to "void", "System.IntPtr" to "nint", "System.UIntPtr" to "nuint",
    )

    fun display(type: SemanticType?): String? = when (type) {
        null -> null
        is SemanticType.Parameter -> type.name
        is SemanticType.ArrayOf -> display(type.element)?.let { element ->
            // `int[][,]`: Roslyn writes the ranks outermost first; the element of an array of arrays carries its own ranks after them
            val (core, inner) = splitRanks(element)
            core + "[" + ",".repeat((type.rank - 1).coerceAtLeast(0)) + "]" + inner
        }
        is SemanticType.Source -> {
            val arguments = type.arguments.map { display(it) ?: return null }
            val own = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">")
            val outer = type.outer
            when {
                outer != null -> display(outer)?.let { it + "." + type.info.qualifiedName.substringAfterLast('.') + own }
                // nested in a generic type whose arguments are not known: `Outer.Inner` would be wrong
                isNestedInGeneric(type) -> null
                else -> type.info.qualifiedName + own
            }
        }
        is SemanticType.Library -> library(type)
    }

    private fun isNestedInGeneric(type: SemanticType.Source): Boolean {
        var at = type.info.parts.firstOrNull()?.element()?.parent
        while (at is CSharpBaseTypeDeclaration) {
            if ((at as? CSharpTypeDeclaration)?.typeParameterList != null) return true
            at = at.parent
        }
        return false
    }

    private fun splitRanks(element: String): Pair<String, String> {
        if (!element.endsWith("]")) return element to ""
        var depth = 0
        var at = element.length
        // the trailing `[...]` groups of an array element: stop at the first character outside of brackets
        while (at > 0) {
            val c = element[at - 1]
            if (c == ']') depth++ else if (c == '[') depth-- else if (depth == 0) break
            at--
        }
        return element.substring(0, at) to element.substring(at)
    }

    private fun library(type: SemanticType.Library): String? {
        val full = type.type.fullName
        KEYWORDS[full]?.let { return it }
        if (full == "System.Nullable`1") return display(type.arguments.singleOrNull() ?: return null)?.let { "$it?" }
        if (full.startsWith("System.ValueTuple`") && type.arguments.size in 2..7 && type.type.namespace == "System") {
            val names = type.tupleNames
            return type.arguments.mapIndexed { i, argument ->
                val shown = display(argument) ?: return null
                names?.getOrNull(i)?.let { "$shown $it" } ?: shown
            }.joinToString(", ", "(", ")")
        }
        val segments = IndexedTypeRef.segments(type.type.path)
        val total = segments.sumOf { it.second }
        if (type.arguments.size != total) return if (total == 0) qualified(type.type.namespace, segments.joinToString(".") { it.first }) else null
        val result = StringBuilder()
        var next = 0
        for ((i, segment) in segments.withIndex()) {
            if (i > 0) result.append('.')
            result.append(segment.first)
            if (segment.second > 0) {
                result.append('<')
                for (k in 0 until segment.second) {
                    if (k > 0) result.append(", ")
                    result.append(display(type.arguments[next++]) ?: return null)
                }
                result.append('>')
            }
        }
        return qualified(type.type.namespace, result.toString())
    }

    private fun qualified(namespace: String, name: String): String = if (namespace.isEmpty()) name else "$namespace.$name"
}
