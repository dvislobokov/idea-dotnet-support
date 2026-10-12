package io.github.dotnetsupport.lang

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import com.intellij.util.Processor
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.index.AssemblyNavigation
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch

/**
 * Find Usages (Alt+F7), Show Usages (Ctrl+Alt+F7), Go to Implementation and the usages under the caret for the types and members of the
 * solution without the language server (CSHARP_PSI_MIGRATION.md, task C4b; feature `NAVIGATION`): the platform's own machinery on the
 * declarations of csharp-psi's tree. [CSharpTargetElementEvaluator] gives the platform the declaration at the caret (named there, or resolved
 * from a usage), [CSharpFindUsagesProvider] lets it search, [CSharpReferencesSearcher] answers `ReferencesSearch` by the search of
 * [CSharpSolutionSearch] (candidates by word, each resolved), [CSharpDefinitionsSearcher] answers `DefinitionsScopedSearch` (subtypes,
 * overrides and implementations) for Go to Implementation. All of it stands back when the switch gives NAVIGATION to the server — then the
 * server's `textDocument/references` answers through the LSP client, as before.
 */
object NativeCSharpFindUsages {
    fun serves(file: PsiFile?): Boolean = NativeCSharpNavigation.serves(file) && !DumbService.isDumb(file!!.project)

    /** The identifier at [offset] or right before it. */
    fun identifierAt(file: PsiFile, offset: Int): PsiElement? =
        file.findElementAt(offset)?.takeIf(CSharpLeaves::isIdentifier) ?: if (offset > 0) file.findElementAt(offset - 1)?.takeIf(CSharpLeaves::isIdentifier) else null

    /** The declaration of the local symbol [leaf] declares or names: its name leaf. */
    fun localDeclaration(leaf: PsiElement): PsiElement? {
        val file = leaf.containingFile as? CSharpFile ?: return null
        return NativeCSharpResolver(file).symbolAt(leaf)?.takeUnless { it.isMember }?.declaration
    }

    /** What Find Usages searches for at [leaf]: a declaration of the solution (a local by its name leaf); null when nothing is resolved. */
    fun targetElement(leaf: PsiElement): PsiElement? {
        if (!CSharpLeaves.isIdentifier(leaf)) return null
        localDeclaration(leaf)?.let { return it }
        val target = CSharpSolutionSearch.targetAt(leaf, CSharpSemanticSession(leaf.project)) ?: return null
        // a type or member of an assembly: its declaration in the metadata view, which stands for it (CSharpSolutionSearch.targetOf)
        return target.primary ?: when (val library = target.library) {
            is CSharpSymbol.LibraryType -> AssemblyNavigation.target(leaf.project, library.type)
            is CSharpSymbol.LibraryMember -> AssemblyNavigation.target(leaf.project, library.member)
            else -> null
        }
    }

    /** A declaration this search answers for: a type or member of a C# file of the native tree, or the name leaf of a local symbol. */
    fun isSearchable(element: PsiElement): Boolean {
        val file = element.containingFile as? CSharpFile ?: return false
        if (file.compilationUnit == null) return false
        if (CSharpLeaves.isIdentifier(element)) return localDeclaration(element) == element
        return CSharpSolutionSearch.targetOf(element) != null && element !is CSharpIndexerDeclaration
    }

    /** The usages of [element] in [scope] as leaves: a local's from the scopes of its file, a type's or member's from the search. */
    fun processUsages(element: PsiElement, scope: com.intellij.psi.search.SearchScope, consumer: (PsiElement) -> Boolean): Boolean {
        val file = element.containingFile as? CSharpFile ?: return true
        if (CSharpLeaves.isIdentifier(element)) {
            val resolver = NativeCSharpResolver(file)
            val symbol = resolver.symbolAt(element) ?: return true
            for (leaf in resolver.references(symbol)) {
                if (scope is LocalSearchScope && !scope.containsRange(file, leaf.textRange)) continue
                if (!consumer(leaf)) return false
            }
            return true
        }
        val target = CSharpSolutionSearch.targetOf(element) ?: return true
        // as the server's Find References: the usages of the whole hierarchy of a member (a call through the interface is a usage of the implementation)
        val session = CSharpSemanticSession(element.project)
        return CSharpSolutionSearch.processUsages(element.project, CSharpSolutionSearch.withHierarchy(element.project, target, session), scope, session) { consumer(it.leaf) }
    }
}

/** A usage found by [CSharpReferencesSearcher]: the identifier, resolved to the declaration it was searched for. */
class CSharpSolutionReference(leaf: PsiElement, private val target: PsiElement) : PsiReferenceBase<PsiElement>(leaf, TextRange(0, leaf.textLength), true) {
    override fun resolve(): PsiElement = target
    override fun isReferenceTo(element: PsiElement): Boolean = element == target || element.manager.areElementsEquivalent(element, target)
    override fun handleElementRename(newElementName: String): PsiElement = element
}

/**
 * The declaration the platform's actions take at the caret of a C# editor: the declaration a name declares (Find Usages, Show Usages and
 * Ctrl + click on a declaration, which shows its usages as in Rider) and the one a usage resolves to (the resolver of the native tree).
 */
class CSharpTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun getNamedElement(element: PsiElement): PsiElement? {
        if (!CSharpLeaves.isIdentifier(element) && !CSharpLeaves.isKeyword(element, "this")) return null
        if (!NativeCSharpFindUsages.serves(element.containingFile)) return null
        CSharpSolutionSearch.declarationNamedBy(element)?.takeIf { it !is CSharpIndexerDeclaration }?.let { return it }
        return NativeCSharpFindUsages.localDeclaration(element)?.takeIf { it == element }
    }

    override fun adjustReferenceOrReferencedElement(file: PsiFile, editor: Editor, offset: Int, flags: Int, refElement: PsiElement?): PsiElement? {
        if (refElement != null || !NativeCSharpFindUsages.serves(file)) return refElement
        val leaf = NativeCSharpFindUsages.identifierAt(file, offset) ?: return null
        if (CSharpSolutionSearch.declarationNamedBy(leaf) != null) return null
        val local = NativeCSharpFindUsages.localDeclaration(leaf)
        if (local != null) return if (local == leaf) null else local
        return NativeCSharpFindUsages.targetElement(leaf)
    }

    override fun getElementByReference(ref: PsiReference, flags: Int): PsiElement? = (ref as? CSharpSolutionReference)?.resolve()

    /** An interface, an abstract class or member has nothing to go to of its own: only its implementations are listed. */
    override fun includeSelfInGotoImplementation(element: PsiElement): Boolean {
        val modifiers = (element as? CSharpMemberDeclaration)?.modifiers.orEmpty().map { it.text }
        if ("abstract" in modifiers) return false
        if ((element as? CSharpTypeDeclaration)?.keyword?.text == "interface") return false
        val owner = CSharpSolutionSearch.ownerType(element) as? CSharpTypeDeclaration
        if (owner?.keyword?.text == "interface") return false
        // a virtual member that is overridden, a class that has subclasses: their implementations only, as the server answers
        val project = element.project
        if (DumbService.isDumb(project)) return true
        val session = CSharpSemanticSession(project)
        if (element is CSharpBaseTypeDeclaration) return CSharpSolutionSearch.targetOf(element)?.let { CSharpSolutionSearch.directSubtypes(project, it, session) }.isNullOrEmpty()
        return !CSharpSolutionSearch.isOverridable(element) || CSharpSolutionSearch.overridingMembers(project, element, session).isEmpty()
    }
}

/** Find Usages for C# declarations of the native tree ([NativeCSharpFindUsages.isSearchable]); words for the index are the platform's default. */
class CSharpFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner? = null

    override fun canFindUsagesFor(element: PsiElement): Boolean = NativeCSharpFindUsages.serves(element.containingFile) && NativeCSharpFindUsages.isSearchable(element)

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is CSharpTypeDeclaration -> element.keyword?.text ?: "type"
        is CSharpEnumDeclaration -> "enum"
        is CSharpDelegateDeclaration -> "delegate"
        is CSharpConstructorDeclaration -> "constructor"
        is CSharpMethodDeclaration -> "method"
        is CSharpPropertyDeclaration -> "property"
        is CSharpEventDeclaration, is CSharpEventFieldDeclaration -> "event"
        is CSharpEnumMemberDeclaration -> "enum member"
        is CSharpBaseFieldDeclaration, is CSharpVariableDeclarator -> "field"
        else -> NativeCSharpResolver((element.containingFile as? CSharpFile) ?: return "symbol").symbolAt(element)?.kind?.name?.lowercase()?.replace('_', ' ') ?: "symbol"
    }

    override fun getDescriptiveName(element: PsiElement): String {
        val name = getNodeText(element, false)
        val owner = CSharpSolutionSearch.ownerType(element)?.let { CSharpDeclarationNames.name(it) }
        return if (owner != null && element !is CSharpBaseTypeDeclaration && !CSharpLeaves.isIdentifier(element)) "$owner.$name" else name
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = when (element) {
        is CSharpVariableDeclarator -> element.identifier?.text
        else -> if (CSharpLeaves.isIdentifier(element)) element.text else CSharpDeclarationNames.name(element)
    }.orEmpty()
}

/** `ReferencesSearch` of a C# declaration of the native tree: the usages [CSharpSolutionSearch] finds, as references to the declaration. */
class CSharpReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val element = parameters.elementToSearch
        if (!NativeCSharpFindUsages.serves(element.containingFile) || !NativeCSharpFindUsages.isSearchable(element)) return
        NativeCSharpFindUsages.processUsages(element, parameters.effectiveSearchScope) { leaf -> consumer.process(CSharpSolutionReference(leaf, element)) }
    }
}

/**
 * `DefinitionsScopedSearch` of a C# type or member (Go to Implementation, Ctrl+Alt+B): the subtypes of a type, the members that override or
 * implement a member, all the way down.
 */
class CSharpDefinitionsSearcher : QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {
    override fun processQuery(parameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<in PsiElement>) {
        val element = parameters.element
        if (!NativeCSharpFindUsages.serves(element.containingFile)) return
        val session = CSharpSemanticSession(element.project)
        val found = when (element) {
            // as the server answers `textDocument/implementation`: the classes and structs below (an interface deriving from it is no
            // implementation), every part of a partial one; a type without them — the other parts of itself (the platform adds this one)
            is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> {
                val subtypes = CSharpSolutionSearch.targetOf(element)?.let { CSharpSolutionSearch.allSubtypes(element.project, it, session) }.orEmpty()
                    .filter { (it as? CSharpTypeDeclaration)?.keyword?.text != "interface" }.flatMap(::parts)
                subtypes.ifEmpty { parts(element).filter { it != element && CSharpSearchTarget.Key.of(it) != CSharpSearchTarget.Key.of(element) } }
            }
            else -> CSharpSolutionSearch.overridingMembers(element.project, element, session)
        }
        for (definition in found.distinct()) if (!consumer.process(definition)) return
    }

    private fun parts(type: PsiElement): List<PsiElement> =
        (type.containingFile as? CSharpFile)?.let { NativeCSharpResolver(it).declaredType(type)?.targets() }.orEmpty().ifEmpty { listOf(type) }
}

/**
 * The group of a usage of a C# declaration in the Usages view (Group by Usage Type): the kind of [CSharpUsageKind] by the tree, under Rider's
 * names. The usages of the LSP client are text usages of the file; [CSharpUsageGroupingRuleProvider] groups those.
 */
class CSharpUsageTypeProvider : UsageTypeProviderEx {
    override fun getUsageType(element: PsiElement): UsageType? = getUsageType(element, UsageTarget.EMPTY_ARRAY)

    override fun getUsageType(element: PsiElement, targets: Array<out UsageTarget>): UsageType? {
        val file = element.containingFile as? CSharpFile ?: return null
        if (element is PsiFile || file.compilationUnit == null) return null
        CSharpUsages.analysis(file).kindOf(element.textRange)?.let { return TYPES[it] }
        // a `cref` of a doc comment: the server's group, not the platform's «Usage in comments»
        if (com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, io.github.dotnetsupport.csharp.lang.psi.CSharpDocumentationCommentTrivia::class.java) != null) return TYPES[CSharpUsageKind.COMMENT]
        return null
    }

    private companion object {
        val TYPES: Map<CSharpUsageKind, UsageType> = CSharpUsageKind.entries.associateWith { kind -> UsageType { kind.title } }
    }
}
