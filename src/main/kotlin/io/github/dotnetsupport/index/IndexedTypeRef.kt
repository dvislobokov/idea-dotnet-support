package io.github.dotnetsupport.index

/**
 * A type as a signature of an indexed assembly names it: by the metadata name of its definition (`System.Collections.Generic.List`1`,
 * a nested one `Dictionary`2+Enumerator`), never resolved — which assembly has it is the business of [AssemblyIndexSet]. Written by
 * the indexer as a string of a small grammar (`TypeRefs` in indexer/Program.cs) and parsed here when it is asked for.
 *
 * [annotated]: `string?`, `T?` — the annotation of a nullable reference type, as `[Nullable]` of the assembly has it (a nullable
 * value type is `Nullable<T>`, a [Generic]).
 */
sealed class IndexedTypeRef {
    abstract val annotated: Boolean

    data class Named(val fullName: String, val isValueType: Boolean, override val annotated: Boolean = false) : IndexedTypeRef() {
        val namespace: String get() = fullName.substringBefore('+').substringBeforeLast('.', "")

        /** `Dictionary`2+Enumerator`: the name within the namespace. */
        val path: String get() = if (namespace.isEmpty()) fullName else fullName.substring(namespace.length + 1)
    }

    /** The arguments of the types around a nested type first: `Box<T>.Inner<U>` is `Box`1+Inner`1` of `[T, U]`. */
    data class Generic(val definition: Named, val arguments: List<IndexedTypeRef>, val tupleNames: List<String>? = null, override val annotated: Boolean = false) : IndexedTypeRef()

    data class TypeParameter(val index: Int, val ofMethod: Boolean, override val annotated: Boolean = false) : IndexedTypeRef()

    /** [rank] 1 is `T[]`. */
    data class ArrayOf(val element: IndexedTypeRef, val rank: Int = 1, override val annotated: Boolean = false) : IndexedTypeRef()

    data class PointerTo(val element: IndexedTypeRef) : IndexedTypeRef() {
        override val annotated: Boolean get() = false
    }

    /** `ref T` of a return; a parameter by reference says so by its flags and has the type itself. */
    data class ByRef(val element: IndexedTypeRef) : IndexedTypeRef() {
        override val annotated: Boolean get() = false
    }

    data class FunctionPointer(val returns: IndexedTypeRef, val parameters: List<IndexedTypeRef>) : IndexedTypeRef() {
        override val annotated: Boolean get() = false
    }

    /** The definition a type is an instance of: `List`1` of `List<int>`; null for type parameters, arrays and pointers. */
    val definitionName: String?
        get() = when (this) {
            is Named -> fullName
            is Generic -> definition.fullName
            else -> null
        }

    /** [typeArguments] in place of the type parameters of the type, [methodArguments] of the method; what has no argument stays. */
    fun substitute(typeArguments: List<IndexedTypeRef>, methodArguments: List<IndexedTypeRef> = emptyList()): IndexedTypeRef = when (this) {
        is Named -> this
        is Generic -> copy(arguments = arguments.map { it.substitute(typeArguments, methodArguments) })
        is TypeParameter -> (if (ofMethod) methodArguments else typeArguments).getOrNull(index)?.let { if (annotated) it.annotate() else it } ?: this
        is ArrayOf -> copy(element = element.substitute(typeArguments, methodArguments))
        is PointerTo -> copy(element = element.substitute(typeArguments, methodArguments))
        is ByRef -> copy(element = element.substitute(typeArguments, methodArguments))
        is FunctionPointer -> copy(returns.substitute(typeArguments, methodArguments), parameters.map { it.substitute(typeArguments, methodArguments) })
    }

    private fun annotate(): IndexedTypeRef = when (this) {
        is Named -> if (isValueType) this else copy(annotated = true)
        is Generic -> if (definition.isValueType) this else copy(annotated = true)
        is TypeParameter -> copy(annotated = true)
        is ArrayOf -> copy(annotated = true)
        else -> this
    }

    /**
     * As C# writes it: `int`, `List<string>`, `Dictionary<TKey, TValue>.Enumerator`, `int?`, `(string Name, int)`, `T[]`, `ref T`.
     * The names of the type parameters are given by who has them (the type, the method); [nullable] adds the `?` of an annotated
     * reference type.
     */
    fun display(typeParameters: List<String> = emptyList(), methodParameters: List<String> = emptyList(), nullable: Boolean = false): String {
        val builder = StringBuilder()
        display(builder, typeParameters, methodParameters, nullable)
        return builder.toString()
    }

    private fun display(out: StringBuilder, types: List<String>, methods: List<String>, nullable: Boolean) {
        val question = nullable && annotated
        when (this) {
            is Named -> out.append(KEYWORDS[fullName] ?: segments(path).joinToString(".") { it.first })
            is Generic -> displayGeneric(out, types, methods, nullable)
            is TypeParameter -> out.append(if (ofMethod) methods.getOrNull(index) ?: "TM$index" else types.getOrNull(index) ?: "T$index")
            is ArrayOf -> {
                element.display(out, types, methods, nullable)
                out.append('[').append(",".repeat(rank - 1)).append(']')
            }
            is PointerTo -> element.display(out, types, methods, nullable).also { out.append('*') }
            is ByRef -> out.append("ref ").also { element.display(out, types, methods, nullable) }
            is FunctionPointer -> {
                out.append("delegate*<")
                (parameters + returns).forEachIndexed { i, type -> if (i > 0) out.append(", "); type.display(out, types, methods, nullable) }
                out.append('>')
            }
        }
        if (question) out.append('?')
    }

    private fun Generic.displayGeneric(out: StringBuilder, types: List<String>, methods: List<String>, nullable: Boolean) {
        if (definition.fullName == NULLABLE && arguments.size == 1) {
            arguments[0].display(out, types, methods, nullable)
            out.append('?')
            return
        }
        val elements = tupleElements()
        if (elements != null && elements.size > 1) {
            out.append('(')
            elements.forEachIndexed { i, element ->
                if (i > 0) out.append(", ")
                element.display(out, types, methods, nullable)
                tupleNames?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { out.append(' ').append(it) }
            }
            out.append(')')
            return
        }
        // the arguments go to the segments of the path by their arity: `Box`1+Inner`1` of [T, U] is `Box<T>.Inner<U>`
        val namespaceless = definition.path
        var next = 0
        segments(namespaceless).forEachIndexed { i, (name, arity) ->
            if (i > 0) out.append('.')
            out.append(name)
            if (arity > 0) {
                out.append('<')
                for (j in 0 until arity) {
                    if (j > 0) out.append(", ")
                    arguments.getOrNull(next++)?.display(out, types, methods, nullable)
                }
                out.append('>')
            }
        }
    }

    /** The elements of a tuple, the rest of a long one (its eighth argument) flattened; null for what is not a tuple. */
    fun tupleElements(): List<IndexedTypeRef>? {
        if (this !is Generic || !definition.fullName.startsWith(TUPLE)) return null
        if (arguments.size == 8) {
            val rest = arguments[7].tupleElements() ?: return arguments
            return arguments.subList(0, 7) + rest
        }
        return arguments
    }

    /**
     * As a documentation ID writes a type in the parameters of a member: `System.Collections.Generic.IEnumerable{``0}`, `` `0 ``,
     * `System.Int32[]`, `System.Int32[0:,0:]`, `System.Int32*`. A parameter by reference adds `@` itself.
     */
    fun docId(): String = when (this) {
        is Named -> fullName.replace('+', '.')
        is Generic -> {
            val builder = StringBuilder()
            val namespace = definition.namespace
            if (namespace.isNotEmpty()) builder.append(namespace).append('.')
            var next = 0
            segments(definition.path).forEachIndexed { i, (name, arity) ->
                if (i > 0) builder.append('.')
                builder.append(name)
                if (arity > 0) builder.append((0 until arity).joinToString(",", "{", "}") { arguments.getOrNull(next++)?.docId() ?: "" })
            }
            builder.toString()
        }
        is TypeParameter -> (if (ofMethod) "``" else "`") + index
        is ArrayOf -> element.docId() + if (rank == 1) "[]" else (0 until rank).joinToString(",", "[", "]") { "0:" }
        is PointerTo -> element.docId() + "*"
        is ByRef -> element.docId() + "@"
        is FunctionPointer -> "=FUNC:" + returns.docId() + parameters.joinToString(",", "(", ")") { it.docId() }
    }

    companion object {
        internal const val NULLABLE = "System.Nullable`1"
        private const val TUPLE = "System.ValueTuple`"

        internal val KEYWORDS = mapOf(
            "System.Boolean" to "bool", "System.Byte" to "byte", "System.SByte" to "sbyte", "System.Char" to "char", "System.Int16" to "short",
            "System.UInt16" to "ushort", "System.Int32" to "int", "System.UInt32" to "uint", "System.Int64" to "long", "System.UInt64" to "ulong",
            "System.Single" to "float", "System.Double" to "double", "System.Decimal" to "decimal", "System.String" to "string", "System.Object" to "object",
            "System.Void" to "void", "System.IntPtr" to "nint", "System.UIntPtr" to "nuint",
        )

        /** `Dictionary`2+Enumerator` -> [(Dictionary, 2), (Enumerator, 0)]. */
        fun segments(path: String): List<Pair<String, Int>> = path.split('+').map { segment ->
            val mark = segment.indexOf('`')
            if (mark < 0) segment to 0 else segment.substring(0, mark) to (segment.substring(mark + 1).toIntOrNull() ?: 0)
        }

        /** The grammar is described at `TypeRefs` of indexer/Program.cs. */
        fun parse(encoded: String): IndexedTypeRef {
            val parser = Parser(encoded)
            val result = parser.ref()
            require(parser.at == encoded.length) { "Not a type reference: $encoded" }
            return result
        }
    }

    private class Parser(val text: String) {
        var at = 0

        fun ref(): IndexedTypeRef {
            var annotated = false
            if (peek() == '?') {
                annotated = true
                at++
            }
            return when (val tag = text[at++]) {
                'N', 'V' -> Named(until(';'), tag == 'V', annotated)
                'I' -> {
                    val count = number(':', '{')
                    var names: List<String>? = null
                    if (text[at - 1] == '{') {
                        names = until('}').split(',')
                        expect(':')
                    }
                    val definition = ref() as? Named ?: error("A generic instance of what is not a named type: $text")
                    Generic(definition, List(count) { ref() }, names, annotated)
                }
                '!' -> TypeParameter(number(';'), false, annotated)
                'M' -> TypeParameter(number(';'), true, annotated)
                '[' -> ArrayOf(ref(), 1, annotated)
                'A' -> {
                    val rank = number(';')
                    ArrayOf(ref(), rank, annotated)
                }
                '*' -> PointerTo(ref())
                '&' -> ByRef(ref())
                'F' -> {
                    val count = number(':')
                    val returns = ref()
                    FunctionPointer(returns, List(count) { ref() })
                }
                else -> error("Not a type reference: $text at $at")
            }
        }

        private fun peek(): Char = if (at < text.length) text[at] else error("A type reference cut short: $text")

        private fun expect(c: Char) {
            require(peek() == c) { "Expected $c at $at of $text" }
            at++
        }

        private fun until(end: Char): String {
            val stop = text.indexOf(end, at)
            require(stop >= 0) { "A type reference cut short: $text" }
            return text.substring(at, stop).also { at = stop + 1 }
        }

        /** Digits up to one of [ends], which is passed over. */
        private fun number(vararg ends: Char): Int {
            val start = at
            while (at < text.length && text[at] !in ends) at++
            require(at < text.length) { "A type reference cut short: $text" }
            return text.substring(start, at).toInt().also { at++ }
        }
    }
}
