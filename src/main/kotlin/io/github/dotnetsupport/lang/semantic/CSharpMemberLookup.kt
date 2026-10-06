package io.github.dotnetsupport.lang.semantic

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberAccessExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberBindingExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpSimpleName
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.lang.Member
import io.github.dotnetsupport.lang.NativeCSharpMembers
import io.github.dotnetsupport.lang.NativeCSharpUsingCompletion
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypeKind

/**
 * What a name after a dot can be (CSHARP_PSI_MIGRATION.md, task C3, completion after a dot), on the resolver of layers 11a–11b: the
 * namespaces and types of a namespace (`System.|`), the static members and nested types of a type (`Console.|`, `Color.|`), the instance
 * members of a value's type with the inherited ones and the extension methods in scope (`orders.|`), both for `Color Color`. Members of
 * types of the solution are offered where C# lets the place see them (private inside the type, protected inside a derived one); of
 * assemblies the public ones (protected inside a type derived from theirs), not the ones hidden from the editor (`EditorBrowsable(Never)`).
 */
class CSharpMemberLookup(private val resolver: CSharpNameResolver) {
    /**
     * The members of one name, overloads together (the nearest type's first); [symbols] are of one kind but for a method group.
     * [inaccessible]: the place does not see them (private / protected of another type, C# §7.5): asked for by the second Ctrl+Space only.
     */
    class Entry(val name: String, val symbols: List<CSharpSymbol>, val inaccessible: Boolean = false) {
        val first: CSharpSymbol get() = symbols.first()
        override fun toString(): String = "$name ${symbols.size}"
    }

    /** What is left of the dot of [name]: the place of the completion (`a.|`, `a?.|`, `A.B.|` as a type). */
    fun qualifierOf(name: CSharpSimpleName): CSharpNameResolver.Qualifier? = when (val parent = name.parent) {
        is CSharpMemberAccessExpression -> if (parent.nameElement == name) parent.expression?.let(resolver::qualifier) else null
        is CSharpQualifiedName -> if (parent.right == name) parent.left?.let(resolver::qualifier) else null
        is CSharpMemberBindingExpression -> resolver.receiverOfBinding(parent)?.let { CSharpNameResolver.Qualifier.Value(it) }
        else -> null
    }

    /**
     * The names after the dot of [site] when what is left of it is [qualifier]. [typesOnly]: a place only a type can stand (`A.B.|` of a
     * declaration's type): no members but nested types. [matcher]: the names worth looking at (the prefix typed), all when null.
     * [throughThis]: the value is `this`, whose protected members of library bases are seen (C# §7.5.4 wants the instance to be of the
     * derived type, so `other.MemberwiseClone()` is not offered). [inaccessibleToo]: the members the place does not see come as entries
     * of their own ([Entry.inaccessible], under the names it sees) — the second Ctrl+Space (0.1.96).
     */
    fun entries(
        qualifier: CSharpNameResolver.Qualifier, site: PsiElement, typesOnly: Boolean = false, matcher: PrefixMatcher? = null, throughThis: Boolean = false,
        inaccessibleToo: Boolean = false,
    ): List<Entry> {
        val sink = Sink(matcher, inaccessibleToo)
        this.throughThis = throughThis
        when (qualifier) {
            is CSharpNameResolver.Qualifier.Namespace -> namespace(qualifier.name, sink)
            is CSharpNameResolver.Qualifier.Type -> members(qualifier.type, true, typesOnly, site, sink, 0)
            is CSharpNameResolver.Qualifier.Value -> if (!typesOnly) {
                members(qualifier.type, false, false, site, sink, 0)
                extensions(qualifier.type, site, sink)
            }
            is CSharpNameResolver.Qualifier.ValueOrType -> {
                if (!typesOnly) members(qualifier.value, false, false, site, sink, 0)
                members(qualifier.type, true, typesOnly, site, sink, 0)
                if (!typesOnly) extensions(qualifier.value, site, sink)
            }
        }
        return sink.entries()
    }

    // ---- namespaces

    private fun namespace(name: String, sink: Sink) {
        val project = resolver.file.project
        val all = com.intellij.psi.stubs.StubIndex.getInstance().getAllKeys(CSharpStubIndexKeys.NAMESPACES, project) + resolver.assemblies.namespaces
        for (child in NativeCSharpUsingCompletion.childNamespaces(name, all)) sink.add(child, CSharpSymbol.Namespace(CSharpNameResolver.join(name, child)), overloads = false)
        for ((type, arity) in NativeCSharpUsingCompletion.solutionTypesIn(resolver.file, name, sink.matcher)) {
            for (symbol in resolver.typesIn(name, type, arity)) sink.add(type, symbol, overloads = false)
        }
        for (type in resolver.assemblies.typesIn(name)) {
            if (type.declaringType != null || type.isHidden || type.isProtected || type.simpleName.startsWith("<")) continue
            // C# has no use for it (`void`), and Roslyn does not offer it (robot, E-81)
            if (type.fullName == "System.Void") continue
            sink.add(type.simpleName, CSharpSymbol.LibraryType(type), overloads = false)
        }
    }

    // ---- members

    private fun members(type: SemanticType, static: Boolean, typesOnly: Boolean, site: PsiElement, sink: Sink, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (type) {
            is SemanticType.Source -> {
                val visited = HashSet<String>()
                var level = listOf(type)
                var steps = 0
                while (level.isNotEmpty() && steps++ < MAX_DEPTH) {
                    val next = ArrayList<SemanticType.Source>()
                    for (current in level) {
                        if (!visited.add(current.info.key)) continue
                        sourceMembers(current, static, typesOnly, site, sink)
                        // `Color.|`: the members of the enum, not the static methods of System.Enum (as Roslyn, robot E-81)
                        if (static && current.info.kind == TypeKind.ENUM) continue
                        for (base in resolver.baseTypes(current)) when (base) {
                            is SemanticType.Source -> next += base
                            is SemanticType.Library -> members(base, static, typesOnly, site, sink, depth + 1)
                            else -> {}
                        }
                    }
                    level = next
                }
            }
            is SemanticType.Library -> {
                libraryMembers(type, static, typesOnly, site, sink)
                if (!static && type.type.kind == io.github.dotnetsupport.index.IndexedTypeKind.INTERFACE) resolver.libraryType(OBJECT)?.let { libraryMembers(it, false, typesOnly, site, sink) }
            }
            is SemanticType.ArrayOf -> resolver.libraryType("System.Array")?.let { members(it, static, typesOnly, site, sink, depth + 1) }
            is SemanticType.Parameter -> if (!static) {
                for (constraint in resolver.constraintsOf(type)) members(constraint, false, typesOnly, site, sink, depth + 1)
                resolver.libraryType(OBJECT)?.let { members(it, false, typesOnly, site, sink, depth + 1) }
            }
        }
    }

    /** The members declared in the parts of [type] (not its bases): what the place may see of them. */
    private fun sourceMembers(type: SemanticType.Source, static: Boolean, typesOnly: Boolean, site: PsiElement, sink: Sink) {
        val info = type.info
        // the members of one type together: overloads of two parts are one group, a name of a base is hidden by it
        val own = LinkedHashMap<String, MutableList<CSharpSymbol>>()
        for (part in info.parts) part.members { key, member ->
            if ('<' in key || '`' in key || !sink.takes(key)) return@members
            val seen = accessible(member, info, site)
            if (!seen && !sink.inaccessibleToo) return@members
            if (member.nestedType != null) {
                if (!static) return@members
                resolver.syntax.nestedTypeOf(member)?.let { if (seen) own.getOrPut(key) { ArrayList() } += CSharpSymbol.SourceType(it) else sink.addInaccessible(key, CSharpSymbol.SourceType(it)) }
                return@members
            }
            if (typesOnly || NativeCSharpMembers.isStatic(member) != static) return@members
            for (target in member.targets()) if (seen) own.getOrPut(key) { ArrayList() } += CSharpSymbol.SourceMember(target, member, type) else sink.addInaccessible(key, CSharpSymbol.SourceMember(target, member, type))
        }
        for ((key, symbols) in own) for (symbol in symbols.distinct()) sink.add(key, symbol, overloads = isMethod(symbol))
        sink.closeLevel()
    }

    /** C# §7.5: who sees a member of a type of the solution. Without an access modifier: public in an interface or an enum, else private. */
    private fun accessible(member: Member, declaring: TypeInfo, site: PsiElement): Boolean {
        val modifiers = member.modifiers
        if ("public" in modifiers || "internal" in modifiers) return true
        val enclosing = enclosing(site)
        if ("protected" in modifiers) return enclosing.any { it.key == declaring.key || derives(it, declaring) }
        if ("private" !in modifiers && (declaring.kind == TypeKind.INTERFACE || declaring.kind == TypeKind.ENUM)) return true
        // record parameters are public properties; members of the stubs keep their modifiers, so "nothing" is private
        if (modifiers.isEmpty() && member.declarationKey == io.github.dotnetsupport.lang.CSharpColors.PROPERTY && declaring.kind in RECORDS) return true
        return enclosing.any { it.key == declaring.key }
    }

    private var enclosingTypes: List<TypeInfo>? = null
    private var throughThis = false

    private fun enclosing(site: PsiElement): List<TypeInfo> = enclosingTypes ?: resolver.syntax.enclosingTypes(site).toList().also { enclosingTypes = it }

    private fun derives(type: TypeInfo, base: TypeInfo): Boolean = NativeCSharpMembers.baseTypes(type, resolver.syntax).any { it.key == base.key }

    /** The library types the types around [site] derive from: their protected members are seen there. */
    private var libraryBasesOfSite: Set<String>? = null

    private fun derivesFromLibrary(type: IndexedType, site: PsiElement): Boolean {
        val bases = libraryBasesOfSite ?: enclosing(site).flatMap { info -> resolver.libraryBases(resolver.selfType(info)) }
            .mapNotNullTo(HashSet()) { (it as? SemanticType.Library)?.type?.fullName }
            .also { libraryBasesOfSite = it }
        return type.fullName in bases
    }

    private fun libraryMembers(type: SemanticType.Library, static: Boolean, typesOnly: Boolean, site: PsiElement, sink: Sink) {
        // `ConsoleColor.|`: the members of the enum only, as for an enum of the solution
        val enumMembersOnly = static && type.type.kind == io.github.dotnetsupport.index.IndexedTypeKind.ENUM
        if (!typesOnly) {
            for ((name, inherited) in resolver.session.libraryMembers(resolver.assemblies, type.type)) {
                if (!sink.takes(name) || '.' in name || name.startsWith("op_") || name.startsWith("<")) continue
                for (found in inherited) {
                    val member = found.member
                    if (member.kind == IndexedMemberKind.CONSTRUCTOR || member.kind == IndexedMemberKind.OPERATOR || member.kind == IndexedMemberKind.INDEXER) continue
                    if (member.isHidden) continue
                    // the destructor of `object`: C# cannot call it, Roslyn does not offer it after `this.` (robot, E-81)
                    if (name == "Finalize" && found.member.type.fullName == OBJECT) continue
                    val seen = !member.isProtected || throughThis && derivesFromLibrary(found.from?.type ?: type.type, site)
                        && (!member.isFriendOnly || resolver.assemblies.grants(member.type.index))
                    if (!seen && !sink.inaccessibleToo) continue
                    val isStatic = member.isStatic || member.kind == IndexedMemberKind.CONSTANT || member.kind == IndexedMemberKind.ENUM_MEMBER
                    if (isStatic != static) continue
                    if (enumMembersOnly && member.kind != IndexedMemberKind.ENUM_MEMBER) continue
                    val symbol = CSharpSymbol.LibraryMember(member, resolver.declaringArguments(type, found.from))
                    if (seen) sink.add(name, symbol, overloads = member.kind.isCallable) else sink.addInaccessible(name, symbol)
                }
            }
        }
        if (static) {
            val nested = type.type.nestedTypes + resolver.session.baseTypes(resolver.assemblies, type.type).flatMap { it.type.nestedTypes }
            for (child in nested) if (!child.isHidden && sink.takes(child.simpleName)) {
                if (!child.isProtected || derivesFromLibrary(type.type, site)) sink.add(child.simpleName, CSharpSymbol.LibraryType(child), overloads = false)
                else if (sink.inaccessibleToo) sink.addInaccessible(child.simpleName, CSharpSymbol.LibraryType(child))
            }
        }
        sink.closeLevel()
    }

    private fun extensions(receiver: SemanticType, site: PsiElement, sink: Sink) {
        for (symbol in resolver.extensionMethodsFor(receiver, site, sink.matcher?.let { m -> m::prefixMatches })) {
            val name = when (symbol) {
                is CSharpSymbol.LibraryMember -> symbol.member.name
                is CSharpSymbol.SourceMember -> (symbol.element as? io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration)?.identifier?.text
                else -> null
            } ?: continue
            // an extension method does not hide a member, it is one more overload of the name (C# §12.8.10.3)
            sink.addExtension(name, symbol)
        }
    }

    private fun isMethod(symbol: CSharpSymbol): Boolean = resolver.isMethod(symbol)

    /**
     * The entries by name: a name a nearer type has hides the same name of a base (C# §12.5), but methods of the bases join the group of
     * a method of a nearer type (they are overloads unless the same signature — completion shows a group once anyway).
     */
    private inner class Sink(val matcher: PrefixMatcher?, val inaccessibleToo: Boolean) {
        private val byName = LinkedHashMap<String, MutableList<CSharpSymbol>>()
        private val closed = HashSet<String>()
        private val thisLevel = HashSet<String>()
        // what the place does not see, apart: a seen name of a base is not hidden by a private one of a nearer type
        private val inaccessible = LinkedHashMap<String, MutableList<CSharpSymbol>>()

        fun takes(name: String): Boolean = matcher == null || matcher.prefixMatches(name)

        fun addInaccessible(name: String, symbol: CSharpSymbol) {
            if (takes(name)) inaccessible.getOrPut(name) { ArrayList() }.let { if (symbol !in it) it += symbol }
        }

        fun add(name: String, symbol: CSharpSymbol, overloads: Boolean) {
            if (!takes(name)) return
            val known = byName[name]
            if (known != null && name in closed && !(overloads && known.all(::isMethod))) return
            byName.getOrPut(name) { ArrayList() }.let { if (symbol !in it) it += symbol }
            thisLevel += name
        }

        fun addExtension(name: String, symbol: CSharpSymbol) {
            val known = byName[name]
            if (known != null && !known.all(::isMethod)) return
            byName.getOrPut(name) { ArrayList() }.let { if (symbol !in it) it += symbol }
        }

        /** The names of the type just walked hide those of the types after it. */
        fun closeLevel() {
            closed += thisLevel
            thisLevel.clear()
        }

        fun entries(): List<Entry> = byName.map { (name, symbols) -> Entry(name, symbols) } +
            inaccessible.filterKeys { it !in byName }.map { (name, symbols) -> Entry(name, symbols, inaccessible = true) }
    }

    private companion object {
        const val OBJECT = "System.Object"
        const val MAX_DEPTH = 16
        val RECORDS = setOf(TypeKind.RECORD, TypeKind.RECORD_STRUCT)
    }
}
