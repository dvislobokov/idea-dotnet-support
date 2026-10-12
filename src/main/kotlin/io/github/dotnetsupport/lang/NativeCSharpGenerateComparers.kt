package io.github.dotnetsupport.lang

import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * Rider's Equality comparer, Relational members and Relational comparer of Generate (0.1.81), written as Rider writes them: a nested
 * `private sealed class NameAgeEqualityComparer : IEqualityComparer<T>` with a static property `NameAgeComparer` that holds one; `CompareTo`
 * comparing the chosen members in order (`string.Compare(…, StringComparison.Ordinal)` for strings, `CompareTo` for values,
 * `Comparer<T>.Default` else), the non-generic `IComparable` and the operators `<`, `>`, `<=`, `>=` as options; the same comparison in a
 * nested `IComparer<T>`.
 */
object NativeCSharpComparers {
    /** `Name`, `Age` → `NameAge`: the prefix of the names of the comparer class and property (the type's own name when nothing is chosen). */
    private fun prefix(site: CSharpGenerateSite, members: List<CSharpDataMember>): String =
        members.joinToString("") { NativeCSharpGenerate.propertyName(it.name) }.ifEmpty { site.name }

    /** `IEqualityComparer` of `System.Collections.Generic` as the code at [site] writes it, with [argument]. */
    private fun generic(writer: CSharpCodeWriter, fullName: String, argument: String): String = writer.named(fullName).substringBefore('`').substringBefore('<') + "<$argument>"

    private fun unique(site: CSharpGenerateSite, name: String, taken: MutableSet<String>): String = CSharpVariableNames.unique(name, taken).also { taken += it }

    fun equalityComparer(site: CSharpGenerateSite, writer: CSharpCodeWriter, members: List<CSharpDataMember>): CSharpGeneratedCode {
        val self = site.selfText
        val q = if (site.nullable && !site.isStruct) "?" else ""
        val taken = NativeCSharpGenerate.memberNames(site).toMutableSet()
        val prefix = prefix(site, members)
        val className = unique(site, "${prefix}EqualityComparer", taken)
        val propertyName = unique(site, "${prefix}Comparer", taken)
        val compared = members.joinToString(" && ") { NativeCSharpGenerate.comparison(writer, it, "x.${it.name}", "y.${it.name}") }.ifEmpty { "true" }
        val checks = if (site.isStruct) "" else
            "        if (ReferenceEquals(x, y)) return true;\n        if (x is null) return false;\n        if (y is null) return false;\n        if (x.GetType() != y.GetType()) return false;\n"
        val hash = NativeCSharpGenerate.hashCode(writer, members, "obj.").lines().filter { it.isNotEmpty() }.joinToString("") { "    $it\n" }
        val comparer = generic(writer, "System.Collections.Generic.IEqualityComparer`1", self)
        val nested = "private sealed class $className : $comparer\n{\n" +
            "    public bool Equals($self$q x, $self$q y)\n    {\n$checks        return $compared;\n    }\n\n" +
            "    public int GetHashCode($self obj)\n    {\n$hash    }\n}"
        val property = "public static $comparer $propertyName { get; } = new $className();"
        return CSharpGeneratedCode(listOf(nested, property))
    }

    /** One member compared: the line that returns or keeps the result, the last one returns it. */
    private fun comparisons(writer: CSharpCodeWriter, members: List<CSharpDataMember>, left: String, right: String): String {
        if (members.isEmpty()) return "    return 0;\n"
        val taken = HashSet<String>()
        return members.mapIndexed { i, member ->
            val compare = compare(writer, member, left + member.name, right + member.name)
            if (i == members.lastIndex) "    return $compare;\n" else {
                val variable = CSharpVariableNames.unique(NativeCSharpGenerate.parameterName(member.name).removePrefix("@") + "Comparison", taken).also { taken += it }
                "    var $variable = $compare;\n    if ($variable != 0) return $variable;\n"
            }
        }.joinToString("")
    }

    /** Rider's comparison of one member: strings ordinally, values with their own `CompareTo`, the rest through `Comparer<T>.Default`. */
    private fun compare(writer: CSharpCodeWriter, member: CSharpDataMember, left: String, right: String): String {
        val type = member.type
        val full = (type as? SemanticType.Library)?.type?.fullName
        val text = member.typeText
        if (full == "System.String" || text == "string" || text == "string?") return "string.Compare($left, $right, ${writer.named("System.StringComparison")}.Ordinal)"
        val nullable = text.endsWith("?")
        val value = text in VALUE_KEYWORDS || type != null && writer.resolver.isValueType(type) ||
            type is SemanticType.Library && type.type.kind == IndexedTypeKind.ENUM || type is SemanticType.Source && type.info.kind == TypeKind.ENUM
        if (value && !nullable) return "$left.CompareTo($right)"
        return generic(writer, "System.Collections.Generic.Comparer`1", text) + ".Default.Compare($left, $right)"
    }

    private val VALUE_KEYWORDS = setOf("bool", "byte", "sbyte", "char", "short", "ushort", "int", "uint", "long", "ulong", "decimal", "float", "double", "nint", "nuint")

    fun relational(site: CSharpGenerateSite, writer: CSharpCodeWriter, members: List<CSharpDataMember>, options: Set<String>): CSharpGeneratedCode {
        val self = site.selfText
        val q = if (site.nullable && !site.isStruct) "?" else ""
        val result = ArrayList<String>()
        val checks = if (site.isStruct) "" else "    if (ReferenceEquals(this, other)) return 0;\n    if (other is null) return 1;\n"
        result += "public int CompareTo($self$q other)\n{\n$checks${comparisons(writer, members, "", "other.")}}"
        val bases = ArrayList<String>()
        bases += generic(writer, "System.IComparable`1", self)
        if (NativeCSharpGenerate.OPTION_COMPARABLE in options) {
            val argument = writer.named("System.ArgumentException")
            val same = if (site.isStruct) "" else "    if (ReferenceEquals(this, obj)) return 0;\n"
            result += "public int CompareTo(object$q obj)\n{\n    if (obj is null) return 1;\n$same" +
                "    return obj is $self other ? CompareTo(other) : throw new $argument(\$\"Object must be of type {nameof(${site.name})}\");\n}"
            bases += writer.named("System.IComparable")
        }
        if (NativeCSharpGenerate.OPTION_RELATIONAL_OPERATORS in options) {
            val operand = "$self$q"
            for (operator in listOf("<", ">", "<=", ">=")) {
                val body = if (site.isStruct) "left.CompareTo(right) $operator 0" else generic(writer, "System.Collections.Generic.Comparer`1", self) + ".Default.Compare(left, right) $operator 0"
                result += "public static bool operator $operator($operand left, $operand right)\n{\n    return $body;\n}"
            }
        }
        return CSharpGeneratedCode(result, bases)
    }

    fun relationalComparer(site: CSharpGenerateSite, writer: CSharpCodeWriter, members: List<CSharpDataMember>): CSharpGeneratedCode {
        val self = site.selfText
        val q = if (site.nullable && !site.isStruct) "?" else ""
        val taken = NativeCSharpGenerate.memberNames(site).toMutableSet()
        val prefix = prefix(site, members)
        val className = unique(site, "${prefix}RelationalComparer", taken)
        val propertyName = unique(site, "${prefix}Comparer", taken)
        val checks = if (site.isStruct) "" else "        if (ReferenceEquals(x, y)) return 0;\n        if (y is null) return 1;\n        if (x is null) return -1;\n"
        val body = comparisons(writer, members, "x.", "y.").lines().filter { it.isNotEmpty() }.joinToString("") { "    $it\n" }
        val comparer = generic(writer, "System.Collections.Generic.IComparer`1", self)
        val nested = "private sealed class $className : $comparer\n{\n    public int Compare($self$q x, $self$q y)\n    {\n$checks$body    }\n}"
        val property = "public static $comparer $propertyName { get; } = new $className();"
        return CSharpGeneratedCode(listOf(nested, property))
    }
}
