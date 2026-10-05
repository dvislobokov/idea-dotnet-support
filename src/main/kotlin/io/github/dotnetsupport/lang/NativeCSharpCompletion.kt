package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
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
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original.project)) return
        val file = parameters.position.containingFile as? CSharpFile ?: return
        if (file.compilationUnit == null) return
        val place = NativeCSharpCompletionPlace.of(parameters.position) ?: return
        if (!NativeCSharpCompletion.opensByItself(parameters, result.prefixMatcher.prefix, place.kind)) return
        val excluded = HashSet<String>()
        val items = NativeCSharpCompletion.items(place, file, result.prefixMatcher, excluded)
        for (item in items) result.addElement(item)
        val names = items.flatMapTo(HashSet()) { item -> item.allLookupStrings.map(NativeCSharpCompletion::nameOf) }
        names += excluded
        result.runRemainingContributors(parameters) { found ->
            if (!NativeCSharpCompletion.isDuplicate(found.lookupElement, names, place.keywordsAreNative)) result.passResult(found)
        }
        result.stopHere()
    }
}

object NativeCSharpCompletion {
    /** The items of the list made by the plugin's tree: tests and the merge tell them by this key. */
    val NATIVE: Key<Boolean> = Key.create("dotnet.nativeCompletion")

    /** Put by the client of the server on its items (`RoslynCompletionSupport`): what [isDuplicate] may drop. */
    val SERVER: Key<Boolean> = Key.create("dotnet.serverCompletion")

    /**
     * Whether the native list is shown at a list that opened by itself with nothing typed yet. The client of the server opens it on every
     * trigger character of Roslyn (`{`, `(`, `[`, `:`, `<`, a space…), and Roslyn answers most of them with nothing: the native list of
     * the place would pop up after `GetStringAsync(...){`. As in Rider, a list opens by itself after `.`, after `[` of an attribute, and
     * after a space where a type, a name after a type, a keyword, a namespace of `using` or an attribute is expected (`new `, `override `).
     * Ctrl+Space (an explicit call) and a typed prefix always get the list.
     */
    fun opensByItself(parameters: CompletionParameters, prefix: String, kind: NativeCompletionKind): Boolean {
        if (!parameters.isAutoPopup || prefix.isNotEmpty()) return true
        val text = parameters.editor.document.charsSequence
        return when (text.getOrNull(parameters.offset - 1)) {
            '.' -> true
            '[' -> kind == NativeCompletionKind.ATTRIBUTE
            ' ', '\t' -> kind == NativeCompletionKind.TYPE || kind == NativeCompletionKind.DECLARATION_NAME || kind == NativeCompletionKind.KEYWORDS_ONLY ||
                kind == NativeCompletionKind.USING_DIRECTIVE || kind == NativeCompletionKind.ATTRIBUTE
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
        // a named argument (`amount:`, the lookup string `amount`) is not the local of the same name the native list has
        if (name.isNotEmpty() && name in nativeNames) return LookupElementPresentation.renderElement(element).itemText?.endsWith(":") != true
        return keywordsAreNative && element.lookupString in NativeCSharpCompletionPlace.ALL_KEYWORDS
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
        private val expected by lazy { NativeCSharpExpectations.at(place, resolver) }
        // the resource of `using (|` / `using var x = |`: what is known not to be disposable is left out (A8, the types of C2)
        private val resource by lazy { NativeCSharpUsingChecks.resourcePlace(place)?.let { async -> NativeCSharpUsingChecks.Filter(file, async, at) } }

        fun build() {
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
                NativeCompletionKind.MEMBER_ACCESS -> NativeCSharpMemberCompletion.items(place, file, matcher).forEach(::add)
                NativeCompletionKind.ATTRIBUTE -> attributeTypes()
                NativeCompletionKind.MEMBER_START -> memberStart()
                NativeCompletionKind.TOP_LEVEL -> {
                    NativeCSharpUsingCompletion.topItems(place).forEach(::add)
                    keywords(NativeCSharpKeywords.topLevel(place.modifiers))
                }
                NativeCompletionKind.USING_DIRECTIVE -> NativeCSharpUsingCompletion.directiveItems(place, file, matcher).forEach(::add)
                NativeCompletionKind.TYPE -> {
                    typeParameters()
                    nestedTypes()
                    solutionTypes()
                    keywords(NativeCSharpCompletionPlace.PREDEFINED_TYPES, priority = TYPE)
                    keywords(place.keywords + NativeCSharpKeywords.TYPE)
                }
                NativeCompletionKind.STATEMENT, NativeCompletionKind.EXPRESSION -> {
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
                    val keywords = if (place.kind == NativeCompletionKind.STATEMENT) NativeCSharpKeywords.statement(place) else NativeCSharpKeywords.expression(place)
                    keywords(keywords + place.keywords)
                    keywords(NativeCSharpCompletionPlace.PREDEFINED_TYPES)
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
                if (symbol.kind == LocalSymbolKind.LOCAL_FUNCTION) {
                    val function = symbol.declaration.parent as? CSharpLocalFunctionStatement
                    add(name(symbol.name, icon, type, priority, "()", NativeCSharpCalls.handler { listOfNotNull(function) }))
                } else {
                    add(name(symbol.name, icon, type, priority))
                }
            }
        }

        private fun labels() {
            val offset = place.offset
            for (symbol in resolver.scopes.symbols) {
                if (symbol.kind == LocalSymbolKind.LABEL && symbol.scope.textRange.contains(offset)) add(name(symbol.name, AllIcons.Nodes.Tag, null, VALUE_MEMBER))
            }
        }

        private fun typeParameters() {
            for (symbol in NativeCSharpLocals.visible(resolver.scopes, place.leaf)) {
                if (symbol.kind == LocalSymbolKind.TYPE_PARAMETER) add(name(symbol.name, AllIcons.Nodes.Type, null, TYPE_PARAMETER))
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

        private fun member(key: String, member: Member) {
            val kind = NativeCSharpMembers.kind(member)
            if ((kind == NativeCSharpMembers.Kind.FIELD || kind == NativeCSharpMembers.Kind.PROPERTY || kind == NativeCSharpMembers.Kind.CONSTANT) && resource?.rejects(member) == true) {
                excluded += key
                return
            }
            val type = NativeCSharpMembers.typeIn(member, file)
            when (kind) {
                NativeCSharpMembers.Kind.METHOD -> add(name(key, AllIcons.Nodes.Method, type, METHOD, "()", NativeCSharpCalls.handler { member.targets() }))
                NativeCSharpMembers.Kind.PROPERTY -> add(name(key, AllIcons.Nodes.Property, type, VALUE_MEMBER))
                NativeCSharpMembers.Kind.CONSTANT -> add(name(key, AllIcons.Nodes.Constant, type, VALUE_MEMBER))
                NativeCSharpMembers.Kind.EVENT -> add(name(key, AllIcons.Nodes.Field, type, VALUE_MEMBER))
                NativeCSharpMembers.Kind.FIELD -> add(name(key, AllIcons.Nodes.Field, type, VALUE_MEMBER))
            }
        }

        private fun thisMembers() {
            val own = resolver.enclosingTypes(at).firstOrNull() ?: return
            val types = if (place.base) NativeCSharpMembers.baseTypes(own, resolver) else listOf(own)
            for (type in types) for ((key, member) in resolver.membersOf(type)) {
                if ('<' in key || '`' in key || member.nestedType != null || NativeCSharpMembers.isStatic(member)) continue
                member(key, member)
            }
        }

        private fun staticImports() {
            for (type in resolver.staticImports) for ((key, member) in resolver.membersOf(type)) {
                if ('<' in key || '`' in key || member.nestedType != null || !NativeCSharpMembers.isStatic(member)) continue
                member(key, member)
            }
        }

        /** Types nested in the enclosing types (and their bases). */
        private fun nestedTypes() {
            for (type in resolver.enclosingTypes(at)) for ((key, member) in resolver.membersOf(type)) {
                if ('<' in key || '`' in key || member.nestedType == null) continue
                val info = resolver.typeInfo(member.nestedType, member.nestedArity) ?: continue
                type(key, info)
            }
        }

        /** The types of this file and of the solution (stubs) that the place sees by simple name: of the namespaces around it and its `using`s. */
        private fun solutionTypes() {
            for ((name, arities) in NativeCSharpTypeNames.candidates(file, matcher, resolver)) {
                for (arity in arities) for (info in resolver.visibleTypes(at, name, arity)) type(name, info)
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

        private fun type(name: String, info: TypeInfo) {
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
            add(element(name, icon, presentable, if (namespace.isEmpty()) null else " ($namespace)", null, TYPE + bonus(name, name), handler))
        }

        // ---- declarations

        private fun declarationNames() {
            val type = place.declaredType ?: return
            val taken = NativeCSharpLocals.visible(resolver.scopes, place.leaf).mapTo(HashSet()) { it.name }
            for ((index, suggestion) in CSharpVariableNames.forType(type, place.nameStyle).withIndex()) {
                val name = CSharpVariableNames.unique(suggestion, taken)
                add(element(name, AllIcons.Nodes.Variable, name, null, null, DECLARATION - index, null))
            }
        }

        private fun memberStart() {
            val modifiers = place.modifiers
            val type = place.typeDeclaration
            if ("override" in modifiers && type != null) {
                NativeCSharpOverrides.overrides(type, resolver, place).forEach(::add)
                return
            }
            if ("partial" in modifiers && type != null) NativeCSharpOverrides.partialMethods(type, resolver, place).forEach(::add)
            keywords(NativeCSharpKeywords.memberStart(modifiers, type))
            nestedTypes()
            solutionTypes()
            keywords(NativeCSharpCompletionPlace.PREDEFINED_TYPES, priority = TYPE)
        }

        // ---- the elements

        private fun keywords(keywords: List<String>, priority: Double = KEYWORD) {
            for (keyword in keywords) {
                if (!added.add(keyword)) continue
                val handler = NativeCSharpKeywords.handler(keyword, place)
                val element = LookupElementBuilder.create(keyword).bold().withInsertHandler(handler)
                element.putUserData(NATIVE, true)
                items += PrioritizedLookupElement.withPriority(element, priority).also { it.putUserData(NATIVE, true) }
            }
        }

        private fun name(name: String, icon: Icon, type: String?, priority: Double, tail: String? = null, handler: InsertHandler<LookupElement>? = null): LookupElement =
            element(name, icon, name, tail, type, priority + bonus(name, type), handler)

        private fun element(lookup: String, icon: Icon, presentable: String, tail: String?, type: String?, priority: Double, handler: InsertHandler<LookupElement>?): LookupElement {
            var builder = LookupElementBuilder.create(lookup).withIcon(icon).withPresentableText(presentable)
            if (tail != null) builder = builder.withTailText(tail, tail.startsWith(" "))
            if (type != null) builder = builder.withTypeText(type)
            if (handler != null) builder = builder.withInsertHandler(handler)
            builder.putUserData(NATIVE, true)
            return PrioritizedLookupElement.withPriority(builder, priority).also { it.putUserData(NATIVE, true) }
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

    /** `()` / `();` after a method whose [shape] is (returns nothing, takes arguments in some overload). */
    fun callHandler(shape: () -> Pair<Boolean, Boolean>): InsertHandler<LookupElement> = InsertHandler { context, _ ->
        if (!choosesWithCall(context)) return@InsertHandler
        val document = context.document
        val text = document.charsSequence
        val offset = context.tailOffset
        if (context.completionChar == '(') context.setAddCompletionChar(false)
        if (offset < text.length && text[offset] == '(') {
            context.editor.caretModel.moveToOffset(offset + 1)
            return@InsertHandler
        }
        val (returnsNothing, takesArguments) = shape()
        val call = CSharpCalls.call(returnsNothing, takesArguments, false, CSharpCalls.endsStatement(text, context.startOffset), CSharpCalls.restOfLine(text, offset))
        document.insertString(offset, call.text)
        context.editor.caretModel.moveToOffset(offset + call.caret)
        context.commitDocument()
    }

    /** `List<|>`, `new Order(|)`, `new List<|>()`. */
    fun typeHandler(generic: Boolean, constructed: Boolean): InsertHandler<LookupElement> = InsertHandler { context, _ ->
        if (context.completionChar != Lookup.NORMAL_SELECT_CHAR && context.completionChar != Lookup.REPLACE_SELECT_CHAR) return@InsertHandler
        val document = context.document
        val offset = context.tailOffset
        val next = document.charsSequence.getOrNull(offset)
        if (next == '<' || next == '(') return@InsertHandler
        val text = (if (generic) "<>" else "") + (if (constructed) "()" else "")
        document.insertString(offset, text)
        context.editor.caretModel.moveToOffset(offset + 1)
        context.commitDocument()
    }

    private fun choosesWithCall(context: InsertionContext): Boolean {
        val char = context.completionChar
        if (char != Lookup.NORMAL_SELECT_CHAR && char != Lookup.REPLACE_SELECT_CHAR && char != '(') return false
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
