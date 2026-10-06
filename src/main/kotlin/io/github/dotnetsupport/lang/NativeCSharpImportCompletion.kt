package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpObjectCreationExpression
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.ImportCompletion
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.semantic.CSharpMemberLookup
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpSymbolText
import io.github.dotnetsupport.suggest.SuggestionRules
import io.github.dotnetsupport.suggest.SuggestionStats
import java.util.Collections
import io.github.dotnetsupport.lsp.RoslynOptions
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * The types and extension methods of the native completion list that come from more than the stubs of the solution (0.1.87), as Rider and
 * Roslyn's import completion give them:
 *  - the types of the referenced assemblies in the namespaces the place sees (its namespaces, its `using`s, the implicit and global usings),
 *    with nothing typed too: `Li` / Ctrl+Space → `List<>` where `System.Collections.Generic` is imported. Read from the index of the
 *    assemblies namespace by namespace (a binary search each, remembered per set of references), never the whole index;
 *  - the types of the solution and of the assemblies in namespaces the place does not see, from the first letter typed, as rows
 *    `Order (in Shop.Models)`: choosing one adds the `using` (or writes the namespace in front of the name when the file sees another type
 *    of that name);
 *  - after a dot, the extension methods of namespaces that are not imported (Roslyn's `ExtensionMemberImportCompletionProvider`), of the
 *    assemblies by what they extend and of the solution by the type of their `this` parameter, matched with the receiver by the types of
 *    `lang/semantic` (bases, interfaces, generic `this T`), with their `using`;
 *  - at `[`, the attributes of the assemblies: those the place sees and, from the first letter, the others with their `using`.
 * What is not imported waits for a letter: the list of an empty prefix would be the whole index ([MIN_PREFIX], the contributor restarts the
 * completion at the first letter).
 */
object NativeCSharpImportCompletion {
    /** What is not imported is offered from this many letters typed. */
    const val MIN_PREFIX = 1

    /** `completion.dotnet_show_completion_items_from_unimported_namespaces` of the server's page: off, only what a `using` sees is listed. */
    val unimportedEnabled: Boolean get() = RoslynOptions.isOn("completion.dotnet_show_completion_items_from_unimported_namespaces")

    /** Of what is not imported, the rows a call gives at most, the most likely first: `System` first, then the shorter names. */
    const val MAX_UNIMPORTED = 150

    /** Under the keywords, as [ImportCompletion.PRIORITY]: what the file does not import yet. */
    const val UNIMPORTED = ImportCompletion.PRIORITY

    /** On the rows of what is not imported: they stand next to a row of the same name the place sees (`Timer (in System.Timers)`). */
    val NOT_IMPORTED: com.intellij.openapi.util.Key<String> = com.intellij.openapi.util.Key.create("dotnet.notImported")

    /** Whether [kind] gets something from here that depends on the letters typed: the contributor restarts at the first one. */
    fun waitsForLetters(kind: NativeCompletionKind): Boolean = kind in TYPE_PLACES || kind == NativeCompletionKind.MEMBER_ACCESS || kind == NativeCompletionKind.ATTRIBUTE

    private val TYPE_PLACES = setOf(NativeCompletionKind.TYPE, NativeCompletionKind.STATEMENT, NativeCompletionKind.EXPRESSION, NativeCompletionKind.MEMBER_START)

    /**
     * The types for the bare name at [place]: of the assemblies in the namespaces the place sees, of the solution in the namespaces seen only
     * through global usings (the syntactic list of [NativeCSharpCompletion] has the ones of the file's own `using`s), then, from the first
     * letter, the ones not imported. [taken]: the names the list has already (a type of the solution hides a library type of its name).
     * [bonus]: what the expected type and name add to an item.
     */
    fun types(
        place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher, taken: Set<String>, syntax: NativeCSharpResolver = NativeCSharpResolver(file),
        bonus: (String) -> Double = { 0.0 },
    ): List<LookupElement> = Collector(place, file, matcher, taken, syntax, bonus, attributes = false).collect().filterNot(CSharpCompletionExclusions::excludes)

    /** The attributes at `[`: of the assemblies the place sees, then the ones not imported, by the name without `Attribute`. */
    fun attributes(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher, taken: Set<String>, syntax: NativeCSharpResolver = NativeCSharpResolver(file)): List<LookupElement> =
        Collector(place, file, matcher, taken, syntax, { 0.0 }, attributes = true).collect().filterNot(CSharpCompletionExclusions::excludes)

    private class Collector(
        val place: NativeCSharpCompletionPlace, val file: CSharpFile, val matcher: PrefixMatcher, val taken: Set<String>, val syntax: NativeCSharpResolver,
        val bonus: (String) -> Double, val attributes: Boolean,
    ) {
        val at: PsiElement = place.name ?: place.leaf
        val semantic: CSharpNameResolver = CSharpSemanticSession(file.project).resolver(file)
        val visible: Set<String> = semantic.visibleNamespaces(at)
        val result = ArrayList<LookupElement>()
        // name -> qualified names of the visible types of that name: a type that is not imported but has the name of one is written qualified
        val visibleNames = HashMap<String, MutableSet<String>>()
        val seen = HashSet<String>()
        val afterNew = place.name?.parent is CSharpObjectCreationExpression

        fun collect(): List<LookupElement> {
            val prefix = matcher.prefix
            val assemblies = semantic.assemblies
            visibleLibrary(assemblies)
            val solution = solutionCandidates()
            for ((name, parts) in solution) for (part in parts) if (part.namespace in visible) visibleNames.getOrPut(name) { HashSet() } += part.qualifiedName
            for ((name, parts) in solution) for (part in parts) {
                if (part.namespace !in visible) continue
                ProgressManager.checkCanceled()
                // the syntactic list has what the file's own `using`s import; this adds what the global usings do
                if (name in taken || !seen.add(key(part.qualifiedName, part.arity))) continue
                val info = syntax.typeInfo(part.qualifiedName, part.arity) ?: continue
                result += sourceElement(name, info, part.namespace!!, imported = true)
            }
            if (prefix.length < MIN_PREFIX || !unimportedEnabled) return result
            val unimported = ArrayList<Pair<String, () -> LookupElement>>()
            for ((name, parts) in solution) for (part in parts) {
                val namespace = part.namespace ?: continue
                if (namespace in visible || !seen.add(key(part.qualifiedName, part.arity))) continue
                val info = syntax.typeInfo(part.qualifiedName, part.arity) ?: continue
                if (!accessible(info)) continue
                unimported += namespace to { sourceElement(name, info, namespace, imported = false) }
            }
            unimportedLibrary(assemblies, prefix, unimported)
            unimported.sortedWith(compareBy<Pair<String, () -> LookupElement>> { !isSystem(it.first) }.thenBy { it.first.length })
                .take(MAX_UNIMPORTED).forEach { result += it.second() }
            return result
        }

        // ---- the assemblies

        private fun visibleLibrary(assemblies: AssemblyIndexSet) {
            for (namespace in visible) {
                for (type in typesOf(assemblies, namespace)) {
                    ProgressManager.checkCanceled()
                    val name = shortName(type.simpleName) ?: continue
                    visibleNames.getOrPut(name) { HashSet() } += type.qualifiedName
                    if (name in taken || !matcher.prefixMatches(name) && !(attributes && matcher.prefixMatches(type.simpleName))) continue
                    if (!seen.add(key(type.qualifiedName, type.arity))) continue
                    result += libraryElement(name, type, imported = true)
                }
            }
        }

        private fun unimportedLibrary(assemblies: AssemblyIndexSet, prefix: String, into: MutableList<Pair<String, () -> LookupElement>>) {
            for (index in assemblies.indexes) {
                for (type in index.types(prefix, LIBRARY_LIMIT)) {
                    ProgressManager.checkCanceled()
                    if (type.namespace in visible || !offered(type)) continue
                    val name = shortName(type.simpleName) ?: continue
                    if (!matcher.prefixMatches(name) && !(attributes && matcher.prefixMatches(type.simpleName))) continue
                    if (!seen.add(key(type.qualifiedName, type.arity))) continue
                    into += type.namespace to { libraryElement(name, type, imported = false) }
                }
            }
        }

        /** The name the item is written by: an attribute without `Attribute`; null for what is not offered at the place. */
        private fun shortName(simpleName: String): String? {
            if (!attributes) return simpleName
            if (!simpleName.endsWith("Attribute") || simpleName == "Attribute") return null
            return simpleName.removeSuffix("Attribute")
        }

        private fun libraryElement(name: String, type: IndexedType, imported: Boolean): LookupElement {
            val kind = type.kind
            val constructed = afterNew && kind != IndexedTypeKind.INTERFACE && kind != IndexedTypeKind.STATIC_CLASS && !type.isStatic && !type.isAbstract
            return element(name, libraryIcon(type), type.arity, type.namespace, imported, constructed && !attributes, type.obsolete)
        }

        // ---- the solution

        private fun solutionCandidates(): Map<String, List<TypePart>> {
            val found = LinkedHashMap<String, List<TypePart>>()
            for ((name, _) in NativeCSharpTypeNames.candidates(file, if (attributes) null else matcher, syntax)) {
                ProgressManager.checkCanceled()
                val short = shortName(name) ?: continue
                if (!matcher.prefixMatches(short) && !(attributes && matcher.prefixMatches(name))) continue
                found[short] = syntax.typeParts(name).filter { it.namespace != null }
            }
            return found
        }

        /** A type of another project may be `internal`: what the stubs say is enough here, as for the visible ones. */
        private fun accessible(info: TypeInfo): Boolean = info.parts.none { "private" in it.modifiers || "file" in it.modifiers }

        private fun sourceElement(name: String, info: TypeInfo, namespace: String, imported: Boolean): LookupElement {
            val kind = info.kind
            val constructed = afterNew && kind != TypeKind.INTERFACE && kind != TypeKind.STATIC_CLASS
            return element(name, sourceIcon(kind), info.arity, namespace, imported, constructed && !attributes, false)
        }

        // ---- the rows

        private fun element(name: String, icon: Icon, arity: Int, namespace: String, imported: Boolean, constructed: Boolean, obsolete: Boolean): LookupElement {
            val generic = arity > 0 && !attributes
            val presentable = if (generic) "$name<${"".padEnd(arity - 1, ',')}>" else name
            val qualified = !imported && visibleNames[name].orEmpty().any { it != CSharpNameResolver.join(namespace, if (attributes) name + "Attribute" else name) }
            val typeHandler = if (generic || constructed) NativeCSharpCalls.typeHandler(generic, constructed) else null
            val tail = if (namespace.isEmpty()) null else if (imported) " ($namespace)" else " (in $namespace)"
            // the object tells two rows of one name apart: the platform takes equal elements for one
            var builder = LookupElementBuilder.create(CSharpNameResolver.join(namespace, name), name).withIcon(icon).withPresentableText(presentable).withStrikeoutness(obsolete)
            if (tail != null) builder = builder.withTailText(tail, true)
            if (!imported) {
                builder = builder.withInsertHandler(importHandler(namespace, qualified, typeHandler))
                builder.putUserData(SuggestionStats.SIGNALS, setOf(SuggestionRules.SIGNAL_INDEX))
                builder.putUserData(NOT_IMPORTED, namespace)
            } else if (typeHandler != null) {
                builder = builder.withInsertHandler(typeHandler)
            }
            builder.putUserData(NativeCSharpCompletion.NATIVE, true)
            val priority = (if (imported) NativeCSharpCompletion.TYPE else UNIMPORTED) + bonus(name)
            return PrioritizedLookupElement.withPriority(builder, priority).also {
                it.putUserData(NativeCSharpCompletion.NATIVE, true)
                if (!imported) it.putUserData(NOT_IMPORTED, namespace)
            }
        }
    }

    // ---- extension methods after a dot

    /**
     * The extension methods of namespaces the place does not import that a value left of the dot can call, one row per name and namespace:
     * `numbers.Shu|` → `Shuffle (in Shop.Collections)`; choosing it writes the call and the `using`. Nothing before the first letter.
     */
    fun extensions(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher, taken: Set<String>): List<LookupElement> {
        if (matcher.prefix.length < MIN_PREFIX || !unimportedEnabled) return emptyList()
        val name = place.name ?: return emptyList()
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val qualifier = CSharpMemberLookup(resolver).qualifierOf(name) ?: return emptyList()
        val receiver = when (qualifier) {
            is CSharpNameResolver.Qualifier.Value -> qualifier.type
            is CSharpNameResolver.Qualifier.ValueOrType -> qualifier.value
            else -> return emptyList()
        }
        val visible = resolver.visibleNamespaces(name)
        val groups = LinkedHashMap<Pair<String, String>, MutableList<CSharpSymbol>>()
        for (symbol in resolver.extensionMethodsFor(receiver, name, matcher::prefixMatches) { it !in visible }) {
            ProgressManager.checkCanceled()
            val (method, namespace) = when (symbol) {
                is CSharpSymbol.LibraryMember -> if (symbol.member.isHidden || symbol.member.type.isHidden) continue else symbol.member.name to symbol.member.type.namespace
                is CSharpSymbol.SourceMember -> ((symbol.element as? CSharpMethodDeclaration)?.identifier?.text ?: continue) to (resolver.namespaceOfMethod(symbol.element) ?: continue)
                else -> continue
            }
            if (method in taken || CSharpCompletionExclusions.excludes(namespace) || !fitsConstraints(resolver, symbol, receiver)) continue
            groups.getOrPut(method to namespace) { ArrayList() } += symbol
        }
        val text = CSharpSymbolText(resolver)
        return groups.entries.sortedWith(compareBy<Map.Entry<Pair<String, String>, MutableList<CSharpSymbol>>> { !isSystem(it.key.second) }.thenBy { it.key.first })
            .take(MAX_UNIMPORTED).map { (key, symbols) ->
                val (method, namespace) = key
                val first = symbols.first()
                val inferred = NativeCSharpMemberCompletion.inferred(first, receiver, resolver)
                val parameters = runCatching { text.parameters(first, true, inferred) }.getOrNull()
                val list = parameters?.joinToString(", ", "(", ")") ?: "()"
                val tail = (if (symbols.size > 1) "$list (+ ${symbols.size - 1})" else list) + " (in $namespace)"
                val call = NativeCSharpCalls.callHandler { symbols.all(text::returnsNothing) to symbols.any { text.parameters(it, true)?.isNotEmpty() != false } }
                val obsolete = symbols.all { it is CSharpSymbol.LibraryMember && it.member.obsolete }
                var builder = LookupElementBuilder.create("$namespace.$method", method).withIcon(AllIcons.Nodes.Method).withPresentableText(method).withTailText(tail, true)
                    .withStrikeoutness(obsolete).withInsertHandler(importHandler(namespace, false, call))
                runCatching { text.typeOf(first, inferred) }.getOrNull()?.let { builder = builder.withTypeText(it) }
                builder.putUserData(NativeCSharpCompletion.NATIVE, true)
                builder.putUserData(SuggestionStats.SIGNALS, setOf(SuggestionRules.SIGNAL_INDEX))
                builder.putUserData(NOT_IMPORTED, namespace)
                PrioritizedLookupElement.withPriority(builder, UNIMPORTED).also {
                    it.putUserData(NativeCSharpCompletion.NATIVE, true)
                    it.putUserData(NOT_IMPORTED, namespace)
                }
            }
    }

    /**
     * Whether the type arguments the receiver gives a generic extension method of the assemblies meet their constraints: `Elements<T>(this
     * IEnumerable<T>) where T : XContainer` is not for a `List<int>` (Roslyn leaves it out; the lookup of imported ones does not ask this).
     * What is not known is taken as fitting.
     */
    private fun fitsConstraints(resolver: CSharpNameResolver, symbol: CSharpSymbol, receiver: io.github.dotnetsupport.lang.semantic.SemanticType): Boolean {
        val member = (symbol as? CSharpSymbol.LibraryMember)?.member ?: return true
        if (member.arity == 0) return true
        val self = member.parameters.firstOrNull()?.typeRef ?: return true
        val bound = arrayOfNulls<io.github.dotnetsupport.lang.semantic.SemanticType>(member.arity)
        when (self) {
            is IndexedTypeRef.TypeParameter -> if (self.ofMethod) bound[self.index] = receiver
            is IndexedTypeRef.Generic -> {
                val instance = resolver.expressions.instanceOf(receiver, self.definition.fullName) ?: return true
                for ((argument, actual) in self.arguments.zip(instance.arguments)) {
                    if (argument is IndexedTypeRef.TypeParameter && argument.ofMethod && actual != null) bound[argument.index] = actual
                }
            }
            else -> return true
        }
        val parameters = member.typeParameters
        for ((index, actual) in bound.withIndex()) {
            actual ?: continue
            val parameter = parameters.getOrNull(index) ?: continue
            if (parameter.isStruct && actual is io.github.dotnetsupport.lang.semantic.SemanticType.Library && actual.type.kind == IndexedTypeKind.CLASS) return false
            for (constraint in parameter.constraints) {
                if (mentionsTypeParameter(constraint)) continue
                val target = resolver.fromRef(constraint, emptyList()) ?: continue
                if (resolver.conversion(actual, target) == CSharpNameResolver.Conversion.NONE) return false
            }
        }
        return true
    }

    private fun mentionsTypeParameter(reference: IndexedTypeRef): Boolean = when (reference) {
        is IndexedTypeRef.TypeParameter -> true
        is IndexedTypeRef.Generic -> reference.arguments.any(::mentionsTypeParameter)
        is IndexedTypeRef.ArrayOf -> mentionsTypeParameter(reference.element)
        else -> false
    }

    // ---- choosing

    /**
     * What the item writes ([then]: the brackets, the call), and the `using` of [namespace] at the top of the file — or, when the file sees
     * another type of the name ([qualified]), the namespace in front of the name, as Rider writes it to keep the name unambiguous.
     */
    fun importHandler(namespace: String, qualified: Boolean, then: InsertHandler<LookupElement>?): InsertHandler<LookupElement> = InsertHandler { context, item ->
        if (qualified) context.document.insertString(context.startOffset, "$namespace.")
        then?.handleInsert(context, item)
        if (!qualified) addUsing(context, namespace)
    }

    /** The caret stays where it is in the code: it moves with what is inserted above it. */
    fun addUsing(context: InsertionContext, namespace: String) {
        val document = context.document
        CSharpUsings.insertion(document.charsSequence, namespace)?.let { document.insertString(it.offset, it.text) }
        context.commitDocument()
    }

    // ---- the index, namespace by namespace

    private const val LIBRARY_LIMIT = 400

    private val BY_NAMESPACE: MutableMap<AssemblyIndexSet, ConcurrentHashMap<String, List<IndexedType>>> = Collections.synchronizedMap(WeakHashMap())

    /** The types of the assemblies at the top of [namespace] a place may name, each once; remembered per set of references. */
    fun typesOf(assemblies: AssemblyIndexSet, namespace: String): List<IndexedType> {
        val perSet = BY_NAMESPACE.getOrPut(assemblies) { ConcurrentHashMap() }
        return perSet.getOrPut(namespace) {
            val seen = HashSet<String>()
            assemblies.indexes.flatMap { it.typesIn(namespace) }.filter { offered(it) && seen.add(key(it.qualifiedName, it.arity)) }
        }
    }

    private fun offered(type: IndexedType): Boolean =
        type.declaringType == null && !type.isHidden && !type.isProtected && !type.simpleName.startsWith("<") && type.fullName != "System.Void"

    private fun key(qualifiedName: String, arity: Int): String = "$qualifiedName`$arity"

    private fun isSystem(namespace: String): Boolean = namespace == "System" || namespace.startsWith("System.")

    private fun libraryIcon(type: IndexedType): Icon = when (type.kind) {
        IndexedTypeKind.INTERFACE -> AllIcons.Nodes.Interface
        IndexedTypeKind.ENUM -> AllIcons.Nodes.Enum
        IndexedTypeKind.DELEGATE -> AllIcons.Nodes.Lambda
        IndexedTypeKind.STATIC_CLASS -> AllIcons.Nodes.Static
        else -> if (type.isStatic) AllIcons.Nodes.Static else if (type.isRecord) AllIcons.Nodes.Record else AllIcons.Nodes.Class
    }

    private fun sourceIcon(kind: TypeKind?): Icon = when (kind) {
        TypeKind.INTERFACE -> AllIcons.Nodes.Interface
        TypeKind.ENUM -> AllIcons.Nodes.Enum
        TypeKind.RECORD, TypeKind.RECORD_STRUCT -> AllIcons.Nodes.Record
        TypeKind.DELEGATE -> AllIcons.Nodes.Lambda
        TypeKind.STATIC_CLASS -> AllIcons.Nodes.Static
        else -> AllIcons.Nodes.Class
    }
}
