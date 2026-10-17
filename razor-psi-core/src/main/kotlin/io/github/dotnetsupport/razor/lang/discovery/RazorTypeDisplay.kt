package io.github.dotnetsupport.razor.lang.discovery

/**
 * Roslyn's `ToDisplayString` of a type in the three formats the producers use (`WellKnownSymbolDisplayFormats`, `SymbolExtensions`):
 * - [defaultDisplay] — `CSharpErrorMessageFormat` (`ToDisplayString()`): keywords for the special types, `?` of nullable references
 *   and of `Nullable<T>` (`string?`, `bool?`, `object?[]?`);
 * - [fullName] — `FullNameTypeDisplayFormat`: no keywords, no `?` of references, `System.Boolean?` for `Nullable<bool>`;
 * - [globallyQualified] — the same with `global::` before every namespace-rooted name.
 * Nested types are joined with `.`, generic arguments spelled out, tuples in tuple syntax.
 */
object RazorTypeDisplay {
    fun defaultDisplay(type: RType): String = StringBuilder().also { append(it, type, Style.DEFAULT) }.toString()
    fun fullName(type: RType): String = StringBuilder().also { append(it, type, Style.FULL) }.toString()
    fun globallyQualified(type: RType): String = StringBuilder().also { append(it, type, Style.GLOBAL) }.toString()

    /** `ToDisplayString()` of a definition (`GetDefaultDisplayString` of a walked type): its type parameters as the arguments. */
    fun defaultDisplay(definition: RTypeDefinition): String = defaultDisplay(definition.asType())
    fun fullName(definition: RTypeDefinition): String = fullName(definition.asType())
    /** The full name of a namespace (`INamespaceSymbol.GetFullName()`): as written, "" for the global one. */
    fun namespaceFullName(definition: RTypeDefinition): String = definition.namespace

    private enum class Style { DEFAULT, FULL, GLOBAL }

    private val KEYWORDS = mapOf(
        "System_Object" to "object", "System_String" to "string", "System_Boolean" to "bool", "System_Char" to "char", "System_SByte" to "sbyte",
        "System_Byte" to "byte", "System_Int16" to "short", "System_UInt16" to "ushort", "System_Int32" to "int", "System_UInt32" to "uint",
        "System_Int64" to "long", "System_UInt64" to "ulong", "System_Decimal" to "decimal", "System_Single" to "float", "System_Double" to "double",
        "System_Void" to "void",
    )

    private fun append(out: StringBuilder, type: RType, style: Style) {
        when (type) {
            is RNamed -> appendNamed(out, type, style)
            is RTypeParameterRef -> {
                out.append(type.name)
                if (style == Style.DEFAULT && type.nullable) out.append('?')
            }
            is RArray -> {
                // int[][,] is an array of int[,]: the ranks in reading order, innermost element first
                var element: RType = type
                val ranks = ArrayList<RArray>()
                while (element is RArray) {
                    ranks += element
                    element = element.elementType
                }
                append(out, element, style)
                for (array in ranks) {
                    out.append('[').append(",".repeat(array.rank - 1)).append(']')
                    if (style == Style.DEFAULT && array.nullable) out.append('?')
                }
            }
            is RPointer -> {
                append(out, type.pointedAtType, style)
                out.append('*')
            }
            is RDynamic -> out.append("dynamic")
            is RError -> {
                if (type.namespace != null) {
                    if (style == Style.GLOBAL) out.append("global::")
                    out.append(type.namespace).append('.')
                }
                out.append(type.name)
                if (type.typeArguments.isNotEmpty()) {
                    out.append('<')
                    for ((i, argument) in type.typeArguments.withIndex()) {
                        if (i > 0) out.append(", ")
                        append(out, argument, style)
                    }
                    out.append('>')
                }
            }
        }
    }

    private fun appendNamed(out: StringBuilder, type: RNamed, style: Style) {
        val definition = type.definition
        if (definition.specialType == "System_Nullable_T" && type.typeArguments.size == 1) {
            append(out, type.typeArguments[0], style)
            out.append('?')
            return
        }
        if (type.isTuple) {
            out.append('(')
            val names = type.tupleElementNames!!
            for ((i, element) in tupleElements(type).withIndex()) {
                if (i > 0) out.append(", ")
                append(out, element, style)
                val name = names.getOrNull(i)
                if (name != null && name != "Item${i + 1}") out.append(' ').append(name)
            }
            out.append(')')
            if (style == Style.DEFAULT && type.nullable) out.append('?')
            return
        }
        // the numeric IntPtr of .NET 7+: nint / nuint in every format
        val native = when (definition.specialType) {
            "System_IntPtr" -> "nint"
            "System_UIntPtr" -> "nuint"
            else -> null
        }
        (if (style == Style.DEFAULT) KEYWORDS[definition.specialType] ?: native else native)?.let {
            out.append(it)
            if (type.nullable) out.append('?')
            return
        }
        val outer = type.containingType ?: definition.containingType?.asType()
        if (outer != null) {
            appendNamed(out, outer.withoutNullable(), style)
            out.append('.')
        } else {
            if (style == Style.GLOBAL) out.append("global::")
            if (definition.namespace.isNotEmpty()) out.append(definition.namespace).append('.')
        }
        out.append(escape(definition.name))
        if (type.typeArguments.isNotEmpty()) {
            out.append('<')
            for ((i, argument) in type.typeArguments.withIndex()) {
                if (i > 0) out.append(", ")
                append(out, argument, style)
            }
            out.append('>')
        }
        if (style == Style.DEFAULT && type.nullable) out.append('?')
    }

    /** The elements of a tuple: `ValueTuple` of up to seven, the eighth argument the rest (`TRest`). */
    private fun tupleElements(type: RNamed): List<RType> {
        val arguments = type.typeArguments
        if (arguments.size < 8) return arguments
        val rest = arguments[7] as? RNamed ?: return arguments
        return arguments.subList(0, 7) + tupleElements(RNamed(rest.definition, rest.typeArguments, rest.containingType, rest.tupleElementNames ?: emptyList()))
    }

    private fun RNamed.withoutNullable(): RNamed = if (nullable) RNamed(definition, typeArguments, containingType, tupleElementNames, false) else this

    private val CSHARP_KEYWORDS = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const", "continue", "decimal", "default", "delegate", "do",
        "double", "else", "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int",
        "interface", "internal", "is", "lock", "long", "namespace", "new", "null", "object", "operator", "out", "override", "params", "private", "protected",
        "public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
        "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void", "volatile", "while",
    )

    /** `EscapeKeywordIdentifiers` of the formats: a type named like a keyword is written `@name`. */
    private fun escape(name: String): String = if (name in CSHARP_KEYWORDS) "@$name" else name
}
