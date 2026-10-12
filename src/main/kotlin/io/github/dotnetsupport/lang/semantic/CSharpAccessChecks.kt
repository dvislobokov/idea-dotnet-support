package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedAccess
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.NativeCSharpResolver
import io.github.dotnetsupport.lang.NativeCSharpScopes
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypeKind
import io.github.dotnetsupport.msbuild.CompilationModel

/**
 * Who may touch a member and how (the access group of the native errors): CS0122 (a member, a constructor or a type out of its reach),
 * CS0200 (a property or indexer without a setter assigned), CS0191 / CS0198 (a readonly field assigned outside a constructor of its type),
 * CS0192 / CS0199 / CS0206 (a readonly field, a property or an indexer passed by `ref` / `out`), CS8852 (an init-only property assigned
 * outside an initializer, a constructor or an `init` accessor), CS0272 / CS0271 (an accessor more private than the place), CS0154 (a
 * property without a getter read), CS0131 (a value on the left of an assignment: a literal, an operation, a call, a constant).
 *
 * What the compiler imports from another assembly decides between them, as `dotnet build` shows: an internal member of another project or
 * library that does not name this one in its `InternalsVisibleTo` is not there at all (CS1061 / CS0117 / CS0103 for it, CS0200 / CS0154
 * for an internal accessor), while its internal and private types, its protected and private protected members are, and are inaccessible
 * (CS0122, CS0272). The projects of the solution are compiled against their reference assemblies, which keep private protected members
 * only when the project has a friend: that one needs `ProduceReferenceAssembly` evaluated, or the error is not said.
 *
 * Precision first, as [CSharpSemanticChecks]: only resolved members whose facts are all written in their declaration or the index; not a
 * member Roslyn's lookup would skip for an accessible one of the same name (a base, an outer type, an extension method), `override` and
 * `partial` properties (the other part or the base has the accessor), field initializers and constructor initializers, overloads and
 * constructors whose arguments do not surely fit or not.
 */
internal class CSharpAccessChecks(private val resolver: CSharpNameResolver, private val checks: CSharpSemanticChecks, private val sink: (CSharpSemanticProblem) -> Unit) {
    private val file: CSharpFile = resolver.file
    private val session = resolver.session
    private val assemblies get() = resolver.assemblies
    private val derived = HashMap<Pair<String, String>, Boolean?>()
    private val reaches = HashMap<String, Reach>()

    fun check(element: PsiElement) {
        when (element) {
            is CSharpSimpleName -> { checkAccess(element); checkRead(element) }
            is CSharpAssignmentExpression -> checkAssignment(element)
            is CSharpPrefixUnaryExpression -> if (isStep(element.operatorToken)) element.operand?.let { checkWrite(it, Receiver.NONE, assignment = false) }
            is CSharpPostfixUnaryExpression -> if (isStep(element.operatorToken)) element.operand?.let { checkWrite(it, Receiver.NONE, assignment = false) }
            is CSharpObjectCreationExpression -> checkConstructor(element)
            is CSharpArgument -> checkRefArgument(element)
        }
    }

    private fun isStep(token: PsiElement?): Boolean = token?.text == "++" || token?.text == "--"

    // ---- CS0122 and the members the compiler does not import

    private fun checkAccess(name: CSharpSimpleName) {
        val leaf = name.identifier ?: return
        if (resolver.syntax.symbolAt(leaf) != null || inDeclarationHeader(name)) return
        val parent = name.parent
        val free = NativeCSharpScopes.isFreeName(name)
        val qualified = parent is CSharpMemberAccessExpression && parent.nameElement == name || parent is CSharpQualifiedName && parent.right == name &&
            PsiTreeUtil.getParentOfType(name, CSharpUsingDirective::class.java) == null
        val initialized = parent is CSharpAssignmentExpression && parent.left == name && NativeCSharpScopes.isObjectInitializer(parent.parent)
        if (!free && !qualified && !initialized) return
        val resolution = resolver.resolve(leaf)
        if (resolution == null) {
            if (!initialized) checkHiddenType(name, leaf, free) else checkInitialized(name, leaf)
            return
        }
        val symbols = resolution.symbols
        when {
            symbols.all { it is CSharpSymbol.SourceMember } -> {
                val first = symbols.first() as CSharpSymbol.SourceMember
                val declaring = declaringType(first.element) ?: return
                if (symbols.any { declaringType((it as CSharpSymbol.SourceMember).element)?.key != declaring.key }) return
                sourceMember(first, declaring, name, leaf, free, initialized)
            }
            symbols.all { it is CSharpSymbol.LibraryMember } -> libraryMember(symbols.map { it as CSharpSymbol.LibraryMember }, name, leaf, free)
            symbols.size > 1 -> {}
            else -> when (val symbol = symbols.single()) {
                is CSharpSymbol.SourceType -> if (!initialized) sourceType(symbol, name, leaf, free)
                is CSharpSymbol.LibraryType -> if (!free && !initialized) libraryType(symbol.type, name, leaf)
                else -> {}
            }
        }
    }

    /**
     * A member of the solution: CS0122 when none of the members of its name in its type is reachable (Roslyn names the first declared of
     * them), the error of a name that is not there when none is imported (an internal member of another project).
     */
    private fun sourceMember(symbol: CSharpSymbol.SourceMember, declaring: TypeInfo, name: CSharpSimpleName, leaf: PsiElement, free: Boolean, initialized: Boolean) {
        val text = leaf.text
        val targets = symbol.member.targets().map { (it.parent as? CSharpParameter)?.takeIf { p -> p.identifier == it } ?: it }
        if (targets.isEmpty() || targets.any { declaringType(it)?.key != declaring.key }) return
        val accesses = if (targets.size == 1) listOf(sourceAccess(symbol, declaring, name)) else targets.map { targetAccess(it, declaring, name) }
        when {
            accesses.all { it == Access.NO } -> {
                if (!alone(declaring, text, name, free)) return
                if (targets.any { it is CSharpMethodDeclaration } && extensionsNamed(text)) return
                val first = targets.minByOrNull { it.textOffset } ?: return
                val shown = memberDisplay(first, symbol.owner, declaring) ?: return
                report("CS0122", "'$shown' is inaccessible due to its protection level", leaf.textRange)
            }
            accesses.all { it == Access.MISSING } -> {
                if (!alone(declaring, text, name, free)) return
                reportMissing(name, leaf, free, initialized)
            }
        }
    }

    /**
     * `new T { X = 1 }` where `T` has nothing named `X` the code may see (an internal member of another assembly is not imported): CS0117.
     * Only for a type whose members are all known.
     */
    private fun checkInitialized(name: CSharpSimpleName, leaf: PsiElement) {
        val creation = name.parent?.parent?.parent as? CSharpBaseObjectCreationExpression ?: return
        val type = resolver.typeOf(creation) ?: return
        if (type !is SemanticType.Library && type !is SemanticType.Source || !checks.isKnown(type) || checks.generated) return
        if (type is SemanticType.Source && isPartial(type.info)) return
        if (type is SemanticType.Library && !checks.checkable(type)) return
        val text = leaf.text
        if (checks.has(type, text)) return
        val shown = shortDisplay(type) ?: return
        report("CS0117", "'$shown' does not contain a definition for '$text'", leaf.textRange)
    }

    /** `a.X`, `T.X`, `X`, `new T { X = 1 }` of a member the compiler did not import: what Roslyn says of a name that is not there. */
    private fun reportMissing(name: CSharpSimpleName, leaf: PsiElement, free: Boolean, initialized: Boolean) {
        val text = leaf.text
        val parent = name.parent
        when {
            initialized -> {
                val creation = (parent as CSharpAssignmentExpression).parent?.parent as? CSharpExpression ?: return
                val shown = resolver.typeOf(creation)?.let(::shortDisplay) ?: return
                report("CS0117", "'$shown' does not contain a definition for '$text'", leaf.textRange)
            }
            free -> report("CS0103", "The name '$text' does not exist in the current context", leaf.textRange)
            parent is CSharpMemberAccessExpression -> when (val qualifier = parent.expression?.let(resolver::qualifier)) {
                is CSharpNameResolver.Qualifier.Type -> {
                    // a static extension member of that name (C# 14) may be what is meant
                    if (extensionsNamed(text)) return
                    val shown = shortDisplay(qualifier.type) ?: return
                    report("CS0117", "'$shown' does not contain a definition for '$text'", leaf.textRange)
                }
                is CSharpNameResolver.Qualifier.Value -> {
                    // an extension method for the receiver is taken instead (LINQ's `Count` is not one for a type that is not a sequence)
                    if (resolver.extensionMethodsFor(qualifier.type, name, { it == text }).isNotEmpty()) return
                    val shown = shortDisplay(qualifier.type) ?: return
                    sink(CSharpSemanticProblem("CS1061", "'$shown' does not contain a definition for '$text' and no accessible extension method '$text' accepting a first argument " +
                        "of type '$shown' could be found (are you missing a using directive or an assembly reference?)", leaf.textRange, name = text))
                }
                else -> {}
            }
        }
    }

    /** A type of the solution: an internal one of another project that does not make this one its friend, a nested one out of reach. */
    private fun sourceType(symbol: CSharpSymbol.SourceType, name: CSharpSimpleName, leaf: PsiElement, free: Boolean) {
        val info = symbol.info
        val part = info.parts.singleOrNull() ?: return
        if (info.arity > 0) return
        val element = part.element() ?: return
        val outerDeclaration = element.parent as? CSharpBaseTypeDeclaration
        val text = leaf.text
        if (outerDeclaration == null) {
            if (part.namespace == null || typeAccess(part.modifiers, element) != Access.NO) return
            // Roslyn takes an accessible type of the name from another namespace on the way out instead
            if (free && !onlyType(name, text, info)) return
        } else {
            if (free) return
            val outer = resolver.syntax.declaredType(outerDeclaration) ?: return
            if (access(part.modifiers, outer, outer.kind == TypeKind.INTERFACE, element, name, type = true) != Access.NO) return
            if (!alone(outer, text, name, false)) return
        }
        val shown = shortDisplay(resolver.selfType(info)) ?: return
        report("CS0122", "'$shown' is inaccessible due to its protection level", leaf.textRange)
    }

    /** A top-level type: public, or internal (written or by default) — of this project or of one whose friend this one is. */
    private fun typeAccess(modifiers: Collection<String>, declaration: PsiElement): Access = when {
        "public" in modifiers -> Access.YES
        "file" in modifiers -> Access.UNSURE
        else -> when (reach(declaration)) {
            Reach.SAME, Reach.FRIEND -> Access.YES
            Reach.OTHER -> Access.NO
            Reach.UNKNOWN -> Access.UNSURE
        }
    }

    /** Whether [info] is the only type named [text] in the namespaces [at] sees, of the solution and of the assemblies. */
    private fun onlyType(at: PsiElement, text: String, info: TypeInfo): Boolean {
        if (!resolver.importsKnown(at) || resolver.aliasNamed(at, text)) return false
        val found = resolver.visibleNamespaces(at).flatMap { resolver.typesIn(it, text, 0) }.distinct()
        return found.size == 1 && (found.single() as? CSharpSymbol.SourceType)?.info?.key == info.key
    }

    /** Members of an assembly: CS0122 when none of the name is reachable here (the first of them in the metadata named). */
    private fun libraryMember(symbols: List<CSharpSymbol.LibraryMember>, name: CSharpSimpleName, leaf: PsiElement, free: Boolean) {
        val text = leaf.text
        val type = symbols.first().member.type
        if (symbols.any { it.member.type != type }) return
        val all = session.libraryMembers(assemblies, type)[text].orEmpty()
        if (all.isEmpty() || all.any { it.member.type != type || libraryAccess(it.member, name) != Access.NO }) return
        if (all.any { it.member.kind.isCallable } && extensionsNamed(text)) return
        if (free && !freeAlone(name, text)) return
        val first = all.minBy { it.member.row }.member
        val shown = libraryDisplay(CSharpSymbol.LibraryMember(first, symbols.first().declaringArguments)) ?: return
        report("CS0122", "'$shown' is inaccessible due to its protection level", leaf.textRange)
    }

    /** A free name of an inherited member of an assembly: no other type around, `using static` type or type of that name for the lookup to take. */
    private fun freeAlone(at: PsiElement, text: String): Boolean {
        if (resolver.syntax.enclosingTypes(at).count { checks.has(resolver.selfType(it), text) } != 1) return false
        if (resolver.staticTypes(at).any { checks.has(it, text) }) return false
        return (0..MAX_ARITY).all { resolver.typeOrNamespace(at, text, it).isEmpty() }
    }

    /** `Outer.Nested` of an assembly, nested protected: seen in the types derived from the one around it only. */
    private fun libraryType(type: IndexedType, name: CSharpSimpleName, leaf: PsiElement) {
        if (!type.isProtected || assemblies.grants(type.index)) return
        val outer = type.declaringType ?: return
        if (protectedLibrary(outer, name) != Access.NO) return
        val shown = hiddenDisplay(type) ?: return
        report("CS0122", "'$shown' is inaccessible due to its protection level", leaf.textRange)
    }

    /** A name nothing resolves: an internal or private type of an assembly there is CS0122 (and not CS0246, CS0103, CS0234: see [hidesType]). */
    private fun checkHiddenType(name: CSharpSimpleName, leaf: PsiElement, free: Boolean) {
        val text = leaf.text
        val arity = NativeCSharpResolver.arity(name)
        val parent = name.parent
        val type = when {
            free -> if (freeTypeAbsent(name, text)) resolver.visibleNamespaces(name).firstNotNullOfOrNull { hiddenTypeIn(it, text, arity) } else null
            parent is CSharpQualifiedName || parent is CSharpMemberAccessExpression -> {
                val left = (parent as? CSharpQualifiedName)?.left ?: (parent as? CSharpMemberAccessExpression)?.expression ?: return
                when (val qualifier = resolver.qualifier(left)) {
                    is CSharpNameResolver.Qualifier.Namespace -> hiddenTypeIn(qualifier.name, text, arity)
                    is CSharpNameResolver.Qualifier.Type -> (qualifier.type as? SemanticType.Library)?.type?.let { hiddenNested(it, text, arity) }
                    else -> null
                }
            }
            else -> null
        } ?: return
        if (checks.generated) return
        val shown = hiddenDisplay(type) ?: return
        report("CS0122", "'$shown' is inaccessible due to its protection level", leaf.textRange)
    }

    /** As [CSharpSemanticChecks]' own test before CS0246 / CS0103: nothing of the name around [at] that is not known. */
    private fun freeTypeAbsent(at: CSharpSimpleName, text: String): Boolean {
        if (!resolver.importsKnown(at) || resolver.aliasNamed(at, text)) return false
        for (info in resolver.syntax.enclosingTypes(at)) {
            val self = resolver.selfType(info)
            if (checks.isPartial(info) || !checks.isKnown(self) || checks.has(self, text)) return false
        }
        for (static in resolver.staticTypes(at)) if (!checks.isKnown(static) || checks.has(static, text)) return false
        return (0..MAX_ARITY).all { resolver.typeOrNamespace(at, text, it).isEmpty() }
    }

    /** Whether [text] at [at] is a type of an assembly the code cannot reach (internal, private nested): CS0122 is said here, not CS0246 / CS0103 there. */
    internal fun hidesType(at: PsiElement, text: String): Boolean = resolver.visibleNamespaces(at).any { hidesTypeIn(it, text) }

    /** Whether the namespace [namespace] has a type [text] of an assembly the code cannot reach: CS0122 is said here, not CS0234 there. */
    internal fun hidesTypeIn(namespace: String, text: String): Boolean = (0..MAX_ARITY).any { hiddenTypeIn(namespace, text, it) != null }

    private fun hiddenTypeIn(namespace: String, text: String, arity: Int): IndexedType? =
        assemblies.findInaccessibleType(namespace, if (arity == 0) text else "$text`$arity")

    private fun hiddenNested(type: IndexedType, text: String, arity: Int): IndexedType? =
        (listOf(type) + session.baseTypes(assemblies, type).map { it.type }).firstNotNullOfOrNull { owner ->
            owner.hiddenNestedTypes.firstOrNull { it.simpleName == text && it.ownArity == arity && !assemblies.isAccessible(it) }
        }

    /** `InternalType`, `Outer.Hidden`, `Box<T>`: an inaccessible type of an assembly as Roslyn's messages name it. */
    private fun hiddenDisplay(type: IndexedType): String? =
        CSharpTypeDisplay.display(SemanticType.Library(type, type.typeParameters.mapIndexed { i, p -> SemanticType.Parameter(p.name, null, i, false) }), qualified = false)

    /** A name in a base list, an attribute or a constraint of a type: the type itself is not around it for C#'s access rules as the resolver sees them. */
    private fun inDeclarationHeader(name: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(name, CSharpBaseList::class.java, CSharpAttributeList::class.java, CSharpTypeParameterConstraintClause::class.java) != null

    /**
     * Roslyn's lookup skips what it cannot reach: [text] must be found nowhere else it would look — the bases of [declaring], for a simple
     * name the other types around it, the `using static` types and the types and namespaces of that name.
     */
    private fun alone(declaring: TypeInfo, text: String, at: PsiElement, free: Boolean): Boolean {
        val self = resolver.selfType(declaring)
        if (!checks.isKnown(self) || isPartial(declaring)) return false
        if (resolver.baseTypes(self).any { checks.has(it, text) }) return false
        if (!free) return true
        if (resolver.syntax.enclosingTypes(at).count { checks.has(resolver.selfType(it), text) } != 1) return false
        if (resolver.staticTypes(at).any { checks.has(it, text) }) return false
        return (0..MAX_ARITY).all { resolver.typeOrNamespace(at, text, it).isEmpty() }
    }

    private fun extensionsNamed(text: String): Boolean =
        session.sourceExtensions(text).isNotEmpty() || assemblies.membersNamed(text).any { it.kind == IndexedMemberKind.EXTENSION_METHOD }

    // ---- accessibility, C# §7.5, and what another assembly imports

    /** [MISSING]: the compiler does not import it from the other assembly (an internal member without InternalsVisibleTo). */
    private enum class Access { YES, NO, UNSURE, MISSING }

    /** Where a declaration is from, for the code of [file]: this project, a project that makes it a friend, another, or not known. */
    private enum class Reach { SAME, FRIEND, OTHER, UNKNOWN }

    private fun sourceAccess(symbol: CSharpSymbol.SourceMember, declaring: TypeInfo, site: PsiElement): Access {
        val element = symbol.target
        // a positional parameter of a record is a public property
        if (element is CSharpParameter) return Access.YES
        val modifiers = symbol.member.modifiers.takeIf { symbol.member.targets().size == 1 } ?: modifiersOf(element) ?: return Access.UNSURE
        return access(modifiers, declaring, defaultPublic(declaring), element, site)
    }

    private fun targetAccess(element: PsiElement, declaring: TypeInfo, site: PsiElement): Access {
        if (element is CSharpParameter) return Access.YES
        return access(modifiersOf(element) ?: return Access.UNSURE, declaring, defaultPublic(declaring), element, site)
    }

    private fun defaultPublic(declaring: TypeInfo): Boolean = declaring.kind == TypeKind.INTERFACE || declaring.kind == TypeKind.ENUM

    private fun modifiersOf(element: PsiElement): Collection<String>? =
        ((element as? CSharpMemberDeclaration) ?: PsiTreeUtil.getParentOfType(element, CSharpBaseFieldDeclaration::class.java))?.modifiers?.map { it.text }

    /**
     * Whether [site] reaches what is declared with [modifiers] in [declaring] (in the file of [declaration]); [type]: a nested type, which
     * another assembly always imports (an internal member it does not, [Access.MISSING]); [constructor]: called by `new`, where protected
     * is the type's own business (a derived type calls it through `base(...)` only).
     */
    private fun access(modifiers: Collection<String>, declaring: TypeInfo, defaultPublic: Boolean, declaration: PsiElement?, site: PsiElement, type: Boolean = false,
                       constructor: Boolean = false): Access {
        val isPrivate = "private" in modifiers
        val isProtected = "protected" in modifiers
        val isInternal = "internal" in modifiers
        fun protectedHere(): Access = if (constructor) within(declaring, site) else protectedAccess(declaring, site, Access.NO)
        when {
            "public" in modifiers -> return Access.YES
            isInternal && isProtected -> return when (reach(declaration)) {
                Reach.SAME, Reach.FRIEND -> Access.YES
                Reach.OTHER -> protectedHere()
                Reach.UNKNOWN -> if (protectedHere() == Access.YES) Access.YES else Access.UNSURE
            }
            isInternal -> return when (reach(declaration)) {
                Reach.SAME, Reach.FRIEND -> Access.YES
                Reach.OTHER -> if (type) Access.NO else Access.MISSING
                Reach.UNKNOWN -> Access.UNSURE
            }
            isPrivate && isProtected -> return when (reach(declaration)) {
                Reach.SAME -> protectedHere()
                // a friend outside of the derived types: `dotnet build` may say CS0281 of the public key instead
                Reach.FRIEND -> if (protectedHere() == Access.YES) Access.YES else Access.UNSURE
                Reach.OTHER -> if (type) Access.NO else privateProtectedElsewhere(declaration)
                Reach.UNKNOWN -> Access.UNSURE
            }
            isProtected -> return if (declaring.kind == TypeKind.INTERFACE) Access.UNSURE else protectedHere()
            !isPrivate && defaultPublic -> return Access.YES
            "file" in modifiers -> return Access.UNSURE
        }
        return within(declaring, site)
    }

    private fun within(declaring: TypeInfo, site: PsiElement): Access =
        if (resolver.syntax.enclosingTypes(site).any { it.key == declaring.key }) Access.YES else Access.NO

    /**
     * A private protected member of another project that is not a friend: its reference assembly keeps it when the project has some friend
     * (then CS0122), drops it when it has none ([Access.MISSING]); a project without a reference assembly is compiled against as is (CS0122).
     */
    private fun privateProtectedElsewhere(declaration: PsiElement?): Access {
        val other = declaration?.containingFile ?: return Access.UNSURE
        val projectFile = CSharpSemanticEnvironment.projectOf(other) ?: return Access.UNSURE
        val friends = CSharpFriendAssemblies.friendsOf(file.project, projectFile) ?: return Access.UNSURE
        if (friends.isNotEmpty()) return Access.NO
        return when (CSharpFriendAssemblies.producesReferenceAssembly(file.project, projectFile)) {
            true -> Access.MISSING
            false -> Access.NO
            null -> Access.UNSURE
        }
    }

    /** Within [declaring] or a type derived from it ([otherwise] elsewhere); a receiver of the wrong type there is CS1540, not this group's. */
    private fun protectedAccess(declaring: TypeInfo, site: PsiElement, otherwise: Access): Access {
        for (info in resolver.syntax.enclosingTypes(site)) {
            if (info.key == declaring.key) return Access.YES
            when (derives(info, declaring.key)) {
                true -> return Access.YES
                null -> return Access.UNSURE
                false -> {}
            }
        }
        return otherwise
    }

    private fun derives(info: TypeInfo, base: String): Boolean? = derived.getOrPut(info.key to base) {
        val self = resolver.selfType(info)
        if (!checks.isKnown(self)) return@getOrPut null
        var found = false
        val seen = HashSet<String>()
        fun walk(type: SemanticType.Source, depth: Int) {
            if (found || depth > 16 || !seen.add(type.info.key)) return
            for (b in resolver.baseTypes(type)) if (b is SemanticType.Source) {
                if (b.info.key == base) found = true else walk(b, depth + 1)
            }
        }
        walk(self, 0)
        found
    }

    /** Whether [declaration] is of this project, of one whose `InternalsVisibleTo` names this one, of another; kept per project for the run. */
    private fun reach(declaration: PsiElement?): Reach {
        val other = declaration?.containingFile ?: return Reach.UNKNOWN
        if (other == file || other.originalFile == file.originalFile) return Reach.SAME
        val here = CSharpSemanticEnvironment.projectOf(file) ?: return Reach.UNKNOWN
        val there = CSharpSemanticEnvironment.projectOf(other) ?: return Reach.UNKNOWN
        if (here == there) return Reach.SAME
        return reaches.getOrPut(there.path) {
            val friends = CSharpFriendAssemblies.friendsOf(file.project, there) ?: return@getOrPut Reach.UNKNOWN
            if (CSharpFriendAssemblies.isFriend(file.project, here, friends)) Reach.FRIEND else Reach.OTHER
        }
    }

    /** A member of an assembly: protected ones in the derived types, internal ones for a friend, private protected ones for a derived friend. */
    private fun libraryAccess(member: IndexedMember, site: PsiElement): Access {
        if (!assemblies.isAccessible(member.type)) return Access.UNSURE
        val granted = assemblies.grants(member.type.index)
        return when {
            // a friend outside of the derived types: `dotnet build` says CS0281 of the public key, not CS0122
            member.isPrivateProtected -> if (!granted) Access.NO else if (protectedLibrary(member.type, site) == Access.YES) Access.YES else Access.UNSURE
            member.isProtected && member.isInternal -> if (granted) Access.YES else protectedLibrary(member.type, site)
            member.isProtected -> protectedLibrary(member.type, site)
            member.isInternal -> if (granted) Access.YES else Access.MISSING
            else -> Access.YES
        }
    }

    /** An accessor of a property of an assembly, by its own accessibility; [IndexedAccess.NONE] (a private one) is not imported. */
    private fun accessorAccess(access: IndexedAccess, member: IndexedMember, site: PsiElement): Access {
        val granted = assemblies.grants(member.type.index)
        return when (access) {
            IndexedAccess.PUBLIC -> Access.YES
            IndexedAccess.PROTECTED -> protectedLibrary(member.type, site)
            IndexedAccess.PROTECTED_INTERNAL -> if (granted) Access.YES else protectedLibrary(member.type, site)
            IndexedAccess.INTERNAL -> if (granted) Access.YES else Access.MISSING
            IndexedAccess.PRIVATE_PROTECTED -> if (granted) protectedLibrary(member.type, site) else Access.NO
            IndexedAccess.NONE -> Access.MISSING
        }
    }

    /** A protected member of an assembly: seen in the types derived from [declaring]. */
    private fun protectedLibrary(declaring: IndexedType, site: PsiElement): Access {
        for (info in resolver.syntax.enclosingTypes(site)) {
            val self = resolver.selfType(info)
            if (!checks.isKnown(self)) return Access.UNSURE
            for (base in resolver.libraryBases(self)) {
                val type = (base as? SemanticType.Library)?.type ?: continue
                if (type == declaring || session.baseTypes(assemblies, type).any { it.type == declaring }) return Access.YES
            }
        }
        return Access.NO
    }

    private fun declaringType(element: PsiElement): TypeInfo? =
        PsiTreeUtil.getParentOfType(element, CSharpBaseTypeDeclaration::class.java)?.let(resolver.syntax::declaredType)

    private fun isPartial(info: TypeInfo): Boolean = info.parts.size > 1 || info.parts.any { "partial" in it.modifiers }

    // ---- constructors

    /**
     * `new T(...)`: Roslyn chooses among all the constructors and says CS0122 when the one that fits is out of reach and none in reach fits
     * (`new Ov("s")` with a private `Ov(string)` and a public `Ov(double)`). Only when every constructor surely fits or surely not; none
     * that fits is CS1729 / CS7036 of [CSharpSemanticChecks].
     */
    private fun checkConstructor(creation: CSharpObjectCreationExpression) {
        val arguments = creation.argumentList?.arguments.orEmpty()
        if (arguments.any { it.nameColon != null || it.expression?.text == "__arglist" }) return
        val at = creation.type ?: return
        when (val type = resolver.typeOf(creation)) {
            is SemanticType.Source -> sourceConstructors(type, arguments, creation)?.let { candidates ->
                // an internal constructor of another project is not there (the others are CS1729 of CSharpSemanticChecks)
                if (candidates.any { it.access == Access.MISSING } && candidates.none { it.access != Access.MISSING && it.takes }) shortDisplay(type)?.let { shown ->
                    report("CS1729", "'$shown' does not contain a constructor that takes ${arguments.size} arguments", at.textRange)
                } else decide(candidates, at)
            }
            is SemanticType.Library -> libraryConstructors(type, arguments, creation)?.let { candidates ->
                // the constructors the compiler did not import (internal, private) are not there: none for that many arguments is CS1729
                if (candidates.none { it.takes }) CSharpTypeDisplay.display(type, qualified = false)?.let { shown ->
                    report("CS1729", "'$shown' does not contain a constructor that takes ${arguments.size} arguments", at.textRange)
                } else decide(candidates, at)
            }
            else -> {}
        }
    }

    /** [takes]: the constructor has room for that many arguments (its optional and `params` parameters counted). */
    private class Candidate(val access: Access, val fits: Boolean?, val takes: Boolean = true, val shown: () -> String?)

    private fun takes(parameters: List<Param>, count: Int): Boolean =
        count >= parameters.count { !it.optional && !it.isParams } && (count <= parameters.size || parameters.lastOrNull()?.isParams == true)

    private fun sourceConstructors(type: SemanticType.Source, arguments: List<CSharpArgument>, site: PsiElement): List<Candidate>? {
        val info = type.info
        val kind = info.kind
        if (type.arguments.isNotEmpty() || isPartial(info) || kind != TypeKind.CLASS && kind != TypeKind.RECORD && kind != TypeKind.STRUCT && kind != TypeKind.RECORD_STRUCT) return null
        val declarations = info.parts.map { it.element() as? CSharpTypeDeclaration ?: return null }
        if (declarations.any { d -> d.modifiers.any { it.text == "abstract" || it.text == "static" } }) return null
        // the parameterless constructor of a struct is always there; a record's copy constructor is not written
        if ((kind == TypeKind.STRUCT || kind == TypeKind.RECORD_STRUCT) && arguments.isEmpty() || kind == TypeKind.RECORD && arguments.size == 1) return null
        val owner = shortDisplay(resolver.selfType(info)) ?: return null
        val simple = info.qualifiedName.substringAfterLast('.')
        return declarations.flatMap { d ->
            d.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } }.map { c ->
                val list = c.parameterList?.parameters.orEmpty()
                val parameters = sourceParameters(list)
                Candidate(access(c.modifiers.map { it.text }, info, false, c, site, constructor = true), parameters?.let { fits(it, arguments) },
                    parameters?.let { takes(it, arguments.size) } ?: true) {
                    parameters?.let { ps -> constructorDisplay("$owner.$simple", ps) }
                }
            } + listOfNotNull(d.parameterList?.let { list ->
                val parameters = sourceParameters(list.parameters)
                Candidate(Access.YES, parameters?.let { fits(it, arguments) }, parameters?.let { takes(it, arguments.size) } ?: true) { null }
            })
        }.ifEmpty { listOf(Candidate(Access.YES, arguments.isEmpty(), arguments.isEmpty()) { null }) }
    }

    private fun libraryConstructors(type: SemanticType.Library, arguments: List<CSharpArgument>, site: PsiElement): List<Candidate>? {
        val t = type.type
        if (t.kind != IndexedTypeKind.CLASS && t.kind != IndexedTypeKind.STRUCT || t.isAbstract || t.isStatic || !assemblies.isAccessible(t)) return null
        if (t.kind == IndexedTypeKind.STRUCT && arguments.isEmpty()) return null
        val owner = CSharpTypeDisplay.display(type, qualified = false) ?: return null
        return assemblies.ownMembers(t).filter { it.kind == IndexedMemberKind.CONSTRUCTOR }.map { m ->
            val parameters = libraryParameters(m, type.arguments)
            // `new` of a protected constructor is only for the type itself, which is not of this code
            val access = when (val a = libraryAccess(m, site)) {
                Access.YES -> if (m.isProtected && !(m.isInternal && !m.isPrivateProtected && assemblies.grants(t.index))) Access.NO else Access.YES
                else -> if (a == Access.UNSURE) a else if (a == Access.MISSING) a else Access.NO
            }
            Candidate(access, parameters?.let { fits(it, arguments) }, parameters?.let { takes(it, arguments.size) } ?: true) {
                parameters?.let { ps -> constructorDisplay("$owner.${t.simpleName}", ps) }
            }
        }
    }

    /** `Ov.Ov(string)`, `Ov.Ov(ref int)`: a constructor as Roslyn's messages name it. */
    private fun constructorDisplay(name: String, parameters: List<Param>): String? =
        name + "(" + parameters.map { p -> listOfNotNull(p.refKind, shortDisplay(p.type) ?: return null).joinToString(" ") }.joinToString(", ") + ")"

    private fun decide(candidates: List<Candidate>, at: PsiElement) {
        val kept = candidates.filter { it.access != Access.MISSING }
        if (kept.isEmpty() || kept.any { it.access == Access.UNSURE || it.fits == null }) return
        if (kept.any { it.access == Access.YES && it.fits == true }) return
        val one = kept.filter { it.access == Access.NO && it.fits == true }.singleOrNull() ?: return
        val shown = one.shown() ?: return
        report("CS0122", "'$shown' is inaccessible due to its protection level", at.textRange)
    }

    // ---- whether arguments fit parameters

    private class Param(val type: SemanticType, val refKind: String?, val isParams: Boolean, val optional: Boolean)

    private fun sourceParameters(list: List<CSharpParameter>): List<Param>? {
        val owner = (list.firstOrNull()?.containingFile as? CSharpFile)?.let(session::reachable) ?: resolver
        return list.map { p ->
            val modifiers = p.modifiers.map { it.text }
            if ("this" in modifiers || "scoped" in modifiers) return null
            Param(p.type?.let(owner::resolveType) ?: return null, modifiers.firstOrNull { it == "ref" || it == "out" || it == "in" }, "params" in modifiers, p.default != null)
        }
    }

    private fun libraryParameters(member: IndexedMember, typeArguments: List<SemanticType?>): List<Param>? =
        session.parameters(member).map { p ->
            val kind = when {
                p.isOut -> "out"
                p.isRef -> "ref"
                p.isIn -> "in"
                else -> null
            }
            Param(resolver.fromRef(p.typeRef, typeArguments) ?: return null, kind, p.isParams, p.isOptional || p.hasDefault)
        }

    /** Whether the positional [arguments] surely fit [parameters] (true), surely not (false); null when a type or a conversion is not known. */
    private fun fits(parameters: List<Param>, arguments: List<CSharpArgument>): Boolean? {
        val count = arguments.size
        val variadic = parameters.lastOrNull()?.takeIf { it.isParams }
        if (count < parameters.count { !it.optional && !it.isParams }) return false
        if (count > parameters.size && variadic == null) return false
        val fixed = if (variadic != null) parameters.size - 1 else parameters.size
        var unsure = false
        for (i in 0 until minOf(count, fixed)) {
            when (fitsOne(arguments[i], parameters[i], parameters[i].type)) {
                false -> return false
                null -> unsure = true
                true -> {}
            }
        }
        if (variadic != null && count > fixed) {
            // the normal form (an array) first, then the expanded one
            val normal = if (count == parameters.size) fitsOne(arguments.last(), variadic, variadic.type) else false
            if (normal != true) {
                val element = (variadic.type as? SemanticType.ArrayOf)?.element ?: return null
                var expanded: Boolean? = true
                for (i in fixed until count) when (fitsOne(arguments[i], Param(element, null, false, false), element)) {
                    false -> expanded = false
                    null -> if (expanded == true) expanded = null
                    true -> {}
                }
                when {
                    expanded == false && normal == false -> return false
                    expanded == null || normal == null -> unsure = true
                }
            }
        }
        return if (unsure) null else true
    }

    /**
     * The overload the arguments surely choose, by its position: the only one they fit, or of several the only one every argument is
     * of the very type of (`v[1]` of `this[int]` and `this[long]`); null when it is not sure.
     */
    private fun chosen(overloads: List<List<Param>>, arguments: List<CSharpArgument>): Int? {
        val fitting = overloads.indices.filter { fits(overloads[it], arguments) ?: return null }
        fitting.singleOrNull()?.let { return it }
        return fitting.filter { exact(overloads[it], arguments) }.singleOrNull()
    }

    private fun exact(parameters: List<Param>, arguments: List<CSharpArgument>): Boolean =
        parameters.size == arguments.size && parameters.zip(arguments).all { (p, a) ->
            val value = a.expression?.let(::unparenthesized)
            p.refKind == null && !p.isParams && a.refKindKeyword == null && value != null && value !is CSharpDeclarationExpression &&
                resolver.typeOf(value)?.let { resolver.overloads.argument(value, it, p.type) } == CSharpNameResolver.Conversion.IDENTITY
        }

    private fun fitsOne(argument: CSharpArgument, parameter: Param, target: SemanticType): Boolean? {
        val expression = argument.expression ?: return null
        val given = argument.refKindKeyword?.text
        if (given == "ref" || given == "out") return if (parameter.refKind != given) false else null
        if (parameter.refKind == "ref" || parameter.refKind == "out") return false
        if (expression is CSharpDeclarationExpression) return null
        val value = unparenthesized(expression)
        val type = if (value.elementType == SyntaxKind.NullLiteralExpression) null else resolver.typeOf(value) ?: return null
        return when (resolver.overloads.argument(value, type, target)) {
            CSharpNameResolver.Conversion.IDENTITY, CSharpNameResolver.Conversion.IMPLICIT -> true
            CSharpNameResolver.Conversion.NONE -> false
            CSharpNameResolver.Conversion.UNKNOWN -> null
        }
    }

    // ---- CS0154, CS0271

    private fun checkRead(name: CSharpSimpleName) {
        val parent = name.parent
        val top: CSharpExpression = when {
            parent is CSharpMemberAccessExpression && parent.nameElement == name -> parent
            NativeCSharpScopes.isFreeName(name) && name is CSharpIdentifierName -> name
            else -> return
        }
        if (!isRead(top)) return
        val leaf = name.identifier ?: return
        if (resolver.syntax.symbolAt(leaf) != null) return
        val symbol = resolver.resolve(leaf)?.single ?: return
        if (!receiverFits(top, symbol)) return
        when (symbol) {
            is CSharpSymbol.SourceMember -> {
                val property = symbol.element as? CSharpPropertyDeclaration ?: return
                val declaring = declaringType(property) ?: return
                if (sourceAccess(symbol, declaring, name) != Access.YES) return
                if (top == name && isInstance(symbol) && inStaticMember(name)) return
                val facts = facts(property, declaring) ?: return
                val shown = memberDisplay(symbol.target, symbol.owner, declaring) ?: return
                val getter = facts.getter
                val access = when {
                    getter == null -> Access.MISSING
                    getter.isEmpty() -> Access.YES
                    else -> access(getter, declaring, false, property, name)
                }
                if (access == Access.MISSING) reportNoGetter(shown, top.textRange)
                else if (access == Access.NO) reportGetter(shown, top.textRange)
            }
            is CSharpSymbol.LibraryMember -> {
                val member = symbol.member
                if (member.kind != IndexedMemberKind.PROPERTY || member.typeRef is IndexedTypeRef.ByRef) return
                if (libraryAccess(member, name) != Access.YES) return
                val shown = libraryDisplay(symbol) ?: return
                when (accessorAccess(member.getterAccess, member, name)) {
                    Access.MISSING -> reportNoGetter(shown, top.textRange)
                    Access.NO -> reportGetter(shown, top.textRange)
                    else -> {}
                }
            }
            else -> {}
        }
    }

    /** Whether the value of [expression] is read: not the target of a plain `=`, nor a `ref` / `out` argument, nor an element of a tuple. */
    private fun isRead(expression: CSharpExpression): Boolean = when (val parent = expression.parent) {
        is CSharpAssignmentExpression -> parent.left != expression || parent.operatorToken?.text != "="
        is CSharpArgument -> parent.refKindKeyword == null && parent.parent !is CSharpTupleExpression
        is CSharpRefExpression, is CSharpParenthesizedExpression -> false
        else -> true
    }

    // ---- assignments: CS0131, CS0191, CS0198, CS0200, CS0272, CS8852

    private fun checkAssignment(assignment: CSharpAssignmentExpression) {
        val left = assignment.left ?: return
        val plain = assignment.operatorToken?.text == "="
        if (NativeCSharpScopes.isObjectInitializer(assignment.parent)) {
            // `Items = { 1, 2 }` reads the property and fills it
            if (plain && assignment.right !is CSharpInitializerExpression && left is CSharpIdentifierName) checkWrite(left, Receiver.INITIALIZER, assignment = true)
            return
        }
        if (left is CSharpTupleExpression) {
            if (plain) for (argument in left.arguments) if (argument.refKindKeyword == null && argument.nameColon == null) argument.expression?.let { checkWrite(it, Receiver.NONE, true) }
            return
        }
        checkWrite(left, Receiver.NONE, assignment = true)
    }

    private enum class Receiver { NONE, IMPLICIT, THIS, BASE, INITIALIZER, OTHER }

    /** The member name of `X`, `this.X`, `a.X` (or of an object initializer, [given]) and what it is reached through. */
    private fun target(left: CSharpExpression, given: Receiver): Pair<CSharpSimpleName, Receiver>? = when {
        given == Receiver.INITIALIZER -> (left as? CSharpIdentifierName)?.let { it to given }
        left is CSharpIdentifierName -> if (NativeCSharpScopes.isFreeName(left)) left to Receiver.IMPLICIT else null
        left is CSharpMemberAccessExpression -> (left.nameElement as? CSharpIdentifierName)?.let { name ->
            when (left.expression?.let(::unparenthesized)) {
                is CSharpThisExpression -> name to Receiver.THIS
                is CSharpBaseExpression -> name to Receiver.BASE
                null -> null
                else -> name to Receiver.OTHER
            }
        }
        else -> null
    }

    /** [target] written to (by an assignment when [assignment], else by `++` / `--`); [given]: the object of an object initializer. */
    private fun checkWrite(target: CSharpExpression, given: Receiver, assignment: Boolean) {
        val left = unparenthesized(target)
        if (left is CSharpElementAccessExpression) return checkIndexer(left)
        val (name, receiver) = target(left, given) ?: run {
            if (assignment && given != Receiver.INITIALIZER) checkValue(left)
            return
        }
        val leaf = name.identifier ?: return
        val symbol = resolver.resolve(leaf)?.single ?: return
        if (given != Receiver.INITIALIZER && !receiverFits(left, symbol)) return
        val range = left.textRange
        when (symbol) {
            is CSharpSymbol.Local -> if (assignment && symbol.symbol.kind == LocalSymbolKind.LOCAL && isConstLocal(symbol)) reportValue(range)
            is CSharpSymbol.SourceMember -> writeSource(symbol, name, receiver, assignment, range)
            is CSharpSymbol.LibraryMember -> writeLibrary(symbol, name, receiver, assignment, range)
            else -> {}
        }
    }

    private fun writeSource(symbol: CSharpSymbol.SourceMember, name: CSharpSimpleName, receiver: Receiver, assignment: Boolean, range: TextRange) {
        val element = symbol.target
        val declaring = declaringType(element) ?: return
        if (sourceAccess(symbol, declaring, name) != Access.YES) return
        if (receiver == Receiver.IMPLICIT && isInstance(symbol) && inStaticMember(name)) return
        if (element is CSharpEnumMemberDeclaration) {
            if (assignment) reportValue(range)
            return
        }
        val field = element as? CSharpBaseFieldDeclaration ?: PsiTreeUtil.getParentOfType(element, CSharpBaseFieldDeclaration::class.java)
        if (field != null && (element is CSharpBaseFieldDeclaration || element is CSharpVariableDeclarator)) {
            if (field is CSharpEventFieldDeclaration) return
            val modifiers = field.modifiers.map { it.text }
            when {
                "const" in modifiers -> if (assignment) reportValue(range)
                "readonly" in modifiers -> readonlyField("static" in modifiers, declaring, receiver, name, range, byReference = false)
            }
            return
        }
        val property = element as? CSharpPropertyDeclaration
        val facts = when {
            property != null -> facts(property, declaring)
            element is CSharpParameter -> recordFacts(element, declaring)
            else -> null
        } ?: return
        val shown = memberDisplay(element, symbol.owner, declaring) ?: return
        val setter = facts.setter
        if (setter == null) {
            val static = property != null && property.modifiers.any { it.text == "static" }
            if (facts.auto && autoAssignable(static, declaring, receiver, name) != false) return
            reportReadOnly(shown, range)
            return
        }
        // as Roslyn checks them: an accessor that is not imported, then where an init-only one may be called, then who may call it
        val access = if (setter.isEmpty()) Access.YES else access(setter, declaring, false, element, name)
        when {
            access == Access.UNSURE -> {}
            access == Access.MISSING -> reportReadOnly(shown, range)
            facts.init && !initAssignable(receiver, name) -> reportInit(shown, range)
            access == Access.NO -> reportSetter(shown, range)
        }
    }

    private fun writeLibrary(symbol: CSharpSymbol.LibraryMember, name: CSharpSimpleName, receiver: Receiver, assignment: Boolean, range: TextRange) {
        val member = symbol.member
        if (libraryAccess(member, name) != Access.YES) return
        when (member.kind) {
            IndexedMemberKind.CONSTANT, IndexedMemberKind.ENUM_MEMBER -> if (assignment) reportValue(range)
            IndexedMemberKind.FIELD -> if (member.isReadOnly) {
                if (member.isStatic) reportStatic(range) else reportReadonly(range)
            }
            IndexedMemberKind.PROPERTY -> {
                // a property that returns by reference is assigned through its getter
                if (member.typeRef is IndexedTypeRef.ByRef) return
                val shown = libraryDisplay(symbol) ?: return
                // the index has every accessor the compiler imports (not the private ones, nor the internal ones without InternalsVisibleTo)
                val access = accessorAccess(member.setterAccess, member, name)
                when {
                    access == Access.UNSURE -> {}
                    access == Access.MISSING -> reportReadOnly(shown, range)
                    member.isInitOnly && !initAssignable(receiver, name) -> reportInit(shown, range)
                    access == Access.NO -> reportSetter(shown, range)
                }
            }
            else -> {}
        }
    }

    /** `obj.Field` of a static member or `Type.Field` of an instance one is an error of its own (CS0176 / CS0120): nothing said here. */
    private fun receiverFits(expression: CSharpExpression, symbol: CSharpSymbol): Boolean {
        val access = expression as? CSharpMemberAccessExpression ?: return true
        val static = when (symbol) {
            is CSharpSymbol.SourceMember -> {
                val element = symbol.target
                if (element is CSharpParameter) false
                else (modifiersOf(element) ?: return false).let { "static" in it || "const" in it } || element is CSharpEnumMemberDeclaration
            }
            is CSharpSymbol.LibraryMember -> symbol.member.isStatic || symbol.member.kind == IndexedMemberKind.CONSTANT || symbol.member.kind == IndexedMemberKind.ENUM_MEMBER
            else -> return true
        }
        return when (access.expression?.let(::unparenthesized)?.let(resolver::qualifier)) {
            is CSharpNameResolver.Qualifier.Type -> static
            is CSharpNameResolver.Qualifier.Value -> !static
            is CSharpNameResolver.Qualifier.ValueOrType -> true
            else -> false
        }
    }

    /** A readonly field written ([byReference]: passed by `ref` / `out`, CS0192 / CS0199) outside a constructor of its type. */
    private fun readonlyField(static: Boolean, declaring: TypeInfo, receiver: Receiver, at: PsiElement, range: TextRange, byReference: Boolean) {
        val context = contextOf(at)
        if (context == Context.Unsure) return
        if (static) {
            if (context is Context.Constructor && context.static && ownerKey(context.declaration) == declaring.key) return
            return if (byReference) reportStaticByReference(range) else reportStatic(range)
        }
        if (receiver == Receiver.IMPLICIT && inStaticMember(at)) return
        val inOwn = context is Context.Constructor && !context.static && ownerKey(context.declaration) == declaring.key ||
            context is Context.Init && ownerKey(context.accessor) == declaring.key
        if (inOwn && (receiver == Receiver.IMPLICIT || receiver == Receiver.THIS)) return
        // `base.Field` in a constructor, an instance field named in a static constructor: other errors, or none
        if (inOwn && receiver == Receiver.BASE || context is Context.Constructor && context.static) return
        if (byReference) reportReadonlyByReference(range) else reportReadonly(range)
    }

    /** A get-only auto-property is assigned in a constructor of its type (static for a static one), on `this`: true / false, null when unsure. */
    private fun autoAssignable(static: Boolean, declaring: TypeInfo, receiver: Receiver, at: PsiElement): Boolean? {
        val context = contextOf(at)
        if (context == Context.Unsure) return null
        if (context !is Context.Constructor || ownerKey(context.declaration) != declaring.key) return false
        if (static) return if (context.static) true else false
        if (context.static) return null
        return when (receiver) {
            Receiver.IMPLICIT, Receiver.THIS -> true
            Receiver.OTHER -> false
            else -> null
        }
    }

    /** An init-only setter: an object or `with` initializer, or `this` / `base` in an instance constructor or an `init` accessor. */
    private fun initAssignable(receiver: Receiver, at: PsiElement): Boolean {
        if (receiver == Receiver.INITIALIZER) return true
        val context = contextOf(at)
        // a lambda or a local function in a constructor is not the constructor
        if (context == Context.Unsure) return true
        val own = context is Context.Constructor && !context.static || context is Context.Init
        return own && receiver != Receiver.OTHER
    }

    private fun ownerKey(element: PsiElement): String? = declaringType(element)?.key

    private sealed class Context {
        object Lambda : Context()
        object Unsure : Context()
        object Other : Context()
        class Constructor(val declaration: CSharpConstructorDeclaration, val static: Boolean) : Context()
        class Init(val accessor: CSharpAccessorDeclaration) : Context()
    }

    /** The function the code at [at] runs in, as far as assigning readonly things goes. */
    private fun contextOf(at: PsiElement): Context {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            when (current) {
                is CSharpAnonymousFunctionExpression, is CSharpLocalFunctionStatement -> return Context.Lambda
                // a constructor initializer, a property initializer, a default value: rules of their own
                is CSharpConstructorInitializer, is CSharpBaseList, is CSharpAttributeList -> return Context.Unsure
                is CSharpEqualsValueClause -> if (current.parent is CSharpPropertyDeclaration || current.parent is CSharpParameter) return Context.Unsure
                is CSharpConstructorDeclaration -> return Context.Constructor(current, current.modifiers.any { it.text == "static" })
                is CSharpAccessorDeclaration -> return if (current.keyword?.text == "init") Context.Init(current) else Context.Other
                is CSharpBaseFieldDeclaration -> return Context.Unsure
                is CSharpMemberDeclaration -> return Context.Other
            }
            current = current.parent
        }
        return Context.Other
    }

    /** In a static member (or a static lambda): an instance member named there without a receiver is CS0120's, not this group's. */
    private fun inStaticMember(at: PsiElement): Boolean {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            when (current) {
                is CSharpAnonymousFunctionExpression -> if (current.modifiers.any { it.text == "static" }) return true
                is CSharpLocalFunctionStatement -> if (current.modifiers.any { it.text == "static" }) return true
                is CSharpMemberDeclaration -> return current.modifiers.any { it.text == "static" || it.text == "const" }
            }
            current = current.parent
        }
        return false
    }

    /** The declaration of a member; a positional parameter of a record is found by its identifier. */
    private val CSharpSymbol.SourceMember.target: PsiElement
        get() = element.let { e -> (e.parent as? CSharpParameter)?.takeIf { it.identifier == e } ?: e }

    private fun isInstance(symbol: CSharpSymbol.SourceMember): Boolean =
        symbol.target is CSharpParameter || modifiersOf(symbol.target)?.none { it == "static" || it == "const" } == true

    // ---- CS0192, CS0199, CS0206

    /** `M(ref x.Field)`, `M(out x.Property)`, `M(ref list[0])`: what cannot be passed by reference. */
    private fun checkRefArgument(argument: CSharpArgument) {
        val kind = argument.refKindKeyword?.text
        if (kind != "ref" && kind != "out" || argument.parent?.parent is CSharpTupleExpression) return
        val left = unparenthesized(argument.expression ?: return)
        val range = left.textRange
        if (left is CSharpElementAccessExpression) {
            if (indexersByValue(left) == true) reportByReference(range)
            return
        }
        val (name, receiver) = target(left, Receiver.NONE) ?: return
        val leaf = name.identifier ?: return
        val symbol = resolver.resolve(leaf)?.single ?: return
        if (!receiverFits(left, symbol)) return
        when (symbol) {
            is CSharpSymbol.SourceMember -> {
                val element = symbol.target
                val declaring = declaringType(element) ?: return
                if (sourceAccess(symbol, declaring, name) != Access.YES) return
                if (receiver == Receiver.IMPLICIT && isInstance(symbol) && inStaticMember(name)) return
                when {
                    element is CSharpVariableDeclarator || element is CSharpBaseFieldDeclaration -> {
                        val field = element as? CSharpFieldDeclaration ?: PsiTreeUtil.getParentOfType(element, CSharpFieldDeclaration::class.java) ?: return
                        val modifiers = field.modifiers.map { it.text }
                        if ("readonly" in modifiers && "const" !in modifiers) readonlyField("static" in modifiers, declaring, receiver, name, range, byReference = true)
                    }
                    element is CSharpPropertyDeclaration -> if (element.type !is CSharpRefType && "override" !in element.modifiers.map { it.text }) reportByReference(range)
                    element is CSharpParameter -> if (recordFacts(element, declaring) != null) reportByReference(range)
                }
            }
            is CSharpSymbol.LibraryMember -> {
                val member = symbol.member
                if (libraryAccess(member, name) != Access.YES) return
                when (member.kind) {
                    IndexedMemberKind.FIELD -> if (member.isReadOnly) { if (member.isStatic) reportStaticByReference(range) else reportReadonlyByReference(range) }
                    IndexedMemberKind.PROPERTY -> if (member.typeRef !is IndexedTypeRef.ByRef) reportByReference(range)
                    else -> {}
                }
            }
            else -> {}
        }
    }

    /** Whether every indexer `a[...]` may be is one that does not return by reference (null: not known). */
    private fun indexersByValue(element: CSharpElementAccessExpression): Boolean? {
        val receiver = element.expression ?: return null
        return when (val type = resolver.typeOf(receiver)) {
            is SemanticType.Source -> {
                if (type.arguments.isNotEmpty() || isPartial(type.info) || !checks.isKnown(type) || !onlyObjectBases(type)) return null
                val declaration = type.info.parts.single().element() as? CSharpTypeDeclaration ?: return null
                val indexers = declaration.members.filterIsInstance<CSharpIndexerDeclaration>()
                indexers.isNotEmpty() && indexers.all { it.type !is CSharpRefType }
            }
            is SemanticType.Library -> {
                if (!checks.checkable(type)) return null
                val indexers = session.libraryMembers(assemblies, type.type).values.flatten().filter { it.member.kind == IndexedMemberKind.INDEXER }
                indexers.isNotEmpty() && indexers.all { it.member.typeRef !is IndexedTypeRef.ByRef }
            }
            else -> null
        }
    }

    // ---- properties

    /**
     * What the declaration of a property says: the modifiers of its getter and setter (null: none; empty: the property's), whether the
     * setter is `init`, whether it is an auto-property (`field` counts). Null when the base or another part may have more.
     */
    private class Facts(val getter: Collection<String>?, val setter: Collection<String>?, val init: Boolean, val auto: Boolean)

    private fun facts(property: CSharpPropertyDeclaration, declaring: TypeInfo): Facts? {
        val modifiers = property.modifiers.map { it.text }
        if (modifiers.any { it == "override" || it == "partial" || it == "extern" } || property.type is CSharpRefType || property.explicitInterfaceSpecifier != null) return null
        if (property.expressionBody != null) return Facts(emptyList(), null, init = false, auto = false)
        val accessors = property.accessorList?.accessors ?: return null
        val byKeyword = accessors.groupBy { it.keyword?.text }
        if (byKeyword.values.any { it.size > 1 } || byKeyword.keys.any { it != "get" && it != "set" && it != "init" } || "set" in byKeyword && "init" in byKeyword) return null
        val get = byKeyword["get"]?.single()
        val set = byKeyword["set"]?.single() ?: byKeyword["init"]?.single()
        val auto = declaring.kind != TypeKind.INTERFACE && "abstract" !in modifiers &&
            accessors.all { a -> a.body == null && a.expressionBody == null || mentionsField(a) }
        return Facts(get?.modifiers?.map { it.text }, set?.modifiers?.map { it.text }, init = set?.keyword?.text == "init", auto = auto)
    }

    /** C# 14 `field`: an accessor with a body that uses the backing field (any `field` name: a guess on the safe side). */
    private fun mentionsField(accessor: CSharpAccessorDeclaration): Boolean {
        var found = false
        PsiTreeUtil.processElements(accessor) { e ->
            if (e.firstChild == null && e.text == "field") found = true
            !found
        }
        return found
    }

    /** A positional parameter of a record: `init` in a record class and a readonly record struct, `set` in a record struct. */
    private fun recordFacts(parameter: CSharpParameter, declaring: TypeInfo): Facts? {
        val type = parameter.parent?.parent as? CSharpTypeDeclaration ?: return null
        if (resolver.syntax.declaredType(type)?.key != declaring.key || isPartial(declaring)) return null
        return when (declaring.kind) {
            TypeKind.RECORD -> Facts(emptyList(), emptyList(), init = true, auto = true)
            TypeKind.RECORD_STRUCT -> if (type.modifiers.any { it.text == "readonly" }) Facts(emptyList(), emptyList(), init = true, auto = true) else null
            else -> null
        }
    }

    private fun onlyObjectBases(type: SemanticType.Source): Boolean =
        resolver.baseTypes(type).all { it is SemanticType.Library && (it.type.fullName == "System.Object" || it.type.fullName == "System.ValueType") }

    /**
     * `a[i] = v`: the indexer the arguments surely choose — of a type of the solution with no base that may have another, or of an
     * assembly — CS0200 without a setter (or one not imported), CS0272 with one the place cannot reach.
     */
    private fun checkIndexer(element: CSharpElementAccessExpression) {
        val receiver = element.expression ?: return
        val arguments = element.argumentList?.arguments ?: return
        if (arguments.any { it.nameColon != null || it.refKindKeyword != null }) return
        when (val type = resolver.typeOf(receiver)) {
            is SemanticType.Source -> sourceIndexer(type, arguments, element)
            is SemanticType.Library -> libraryIndexer(type, arguments, element)
            else -> {}
        }
    }

    private fun sourceIndexer(type: SemanticType.Source, arguments: List<CSharpArgument>, element: CSharpElementAccessExpression) {
        val info = type.info
        if (type.arguments.isNotEmpty() || isPartial(info) || !checks.isKnown(type) || !onlyObjectBases(type)) return
        val declaration = info.parts.single().element() as? CSharpTypeDeclaration ?: return
        val indexers = declaration.members.filterIsInstance<CSharpIndexerDeclaration>()
        val candidates = indexers.map { it to (sourceParameters(it.parameterList?.parameters ?: return) ?: return) }
        val indexer = chosen(candidates.map { it.second }, arguments)?.let { candidates[it].first } ?: return
        val parameters = indexer.parameterList?.parameters ?: return
        val modifiers = indexer.modifiers.map { it.text }
        if (modifiers.any { it == "override" || it == "partial" || it == "extern" } || indexer.type is CSharpRefType || indexer.explicitInterfaceSpecifier != null) return
        if (access(modifiers, info, info.kind == TypeKind.INTERFACE, indexer, element) != Access.YES) return
        val shownParameters = parameters.map { p ->
            if (p.modifiers.isNotEmpty()) return
            p.type?.let(resolver::resolveType)?.let { shortDisplay(it) } ?: return
        }
        val owner = shortDisplay(type) ?: return
        val shown = "$owner.this[${shownParameters.joinToString(", ")}]"
        val setter = if (indexer.expressionBody != null) null else (indexer.accessorList ?: return).accessors.firstOrNull { it.keyword?.text == "set" || it.keyword?.text == "init" }
        if (setter == null) return reportReadOnly(shown, element.textRange)
        val setterModifiers = setter.modifiers.map { it.text }
        if (setterModifiers.isEmpty()) return
        when (access(setterModifiers, info, false, indexer, element)) {
            Access.MISSING -> reportReadOnly(shown, element.textRange)
            Access.NO -> reportSetter(shown, element.textRange)
            else -> {}
        }
    }

    private fun libraryIndexer(type: SemanticType.Library, arguments: List<CSharpArgument>, element: CSharpElementAccessExpression) {
        if (!checks.checkable(type)) return
        val indexers = session.libraryMembers(assemblies, type.type).values.flatten().filter { it.member.kind == IndexedMemberKind.INDEXER }
        if (indexers.isEmpty()) return
        val candidates = indexers.map { found ->
            val typeArguments = resolver.declaringArguments(type, found.from)
            Triple(found.member, typeArguments, libraryParameters(found.member, typeArguments) ?: return)
        }
        val (member, typeArguments, _) = chosen(candidates.map { it.third }, arguments)?.let { candidates[it] } ?: return
        if (member.typeRef is IndexedTypeRef.ByRef || libraryAccess(member, element) != Access.YES) return
        val owner = CSharpTypeDisplay.display(SemanticType.Library(member.type, typeArguments), qualified = false) ?: return
        val parameters = libraryParameters(member, typeArguments)?.map { p -> if (p.refKind != null || p.isParams) return else shortDisplay(p.type) ?: return } ?: return
        val shown = "$owner.this[${parameters.joinToString(", ")}]"
        when (accessorAccess(member.setterAccess, member, element)) {
            Access.MISSING -> reportReadOnly(shown, element.textRange)
            Access.NO -> reportSetter(shown, element.textRange)
            else -> {}
        }
    }

    // ---- CS0131

    /** A value on the left of an assignment: a literal, an operation, a cast, a call of a method that does not return by reference. */
    private fun checkValue(left: CSharpExpression) {
        val value = when (left) {
            is CSharpLiteralExpression -> left.token?.text.let { it != "default" && it != "__arglist" }
            is CSharpBinaryExpression, is CSharpCastExpression -> true
            is CSharpInvocationExpression -> returnsValue(left)
            else -> false
        }
        if (value) reportValue(left.textRange)
    }

    private fun returnsValue(call: CSharpInvocationExpression): Boolean {
        val callee = when (val e = call.expression) {
            is CSharpIdentifierName -> e
            is CSharpMemberAccessExpression -> e.nameElement as? CSharpIdentifierName
            else -> null
        } ?: return false
        val symbol = callee.identifier?.let(resolver::resolve)?.single ?: return false
        return when (symbol) {
            is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.returnType?.let { it !is CSharpRefType } == true
            is CSharpSymbol.LibraryMember -> symbol.member.kind.isCallable && symbol.member.typeRef !is IndexedTypeRef.ByRef
            else -> false
        }
    }

    private fun isConstLocal(local: CSharpSymbol.Local): Boolean =
        PsiTreeUtil.getParentOfType(local.symbol.declaration, CSharpLocalDeclarationStatement::class.java)?.modifiers?.any { it.text == "const" } == true

    // ---- messages

    /** `A._p`, `Gen<int>.Value`, `A.Hidden(int, string)`: the member [element] as Roslyn's messages name it, of [owner] when it is [declaring]. */
    private fun memberDisplay(element: PsiElement, owner: SemanticType?, declaring: TypeInfo): String? {
        val type = (owner as? SemanticType.Source)?.takeIf { it.info.key == declaring.key } ?: resolver.selfType(declaring)
        val shownType = shortDisplay(type) ?: return null
        return when (element) {
            is CSharpMethodDeclaration -> {
                if (element.typeParameterList != null || type.arguments.isNotEmpty()) return null
                val parameters = element.parameterList?.parameters.orEmpty().map { p ->
                    if (p.modifiers.isNotEmpty()) return null
                    p.type?.let(resolver::resolveType)?.let { shortDisplay(it) } ?: return null
                }
                "$shownType.${element.identifier?.text ?: return null}(${parameters.joinToString(", ")})"
            }
            is CSharpPropertyDeclaration -> "$shownType.${element.identifier?.text ?: return null}"
            is CSharpVariableDeclarator -> "$shownType.${element.identifier?.text ?: return null}"
            is CSharpBaseFieldDeclaration -> "$shownType.${element.declaration?.variables?.singleOrNull()?.identifier?.text ?: return null}"
            is CSharpParameter -> "$shownType.${element.identifier?.text ?: return null}"
            is CSharpEventDeclaration -> "$shownType.${element.identifier?.text ?: return null}"
            else -> null
        }
    }

    private fun shortDisplay(type: SemanticType): String? = if (type is SemanticType.Source) typeDisplay(type) else CSharpTypeDisplay.display(type, qualified = false)

    /** `Outer.Pub`, `Gen<int>`: a type of the solution without its namespace, nested ones with the types around them (not generic ones). */
    private fun typeDisplay(type: SemanticType.Source): String? {
        val outer = type.info.parts.firstOrNull()?.element()?.parent as? CSharpBaseTypeDeclaration ?: return CSharpTypeDisplay.display(type, qualified = false)
        val outerInfo = resolver.syntax.declaredType(outer)?.takeIf { it.arity == 0 && type.arguments.isEmpty() } ?: return null
        return (typeDisplay(resolver.selfType(outerInfo)) ?: return null) + "." + type.info.qualifiedName.substringAfterLast('.')
    }

    /** `List<int>.Count`, `string.Length`, `object.MemberwiseClone()`. */
    private fun libraryDisplay(symbol: CSharpSymbol.LibraryMember): String? {
        val member = symbol.member
        val owner = CSharpTypeDisplay.display(SemanticType.Library(member.type, symbol.declaringArguments), qualified = false) ?: return null
        if (!member.kind.isCallable) return "$owner.${member.name}"
        if (member.arity > 0 || member.type.arity > 0) return null
        val parameters = session.parameters(member).map { p ->
            if (p.isByReference || p.isParams) return null
            resolver.fromRef(p.typeRef, emptyList())?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
        }
        return "$owner.${member.name}(${parameters.joinToString(", ")})"
    }

    private fun unparenthesized(expression: CSharpExpression): CSharpExpression {
        var e = expression
        while (e is CSharpParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    private fun reportReadOnly(shown: String, range: TextRange) = report("CS0200", "Property or indexer '$shown' cannot be assigned to -- it is read only", range)

    private fun reportSetter(shown: String, range: TextRange) =
        report("CS0272", "The property or indexer '$shown' cannot be used in this context because the set accessor is inaccessible", range)

    private fun reportNoGetter(shown: String, range: TextRange) =
        report("CS0154", "The property or indexer '$shown' cannot be used in this context because it lacks the get accessor", range)

    private fun reportGetter(shown: String, range: TextRange) =
        report("CS0271", "The property or indexer '$shown' cannot be used in this context because the get accessor is inaccessible", range)

    private fun reportInit(shown: String, range: TextRange) =
        report("CS8852", "Init-only property or indexer '$shown' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor.", range)

    private fun reportReadonly(range: TextRange) =
        report("CS0191", "A readonly field cannot be assigned to (except in a constructor or init-only setter of the type in which the field is defined or a variable initializer)", range)

    private fun reportStatic(range: TextRange) = report("CS0198", "A static readonly field cannot be assigned to (except in a static constructor or a variable initializer)", range)

    private fun reportReadonlyByReference(range: TextRange) = report("CS0192", "A readonly field cannot be used as a ref or out value (except in a constructor)", range)

    private fun reportStaticByReference(range: TextRange) = report("CS0199", "A static readonly field cannot be used as a ref or out value (except in a static constructor)", range)

    private fun reportByReference(range: TextRange) = report("CS0206", "A non ref-returning property or indexer may not be used as an out or ref value", range)

    private fun reportValue(range: TextRange) = report("CS0131", "The left-hand side of an assignment must be a variable, property or indexer", range)

    private fun report(code: String, message: String, range: TextRange) {
        sink(CSharpSemanticProblem(code, message, range))
    }

    private companion object {
        const val MAX_ARITY = 8
    }
}

/**
 * Who sees the internals of a project of the solution (errors of access across projects): its `InternalsVisibleTo` items (the SDK writes
 * the attribute of them) and the `[assembly: InternalsVisibleTo("Name, PublicKey=…")]` of its files; a project is named by its
 * `AssemblyName` (the name of its file by default). An attribute whose argument is not a string literal makes the friends unknown.
 */
internal object CSharpFriendAssemblies {
    private val ASSEMBLY_ATTRIBUTES = Regex("""\[\s*assembly\s*:([^\]]*)]""")
    private val FRIEND = Regex("""InternalsVisibleTo(?:Attribute)?\s*\(""")
    private val LITERAL = Regex("""\G\s*@?"([^"]*)"""")
    private val KEY = Key.create<CachedValue<Map<String, Set<String>?>>>("dotnet.csharp.friendAssemblies")

    /**
     * The friends of the project [projectFile]: `Name`, or `Name,key` when the friend is named with a public key; null when not known (an
     * index not ready, an argument that is not a string literal).
     */
    fun friendsOf(project: Project, projectFile: VirtualFile): Set<String>? {
        val items = CompilationModel.getInstance(project).options(projectFile).internalsVisibleTo
        if (DumbService.isDumb(project)) return null
        val attributes = attributes(project)
        val written = if (projectFile.path in attributes) attributes[projectFile.path] ?: return null else emptySet()
        return (items.map(::nameOf) + written).filterTo(LinkedHashSet()) { it.substringBefore(',').isNotEmpty() }
    }

    /**
     * Whether [friends] make the project [projectFile] a friend: its assembly name (the project file's by default) is one of them; one
     * named with a public key is for a signed assembly only (its key is not compared).
     */
    fun isFriend(project: Project, projectFile: VirtualFile, friends: Collection<String>): Boolean {
        val options = CompilationModel.getInstance(project).options(projectFile)
        val name = options.assemblyName ?: projectFile.nameWithoutExtension
        return friends.any { it.substringBefore(',').equals(name, ignoreCase = true) && (options.signAssembly || !it.endsWith(",key")) }
    }

    fun producesReferenceAssembly(project: Project, projectFile: VirtualFile): Boolean? = CompilationModel.getInstance(project).options(projectFile).produceReferenceAssembly

    private fun nameOf(friend: String): String {
        val parts = friend.split(',')
        return parts[0].trim() + if (parts.drop(1).any { it.trim().startsWith("PublicKey", ignoreCase = true) }) ",key" else ""
    }

    /** The friends written in the files of each project by the path of its project file; null for a project with an attribute not understood. */
    private fun attributes(project: Project): Map<String, Set<String>?> = CachedValuesManager.getManager(project).getCachedValue(project, KEY, {
        val found = HashMap<String, MutableSet<String>?>()
        val search = PsiSearchHelper.getInstance(project)
        for (word in listOf("InternalsVisibleTo", "InternalsVisibleToAttribute")) {
            search.processAllFilesWithWord(word, GlobalSearchScope.projectScope(project), { psi ->
                val owner = (psi as? CSharpFile)?.let(CSharpSemanticEnvironment::projectOf)?.path
                if (owner != null && (owner !in found || found[owner] != null)) {
                    val names = parse(psi.text)
                    found[owner] = if (names == null) null else (found[owner] ?: LinkedHashSet()).apply { addAll(names) }
                }
                true
            }, true)
        }
        CachedValueProvider.Result.create(found as Map<String, Set<String>?>, PsiModificationTracker.MODIFICATION_COUNT)
    }, false)

    /** The names of `[assembly: InternalsVisibleTo("…")]` in [text]; null when one does not take a string literal. */
    internal fun parse(text: String): List<String>? {
        val names = ArrayList<String>()
        for (block in ASSEMBLY_ATTRIBUTES.findAll(text)) {
            val body = block.groupValues[1]
            for (friend in FRIEND.findAll(body)) {
                val literal = LITERAL.find(body, friend.range.last + 1) ?: return null
                names += nameOf(literal.groupValues[1])
            }
        }
        return names
    }
}
