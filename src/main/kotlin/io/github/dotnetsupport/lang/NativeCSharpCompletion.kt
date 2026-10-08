package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpStubElementImpl
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.ml.CSharpMlCandidateKind
import io.github.dotnetsupport.ml.CSharpMlCompletionRanker
import io.github.dotnetsupport.ml.CSharpMlScope
import javax.swing.Icon

/**
 * COMPLETION on csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9, task A6), the syntactic part: keywords by place, the names in scope
 * without a dot (locals, parameters, local functions, labels after `goto`, type parameters, members of the enclosing types with their
 * partial parts and base classes of the solution, members of `using static` types, types of the solution the file sees), `override` /
 * `partial` members, names of a variable after its type, and the common calls of a non-`async` method that returns a task
 * ([NativeCSharpCommonCalls]). The place comes from [NativeCSharpCompletionPlace], the names from [NativeCSharpResolver] and
 * [NativeCSharpScopes] (read only); no index but the stubs of the solution, so it answers before the server has loaded anything.
 *
 * Order (higher first, as Rider): what is wanted at the caret (the declared type of the variable being initialized, the return type after
 * `return`, the type and name of the parameter of a method of the solution being called) — then locals, parameters, local functions,
 * members (fields and properties above methods), type parameters, types, keywords last. The numbers are those of
 * `RoslynCompletionPolicy.priority` for the server's kinds, so the two lists interleave by kind.
 *
 * How it meets the server — nothing is lost. When the switch is NATIVE the language server still completes (`RoslynCompletionSupport`
 * runs whatever the switch), and this contributor stands in front of it (`first, after dotnetCaseInsensitive`; the LSP contributor of
 * the platform is `last`). It adds its items, then runs the contributors after it and passes on their items except those of the server
 * ([isServerItem]) that the native list already has:
 *  - an item whose name (the lookup string up to `(` or `<`, without `@`) is the name of a native item: the native one stays, with its
 *    kind and its rank;
 *  - where the tree decides which keywords are legal ([NativeCSharpCompletionPlace.keywordsAreNative]: a statement, an expression, a
 *    type, a member's start...), every keyword item of the server: the native list has the legal ones.
 * Members after a dot are [NativeCSharpMemberCompletion]'s (task C3: the semantics of `lang/semantic`), and where it cannot type the left
 * side it adds nothing. Types and members of referenced assemblies without a dot, unimported types, snippets, lambdas, `await Method()`:
 * the server's (when it is ready) and the index of assemblies', untouched; where the native part has no place (in an accessor list) it
 * adds nothing and passes everything. The targets of `using` directives and `using var` are [NativeCSharpUsingCompletion]'s (task A8). Items of other contributors (live templates, postfix templates, the index of
 * assemblies) are never dropped. The switch ROSLYN or a file of the heuristic tree: this contributor does nothing.
 */
class NativeCSharpCompletionContributor : CompletionContributor() {
    // `status == `, `case `: the list of the expected enum opens by itself (0.1.88, see NativeCSharpExpectedCompletion.opensAfterSpace)
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean = NativeCSharpExpectedCompletion.invokesAutoPopup(position, typeChar)

    override fun fillCompletionVariants(parameters: CompletionParameters, given: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original.project)) return
        val file = parameters.position.containingFile as? CSharpFile ?: return
        if (file.compilationUnit == null) return
        val place = NativeCSharpCompletionPlace.of(parameters.position) ?: return
        // `catch (|`: what derives from Exception is the first group, whatever the platform lifts (0.1.92)
        val result = if (place.kind == NativeCompletionKind.TYPE && NativeCSharpExpectedCompletion.Analysis(place, file).typeRole == NativeCSharpExpectedCompletion.TypeRole.EXCEPTION_FIRST)
            NativeCSharpExpectedCompletion.exceptionsFirst(parameters, given) else given
        if (!NativeCSharpCompletion.opensByItself(parameters, result.prefixMatcher.prefix, place.kind, place.modifiers)) return
        // Ctrl+Shift+Space: only what fits the expected type (0.1.88); where it is not known, the usual list
        if (parameters.completionType == CompletionType.SMART) {
            NativeCSharpExpectedCompletion.smartItems(place, file, result.prefixMatcher)?.let { smart ->
                smart.forEach(result::addElement)
                NativeCSharpDoubleCompletion.smart(parameters, place, file, result)   // the second press: chains (0.1.96)
                result.stopHere()
                return
            }
        }
        val excluded = HashSet<String>()
        // what is not imported waits for the first letter ([NativeCSharpImportCompletion.MIN_PREFIX])
        if (result.prefixMatcher.prefix.isEmpty() && NativeCSharpImportCompletion.waitsForLetters(place.kind)) {
            result.restartCompletionOnPrefixChange(com.intellij.patterns.StandardPatterns.string().withLength(NativeCSharpImportCompletion.MIN_PREFIX))
        }
        val items = NativeCSharpCompletion.items(place, file, result.prefixMatcher, excluded)
        // the ML ranker (CSharpMlCompletionRanker, ML build) needs the whole list for its list-relative features: with it active the items
        // are held back and added scored once the other contributors have answered; without it they go in as they are
        val ml = CSharpMlCompletionRanker.batch(parameters, result)
        for (item in items) ml?.add(item) ?: result.addElement(item)
        val names = items.flatMapTo(HashSet()) { item -> item.allLookupStrings.map(NativeCSharpCompletion::nameOf) }
        names += excluded
        NativeCSharpDoubleCompletion.basic(parameters, place, file, result, names)   // the second press: inaccessible members, unreferenced types (0.1.96)
        // `override |`, `override str|`: the members to override only — no `struct`, `class` or a template of another contributor (0.1.126)
        val afterOverride = place.kind == NativeCompletionKind.MEMBER_START && "override" in place.modifiers
        result.runRemainingContributors(parameters) { found ->
            if (afterOverride && NativeCSharpCompletion.isKeywordOrTemplate(found.lookupElement)) return@runRemainingContributors
            if (!NativeCSharpCompletion.isDuplicate(found.lookupElement, names, place.keywordsAreNative)) ml?.pass(found) ?: result.passResult(found)
        }
        ml?.flush()
        result.stopHere()
    }
}

object NativeCSharpCompletion {
    /** The items of the list made by the plugin's tree: tests and the merge tell them by this key. */
    val NATIVE: Key<Boolean> = Key.create("dotnet.nativeCompletion")

    /** The option of the server's page the names after a type (`Person |`, `foreach (var |`, parameters) obey, as the server's name provider. */
    const val NAME_SUGGESTIONS = "completion.dotnet_show_name_completion_suggestions"

    /** Put by the client of the server on its items (`RoslynCompletionSupport`): what [isDuplicate] may drop. */
    val SERVER: Key<Boolean> = Key.create("dotnet.serverCompletion")

    /**
     * Whether the native list is shown at a list that opened by itself with nothing typed yet. The client of the server opens it on every
     * trigger character of Roslyn (`{`, `(`, `[`, `:`, `<`, a space…), and Roslyn answers most of them with nothing: the native list of
     * the place would pop up after `GetStringAsync(...){`. As in Rider, a list opens by itself after `.`, after `[` of an attribute, and
     * after a space where a type, a name after a type, a keyword, a namespace of `using` or an attribute is expected (`new `), and after
     * `override ` / `partial ` at the start of a member (the members to override, the partial methods; [CSharpSpaceAutoPopupHandler] opens it).
     * Ctrl+Space (an explicit call) and a typed prefix always get the list.
     */
    fun opensByItself(parameters: CompletionParameters, prefix: String, kind: NativeCompletionKind, modifiers: List<String> = emptyList()): Boolean {
        if (!parameters.isAutoPopup || prefix.isNotEmpty()) return true
        if (CSharpCompletionAutoPopup.opensAt(parameters, kind)) return true
        val text = parameters.editor.document.charsSequence
        return when (text.getOrNull(parameters.offset - 1)) {
            '.' -> true
            '[' -> kind == NativeCompletionKind.ATTRIBUTE
            ' ', '\t' -> kind == NativeCompletionKind.TYPE || kind == NativeCompletionKind.DECLARATION_NAME || kind == NativeCompletionKind.KEYWORDS_ONLY ||
                kind == NativeCompletionKind.USING_DIRECTIVE || kind == NativeCompletionKind.ATTRIBUTE ||
                kind == NativeCompletionKind.MEMBER_START && modifiers.lastOrNull().let { it == "override" || it == "partial" || it == "new" } ||
                kind == NativeCompletionKind.EXPRESSION && NativeCSharpExpectedCompletion.opensAfterSpace(parameters)
            else -> false
        }
    }

    /** The object of the lookup elements of the LSP client of the platform. */
    const val SERVER_OBJECT = "com.intellij.platform.lsp.impl.features.completion.LspCompletionObject"

    // the kinds of the server for the same kinds (RoslynCompletionPolicy.priority): variables 40, methods 30, types 20, keywords 0
    const val LOCAL = 48.0
    const val PARAMETER = 46.0
    const val LOCAL_FUNCTION = 44.0
    const val VALUE_MEMBER = 40.0
    const val METHOD = 30.0
    const val TYPE_PARAMETER = 22.0
    const val TYPE = 20.0
    const val KEYWORD = 0.0
    const val DECLARATION = 50.0
    const val COMMON_CALL = 300.0
    private const val MAX_INITIALIZER_ROWS = 8

    // what fits the place goes up over its kind, as RoslynCompletionRanking does for the server's items
    const val EXPECTED_TYPE = 25.0
    const val NAME_EXACT = 30.0
    const val NAME_PARTIAL = 12.0

    /** The name of an item as the lists compare them: `Equals(object? obj)` → `Equals`, `List<>` → `List`, `@event` → `event`. */
    fun nameOf(lookupString: String): String = lookupString.removePrefix("@").substringBefore('(').substringBefore('<').trim()

    /** A server item the native list already has (see the class comment of [NativeCSharpCompletionContributor]). */
    fun isDuplicate(element: LookupElement, nativeNames: Set<String>, keywordsAreNative: Boolean): Boolean {
        if (!isServerItem(element)) return false
        // the server's `override` / `partial` members come with an empty lookup string, named by the text shown (`Describe(int digits)`)
        val name = nameOf(element.lookupString.ifBlank { LookupElementPresentation.renderElement(element).itemText.orEmpty() })
        // a named argument (`amount:`, the lookup string `amount`) is not the local of the same name the native list has, but its own `amount:` (NativeCSharpArgumentCompletion)
        if (name.isNotEmpty() && LookupElementPresentation.renderElement(element).itemText?.endsWith(":") == true) return "$name:" in nativeNames
        if (name.isNotEmpty() && name in nativeNames) return true
        return keywordsAreNative && element.lookupString in NativeCSharpCompletionPlace.ALL_KEYWORDS
    }

    /** A keyword or a live template of another contributor: nothing of that goes after `override`. */
    fun isKeywordOrTemplate(element: LookupElement): Boolean {
        if (element.lookupString in NativeCSharpCompletionPlace.ALL_KEYWORDS) return true
        var current: LookupElement? = element
        while (current != null) {
            if (current.javaClass.name.contains("LiveTemplateLookupElement")) return true
            current = (current as? LookupElementDecorator<*>)?.delegate
        }
        return false
    }

    fun isServerItem(element: LookupElement): Boolean {
        var current: LookupElement? = element
        while (current != null) {
            if (current.getUserData(SERVER) == true || current.`object`?.javaClass?.name == SERVER_OBJECT) return true
            current = (current as? LookupElementDecorator<*>)?.delegate
        }
        return false
    }

    /** Everything the native list has at [place] of [file] (the copy the platform completes in). */
    /**
     * [excluded] gets the names left out on purpose (a local that is not disposable at `using (|`): the server's items of those names
     * go too.
     */
    fun items(place: NativeCSharpCompletionPlace, file: CSharpFile, matcher: PrefixMatcher, excluded: MutableSet<String>? = null): List<LookupElement> {
        val builder = Builder(place, file, matcher)
        builder.build()
        excluded?.addAll(builder.excluded)
        return builder.items
    }

    private class Builder(val place: NativeCSharpCompletionPlace, val file: CSharpFile, val matcher: PrefixMatcher) {
        val items = ArrayList<LookupElement>()
        val excluded = HashSet<String>()
        private val added = HashSet<String>()
        private val resolver by lazy { NativeCSharpResolver(file) }
        private val at: PsiElement get() = place.name ?: place.leaf
        // the semantics of the place (0.1.88): the expected type where the tree alone does not say it
        private val analysis by lazy { NativeCSharpExpectedCompletion.Analysis(place, file) }
        private val expected by lazy { NativeCSharpExpectations.at(place, resolver) ?: NativeCSharpExpectedCompletion.rankingExpected(analysis) }
        // the resource of `using (|` / `using var x = |`: what is known not to be disposable is left out (A8, the types of C2)
        private val resource by lazy { NativeCSharpUsingChecks.resourcePlace(place)?.let { async -> NativeCSharpUsingChecks.Filter(file, async, at) } }

        fun build() {
            // `new Order { |`, `o with { |`, `o is { |`: the members to name, nothing else
            NativeCSharpExpectedCompletion.initializerMembers(analysis)?.let { members ->
                members.forEach(::add)
                // `Name = user.Name,` and "Map all remaining members from user" (0.1.134)
                NativeCSharpMappingCompletion.initializerItems(analysis).forEach(::add)
                return
            }
            when (place.kind) {
                NativeCompletionKind.KEYWORDS_ONLY -> keywords(place.keywords)
                NativeCompletionKind.LABEL -> {
                    labels()
                    if (PsiTreeUtil.getParentOfType(place.leaf, CSharpSwitchStatement::class.java) != null) keywords(listOf("case", "default"))
                }
                NativeCompletionKind.DECLARATION_NAME -> {
                    declarationNames()
                    keywords(place.keywords)
                }
                NativeCompletionKind.THIS_MEMBERS -> {
                    thisMembers()
                    NativeCSharpMemberCompletion.thisItems(place, file, matcher).forEach(::add)
                }
                NativeCompletionKind.MEMBER_ACCESS -> {
                    NativeCSharpMemberCompletion.items(place, file, matcher).forEach(::add)
                    imports(NativeCSharpImportCompletion.extensions(place, file, matcher, added))
                }
                NativeCompletionKind.ATTRIBUTE -> {
                    attributeTypes()
                    imports(NativeCSharpImportCompletion.attributes(place, file, matcher, added, resolver))
                }
                NativeCompletionKind.MEMBER_START -> memberStart()
                NativeCompletionKind.TOP_LEVEL -> {
                    NativeCSharpUsingCompletion.topItems(place).forEach(::add)
                    keywords(NativeCSharpKeywords.topLevel(place.modifiers))
                }
                NativeCompletionKind.USING_DIRECTIVE -> NativeCSharpUsingCompletion.directiveItems(place, file, matcher).forEach(::add)
                NativeCompletionKind.TYPE -> {
                    NativeCSharpExpectedCompletion.typeItems(analysis).forEach(::add)
                    typeParameters()
                    nestedTypes()
                    solutionTypes()
                    if (analysis.typeRole.keywords) importedTypes()   // the filtered places of 0.1.88 (throw new, base list, event, constraint) keep to their own types
                    if (analysis.typeRole.keywords) {
                        keywords(NativeCSharpCompletionPlace.PREDEFINED_TYPES, priority = TYPE, kind = CSharpMlCandidateKind.TYPE)
                        keywords(place.keywords + NativeCSharpKeywords.TYPE)
                    } else if (analysis.typeRole == NativeCSharpExpectedCompletion.TypeRole.CONSTRAINT) keywords(place.keywords)
                }
                NativeCompletionKind.STATEMENT, NativeCompletionKind.EXPRESSION -> {
                    // `dto.Email = user.Email;` under a block of such assignments (0.1.134)
                    if (place.kind == NativeCompletionKind.STATEMENT) NativeCSharpMappingCompletion.statementItems(analysis).forEach(::add)
                    NativeCSharpExpectedCompletion.expressionItems(analysis).forEach(::add)
                    items += NativeCSharpArgumentCompletion.items(place, file, matcher)
                    NativeCSharpCommonCalls.items(place).forEach(::add)
                    NativeCSharpUsingCompletion.statementItems(place).forEach(::add)
                    if (place.kind == NativeCompletionKind.STATEMENT && place.name?.let(NativeCSharpCompletionPlace::statementStart)?.parent is CSharpGlobalStatement) {
                        NativeCSharpUsingCompletion.topItems(place).forEach(::add)
                    }
                    locals()
                    members()
                    staticImports()
                    nestedTypes()
                    solutionTypes()
                    if (analysis.typeRole.keywords) importedTypes()   // the filtered places of 0.1.88 (throw new, base list, event, constraint) keep to their own types
                    val keywords = if (place.kind == NativeCompletionKind.STATEMENT) NativeCSharpKeywords.statement(place) else NativeCSharpKeywords.expression(place)
                    keywords(keywords + place.keywords)
                    keywords(NativeCSharpCompletionPlace.PREDEFINED_TYPES, kind = CSharpMlCandidateKind.TYPE)
                }
            }
        }

        // ---- names

        private fun locals() {
            val expectedName = expected?.declaredName
            for (symbol in NativeCSharpLocals.visible(resolver.scopes, place.leaf)) {
                if (symbol.name == expectedName) continue
                if (symbol.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER && resolver.memberHiding(symbol.name, at) != null) continue
                val (priority, icon) = when (symbol.kind) {
                    LocalSymbolKind.LOCAL -> LOCAL to AllIcons.Nodes.Variable
                    LocalSymbolKind.PARAMETER, LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> PARAMETER to AllIcons.Nodes.Parameter
                    LocalSymbolKind.LOCAL_FUNCTION -> LOCAL_FUNCTION to AllIcons.Nodes.Function
                    LocalSymbolKind.TYPE_PARAMETER -> TYPE_PARAMETER to AllIcons.Nodes.Type
                    LocalSymbolKind.LABEL -> continue
                }
                if (resource?.rejects(symbol) == true) {
                    excluded += symbol.name
                    continue
                }
                val type = NativeCSharpLocals.typeOf(symbol)
                val shape = localShape(symbol)
                if (symbol.kind == LocalSymbolKind.LOCAL_FUNCTION) {
                    val function = symbol.declaration.parent as? CSharpLocalFunctionStatement
                    add(name(symbol.name, icon, type, priority, "()", NativeCSharpCalls.handler { listOfNotNull(function) }, shape))
                } else {
                    add(name(symbol.name, icon, type, priority, shape = shape))
                }
            }
        }

        /** The ML shape of a local symbol: its kind, the local scope, its declaration (in this file by definition). */
        private fun localShape(symbol: LocalSymbol): NativeCSharpMlInfo.Shape {
            val kind = when (symbol.kind) {
                LocalSymbolKind.LOCAL -> CSharpMlCandidateKind.LOCAL
                LocalSymbolKind.PARAMETER, LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> CSharpMlCandidateKind.PARAMETER
                LocalSymbolKind.LOCAL_FUNCTION -> CSharpMlCandidateKind.LOCAL_FUNCTION
                LocalSymbolKind.TYPE_PARAMETER -> CSharpMlCandidateKind.TYPE_PARAMETER
                LocalSymbolKind.LABEL -> CSharpMlCandidateKind.OTHER
            }
            return NativeCSharpMlInfo.Shape(kind, CSharpMlScope.LOCAL) { symbol.declaration }
        }

        private fun labels() {
            val offset = place.offset
            for (symbol in resolver.scopes.symbols) {
                if (symbol.kind == LocalSymbolKind.LABEL && symbol.scope.textRange.contains(offset)) add(name(symbol.name, AllIcons.Nodes.Tag, null, VALUE_MEMBER, shape = localShape(symbol)))
            }
        }

        private fun typeParameters() {
            for (symbol in NativeCSharpLocals.visible(resolver.scopes, place.leaf)) {
                if (symbol.kind == LocalSymbolKind.TYPE_PARAMETER) add(name(symbol.name, AllIcons.Nodes.Type, null, TYPE_PARAMETER, shape = localShape(symbol)))
            }
        }

        /** The members of the enclosing types (their parts and base classes of the solution), the innermost first; not instance ones in a static member. */
        private fun members() {
            val static = NativeCSharpLocals.inStaticContext(at)
            for (type in resolver.enclosingTypes(at)) {
                for ((key, member) in resolver.membersOf(type)) {
                    if ('<' in key || '`' in key) continue
                    if (member.nestedType != null) continue
                    if (static && !NativeCSharpMembers.isStatic(member)) continue
                    member(key, member)
                }
            }
        }

        private fun member(key: String, member: Member, scope: Int = CSharpMlScope.MEMBER) {
            val kind = NativeCSharpMembers.kind(member)
            if ((kind == NativeCSharpMembers.Kind.FIELD || kind == NativeCSharpMembers.Kind.PROPERTY || kind == NativeCSharpMembers.Kind.CONSTANT) && resource?.rejects(member) == true) {
                excluded += key
                return
            }
            val type = NativeCSharpMembers.typeIn(member, file)
            val shape = NativeCSharpMlInfo.Shape(NativeCSharpMembers.mlKind(kind), scope, NativeCSharpMembers.isStatic(member)) { member.targets().firstOrNull() }
            when (kind) {
                NativeCSharpMembers.Kind.METHOD -> add(name(key, AllIcons.Nodes.Method, type, METHOD, "()", NativeCSharpCalls.handler { member.targets() }, shape))
                NativeCSharpMembers.Kind.PROPERTY -> add(name(key, AllIcons.Nodes.Property, type, VALUE_MEMBER, shape = shape))
                NativeCSharpMembers.Kind.CONSTANT -> add(name(key, AllIcons.Nodes.Constant, type, VALUE_MEMBER, shape = shape))
                NativeCSharpMembers.Kind.EVENT -> add(name(key, AllIcons.Nodes.Field, type, VALUE_MEMBER, shape = shape))
                NativeCSharpMembers.Kind.FIELD -> add(name(key, AllIcons.Nodes.Field, type, VALUE_MEMBER, shape = shape))
            }
        }

        private fun thisMembers() {
            val own = resolver.enclosingTypes(at).firstOrNull() ?: return
            val types = if (place.base) NativeCSharpMembers.baseTypes(own, resolver) else listOf(own)
            for (type in types) for ((key, member) in resolver.membersOf(type)) {
                if ('<' in key || '`' in key || member.nestedType != null || NativeCSharpMembers.isStatic(member)) continue
                member(key, member, if (place.base) CSharpMlScope.BASE_MEMBER else CSharpMlScope.MEMBER)
            }
        }

        private fun staticImports() {
            for (type in resolver.staticImports) for ((key, member) in resolver.membersOf(type)) {
                if ('<' in key || '`' in key || member.nestedType != null || !NativeCSharpMembers.isStatic(member)) continue
                member(key, member, CSharpMlScope.RECEIVER)
            }
        }

        /** Types nested in the enclosing types (and their bases). */
        private fun nestedTypes() {
            for (type in resolver.enclosingTypes(at)) for ((key, member) in resolver.membersOf(type)) {
                if ('<' in key || '`' in key || member.nestedType == null) continue
                val info = resolver.typeInfo(member.nestedType, member.nestedArity) ?: continue
                type(key, info, CSharpMlScope.MEMBER)
            }
        }

        /** The types of this file and of the solution (stubs) that the place sees by simple name: of the namespaces around it and its `using`s. */
        private fun solutionTypes() {
            for ((name, arities) in NativeCSharpTypeNames.candidates(file, matcher, resolver)) {
                for (arity in arities) for (info in resolver.visibleTypes(at, name, arity)) type(name, info)
            }
        }

        /** The types of the assemblies the place sees and the ones it does not import ([NativeCSharpImportCompletion]). */
        private fun importedTypes() = imports(NativeCSharpImportCompletion.types(place, file, matcher, added, resolver) { bonus(it, it) })

        /** Rows of [NativeCSharpImportCompletion]: several of one name may stand (one per namespace), none of a name the list has. */
        private fun imports(elements: List<LookupElement>) {
            val before = HashSet(added)
            for (element in elements) if (element.lookupString !in before || element.getUserData(NativeCSharpImportCompletion.NOT_IMPORTED) != null) {
                added += element.lookupString
                items += element
            }
        }

        private fun attributeTypes() {
            for ((name, arities) in NativeCSharpTypeNames.candidates(file, null, resolver)) {
                if (!name.endsWith("Attribute") || name == "Attribute") continue
                val short = name.removeSuffix("Attribute")
                if (!matcher.prefixMatches(short) && !matcher.prefixMatches(name)) continue
                for (arity in arities) for (info in resolver.visibleTypes(at, name, arity)) type(short, info)
            }
        }

        private fun type(name: String, info: TypeInfo, scope: Int = CSharpMlScope.IMPORTED) {
            // a base list, `event`, a constraint, `throw new`: what cannot stand there is left out; what `new` / `catch` wants goes up
            val verdict = if (place.kind == NativeCompletionKind.TYPE) analysis.verdict(info) else NativeCSharpExpectedCompletion.Verdict.NEUTRAL
            if (verdict == NativeCSharpExpectedCompletion.Verdict.REJECT) return
            val icon = when (info.kind) {
                TypeKind.INTERFACE -> AllIcons.Nodes.Interface
                TypeKind.ENUM -> AllIcons.Nodes.Enum
                TypeKind.RECORD, TypeKind.RECORD_STRUCT -> AllIcons.Nodes.Record
                TypeKind.DELEGATE -> AllIcons.Nodes.Lambda
                TypeKind.STATIC_CLASS -> AllIcons.Nodes.Static
                else -> AllIcons.Nodes.Class
            }
            val afterNew = place.name?.parent is CSharpObjectCreationExpression
            val constructed = afterNew && info.kind != TypeKind.INTERFACE && info.kind != TypeKind.STATIC_CLASS
            val generic = info.arity > 0
            val handler = if (generic || constructed) NativeCSharpCalls.typeHandler(generic, constructed) else null
            val presentable = if (generic) "$name<${"".padEnd(info.arity - 1, ',')}>" else name
            val namespace = info.qualifiedName.substringBeforeLast('.', "")
            val fits = if (verdict == NativeCSharpExpectedCompletion.Verdict.FITS) EXPECTED_TYPE else 0.0
            val tail = if (namespace.isEmpty()) null else " ($namespace)"
            val priority = TYPE + maxOf(bonus(name, name), fits)
            val shape = NativeCSharpMlInfo.Shape(CSharpMlCandidateKind.TYPE, scope, info.kind == TypeKind.STATIC_CLASS) { info.targets().firstOrNull() }
            val row = element(name, icon, presentable, tail, null, priority, handler, shape, fits > 0 || fitsExpected(name))
            if (fits > 0 && analysis.typeRole == NativeCSharpExpectedCompletion.TypeRole.EXCEPTION_FIRST) NativeCSharpExpectedCompletion.markException(row)
            if (!added.contains(name)) initializerRow(name, info, icon, tail, priority)
            add(row)
        }

        private var initializerRows = 0

        /**
         * `Member { … }` under `Member` after `new` (0.1.102, [NativeCSharpObjectInitializers.initializerRow]): for the types of the solution
         * whose name is typed (two letters at least) and the one the variable names (`var member = new |`), a few at most — the check is
         * by the declarations, the list may have hundreds of types. Its lookup string is the name: added past the dedupe by name.
         */
        private fun initializerRow(name: String, info: TypeInfo, icon: Icon, tail: String?, priority: Double) {
            if (place.name?.parent !is CSharpObjectCreationExpression || analysis.typeRole != NativeCSharpExpectedCompletion.TypeRole.NEW) return
            if (initializerRows >= MAX_INITIALIZER_ROWS) return
            val named = CSharpNameLikeness.of(expected?.name, name) == CSharpNameLikeness.Likeness.EXACT
            if (!named && (matcher.prefix.length < 2 || !matcher.prefixMatches(name))) return
            if (!NativeCSharpObjectInitializers.wantsInitializerRow(info)) return
            initializerRows++
            add(NativeCSharpObjectInitializers.initializerRow(name, icon, tail, priority - 0.01))
        }

        // ---- declarations

        private fun declarationNames() {
            // `completion.dotnet_show_name_completion_suggestions` of the server's page, as the server's NameCompletionProvider
            if (!RoslynOptions.isOn(NAME_SUGGESTIONS)) return
            val type = place.declaredType ?: return
            val taken = NativeCSharpLocals.visible(resolver.scopes, place.leaf).mapTo(HashSet()) { it.name }
            for ((index, suggestion) in CSharpVariableNames.forType(type, place.nameStyle).withIndex()) {
                val name = CSharpVariableNames.unique(suggestion, taken)
                add(element(name, AllIcons.Nodes.Variable, name, null, null, DECLARATION - index, null, NativeCSharpMlInfo.Shape(CSharpMlCandidateKind.OTHER, CSharpMlScope.LOCAL)))
            }
        }

        private fun memberStart() {
            val modifiers = place.modifiers
            val type = place.typeDeclaration
            if ("override" in modifiers && type != null) {
                NativeCSharpOverrides.overrides(type, file, place).forEach(::add)
                return
            }
            if ("partial" in modifiers && type != null) NativeCSharpOverrides.partialMethods(type, resolver, place).forEach(::add)
            // `public ov|`: the keyword first, then the whole members to override (0.1.126), then the types
            if (type != null && NativeCSharpOverrides.startsOverride(matcher.prefix, modifiers)) {
                val early = NativeCSharpOverrides.earlyOverrides(type, file, place, matcher.prefix)
                if (early.isNotEmpty() && "override" in NativeCSharpKeywords.memberStart(modifiers, type)) {
                    keywords(listOf("override"), priority = TYPE + 2)
                    early.forEach(::add)
                }
            }
            keywords(NativeCSharpKeywords.memberStart(modifiers, type))
            nestedTypes()
            solutionTypes()
            importedTypes()
            keywords(NativeCSharpCompletionPlace.PREDEFINED_TYPES, priority = TYPE, kind = CSharpMlCandidateKind.TYPE)
        }

        // ---- the elements

        private fun keywords(keywords: List<String>, priority: Double = KEYWORD, kind: CSharpMlCandidateKind = CSharpMlCandidateKind.KEYWORD) {
            for (keyword in keywords) {
                if (!added.add(keyword)) continue
                val handler = NativeCSharpKeywords.handler(keyword, place)
                val element = LookupElementBuilder.create(keyword).bold().withInsertHandler(handler)
                element.putUserData(NATIVE, true)
                val row = PrioritizedLookupElement.withPriority(element, priority).also { it.putUserData(NATIVE, true) }
                items += NativeCSharpMlInfo.attach(row, NativeCSharpMlInfo.Shape(kind, CSharpMlScope.LOCAL), priority)
            }
        }

        private fun name(
            name: String, icon: Icon, type: String?, priority: Double, tail: String? = null, handler: InsertHandler<LookupElement>? = null,
            shape: NativeCSharpMlInfo.Shape = NativeCSharpMlInfo.OTHER,
        ): LookupElement = element(name, icon, name, tail, type, priority + bonus(name, type), handler, shape, fitsExpected(type))

        /** Whether [type] is the type the place wants (the part of [bonus] that is the `expected_type_match` feature of the ML ranker). */
        private fun fitsExpected(type: String?): Boolean = expected?.let { CSharpTypeNames.matches(it.type, type) } == true

        private fun element(
            lookup: String, icon: Icon, presentable: String, tail: String?, type: String?, priority: Double, handler: InsertHandler<LookupElement>?,
            shape: NativeCSharpMlInfo.Shape = NativeCSharpMlInfo.OTHER, fits: Boolean = false,
        ): LookupElement {
            var builder = LookupElementBuilder.create(lookup).withIcon(icon).withPresentableText(presentable)
            if (tail != null) builder = builder.withTailText(tail, tail.startsWith(" "))
            if (type != null) builder = builder.withTypeText(type)
            if (handler != null) builder = builder.withInsertHandler(handler)
            builder.putUserData(NATIVE, true)
            val row = PrioritizedLookupElement.withPriority(builder, priority).also { it.putUserData(NATIVE, true) }
            return NativeCSharpMlInfo.attach(row, shape, priority, if (fits) 2 else 0, file)
        }

        private fun bonus(name: String, type: String?): Double {
            val wanted = expected ?: return 0.0
            var bonus = 0.0
            if (CSharpTypeNames.matches(wanted.type, type)) bonus += EXPECTED_TYPE
            when (CSharpNameLikeness.of(wanted.name, name)) {
                CSharpNameLikeness.Likeness.EXACT -> bonus += NAME_EXACT
                CSharpNameLikeness.Likeness.PARTIAL -> bonus += NAME_PARTIAL
                CSharpNameLikeness.Likeness.NONE -> Unit
            }
            return bonus
        }

        private fun add(element: LookupElement) {
            val name = nameOf(element.lookupString)
            if (name == CompletionUtil.DUMMY_IDENTIFIER_TRIMMED || name.isEmpty()) return
            // `Member { … }` shares the name of the row of its type
            if (NativeCSharpObjectInitializers.isInitializerRow(element)) {
                if (added.add("${element.lookupString} { }")) items += element
                return
            }
            // the innermost declaration of a name hides the outer ones; keywords and common calls are told by their whole text
            if (!added.add(element.lookupString)) return
            items += element
        }
    }
}

/** The locals, parameters, local functions and type parameters the caret sees, from the scopes of the file ([NativeCSharpScopes]). */
object NativeCSharpLocals {
    /**
     * Visible at [leaf]: in the scope of the symbol; a local after its declaration and not in its own initializer; a local function in
     * its whole block; a parameter of a primary constructor not in a nested type; a local of the top-level statements not in a type.
     * Of several with one name the innermost.
     */
    fun visible(scopes: NativeCSharpScopes, leaf: PsiElement): List<LocalSymbol> {
        val offset = leaf.textRange.startOffset
        val byName = LinkedHashMap<String, LocalSymbol>()
        for (symbol in scopes.symbols) {
            if (symbol.isMember || symbol.kind == LocalSymbolKind.LABEL || symbol.declaration == leaf) continue
            val scope = symbol.scope
            if (!scope.textRange.containsOffset(offset) || scope.textRange.endOffset == offset && scope !is CSharpCompilationUnit) continue
            when (symbol.kind) {
                LocalSymbolKind.LOCAL -> {
                    if (symbol.declaration.textRange.endOffset > offset) continue
                    val declarator = PsiTreeUtil.getParentOfType(symbol.declaration, CSharpVariableDeclarator::class.java, CSharpForEachStatement::class.java)
                    if (declarator is CSharpVariableDeclarator && declarator.textRange.containsOffset(offset)) continue
                    if (declarator is CSharpForEachStatement && declarator.statement?.textRange?.containsOffset(offset) != true) continue
                }
                LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> if (typeBetween(leaf, scope)) continue
                else -> {}
            }
            if (scope is CSharpCompilationUnit && PsiTreeUtil.getParentOfType(leaf, CSharpGlobalStatement::class.java) == null) continue
            val known = byName[symbol.name]
            if (known == null || known.scope.textRange.contains(scope.textRange)) byName[symbol.name] = symbol
        }
        return byName.values.toList()
    }

    private fun typeBetween(leaf: PsiElement, scope: PsiElement): Boolean =
        generateSequence(leaf.parent) { it.parent }.takeWhile { it != scope && it !is CSharpFile }.any { it is CSharpBaseTypeDeclaration }

    /** The type a local or parameter is declared with, as written; `var x = new T(...)` → `T`; null when nothing says. */
    fun typeOf(symbol: LocalSymbol): String? {
        val owner = symbol.declaration.parent
        val type = when (owner) {
            is CSharpVariableDeclarator -> {
                val declared = (owner.parent as? CSharpVariableDeclaration)?.type
                if (declared?.text == "var") (owner.initializer?.value as? CSharpObjectCreationExpression)?.type else declared
            }
            is CSharpParameter -> owner.type
            is CSharpForEachStatement -> owner.type?.takeIf { it.text != "var" }
            is CSharpCatchDeclaration -> owner.type
            is CSharpSingleVariableDesignation -> when (val pattern = owner.parent) {
                is CSharpDeclarationPattern -> pattern.type
                is CSharpDeclarationExpression -> pattern.type?.takeIf { it.text != "var" }
                is CSharpRecursivePattern -> pattern.type
                else -> null
            }
            is CSharpLocalFunctionStatement -> owner.returnType
            else -> null
        }
        return type?.text?.let(CSharpStubsText::collapse)
    }

    /** In a static member (or a static local function / lambda): the instance members of the type are not reachable. */
    fun inStaticContext(at: PsiElement): Boolean {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            when (current) {
                is CSharpLocalFunctionStatement -> if (current.modifiers.any { it.text == "static" }) return true
                is CSharpAnonymousFunctionExpression -> if (current.modifiers.any { it.text == "static" }) return true
                is CSharpBaseTypeDeclaration -> return false
                is CSharpMemberDeclaration -> return current.modifiers.any { it.text == "static" || it.text == "const" }
            }
            current = current.parent
        }
        return false
    }
}

/** Whitespace of a type as written collapsed to one space. */
object CSharpStubsText {
    private val WHITESPACE = Regex("""\s+""")

    fun collapse(text: String): String = text.trim().replace(WHITESPACE, " ")
}

/** What a [Member] of the resolver is, by the key its declaration is colored with. */
object NativeCSharpMembers {
    enum class Kind { METHOD, PROPERTY, FIELD, CONSTANT, EVENT }

    fun kind(member: Member): Kind = when (member.declarationKey) {
        CSharpColors.METHOD_DECLARATION, CSharpColors.STATIC_METHOD_DECLARATION, CSharpColors.EXTENSION_METHOD_DECLARATION -> Kind.METHOD
        CSharpColors.PROPERTY, CSharpColors.STATIC_PROPERTY -> Kind.PROPERTY
        CSharpColors.CONSTANT -> Kind.CONSTANT
        CSharpColors.EVENT -> Kind.EVENT
        else -> Kind.FIELD
    }

    fun isStatic(member: Member): Boolean = member.declarationKey in STATIC

    /** The kind as the ML ranker's one-hot block names it ([io.github.dotnetsupport.ml.CSharpMlFeatures]). */
    fun mlKind(kind: Kind): CSharpMlCandidateKind = when (kind) {
        Kind.METHOD -> CSharpMlCandidateKind.METHOD
        Kind.PROPERTY -> CSharpMlCandidateKind.PROPERTY
        Kind.FIELD -> CSharpMlCandidateKind.FIELD
        Kind.CONSTANT -> CSharpMlCandidateKind.CONSTANT
        Kind.EVENT -> CSharpMlCandidateKind.EVENT
    }

    private val STATIC = setOf(
        CSharpColors.STATIC_METHOD_DECLARATION, CSharpColors.EXTENSION_METHOD_DECLARATION, CSharpColors.STATIC_PROPERTY, CSharpColors.STATIC_FIELD,
        CSharpColors.CONSTANT,
    )

    /** The type of a member declared in [file] (a field's, a property's, a method's return type); null for members of other files: their AST is not loaded for a type. */
    fun typeIn(member: Member, file: PsiElement): String? {
        val target = member.targets().firstOrNull { it.containingFile == file } ?: return null
        val type = when (target) {
            is CSharpVariableDeclarator -> (target.parent as? CSharpVariableDeclaration)?.type
            is CSharpBaseFieldDeclaration -> target.declaration?.type
            is CSharpBasePropertyDeclaration -> target.type
            is CSharpMethodDeclaration -> target.returnType
            else -> null
        }
        return type?.text?.let(CSharpStubsText::collapse)
    }

    /** The base classes of the solution of [type], nearest first (interfaces left out). */
    fun baseTypes(type: TypeInfo, resolver: NativeCSharpResolver): List<TypeInfo> {
        val result = ArrayList<TypeInfo>()
        val visited = HashSet<String>()
        visited += type.key
        var current = listOf(type)
        repeat(8) {
            val next = ArrayList<TypeInfo>()
            for (info in current) for (part in info.parts) for ((name, arity) in part.bases()) {
                val base = resolver.resolveType(name, arity) ?: continue
                if (base.kind == TypeKind.INTERFACE || !visited.add(base.key)) continue
                result += base
                next += base
            }
            if (next.isEmpty()) return result
            current = next
        }
        return result
    }
}

/** The simple names of the types of the solution (the stub index) and of the file that may match what is typed. */
object NativeCSharpTypeNames {
    private const val MAX_NAMES = 2000

    /**
     * The types of the solution outside [file], read from the stub index once (the dataset export completes hundreds of positions of one
     * file whose other files do not change; the IDE never sets it): [names] with their arities, [stubs] the parts by name, as
     * [NativeCSharpResolver.stubParts] finds them, [files] the files declaring each name ([file] included, so that [refresh] knows what to
     * read again). The types of [file] itself are left out: the resolver has them from its PSI, and their stubs go stale with every edit.
     */
    class Snapshot(
        val file: com.intellij.openapi.vfs.VirtualFile, val names: Map<String, Set<Int>>, val stubs: Map<String, List<TypePart>>,
        val files: Map<String, Set<com.intellij.openapi.vfs.VirtualFile>>,
    ) {
        /**
         * The snapshot for [next] after [changed] files were edited (the file exported before, whose stubs were rebuilt): the names declared
         * in those files and in [next] are read from the index again, the rest is kept.
         */
        fun refresh(project: com.intellij.openapi.project.Project, next: com.intellij.openapi.vfs.VirtualFile, changed: Collection<com.intellij.openapi.vfs.VirtualFile>): Snapshot {
            val stale = (changed + next + file).toSet()
            val names = LinkedHashMap(this.names); val stubs = HashMap(this.stubs); val files = HashMap(this.files)
            for ((name, declaredIn) in this.files) {
                if (declaredIn.none { it in stale }) continue
                read(project, next, name, names, stubs, files)
            }
            return Snapshot(next, names, stubs, files)
        }

        companion object {
            internal fun read(
                project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile, name: String,
                names: MutableMap<String, Set<Int>>, stubs: MutableMap<String, List<TypePart>>, files: MutableMap<String, Set<com.intellij.openapi.vfs.VirtualFile>>,
            ) {
                val declaredIn = HashSet<com.intellij.openapi.vfs.VirtualFile>()
                val parts = NativeCSharpResolver.stubParts(project, file, name, declaredIn)
                if (declaredIn.isEmpty()) files.remove(name) else files[name] = declaredIn
                if (parts.isEmpty()) { names.remove(name); stubs.remove(name) } else { names[name] = parts.mapTo(HashSet()) { it.arity }; stubs[name] = parts }
            }
        }
    }

    @Volatile private var snapshot: Snapshot? = null

    /** The snapshot for [file] (the original: completion works in a copy) while one is set and is of that file. */
    fun snapshotOf(file: com.intellij.openapi.vfs.VirtualFile): Snapshot? = snapshot?.takeIf { it.file == file }

    @org.jetbrains.annotations.TestOnly
    fun setSnapshotForTests(value: Snapshot?) {
        snapshot = value
    }

    /**
     * Reads every type name of the stub index of [project] into a [Snapshot] for [file], for [setSnapshotForTests]; [Snapshot.refresh] moves
     * it to the next file of the same solution (reading the whole index takes a second on a big solution, a minute when done for every file).
     */
    @org.jetbrains.annotations.TestOnly
    fun snapshot(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile): Snapshot {
        val names = LinkedHashMap<String, Set<Int>>()
        val stubs = HashMap<String, List<TypePart>>()
        val files = HashMap<String, Set<com.intellij.openapi.vfs.VirtualFile>>()
        for (key in StubIndex.getInstance().getAllKeys(CSharpStubIndexKeys.TYPE_NAMES, project)) Snapshot.read(project, file, key, names, stubs, files)
        return Snapshot(file, names, stubs, files)
    }

    /** Name → arities. [matcher] null: every name (capped). */
    fun candidates(file: CSharpFile, matcher: PrefixMatcher?, resolver: NativeCSharpResolver): Map<String, Set<Int>> {
        val result = LinkedHashMap<String, MutableSet<Int>>()
        for (declaration in resolver.scopes.declarations) {
            if (declaration !is CSharpBaseTypeDeclaration && declaration !is CSharpDelegateDeclaration) continue
            val name = CSharpDeclarationNames.nameElement(declaration)?.text ?: continue
            if (matcher == null || matcher.prefixMatches(name)) result.getOrPut(name) { HashSet() } += TypePart.typeParameters(declaration)
        }
        val project = file.project
        val index = StubIndex.getInstance()
        snapshotOf(file.originalFile.viewProvider.virtualFile)?.let { snapshot ->
            var taken = 0
            for ((name, arities) in snapshot.names) {
                if (matcher != null && !matcher.prefixMatches(name)) continue
                if (taken++ >= MAX_NAMES) break
                result.getOrPut(name) { HashSet() } += arities
            }
            return result
        }
        val keys = index.getAllKeys(CSharpStubIndexKeys.TYPE_NAMES, project).filter { matcher == null || matcher.prefixMatches(it) }.take(MAX_NAMES)
        val scope = io.github.dotnetsupport.codeanalysis.CSharpSourceScope.of(project)
        for (key in keys) {
            index.processElements(CSharpStubIndexKeys.TYPE_NAMES, key, project, scope, CSharpElement::class.java) { element ->
                val stub = (element as? CSharpStubElementImpl)?.greenStub
                val arity = when (element) {
                    is CSharpTypeDeclaration -> stub?.arity ?: element.typeParameterList?.parameters?.size ?: 0
                    is CSharpDelegateDeclaration -> stub?.arity ?: element.typeParameterList?.parameters?.size ?: 0
                    is CSharpBaseTypeDeclaration -> 0
                    else -> null
                }
                if (arity != null) result.getOrPut(key) { HashSet() } += arity
                true
            }
        }
        return result
    }
}

/** What is wanted where the caret is, by the tree: the type and name of the variable being initialized, of the returned value, of the parameter. */
class NativeCSharpExpected(val type: String?, val name: String?, /** The local being declared: not offered in its own initializer. */ val declaredName: String? = null)

object NativeCSharpExpectations {
    fun at(place: NativeCSharpCompletionPlace, resolver: NativeCSharpResolver): NativeCSharpExpected? {
        var value: PsiElement = place.name ?: return null
        // `Order o = new |`: what is wanted of the creation
        if (value.parent is CSharpObjectCreationExpression) value = value.parent
        return of(value, resolver)
    }

    private fun of(value: PsiElement, resolver: NativeCSharpResolver): NativeCSharpExpected? {
        val parent = value.parent ?: return null
        return when {
            parent is CSharpEqualsValueClause && parent.parent is CSharpVariableDeclarator -> {
                val declarator = parent.parent as CSharpVariableDeclarator
                val type = (declarator.parent as? CSharpVariableDeclaration)?.type?.text?.takeIf { it != "var" }
                val name = declarator.identifier?.text
                NativeCSharpExpected(type?.let(CSharpStubsText::collapse), name, declaredName = name.takeIf { declarator.parent?.parent !is CSharpBaseFieldDeclaration })
            }
            parent is CSharpReturnStatement || parent is CSharpArrowExpressionClause -> NativeCSharpCommonCalls.function(value)?.let { function ->
                val returned = NativeCSharpCommonCalls.returnType(function) ?: return null
                val async = NativeCSharpCommonCalls.isAsync(function)
                val type = if (async) NativeCSharpCommonCalls.TASK_OF.matchEntire(returned)?.groupValues?.get(2) else returned
                type?.let { NativeCSharpExpected(it, null) }
            }
            parent is CSharpArgument && parent.expression == value -> argument(parent, resolver)
            parent is CSharpAssignmentExpression && parent.right == value -> {
                val left = parent.left as? CSharpIdentifierName ?: return null
                val identifier = left.identifier ?: return null
                val symbol = resolver.scopes.symbolAt(identifier)
                val type = if (symbol != null) NativeCSharpLocals.typeOf(symbol) else resolver.enclosingMember(left, identifier.text)?.let { NativeCSharpMembers.typeIn(it, resolver.file) }
                NativeCSharpExpected(type, identifier.text)
            }
            else -> null
        }
    }

    /** `Save(|)` of a method of the solution (of the enclosing types, or a local function): the parameter at that place, or the named one. */
    private fun argument(argument: CSharpArgument, resolver: NativeCSharpResolver): NativeCSharpExpected? {
        val list = argument.parent as? CSharpArgumentList ?: return null
        val call = list.parent as? CSharpInvocationExpression ?: return null
        val callee = when (val expression = call.expression) {
            is CSharpIdentifierName -> expression
            is CSharpMemberAccessExpression -> (expression.nameElement as? CSharpIdentifierName)?.takeIf { expression.expression is CSharpThisExpression || expression.expression is CSharpBaseExpression }
            else -> null
        } ?: return null
        val identifier = callee.identifier ?: return null
        val symbol = resolver.scopes.symbolAt(identifier)
        val parameterLists: List<CSharpParameterList> = if (symbol != null) {
            listOfNotNull((symbol.declaration.parent as? CSharpLocalFunctionStatement)?.parameterList)
        } else {
            resolver.enclosingMember(callee, identifier.text)?.targets().orEmpty().mapNotNull { (it as? CSharpMethodDeclaration)?.parameterList }
        }
        val named = argument.nameColon?.nameElement?.identifier?.text
        val index = list.arguments.indexOf(argument)
        for (parameters in parameterLists) {
            val parameter = if (named != null) parameters.parameters.firstOrNull { it.identifier?.text == named } else parameters.parameters.getOrNull(index) ?: continue
            parameter ?: continue
            return NativeCSharpExpected(parameter.type?.text?.let(CSharpStubsText::collapse), parameter.identifier?.text)
        }
        return null
    }
}

/** What a chosen method, local function or type gets after its name: `()`, `();`, `<>`, as the server's items get it ([CSharpCalls]). */
object NativeCSharpCalls {
    private val SUBSCRIPTION = Regex("""[+-]=\s*$""")

    fun handler(targets: () -> List<PsiElement>): InsertHandler<LookupElement> = callHandler {
        val functions = runCatching(targets).getOrDefault(emptyList())
        val (returnType, parameters) = functions.map { function ->
            when (function) {
                is CSharpMethodDeclaration -> function.returnType?.text to function.parameterList?.parameters?.size
                is CSharpLocalFunctionStatement -> function.returnType?.text to function.parameterList?.parameters?.size
                else -> null to null
            }
        }.let { pairs -> pairs.map { it.first } to pairs.map { it.second } }
        val returnsNothing = returnType.isNotEmpty() && returnType.all { it == "void" }
        val takesArguments = parameters.isEmpty() || parameters.any { it == null || it > 0 }
        returnsNothing to takesArguments
    }

    /**
     * `()` / `();` after a method whose [shape] is (returns nothing, takes arguments in some overload). Chosen by Enter, Tab, `(` or inserted
     * by itself as the only item; by the commit characters ([CSharpCommitCharFilter]) `.` (`Total().`, the members of the result open) and `;`
     * (`Save();`, the caret in the parentheses when the method takes arguments), as in Rider.
     */
    fun callHandler(shape: () -> Pair<Boolean, Boolean>): InsertHandler<LookupElement> = InsertHandler { context, _ ->
        if (!choosesWithCall(context)) return@InsertHandler
        val document = context.document
        val text = document.charsSequence
        val offset = context.tailOffset
        val char = context.completionChar
        if (char == '.' || char == ';') {
            if (offset < text.length && text[offset] == '(') return@InsertHandler
            commitCall(context, offset, char, shape)
            return@InsertHandler
        }
        if (char == '(') context.setAddCompletionChar(false)
        if (offset < text.length && text[offset] == '(') {
            context.editor.caretModel.moveToOffset(offset + 1)
            NativeCSharpCallPopups.afterCall(context.editor)
            return@InsertHandler
        }
        val (returnsNothing, takesArguments) = shape()
        val call = CSharpCalls.call(returnsNothing, takesArguments, false, CSharpCalls.endsStatement(text, context.startOffset), CSharpCalls.restOfLine(text, offset))
        document.insertString(offset, call.text)
        context.editor.caretModel.moveToOffset(offset + call.caret)
        context.commitDocument()
        // the caret between the parentheses: what the server's items get (RoslynCompletionItems), the parameter info and the gray arguments
        if (call.caret < call.text.length) NativeCSharpCallPopups.afterCall(context.editor)
    }

    /** `Total().` with the caret after the dot (the members of the result open); `Save();`, `Add(|);` with the caret inside when it takes arguments. */
    private fun commitCall(context: InsertionContext, offset: Int, char: Char, shape: () -> Pair<Boolean, Boolean>) {
        context.setAddCompletionChar(false)
        val editor = context.editor
        if (char == '.') {
            context.document.insertString(offset, "().")
            editor.caretModel.moveToOffset(offset + 3)
            context.commitDocument()
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(editor)
            return
        }
        val takesArguments = shape().second
        val semicolon = context.document.charsSequence.getOrNull(offset) != ';'
        context.document.insertString(offset, if (semicolon) "();" else "()")
        editor.caretModel.moveToOffset(if (takesArguments) offset + 1 else offset + 3)
        context.commitDocument()
        if (takesArguments) NativeCSharpCallPopups.afterCall(editor)
    }

    private fun choosesByItself(char: Char): Boolean = char == Lookup.NORMAL_SELECT_CHAR || char == Lookup.REPLACE_SELECT_CHAR || char == Lookup.AUTO_INSERT_SELECT_CHAR

    /** `List<|>`, `new Order(|)`, `new List<|>()`; also when the only item is inserted by itself. */
    fun typeHandler(generic: Boolean, constructed: Boolean): InsertHandler<LookupElement> = InsertHandler { context, _ ->
        if (!choosesByItself(context.completionChar)) return@InsertHandler
        val document = context.document
        val offset = context.tailOffset
        val next = document.charsSequence.getOrNull(offset)
        if (next == '<' || next == '(') return@InsertHandler
        // `new OrderLine` of a type with required members: the initializer with them instead of `()` (0.1.98, as in Rider)
        if (constructed && !generic && runCatching { NativeCSharpObjectInitializers.afterNewType(context) }.getOrDefault(false)) return@InsertHandler
        val text = (if (generic) "<>" else "") + (if (constructed) "()" else "")
        document.insertString(offset, text)
        context.editor.caretModel.moveToOffset(offset + 1)
        context.commitDocument()
    }

    private fun choosesWithCall(context: InsertionContext): Boolean {
        val char = context.completionChar
        if (!choosesByItself(char) && char != '(' && char != '.' && char != ';') return false
        val text = context.document.charsSequence
        val start = context.startOffset
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val before = text.subSequence(lineStart, start)
        // `+= Handler`: a method group; `nameof(Save)`: a name
        return !SUBSCRIPTION.containsMatchIn(before) && !before.trimEnd().endsWith("nameof(")
    }

    fun commit(context: InsertionContext) {
        PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
    }
}
