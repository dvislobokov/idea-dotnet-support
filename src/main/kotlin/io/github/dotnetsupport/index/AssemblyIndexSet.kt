package io.github.dotnetsupport.index

/**
 * The assemblies a project is compiled against, together: what a resolver asks about the types of the libraries. A reference of a
 * signature names a type and not its assembly, so a type is looked up in all of them (the first that has it wins: a reference
 * assembly that forwards a type has no row for it). Nothing is loaded up front: every question is a few binary searches in the
 * mapped indexes.
 */
class AssemblyIndexSet(val indexes: List<AssemblyIndex>) {
    /**
     * A base type or an interface of a type, the way the type sees it: [reference] has the type parameters of the type that was
     * asked about (`class MyList : List<int>` gives `List<int>` and, from `List<T> : IList<T>`, `IList<int>`).
     */
    class Supertype(val type: IndexedType, val reference: IndexedTypeRef) {
        /** The type arguments of [reference]: what the type parameters in the signatures of the members of [type] stand for. */
        val arguments: List<IndexedTypeRef> get() = (reference as? IndexedTypeRef.Generic)?.arguments.orEmpty()
        override fun toString(): String = reference.display()
    }

    /** A member with the supertype it comes from: its signature is [IndexedTypeRef.substitute]d with [Supertype.arguments]. */
    class Inherited(val member: IndexedMember, val from: Supertype?) {
        val arguments: List<IndexedTypeRef> get() = from?.arguments.orEmpty()
        override fun toString(): String = member.toString()
    }

    fun findType(fullName: String): IndexedType? = indexes.firstNotNullOfOrNull { it.findType(fullName) }

    fun findType(namespace: String, path: String): IndexedType? = indexes.firstNotNullOfOrNull { it.findType(namespace, path) }

    /** The type a reference names, its definition for a generic instance; null for type parameters, arrays and pointers. */
    fun resolve(reference: IndexedTypeRef): IndexedType? = reference.definitionName?.let(::findType)

    fun typesIn(namespace: String): List<IndexedType> = indexes.flatMap { it.typesIn(namespace) }

    val namespaces: Set<String> by lazy { indexes.flatMapTo(LinkedHashSet()) { it.namespaces } }

    /** The base classes of [type], the nearest first; `object` last, when it is among the assemblies. */
    fun baseTypes(type: IndexedType): List<Supertype> {
        val found = ArrayList<Supertype>()
        var current = type
        var reference = type.baseType ?: return found
        while (found.size < MAX_DEPTH) {
            val base = resolve(reference) ?: break
            found += Supertype(base, reference)
            current = base
            val next = current.baseType ?: break
            reference = next.substitute((reference as? IndexedTypeRef.Generic)?.arguments.orEmpty())
        }
        return found
    }

    /** Every interface [type] implements, its own, the ones of its bases and of its interfaces, each once. */
    fun interfaces(type: IndexedType): List<Supertype> {
        val found = LinkedHashMap<IndexedTypeRef, Supertype>()
        fun visit(owner: IndexedType, arguments: List<IndexedTypeRef>) {
            for (declared in owner.interfaces) {
                val reference = declared.substitute(arguments)
                if (reference in found) continue
                val resolved = resolve(reference) ?: continue
                found[reference] = Supertype(resolved, reference)
                visit(resolved, (reference as? IndexedTypeRef.Generic)?.arguments.orEmpty())
            }
        }
        visit(type, type.typeParameters.indices.map { IndexedTypeRef.TypeParameter(it, false) })
        for (base in baseTypes(type)) visit(base.type, base.arguments)
        return found.values.toList()
    }

    /**
     * The members of [type] and, when [inherited], the ones it gets from its base classes (an interface: from its interfaces) that
     * it does not override or hide: a member of a base with the same name and the same parameters (after the substitution) is
     * hidden by the one of the type. Constructors are not inherited.
     */
    fun members(type: IndexedType, inherited: Boolean = true): List<Inherited> {
        val own = type.members.map { Inherited(it, null) }
        if (!inherited) return own
        val result = ArrayList(own)
        val seen = own.mapTo(HashSet()) { key(it.member, emptyList()) }
        val supertypes = if (type.kind == IndexedTypeKind.INTERFACE) interfaces(type) else baseTypes(type)
        for (supertype in supertypes) {
            for (member in supertype.type.members) {
                if (member.kind == IndexedMemberKind.CONSTRUCTOR) continue
                if (seen.add(key(member, supertype.arguments))) result += Inherited(member, supertype)
            }
        }
        return result
    }

    /** What hides what: the name and, for what takes parameters, their types and their passing. */
    private fun key(member: IndexedMember, arguments: List<IndexedTypeRef>): String = when (member.kind) {
        IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD, IndexedMemberKind.OPERATOR, IndexedMemberKind.INDEXER ->
            member.name + "`" + member.arity + member.parameters.joinToString(",", "(", ")") { it.typeRef.substitute(arguments).docId() + if (it.isByReference) "@" else "" }
        else -> member.name
    }

    /** Extension methods by what they extend (see [AssemblyIndex.extensions]): a metadata name of a type, `[]`, `!`. */
    fun extensions(key: String): List<IndexedMember> = indexes.flatMap { it.extensions(key) }

    /**
     * The extension methods that may apply to a receiver of [type]: the ones for it, for its base classes and interfaces, and the
     * generic ones (`this T`). Whether the type arguments fit is for the caller to decide.
     */
    fun extensionsFor(type: IndexedType): List<IndexedMember> {
        val keys = LinkedHashSet<String>()
        keys += type.fullName
        baseTypes(type).forEach { keys += it.type.fullName }
        interfaces(type).forEach { keys += it.type.fullName }
        keys += GENERIC_RECEIVER
        return keys.flatMap(::extensions)
    }

    fun doc(docId: String): IndexedDoc? = indexes.firstNotNullOfOrNull { it.doc(docId) }

    /** Every type of the assemblies whose own simple name is [name] (any namespace, any arity, nested ones too): «Import type» (task C4c). */
    fun typesNamed(name: String): List<IndexedType> = indexes.flatMap { index -> AssemblyIndexNames.of(index).typeRows(name).map(index::type) }

    /** Every member named [name] of every type of the assemblies (no constructors, no operators): what an extension method may be. */
    fun membersNamed(name: String): List<IndexedMember> = indexes.flatMap { index -> AssemblyIndexNames.of(index).memberRows(name).map(index::member) }

    companion object {
        /** The key of the extension methods of a type parameter: `this T value`. */
        const val GENERIC_RECEIVER = "!"
        const val ARRAY_RECEIVER = "[]"
        private const val MAX_DEPTH = 64
    }
}
