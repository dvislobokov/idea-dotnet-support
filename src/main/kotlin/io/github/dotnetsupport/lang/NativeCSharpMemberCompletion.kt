package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpLocalDeclarationStatement
import io.github.dotnetsupport.csharp.lang.psi.CSharpQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpSimpleName
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclaration
import io.github.dotnetsupport.index.ImportCompletion
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpMemberLookup
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpSymbolText
import javax.swing.Icon

/**
 * COMPLETION after a dot on the plugin's semantics (CSHARP_PSI_MIGRATION.md, task C3): `orders.` → the members of the type of `orders`
 * (of the solution and of the referenced assemblies, inherited ones included, type arguments substituted) and the extension methods in
 * scope; `Console.` → static members and nested types; `System.` → namespaces and types. What the resolver cannot type gives no items, and
 * the server's list (when it runs) stays whole: the merge of [NativeCSharpCompletionContributor] drops only the server's items of the
 * names listed here.
 */
object NativeCSharpMemberCompletion {
    /** Extension methods a little under the members of the type, as Rider lists them. */
    const val EXTENSION = 28.0

    fun items(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement> {
        val name = place.name ?: return emptyList()
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val lookup = CSharpMemberLookup(resolver)
        val qualifier = lookup.qualifierOf(name) ?: return emptyList()
        val entries = lookup.entries(qualifier, name, typesOnly(name), matcher)
        val text = CSharpSymbolText(resolver)
        val reduced = qualifier is CSharpNameResolver.Qualifier.Value || qualifier is CSharpNameResolver.Qualifier.ValueOrType
        return entries.mapNotNull { entry ->
            ProgressManager.checkCanceled()
            element(entry, text, resolver, reduced)
        }
    }

    /** The members of the own type that the syntax does not see (of library bases, extension methods): `this.` of a class derived from `Exception`. */
    fun thisItems(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher): List<LookupElement> {
        if (place.base) return emptyList()
        val name = place.name ?: return emptyList()
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val lookup = CSharpMemberLookup(resolver)
        val qualifier = lookup.qualifierOf(name) ?: return emptyList()
        val text = CSharpSymbolText(resolver)
        return lookup.entries(qualifier, name, false, matcher, throughThis = true).mapNotNull { element(it, text, resolver, true) }
    }

    /** `A.B.|` where only a type stands (a parameter's type, a base list); not the type of a local at a statement start, which may be a call. */
    private fun typesOnly(name: CSharpSimpleName): Boolean {
        if (name.parent !is CSharpQualifiedName) return false
        var top: PsiElement = name
        while (top.parent is CSharpQualifiedName) top = top.parent
        val declaration = top.parent as? CSharpVariableDeclaration
        return !(declaration != null && declaration.type == top && declaration.parent is CSharpLocalDeclarationStatement)
    }

    private fun element(entry: CSharpMemberLookup.Entry, text: CSharpSymbolText, resolver: CSharpNameResolver, reduced: Boolean): LookupElement? {
        val name = entry.name
        return when (val first = entry.first) {
            is CSharpSymbol.Namespace -> build(name, AllIcons.Nodes.Package, name, null, null, NativeCSharpCompletion.TYPE + 1, null)
            is CSharpSymbol.SourceType -> {
                val info = first.info
                val presentable = if (info.arity > 0) "$name<${"".padEnd(info.arity - 1, ',')}>" else name
                build(name, typeIcon(info.kind), presentable, null, null, NativeCSharpCompletion.TYPE, if (info.arity > 0) NativeCSharpCalls.typeHandler(generic = true, constructed = false) else null)
            }
            is CSharpSymbol.LibraryType -> {
                val arity = first.type.ownArity
                val presentable = if (arity > 0) "$name<${"".padEnd(arity - 1, ',')}>" else name
                build(name, libraryTypeIcon(first.type.kind, first.type.isStatic), presentable, null, null, NativeCSharpCompletion.TYPE,
                    if (arity > 0) NativeCSharpCalls.typeHandler(generic = true, constructed = false) else null, strikeout = first.type.obsolete)
            }
            is CSharpSymbol.SourceMember -> {
                val kind = NativeCSharpMembers.kind(first.member)
                val type = runCatching { text.typeOf(first) }.getOrNull()
                when (kind) {
                    NativeCSharpMembers.Kind.METHOD -> {
                        val extension = resolver.isExtension(first)
                        val tail = tail(text.parameters(first, reduced && extension), entry.symbols.size)
                        val handler = NativeCSharpCalls.callHandler { entry.symbols.all(text::returnsNothing) to entry.symbols.any { text.parameters(it, reduced && resolver.isExtension(it))?.isNotEmpty() != false } }
                        build(name, AllIcons.Nodes.Method, name, tail, type, if (extension) EXTENSION else NativeCSharpCompletion.METHOD, handler)
                    }
                    NativeCSharpMembers.Kind.PROPERTY -> build(name, AllIcons.Nodes.Property, name, null, type, NativeCSharpCompletion.VALUE_MEMBER, null)
                    NativeCSharpMembers.Kind.CONSTANT -> build(name, AllIcons.Nodes.Constant, name, null, type, NativeCSharpCompletion.VALUE_MEMBER, null)
                    NativeCSharpMembers.Kind.EVENT, NativeCSharpMembers.Kind.FIELD -> build(name, AllIcons.Nodes.Field, name, null, type, NativeCSharpCompletion.VALUE_MEMBER, null)
                }
            }
            is CSharpSymbol.LibraryMember -> {
                val member = first.member
                val type = runCatching { text.typeOf(first) }.getOrNull()
                if (member.kind.isCallable) {
                    val extension = member.kind == IndexedMemberKind.EXTENSION_METHOD
                    val tail = tail(text.parameters(first, reduced), entry.symbols.size)
                    val handler = NativeCSharpCalls.callHandler { entry.symbols.all(text::returnsNothing) to entry.symbols.any { text.parameters(it, reduced)?.isNotEmpty() != false } }
                    build(name, AllIcons.Nodes.Method, name, tail, type, if (extension) EXTENSION else NativeCSharpCompletion.METHOD, handler, strikeout = member.obsolete)
                } else {
                    build(name, ImportCompletion.icon(member.kind), name, null, type, NativeCSharpCompletion.VALUE_MEMBER, null, strikeout = member.obsolete)
                }
            }
            is CSharpSymbol.Local -> null
        }
    }

    /** `(string value)`, with ` (+ 17 overloads)` after it when the name has more. */
    private fun tail(parameters: List<String>?, overloads: Int): String {
        val list = parameters?.joinToString(", ", "(", ")") ?: "()"
        return if (overloads > 1) "$list (+ ${overloads - 1})" else list
    }

    private fun typeIcon(kind: TypeKind?): Icon = when (kind) {
        TypeKind.INTERFACE -> AllIcons.Nodes.Interface
        TypeKind.ENUM -> AllIcons.Nodes.Enum
        TypeKind.RECORD, TypeKind.RECORD_STRUCT -> AllIcons.Nodes.Record
        TypeKind.DELEGATE -> AllIcons.Nodes.Lambda
        TypeKind.STATIC_CLASS -> AllIcons.Nodes.Static
        else -> AllIcons.Nodes.Class
    }

    private fun libraryTypeIcon(kind: IndexedTypeKind, static: Boolean): Icon = when (kind) {
        IndexedTypeKind.INTERFACE -> AllIcons.Nodes.Interface
        IndexedTypeKind.ENUM -> AllIcons.Nodes.Enum
        IndexedTypeKind.DELEGATE -> AllIcons.Nodes.Lambda
        IndexedTypeKind.STATIC_CLASS -> AllIcons.Nodes.Static
        else -> if (static) AllIcons.Nodes.Static else AllIcons.Nodes.Class
    }

    private fun build(
        lookup: String, icon: Icon, presentable: String, tail: String?, type: String?, priority: Double,
        handler: com.intellij.codeInsight.completion.InsertHandler<LookupElement>?, strikeout: Boolean = false,
    ): LookupElement {
        var builder = LookupElementBuilder.create(lookup).withIcon(icon).withPresentableText(presentable).withStrikeoutness(strikeout)
        if (tail != null) builder = builder.withTailText(tail, true)
        if (type != null) builder = builder.withTypeText(type)
        if (handler != null) builder = builder.withInsertHandler(handler)
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(builder, priority).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }
}
