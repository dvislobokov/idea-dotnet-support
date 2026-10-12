package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpLocalDeclarationStatement
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
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
import io.github.dotnetsupport.lang.semantic.SemanticType
import io.github.dotnetsupport.ml.CSharpMlCandidateKind
import io.github.dotnetsupport.ml.CSharpMlScope
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

    /** [inaccessible]: only the members the place does not see, grayed (the second Ctrl+Space, [NativeCSharpDoubleCompletion]). */
    fun items(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher, inaccessible: Boolean = false): List<LookupElement> {
        val name = place.name ?: return emptyList()
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val lookup = CSharpMemberLookup(resolver)
        val qualifier = lookup.qualifierOf(name) ?: return emptyList()
        val entries = lookup.entries(qualifier, name, typesOnly(name), matcher, inaccessibleToo = inaccessible).filter { it.inaccessible == inaccessible }
        val text = CSharpSymbolText(resolver)
        val receiver = receiverOf(qualifier)
        return entries.flatMap(::byArity).mapNotNull { entry ->
            ProgressManager.checkCanceled()
            element(entry, text, resolver, receiver != null, receiver, file)
        }
    }

    /** The members of the own type that the syntax does not see (of library bases, extension methods): `this.` of a class derived from `Exception`. */
    fun thisItems(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher, inaccessible: Boolean = false): List<LookupElement> {
        val name = place.name ?: return emptyList()
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val lookup = CSharpMemberLookup(resolver)
        val qualifier = lookup.qualifierOf(name) ?: return emptyList()
        val text = CSharpSymbolText(resolver)
        // `base.` of an override (`base.ExecuteAsync(...)` of a BackgroundService): the members of library bases too; no extension methods there (CS0175)
        val entries = lookup.entries(qualifier, name, false, matcher, throughThis = true, inaccessibleToo = inaccessible)
            .filter { entry -> entry.inaccessible == inaccessible && (!place.base || entry.symbols.any { !resolver.isExtension(it) }) }
        return entries.flatMap(::byArity).mapNotNull { element(it, text, resolver, true, receiverOf(qualifier), file) }
    }

    /**
     * One row per name and arity, as Rider lists `AddSingleton(Type, Type)`, `AddSingleton<TService>()` and `AddSingleton<TService, TImplementation>()`
     * apart (0.1.148): one row per name hid every generic form behind `(+ N)` of the first overload of the assembly.
     */
    private fun byArity(entry: CSharpMemberLookup.Entry): List<CSharpMemberLookup.Entry> {
        if (entry.symbols.size < 2 || !entry.symbols.all(::isMethod)) return listOf(entry)
        val groups = entry.symbols.groupBy { typeParameters(it).size }
        if (groups.size < 2) return listOf(entry)
        return groups.toSortedMap().map { (_, symbols) -> CSharpMemberLookup.Entry(entry.name, symbols, entry.inaccessible) }
    }

    private fun isMethod(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceMember -> NativeCSharpMembers.kind(symbol.member) == NativeCSharpMembers.Kind.METHOD
        is CSharpSymbol.LibraryMember -> symbol.member.kind.isCallable
        else -> false
    }

    /** The type parameters of a method by name (`TService, TImplementation`); empty for a non-generic one. */
    private fun typeParameters(symbol: CSharpSymbol): List<String> = when (symbol) {
        is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.typeParameterList?.parameters?.map { it.identifier?.text.orEmpty() }.orEmpty()
        is CSharpSymbol.LibraryMember -> if (symbol.member.arity > 0) symbol.member.typeParameters.map { it.name } else emptyList()
        else -> emptyList()
    }

    /**
     * The text and the insertion of a method row: `AddSingleton<TService, TImplementation>` for a generic method, and `<>()` with the caret
     * between the angle brackets when the parameters of some overload of the row cannot tell the type arguments (`AddSingleton<|>()` for
     * `AddSingleton<TService>()` next to `AddSingleton<TService>(TService instance)`, as in Rider); `Select(|)` when every overload infers them.
     */
    private fun methodRow(
        name: String, symbols: List<CSharpSymbol>, parametersOf: (CSharpSymbol) -> List<String>?, shape: () -> Pair<Boolean, Boolean>,
    ): Triple<Any, String, com.intellij.codeInsight.completion.InsertHandler<LookupElement>> {
        val typeParameters = typeParameters(symbols.first())
        if (typeParameters.isEmpty()) return Triple(name, name, NativeCSharpCalls.callHandler(shape))
        val presentable = typeParameters.joinToString(", ", "$name<", ">")
        val explicit = symbols.any { CSharpCalls.needsTypeArguments(typeParameters(it), parametersOf(it).orEmpty()) }
        return Triple("$name<${typeParameters.size}>", presentable, if (explicit) NativeCSharpCalls.genericCallHandler(shape) else NativeCSharpCalls.callHandler(shape))
    }

    /** `ConsoleColor.Black|` at the end of a statement: its `;` (and an open `)`), as the enum rows of an expected type do. */
    private val CLOSES_STATEMENT = com.intellij.codeInsight.completion.InsertHandler<LookupElement> { context, _ -> NativeCSharpExpectedCompletion.closeStatement(context) }

    /** The value left of the dot; null when a type or a namespace is there. */
    fun receiverOf(qualifier: CSharpNameResolver.Qualifier): SemanticType? = when (qualifier) {
        is CSharpNameResolver.Qualifier.Value -> qualifier.type
        is CSharpNameResolver.Qualifier.ValueOrType -> qualifier.value
        else -> null
    }

    /** `A.B.|` where only a type stands (a parameter's type, a base list); not the type of a local at a statement start, which may be a call. */
    private fun typesOnly(name: CSharpSimpleName): Boolean {
        if (name.parent !is CSharpQualifiedName) return false
        var top: PsiElement = name
        while (top.parent is CSharpQualifiedName) top = top.parent
        val declaration = top.parent as? CSharpVariableDeclaration
        return !(declaration != null && declaration.type == top && declaration.parent is CSharpLocalDeclarationStatement)
    }

    private fun element(entry: CSharpMemberLookup.Entry, text: CSharpSymbolText, resolver: CSharpNameResolver, reduced: Boolean, receiver: SemanticType?, file: CSharpFile): LookupElement? {
        val element = row(entry, text, resolver, reduced, receiver)?.let { NativeCSharpMlInfo.attach(it, shape(entry.first), NativeCSharpMlInfo.priorityOf(it), 0, file) } ?: return null
        return if (entry.inaccessible) NativeCSharpDoubleCompletion.inaccessible(element) else element
    }

    private fun row(entry: CSharpMemberLookup.Entry, text: CSharpSymbolText, resolver: CSharpNameResolver, reduced: Boolean, receiver: SemanticType?): LookupElement? {
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
                val inferred = inferred(first, receiver, resolver)
                val type = runCatching { text.typeOf(first, inferred) }.getOrNull()
                when (kind) {
                    NativeCSharpMembers.Kind.METHOD -> {
                        val extension = resolver.isExtension(first)
                        val parameters = text.parameters(first, reduced && extension, inferred)
                        val tail = tail(parameters, entry.symbols.size)
                        val parametersOf = { s: CSharpSymbol -> text.parameters(s, reduced && resolver.isExtension(s)) }
                        val (key, presentable, handler) = methodRow(name, entry.symbols, parametersOf) { entry.symbols.all(text::returnsNothing) to entry.symbols.any { parametersOf(it)?.isNotEmpty() != false } }
                        build(name, AllIcons.Nodes.Method, presentable, tail, type, if (extension) EXTENSION else NativeCSharpCompletion.METHOD, handler, key = key)
                    }
                    NativeCSharpMembers.Kind.PROPERTY -> build(name, AllIcons.Nodes.Property, name, null, type, NativeCSharpCompletion.VALUE_MEMBER, null)
                    NativeCSharpMembers.Kind.CONSTANT -> build(name, AllIcons.Nodes.Constant, name, null, type, NativeCSharpCompletion.VALUE_MEMBER, CLOSES_STATEMENT)
                    NativeCSharpMembers.Kind.EVENT, NativeCSharpMembers.Kind.FIELD -> build(name, AllIcons.Nodes.Field, name, null, type, NativeCSharpCompletion.VALUE_MEMBER, null)
                }
            }
            is CSharpSymbol.LibraryMember -> {
                val member = first.member
                val inferred = inferred(first, receiver, resolver)
                val type = runCatching { text.typeOf(first, inferred) }.getOrNull()
                if (member.kind.isCallable) {
                    val extension = member.kind == IndexedMemberKind.EXTENSION_METHOD
                    val parameters = text.parameters(first, reduced, inferred)
                    val tail = tail(parameters, entry.symbols.size)
                    val parametersOf = { s: CSharpSymbol -> text.parameters(s, reduced) }
                    val (key, presentable, handler) = methodRow(name, entry.symbols, parametersOf) { entry.symbols.all(text::returnsNothing) to entry.symbols.any { parametersOf(it)?.isNotEmpty() != false } }
                    build(name, AllIcons.Nodes.Method, presentable, tail, type, if (extension) EXTENSION else NativeCSharpCompletion.METHOD, handler, strikeout = member.obsolete, key = key)
                } else {
                    val handler = if (member.kind == IndexedMemberKind.ENUM_MEMBER || member.kind == IndexedMemberKind.CONSTANT) CLOSES_STATEMENT else null
                    build(name, ImportCompletion.icon(member.kind), name, null, type, NativeCSharpCompletion.VALUE_MEMBER, handler, strikeout = member.obsolete)
                }
            }
            is CSharpSymbol.Local -> null
        }
    }

    /** What the row is for the ML ranker ([NativeCSharpMlInfo]): the kind of the first symbol of the name, its staticness, the receiver's scope. */
    private fun shape(symbol: CSharpSymbol): NativeCSharpMlInfo.Shape = when (symbol) {
        is CSharpSymbol.Namespace -> NativeCSharpMlInfo.Shape(CSharpMlCandidateKind.NAMESPACE, CSharpMlScope.RECEIVER)
        is CSharpSymbol.SourceType -> NativeCSharpMlInfo.Shape(CSharpMlCandidateKind.TYPE, CSharpMlScope.RECEIVER, symbol.info.kind == TypeKind.STATIC_CLASS) { symbol.info.targets().firstOrNull() }
        is CSharpSymbol.LibraryType -> NativeCSharpMlInfo.Shape(CSharpMlCandidateKind.TYPE, CSharpMlScope.RECEIVER, symbol.type.isStatic)
        is CSharpSymbol.SourceMember ->
            NativeCSharpMlInfo.Shape(NativeCSharpMembers.mlKind(NativeCSharpMembers.kind(symbol.member)), CSharpMlScope.RECEIVER, NativeCSharpMembers.isStatic(symbol.member)) { symbol.element }
        is CSharpSymbol.LibraryMember -> {
            val kind = when (symbol.member.kind) {
                IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD, IndexedMemberKind.CONSTRUCTOR, IndexedMemberKind.OPERATOR -> CSharpMlCandidateKind.METHOD
                IndexedMemberKind.PROPERTY, IndexedMemberKind.INDEXER -> CSharpMlCandidateKind.PROPERTY
                IndexedMemberKind.FIELD -> CSharpMlCandidateKind.FIELD
                IndexedMemberKind.CONSTANT, IndexedMemberKind.ENUM_MEMBER -> CSharpMlCandidateKind.CONSTANT
                IndexedMemberKind.EVENT -> CSharpMlCandidateKind.EVENT
            }
            NativeCSharpMlInfo.Shape(kind, CSharpMlScope.RECEIVER, symbol.member.isStatic)
        }
        is CSharpSymbol.Local -> NativeCSharpMlInfo.Shape(CSharpMlCandidateKind.LOCAL, CSharpMlScope.LOCAL) { symbol.symbol.declaration }
    }

    /** The type arguments of an extension method that the receiver fixes (`ToImmutableArray()` of a `List<Order>`: `Order`); empty for the rest. */
    fun inferred(symbol: CSharpSymbol, receiver: SemanticType?, resolver: CSharpNameResolver): List<SemanticType?> =
        if (receiver == null) emptyList() else runCatching { resolver.expressions.receiverTypeArguments(symbol, receiver) }.getOrDefault(emptyList())

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
        /** What tells the rows of one name apart (the arity of a method): equal builders of one lookup string collapse into one. */
        key: Any = lookup,
    ): LookupElement {
        var builder = LookupElementBuilder.create(key, lookup).withIcon(icon).withPresentableText(presentable).withStrikeoutness(strikeout)
        if (tail != null) builder = builder.withTailText(tail, true)
        if (type != null) builder = builder.withTypeText(type)
        if (handler != null) builder = builder.withInsertHandler(handler)
        builder.putUserData(NativeCSharpCompletion.NATIVE, true)
        if (key != lookup) builder.putUserData(NativeCSharpCompletion.ROW_KEY, key.toString())
        return PrioritizedLookupElement.withPriority(builder, priority).also {
            it.putUserData(NativeCSharpCompletion.NATIVE, true)
            if (key != lookup) it.putUserData(NativeCSharpCompletion.ROW_KEY, key.toString())
        }
    }
}
