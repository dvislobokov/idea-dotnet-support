package io.github.dotnetsupport.index

import io.github.dotnetsupport.lang.NativeCSharpCompletionPlace

/**
 * The metadata view of a type of an assembly, as Rider shows it without a decompiler: C# made of what the index of the assembly keeps —
 * the type with its generic parameters, constraints, bases and attributes (no arguments: the index has their names only), every member
 * other assemblies see as a signature without a body (`;`, a property `{ get; set; }`), the XML documentation as `///`, the nested
 * types. One text per outermost type, [Rendered.typeOffsets] / [Rendered.memberOffsets] say where the name of each declaration is, to
 * navigate to it. Pure: tests compare the text with a golden file.
 */
object AssemblyMetadataText {
    class Rendered(val text: String, val typeOffsets: Map<Int, Int>, val memberOffsets: Map<Int, Int>)

    /** [type] or the type it is nested in, up to the outermost one: what a file of the metadata view shows. */
    fun outermost(type: IndexedType): IndexedType {
        var current = type
        while (true) current = current.declaringType ?: return current
    }

    /** The text of the outermost type of [type]; [assemblyFile] is the dll the index was made of, when it is known. */
    fun render(type: IndexedType, assemblyFile: String?): Rendered = Writer(outermost(type), assemblyFile).write()

    /** `10.0.0.0` -> `10.0`, `4.2.1.0` -> `4.2.1`: the version as Rider shows it next to an assembly. */
    fun shortVersion(version: String): String {
        val parts = version.split('.').toMutableList()
        while (parts.size > 2 && parts.last() == "0") parts.removeAt(parts.lastIndex)
        return parts.joinToString(".")
    }

    /** `System.ObsoleteAttribute` -> `Obsolete`. */
    fun attributeName(fullName: String): String = fullName.substringAfterLast('.').substringAfterLast('+').removeSuffix("Attribute").ifEmpty { fullName }

    /** The attributes that say what the C# already says (`static class` of extension methods, `this[]`, `ref struct`…), or that the compiler writes. */
    private val IMPLIED_ATTRIBUTES = setOf(
        "System.Runtime.CompilerServices.ExtensionAttribute", "System.Reflection.DefaultMemberAttribute", "System.Runtime.CompilerServices.IsByRefLikeAttribute",
        "System.Runtime.CompilerServices.IsReadOnlyAttribute", "System.Runtime.CompilerServices.RequiredMemberAttribute", "System.Runtime.CompilerServices.CompilerGeneratedAttribute",
        "System.Runtime.CompilerServices.CompilerFeatureRequiredAttribute", "System.ParamArrayAttribute", "System.Runtime.CompilerServices.IsUnmanagedAttribute",
        "System.Runtime.CompilerServices.NullableAttribute", "System.Runtime.CompilerServices.NullableContextAttribute", "System.Runtime.CompilerServices.TupleElementNamesAttribute",
    )

    private val OPERATORS = mapOf(
        "op_Addition" to "+", "op_Subtraction" to "-", "op_Multiply" to "*", "op_Division" to "/", "op_Modulus" to "%", "op_BitwiseAnd" to "&",
        "op_BitwiseOr" to "|", "op_ExclusiveOr" to "^", "op_LeftShift" to "<<", "op_RightShift" to ">>", "op_UnsignedRightShift" to ">>>",
        "op_Equality" to "==", "op_Inequality" to "!=", "op_LessThan" to "<", "op_GreaterThan" to ">", "op_LessThanOrEqual" to "<=",
        "op_GreaterThanOrEqual" to ">=", "op_UnaryNegation" to "-", "op_UnaryPlus" to "+", "op_LogicalNot" to "!", "op_OnesComplement" to "~",
        "op_Increment" to "++", "op_Decrement" to "--", "op_True" to "true", "op_False" to "false",
        "op_CheckedAddition" to "checked +", "op_CheckedSubtraction" to "checked -", "op_CheckedMultiply" to "checked *", "op_CheckedDivision" to "checked /",
        "op_CheckedUnaryNegation" to "checked -", "op_CheckedIncrement" to "checked ++", "op_CheckedDecrement" to "checked --",
    )

    /** Rider's order of the members of a type: constants and fields, constructors, properties, events, methods, operators. */
    private fun order(kind: IndexedMemberKind): Int = when (kind) {
        IndexedMemberKind.ENUM_MEMBER, IndexedMemberKind.CONSTANT -> 0
        IndexedMemberKind.FIELD -> 1
        IndexedMemberKind.CONSTRUCTOR -> 2
        IndexedMemberKind.PROPERTY, IndexedMemberKind.INDEXER -> 3
        IndexedMemberKind.EVENT -> 4
        IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD -> 5
        IndexedMemberKind.OPERATOR -> 6
    }

    private val DOC_PART = Regex("""(?<=>)\s*(?=<(summary|remarks|param|typeparam|returns|value|exception|example|seealso|inheritdoc|permission)\b)""")

    private fun unsafe(reference: IndexedTypeRef): Boolean = when (reference) {
        is IndexedTypeRef.PointerTo, is IndexedTypeRef.FunctionPointer -> true
        is IndexedTypeRef.ArrayOf -> unsafe(reference.element)
        is IndexedTypeRef.ByRef -> unsafe(reference.element)
        is IndexedTypeRef.Generic -> reference.arguments.any(::unsafe)
        else -> false
    }

    private fun identifier(name: String): String = if (name in NativeCSharpCompletionPlace.RESERVED) "@$name" else name

    private class Writer(val root: IndexedType, val assemblyFile: String?) {
        private val index = root.index
        private val out = StringBuilder()
        private val typeOffsets = HashMap<Int, Int>()
        private val memberOffsets = HashMap<Int, Int>()

        fun write(): Rendered {
            out.append("// Metadata of ").append(root.fullName).append(": signatures from the index of the assembly, no bodies (no decompiler)\n")
            out.append("// Assembly: ").append(index.assemblyName).append(", Version=").append(index.assemblyVersion).append('\n')
            out.append("// MVID: ").append(index.mvid).append('\n')
            if (assemblyFile != null) out.append("// Assembly location: ").append(assemblyFile).append('\n')
            out.append('\n')
            val namespaces = sortedSetOf<String>(compareBy<String> { if (it == "System" || it.startsWith("System.")) 0 else 1 }.thenBy { it })
            collectNamespaces(root, namespaces)
            val own = root.namespace
            val imported = namespaces.filter { it.isNotEmpty() && it != own && !own.startsWith("$it.") }
            imported.forEach { out.append("using ").append(it).append(";\n") }
            if (imported.isNotEmpty()) out.append('\n')
            if (own.isEmpty()) type(root, "") else {
                out.append("namespace ").append(own).append("\n{\n")
                type(root, INDENT)
                out.append("}\n")
            }
            return Rendered(out.toString(), typeOffsets, memberOffsets)
        }

        private fun collectNamespaces(type: IndexedType, into: MutableSet<String>) {
            listOfNotNull(type.baseType).plus(type.interfaces).forEach { collect(it, into) }
            type.typeParameters.forEach { parameter -> parameter.constraints.forEach { collect(it, into) } }
            type.enumUnderlyingType?.let { collect(it, into) }
            type.attributes.filter { it !in IMPLIED_ATTRIBUTES }.forEach { into += IndexedTypeRef.Named(it, false).namespace }
            for (member in type.members) {
                collect(member.typeRef, into)
                member.parameters.forEach { collect(it.typeRef, into) }
                member.typeParameters.forEach { parameter -> parameter.constraints.forEach { collect(it, into) } }
                member.attributes.filter { it !in IMPLIED_ATTRIBUTES }.forEach { into += IndexedTypeRef.Named(it, false).namespace }
            }
            type.nestedTypes.forEach { collectNamespaces(it, into) }
        }

        private fun collect(reference: IndexedTypeRef, into: MutableSet<String>) {
            when (reference) {
                is IndexedTypeRef.Named -> if (reference.fullName !in IndexedTypeRef.KEYWORDS) into += reference.namespace
                is IndexedTypeRef.Generic -> {
                    val tuple = (reference.tupleElements()?.size ?: 0) > 1
                    if (reference.definition.fullName != IndexedTypeRef.NULLABLE && !tuple) into += reference.definition.namespace
                    (reference.tupleElements()?.takeIf { tuple } ?: reference.arguments).forEach { collect(it, into) }
                }
                is IndexedTypeRef.ArrayOf -> collect(reference.element, into)
                is IndexedTypeRef.PointerTo -> collect(reference.element, into)
                is IndexedTypeRef.ByRef -> collect(reference.element, into)
                is IndexedTypeRef.FunctionPointer -> (reference.parameters + reference.returns).forEach { collect(it, into) }
                is IndexedTypeRef.TypeParameter -> Unit
            }
        }

        private fun docs(docId: String, indent: String) {
            // the index keeps the XML on one line: a line per part (summary, param…) as it was written
            val xml = index.doc(docId)?.xml?.replace(DOC_PART, "\n") ?: return
            val lines = xml.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            val margin = lines.drop(1).filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
            lines.forEachIndexed { i, line ->
                val text = if (i == 0) line.trim() else line.drop(margin).trimEnd()
                out.append(indent).append("///").append(if (text.isEmpty()) "" else " $text").append('\n')
            }
        }

        private fun attributes(names: List<String>, indent: String, compilerObsolete: Boolean = false) {
            val shown = names.filter { it !in IMPLIED_ATTRIBUTES && !(compilerObsolete && it == OBSOLETE) }
            if (shown.isNotEmpty()) out.append(indent).append(shown.joinToString(", ", "[", "]") { attributeName(it) }).append('\n')
        }

        private fun typeNames(type: IndexedType): List<String> = type.typeParameters.map { it.name }

        private fun display(type: IndexedType, reference: IndexedTypeRef): String = reference.display(typeNames(type), emptyList(), nullable = true)

        private fun type(type: IndexedType, indent: String) {
            docs(type.docId, indent)
            // the compiler marks a ref struct `[Obsolete]` for the compilers that do not know them: left out, as in Rider's view
            attributes(type.attributes, indent, compilerObsolete = type.isRefLike)
            out.append(indent).append(if (type.isProtected) "protected " else "public ")
            val own = type.typeParameters.takeLast(type.ownArity)
            if (type.kind == IndexedTypeKind.DELEGATE) return delegate(type, own, indent)
            when (type.kind) {
                IndexedTypeKind.STATIC_CLASS -> out.append("static class ")
                IndexedTypeKind.CLASS -> {
                    when {
                        type.isStatic -> out.append("static ")
                        type.isAbstract -> out.append("abstract ")
                        type.isSealed -> out.append("sealed ")
                    }
                    out.append(if (type.isRecord) "record " else "class ")
                }
                IndexedTypeKind.STRUCT -> {
                    if (type.isReadOnly) out.append("readonly ")
                    if (type.isRefLike) out.append("ref ")
                    out.append("struct ")
                }
                IndexedTypeKind.INTERFACE -> out.append("interface ")
                IndexedTypeKind.ENUM -> out.append("enum ")
                IndexedTypeKind.DELEGATE -> Unit
            }
            typeOffsets[type.row] = out.length
            out.append(identifier(type.simpleName))
            if (own.isNotEmpty()) out.append(own.joinToString(", ", "<", ">"))
            val bases = when (type.kind) {
                IndexedTypeKind.ENUM -> listOfNotNull(type.enumUnderlyingType?.takeUnless { it is IndexedTypeRef.Named && it.fullName == "System.Int32" })
                else -> listOfNotNull(type.baseType?.takeUnless { it is IndexedTypeRef.Named && it.fullName == "System.Object" }) + type.interfaces
            }
            if (bases.isNotEmpty()) out.append(" : ").append(bases.joinToString(", ") { display(type, it) })
            constraints(own, typeNames(type), emptyList())
            out.append('\n').append(indent).append("{\n")
            val inner = indent + INDENT
            // an enum has nothing but its members in C# (`value__` is the metadata's)
            val members = type.members.filter { it.name.firstOrNull()?.let { c -> c.isLetter() || c == '_' || c == '.' } == true }
                .filter { type.kind != IndexedTypeKind.ENUM || it.kind == IndexedMemberKind.ENUM_MEMBER }
                .sortedBy { order(it.kind) }
            var previous: IndexedMemberKind? = null
            for (member in members) {
                if (previous != null && (order(previous) != order(member.kind) || index.doc(member.docId) != null)) out.append('\n')
                previous = member.kind
                member(type, member, inner)
            }
            for (nested in type.nestedTypes) {
                if (previous != null || nested != type.nestedTypes.first()) out.append('\n')
                previous = null
                type(nested, inner)
            }
            out.append(indent).append("}\n")
        }

        private fun delegate(type: IndexedType, own: List<IndexedTypeParameter>, indent: String) {
            val invoke = type.members.firstOrNull { it.name == "Invoke" && it.kind == IndexedMemberKind.METHOD }
            out.append("delegate ").append(invoke?.let { it.display(it.typeRef, nullable = true) } ?: "void").append(' ')
            typeOffsets[type.row] = out.length
            out.append(identifier(type.simpleName))
            if (own.isNotEmpty()) out.append(own.joinToString(", ", "<", ">"))
            out.append(invoke?.let(::parameters) ?: "()")
            constraints(own, typeNames(type), emptyList())
            out.append(";\n")
        }

        /** `where T : class, IComparable<T>, new()`, each on the same line, in the order C# wants them. */
        private fun constraints(parameters: List<IndexedTypeParameter>, types: List<String>, methods: List<String>) {
            for (parameter in parameters) {
                val parts = ArrayList<String>()
                when {
                    parameter.isUnmanaged -> parts += "unmanaged"
                    parameter.isStruct -> parts += "struct"
                    parameter.isClass -> parts += "class"
                }
                parameter.constraints.filterNot { parameter.isStruct && it is IndexedTypeRef.Named && it.fullName == "System.ValueType" }
                    .forEach { parts += it.display(types, methods, nullable = true) }
                if (parameter.hasNew) parts += "new()"
                if (parameter.allowsRefStruct) parts += "allows ref struct"
                if (parts.isNotEmpty()) out.append(" where ").append(parameter.name).append(" : ").append(parts.joinToString(", "))
            }
        }

        private fun parameters(member: IndexedMember, open: String = "(", close: String = ")"): String = member.parameters.joinToString(", ", open, close) { parameter ->
            buildString {
                if (parameter.isThis) append("this ")
                if (parameter.isParams) append("params ")
                when {
                    parameter.isOut -> append("out ")
                    parameter.isRef -> append("ref ")
                    parameter.isIn -> append("in ")
                }
                append(member.display(parameter.typeRef, nullable = true)).append(' ').append(identifier(parameter.name))
                if (parameter.hasDefault) parameter.defaultValue?.let { append(" = ").append(defaultValue(member, parameter.typeRef, it)) }
            }
        }

        /** `Color.Green` for the `2` of an enum of this assembly, `(Color)2` for one of another: the metadata keeps the number. */
        private fun defaultValue(member: IndexedMember, reference: IndexedTypeRef, value: String): String {
            val named = reference as? IndexedTypeRef.Named ?: return value
            if (!named.isValueType || named.fullName in IndexedTypeRef.KEYWORDS || !NUMBER.matches(value)) return value
            val name = member.display(reference)
            val enum = index.findType(named.fullName)?.takeIf { it.kind == IndexedTypeKind.ENUM } ?: return "($name)$value"
            return enum.members.firstOrNull { it.kind == IndexedMemberKind.ENUM_MEMBER && it.constantValue == value }?.let { "$name.${identifier(it.name)}" } ?: "($name)$value"
        }

        private fun modifiers(type: IndexedType, member: IndexedMember): String {
            val inInterface = type.kind == IndexedTypeKind.INTERFACE
            val parts = ArrayList<String>()
            if (member.isProtected) parts += "protected" else if (!inInterface) parts += "public"
            if (member.kind == IndexedMemberKind.CONSTANT) return (parts + "const").joinToString(" ")
            if (member.isStatic) parts += "static"
            if (member.kind == IndexedMemberKind.FIELD && member.isReadOnly) parts += "readonly"
            when {
                member.isOverride -> parts += if (member.isSealed) "sealed override" else "override"
                member.isAbstract -> if (!inInterface || member.isStatic) parts += "abstract"
                member.isVirtual -> if (!inInterface || member.isStatic) parts += "virtual"
            }
            if (member.kind != IndexedMemberKind.FIELD && member.isReadOnly && type.kind == IndexedTypeKind.STRUCT && !type.isReadOnly) parts += "readonly"
            if (member.isRequired) parts += "required"
            if (unsafe(member.typeRef) || member.parameters.any { unsafe(it.typeRef) }) parts += "unsafe"
            return parts.joinToString(" ")
        }

        private fun member(type: IndexedType, member: IndexedMember, indent: String) {
            docs(member.docId, indent)
            // and a constructor of a type with required members, for the same reason
            attributes(member.attributes, indent, compilerObsolete = member.kind == IndexedMemberKind.CONSTRUCTOR && type.members.any { it.isRequired })
            out.append(indent)
            if (member.kind == IndexedMemberKind.ENUM_MEMBER) {
                memberOffsets[member.row] = out.length
                out.append(identifier(member.name))
                member.constantValue?.let { out.append(" = ").append(it) }
                out.append(",\n")
                return
            }
            val modifiers = modifiers(type, member)
            if (modifiers.isNotEmpty()) out.append(modifiers).append(' ')
            val returns = member.display(member.typeRef, nullable = true)
            val methodNames = member.typeParameters.map { it.name }
            when (member.kind) {
                IndexedMemberKind.CONSTANT -> {
                    out.append(returns).append(' ')
                    memberOffsets[member.row] = out.length
                    out.append(identifier(member.name)).append(" = ").append(member.constantValue ?: "default").append(";\n")
                }
                IndexedMemberKind.FIELD -> {
                    out.append(returns).append(' ')
                    memberOffsets[member.row] = out.length
                    out.append(identifier(member.name)).append(";\n")
                }
                IndexedMemberKind.EVENT -> {
                    out.append("event ").append(returns).append(' ')
                    memberOffsets[member.row] = out.length
                    out.append(identifier(member.name)).append(";\n")
                }
                IndexedMemberKind.CONSTRUCTOR -> {
                    memberOffsets[member.row] = out.length
                    out.append(identifier(type.simpleName)).append(parameters(member)).append(";\n")
                }
                IndexedMemberKind.PROPERTY, IndexedMemberKind.INDEXER -> {
                    out.append(returns).append(' ')
                    memberOffsets[member.row] = out.length
                    if (member.kind == IndexedMemberKind.INDEXER) out.append("this").append(parameters(member, "[", "]")) else out.append(identifier(member.name))
                    out.append(" {")
                    if (member.hasGetter) out.append(" get;")
                    if (member.hasSetter) out.append(if (member.isInitOnly) " init;" else " set;")
                    out.append(" }\n")
                }
                IndexedMemberKind.OPERATOR -> {
                    val symbol = OPERATORS[member.name]
                    when {
                        member.name == "op_Implicit" || member.name == "op_Explicit" || member.name == "op_CheckedExplicit" -> {
                            out.append(if (member.name == "op_Implicit") "implicit " else "explicit ")
                            memberOffsets[member.row] = out.length
                            out.append("operator ").append(if (member.name == "op_CheckedExplicit") "checked " else "").append(returns)
                        }
                        symbol != null -> {
                            out.append(returns).append(' ')
                            memberOffsets[member.row] = out.length
                            out.append("operator ").append(symbol)
                        }
                        // a compound assignment (`op_AdditionAssignment` of C# 14) and what else C# has no operator syntax for here: as the method it is
                        else -> {
                            out.append(returns).append(' ')
                            memberOffsets[member.row] = out.length
                            out.append(member.name)
                        }
                    }
                    out.append(parameters(member)).append(";\n")
                }
                IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD -> {
                    out.append(returns).append(' ')
                    memberOffsets[member.row] = out.length
                    out.append(identifier(member.name))
                    if (methodNames.isNotEmpty()) out.append(member.typeParameters.joinToString(", ", "<", ">"))
                    out.append(parameters(member))
                    constraints(member.typeParameters, typeNames(type), methodNames)
                    out.append(";\n")
                }
                IndexedMemberKind.ENUM_MEMBER -> Unit
            }
        }
    }

    private const val INDENT = "    "
    private const val OBSOLETE = "System.ObsoleteAttribute"
    private val NUMBER = Regex("""-?\d+""")
}
