package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.NativeCSharpResolver
import io.github.dotnetsupport.lang.NativeCSharpScopes

/**
 * The `using` directives of a file nothing in it needs (task A8, the part that waited for C2 / C3; done in C4c): CS8019 «Unnecessary using
 * directive.» — shown gray, as the server's IDE0005 — and CS8933 for one a `global using` makes a repeat of.
 *
 * A directive `using N;` imports the types and extension methods of `N` alone, so it is needed when a name of the file resolves to a type
 * of `N` or to an extension method of `N` (every extension method of that name in `N` counts: overload resolution may take another one).
 * A name the resolver does not resolve keeps every directive whose namespace has something of that name; a query expression keeps all of
 * them (`Select`, `Where` of the namespaces), and so does a namespace that has extension methods the compiler calls by itself
 * (`GetAwaiter`, `GetEnumerator`, `Deconstruct`, `Add`, `Dispose`...). Aliases, `using static` and `global using` are left alone.
 */
internal class CSharpUnusedUsings(private val resolver: CSharpNameResolver) {
    private val session = resolver.session

    fun find(unit: CSharpCompilationUnit): List<CSharpSemanticProblem> {
        val directives = ArrayList<Pair<CSharpUsingDirective, String>>()
        val all = unit.usings + PsiTreeUtil.findChildrenOfType(unit, CSharpBaseNamespaceDeclaration::class.java).flatMap { it.usings }
        for (directive in all) {
            if (directive.globalKeyword != null || directive.staticKeyword != null || directive.alias != null) continue
            val namespace = NativeCSharpResolver.compact(directive.namespaceOrType).removePrefix("global::")
            if (namespace.isEmpty() || '<' in namespace || !session.namespaceExists(namespace, resolver.assemblies)) continue
            if (resolver.typesIn(namespace.substringBeforeLast('.', ""), namespace.substringAfterLast('.'), 0).isNotEmpty()) continue // a type, not a namespace
            directives += directive to namespace
        }
        if (directives.isEmpty()) return emptyList()
        val result = ArrayList<CSharpSemanticProblem>()
        // a repeat of a `global using` of the compilation: CS8933, whatever the file needs
        val global = session.globalUsings(resolver.file).filter { it.alias == null && !it.isStatic }.mapTo(HashSet()) { it.namespace.removePrefix("global::") }
        val local = ArrayList<Pair<CSharpUsingDirective, String>>()
        for ((directive, namespace) in directives) {
            if (namespace in global) {
                // as Roslyn: CS8019 on the directive (the gray), CS8933 on its name (the reason, shown as the text of the gray)
                result += CSharpSemanticProblem("CS8019", "Unnecessary using directive.", directive.textRange, unnecessary = true)
                directive.namespaceOrType?.let { result += CSharpSemanticProblem("CS8933", "The using directive for '$namespace' appeared previously as global using", it.textRange, unnecessary = true) }
            }
            else local += directive to namespace
        }
        if (local.isEmpty()) return result
        if (PsiTreeUtil.findChildOfType(unit, CSharpQueryExpression::class.java) != null) return result
        val candidates = local.mapTo(LinkedHashSet()) { it.second }
        val needed = HashSet<String>()
        for (namespace in candidates) if (hasImplicitExtensions(namespace)) needed += namespace
        PsiTreeUtil.processElements(unit) { element ->
            ProgressManager.checkCanceled()
            if (element is CSharpSimpleName && PsiTreeUtil.getParentOfType(element, CSharpUsingDirective::class.java) == null) need(element, candidates, needed)
            !needed.containsAll(candidates)
        }
        for ((directive, namespace) in local) if (namespace !in needed) {
            result += CSharpSemanticProblem("CS8019", "Unnecessary using directive.", directive.textRange, unnecessary = true)
        }
        return result
    }

    /** Adds to [needed] the namespaces of [candidates] the name [name] may come from. */
    private fun need(name: CSharpSimpleName, candidates: Set<String>, needed: MutableSet<String>) {
        val leaf = name.identifier ?: return
        val text = leaf.text
        val resolution = resolver.resolve(leaf)
        val parent = name.parent
        val accessed = parent is CSharpMemberAccessExpression && parent.nameElement == name || parent is CSharpMemberBindingExpression
        if (resolution == null) {
            if (resolver.syntax.symbolAt(leaf) != null) return
            if (accessed) {
                // an extension method of one of them, or a member nobody resolved
                if (session.sourceExtensions(text).isNotEmpty()) needed += candidates
                for (member in resolver.assemblies.membersNamed(text)) if (member.type.isStatic && member.type.namespace in candidates) needed += member.type.namespace
            } else if (NativeCSharpScopes.isFreeName(name) || parent is CSharpAttribute || name.parent is CSharpTypeCref || (parent as? CSharpQualifiedName)?.left == name) {
                for (namespace in candidates) if (hasTypeNamed(namespace, text) || hasTypeNamed(namespace, text + "Attribute")) needed += namespace
            }
            return
        }
        for (symbol in resolution.symbols) when (symbol) {
            is CSharpSymbol.LibraryType -> needed += outermost(symbol.type).namespace
            is CSharpSymbol.SourceType -> symbol.info.parts.firstNotNullOfOrNull { it.namespace }?.let { needed += it } ?: run {
                // a nested type: the namespace of its outermost type
                needed += symbol.info.qualifiedName.split('.').let { parts -> (1 until parts.size).map { parts.subList(0, it).joinToString(".") } }
            }
            is CSharpSymbol.LibraryMember -> if (symbol.member.kind == IndexedMemberKind.EXTENSION_METHOD) {
                needed += symbol.member.type.namespace
                // another extension method of the name may be the one the compiler takes
                for (member in resolver.assemblies.membersNamed(text)) if (member.kind == IndexedMemberKind.EXTENSION_METHOD && member.type.namespace in candidates) needed += member.type.namespace
                if (session.sourceExtensions(text).isNotEmpty()) needed += candidates
            }
            is CSharpSymbol.SourceMember -> if (resolver.isExtension(symbol)) {
                resolver.namespaceOfMethod(symbol.element)?.let { needed += it } ?: needed.addAll(candidates)
                for (member in resolver.assemblies.membersNamed(text)) if (member.kind == IndexedMemberKind.EXTENSION_METHOD && member.type.namespace in candidates) needed += member.type.namespace
                if (session.sourceExtensions(text).size > 1) needed += candidates
            }
            else -> {}
        }
        // two readings of a name (overloads not told apart, an ambiguous type): every namespace with something of the name stays
        if (resolution.symbols.size > 1) for (namespace in candidates) if (hasTypeNamed(namespace, text)) needed += namespace
    }

    private fun outermost(type: io.github.dotnetsupport.index.IndexedType): io.github.dotnetsupport.index.IndexedType {
        var t = type
        while (true) t = t.declaringType ?: return t
    }

    private fun hasTypeNamed(namespace: String, text: String): Boolean = (0..8).any { resolver.typesIn(namespace, text, it).isNotEmpty() }

    /** Extension methods the compiler calls without their name in the code: `await`, `foreach`, deconstruction, collection initializers, `using`. */
    private fun hasImplicitExtensions(namespace: String): Boolean {
        if (IMPLICIT_NAMES.any { name -> session.sourceExtensions(name).isNotEmpty() }) return true
        return IMPLICIT_NAMES.any { name -> resolver.assemblies.membersNamed(name).any { it.kind == IndexedMemberKind.EXTENSION_METHOD && it.type.namespace == namespace } }
    }

    private companion object {
        val IMPLICIT_NAMES = listOf("GetAwaiter", "GetEnumerator", "GetAsyncEnumerator", "Deconstruct", "Add", "Dispose", "DisposeAsync", "GetPinnableReference")
    }
}
