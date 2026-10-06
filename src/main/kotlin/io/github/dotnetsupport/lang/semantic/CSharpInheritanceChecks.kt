package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.NativeCSharpGenerate
import io.github.dotnetsupport.lang.NativeCSharpScopes
import io.github.dotnetsupport.lang.NativeCSharpTypePositions
import io.github.dotnetsupport.lang.TypeKind

/**
 * The errors of inheritance, overriding, abstract and static members, with Roslyn's codes, texts and spans: CS0115 / CS0506 / CS0239 /
 * CS0507 / CS0508 (an `override` and what it overrides), CS0513 / CS0500 / CS0501 (`abstract` and bodies), CS0509 / CS0527 (the base list),
 * CS0144 / CS0712 (`new` of an abstract type, an interface, a static class), CS0176 (a static member through an instance), CS0236 (a
 * field or property initializer that uses an instance member).
 *
 * Precision first, as in [CSharpSemanticChecks]: an override is judged only when every base class resolves, none of them (nor the type)
 * is partial, the signatures compare surely (no `ref` kinds, generic methods or tuples); records and their synthesized members, members
 * of a type nested in a generic one and anything else not surely known stay silent.
 */
internal class CSharpInheritanceChecks(
    private val resolver: CSharpNameResolver, private val checks: CSharpSemanticChecks, private val report: (String, String, TextRange) -> Unit,
) {
    private val session = resolver.session

    fun check(element: PsiElement) {
        when (element) {
            is CSharpTypeDeclaration -> checkBaseList(element)
            is CSharpMethodDeclaration -> { checkBodies(element); checkOverride(element) }
            is CSharpBasePropertyDeclaration -> { checkAbstractAccessors(element); checkOverride(element) }
            is CSharpEventFieldDeclaration -> { checkAbstractEvent(element); checkOverride(element) }
            is CSharpObjectCreationExpression -> checkCreation(element)
            is CSharpImplicitObjectCreationExpression -> checkImplicitCreation(element)
            is CSharpMemberAccessExpression -> checkStaticAccess(element)
            is CSharpSimpleName -> checkInitializer(element)
        }
    }

    // ---- CS0509, CS0527

    private fun checkBaseList(type: CSharpTypeDeclaration) {
        val bases = type.baseList?.types.orEmpty()
        if (bases.isEmpty()) return
        val info = resolver.syntax.declaredType(type) ?: return
        when (info.kind) {
            TypeKind.CLASS, TypeKind.RECORD -> {
                val syntax = bases.first().type ?: return
                val base = resolver.resolveType(syntax) ?: return
                val self = display(resolver.selfType(info)) ?: return
                val record = info.kind == TypeKind.RECORD
                when {
                    isSealedBase(base) -> report("CS0509", "'$self': cannot derive from sealed type '${display(base) ?: return}'", syntax.textRange)
                    // on the name of the class, as Roslyn
                    !record && isStaticClass(base) -> report("CS0709", "'$self': cannot derive from static class '${display(base) ?: return}'", (type.identifier ?: return).textRange)
                    !record && isRecord(base) -> report("CS8865", "Only records may inherit from records.", syntax.textRange)
                    record && isPlainClass(base) -> report("CS8864", "Records may only inherit from object or another record", syntax.textRange)
                }
            }
            // a struct and an interface have no base class: every type of the list must be an interface
            TypeKind.STRUCT, TypeKind.RECORD_STRUCT, TypeKind.INTERFACE -> for (entry in bases) {
                val syntax = entry.type ?: continue
                val base = resolver.resolveType(syntax) ?: continue
                val notInterface = when (base) {
                    is SemanticType.Source -> base.info.kind != null && base.info.kind != TypeKind.INTERFACE
                    is SemanticType.Library -> base.type.kind != IndexedTypeKind.INTERFACE
                    else -> false
                }
                if (notInterface) report("CS0527", "Type '${display(base) ?: continue}' in interface list is not an interface", syntax.textRange)
            }
            else -> {}
        }
    }

    /** A class no class may derive from: a sealed class or record (a record is CS0509 there too, before CS8865), a struct, an enum. */
    private fun isSealedBase(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> when (type.info.kind) {
            TypeKind.STRUCT, TypeKind.RECORD_STRUCT, TypeKind.ENUM -> true
            TypeKind.CLASS, TypeKind.RECORD -> type.info.parts.any { "sealed" in it.modifiers }
            else -> false
        }
        is SemanticType.Library -> when (type.type.kind) {
            IndexedTypeKind.STRUCT, IndexedTypeKind.ENUM -> true
            IndexedTypeKind.CLASS -> type.type.isSealed && !type.type.isStatic
            else -> false
        }
        else -> false
    }

    private fun isStaticClass(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.kind == TypeKind.STATIC_CLASS
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.STATIC_CLASS || type.type.kind == IndexedTypeKind.CLASS && type.type.isStatic
        else -> false
    }

    private fun isRecord(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.kind == TypeKind.RECORD
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.CLASS && type.type.isRecord
        else -> false
    }

    /** A class that is not a record, not `object`, not static: what a record may not derive from (CS8864). */
    private fun isPlainClass(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.kind == TypeKind.CLASS
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.CLASS && !type.type.isRecord && !type.type.isStatic && type.type.fullName != "System.Object"
        else -> false
    }

    // ---- CS0513, CS0500, CS0501

    /** A class or record class (not static, not sealed), with whether it is surely not abstract; null for any other type. */
    private fun classOf(member: PsiElement): Pair<CSharpTypeDeclaration, Boolean>? {
        val owner = member.parent as? CSharpTypeDeclaration ?: return null
        val info = resolver.syntax.declaredType(owner) ?: return null
        if (info.kind != TypeKind.CLASS && info.kind != TypeKind.RECORD) return null
        if (info.parts.any { "sealed" in it.modifiers }) return null
        val abstract = info.parts.any { "abstract" in it.modifiers }
        // a generator may add another part; one that makes the class abstract is not seen in practice: only a project that may generate
        // types (Razor, XAML, generator packages) keeps a partial class unknown
        return owner to (!abstract && (!checks.isPartial(info) || !checks.generated))
    }

    private fun checkBodies(method: CSharpMethodDeclaration) {
        if (method.explicitInterfaceSpecifier != null) return
        val name = method.identifier?.takeIf { it.textLength > 0 } ?: return
        val modifiers = method.modifiers.map { it.text }
        val hasBody = method.body != null || method.expressionBody != null
        val owner = method.parent as? CSharpTypeDeclaration ?: return
        if (owner is CSharpInterfaceDeclaration) return
        if ("abstract" in modifiers) {
            val (_, notAbstract) = classOf(method) ?: return
            val shown = memberDisplay(method) ?: return
            if (hasBody && !notAbstract && owner.modifiers.any { it.text == "abstract" })
                report("CS0500", "'$shown' cannot declare a body because it is marked abstract", name.textRange)
            else if (!hasBody && notAbstract) report("CS0513", "'$shown' is abstract but it is contained in non-abstract type '${typeDisplay(owner) ?: return}'", name.textRange)
            return
        }
        if (hasBody || "extern" in modifiers || "partial" in modifiers) return
        val info = resolver.syntax.declaredType(owner) ?: return
        if (info.kind !in setOf(TypeKind.CLASS, TypeKind.RECORD, TypeKind.STRUCT, TypeKind.RECORD_STRUCT, TypeKind.STATIC_CLASS)) return
        val shown = memberDisplay(method) ?: return
        report("CS0501", "'$shown' must declare a body because it is not marked abstract, extern, or partial", name.textRange)
    }

    /** `abstract int P { get; set; }`: CS0513 on each accessor in a class that is not abstract, CS0500 on one with a body in one that is. */
    private fun checkAbstractAccessors(property: CSharpBasePropertyDeclaration) {
        if (property is CSharpEventDeclaration || property.explicitInterfaceSpecifier != null) return
        if (property.modifiers.none { it.text == "abstract" }) return
        val (owner, notAbstract) = classOf(property) ?: return
        val expressionBody = (property as? CSharpPropertyDeclaration)?.expressionBody ?: (property as? CSharpIndexerDeclaration)?.expressionBody
        val accessors = property.accessorList?.accessors ?: return
        if (expressionBody != null || accessors.isEmpty()) return
        val shown = memberDisplay(property) ?: return
        for (accessor in accessors) {
            val keyword = accessor.keyword?.takeIf { it.textLength > 0 } ?: return
            val hasBody = accessor.body != null || accessor.expressionBody != null
            if (hasBody && !notAbstract && owner.modifiers.any { it.text == "abstract" })
                report("CS0500", "'$shown.${keyword.text}' cannot declare a body because it is marked abstract", keyword.textRange)
            else if (!hasBody && notAbstract)
                report("CS0513", "'$shown.${keyword.text}' is abstract but it is contained in non-abstract type '${typeDisplay(owner) ?: return}'", keyword.textRange)
        }
    }

    private fun checkAbstractEvent(event: CSharpEventFieldDeclaration) {
        if (event.modifiers.none { it.text == "abstract" }) return
        val (owner, notAbstract) = classOf(event) ?: return
        if (!notAbstract) return
        val type = typeDisplay(owner) ?: return
        for (variable in event.declaration?.variables.orEmpty()) {
            val name = variable.identifier?.takeIf { it.textLength > 0 } ?: continue
            report("CS0513", "'$type.${name.text}' is abstract but it is contained in non-abstract type '$type'", name.textRange)
        }
    }

    // ---- CS0115, CS0506, CS0239, CS0507, CS0508

    private enum class Kind { METHOD, PROPERTY, INDEXER, EVENT, OTHER }

    /**
     * An `override` member as written: [parameters] of a method or an indexer, [at] its name (`this` of an indexer), [typeParameters] of a
     * generic method, [readOnly] a property or indexer without `set` / `init`.
     */
    private class Overriding(
        val kind: Kind, val name: String, val at: PsiElement, val parameters: List<CSharpParameter>, val arity: Int, val display: String, val returnType: CSharpType?,
        val typeParameters: List<String> = emptyList(), val readOnly: Boolean = false,
    )

    /**
     * A member of a base class: [parameters] substituted with the type arguments the derived type gives (a type parameter of the method as
     * `!!index`), [refKinds] `ref` / `out` / `in` / "" of each; null where one is not known. [access] the declared one for the message,
     * [required] what an override must write (`protected` for a `protected internal` member of another assembly); [returnType] of a
     * method, the type of a property or indexer, [readOnly] of those: no setter (an override may then narrow the type, C# 9).
     */
    private class BaseMember(
        val kind: Kind, val parameters: List<SemanticType?>, val refKinds: List<String?>, val arity: Int, val modifiers: Set<String>, val display: String?,
        val returnType: SemanticType?, val void: Boolean, val access: String? = null, val required: String? = null, val readOnly: Boolean = false,
    )

    private fun checkOverride(member: CSharpMemberDeclaration) {
        if (member.modifiers.none { it.text == "override" } || member.modifiers.any { it.text == "static" || it.text == "new" }) return
        val owner = member.parent as? CSharpTypeDeclaration ?: return
        if (owner is CSharpInterfaceDeclaration) return
        val info = resolver.syntax.declaredType(owner) ?: return
        if (info.kind !in setOf(TypeKind.CLASS, TypeKind.RECORD, TypeKind.STRUCT, TypeKind.RECORD_STRUCT) || checks.isPartial(info)) return
        val self = resolver.selfType(info)
        if (!checks.isKnown(self)) return
        val overriding = overriding(member) ?: return
        val record = info.kind == TypeKind.RECORD || info.kind == TypeKind.RECORD_STRUCT
        if (record && overriding.name in RECORD_MEMBERS) return
        val chain = classChain(self) ?: return
        if (chain.records && overriding.name in RECORD_MEMBERS || overriding.name in chain.positional) return
        var match: BaseMember? = null
        search@ for (base in chain.types) {
            val members = baseMembers(base, overriding) ?: return
            for (candidate in members) when (compare(overriding, candidate)) {
                Same.YES -> { match = candidate; break@search }
                Same.UNKNOWN -> return
                Same.NO -> {}
            }
        }
        val shown = overriding.display
        if (match == null) return report("CS0115", "'$shown': no suitable method found to override", overriding.at.textRange)
        val base = match.display ?: return
        val m = match.modifiers
        when {
            "static" in m -> {}
            "sealed" in m -> report("CS0239", "'$shown': cannot override inherited member '$base' because it is sealed", overriding.at.textRange)
            "virtual" !in m && "abstract" !in m && "override" !in m ->
                report("CS0506", "'$shown': cannot override inherited member '$base' because it is not marked virtual, abstract, or override", overriding.at.textRange)
            else -> {
                val own = accessOf(member.modifiers.map { it.text })
                if (match.access != null && match.required != null && match.required != own) {
                    report("CS0507", "'$shown': cannot change access modifiers when overriding '${match.access}' inherited member '$base'", overriding.at.textRange)
                } else if (overriding.kind == Kind.METHOD) {
                    val expected = returnMismatch(overriding, match) ?: return
                    report("CS0508", "'$shown': return type must be '$expected' to match overridden member '$base'", overriding.at.textRange)
                } else if (overriding.kind == Kind.PROPERTY || overriding.kind == Kind.INDEXER) {
                    // a property may narrow its type (C# 9) only when neither has a setter
                    val expected = returnMismatch(overriding, match, exact = !(overriding.readOnly && match.readOnly)) ?: return
                    report("CS1715", "'$shown': type must be '$expected' to match overridden member '$base'", overriding.at.textRange)
                }
            }
        }
    }

    private fun overriding(member: CSharpMemberDeclaration): Overriding? {
        val display = memberDisplay(member) ?: return null
        return when (member) {
            is CSharpMethodDeclaration -> {
                if (member.explicitInterfaceSpecifier != null) return null
                val name = member.identifier?.takeIf { it.textLength > 0 } ?: return null
                val typeParameters = member.typeParameterList?.parameters.orEmpty().map { it.identifier?.text ?: return null }
                Overriding(Kind.METHOD, name.text, name, member.parameterList?.parameters.orEmpty(), typeParameters.size, display, member.returnType, typeParameters)
            }
            is CSharpPropertyDeclaration -> {
                if (member.explicitInterfaceSpecifier != null) return null
                val name = member.identifier?.takeIf { it.textLength > 0 } ?: return null
                Overriding(Kind.PROPERTY, name.text, name, emptyList(), 0, display, member.type, readOnly = readOnly(member))
            }
            is CSharpIndexerDeclaration -> {
                if (member.explicitInterfaceSpecifier != null) return null
                val at = member.thisKeyword ?: return null
                Overriding(Kind.INDEXER, "this", at, member.parameterList?.parameters.orEmpty(), 0, display, member.type, readOnly = readOnly(member))
            }
            is CSharpEventDeclaration -> {
                if (member.explicitInterfaceSpecifier != null) return null
                val name = member.identifier?.takeIf { it.textLength > 0 } ?: return null
                Overriding(Kind.EVENT, name.text, name, emptyList(), 0, display, member.type)
            }
            is CSharpEventFieldDeclaration -> {
                val variable = member.declaration?.variables?.singleOrNull() ?: return null
                val name = variable.identifier?.takeIf { it.textLength > 0 } ?: return null
                Overriding(Kind.EVENT, name.text, name, emptyList(), 0, display, member.declaration?.type)
            }
            else -> null
        }
    }

    /** A property or indexer without `set` / `init`: an expression body or `get` accessors only. */
    private fun readOnly(property: CSharpBasePropertyDeclaration): Boolean =
        property.accessorList?.accessors?.all { it.keyword?.text == "get" } ?: true

    private class Chain(val types: List<SemanticType>, val records: Boolean, val positional: Set<String>)

    /** The base classes, nearest first, substituted; null when one is partial or does not resolve. */
    private fun classChain(self: SemanticType.Source): Chain? {
        val types = ArrayList<SemanticType>()
        var records = false
        val positional = HashSet<String>()
        var current: SemanticType.Source = self
        while (types.size < MAX_DEPTH) {
            val base = resolver.baseTypes(current).firstOrNull { !NativeCSharpGenerate.isInterface(it) } ?: return null
            when (base) {
                is SemanticType.Source -> {
                    if (checks.isPartial(base.info) || base.info.kind == null) return null
                    if (base.info.kind == TypeKind.RECORD) {
                        records = true
                        for (part in base.info.parts) (part.element() as? CSharpTypeDeclaration)?.parameterList?.parameters?.forEach { p -> p.identifier?.text?.let(positional::add) }
                    }
                    types += base
                    current = base
                }
                is SemanticType.Library -> {
                    types += base
                    for (supertype in session.baseTypes(resolver.assemblies, base.type)) types += resolver.fromRef(supertype.reference, base.arguments) ?: return null
                    if ((types.filterIsInstance<SemanticType.Library>()).any { it.type.isRecord }) records = true
                    return Chain(types, records, positional)
                }
                else -> return null
            }
        }
        return null
    }

    /** The members of [base] named as [overriding]: of its kind; null when something else of that name is there or a part is not known. */
    private fun baseMembers(base: SemanticType, overriding: Overriding): List<BaseMember>? {
        val found = ArrayList<BaseMember>()
        when (base) {
            is SemanticType.Source -> for (part in base.info.parts) {
                val declaration = part.element() as? CSharpTypeDeclaration ?: return null
                val owner = (declaration.containingFile as? CSharpFile)?.let(session::reachable) ?: return null
                val typeShown = display(base) ?: return null
                for (member in declaration.members) {
                    val kind = kindsAndNames(member).filter { it.second == overriding.name }.map { it.first }.distinct().singleOrNull() ?: continue
                    if (kind != overriding.kind || member is CSharpEventFieldDeclaration && member.declaration?.variables?.size != 1) return null
                    val modifiers = member.modifiers.map { it.text }.toSet()
                    // an inaccessible member of a base (private) is not what Roslyn looks for; say nothing
                    val access = accessOf(modifiers)
                    if (access == "private") return null
                    // `protected internal` of another project is overridden as `protected`: only these two are sure
                    val known = access.takeIf { it == "public" || it == "protected" }
                    val parameters = parametersOf(member).map { p -> p.type?.let(owner::resolveType)?.let { resolver.substitute(it, base) } }
                    val refKinds = parametersOf(member).map { p -> refKind(p.modifiers.map { it.text }) }
                    val arity = (member as? CSharpMethodDeclaration)?.typeParameterList?.parameters?.size ?: 0
                    val returnSyntax = (member as? CSharpMethodDeclaration)?.returnType ?: (member as? CSharpBasePropertyDeclaration)?.takeIf { it !is CSharpEventDeclaration }?.type
                    val void = returnSyntax is CSharpPredefinedType && returnSyntax.text == "void"
                    val returnType = if (void || returnSyntax == null || returnSyntax is CSharpRefType) null else owner.resolveType(returnSyntax)?.let { resolver.substitute(it, base) }
                    val shownParameters = parametersOf(member).mapIndexed { i, p -> parameterDisplay(p.modifiers.map { it.text }, parameters[i]) }
                    val readOnly = (member as? CSharpBasePropertyDeclaration)?.let(::readOnly) ?: false
                    val nameShown = overriding.name + (member as? CSharpMethodDeclaration)?.typeParameterList?.parameters?.joinToString(", ", "<", ">") { it.identifier?.text.orEmpty() }.orEmpty()
                    found += BaseMember(kind, parameters, refKinds, arity, modifiers, signature(typeShown, kind, nameShown, shownParameters), returnType, void, known, known, readOnly)
                }
            }
            is SemanticType.Library -> {
                val type = base.type
                if (type.nestedTypes.any { it.simpleName == overriding.name }) return null
                val typeShown = display(base) ?: return null
                for (member in type.members) {
                    val kind = when (member.kind) {
                        IndexedMemberKind.METHOD -> Kind.METHOD
                        IndexedMemberKind.PROPERTY -> Kind.PROPERTY
                        IndexedMemberKind.INDEXER -> Kind.INDEXER
                        IndexedMemberKind.EVENT -> Kind.EVENT
                        IndexedMemberKind.CONSTRUCTOR, IndexedMemberKind.OPERATOR -> continue
                        else -> Kind.OTHER
                    }
                    val name = if (kind == Kind.INDEXER) "this" else member.name
                    if (name != overriding.name) continue
                    if (kind != overriding.kind) return null
                    found += libraryMember(member, kind, base, typeShown)
                }
            }
            else -> return null
        }
        return found
    }

    private fun libraryMember(member: IndexedMember, kind: Kind, base: SemanticType.Library, typeShown: String): BaseMember {
        val modifiers = buildSet {
            if (member.isAbstract) add("abstract")
            if (member.isVirtual) add("virtual")
            if (member.isOverride) add("override")
            if (member.isSealed) add("sealed")
            if (member.isStatic) add("static")
        }
        // a `protected internal` member of another assembly is overridden as `protected` (format 4 of the index tells the two apart)
        val access = if (!member.isProtected) "public" else if (member.isProtectedInternal) "protected internal" else "protected"
        val required = if (member.isProtected) "protected" else "public"
        // the type parameters of the method as `!!index`: compared by position with the override's
        val methodArguments = List(member.arity) { i -> SemanticType.Parameter("!!$i", null, i, true) }
        val parameters = member.parameters.map { p -> resolver.fromRef(p.typeRef, base.arguments, methodArguments) }
        // `[In]` / `[Out]` of interop marks a parameter that is not by reference as well: only `ref` is sure
        val refKinds = member.parameters.map { p -> if (p.isOut || p.isIn) null else if (p.isRef) "ref" else "" }
        val shown = member.parameters.mapIndexed { i, p ->
            parameterDisplay(listOfNotNull("params".takeIf { p.isParams }), parameters[i]?.takeIf { member.arity == 0 })
        }
        val void = member.returnType == "void"
        val returnType = if (void || kind == Kind.EVENT || kind == Kind.OTHER) null else resolver.fromRef(member.typeRef, base.arguments, methodArguments)
        return BaseMember(kind, parameters, refKinds, member.arity, modifiers, signature(typeShown, kind, member.name, shown), returnType, void, access, required, !member.hasSetter)
    }

    /** `ref`, `out`, `in` or "" of a parameter written with [modifiers]; null for what is not compared here (`ref readonly`, `this`, `scoped`). */
    private fun refKind(modifiers: List<String>): String? {
        val kinds = modifiers.filter { it != "params" }
        return when (kinds.size) {
            0 -> ""
            1 -> kinds.single().takeIf { it == "ref" || it == "out" || it == "in" }
            else -> null
        }
    }

    /** What [member] declares: kind and name of each (every variable of a field or a field-like event). */
    private fun kindsAndNames(member: CSharpMemberDeclaration): List<Pair<Kind, String>> = when (member) {
        is CSharpMethodDeclaration -> if (member.explicitInterfaceSpecifier != null) emptyList() else listOfNotNull(member.identifier?.text?.let { Kind.METHOD to it })
        is CSharpPropertyDeclaration -> if (member.explicitInterfaceSpecifier != null) emptyList() else listOfNotNull(member.identifier?.text?.let { Kind.PROPERTY to it })
        is CSharpIndexerDeclaration -> if (member.explicitInterfaceSpecifier != null) emptyList() else listOf(Kind.INDEXER to "this")
        is CSharpEventDeclaration -> if (member.explicitInterfaceSpecifier != null) emptyList() else listOfNotNull(member.identifier?.text?.let { Kind.EVENT to it })
        is CSharpEventFieldDeclaration -> member.declaration?.variables.orEmpty().mapNotNull { v -> v.identifier?.text?.let { Kind.EVENT to it } }
        is CSharpBaseFieldDeclaration -> member.declaration?.variables.orEmpty().mapNotNull { v -> v.identifier?.text?.let { Kind.OTHER to it } }
        is CSharpBaseTypeDeclaration -> listOfNotNull(member.identifier?.text?.let { Kind.OTHER to it })
        is CSharpDelegateDeclaration -> listOfNotNull(member.identifier?.text?.let { Kind.OTHER to it })
        else -> emptyList()
    }

    private fun parametersOf(member: CSharpMemberDeclaration): List<CSharpParameter> = when (member) {
        is CSharpBaseMethodDeclaration -> member.parameterList?.parameters.orEmpty()
        is CSharpIndexerDeclaration -> member.parameterList?.parameters.orEmpty()
        else -> emptyList()
    }

    private enum class Same { YES, NO, UNKNOWN }

    /**
     * Whether [overriding] has the signature of [base] as Roslyn's override comparer sees it: the type parameters of the methods by
     * position, `ref` / `out` / `in` each its own, the element names of tuples and `params` ignored.
     */
    private fun compare(overriding: Overriding, base: BaseMember): Same {
        if (overriding.parameters.size != base.parameters.size || overriding.arity != base.arity) return Same.NO
        var same = true
        for ((i, p) in overriding.parameters.withIndex()) {
            val ownKind = refKind(p.modifiers.map { it.text }) ?: return Same.UNKNOWN
            val otherKind = base.refKinds[i] ?: return Same.UNKNOWN
            val own = p.type?.let(resolver::resolveType)?.let(::canonical) ?: return Same.UNKNOWN
            val other = base.parameters[i]?.let(::canonical) ?: return Same.UNKNOWN
            if (own != other || ownKind != otherKind) same = false
        }
        return if (same) Same.YES else Same.NO
    }

    /**
     * The full display of [type] as the override comparer sees it: a type parameter of a method as `!!index`, tuples without element names;
     * null when that is not sure (`dynamic`, which is `object` there; `T?` of a type parameter, which may be `T` or `Nullable<T>`).
     */
    private fun canonical(type: SemanticType): String? {
        if (mentions(type) { it is SemanticType.Library && it.type.fullName == "System.Nullable`1" && it.arguments.singleOrNull() is SemanticType.Parameter }) return null
        val plain = resolver.replace(type) { p -> if (p.ofMethod) SemanticType.Parameter("!!${p.index}", null, p.index, true) else p } ?: return null
        return CSharpTypeDisplay.display(untupled(plain))?.takeIf { "dynamic" !in it }
    }

    private fun untupled(type: SemanticType): SemanticType = when (type) {
        is SemanticType.Library -> SemanticType.Library(type.type, type.arguments.map { it?.let(::untupled) }, null)
        is SemanticType.Source -> SemanticType.Source(type.info, type.arguments.map { it?.let(::untupled) }, type.outer?.let { untupled(it) as? SemanticType.Source })
        is SemanticType.ArrayOf -> SemanticType.ArrayOf(type.element?.let(::untupled), type.rank)
        is SemanticType.Parameter -> type
    }

    private fun mentions(type: SemanticType?, test: (SemanticType) -> Boolean): Boolean = when (type) {
        null -> false
        is SemanticType.ArrayOf -> test(type) || mentions(type.element, test)
        is SemanticType.Library -> test(type) || type.arguments.any { mentions(it, test) }
        is SemanticType.Source -> test(type) || type.arguments.any { mentions(it, test) }
        is SemanticType.Parameter -> test(type)
    }

    /** `public`, `protected`, `protected internal`… of [modifiers], `private` when none is written. */
    private fun accessOf(modifiers: Collection<String>): String {
        val written = ACCESS.filter { it in modifiers }
        return if (written.isEmpty()) "private" else written.joinToString(" ")
    }

    /**
     * The return type (the type of a property) the override must have, or null when it is right or not surely wrong. C# 9 allows a derived
     * class of a class, the covariant return — not for a property with a setter ([exact]). Surely wrong: void against a value, a value type
     * or a sealed class against anything else, a class that does not derive from it, a type without type parameters against one that has
     * them (nothing converts to a type parameter). Named with the override's type parameters (`V` of `Get<V>()` for the base's `U`).
     */
    private fun returnMismatch(overriding: Overriding, base: BaseMember, exact: Boolean = false): String? {
        val syntax = overriding.returnType ?: return null
        if (syntax is CSharpRefType) return null
        val void = syntax is CSharpPredefinedType && syntax.text == "void"
        if (void && base.void) return null
        if (base.void) return "void"
        val other = base.returnType ?: return null
        val expected = display(resolver.replace(other) { p -> if (p.ofMethod) overriding.typeParameters.getOrNull(p.index)?.let { SemanticType.Parameter(it, null, p.index, true) } else p } ?: return null)
        if (void) return expected
        val own = resolver.resolveType(syntax) ?: return null
        val ownShown = canonical(own) ?: return null
        val otherShown = canonical(other) ?: return null
        // tuples: element names are CS8139, of their own
        if (ownShown == otherShown || '(' in ownShown || '(' in otherShown) return null
        // a type parameter of the class is no type parameter of the method, nor does it convert to one
        if (own is SemanticType.Parameter && !own.ofMethod && other is SemanticType.Parameter && other.ofMethod) return expected
        if (mentions(own) { it is SemanticType.Parameter }) return null
        if (mentions(other) { it is SemanticType.Parameter } || exact) return expected
        val wrong = isValueType(own) || isValueType(other) || isSealedClass(other) || isClass(other) && isClass(own) && checks.isKnown(own) && !derivesFrom(own, otherShown)
        return if (wrong) expected else null
    }

    private fun isValueType(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.kind == TypeKind.STRUCT || type.info.kind == TypeKind.RECORD_STRUCT || type.info.kind == TypeKind.ENUM
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.STRUCT || type.type.kind == IndexedTypeKind.ENUM
        else -> false
    }

    private fun isSealedClass(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.kind == TypeKind.CLASS && type.info.parts.any { "sealed" in it.modifiers }
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.CLASS && type.type.isSealed
        else -> false
    }

    private fun isClass(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> type.info.kind == TypeKind.CLASS || type.info.kind == TypeKind.RECORD
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.CLASS && type.type.fullName != "System.Object"
        else -> false
    }

    /** Whether a base class of [type] (or itself) displays as [base]. */
    private fun derivesFrom(type: SemanticType, base: String): Boolean {
        var current: SemanticType = type
        repeat(MAX_DEPTH) {
            if (canonical(current) == base) return true
            current = when (val c = current) {
                is SemanticType.Source -> resolver.baseTypes(c).firstOrNull { !NativeCSharpGenerate.isInterface(it) } ?: return false
                is SemanticType.Library -> return session.baseTypes(resolver.assemblies, c.type).any { s -> resolver.fromRef(s.reference, c.arguments)?.let(::canonical) == base }
                else -> return true
            }
        }
        return true
    }

    // ---- CS0144, CS0712

    private fun checkCreation(creation: CSharpObjectCreationExpression) {
        val syntax = creation.type ?: return
        checkCreated(resolver.resolveType(syntax) ?: return, creation)
    }

    /**
     * `IShape s = new();`: the type a target-typed `new` creates is the declared type of the variable, field or property it initializes, or
     * of what it is assigned to; anything else (arguments, returns, conditionals) is left alone.
     */
    private fun checkImplicitCreation(creation: CSharpImplicitObjectCreationExpression) {
        val parent = creation.parent
        val declared: CSharpType? = when {
            parent is CSharpEqualsValueClause -> when (val owner = parent.parent) {
                is CSharpVariableDeclarator -> (owner.parent as? CSharpVariableDeclaration)?.type
                is CSharpPropertyDeclaration -> owner.type
                else -> null
            }
            else -> null
        }
        val type = if (declared != null) {
            // `IShape? s = new();` names `IShape` as well
            if (declared is CSharpRefType || declared.text == "var") return
            resolver.resolveType(declared)
        } else {
            val assignment = parent as? CSharpAssignmentExpression ?: return
            if (assignment.right != creation || assignment.operatorToken?.text != "=") return
            val left = assignment.left ?: return
            if (left !is CSharpSimpleName && left !is CSharpMemberAccessExpression) return
            resolver.typeOf(left)
        } ?: return
        checkCreated(type, creation)
    }

    private fun checkCreated(type: SemanticType, creation: CSharpExpression) {
        val code = when (type) {
            is SemanticType.Source -> when {
                type.info.kind == TypeKind.STATIC_CLASS -> "CS0712"
                type.info.kind == TypeKind.INTERFACE -> if (type.info.parts.any { (it.element() as? CSharpMemberDeclaration)?.attributeLists?.isNotEmpty() == true }) null else "CS0144"
                (type.info.kind == TypeKind.CLASS || type.info.kind == TypeKind.RECORD) && type.info.parts.any { "abstract" in it.modifiers } -> "CS0144"
                else -> null
            }
            is SemanticType.Library -> when {
                type.type.kind == IndexedTypeKind.STATIC_CLASS || type.type.isStatic -> "CS0712"
                // a COM interface with [CoClass] is created through its class
                type.type.kind == IndexedTypeKind.INTERFACE -> if (type.type.attributes.any { it.endsWith("CoClassAttribute") }) null else "CS0144"
                type.type.kind == IndexedTypeKind.CLASS && type.type.isAbstract -> "CS0144"
                else -> null
            }
            else -> null
        } ?: return
        val shown = display(type) ?: return
        if (code == "CS0712") report(code, "Cannot create an instance of the static class '$shown'", creation.textRange)
        else report(code, "Cannot create an instance of the abstract type or interface '$shown'", creation.textRange)
    }

    // ---- CS0176

    /** `calc.Twice(1)`, `text.Empty`: a static member through a value (not `Color Color`, where the left side may be the type). */
    private fun checkStaticAccess(access: CSharpMemberAccessExpression) {
        val name = access.nameElement as? CSharpSimpleName ?: return
        val leaf = name.identifier?.takeIf { it.textLength > 0 } ?: return
        val left = access.expression ?: return
        val qualifier = resolver.qualifier(left) as? CSharpNameResolver.Qualifier.Value ?: return
        val receiver = qualifier.type
        if (receiver !is SemanticType.Source && receiver !is SemanticType.Library) return
        if (receiver is SemanticType.Source && checks.isPartial(receiver.info) || NativeCSharpGenerate.isInterface(receiver) || !checks.isKnown(receiver)) return
        val symbols = resolver.resolve(leaf)?.symbols?.takeIf { it.isNotEmpty() } ?: return
        for (symbol in symbols) {
            val member = when (symbol) {
                is CSharpSymbol.SourceMember -> symbol.element.takeIf {
                    it is CSharpMethodDeclaration && !isExtension(it) || it is CSharpPropertyDeclaration || it is CSharpVariableDeclarator || it is CSharpEventDeclaration ||
                        it is CSharpBaseFieldDeclaration
                }
                is CSharpSymbol.LibraryMember -> symbol.member.takeIf {
                    it.kind in setOf(IndexedMemberKind.METHOD, IndexedMemberKind.PROPERTY, IndexedMemberKind.FIELD, IndexedMemberKind.CONSTANT, IndexedMemberKind.EVENT)
                }
                else -> null
            } ?: return
            if (resolver.overloads.isStatic(symbol) != true) return
            if (member is CSharpVariableDeclarator && member.parent?.parent !is CSharpBaseFieldDeclaration) return
        }
        val text = leaf.text
        // a method group of static methods only leaves an extension method that takes the value as the one called; a field, property or
        // event found by the lookup is the answer, whatever extensions there are
        val methods = symbols.any { it is CSharpSymbol.SourceMember && it.element is CSharpMethodDeclaration || it is CSharpSymbol.LibraryMember && it.member.kind == IndexedMemberKind.METHOD }
        if (methods && (session.sourceExtensions(text).isNotEmpty() ||
                resolver.assemblies.membersNamed(text).any { it.kind == IndexedMemberKind.EXTENSION_METHOD && resolver.receiverFits(CSharpSymbol.LibraryMember(it), receiver) })) return
        if (!allStatic(receiver, text)) return
        val shown = staticMemberDisplay(symbols.first(), text) ?: return
        report("CS0176", "Member '$shown' cannot be accessed with an instance reference; qualify it with a type name instead", access.textRange)
    }

    private fun isExtension(method: CSharpMethodDeclaration): Boolean = method.parameterList?.parameters?.firstOrNull()?.modifiers?.any { it.text == "this" } == true

    /** Whether everything named [text] in [type] and its base classes is static. */
    private fun allStatic(type: SemanticType, text: String): Boolean {
        var current: SemanticType = type
        repeat(MAX_DEPTH) {
            when (val c = current) {
                is SemanticType.Source -> {
                    if (checks.isPartial(c.info)) return false
                    for (part in c.info.parts) {
                        val declaration = part.element() as? CSharpTypeDeclaration ?: return false
                        for (member in declaration.members) {
                            if (kindsAndNames(member).any { it.second == text } && member.modifiers.none { it.text == "static" || it.text == "const" }) return false
                        }
                        if (declaration.parameterList?.parameters?.any { it.identifier?.text == text } == true) return false
                    }
                    current = resolver.baseTypes(c).firstOrNull { !NativeCSharpGenerate.isInterface(it) } ?: return true
                }
                is SemanticType.Library -> return session.libraryMembers(resolver.assemblies, c.type)[text].orEmpty().all { it.member.isStatic || it.member.kind == IndexedMemberKind.CONSTANT }
                else -> return false
            }
        }
        return false
    }

    /** `Calc.Twice(int)`, `string.Empty`: the member as Roslyn's CS0176 names it. */
    private fun staticMemberDisplay(symbol: CSharpSymbol, text: String): String? = when (symbol) {
        is CSharpSymbol.SourceMember -> {
            val element = symbol.element
            val owner = PsiTreeUtil.getParentOfType(element, CSharpTypeDeclaration::class.java) ?: return null
            when (element) {
                is CSharpMethodDeclaration -> memberDisplay(element)
                is CSharpPropertyDeclaration, is CSharpEventDeclaration, is CSharpVariableDeclarator, is CSharpBaseFieldDeclaration -> typeDisplay(owner)?.let { "$it.$text" }
                else -> null
            }
        }
        is CSharpSymbol.LibraryMember -> {
            val m = symbol.member
            if (m.type.arity > 0 || m.arity > 0) null
            else {
                val type = display(SemanticType.Library(m.type, emptyList()))
                if (m.kind != IndexedMemberKind.METHOD) type?.let { "$it.${m.name}" }
                else {
                    val parameters = m.parameters.map { p -> parameterDisplay(listOfNotNull("params".takeIf { p.isParams }, "ref".takeIf { p.isRef }, "out".takeIf { p.isOut }),
                        resolver.fromRef(p.typeRef, emptyList())) ?: return null }
                    type?.let { "$it.${m.name}(${parameters.joinToString(", ")})" }
                }
            }
        }
        else -> null
    }

    // ---- CS0236

    /**
     * A simple name in the initializer of a field, a property or a field-like event (static ones too, as Roslyn) that stands for an instance
     * field, property, method or event of the type or its base classes of the solution: CS0236 (not CS0120).
     */
    private fun checkInitializer(name: CSharpSimpleName) {
        if (!NativeCSharpScopes.isFreeName(name) || NativeCSharpTypePositions.isType(name)) return
        val parent = name.parent
        if (parent is CSharpMemberAccessExpression && parent.nameElement == name) return
        val leaf = name.identifier ?: return
        if (resolver.syntax.symbolAt(leaf) != null) return
        val member = initializerOf(name) ?: return
        val owner = member.parent as? CSharpTypeDeclaration ?: return
        val info = resolver.syntax.declaredType(owner) ?: return
        val symbols = resolver.resolve(leaf)?.symbols?.takeIf { it.isNotEmpty() } ?: return
        if (symbols.all { it is CSharpSymbol.LibraryMember }) return checkLibraryInitializer(leaf, info, symbols.map { (it as CSharpSymbol.LibraryMember) })
        if (symbols.any { it !is CSharpSymbol.SourceMember || resolver.overloads.isStatic(it) != false }) return
        val elements = symbols.map { (it as CSharpSymbol.SourceMember).element }
        for (element in elements) {
            if (element !is CSharpMethodDeclaration && element !is CSharpPropertyDeclaration && element !is CSharpVariableDeclarator && element !is CSharpEventDeclaration &&
                element !is CSharpBaseFieldDeclaration) return
            if (element is CSharpVariableDeclarator && element.parent?.parent !is CSharpBaseFieldDeclaration) return
            // `Color Color`: a member named as its type may be the type
            val type = when (element) {
                is CSharpPropertyDeclaration -> element.type
                is CSharpEventDeclaration -> element.type
                is CSharpVariableDeclarator -> (element.parent as? CSharpVariableDeclaration)?.type
                is CSharpBaseFieldDeclaration -> element.declaration?.type
                else -> null
            }
            if (type != null && type.text.substringBefore('<').substringAfterLast('.').trim() == leaf.text) return
        }
        if (elements.any { it is CSharpMethodDeclaration }) {
            // a static overload anywhere may be the one called
            if (checks.isPartial(info) || !allInstance(resolver.selfType(info), leaf.text)) return
        }
        val first = elements.first()
        val declaring = PsiTreeUtil.getParentOfType(first, CSharpTypeDeclaration::class.java) ?: return
        val shown = when (first) {
            is CSharpMethodDeclaration -> memberDisplay(first)
            else -> typeDisplay(declaring)?.let { "$it.${leaf.text}" }
        } ?: return
        report("CS0236", "A field initializer cannot reference the non-static field, method, or property '$shown'", leaf.textRange)
    }

    /**
     * An instance member of a base class of an assembly (`Count` of `List<int>`, `Message` of `Exception`): named with the base as the type
     * gives its arguments (`List<int>.Count`, `List<int>.ToArray()`); a method only when it is the one of its name, none of them static.
     */
    private fun checkLibraryInitializer(leaf: PsiElement, info: io.github.dotnetsupport.lang.TypeInfo, symbols: List<CSharpSymbol.LibraryMember>) {
        val first = symbols.first()
        val m = first.member
        if (symbols.any { it.member.isStatic || it.member.kind !in INSTANCE_KINDS }) return
        if (checks.isPartial(info) || !checks.isKnown(resolver.selfType(info)) || !allInstance(resolver.selfType(info), leaf.text)) return
        // `Color Color`: a member named as its type may be the type
        if (m.kind != IndexedMemberKind.METHOD && m.returnType.substringBefore('<').substringAfterLast('.') == leaf.text) return
        val arguments = first.declaringArguments
        if (m.type.arity != arguments.size || arguments.any { it == null || mentions(it) { t -> t is SemanticType.Parameter } }) return
        val owner = display(SemanticType.Library(m.type, arguments)) ?: return
        val shown = if (m.kind != IndexedMemberKind.METHOD) "$owner.${m.name}" else {
            if (symbols.size != 1 || m.arity > 0) return
            val parameters = m.parameters.map { p ->
                if (p.isOptional || p.hasDefault || p.isOut || p.isIn) return
                parameterDisplay(listOfNotNull("params".takeIf { p.isParams }, "ref".takeIf { p.isRef }), resolver.fromRef(p.typeRef, arguments)) ?: return
            }
            "$owner.${m.name}(${parameters.joinToString(", ")})"
        }
        report("CS0236", "A field initializer cannot reference the non-static field, method, or property '$shown'", leaf.textRange)
    }

    /** Whether no method named [text] of [type] and its base classes of the solution is static (and the bases are all of the solution). */
    private fun allInstance(type: SemanticType.Source, text: String): Boolean {
        var current: SemanticType = type
        repeat(MAX_DEPTH) {
            when (val c = current) {
                is SemanticType.Source -> {
                    if (checks.isPartial(c.info)) return false
                    for (part in c.info.parts) {
                        val declaration = part.element() as? CSharpTypeDeclaration ?: return false
                        if (declaration.members.any { m -> m is CSharpMethodDeclaration && m.identifier?.text == text && m.modifiers.any { it.text == "static" } }) return false
                    }
                    current = resolver.baseTypes(c).firstOrNull { !NativeCSharpGenerate.isInterface(it) } ?: return true
                }
                is SemanticType.Library -> return session.libraryMembers(resolver.assemblies, c.type)[text].orEmpty().none { it.member.isStatic }
                else -> return false
            }
        }
        return false
    }

    // ---- display

    /** `Shape`, `Box<T>`, `Outer.Inner`: a type declaration as Roslyn's messages name it; null nested in a generic type. */
    private fun typeDisplay(declaration: CSharpTypeDeclaration): String? = resolver.syntax.declaredType(declaration)?.let { display(resolver.selfType(it)) }

    private fun display(type: SemanticType): String? = CSharpTypeDisplay.display(type, qualified = false)

    private fun parameterDisplay(modifiers: List<String>, type: SemanticType?): String? = display(type ?: return null)?.let { (modifiers + it).joinToString(" ") }

    private fun signature(type: String, kind: Kind, name: String, parameters: List<String?>): String? {
        if (parameters.any { it == null }) return null
        return when (kind) {
            Kind.METHOD -> "$type.$name(${parameters.joinToString(", ")})"
            Kind.INDEXER -> "$type.this[${parameters.joinToString(", ")}]"
            else -> "$type.$name"
        }
    }

    /** `Shape.Area()`, `Box<T>.Put<U>(T, U)`, `Shape.this[int]`, `Shape.Name`. */
    private fun memberDisplay(member: CSharpMemberDeclaration): String? {
        val owner = member.parent as? CSharpTypeDeclaration ?: return null
        val type = typeDisplay(owner) ?: return null
        val parameters = parametersOf(member).map { p -> parameterDisplay(p.modifiers.map { it.text }, p.type?.let(resolver::resolveType) ?: return null) }
        return when (member) {
            is CSharpMethodDeclaration -> {
                val typeParameters = member.typeParameterList?.parameters?.joinToString(", ", "<", ">") { it.text.trim() }.orEmpty()
                signature(type, Kind.METHOD, (member.identifier?.text ?: return null) + typeParameters, parameters)
            }
            is CSharpIndexerDeclaration -> signature(type, Kind.INDEXER, "this", parameters)
            is CSharpPropertyDeclaration -> "$type.${member.identifier?.text ?: return null}"
            is CSharpEventDeclaration -> "$type.${member.identifier?.text ?: return null}"
            is CSharpEventFieldDeclaration -> "$type.${member.declaration?.variables?.singleOrNull()?.identifier?.text ?: return null}"
            else -> null
        }
    }

    companion object {
        private const val MAX_DEPTH = 32
        private val ACCESS = listOf("private", "protected", "internal", "public", "file")
        private val INSTANCE_KINDS = setOf(IndexedMemberKind.METHOD, IndexedMemberKind.PROPERTY, IndexedMemberKind.FIELD, IndexedMemberKind.EVENT)
        /** What the compiler writes in a record (and so may be overridden in a derived one with nothing in the sources to show it). */
        private val RECORD_MEMBERS = setOf("EqualityContract", "PrintMembers", "Equals", "GetHashCode", "ToString", "Deconstruct", "<Clone>$")

        /** The field, property or field-like event whose initializer [element] is in; null anywhere else. */
        fun initializerOf(element: PsiElement): CSharpMemberDeclaration? {
            var at: PsiElement? = element.parent
            var child: PsiElement = element
            while (at != null && at !is CSharpFile) {
                when (at) {
                    is CSharpAttributeList, is CSharpBaseTypeDeclaration -> return null
                    is CSharpPropertyDeclaration -> return at.takeIf { child == at.initializer }
                    // the type of a field is a type position (no simple name of an expression); the rest is its declarators' initializers
                    is CSharpBaseFieldDeclaration -> return at.takeIf { child == at.declaration }
                    is CSharpMemberDeclaration -> return null
                }
                child = at
                at = at.parent
            }
            return null
        }
    }
}
