package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpGenerateSite
import io.github.dotnetsupport.lang.NativeCSharpGenerate
import io.github.dotnetsupport.lang.NativeCSharpInheritedMembers
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.NativeCSharpDiagnostics
import io.github.dotnetsupport.lang.NativeCSharpResolver
import io.github.dotnetsupport.lang.NativeCSharpScopes
import io.github.dotnetsupport.lang.NativeCSharpTypePositions
import io.github.dotnetsupport.lang.TypeKind
import java.util.IdentityHashMap

/**
 * A semantic error of the native pass (CSHARP_PSI_MIGRATION.md, task C4c) with Roslyn's code, message and span, or the gray of a `using`
 * directive nothing needs. [imports]: the namespaces «Import type» offers for it (CS0246 / CS0103 of a type, CS1061 of an extension method).
 */
class CSharpSemanticProblem(
    val code: String, val message: String, val range: TextRange, val unnecessary: Boolean = false,
    val name: String? = null, val imports: List<String> = emptyList(), val extension: Boolean = false,
    /** A compiler warning (D2: CS0162, CS0168, CS0219, CS4014, nullable); [gray]: the code it makes gray, as Rider (unreachable code). */
    val warning: Boolean = false, val gray: TextRange? = null,
) {
    val isError: Boolean get() = !unnecessary && !warning
    val text: String get() = "$code: $message"
    override fun toString(): String = "$range $text"
}

/**
 * The semantic errors C# gets every day, without the language server (task C4c of CSHARP_PSI_MIGRATION.md, the part of D2 / layer 11e
 * that the resolver of C1 / C2 can answer): CS0103 / CS0246 / CS0234 (a name that is nowhere), CS1061 / CS0117 (no such member),
 * CS1501 / CS7036 (no overload takes that many arguments), CS0029 / CS0266 (no implicit conversion between known types), CS0161 (the end
 * of a method that returns a value is reachable), and the `using` directives nothing needs (CS8019, CS8933 — gray, as the server's IDE0005).
 *
 * Precision first: a missing error is fine, a false one is not. Nothing is said where anything involved is unknown — references not all
 * indexed, a base type, alias, imported namespace or `using static` that does not resolve, a partial type (a generator may add to it),
 * a project whose build generates sources, type parameters, `dynamic`, tuples, delegates, a member with a syntax error in it.
 */
class CSharpSemanticChecks(private val resolver: CSharpNameResolver) {
    private val file: CSharpFile = resolver.file
    private val session = resolver.session
    private val found = ArrayList<CSharpSemanticProblem>()
    private val known = IdentityHashMap<Any, Boolean>()
    internal val generated: Boolean by lazy { CSharpSemanticEnvironment.mayGenerateTypes(file) }
    /** The source generators of the project have run and are fresh (D4): a partial type is then complete, its generated parts indexed. */
    private val generatorsKnown: Boolean by lazy { !generated && CSharpSemanticEnvironment.generatedKnown(file) }
    private val broken: List<TextRange> by lazy { brokenRanges() }

    private val warnings = CSharpSemanticWarnings(resolver) { found += it }
    private val generics = CSharpGenericChecks(this, resolver) { found += it }
    private val access = CSharpAccessChecks(resolver, this) { found += it }
    private val inheritance = CSharpInheritanceChecks(resolver, this) { code, message, range -> report(code, message, range) }
    private val overloadChecks = CSharpOverloadChecks(this, resolver)
    private val operators = CSharpOperatorChecks(resolver, this) { code, message, range -> report(code, message, range) }

    fun run(): List<CSharpSemanticProblem> {
        val unit = file.compilationUnit ?: return emptyList()
        if (file.name.endsWith(".csx")) return emptyList()
        // a file outside the projects of the solution is compiled with nothing we know: its extension methods, partial parts, global usings
        if (!inSources()) return emptyList()
        if (!CSharpSemanticEnvironment.referencesComplete(file) || resolver.libraryType(OBJECT) == null) return emptyList()
        if (unit.externs.isNotEmpty()) return emptyList()
        warnings.generatorsKnown = generatorsKnown
        PsiTreeUtil.processElements(unit) { element ->
            ProgressManager.checkCanceled()
            if (element is CSharpSimpleName && !isQuiet(element)) generics.check(element)
            if (element is CSharpElement && !isQuiet(element)) {
                operators.visit(element)
                overloadChecks.visit(element)
                inheritance.check(element)
                when (element) {
                    is CSharpSimpleName -> { checkName(element); checkInstanceFromStatic(element) }
                    is CSharpInvocationExpression -> checkArguments(element)
                    is CSharpBaseObjectCreationExpression -> { checkRequiredMembers(element); checkConstructorArguments(element) }
                    is CSharpVariableDeclaration -> checkDeclaration(element)
                    is CSharpAssignmentExpression -> checkAssignment(element)
                    is CSharpReturnStatement -> checkReturn(element)
                    is CSharpArrowExpressionClause -> checkArrow(element)
                    is CSharpMethodDeclaration -> checkPaths(element)
                    is CSharpAccessorDeclaration -> checkPaths(element)
                    is CSharpExpressionStatement -> warnings.checkNotAwaited(element)
                    is CSharpTypeDeclaration -> { warnings.checkUninitialized(element); checkMissingMembers(element) }
                    is CSharpBlock -> if (isFunctionBody(element)) warnings.checkUnreachable(element)
                }
                access.check(element)
            }
            true
        }
        CSharpDeclarationChecks(resolver, ::isQuiet, this) { code, message, range -> report(code, message, range) }.run()
        CSharpNullableFlow(resolver, ::isQuiet, generatorsKnown) { code, message, range -> warnings.warn(code, message, range) }.run(unit)
        CSharpStatementChecks(resolver, ::isQuiet) { found += it }.run(unit)
        CSharpDefiniteAssignmentChecks(resolver, ::isQuiet) { code, message, range -> report(code, message, range) }.run(unit)
        if (broken.isEmpty()) warnings.checkUnusedLocals()
        if (broken.isEmpty()) CSharpUnusedUsings(resolver).find(unit).let(found::addAll)
        return withoutFlowOfBrokenDeclarations(found).distinctBy { Triple(it.code, it.range, it.message) }
    }

    /**
     * A local with a declaration error is not checked for definite assignment, as Roslyn: no CS0165 next to CS0841 (used before its
     * declaration) on the same name, nor for a name declared twice in the same member (CS0128 / CS0136).
     */
    private fun withoutFlowOfBrokenDeclarations(problems: List<CSharpSemanticProblem>): List<CSharpSemanticProblem> {
        val declarationErrors = problems.filter { it.code == "CS0841" || it.code == "CS0844" || it.code == "CS0128" || it.code == "CS0136" }
        if (declarationErrors.isEmpty()) return problems
        fun name(problem: CSharpSemanticProblem) = QUOTED.find(problem.message)?.groupValues?.get(1)
        fun member(problem: CSharpSemanticProblem) =
            PsiTreeUtil.getParentOfType(file.findElementAt(problem.range.startOffset), CSharpMemberDeclaration::class.java, false)?.textRange
        val broken = declarationErrors.mapNotNull { e -> name(e)?.let { it to member(e) } }.toSet()
        return problems.filterNot { it.code == "CS0165" && (name(it) to member(it)) in broken }
    }

    private val QUOTED = Regex("'([^']+)'")

    private fun inSources(): Boolean {
        val virtualFile = file.viewProvider.virtualFile
        if (virtualFile is com.intellij.testFramework.LightVirtualFile) return true
        return io.github.dotnetsupport.codeanalysis.CSharpSourceScope.of(file.project).contains(virtualFile)
    }

    // ---- where nothing is said

    /** Inside a member with a syntax error, documentation, a directive, `nameof(...)`, a pattern, an extension block, an incomplete member. */
    private fun isQuiet(element: PsiElement): Boolean {
        val range = element.textRange
        if (broken.any { it.intersects(range) }) return true
        var current: PsiElement? = element.parent
        var child: PsiElement = element
        while (current != null && current !is CSharpFile) {
            when {
                current is CSharpStructuredTrivia || current is CSharpIncompleteMember -> return true
                current.elementType == SyntaxKind.ExtensionBlockDeclaration -> return true
                current is CSharpPattern || current is CSharpSwitchLabel || current is CSharpIsPatternExpression -> return true
                current is CSharpBinaryExpression && current.operatorToken?.text.let { it == "is" || it == "as" } && child == current.right -> return true
                current is CSharpArgumentList && isNameof(current.parent) -> return true
                current is CSharpAttributeArgumentList -> return true
            }
            child = current
            current = current.parent
        }
        return false
    }

    private fun isNameof(call: PsiElement?): Boolean =
        call is CSharpInvocationExpression && (call.expression as? CSharpIdentifierName)?.identifier?.text == "nameof"

    /** The members (or the whole file, for a top-level one) with a syntax error: what is typed there is not finished. */
    private fun brokenRanges(): List<TextRange> {
        val ranges = ArrayList<TextRange>()
        for (d in NativeCSharpDiagnostics.of(file)) {
            if (d.isWarning) continue
            val at = file.findElementAt(d.start.coerceIn(0, (file.textLength - 1).coerceAtLeast(0)))
            val member = PsiTreeUtil.getParentOfType(at, CSharpMemberDeclaration::class.java, false)?.takeIf { it !is CSharpBaseTypeDeclaration && it !is CSharpBaseNamespaceDeclaration }
            ranges += member?.textRange ?: file.textRange
        }
        return ranges
    }

    // ---- names: CS0103, CS0246, CS0234, CS1061, CS0117

    private fun checkName(name: CSharpSimpleName) {
        val leaf = name.identifier ?: return
        if (leaf.textLength == 0) return
        val parent = name.parent
        when {
            parent is CSharpMemberAccessExpression && name == parent.nameElement -> parent.expression?.let { checkMember(name, resolver.qualifier(it), parent) }
            parent is CSharpMemberBindingExpression -> resolver.receiverOfBinding(parent)?.let { checkMember(name, CSharpNameResolver.Qualifier.Value(it), parent) }
            parent is CSharpQualifiedName && name == parent.right -> if (!inUsingDirective(name)) parent.left?.let { checkQualified(name, it) }
            parent is CSharpAliasQualifiedName -> {}
            NativeCSharpScopes.isFreeName(name) -> checkFree(name)
        }
    }

    private fun inUsingDirective(element: PsiElement): Boolean = PsiTreeUtil.getParentOfType(element, CSharpUsingDirective::class.java) != null

    private fun checkFree(name: CSharpSimpleName) {
        val leaf = name.identifier ?: return
        val text = leaf.text
        if (text in IMPLICIT || inUsingDirective(name)) return
        if (resolver.syntax.symbolAt(leaf) != null || resolver.resolve(leaf) != null) return
        val parent = name.parent
        val attribute = (parent as? CSharpAttribute)?.takeIf { it.nameElement == name }
        val typeOnly = attribute != null || NativeCSharpTypePositions.isType(name) || (parent as? CSharpQualifiedName)?.left == name
        if (!absent(name, text, typeOnly) || (attribute != null && !absent(name, text + "Attribute", true))) return
        if (generated) return
        val arity = NativeCSharpResolver.arity(name)
        val range = name.identifier!!.textRange.let { if (name is CSharpGenericName) name.textRange else it }
        if (attribute != null) {
            val imports = importsForType(name, text + "Attribute", arity) + importsForType(name, text, arity)
            report("CS0246", "The type or namespace name '${text}Attribute' could not be found (are you missing a using directive or an assembly reference?)", range, text, imports.distinct())
            report("CS0246", "The type or namespace name '$text' could not be found (are you missing a using directive or an assembly reference?)", range, text, imports.distinct())
        } else if (typeOnly) {
            report("CS0246", "The type or namespace name '$text' could not be found (are you missing a using directive or an assembly reference?)", range, text, importsForType(name, text, arity))
        } else {
            report("CS0103", "The name '$text' does not exist in the current context", leaf.textRange, text, importsForType(name, text, arity))
        }
    }

    /**
     * Whether [text] is surely nowhere [at] looks: not a member or nested type of the enclosing types (all their bases known, none of them
     * partial unless only a type is looked for), not a member of a `using static` type, no type or namespace of any arity on the way out,
     * no alias, and everything imported on the way out is known.
     */
    private fun absent(at: CSharpSimpleName, text: String, typeOnly: Boolean): Boolean {
        if (!resolver.importsKnown(at) || resolver.aliasNamed(at, text)) return false
        for (info in resolver.syntax.enclosingTypes(at)) {
            if (!typeOnly && isPartial(info)) return false
            val self = resolver.selfType(info)
            if (!isKnown(self) || has(self, text)) return false
        }
        if (!typeOnly) for (static in resolver.staticTypes(at)) if (!isKnown(static) || has(static, text)) return false
        for (arity in 0..MAX_ARITY) if (resolver.typeOrNamespace(at, text, arity).isNotEmpty()) return false
        if (access.hidesType(at, text)) return false
        // a type of the solution of that name somewhere the lookup did not reach (a stub not yet in the index of this file): no verdict
        return true
    }

    /** `A.B` of a type name: CS0234 when `A` is a namespace that has no type or namespace `B`. */
    private fun checkQualified(name: CSharpSimpleName, left: PsiElement) {
        val leaf = name.identifier ?: return
        if (resolver.resolve(leaf) != null) return
        val qualifier = resolver.qualifier(left) as? CSharpNameResolver.Qualifier.Namespace ?: return
        if (!inNamespaceAbsent(qualifier.name, leaf.text) || generated) return
        report("CS0234", "The type or namespace name '${leaf.text}' does not exist in the namespace '${qualifier.name}' (are you missing an assembly reference?)",
            leaf.textRange, leaf.text, importsForType(name, leaf.text, NativeCSharpResolver.arity(name)))
    }

    private fun inNamespaceAbsent(namespace: String, text: String): Boolean {
        for (arity in 0..MAX_ARITY) if (resolver.typesIn(namespace, text, arity).isNotEmpty()) return false
        if (access.hidesTypeIn(namespace, text)) return false
        return !session.namespaceExists(CSharpNameResolver.join(namespace, text), resolver.assemblies)
    }

    /** `a.B`, `a?.B`, `T.B`, `N.B` in an expression: CS1061 / CS0117 / CS0234 when nothing of that name is there. */
    private fun checkMember(name: CSharpSimpleName, qualifier: CSharpNameResolver.Qualifier?, access: CSharpExpression) {
        val leaf = name.identifier ?: return
        val text = leaf.text
        if (qualifier == null || resolver.resolve(leaf) != null) return
        when (qualifier) {
            is CSharpNameResolver.Qualifier.Namespace -> {
                if (!inNamespaceAbsent(qualifier.name, text) || generated) return
                report("CS0234", "The type or namespace name '$text' does not exist in the namespace '${qualifier.name}' (are you missing an assembly reference?)",
                    access.textRange, text, importsForType(name, text, NativeCSharpResolver.arity(name)))
            }
            is CSharpNameResolver.Qualifier.Type -> {
                // extension methods are not called on a type (C# 14 static extension members aside, which nothing uses yet)
                val type = qualifier.type
                if (!checkable(type) || has(type, text)) return
                val shown = CSharpTypeDisplay.display(type, qualified = false) ?: return
                report("CS0117", "'$shown' does not contain a definition for '$text'", leaf.textRange)
            }
            is CSharpNameResolver.Qualifier.Value -> {
                val type = qualifier.type
                if (!checkable(type) || has(type, text) || extensionNamed(text, name, type)) return
                val shown = CSharpTypeDisplay.display(type, qualified = false) ?: return
                report("CS1061", "'$shown' does not contain a definition for '$text' and no accessible extension method '$text' accepting a first argument of type '$shown' " +
                    "could be found (are you missing a using directive or an assembly reference?)", leaf.textRange, text, importsForExtension(name, text, type), extension = true)
            }
            is CSharpNameResolver.Qualifier.ValueOrType -> {}
        }
    }

    /** A type whose members are all known: of the solution (not partial, bases known) or of an assembly, not `object`, a tuple, a delegate, an array, a type parameter. */
    internal fun checkable(type: SemanticType): Boolean = when (type) {
        is SemanticType.Source -> !isPartial(type.info) && type.info.kind != TypeKind.DELEGATE && type.info.kind != null && isKnown(type)
        is SemanticType.Library -> type.type.fullName != OBJECT && !type.type.fullName.startsWith("System.ValueTuple") && type.type.kind != IndexedTypeKind.DELEGATE &&
            type.type.fullName != "System.Delegate" && type.type.fullName != "System.MulticastDelegate" && !type.type.fullName.startsWith("System.Nullable") && isKnown(type)
        else -> false
    }

    internal fun isPartial(info: io.github.dotnetsupport.lang.TypeInfo): Boolean = !generatorsKnown && info.parts.any { "partial" in it.modifiers }

    /**
     * Whether everything [type] derives from resolves (task C4c): the base list of every part of a type of the solution, the base classes and
     * interfaces of a type of an assembly, all the way up. A base that is not there may bring any member.
     */
    internal fun isKnown(type: SemanticType, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH) return false
        return when (type) {
            is SemanticType.ArrayOf -> true
            is SemanticType.Parameter -> false
            is SemanticType.Library -> libraryKnown(type.type, depth)
            is SemanticType.Source -> known.getOrPut(type.info.key) {
                known[type.info.key] = true // a cycle of bases is an error of its own, not an unknown
                type.info.parts.all { part ->
                    val declaration = part.element() as? CSharpBaseTypeDeclaration ?: return@all part.element() is CSharpDelegateDeclaration
                    val owner = (declaration.containingFile as? CSharpFile)?.let(session::reachable) ?: return@all false
                    declaration.baseList?.types.orEmpty().all { base -> base.type?.let(owner::resolveType)?.let { isKnown(it, depth + 1) } == true }
                }
            }
        }
    }

    private fun libraryKnown(type: IndexedType, depth: Int): Boolean = known.getOrPut(type) {
        if (depth > MAX_DEPTH) return@getOrPut false
        // an interface the index does not have, with every reference of the project indexed, is a non-public one of the assembly of the type
        // (WPF's IAddChildInternal, IHaveResources, DUCE.IResource): nothing of it can be named or reached from outside, so it hides nothing
        val bases = listOfNotNull(type.baseType) + type.interfaces.filter { resolver.assemblies.resolve(it) != null }
        bases.all { reference -> resolver.assemblies.resolve(reference)?.let { libraryKnown(it, depth + 1) } == true }
    }

    /** Whether [type] has a member or a nested type named [text] of any arity, inherited ones included (`object`'s for an interface). */
    internal fun has(type: SemanticType, text: String, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH) return true
        return when (type) {
            is SemanticType.Parameter -> true
            is SemanticType.ArrayOf -> ARRAY_TYPES.any { name -> resolver.libraryType(name)?.let { has(it, text, depth + 1) } ?: true }
            is SemanticType.Source -> {
                val members = resolver.syntax.membersOf(type.info)
                if (members.keys.any { it == text || it.startsWith("$text<") || it.startsWith("$text`") }) return true
                if (type.info.kind == TypeKind.RECORD || type.info.kind == TypeKind.RECORD_STRUCT) if (text in RECORD_MEMBERS) return true
                if (type.info.kind == TypeKind.DELEGATE) return true
                // the bases as their declarations resolve them: the syntactic map above finds a base by its simple name as this file sees it,
                // which may be another type of that name (a DTO `StressTest` imported here, not the entity's base, E-190)
                if (resolver.baseTypes(type).any { (it !is SemanticType.Source || it.info.key != type.info.key) && has(it, text, depth + 1) }) return true
                type.info.kind == TypeKind.INTERFACE && resolver.libraryType(OBJECT)?.let { has(it, text, depth + 1) } != false
            }
            is SemanticType.Library -> {
                val t = type.type
                if (t.kind == IndexedTypeKind.DELEGATE) return true
                if (session.libraryMembers(resolver.assemblies, t)[text].orEmpty().isNotEmpty()) return true
                if ((t.nestedTypes + t.hiddenNestedTypes).any { it.simpleName == text }) return true
                if (session.baseTypes(resolver.assemblies, t).any { base -> (base.type.nestedTypes + base.type.hiddenNestedTypes).any { it.simpleName == text } }) return true
                t.kind == IndexedTypeKind.INTERFACE && t.fullName != OBJECT && resolver.libraryType(OBJECT)?.let { has(it, text, depth + 1) } != false
            }
        }
    }

    /**
     * Whether something that may be an extension member named [text] is there: a member of a static class of the assemblies in a namespace
     * [site] sees (extension methods, and what C# 14 extension blocks compile to), any extension method of the solution of that name. An
     * extension method of a type parameter whose constraints [receiver] does not satisfy is no excuse (`day.AddEndpointFilter`).
     */
    private fun extensionNamed(text: String, site: PsiElement, receiver: SemanticType): Boolean {
        if (session.sourceExtensions(text).isNotEmpty()) return true
        val visible = resolver.visibleNamespaces(site)
        val all = resolver.assemblies.membersNamed(text) + resolver.assemblies.membersNamed("get_$text")
        // extension methods, and the accessors of C# 14 extension properties (static methods of a static class, `get_X`)
        return all.any {
            it.type.isStatic && it.type.namespace in visible && (it.kind == IndexedMemberKind.EXTENSION_METHOD && resolver.receiverFits(CSharpSymbol.LibraryMember(it), receiver) ||
                it.name.startsWith("get_"))
        }
    }

    // ---- «Import type»

    /** The namespaces with a public top-level type named [text] of [arity], of the solution and of the assemblies, that [at] does not import. */
    private fun importsForType(at: PsiElement, text: String, arity: Int): List<String> {
        val visible = resolver.visibleNamespaces(at)
        val found = LinkedHashSet<String>()
        for (part in resolver.syntax.typeParts(text)) {
            val namespace = part.namespace ?: continue
            if (part.arity == arity && namespace.isNotEmpty() && namespace !in visible) found += namespace
        }
        for (type in resolver.assemblies.typesNamed(text)) {
            if (type.declaringType != null || type.ownArity != arity || type.isHidden || type.namespace.isEmpty() || type.namespace in visible) continue
            found += type.namespace
        }
        return found.sortedWith(compareBy<String> { !(it == "System" || it.startsWith("System.")) }.thenBy { it })
    }

    /** The namespaces with an extension method [text] that takes a receiver of [receiver], that [at] does not import. */
    private fun importsForExtension(at: PsiElement, text: String, receiver: SemanticType): List<String> {
        val visible = resolver.visibleNamespaces(at)
        val found = LinkedHashSet<String>()
        for (member in resolver.assemblies.membersNamed(text)) {
            if (member.kind != IndexedMemberKind.EXTENSION_METHOD || member.type.namespace in visible || member.isHidden) continue
            if (resolver.receiverFits(CSharpSymbol.LibraryMember(member), receiver)) found += member.type.namespace
        }
        return found.sortedWith(compareBy<String> { !(it == "System" || it.startsWith("System.")) }.thenBy { it })
    }

    // ---- arguments: CS1501, CS7036

    private fun checkArguments(call: CSharpInvocationExpression) {
        val callee = when (val e = call.expression) {
            is CSharpIdentifierName, is CSharpGenericName -> e as CSharpSimpleName
            is CSharpMemberAccessExpression -> e.nameElement?.takeIf { it is CSharpIdentifierName || it is CSharpGenericName }
            is CSharpMemberBindingExpression -> e.nameElement?.takeIf { it is CSharpIdentifierName || it is CSharpGenericName }
            else -> null
        } ?: return
        val leaf = callee.identifier ?: return
        val text = leaf.text
        if (text == "nameof" || resolver.syntax.symbolAt(leaf) != null) return
        val arguments = call.argumentList?.arguments ?: return
        if (arguments.any { it.expression?.text == "__arglist" }) return
        val explicitArity = (callee as? CSharpGenericName)?.typeArgumentList?.arguments?.size
        val candidates = overloads(callee, text, explicitArity) ?: return
        if (candidates.isEmpty()) return
        val signatures = candidates.map { resolver.signature(it, false) ?: return }
        if (arguments.any { it.nameColon != null }) return checkNamedArguments(candidates, signatures, arguments, leaf)
        if (signatures.any { resolver.fits(it, arguments) }) {
            if (candidates.size == 1) checkArgumentTypes(candidates.single(), signatures.single(), arguments)
            return
        }
        val count = arguments.size
        val single = signatures.singleOrNull()
        if (single != null && count < single.count { !it.optional && !it.isParams }) {
            val missing = single.drop(count).firstOrNull { !it.optional && !it.isParams } ?: return
            val shown = methodDisplay(candidates.single()) ?: return
            report("CS7036", "There is no argument given that corresponds to the required parameter '${missing.name}' of '$shown'", leaf.textRange)
        } else {
            report("CS1501", "No overload for method '$text' takes $count arguments", leaf.textRange)
        }
    }

    /**
     * `new Point(1)` of a type of the solution (DEV_JOURNEY 5.5, 0.1.100): CS7036 when its one constructor needs more arguments, CS1729 when
     * no constructor takes that many. Positional arguments only; not for partial types (a generator may add a constructor), nor for a
     * record given one argument (its copy constructor).
     */
    private fun checkConstructorArguments(creation: CSharpBaseObjectCreationExpression) {
        if (creation !is CSharpObjectCreationExpression) return
        val arguments = creation.argumentList?.arguments ?: return
        if (arguments.any { it.nameColon != null || it.expression?.text == "__arglist" }) return
        val type = resolver.typeOf(creation) as? SemanticType.Source ?: return
        val kind = type.info.kind
        if (isPartial(type.info) || kind != TypeKind.CLASS && kind != TypeKind.RECORD && kind != TypeKind.STRUCT && kind != TypeKind.RECORD_STRUCT) return
        val declarations = type.info.parts.map { it.element() as? CSharpTypeDeclaration ?: return }
        if (declarations.any { d -> d.modifiers.any { it.text == "abstract" || it.text == "static" } }) return
        val constructors: List<PsiElement> = declarations.flatMap { d ->
            d.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } } + listOfNotNull(d.takeIf { it.parameterList != null })
        }
        val count = arguments.size
        val struct = kind == TypeKind.STRUCT || kind == TypeKind.RECORD_STRUCT
        if (count == 0 && (struct || constructors.isEmpty())) return
        if ((kind == TypeKind.RECORD || kind == TypeKind.RECORD_STRUCT) && count == 1) return
        val lists = constructors.map { c -> ((c as? CSharpConstructorDeclaration)?.parameterList ?: (c as CSharpTypeDeclaration).parameterList)?.parameters.orEmpty() }
        fun required(list: List<CSharpParameter>) = list.count { p -> p.default == null && p.modifiers.none { it.text == "params" } }
        if (lists.any { list -> count >= required(list) && (count <= list.size || list.lastOrNull()?.modifiers?.any { it.text == "params" } == true) }) return
        val at = creation.type?.textRange ?: return
        val single = constructors.singleOrNull()
        val list = lists.singleOrNull()
        if (single != null && list != null && count < required(list)) {
            val missing = list.drop(count).firstOrNull { p -> p.default == null && p.modifiers.none { it.text == "params" } } ?: return
            val shown = constructorDisplay(single, list) ?: return
            report("CS7036", "There is no argument given that corresponds to the required parameter '${missing.identifier?.text}' of '$shown'", at)
        } else {
            val name = type.info.qualifiedName.substringAfterLast('.')
            report("CS1729", "'$name' does not contain a constructor that takes $count arguments", at)
        }
    }

    /** `Point.Point(int, int)`: a constructor (or a primary one) as Roslyn's messages write it. */
    private fun constructorDisplay(constructor: PsiElement, parameters: List<CSharpParameter>): String? {
        val owner = constructor as? CSharpTypeDeclaration ?: PsiTreeUtil.getParentOfType(constructor, CSharpBaseTypeDeclaration::class.java) ?: return null
        val typeName = declaringName(owner) ?: return null
        val shown = parameters.map { p ->
            val type = p.type?.let(resolver::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
            (p.modifiers.map { it.text } + type).joinToString(" ")
        }
        return "$typeName.${owner.identifier?.text}(${shown.joinToString(", ")})"
    }

    /** With named arguments, only the one method of its name: CS7036 for a required parameter no argument gives. */
    private fun checkNamedArguments(candidates: List<CSharpSymbol>, signatures: List<List<CSharpNameResolver.Parameter>>, arguments: List<CSharpArgument>, leaf: PsiElement) {
        val parameters = signatures.singleOrNull() ?: return
        val names = arguments.mapNotNull { it.nameColon?.nameElement?.identifier?.text }
        if (names.any { n -> parameters.none { it.name == n } }) return
        val positional = arguments.takeWhile { it.nameColon == null }.size
        if (positional > parameters.size) return
        val missing = parameters.drop(positional).firstOrNull { !it.optional && !it.isParams && it.name !in names } ?: return
        val shown = methodDisplay(candidates.single()) ?: return
        report("CS7036", "There is no argument given that corresponds to the required parameter '${missing.name}' of '$shown'", leaf.textRange)
    }

    /**
     * CS1503 for the one method of the name whose number of parameters fits: each argument whose type surely does not convert to its
     * parameter (the element type in the expanded form of `params`). Not for parameters of a type parameter of the method (inference
     * decides those), `ref` / `out` / `in`, lambdas and method groups.
     */
    internal fun checkArgumentTypes(symbol: CSharpSymbol, parameters: List<CSharpNameResolver.Parameter>, arguments: List<CSharpArgument>) {
        if (arguments.any { it.refKindKeyword != null } || parameters.any { it.byRef } || hasModifiers(symbol)) return
        val overloads = resolver.overloads
        val last = parameters.lastOrNull()
        val expanded = last != null && last.isParams && (arguments.size != parameters.size || arguments.last().expression?.let { e ->
            val type = last.type() ?: return
            overloads.argument(e, resolver.typeOf(e), type) == CSharpNameResolver.Conversion.NONE
        } == true)
        for ((i, argument) in arguments.withIndex()) {
            val expression = argument.expression ?: continue
            val value = unparenthesized(expression)
            if (value is CSharpAnonymousFunctionExpression || isMethodGroup(value)) continue
            val parameter = if (expanded && i >= parameters.size - 1) last else parameters.getOrNull(i)
            var target = parameter?.type() ?: continue
            if (expanded && parameter === last) target = (target as? SemanticType.ArrayOf)?.element ?: continue
            if (mentionsTypeParameter(target)) continue
            val null_ = value.elementType == SyntaxKind.NullLiteralExpression
            val type = if (null_) null else resolver.typeOf(value) ?: continue
            if (overloads.argument(value, type, target) != CSharpNameResolver.Conversion.NONE) continue
            val from = if (null_) "<null>" else CSharpTypeDisplay.display(type) ?: continue
            val to = CSharpTypeDisplay.display(target) ?: continue
            report("CS1503", "Argument ${i + 1}: cannot convert from '$from' to '$to'", expression.textRange)
        }
    }

    private fun hasModifiers(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.parameterList?.parameters?.any { p -> p.modifiers.any { it.text != "params" } } ?: true
        is CSharpSymbol.LibraryMember -> symbol.member.parameters.any { it.isByReference }
        else -> true
    }

    private fun isMethodGroup(e: CSharpExpression): Boolean {
        val name = when (e) {
            is CSharpSimpleName -> e
            is CSharpMemberAccessExpression -> e.nameElement
            else -> return false
        } ?: return false
        val symbols = name.identifier?.let(resolver::resolve)?.symbols ?: return true
        return symbols.isEmpty() || symbols.any { resolver.isMethod(it) }
    }

    private fun mentionsTypeParameter(type: SemanticType?): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> true
        is SemanticType.ArrayOf -> mentionsTypeParameter(type.element)
        is SemanticType.Library -> type.arguments.any(::mentionsTypeParameter)
        is SemanticType.Source -> type.arguments.any(::mentionsTypeParameter)
    }

    /**
     * Every method named [text] the call at [callee] chooses from — all overloads of the whole hierarchy, as overload resolution sees them
     * when none of the nearest fits. Null when that set is not surely complete: a member of another kind, an unknown or partial type,
     * extension methods of that name, a local function or a delegate.
     */
    internal fun overloads(callee: CSharpSimpleName, text: String, explicitArity: Int?, extensions: Boolean = true): List<CSharpSymbol>? {
        val parent = callee.parent
        var static = false
        val type: SemanticType = when {
            parent is CSharpMemberAccessExpression -> when (val q = parent.expression?.let(resolver::qualifier)) {
                is CSharpNameResolver.Qualifier.Type -> q.type.also { static = true }
                is CSharpNameResolver.Qualifier.Value -> q.type
                else -> return null
            }
            parent is CSharpMemberBindingExpression -> resolver.receiverOfBinding(parent) ?: return null
            else -> {
                // a simple name: the nearest enclosing type that has it; `using static` and the rest are left alone
                val owner = resolver.syntax.enclosingTypes(callee).firstOrNull { has(resolver.selfType(it), text) } ?: return null
                resolver.selfType(owner)
            }
        }
        // extension methods are looked up for `x.M(...)` only, never for a simple name (C# §12.8.10.3)
        val reduced = parent is CSharpMemberAccessExpression || parent is CSharpMemberBindingExpression
        if (!checkable(type) || extensions && reduced && !static && extensionNamed(text, callee, type)) return null
        val found = ArrayList<CSharpSymbol>()
        return if (collect(type, text, found, 0, explicitArity)) found else null
    }

    /** [arity]: the number of the type arguments written (`M<int>(...)`), only methods of as many type parameters are candidates. */
    private fun collect(type: SemanticType, text: String, into: MutableList<CSharpSymbol>, depth: Int, arity: Int? = null): Boolean {
        if (depth > MAX_DEPTH) return false
        when (type) {
            is SemanticType.Source -> {
                if (isPartial(type.info)) return false
                for (part in type.info.parts) {
                    var ok = true
                    part.members { name, member ->
                        if (name != text) return@members
                        for (target in member.targets()) {
                            if (target !is CSharpMethodDeclaration) { ok = false; continue }
                            if (arity == null || (target.typeParameterList?.parameters?.size ?: 0) == arity) into += CSharpSymbol.SourceMember(target, member, type)
                        }
                    }
                    if (!ok) return false
                }
                // a class does not get the members of its interfaces: `repo.AddAsync(order)` of an implementation without the interface's
                // `ct = default` is CS7036, whatever the interface says (DEV_JOURNEY 5.5)
                val self = type.info.kind == TypeKind.INTERFACE
                for (base in resolver.baseTypes(type)) {
                    val face = base is SemanticType.Source && base.info.kind == TypeKind.INTERFACE || base is SemanticType.Library && base.type.kind == IndexedTypeKind.INTERFACE
                    if (face && !self) continue
                    if (!collect(base, text, into, depth + 1, arity)) return false
                }
                return true
            }
            is SemanticType.Library -> {
                for (inherited in session.libraryMembers(resolver.assemblies, type.type)[text].orEmpty()) {
                    val member = inherited.member
                    if (member.kind != IndexedMemberKind.METHOD) return false
                    if (arity == null || member.arity == arity) into += CSharpSymbol.LibraryMember(member, resolver.declaringArguments(type, inherited.from))
                }
                // an interface gets object's members too
                if (type.type.kind == IndexedTypeKind.INTERFACE) resolver.libraryType(OBJECT)?.let { return collect(it, text, into, depth + 1, arity) }
                return true
            }
            else -> return false
        }
    }

    /** `Calc.Add(int, int)` as Roslyn's messages write a method: the type without its namespace, the types of the parameters. */
    internal fun methodDisplay(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.SourceMember -> {
            val method = symbol.element as? CSharpMethodDeclaration
            val owner = method?.let { PsiTreeUtil.getParentOfType(it, CSharpBaseTypeDeclaration::class.java) }
            val typeName = owner?.let { declaringName(it) }
            val parameters = method?.parameterList?.parameters?.map { p ->
                val type = p.type?.let(resolver::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
                (p.modifiers.map { it.text } + type).joinToString(" ")
            }
            val typeParameters = method?.typeParameterList?.parameters?.map { it.text.trim() }?.joinToString(", ", "<", ">").orEmpty()
            if (typeName == null || parameters == null) null else "$typeName.${method.identifier?.text}$typeParameters(${parameters.joinToString(", ")})"
        }
        is CSharpSymbol.LibraryMember -> {
            val m = symbol.member
            if (m.type.arity > 0 || m.parameters.any { it.isByReference || it.isParams || it.isOptional || it.hasDefault }) null
            else {
                val parameters = m.parameters.map { p -> resolver.fromRef(p.typeRef, emptyList())?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null }
                "${m.type.name}.${m.name}(${parameters.joinToString(", ")})"
            }
        }
        else -> null
    }

    /** `Outer.Inner` of a type declaration of the solution; null inside a generic type. */
    internal fun declaringName(declaration: CSharpBaseTypeDeclaration): String? {
        val names = ArrayList<String>()
        var at: PsiElement? = declaration
        while (at is CSharpBaseTypeDeclaration) {
            if ((at as? CSharpTypeDeclaration)?.typeParameterList != null) return null
            names += at.identifier?.text ?: return null
            at = at.parent
        }
        return names.asReversed().joinToString(".")
    }

    // ---- conversions: CS0029, CS0266

    private fun checkDeclaration(declaration: CSharpVariableDeclaration) {
        val written = declaration.type ?: return
        if (resolver.isVar(written) || declaration.parent is CSharpFixedStatement) return
        if (written is CSharpIdentifierName && written.identifier?.text in IMPLICIT) return
        val target = resolver.resolveType(written) ?: return
        for (variable in declaration.variables) {
            if (variable.argumentList != null) continue
            variable.initializer?.value?.let { checkConversion(it, target) }
        }
    }

    private fun checkAssignment(assignment: CSharpAssignmentExpression) {
        if (assignment.operatorToken?.text != "=") return
        val left = assignment.left ?: return
        val right = assignment.right ?: return
        if (left is CSharpTupleExpression || left is CSharpDeclarationExpression || NativeCSharpScopes.isObjectInitializer(assignment.parent)) return
        // only what is surely a variable of a written type: a local, a parameter, a field or a property of a type of the solution or an assembly
        val name = when (left) {
            is CSharpIdentifierName -> left
            is CSharpMemberAccessExpression -> left.nameElement
            else -> null
        } ?: return
        val symbol = name.identifier?.let(resolver::resolve)?.single ?: return
        val writable = when (symbol) {
            is CSharpSymbol.Local -> symbol.symbol.kind == LocalSymbolKind.LOCAL || symbol.symbol.kind == LocalSymbolKind.PARAMETER
            is CSharpSymbol.SourceMember -> symbol.element is CSharpBasePropertyDeclaration || symbol.element is CSharpBaseFieldDeclaration || symbol.element is CSharpVariableDeclarator
            is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.PROPERTY || symbol.member.kind == IndexedMemberKind.FIELD
            else -> false
        }
        if (!writable) return
        // a local declared with `var`: its type is its initializer's, a false conversion there would be one more guess
        if (symbol is CSharpSymbol.Local && symbol.symbol.kind == LocalSymbolKind.LOCAL) {
            val declared = (symbol.symbol.declaration.parent?.parent as? CSharpVariableDeclaration)?.type ?: return
            if (resolver.isVar(declared)) return
        }
        val target = resolver.valueType(symbol) ?: return
        checkConversion(right, target)
    }

    private fun checkReturn(statement: CSharpReturnStatement) {
        val expression = statement.expression ?: return
        returnTarget(statement)?.let { checkConversion(expression, it) }
    }

    /** The body of a method, accessor, constructor, operator, local function or lambda: where reachability starts. */
    private fun isFunctionBody(block: CSharpBlock): Boolean = when (val parent = block.parent) {
        is CSharpBaseMethodDeclaration, is CSharpAccessorDeclaration, is CSharpLocalFunctionStatement -> true
        is CSharpAnonymousFunctionExpression -> parent.block == block
        else -> false
    }

    private fun checkArrow(arrow: CSharpArrowExpressionClause) {
        val expression = arrow.expression ?: return
        val target = when (val owner = arrow.parent) {
            is CSharpMethodDeclaration, is CSharpLocalFunctionStatement -> functionResult(owner)
            is CSharpPropertyDeclaration -> owner.type?.let(resolver::resolveType)
            is CSharpAccessorDeclaration -> if (owner.keyword?.text == "get") (owner.parent?.parent as? CSharpBasePropertyDeclaration)?.type?.let(resolver::resolveType) else null
            else -> null
        } ?: return
        checkConversion(expression, target)
    }

    /** The type a `return` of [statement] must convert to: of the method, local function or getter around it; null in a lambda, an iterator, `async` without `Task<T>`. */
    private fun returnTarget(statement: PsiElement): SemanticType? {
        var at: PsiElement? = statement.parent
        while (at != null && at !is CSharpFile) {
            when (at) {
                is CSharpAnonymousFunctionExpression -> return null
                is CSharpMethodDeclaration, is CSharpLocalFunctionStatement -> return functionResult(at)
                is CSharpAccessorDeclaration -> {
                    if (at.keyword?.text != "get" || hasYield(at)) return null
                    return (at.parent?.parent as? CSharpBasePropertyDeclaration)?.type?.let(resolver::resolveType)
                }
                is CSharpMemberDeclaration -> return null
            }
            at = at.parent
        }
        return null
    }

    private fun functionResult(function: PsiElement): SemanticType? {
        val (returnType, modifiers) = when (function) {
            is CSharpMethodDeclaration -> function.returnType to function.modifiers
            is CSharpLocalFunctionStatement -> function.returnType to function.modifiers
            else -> return null
        }
        if (returnType == null || returnType is CSharpRefType || hasYield(function)) return null
        val type = resolver.resolveType(returnType) ?: return null
        if (modifiers.none { it.text == "async" }) return type
        // `async Task<T>`: a `return` gives the T
        val library = type as? SemanticType.Library ?: return null
        return if (library.type.fullName == "System.Threading.Tasks.Task`1" || library.type.fullName == "System.Threading.Tasks.ValueTask`1") library.arguments.singleOrNull() else null
    }

    private fun hasYield(function: PsiElement): Boolean {
        var yields = false
        PsiTreeUtil.processElements(function) { e ->
            if (e is CSharpYieldStatement) yields = true
            !yields
        }
        return yields
    }

    /**
     * CS0029 / CS0266 when [expression] has a known type that surely does not convert to [target]: between the special types (numbers,
     * `char`, `bool`, `string`) and their nullable forms, and any known type to `string`. Constants of `int` / `long` (they convert to the
     * smaller integers when they fit), target-typed expressions and anything with a user-defined conversion are left alone.
     */
    private fun checkConversion(expression: CSharpExpression, target: SemanticType) {
        val value = unparenthesized(expression)
        // target-typed: a switch expression converts arm by arm; `?:` with a natural type (both branches of one type) converts by it, as Roslyn
        if (value is CSharpSwitchExpression) return value.arms.forEach { arm -> arm.expression?.let { checkConversion(it, target) } }
        if (value is CSharpConditionalExpression) return checkConditional(value, target)
        if (isTargetTyped(value)) return
        val source = resolver.typeOf(value) ?: return
        val code = conversionError(source, target, value) ?: return
        val from = CSharpTypeDisplay.display(source) ?: return
        val to = CSharpTypeDisplay.display(target) ?: return
        if (from == to) return
        if (code == "CS0266") report(code, "Cannot implicitly convert type '$from' to '$to'. An explicit conversion exists (are you missing a cast?)", expression.textRange)
        else report(code, "Cannot implicitly convert type '$from' to '$to'", expression.textRange)
    }

    private fun checkConditional(conditional: CSharpConditionalExpression, target: SemanticType) {
        val branches = listOf(conditional.whenTrue ?: return, conditional.whenFalse ?: return).map(::unparenthesized)
        if (branches.any { isTargetTyped(it) || it is CSharpSwitchExpression || it is CSharpConditionalExpression }) return
        val types = branches.map { resolver.typeOf(it) ?: return }
        val natural = CSharpTypeDisplay.display(types[0]) ?: return
        if (CSharpTypeDisplay.display(types[1]) != natural) return
        val code = conversionError(types[0], target, conditional) ?: return
        val to = CSharpTypeDisplay.display(target) ?: return
        if (natural == to) return
        if (code == "CS0266") report(code, "Cannot implicitly convert type '$natural' to '$to'. An explicit conversion exists (are you missing a cast?)", conditional.textRange)
        else report(code, "Cannot implicitly convert type '$natural' to '$to'", conditional.textRange)
    }

    // ---- CS0120

    /**
     * A simple name in a static method, property, operator or field initializer that stands for an instance field, property, method or
     * event of the same type declaration: CS0120. Not where C# says something else (a `static` lambda or local function, an instance
     * field initializer, a constructor initializer), nor for a member named as its type (`Color Color`).
     */
    private fun checkInstanceFromStatic(name: CSharpSimpleName) {
        if (!NativeCSharpScopes.isFreeName(name) || NativeCSharpTypePositions.isType(name)) return
        val parent = name.parent
        if (parent is CSharpInvocationExpression && parent.expression != name) return
        val leaf = name.identifier ?: return
        if (resolver.syntax.symbolAt(leaf) != null) return
        val context = staticContext(name) ?: return
        if (CSharpInheritanceChecks.initializerOf(name) != null) return // CS0236, as Roslyn
        if (CSharpInheritanceChecks.initializerOf(name) != null) return // CS0236, as Roslyn
        val symbols = resolver.resolve(leaf)?.symbols?.takeIf { it.isNotEmpty() } ?: return
        if (symbols.any { it !is CSharpSymbol.SourceMember || resolver.overloads.isStatic(it) != false }) return
        val members = symbols.map { (it as CSharpSymbol.SourceMember).element }
        if (members.any { it.containingFile != file }) return
        val owner = PsiTreeUtil.getParentOfType(context, CSharpBaseTypeDeclaration::class.java) ?: return
        for (element in members) {
            if (element !is CSharpMethodDeclaration && element !is CSharpPropertyDeclaration && element !is CSharpVariableDeclarator && element !is CSharpEventDeclaration &&
                element !is CSharpBaseFieldDeclaration) return
            if (PsiTreeUtil.getParentOfType(element, CSharpBaseTypeDeclaration::class.java) != owner) return
            val type = when (element) {
                is CSharpPropertyDeclaration -> element.type
                is CSharpEventDeclaration -> element.type
                is CSharpVariableDeclarator -> (element.parent as? CSharpVariableDeclaration)?.type
                is CSharpBaseFieldDeclaration -> element.declaration?.type
                else -> null
            }
            if (type != null && type.text.substringBefore('<').substringAfterLast('.').trim() == leaf.text) return
        }
        // a static context drops instance methods before overload resolution: a static overload anywhere may be the one called
        if (members.any { it is CSharpMethodDeclaration }) {
            if (owner.modifiers.any { it.text == "partial" } || owner.baseList != null) return
            val sameName = (owner as? CSharpTypeDeclaration)?.members.orEmpty().filterIsInstance<CSharpMethodDeclaration>().filter { it.identifier?.text == leaf.text }
            if (sameName.any { m -> m.modifiers.any { it.text == "static" } }) return
        }
        val shown = instanceMemberDisplay(members.first(), owner) ?: return
        report("CS0120", "An object reference is required for the non-static field, method, or property '$shown'", leaf.textRange)
    }

    /** The static member [element] is in; null in an instance one, or where C# reports something else. */
    private fun staticContext(element: PsiElement): PsiElement? {
        var at: PsiElement? = element.parent
        while (at != null && at !is CSharpFile) {
            when (at) {
                is CSharpAnonymousFunctionExpression -> if (at.modifiers.any { it.text == "static" }) return null
                is CSharpLocalFunctionStatement -> if (at.modifiers.any { it.text == "static" }) return null
                is CSharpConstructorInitializer, is CSharpAttributeList, is CSharpBaseTypeDeclaration -> return null
                is CSharpBaseFieldDeclaration -> return at.takeIf { at.modifiers.any { it.text == "static" || it.text == "const" } }
                is CSharpOperatorDeclaration, is CSharpConversionOperatorDeclaration -> return at
                is CSharpMemberDeclaration -> return at.takeIf { at.modifiers.any { it.text == "static" } && at !is CSharpEnumMemberDeclaration }
            }
            at = at.parent
        }
        return null
    }

    /** `Probe.instance`, `Probe.Helper()`, `N.Outer.Inner.Run(int)`: as Roslyn's CS0120 names the member. */
    private fun instanceMemberDisplay(element: PsiElement, owner: CSharpBaseTypeDeclaration): String? {
        val typeName = declaringName(owner) ?: return null
        // file-scoped namespaces hold their types too
        val namespaces = generateSequence(owner.parent) { it.parent }.filterIsInstance<CSharpBaseNamespaceDeclaration>().toList().asReversed()
        val prefix = namespaces.map { it.nameElement?.text?.filterNot(Char::isWhitespace) ?: return null }.joinToString("") { "$it." }
        return when (element) {
            is CSharpMethodDeclaration -> {
                if (element.typeParameterList != null) return null
                val parameters = element.parameterList?.parameters.orEmpty().map { p ->
                    if (p.modifiers.isNotEmpty()) return null
                    p.type?.let(resolver::resolveType)?.let { CSharpTypeDisplay.display(it) } ?: return null
                }
                "$prefix$typeName.${element.identifier?.text}(${parameters.joinToString(", ")})"
            }
            is CSharpPropertyDeclaration -> "$prefix$typeName.${element.identifier?.text}"
            is CSharpEventDeclaration -> "$prefix$typeName.${element.identifier?.text}"
            is CSharpVariableDeclarator -> "$prefix$typeName.${element.identifier?.text}"
            is CSharpBaseFieldDeclaration -> element.declaration?.variables?.singleOrNull()?.identifier?.text?.let { "$prefix$typeName.$it" }
            else -> null
        }
    }

    private fun unparenthesized(expression: CSharpExpression): CSharpExpression {
        var e = expression
        while (e is CSharpParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    private fun isTargetTyped(e: CSharpExpression): Boolean = e is CSharpAnonymousFunctionExpression || e is CSharpConditionalExpression || e is CSharpSwitchExpression ||
        e is CSharpImplicitObjectCreationExpression || e is CSharpCollectionExpression || e is CSharpThrowExpression || e is CSharpTupleExpression ||
        e is CSharpDefaultExpression || e.elementType == SyntaxKind.NullLiteralExpression || e.elementType == SyntaxKind.DefaultLiteralExpression ||
        e is CSharpInterpolatedStringExpression || e.elementType == SyntaxKind.StackAllocArrayCreationExpression || e.elementType == SyntaxKind.ImplicitStackAllocArrayCreationExpression ||
        e.elementType == SyntaxKind.Utf8StringLiteralExpression

    internal fun conversionError(source: SemanticType, target: SemanticType, value: CSharpExpression): String? {
        val sourceLibrary = source as? SemanticType.Library ?: return if (target.isString() && isPlainNonString(source)) "CS0029" else null
        val targetLibrary = target as? SemanticType.Library ?: return null
        val s = special(sourceLibrary)
        val t = special(targetLibrary)
        if (t == STRING && s == null) return if (isPlainNonString(source)) "CS0029" else null
        if (s == null || t == null) return null
        val sourceNullable = resolver.isNullable(sourceLibrary)
        val targetNullable = resolver.isNullable(targetLibrary)
        val sName = definition(sourceLibrary) ?: return null
        val tName = definition(targetLibrary) ?: return null
        // `T` -> `T?`, `int` -> `long?`: implicit
        if (!sourceNullable && sName == tName) return null
        val numericLike = { n: String -> n in NUMERIC || n == CHAR }
        if (sName == tName) return if (sourceNullable && !targetNullable) "CS0266" else null
        if (numericLike(sName) && numericLike(tName)) {
            if (!sourceNullable && implicitNumeric(sName, tName)) return null
            if (sourceNullable && !targetNullable) return "CS0266"
            if (sourceNullable && implicitNumeric(sName, tName)) return null
            // a constant of `int` / `long` converts to a smaller integer it fits in
            if ((sName == INT || sName == LONG) && maybeConstant(value)) return null
            return "CS0266"
        }
        return "CS0029"
    }

    /** A type no `string` comes of but by a user-defined conversion: a class or struct of the solution or an assembly that declares none, arrays, enums. */
    private fun isPlainNonString(type: SemanticType): Boolean = when (type) {
        is SemanticType.ArrayOf -> true
        is SemanticType.Source -> type.info.kind in listOf(TypeKind.CLASS, TypeKind.STRUCT, TypeKind.RECORD, TypeKind.RECORD_STRUCT, TypeKind.ENUM) && !isPartial(type.info) && isKnown(type) &&
            !hasConversions(type)
        is SemanticType.Library -> type.type.kind != IndexedTypeKind.INTERFACE && type.type.kind != IndexedTypeKind.DELEGATE && type.type.fullName != OBJECT &&
            type.type.fullName != "System.ValueType" && type.type.fullName != "System.Enum" && type.type.fullName != STRING && !resolver.isNullable(type) && isKnown(type) && !hasConversions(type)
        is SemanticType.Parameter -> false
    }

    private fun hasConversions(type: SemanticType, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH) return true
        return when (type) {
            is SemanticType.Library -> (listOf(type.type) + session.baseTypes(resolver.assemblies, type.type).map { it.type }).any { t -> t.members.any { it.name == "op_Implicit" || it.name == "op_Explicit" } }
            is SemanticType.Source -> type.info.parts.any { part -> (part.element() as? CSharpTypeDeclaration)?.members?.any { it is CSharpConversionOperatorDeclaration } != false } ||
                resolver.baseTypes(type).any { hasConversions(it, depth + 1) }
            else -> true
        }
    }

    private fun SemanticType.isString(): Boolean = this is SemanticType.Library && type.fullName == STRING

    /** The special type of [type] or of the `T` of its `Nullable<T>`, null for any other. */
    private fun special(type: SemanticType.Library): String? = definition(type)?.takeIf { it in SPECIAL }

    private fun definition(type: SemanticType.Library): String? =
        if (resolver.isNullable(type)) (type.arguments.singleOrNull() as? SemanticType.Library)?.type?.fullName else type.type.fullName

    private fun implicitNumeric(from: String, to: String): Boolean = from == to || to in (IMPLICIT_NUMERIC[from] ?: emptySet())

    /** A literal, an operation on literals or names that may be constants: what C# may fold to a constant. */
    private fun maybeConstant(e: CSharpExpression): Boolean = when (e) {
        is CSharpLiteralExpression -> true
        is CSharpParenthesizedExpression -> e.expression?.let(::maybeConstant) ?: true
        is CSharpPrefixUnaryExpression -> e.operand?.let(::maybeConstant) ?: true
        is CSharpBinaryExpression -> listOfNotNull(e.left, e.right).all(::maybeConstant)
        is CSharpCastExpression -> e.expression?.let(::maybeConstant) ?: true
        is CSharpCheckedExpression -> true
        is CSharpIdentifierName, is CSharpMemberAccessExpression -> {
            val name = (e as? CSharpMemberAccessExpression)?.nameElement ?: e as CSharpSimpleName
            when (val symbol = name.identifier?.let(resolver::resolve)?.single) {
                is CSharpSymbol.Local -> isConstLocal(symbol)
                is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.CONSTANT
                is CSharpSymbol.SourceMember -> (PsiTreeUtil.getParentOfType(symbol.element, CSharpBaseFieldDeclaration::class.java, false) ?: symbol.element as? CSharpMemberDeclaration)
                    ?.modifiers?.any { it.text == "const" } != false
                else -> true
            }
        }
        else -> false
    }

    private fun isConstLocal(local: CSharpSymbol.Local): Boolean {
        val statement = PsiTreeUtil.getParentOfType(local.symbol.declaration, CSharpLocalDeclarationStatement::class.java) ?: return local.symbol.kind != LocalSymbolKind.PARAMETER
        return statement.modifiers.any { it.text == "const" }
    }

    // ---- CS0161

    private fun checkPaths(element: CSharpElement) {
        val body: CSharpBlock
        val at: PsiElement
        val shown: String
        when (element) {
            is CSharpMethodDeclaration -> {
                body = element.body ?: return
                val returnType = element.returnType ?: return
                if (returnType is CSharpPredefinedType && returnType.text == "void") return
                if (hasYield(element)) return
                if (element.modifiers.any { it.text == "async" }) {
                    val type = resolver.resolveType(returnType) as? SemanticType.Library ?: return
                    if (type.type.fullName != "System.Threading.Tasks.Task`1" && type.type.fullName != "System.Threading.Tasks.ValueTask`1") return
                }
                at = element.identifier ?: return
                val owner = PsiTreeUtil.getParentOfType(element, CSharpBaseTypeDeclaration::class.java) ?: return
                if (element.typeParameterList != null || element.explicitInterfaceSpecifier != null) return
                val parameters = element.parameterList?.parameters.orEmpty().map { p ->
                    if (p.modifiers.isNotEmpty() || p.default != null) return
                    p.type?.let(resolver::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return
                }
                shown = "${declaringName(owner) ?: return}.${at.text}(${parameters.joinToString(", ")})"
            }
            is CSharpAccessorDeclaration -> {
                if (element.keyword?.text != "get") return
                body = element.body ?: return
                val property = element.parent?.parent as? CSharpPropertyDeclaration ?: return
                if (property.explicitInterfaceSpecifier != null || hasYield(element)) return
                val owner = PsiTreeUtil.getParentOfType(property, CSharpBaseTypeDeclaration::class.java) ?: return
                at = element.keyword ?: return
                shown = "${declaringName(owner) ?: return}.${property.identifier?.text ?: return}.get"
            }
            else -> return
        }
        if (PsiTreeUtil.findChildOfAnyType(body, CSharpGotoStatement::class.java, CSharpLabeledStatement::class.java) != null) return
        if (CSharpReachability(resolver).endOf(body) == CSharpReachability.Reach.YES) report("CS0161", "'$shown': not all code paths return a value", at.textRange)
    }

    // ---- CS0534, CS0535

    /**
     * A class or struct that does not implement an abstract member of its base classes (CS0534, on its name) or a member of its
     * interfaces (CS0535, on the interface in its base list), by [NativeCSharpInheritedMembers.missingMembers]; not a partial type, not
     * a record (the compiler writes members of its own), not where a base does not resolve. Alt+Enter there: Implement missing members.
     */
    private fun checkMissingMembers(type: CSharpTypeDeclaration) {
        if (type !is CSharpClassDeclaration && type !is CSharpStructDeclaration) return
        val name = type.identifier?.takeIf { it.textLength > 0 } ?: return
        val site = CSharpGenerateSite.of(type, resolver) ?: return
        if (site.isStatic || isPartial(site.info) || !isKnown(site.self)) return
        val missing = NativeCSharpInheritedMembers(site).missingMembers()
        if (missing.isEmpty()) return
        val shown = CSharpTypeDisplay.display(site.self) ?: return
        // the interfaces of the base list: a member of a base interface of one of them is reported on the first (`IList<int>` brings `IEnumerable<int>`)
        val faces = type.baseList?.types.orEmpty().mapNotNull { base ->
            base.type?.let { syntax -> resolver.resolveType(syntax)?.takeIf(NativeCSharpGenerate::isInterface)?.let { CSharpTypeDisplay.display(it) to syntax } }
        }
        for (member in missing) {
            val face = member.face
            if (face == null) {
                report("CS0534", "'$shown' does not implement inherited abstract member '${member.display}'", name.textRange)
            } else {
                val at = (faces.firstOrNull { it.first == CSharpTypeDisplay.display(face) } ?: faces.firstOrNull())?.second
                report("CS0535", "'$shown' does not implement interface member '${member.display}'", at?.textRange ?: name.textRange)
            }
        }
    }

    // ---- CS9035

    /**
     * `new OrderLine()` / `new OrderLine { Price = 1 }` that leaves a `required` member unset (C# 11): CS9035 for each, on the type of the
     * creation (`new` of a target-typed one), as Roslyn. Not where the constructor is not sure (overloads, some `[SetsRequiredMembers]`),
     * the type or one of its bases is not known, or a partial type a generator may still add to.
     */
    private fun checkRequiredMembers(creation: CSharpBaseObjectCreationExpression) {
        val type = resolver.typeOf(creation) ?: return
        if (type !is SemanticType.Source && type !is SemanticType.Library) return
        if (type is SemanticType.Source && isPartial(type.info)) return
        val initializer = creation.initializer
        // an unfinished `{ Sk| }` reads as a collection initializer: nothing said while it has elements
        if (initializer != null && initializer.node.elementType != SyntaxKind.ObjectInitializerExpression && initializer.expressions.isNotEmpty()) return
        val required = CSharpRequiredMembers(resolver)
        if (required.of(type).isEmpty() || !isKnown(type)) return
        val missing = required.missing(creation, type) ?: return
        val at = (creation as? CSharpObjectCreationExpression)?.type ?: creation.newKeyword ?: return
        for (member in missing) {
            report("CS9035", "Required member '${member.owner}.${member.name}' must be set in the object initializer or attribute constructor.", at.textRange)
        }
    }

    // ----

    internal fun report(code: String, message: String, range: TextRange, name: String? = null, imports: List<String> = emptyList(), extension: Boolean = false) {
        found += CSharpSemanticProblem(code, message, range, name = name, imports = imports, extension = extension)
    }

    companion object {
        private const val MAX_DEPTH = 16
        private const val MAX_ARITY = 8
        private const val OBJECT = "System.Object"
        private const val STRING = "System.String"
        private const val INT = "System.Int32"
        private const val LONG = "System.Int64"
        private const val CHAR = "System.Char"

        /** Names the scopes of the native resolver do not declare but C# does: contextual keywords, implicit parameters, `args` of top-level code. */
        val IMPLICIT = setOf("var", "dynamic", "nameof", "_", "value", "field", "args", "nint", "nuint", "notnull", "unmanaged", "managed", "global", "__arglist", "await", "async", "record", "scoped", "when", "with", "Attribute")

        private val ARRAY_TYPES = listOf("System.Array", "System.Collections.Generic.IList`1", "System.Collections.Generic.IReadOnlyList`1")
        private val RECORD_MEMBERS = setOf("Deconstruct", "EqualityContract", "PrintMembers", "<Clone>$")

        private val NUMERIC = setOf("System.SByte", "System.Byte", "System.Int16", "System.UInt16", "System.Int32", "System.UInt32", "System.Int64", "System.UInt64",
            "System.Single", "System.Double", "System.Decimal")
        private val SPECIAL = NUMERIC + setOf(CHAR, "System.Boolean", STRING)

        /** C# §10.2.3, the implicit numeric conversions (and from `char`). */
        private val IMPLICIT_NUMERIC: Map<String, Set<String>> = run {
            fun s(vararg names: String) = names.map { "System.$it" }.toSet()
            mapOf(
                "System.SByte" to s("Int16", "Int32", "Int64", "Single", "Double", "Decimal"),
                "System.Byte" to s("Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal"),
                "System.Int16" to s("Int32", "Int64", "Single", "Double", "Decimal"),
                "System.UInt16" to s("Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal"),
                "System.Int32" to s("Int64", "Single", "Double", "Decimal"),
                "System.UInt32" to s("Int64", "UInt64", "Single", "Double", "Decimal"),
                "System.Int64" to s("Single", "Double", "Decimal"),
                "System.UInt64" to s("Single", "Double", "Decimal"),
                "System.Char" to s("UInt16", "Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal"),
                "System.Single" to s("Double"),
            )
        }
    }
}
