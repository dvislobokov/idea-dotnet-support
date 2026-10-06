package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.lang.NativeCSharpResolver
import io.github.dotnetsupport.lang.TypePart

/**
 * A type or member of the solution (or of an assembly) whose usages, implementations or hierarchy are asked (CSHARP_PSI_MIGRATION.md, task
 * C4b): every declaration of it (the parts of a partial type; one overload of a method), or the symbol of an assembly ([library]).
 */
class CSharpSearchTarget(val name: String, val kind: Kind, val declarations: List<PsiElement>, val library: CSharpSymbol? = null) {
    enum class Kind { TYPE, METHOD, PROPERTY, INDEXER, FIELD, EVENT, ENUM_MEMBER, CONSTRUCTOR }

    /**
     * The declarations as places: a declaration is the same whether its PSI comes from a stub or from the AST. Read anew each time, not
     * once: a target outlives edits of its file in the cache of the lenses ([io.github.dotnetsupport.lang.CSharpUsageCounts]), and an edit
     * above a declaration moves it — its offset of before the edit would match nothing.
     */
    val keys: Set<Key> get() = declarations.mapNotNullTo(LinkedHashSet(), Key::of)

    val isType: Boolean get() = kind == Kind.TYPE

    /** The first declaration: what the platform's Find Usages holds. */
    val primary: PsiElement? get() = declarations.firstOrNull()

    override fun toString(): String = "$kind $name"

    data class Key(val file: VirtualFile, val offset: Int) {
        companion object {
            fun of(element: PsiElement): Key? = element.containingFile?.viewProvider?.virtualFile?.let { Key(it, element.textOffset) }
        }
    }
}

/**
 * The search of usages across the solution without the language server (task C4b): the files that have the name as a word (the platform's
 * index of words), the identifiers of that text in them, each resolved by [CSharpNameResolver] — a usage is one whose answer names a
 * declaration of the target. One [CSharpSemanticSession] per search, in a read action, never on the EDT for the whole solution.
 */
object CSharpSolutionSearch {
    /** The type or member [leaf] declares or names; null for a local symbol, a namespace, what is not resolved. */
    fun targetAt(leaf: PsiElement, session: CSharpSemanticSession): CSharpSearchTarget? {
        val file = leaf.containingFile as? CSharpFile ?: return null
        if (!CSharpLeaves.isIdentifier(leaf) && !CSharpLeaves.isKeyword(leaf, "this")) return null
        declarationNamedBy(leaf)?.let { return targetOf(it) }
        if (!CSharpLeaves.isIdentifier(leaf)) return null
        val resolver = session.resolver(file)
        if (resolver.syntax.symbolAt(leaf) != null) return null
        val symbols = resolve(resolver, leaf) ?: return null
        val symbol = symbols.singleOrNull() ?: symbols.distinctBy { it.declarations.firstOrNull() ?: it }.singleOrNull() ?: return null
        return targetOf(symbol)
    }

    /** The declaration [leaf] is the name of: a type, delegate, member, enum member, field declarator; not a local or a parameter. */
    fun declarationNamedBy(leaf: PsiElement): PsiElement? {
        var parent = leaf.parent ?: return null
        if (parent is CSharpVariableDeclarator) {
            val field = parent.parent?.parent as? CSharpBaseFieldDeclaration ?: return null
            return if (field.declaration?.variables?.size == 1) field else parent
        }
        if (parent !is CSharpMemberDeclaration || parent is CSharpBaseNamespaceDeclaration) return null
        if (CSharpDeclarationNames.nameElement(parent) != leaf) return null
        if (parent.parent !is CSharpBaseTypeDeclaration && parent.parent !is CSharpBaseNamespaceDeclaration && parent.parent !is CSharpCompilationUnit &&
            parent !is CSharpBaseTypeDeclaration && parent !is CSharpDelegateDeclaration) return null
        if (parent is CSharpDestructorDeclaration) parent = parent.parent ?: return null
        return parent
    }

    fun targetOf(declaration: PsiElement): CSharpSearchTarget? {
        metadataTarget(declaration)?.let { return it }
        val name = when (declaration) {
            is CSharpVariableDeclarator -> declaration.identifier?.text
            is CSharpIndexerDeclaration -> "this"
            else -> CSharpDeclarationNames.nameElement(declaration)?.text
        }?.removePrefix("@") ?: return null
        val kind = when (declaration) {
            is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> CSharpSearchTarget.Kind.TYPE
            is CSharpConstructorDeclaration -> CSharpSearchTarget.Kind.CONSTRUCTOR
            is CSharpMethodDeclaration -> CSharpSearchTarget.Kind.METHOD
            is CSharpPropertyDeclaration -> CSharpSearchTarget.Kind.PROPERTY
            is CSharpIndexerDeclaration -> CSharpSearchTarget.Kind.INDEXER
            is CSharpEventDeclaration, is CSharpEventFieldDeclaration -> CSharpSearchTarget.Kind.EVENT
            is CSharpEnumMemberDeclaration -> CSharpSearchTarget.Kind.ENUM_MEMBER
            is CSharpBaseFieldDeclaration -> CSharpSearchTarget.Kind.FIELD
            is CSharpVariableDeclarator -> if (declaration.parent?.parent is CSharpEventFieldDeclaration) CSharpSearchTarget.Kind.EVENT else CSharpSearchTarget.Kind.FIELD
            else -> return null
        }
        if (kind == CSharpSearchTarget.Kind.TYPE) {
            val file = declaration.containingFile as? CSharpFile
            val parts = file?.let { NativeCSharpResolver(it).declaredType(declaration)?.targets() }.orEmpty()
            return CSharpSearchTarget(name, kind, (listOf(declaration) + parts).distinctBy { CSharpSearchTarget.Key.of(it) ?: it })
        }
        return CSharpSearchTarget(name, kind, listOf(declaration))
    }

    /**
     * A declaration of the metadata view of an assembly (B4) stands for the type or member of the assembly it shows: its usages are the names
     * of the solution resolved to that symbol. Found by the place of its name in the rendered text.
     */
    private fun metadataTarget(declaration: PsiElement): CSharpSearchTarget? {
        val file = declaration.containingFile?.viewProvider?.virtualFile as? io.github.dotnetsupport.index.AssemblyMetadataFile ?: return null
        val offset = declaration.textOffset
        val index = file.index
        file.rendered.memberOffsets.entries.firstOrNull { it.value == offset }?.let { (row, _) ->
            return targetOf(CSharpSymbol.LibraryMember(index.member(row)))
        }
        file.rendered.typeOffsets.entries.firstOrNull { it.value == offset }?.let { (row, _) -> return targetOf(CSharpSymbol.LibraryType(index.type(row))) }
        return null
    }

    fun targetOf(symbol: CSharpSymbol): CSharpSearchTarget? = when (symbol) {
        is CSharpSymbol.SourceType -> symbol.declarations.firstOrNull()?.let(::targetOf)
        is CSharpSymbol.SourceMember -> targetOf(symbol.element)
        is CSharpSymbol.LibraryType -> CSharpSearchTarget(symbol.type.simpleName, CSharpSearchTarget.Kind.TYPE, emptyList(), symbol)
        is CSharpSymbol.LibraryMember -> CSharpSearchTarget(symbol.member.name, libraryKind(symbol.member.kind), emptyList(), symbol)
        else -> null
    }

    private fun libraryKind(kind: IndexedMemberKind): CSharpSearchTarget.Kind = when (kind) {
        IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD, IndexedMemberKind.OPERATOR -> CSharpSearchTarget.Kind.METHOD
        IndexedMemberKind.CONSTRUCTOR -> CSharpSearchTarget.Kind.CONSTRUCTOR
        IndexedMemberKind.PROPERTY -> CSharpSearchTarget.Kind.PROPERTY
        IndexedMemberKind.INDEXER -> CSharpSearchTarget.Kind.INDEXER
        IndexedMemberKind.FIELD -> CSharpSearchTarget.Kind.FIELD
        IndexedMemberKind.CONSTANT, IndexedMemberKind.ENUM_MEMBER -> CSharpSearchTarget.Kind.ENUM_MEMBER
        IndexedMemberKind.EVENT -> CSharpSearchTarget.Kind.EVENT
    }

    /** What [leaf] stands for: the resolver's answer, and inside a doc comment's `cref` the type or member the cref names. */
    fun resolve(resolver: CSharpNameResolver, leaf: PsiElement): List<CSharpSymbol>? {
        val name = leaf.parent as? CSharpSimpleName
        val cref = name?.let { PsiTreeUtil.getParentOfType(it, CSharpCref::class.java) } ?: return resolver.resolve(leaf)?.symbols ?: subpatternMember(resolver, leaf)
        return crefSymbols(resolver, name, cref)
    }

    /**
     * `Total` of `T { Total: > 0 }`: a member of the type the property pattern tests — written, or the type of what an `is` or a `switch`
     * expression tests. The resolver leaves these names open.
     */
    private fun subpatternMember(resolver: CSharpNameResolver, leaf: PsiElement): List<CSharpSymbol>? {
        val name = leaf.parent as? CSharpIdentifierName ?: return null
        val colon = name.parent as? CSharpNameColon ?: return null
        val subpattern = colon.parent as? CSharpSubpattern ?: return null
        val pattern = PsiTreeUtil.getParentOfType(subpattern, CSharpRecursivePattern::class.java) ?: return null
        val type = pattern.type?.let(resolver::resolveType) ?: when (val holder = pattern.parent) {
            is CSharpIsPatternExpression -> holder.expression?.let(resolver::typeOf)
            is CSharpSwitchExpressionArm -> (holder.parent as? CSharpSwitchExpression)?.governingExpression?.let(resolver::typeOf)
            else -> null
        } ?: return null
        return resolver.membersNamed(type, leaf.text.removePrefix("@"), 0).ifEmpty { null }
    }

    /**
     * `cref="T"`, `cref="M"`, `cref="T.M"`, `cref="M(int)"`: the type by the lookup of a type name, a member of the enclosing types, a member of
     * the container type; the resolver itself does not look into doc comments.
     */
    private fun crefSymbols(resolver: CSharpNameResolver, name: CSharpSimpleName, cref: CSharpCref): List<CSharpSymbol>? {
        val text = name.identifier?.text ?: return null
        val arity = NativeCSharpResolver.arity(name)
        val outer = generateSequence(cref) { it.parent as? CSharpCref }.last()
        val qualified = outer as? CSharpQualifiedCref
        val member = qualified?.member ?: outer.takeIf { it is CSharpNameMemberCref }
        val inMember = member is CSharpNameMemberCref && member.nameElement?.let { PsiTreeUtil.isAncestor(it, name, false) } == true &&
            NativeCSharpResolver.isRightmost(name, member.nameElement!!)
        if (qualified != null && inMember) {
            val container = qualified.container ?: return null
            val type = crefType(resolver, container) ?: return null
            return resolver.membersNamed(type, text, arity).ifEmpty { null }
        }
        if (qualified == null && inMember) {
            val owner = PsiTreeUtil.getParentOfType(cref, CSharpDocumentationCommentTrivia::class.java)
            val at: PsiElement = owner?.let { nextDeclaration(it) } ?: cref
            for (info in resolver.syntax.enclosingTypes(at).toList() + listOfNotNull((at as? CSharpBaseTypeDeclaration)?.let(resolver.syntax::declaredType))) {
                resolver.membersNamed(resolver.selfType(info), text, arity).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        // a type: `cref="T"`, the container of `T.M`, a parameter type of `M(T)`
        return resolver.typeOrNamespace(name, text, arity).ifEmpty { null }
    }

    /** The type the container of a qualified cref names (`T` of `T.M`, `A.T` of `A.T.M`). */
    private fun crefType(resolver: CSharpNameResolver, container: CSharpType): SemanticType? {
        val rightmost = when (container) {
            is CSharpSimpleName -> container
            is CSharpQualifiedName -> container.right
            is CSharpAliasQualifiedName -> container.nameElement
            else -> null
        } ?: return resolver.resolveType(container)
        val leaf = rightmost.identifier ?: return null
        val symbol = resolve(resolver, leaf)?.singleOrNull() ?: return resolver.resolveType(container)
        return when (symbol) {
            is CSharpSymbol.SourceType -> resolver.selfType(symbol.info)
            is CSharpSymbol.LibraryType -> SemanticType.Library(symbol.type, emptyList())
            else -> null
        }
    }

    /** The declaration a doc comment belongs to: the trivia sits before the first token of it. */
    private fun nextDeclaration(trivia: PsiElement): PsiElement? {
        var next: PsiElement? = PsiTreeUtil.nextLeaf(trivia)
        while (next != null && (next.textLength == 0 || next.text.isBlank() || PsiTreeUtil.getParentOfType(next, CSharpDocumentationCommentTrivia::class.java) != null)) next = PsiTreeUtil.nextLeaf(next)
        return next?.let { PsiTreeUtil.getParentOfType(it, CSharpMemberDeclaration::class.java) }
    }

    /** The words a usage of [target] is written as: its name; an attribute class also without `Attribute`. */
    fun words(target: CSharpSearchTarget): List<String> {
        val words = arrayListOf(target.name)
        if (target.isType && target.name.endsWith("Attribute") && target.name.length > "Attribute".length) words += target.name.removeSuffix("Attribute")
        return words
    }

    /**
     * One usage: its leaf — the identifier, or for a call the code does not write by name ([implicit]: `base(…)`, a target-typed `new`,
     * a deconstruction, `foreach`, `using`, `await`, an element of a collection initializer) the first leaf of the place Roslyn reports;
     * rename leaves the implicit ones alone.
     */
    class Usage(val leaf: PsiElement, val implicit: Boolean = !CSharpLeaves.isIdentifier(leaf))

    /**
     * Every usage of [target] in [scope]: the declarations themselves are not usages. [consumer] false stops. Runs in a read action; checks
     * the progress for cancellation between files.
     */
    fun processUsages(project: Project, target: CSharpSearchTarget, scope: SearchScope, session: CSharpSemanticSession = CSharpSemanticSession(project), consumer: (Usage) -> Boolean): Boolean {
        if (target.kind == CSharpSearchTarget.Kind.INDEXER) return true
        for (word in words(target)) {
            for (file in filesWithWord(project, word, scope)) {
                ProgressManager.checkCanceled()
                if (!processFile(file, word, target, scope, session, consumer)) return false
            }
        }
        if (target.isType || target.kind == CSharpSearchTarget.Kind.CONSTRUCTOR) return processConstructorCalls(project, target, scope, session, consumer)
        if (target.kind == CSharpSearchTarget.Kind.METHOD && target.name in IMPLICIT_CALLS) return processImplicitCalls(project, target, scope, session, consumer)
        return true
    }

    /** The methods the compiler calls by pattern, without the name in the code: their usages are found by the constructs that call them. */
    private val IMPLICIT_CALLS = setOf("Deconstruct", "GetEnumerator", "Dispose", "GetAwaiter", "Add")

    /**
     * The calls of [target] the code does not write, as Roslyn's Find References reports them (checked on the playground with the server):
     * `Deconstruct` — a deconstructing assignment (its left side) and `foreach (var (a, b) in …)` (the variable), not a positional pattern;
     * `GetEnumerator` — `foreach` (the keyword); `Dispose` — `using` of a statement or a declaration (the keyword); `GetAwaiter` — `await`;
     * `Add` — each element of a collection initializer. The method is the one the construct binds to: the member of that name of the type
     * of the value, with the number of parameters the construct passes.
     */
    private fun processImplicitCalls(project: Project, target: CSharpSearchTarget, scope: SearchScope, session: CSharpSemanticSession, consumer: (Usage) -> Boolean): Boolean {
        val keys = target.keys
        fun calls(resolver: CSharpNameResolver, type: SemanticType?, arguments: Int): Boolean {
            if (type == null) return false
            val methods = resolver.membersNamed(type, target.name, 0).filter(resolver::isMethod).filter { method ->
                val parameters = resolver.signature(method, false) ?: return@filter false
                parameters.size == arguments || parameters.size > arguments && parameters.drop(arguments).all { it.optional || it.isParams }
            }
            val method = methods.singleOrNull() ?: return false
            target.library?.let { return method == it }
            return method.declarations.any { CSharpSearchTarget.Key.of(it) in keys }
        }
        fun inScope(element: PsiElement): Boolean = when (scope) {
            is LocalSearchScope -> scope.containsRange(element.containingFile, element.textRange)
            is GlobalSearchScope -> element.containingFile?.virtualFile?.let(scope::contains) ?: true
            else -> true
        }
        fun report(leaf: PsiElement?): Boolean = leaf == null || !inScope(leaf) || consumer(Usage(leaf, implicit = true))
        val files: List<CSharpFile> = when (target.name) {
            "GetEnumerator" -> filesWithWord(project, "foreach", scope)
            "Dispose" -> filesWithWord(project, "using", scope)
            "GetAwaiter" -> filesWithWord(project, "await", scope)
            // `new T { … }` names the type, `new() { … }` converts to a type written in the file
            "Add" -> (listOfNotNull(target.primary?.let(::ownerType)?.let { CSharpDeclarationNames.name(it) }) + listOfNotNull((target.library as? CSharpSymbol.LibraryMember)?.member?.type?.simpleName?.substringBefore('`')))
                .flatMap { filesWithWord(project, it, scope) }.distinct()
            // a deconstruction writes no word of its own: every C# file whose text has `) =` or `foreach`
            else -> csharpFiles(project, scope).filter { DECONSTRUCTION.containsMatchIn(it.viewProvider.contents) }
        }
        for (file in files) {
            ProgressManager.checkCanceled()
            val resolver = session.resolver(file)
            val traverser = SyntaxTraverser.psiTraverser(file)
            val ok = when (target.name) {
                "Deconstruct" -> traverser.filter { it is CSharpAssignmentExpression || it is CSharpForEachVariableStatement }.all { node ->
                    when (node) {
                        is CSharpAssignmentExpression -> {
                            val left = node.left
                            val count = left?.let(::deconstructedCount)
                            node.operatorToken?.text != "=" || count == null || !calls(resolver, node.right?.let(resolver::typeOf), count) || report(PsiTreeUtil.getDeepestFirst(left))
                        }
                        is CSharpForEachVariableStatement -> {
                            val variable = node.variable
                            val count = variable?.let(::deconstructedCount)
                            count == null || !calls(resolver, node.expression?.let(resolver::typeOf)?.let(resolver::elementType), count) || report(PsiTreeUtil.getDeepestFirst(variable))
                        }
                        else -> true
                    }
                }
                "GetEnumerator" -> traverser.filter(CSharpCommonForEachStatement::class.java).all { loop ->
                    loop.awaitKeyword != null || !calls(resolver, loop.expression?.let(resolver::typeOf), 0) || report(loop.forEachKeyword)
                }
                "Dispose" -> traverser.filter { it is CSharpUsingStatement || it is CSharpLocalDeclarationStatement && it.usingKeyword != null }.all { node ->
                    val (await, keyword, declaration, expression) = when (node) {
                        is CSharpUsingStatement -> listOf(node.awaitKeyword, node.usingKeyword, node.declaration, node.expression)
                        is CSharpLocalDeclarationStatement -> listOf(node.awaitKeyword, node.usingKeyword, node.declaration, null)
                        else -> listOf(null, null, null, null)
                    }
                    val types = if (declaration is CSharpVariableDeclaration) declaredTypes(resolver, declaration) else listOfNotNull((expression as? CSharpExpression)?.let(resolver::typeOf))
                    await != null || types.none { calls(resolver, it, 0) } || report(keyword)
                }
                "GetAwaiter" -> traverser.filter(CSharpAwaitExpression::class.java).all { e -> !calls(resolver, e.expression?.let(resolver::typeOf), 0) || report(e.awaitKeyword) }
                "Add" -> traverser.filter(CSharpInitializerExpression::class.java).all { initializer ->
                    val creation = initializer.parent as? CSharpBaseObjectCreationExpression
                    if (creation == null || creation.initializer != initializer || initializer.elementType != SyntaxKind.CollectionInitializerExpression) return@all true
                    val type = resolver.typeOf(creation)
                    initializer.expressions.all { element ->
                        val count = if (element.elementType == SyntaxKind.ComplexElementInitializerExpression) (element as CSharpInitializerExpression).expressions.size else 1
                        !calls(resolver, type, count) || report(PsiTreeUtil.getDeepestFirst(element))
                    }
                }
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    private val DECONSTRUCTION = Regex("""\)\s*=(?![=>])|\bforeach\b""")

    /** The number of variables a deconstruction's left side (or a `foreach` variable) has: `(a, b)`, `var (a, b)`, `(var a, var b)`; null for anything else. */
    private fun deconstructedCount(left: CSharpExpression): Int? = when (left) {
        is CSharpTupleExpression -> left.arguments.size
        is CSharpDeclarationExpression -> (left.designation as? CSharpParenthesizedVariableDesignation)?.variables?.size
        else -> null
    }

    /** The types of the variables of a declaration: written, or of their initializers for `var`. */
    private fun declaredTypes(resolver: CSharpNameResolver, declaration: CSharpVariableDeclaration): List<SemanticType> {
        val written = declaration.type?.takeIf { !resolver.isVar(it) }?.let(resolver::resolveType)
        if (written != null) return listOf(written)
        return declaration.variables.mapNotNull { it.initializer?.value?.let(resolver::typeOf) }
    }

    private fun csharpFiles(project: Project, scope: SearchScope): List<CSharpFile> {
        if (scope is LocalSearchScope) return scope.scope.mapNotNull { it.containingFile as? CSharpFile }.distinct()
        val global = scope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(project)
        val manager = com.intellij.psi.PsiManager.getInstance(project)
        return com.intellij.psi.search.FileTypeIndex.getFiles(io.github.dotnetsupport.lang.CSharpFileType, global)
            .mapNotNull { manager.findFile(it) as? CSharpFile }.filter { it.compilationUnit != null }
    }

    /**
     * The type a target-typed `new(…)` creates where the expression types do not say it: the value of an index initializer
     * (`["a"] = new(…)` of a dictionary: the type of the indexer) and an element of a collection initializer (the parameter of `Add`).
     */
    private fun createdByPlace(resolver: CSharpNameResolver, creation: CSharpImplicitObjectCreationExpression): SemanticType? {
        resolver.typeOf(creation)?.let { return it }
        var at: PsiElement = creation
        while (at.parent is CSharpParenthesizedExpression) at = at.parent
        val parent = at.parent
        if (parent is CSharpAssignmentExpression && parent.right == at) {
            val index = parent.left as? CSharpImplicitElementAccess ?: return null
            val owner = (parent.parent as? CSharpInitializerExpression)?.parent as? CSharpBaseObjectCreationExpression ?: return null
            val receiver = resolver.typeOf(owner) ?: return null
            return resolver.expressions.indexed(receiver, index.argumentList?.arguments.orEmpty())
        }
        // `{ new(…) }` and `{ "k", new(…) }` of a collection initializer: the parameter of the `Add` they go to
        var initializer = parent as? CSharpInitializerExpression ?: return null
        var position = 0
        var count = 1
        if (initializer.elementType == SyntaxKind.ComplexElementInitializerExpression) {
            position = initializer.expressions.indexOf(at)
            count = initializer.expressions.size
            initializer = initializer.parent as? CSharpInitializerExpression ?: return null
        }
        if (initializer.elementType != SyntaxKind.CollectionInitializerExpression) return null
        val owner = initializer.parent as? CSharpBaseObjectCreationExpression ?: return null
        val collection = resolver.typeOf(owner) ?: return null
        val add = resolver.membersNamed(collection, "Add", 0).filter(resolver::isMethod).filter { resolver.signature(it, false)?.size == count }.singleOrNull()
        return add?.let { resolver.signature(it, false)?.getOrNull(position)?.type?.invoke() } ?: if (count == 1) resolver.elementType(collection) else null
    }

    /**
     * The calls of constructors that do not write the type's name: the `base(…)` initializers of the subtypes' constructors and the `this(…)`
     * ones of the type — usages of the constructor alone, as Roslyn and Rider count them (a constructor initializer names no type) — and
     * target-typed `new(…)`, a usage of the constructor and of the type. Their leaf is the keyword (`base`, `this`, `new`) — a usage to
     * show, not a name to rename.
     */
    private fun processConstructorCalls(project: Project, target: CSharpSearchTarget, scope: SearchScope, session: CSharpSemanticSession, consumer: (Usage) -> Boolean): Boolean {
        val constructor = target.primary as? CSharpConstructorDeclaration
        val type = (if (constructor != null) constructor.parent else target.primary) as? CSharpTypeDeclaration ?: return true
        if (type.keyword?.text == "interface") return true
        val typeTarget = if (constructor != null) targetOf(type) ?: return true else target
        val constructors = typeTarget.declarations.flatMap { (it as? CSharpTypeDeclaration)?.members.orEmpty() }.filterIsInstance<CSharpConstructorDeclaration>()
            .filter { c -> c.modifiers.none { it.text == "static" } }
        // the constructor an argument count calls, as constructorUsage decides it for `new T(…)`
        fun calls(arguments: Int): Boolean {
            if (constructor == null) return true
            val fitting = constructors.filter { fits(it, arguments) }
            return constructor in fitting && (fitting.size == 1 || constructors.size == 1 || fitting.count { (it.parameterList?.parameters?.size ?: 0) == arguments } <= 1 && (constructor.parameterList?.parameters?.size ?: 0) == arguments)
        }
        fun inScope(leaf: PsiElement): Boolean = when (scope) {
            is LocalSearchScope -> scope.containsRange(leaf.containingFile, leaf.textRange)
            is GlobalSearchScope -> leaf.containingFile?.virtualFile?.let(scope::contains) ?: true
            else -> true
        }
        val own = typeTarget.keys
        for (holder in if (constructor == null) emptyList() else typeTarget.declarations + directSubtypes(project, typeTarget, session)) {
            val isOwn = CSharpSearchTarget.Key.of(holder) in own
            for (member in (holder as? CSharpTypeDeclaration)?.members.orEmpty()) {
                val initializer = (member as? CSharpConstructorDeclaration)?.initializer ?: continue
                val keyword = initializer.thisOrBaseKeyword ?: continue
                if ((keyword.text == "this") != isOwn || !inScope(keyword)) continue
                if (calls(initializer.argumentList?.arguments?.size ?: 0) && !consumer(Usage(keyword))) return false
            }
        }
        // target-typed `new()`: in the files that name the type somewhere (the declared type the `new` converts to is written there)
        for (word in words(typeTarget)) {
            for (file in filesWithWord(project, word, scope)) {
                ProgressManager.checkCanceled()
                val resolver = session.resolver(file)
                for (creation in SyntaxTraverser.psiTraverser(file).filter(CSharpImplicitObjectCreationExpression::class.java)) {
                    val keyword = creation.newKeyword ?: continue
                    if (!inScope(keyword)) continue
                    val created = createdByPlace(resolver, creation) as? SemanticType.Source ?: continue
                    if (created.info.targets().none { CSharpSearchTarget.Key.of(it) in own }) continue
                    if (calls(creation.argumentList?.arguments?.size ?: 0) && !consumer(Usage(keyword))) return false
                }
            }
        }
        return true
    }

    /**
     * [target] with the members of its hierarchy, as Roslyn's Find References cascades: the members it overrides or implements (of the
     * solution) and everything that overrides or implements those or it. The target itself for a type, a constructor, a field.
     */
    fun withHierarchy(project: Project, target: CSharpSearchTarget, session: CSharpSemanticSession): CSharpSearchTarget {
        val member = target.primary ?: return target
        if (target.isType || target.kind == CSharpSearchTarget.Kind.CONSTRUCTOR || target.kind == CSharpSearchTarget.Kind.FIELD || target.kind == CSharpSearchTarget.Kind.ENUM_MEMBER) return target
        // up to the top of every chain: an override reaches the virtual member, which reaches the interface member it implements
        val roots = LinkedHashSet<PsiElement>(target.declarations)
        val queue = ArrayDeque(listOf(member))
        while (queue.isNotEmpty() && roots.size < 64) {
            for (base in baseMembers(queue.removeFirst(), session)) if (base is PsiElement && roots.add(base)) queue += base
        }
        val all = LinkedHashSet<PsiElement>(roots)
        for (root in roots) all += overridingMembers(project, root, session)
        if (all.size == target.declarations.size) return target
        return CSharpSearchTarget(target.name, target.kind, all.toList(), target.library)
    }

    fun usages(project: Project, target: CSharpSearchTarget, scope: SearchScope, session: CSharpSemanticSession = CSharpSemanticSession(project)): List<Usage> {
        val found = ArrayList<Usage>()
        processUsages(project, target, scope, session) { found += it; true }
        return found
    }

    private fun filesWithWord(project: Project, word: String, scope: SearchScope): List<CSharpFile> {
        if (scope is LocalSearchScope) return scope.scope.mapNotNull { it.containingFile as? CSharpFile }.distinct()
        val files = LinkedHashSet<CSharpFile>()
        val global = scope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(project)
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, global, { file ->
            (file as? CSharpFile)?.takeIf { it.compilationUnit != null }?.let(files::add)
            true
        }, true)
        return files.toList()
    }

    /** The usages of [target] in [file] whose text is [word]. */
    fun processFile(file: CSharpFile, word: String, target: CSharpSearchTarget, scope: SearchScope?, session: CSharpSemanticSession, consumer: (Usage) -> Boolean): Boolean {
        val text = file.viewProvider.contents
        val resolver = session.resolver(file)
        var at = indexOfWord(text, word, 0)
        while (at >= 0) {
            val leaf = file.findElementAt(at)
            if (leaf != null && leaf.textRange.startOffset == at && CSharpLeaves.isIdentifier(leaf) && leaf.text.removePrefix("@") == word &&
                (scope !is LocalSearchScope || scope.containsRange(file, leaf.textRange)) && matches(resolver, leaf, target)
            ) {
                if (!consumer(Usage(leaf))) return false
            }
            at = indexOfWord(text, word, at + word.length)
        }
        return true
    }

    private fun indexOfWord(text: CharSequence, word: String, from: Int): Int {
        var at = from
        while (true) {
            val found = text.indexOf(word, at)
            if (found < 0) return -1
            val before = if (found > 0) text[found - 1] else ' '
            val after = if (found + word.length < text.length) text[found + word.length] else ' '
            if (!Character.isLetterOrDigit(before) && before != '_' && !Character.isLetterOrDigit(after) && after != '_') {
                return if (before == '@') found - 1 else found
            }
            at = found + 1
        }
    }

    /** [leaf] names [target]: a declaration of its is among what the resolver answers (an overload left open counts), or its assembly symbol. */
    fun matches(resolver: CSharpNameResolver, leaf: PsiElement, target: CSharpSearchTarget): Boolean {
        if (declarationNamedBy(leaf) != null) return false
        val symbols = resolve(resolver, leaf) ?: return false
        if (target.kind == CSharpSearchTarget.Kind.CONSTRUCTOR) return constructorUsage(leaf, symbols, target)
        target.library?.let { library -> return symbols.any { it == library } }
        val keys = target.keys
        return symbols.any { symbol -> symbol.declarations.any { CSharpSearchTarget.Key.of(it) in keys } }
    }

    /** `new T(…)` of the constructor's type: the constructor when the type has one of its arity, or this one is the only one. */
    private fun constructorUsage(leaf: PsiElement, symbols: List<CSharpSymbol>, target: CSharpSearchTarget): Boolean {
        val constructor = target.primary as? CSharpConstructorDeclaration ?: return false
        val type = constructor.parent as? CSharpBaseTypeDeclaration ?: return false
        val typeKey = CSharpSearchTarget.Key.of(type)
        if (symbols.none { s -> s is CSharpSymbol.SourceType && s.declarations.any { CSharpSearchTarget.Key.of(it) == typeKey } }) return false
        var top: PsiElement = leaf.parent ?: return false
        while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
        val creation = top.parent as? CSharpObjectCreationExpression ?: return false
        if (creation.type != top) return false
        val arguments = creation.argumentList?.arguments?.size ?: 0
        val constructors = (type as? CSharpTypeDeclaration)?.members.orEmpty().filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } }
        val fitting = constructors.filter { fits(it, arguments) }
        return constructor in fitting && (fitting.size == 1 || constructors.size == 1 || fitting.count { (it.parameterList?.parameters?.size ?: 0) == arguments } <= 1 && (constructor.parameterList?.parameters?.size ?: 0) == arguments)
    }

    private fun fits(constructor: CSharpConstructorDeclaration, arguments: Int): Boolean {
        val parameters = constructor.parameterList?.parameters.orEmpty()
        val required = parameters.count { p -> p.default == null && p.modifiers.none { it.text == "params" } }
        return arguments >= required && (arguments <= parameters.size || parameters.lastOrNull()?.modifiers?.any { it.text == "params" } == true)
    }

    // ---- the hierarchy of types

    /** The base class and interfaces of the type [declaration] (all its parts) as written: types of the solution and of the assemblies. */
    fun supertypes(declaration: PsiElement, session: CSharpSemanticSession): List<SemanticType> {
        val file = declaration.containingFile as? CSharpFile ?: return emptyList()
        val resolver = session.resolver(file)
        val info = resolver.syntax.declaredType(declaration) ?: return emptyList()
        val found = ArrayList<SemanticType>()
        for (part in info.parts) {
            val element = part.element() as? CSharpBaseTypeDeclaration ?: continue
            val owner = (element.containingFile as? CSharpFile)?.let(session::resolver) ?: continue
            for (base in element.baseList?.types.orEmpty()) base.type?.let(owner::resolveType)?.let { found += it }
        }
        return found.distinctBy { key(it) }
    }

    private fun key(type: SemanticType): Any = when (type) {
        is SemanticType.Source -> type.info.key
        is SemanticType.Library -> type.type
        else -> type.toString()
    }

    /** The types whose base lists name the type [target] (its declarations, or the assembly type): the direct subtypes, one level. */
    fun directSubtypes(project: Project, target: CSharpSearchTarget, session: CSharpSemanticSession): List<PsiElement> {
        val found = LinkedHashMap<Any, PsiElement>()
        val candidates = ArrayList<PsiElement>()
        StubIndex.getInstance().processElements(CSharpStubIndexKeys.SUPERTYPES, target.name, project, GlobalSearchScope.projectScope(project), CSharpElement::class.java) {
            candidates += it
            true
        }
        val keys = target.keys
        for (candidate in candidates) {
            ProgressManager.checkCanceled()
            val declaration = candidate as? CSharpBaseTypeDeclaration ?: continue
            val file = declaration.containingFile as? CSharpFile ?: continue
            val resolver = session.resolver(file)
            val names = declaration.baseList?.types.orEmpty().mapNotNull { it.type }
            val hit = names.any { written ->
                when (val type = resolver.resolveType(written)) {
                    is SemanticType.Source -> type.info.targets().any { CSharpSearchTarget.Key.of(it) in keys }
                    is SemanticType.Library -> (target.library as? CSharpSymbol.LibraryType)?.type == type.type
                    else -> false
                }
            }
            if (hit) found.putIfAbsent(CSharpSearchTarget.Key.of(declaration) ?: declaration, declaration)
        }
        // the parts of one subtype once: its first part found
        val byType = LinkedHashMap<String, PsiElement>()
        for (element in found.values) {
            val file = element.containingFile as? CSharpFile ?: continue
            val info = session.resolver(file).syntax.declaredType(element)
            byType.putIfAbsent(info?.key ?: element.toString(), element)
        }
        return byType.values.toList()
    }

    /** Every subtype of [target], the nearer first. */
    fun allSubtypes(project: Project, target: CSharpSearchTarget, session: CSharpSemanticSession, limit: Int = 500): List<PsiElement> {
        val seen = LinkedHashMap<CSharpSearchTarget.Key, PsiElement>()
        val own = target.keys
        var level = listOf(target)
        var depth = 0
        while (level.isNotEmpty() && depth++ < 32 && seen.size < limit) {
            val next = ArrayList<CSharpSearchTarget>()
            for (type in level) for (sub in directSubtypes(project, type, session)) {
                val key = CSharpSearchTarget.Key.of(sub) ?: continue
                if (key in own || seen.putIfAbsent(key, sub) != null) continue
                targetOf(sub)?.let { next += it }
            }
            level = next
        }
        return seen.values.toList()
    }

    // ---- members across the hierarchy

    /** The type a member declaration is in. */
    fun ownerType(member: PsiElement): CSharpBaseTypeDeclaration? = PsiTreeUtil.getParentOfType(member, CSharpBaseTypeDeclaration::class.java, true)

    private fun isInterface(type: PsiElement?): Boolean = (type as? CSharpTypeDeclaration)?.keyword?.text == "interface"

    private fun modifiers(member: PsiElement): List<String> = (member as? CSharpMemberDeclaration)?.modifiers.orEmpty().map { it.text }

    /** A member another can override or implement: of an interface, or `virtual` / `abstract` / `override` of a class. */
    fun isOverridable(member: PsiElement): Boolean {
        val owner = ownerType(member) ?: return false
        if (isInterface(owner)) return "static" !in modifiers(member) || "abstract" in modifiers(member) || "virtual" in modifiers(member)
        return modifiers(member).any { it == "virtual" || it == "abstract" || it == "override" }
    }

    /** What makes members alike across types: kind, name, number of parameters and of type parameters. */
    private fun shape(member: PsiElement): String? = when (member) {
        is CSharpMethodDeclaration -> "M:" + (member.identifier?.text ?: return null) + "`" + (member.typeParameterList?.parameters?.size ?: 0) + "(" + parameterTypes(member.parameterList) + ")"
        is CSharpPropertyDeclaration -> "P:" + (member.identifier?.text ?: return null)
        is CSharpIndexerDeclaration -> "I:(" + parameterTypes(member.parameterList) + ")"
        is CSharpEventDeclaration -> "E:" + (member.identifier?.text ?: return null)
        is CSharpEventFieldDeclaration -> "E:" + (member.declaration?.variables?.singleOrNull()?.identifier?.text ?: return null)
        is CSharpVariableDeclarator -> if (member.parent?.parent is CSharpEventFieldDeclaration) "E:" + member.identifier?.text else null
        else -> null
    }

    /** `ref int, string`: the modifiers and the simple names of the parameter types; a type parameter as `?`, which matches anything. */
    private fun parameterTypes(list: CSharpBaseParameterList?): String = list?.parameters.orEmpty().joinToString(",") { p ->
        val mods = p.modifiers.map { it.text }.filter { it == "ref" || it == "out" || it == "in" || it == "params" }.joinToString(" ")
        val type = p.type?.let { t -> TypePart.simpleName(t)?.first ?: t.text.filterNot(Char::isWhitespace) }.orEmpty()
        "$mods $type".trim()
    }

    private fun sameShape(a: String, b: String, typeParameters: Set<String>): Boolean {
        if (a == b) return true
        val head = a.substringBefore('(')
        if (head != b.substringBefore('(')) return false
        val left = a.substringAfter('(').removeSuffix(")").split(',')
        val right = b.substringAfter('(').removeSuffix(")").split(',')
        if (left.size != right.size) return false
        return left.indices.all { i -> left[i] == right[i] || left[i].substringAfterLast(' ') in typeParameters || right[i].substringAfterLast(' ') in typeParameters }
    }

    private fun typeParameterNames(type: PsiElement?): Set<String> {
        val names = HashSet<String>()
        var current = type
        while (current != null && current !is PsiFile) {
            (current as? CSharpTypeDeclaration)?.typeParameterList?.parameters?.forEach { p -> p.identifier?.text?.let(names::add) }
            (current as? CSharpMethodDeclaration)?.typeParameterList?.parameters?.forEach { p -> p.identifier?.text?.let(names::add) }
            current = current.parent
        }
        return names
    }

    private fun members(type: PsiElement): List<PsiElement> = when (type) {
        is CSharpTypeDeclaration -> type.members.flatMap { m -> if (m is CSharpEventFieldDeclaration && (m.declaration?.variables?.size ?: 0) > 1) m.declaration!!.variables else listOf(m) }
        else -> emptyList()
    }

    /** The interface a member implements explicitly (`void I.M()`), by its simple name. */
    private fun explicitInterface(member: PsiElement): String? = when (member) {
        is CSharpMethodDeclaration -> member.explicitInterfaceSpecifier
        is CSharpBasePropertyDeclaration -> member.explicitInterfaceSpecifier
        else -> null
    }?.nameElement?.let { TypePart.simpleName(it)?.first }

    /**
     * The members of the subtypes of [member]'s type that override or implement it: `override` ones in classes, the members of the same
     * shape in the types implementing an interface (an explicit `I.M` included).
     */
    fun overridingMembers(project: Project, member: PsiElement, session: CSharpSemanticSession): List<PsiElement> {
        if (!isOverridable(member)) return emptyList()
        val owner = ownerType(member) ?: return emptyList()
        val shape = shape(member) ?: return emptyList()
        val ownerTarget = targetOf(owner) ?: return emptyList()
        val fromInterface = isInterface(owner)
        val found = ArrayList<PsiElement>()
        val subtypes = allSubtypes(project, ownerTarget, session)
        // a class implements the interface member only when it lists the interface (or one deriving from it) itself; below that a member
        // of the same shape hides the implementation of its base class (`new`) unless it overrides it
        val interfaces = if (!fromInterface) emptySet() else (subtypes.filter(::isInterface) + owner).mapNotNullTo(HashSet()) { CSharpSearchTarget.Key.of(it) }
        fun listsTheInterface(sub: PsiElement): Boolean = supertypes(sub, session).any { base ->
            base is SemanticType.Source && base.info.targets().any { CSharpSearchTarget.Key.of(it) in interfaces }
        }
        for (sub in subtypes) {
            val parameters = typeParameterNames(owner) + typeParameterNames(sub)
            for (candidate in members(sub)) {
                val other = shape(candidate) ?: continue
                if (!sameShape(shape, other, parameters)) continue
                val explicit = explicitInterface(candidate)
                val fits = when {
                    explicit != null -> fromInterface && explicit == ownerTarget.name
                    isInterface(sub) -> false
                    "override" in modifiers(candidate) -> true
                    fromInterface -> listsTheInterface(sub)
                    else -> false
                }
                if (fits) found += candidate
            }
        }
        return found
    }

    /**
     * The members [member] overrides or implements, the nearest first: of the solution as declarations, of the assemblies as their symbols.
     * A class member reaches its base classes only when it is `override`; interfaces always (implicit implementation).
     */
    fun baseMembers(member: PsiElement, session: CSharpSemanticSession): List<Any> {
        val owner = ownerType(member) ?: return emptyList()
        val shape = shape(member) ?: return emptyList()
        val name = shape.substringAfter(':').substringBefore('`').substringBefore('(')
        val isOverride = "override" in modifiers(member)
        val explicit = explicitInterface(member)
        val found = ArrayList<Any>()
        val seen = HashSet<Any>()
        // an interface reached through a base class is implemented by that class, not by this member (unless it overrides there)
        var level = supertypes(owner, session).map { it to false }
        var depth = 0
        var classDone = false
        while (level.isNotEmpty() && depth++ < 16) {
            val next = ArrayList<Pair<SemanticType, Boolean>>()
            for ((base, viaClass) in level) {
                if (!seen.add(key(base))) continue
                ProgressManager.checkCanceled()
                when (base) {
                    is SemanticType.Source -> {
                        val declaration = base.info.targets().firstOrNull()
                        val baseIsInterface = isInterface(declaration)
                        val wanted = if (baseIsInterface) !viaClass && (explicit == null || explicit == base.name) else isOverride && !classDone && explicit == null
                        if (wanted) {
                            val parameters = typeParameterNames(owner) + typeParameterNames(declaration)
                            for (part in base.info.targets()) for (candidate in members(part)) {
                                val other = shape(candidate) ?: continue
                                if (sameShape(shape, other, parameters) && explicitInterface(candidate) == null && candidate !in found) found += candidate
                            }
                            if (!baseIsInterface && found.isNotEmpty()) classDone = true
                        }
                        declaration?.let { next += supertypes(it, session).map { up -> up to (viaClass || !baseIsInterface) } }
                    }
                    is SemanticType.Library -> {
                        val baseIsInterface = base.type.kind == io.github.dotnetsupport.index.IndexedTypeKind.INTERFACE
                        val wanted = if (baseIsInterface) !viaClass && (explicit == null || explicit == base.name) else isOverride && !classDone && explicit == null
                        if (wanted && member.containingFile is CSharpFile) {
                            val resolver = session.resolver(member.containingFile as CSharpFile)
                            val members = resolver.membersNamed(base, name, 0).filterIsInstance<CSharpSymbol.LibraryMember>()
                                .filter { it.member.kind != IndexedMemberKind.CONSTRUCTOR && parameterCount(member) == session.parameters(it.member).size }
                            for (m in members) if (found.none { it == m }) found += m
                            if (!baseIsInterface && members.isNotEmpty()) classDone = true
                        }
                    }
                    else -> {}
                }
            }
            level = next
        }
        return found
    }

    private fun parameterCount(member: PsiElement): Int = when (member) {
        is CSharpBaseMethodDeclaration -> member.parameterList?.parameters?.size ?: 0
        is CSharpIndexerDeclaration -> member.parameterList?.parameters?.size ?: 0
        else -> 0
    }
}
