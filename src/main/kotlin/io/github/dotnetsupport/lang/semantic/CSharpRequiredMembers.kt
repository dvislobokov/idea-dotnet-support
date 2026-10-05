package io.github.dotnetsupport.lang.semantic

import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.Member

/**
 * The `required` members of C# 11 (0.1.98): what an object creation must set in its initializer (CS9035), what completion of `new T`
 * writes as an initializer and what «Add initializer for required members» fills. Of a type of the solution: the properties and fields
 * written `required`, of its bases too; of an assembly: the members the index flags (`RequiredMemberAttribute`). A constructor marked
 * `[SetsRequiredMembers]` sets them all.
 */
class CSharpRequiredMembers(private val r: CSharpNameResolver) {
    /** A required member: [name] as the initializer writes it, [owner] the type that declares it (`OrderLine` of `OrderLine.Sku`). */
    class Required(val name: String, val owner: String)

    /** The required members of [type], those of its bases first, in the order written; empty for a type without any. */
    fun of(type: SemanticType): List<Required> {
        val chain = ArrayList<List<Required>>()
        var current: SemanticType? = type
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            when (current) {
                is SemanticType.Source -> {
                    chain += own(current)
                    current = r.baseTypes(current).firstOrNull { it is SemanticType.Source || it is SemanticType.Library && it.type.kind != IndexedTypeKind.INTERFACE }
                }
                is SemanticType.Library -> {
                    chain += current.type.members.filter { it.isRequired && !it.isStatic && (it.kind == IndexedMemberKind.PROPERTY || it.kind == IndexedMemberKind.FIELD) }
                        .map { Required(it.name, current.type.simpleName) }
                    for (base in r.session.baseTypes(r.assemblies, current.type)) {
                        chain += base.type.members.filter { it.isRequired && !it.isStatic && (it.kind == IndexedMemberKind.PROPERTY || it.kind == IndexedMemberKind.FIELD) }
                            .map { Required(it.name, base.type.simpleName) }
                    }
                    current = null
                }
                else -> current = null
            }
        }
        // the base's first; an override of a required property stays where the base put it
        val seen = HashSet<String>()
        return chain.asReversed().flatten().filter { seen.add(it.name) }
    }

    private fun own(type: SemanticType.Source): List<Required> {
        val result = ArrayList<Required>()
        val owner = type.info.qualifiedName.substringAfterLast('.')
        for (part in type.info.parts) {
            val declaration = part.element() as? CSharpTypeDeclaration ?: continue
            for (member in declaration.members) {
                if (member.modifiers.none { it.text == "required" } || member.modifiers.any { it.text == "static" }) continue
                when (member) {
                    is CSharpPropertyDeclaration -> member.identifier?.text?.let { result += Required(it, owner) }
                    is CSharpBaseFieldDeclaration -> member.declaration?.variables.orEmpty().forEach { v -> v.identifier?.text?.let { result += Required(it, owner) } }
                }
            }
        }
        return result
    }

    /**
     * The required members [creation] leaves unset: none when its constructor is `[SetsRequiredMembers]`, null when it is not known which
     * constructor it calls (or whether that one sets them).
     */
    fun missing(creation: CSharpBaseObjectCreationExpression, type: SemanticType): List<Required>? {
        val required = of(type)
        if (required.isEmpty()) return required
        val setsAll = setsRequired(type, creation.argumentList?.arguments.orEmpty()) ?: return null
        if (setsAll) return emptyList()
        val assigned = assignedNames(creation.initializer)
        return required.filter { it.name !in assigned }
    }

    /** Whether the constructor [arguments] pick sets the required members; null when the pick is not sure. */
    fun setsRequired(type: SemanticType, arguments: List<CSharpArgument>): Boolean? {
        val constructors = constructorsOf(type)
        if (constructors.isEmpty()) return false
        val marks = constructors.map { it to marked(it) }
        if (marks.none { it.second }) return false
        if (marks.all { it.second }) return true
        val symbols = constructors.mapNotNull { it as? CSharpSymbol }
        val chosen = r.pickConstructor(symbols, arguments) ?: return null
        return marks.firstOrNull { it.first == chosen }?.second
    }

    /**
     * Whether `new T` with no arguments compiles and leaves the required members to the initializer: there is a parameterless constructor
     * (or none is written) and it is not `[SetsRequiredMembers]`. Completion writes `new T { … }` then; otherwise `new T(|)` as before.
     */
    fun createdWithoutArguments(type: SemanticType): Boolean {
        val constructors = constructorsOf(type)
        if (constructors.isEmpty()) return true
        val parameterless = constructors.filter { parameterCount(it) == 0 }
        if (parameterless.isEmpty()) return (type as? SemanticType.Library)?.type?.kind == IndexedTypeKind.STRUCT || isStruct(type)
        return parameterless.none(::marked)
    }

    private fun isStruct(type: SemanticType): Boolean =
        type is SemanticType.Source && (type.info.kind == io.github.dotnetsupport.lang.TypeKind.STRUCT || type.info.kind == io.github.dotnetsupport.lang.TypeKind.RECORD_STRUCT)

    /** The instance constructors: [CSharpSymbol.LibraryMember] of an assembly, [CSharpSymbol.SourceMember] (and a primary constructor) of the solution. */
    private fun constructorsOf(type: SemanticType): List<CSharpSymbol> = when (type) {
        is SemanticType.Library -> type.type.members.filter { it.kind == IndexedMemberKind.CONSTRUCTOR && !it.isStatic && !it.isHidden }.map { CSharpSymbol.LibraryMember(it, type.arguments) }
        is SemanticType.Source -> type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.flatMap { declaration ->
            val explicit = declaration.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } }
            (explicit + listOfNotNull(declaration.takeIf { it.parameterList != null })).map { CSharpSymbol.SourceMember(it, Member.method(emptyList(), false).at { it }, type) }
        }
        else -> emptyList()
    }

    private fun parameterCount(constructor: CSharpSymbol): Int = when (constructor) {
        is CSharpSymbol.LibraryMember -> constructor.member.parameters.count { !it.isOptional }
        is CSharpSymbol.SourceMember -> when (val e = constructor.element) {
            is CSharpConstructorDeclaration -> e.parameterList?.parameters.orEmpty().count { it.default == null && it.modifiers.none { m -> m.text == "params" } }
            is CSharpTypeDeclaration -> e.parameterList?.parameters.orEmpty().count { it.default == null }
            else -> 0
        }
        else -> 0
    }

    private fun marked(constructor: CSharpSymbol): Boolean = when (constructor) {
        is CSharpSymbol.LibraryMember -> constructor.member.attributes.any { it == SETS_REQUIRED_FULL }
        is CSharpSymbol.SourceMember -> (constructor.element as? CSharpConstructorDeclaration)?.attributeLists.orEmpty().any { list -> list.attributes.any { attributeName(it) == SETS_REQUIRED } }
        else -> false
    }

    companion object {
        private const val MAX_DEPTH = 16
        private const val SETS_REQUIRED = "SetsRequiredMembers"
        private const val SETS_REQUIRED_FULL = "System.Diagnostics.CodeAnalysis.SetsRequiredMembersAttribute"

        /** The members an object or `with` initializer assigns by name: `Sku = …`. */
        fun assignedNames(initializer: CSharpInitializerExpression?): Set<String> =
            initializer?.expressions.orEmpty().mapNotNullTo(HashSet()) { ((it as? CSharpAssignmentExpression)?.left as? CSharpIdentifierName)?.identifier?.text }

        fun attributeName(attribute: CSharpAttribute): String? {
            val name = when (val n = attribute.nameElement) {
                is CSharpSimpleName -> n.identifier?.text
                is CSharpQualifiedName -> n.right?.identifier?.text
                is CSharpAliasQualifiedName -> n.nameElement?.identifier?.text
                else -> null
            } ?: return null
            return name.removeSuffix("Attribute")
        }
    }
}
