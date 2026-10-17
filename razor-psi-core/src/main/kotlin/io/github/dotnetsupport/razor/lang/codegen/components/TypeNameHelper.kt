package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.codegen.CodeWriter

/**
 * `TypeNameHelper` (`TypeNameHelper.cs`, `TypeNameHelper.NameComparer.cs`; namespace `Microsoft.AspNetCore.Razor.Language` in the
 * compiler): `global::` in front of a type name unless it has one, is a tuple or a predefined C# type.
 */
object TypeNameHelper {
    private const val GLOBAL_PREFIX = "global::"

    /** `PredefinedTypeNames`, compared ordinally ([NameComparer]). */
    private val predefinedTypeNames: Set<String> = hashSetOf(
        "bool", "int", "string", "float", "double", "decimal", "byte", "short", "long", "char", "object", "dynamic", "uint", "ushort",
        "ulong", "sbyte", "nint", "nuint",
    )

    internal fun getGloballyQualifiedNameIfNeeded(typeName: String): String {
        if (typeName.isEmpty()) return typeName
        if (typeName.startsWith(GLOBAL_PREFIX)) return typeName

        // Mitigation for https://github.com/dotnet/razor-compiler/issues/332: tuples are not prefixed (no C# parser at this layer).
        if (typeName[0] == '(') return typeName

        // Fast path: a length outside that of the built-in C# types needs no further check.
        if (typeName.length < 3 || typeName.length > 7) return GLOBAL_PREFIX + typeName

        if (NameComparer.contains(predefinedTypeNames, typeName)) return typeName

        return GLOBAL_PREFIX + typeName
    }

    fun writeGloballyQualifiedName(codeWriter: CodeWriter, typeName: CharSequence) {
        writeGlobalPrefixIfNeeded(codeWriter, typeName)
        codeWriter.write(typeName)
    }

    /** Writes `global::` if the type name does not start with it and is not a predefined type. */
    internal fun writeGlobalPrefixIfNeeded(codeWriter: CodeWriter, typeName: CharSequence) {
        if (typeName.isEmpty()) return
        if (typeName.startsWith(GLOBAL_PREFIX)) return

        // Mitigation for https://github.com/dotnet/razor-compiler/issues/332: tuples are not prefixed.
        if (typeName[0] == '(') return

        // Fast path: a length outside that of the built-in C# types needs no further check.
        if (typeName.length < 3 || typeName.length > 7) {
            codeWriter.write(GLOBAL_PREFIX)
            return
        }

        if (NameComparer.contains(predefinedTypeNames, typeName)) return

        codeWriter.write(GLOBAL_PREFIX)
    }

    /**
     * `GetNonGenericTypeName(typeName, out genericTypeParameterList)`: the name before `<` and the parameter list from `<` on; the list is
     * null (the compiler's `default` memory) when the name has no `<`.
     */
    internal fun getNonGenericTypeName(typeName: String): Pair<CharSequence, CharSequence?> {
        val index = typeName.indexOf('<')
        return if (index == -1) Pair(typeName, null) else Pair(typeName.subSequence(0, index), typeName.subSequence(index, typeName.length))
    }

    /** `NameComparer`: ordinal equality of the characters, whatever the sequence type (the compiler's `ReadOnlyMemory<char>` keys). */
    private object NameComparer {
        fun contains(set: Set<String>, name: CharSequence): Boolean = set.contains(name.toString())
    }
}
