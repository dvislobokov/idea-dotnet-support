package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProgressIndicator
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupFocusDegree
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.suggest.SuggestionStats
import kotlin.math.ln

/**
 * How the list of a C# file behaves, beside what is in it (COMPLETION_GAPS 2.8–2.14, 3.4, 3.10), as Rider and Visual Studio behave:
 *  - suggestion mode where a new name is written ([CSharpSuggestionMode]): a list that opened by itself there has no selection, so Enter,
 *    space and the commit characters ([CSharpCommitCharFilter]) do not replace the typed name with an item;
 *  - middle matching ([CSharpMiddleMatcher]): `rite` finds `WriteLine`, under what matches from the start;
 *  - keywords the places of the native list miss ([CSharpKeywordRecommendations]), and none in `nameof(` / no `dynamic` in `typeof(`.
 * After [CSharpCaseInsensitiveCompletion] (it gets the matcher of that one) and in front of [NativeCSharpCompletionContributor], whose items
 * it filters at the places where the native list is wrong.
 */
class CSharpCompletionBehaviourContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is CSharpFile) return
        val position = parameters.position
        val namePlace = CSharpSuggestionMode.isNamePlace(position)
        val lookup = (parameters.process as? CompletionProgressIndicator)?.lookup
        if (lookup != null) {
            lookup.putUserData(CSharpSuggestionMode.KEY, namePlace)
            if (namePlace && parameters.isAutoPopup && !lookup.isSelectionTouched) lookup.lookupFocusDegree = LookupFocusDegree.UNFOCUSED
        }
        val native = CSharpFeatures.native(CSharpFeature.COMPLETION, parameters.originalFile)
        val recommendation = if (native && !namePlace) CSharpKeywordRecommendations.at(position) else null
        val inNameof = native && CSharpKeywordRecommendations.inNameof(position)
        val inTypeof = native && CSharpKeywordRecommendations.inTypeof(position)
        val set = result.withPrefixMatcher(CSharpMiddleMatcher.of(result.prefixMatcher))
        val own = recommendation?.keywords.orEmpty().toSet()
        for (keyword in recommendation?.keywords.orEmpty()) set.addElement(keyword(keyword))
        set.runRemainingContributors(parameters) { found ->
            val element = found.lookupElement
            val drop = isNative(element) && (
                recommendation?.exclusive == true ||
                    element.lookupString in own ||
                    inNameof && isKeyword(element) ||
                    inTypeof && element.lookupString == "dynamic")
            if (!drop) set.passResult(found)
        }
        set.stopHere()
    }

    private fun keyword(keyword: String): LookupElement {
        val element = LookupElementBuilder.create(keyword).bold().withInsertHandler(KEYWORD_HANDLER)
            .let { if (keyword == "assembly" || keyword == "module") it.withPresentableText("$keyword:") else it }
        element.putUserData(NativeCSharpCompletion.NATIVE, true)
        return PrioritizedLookupElement.withPriority(element, NativeCSharpCompletion.KEYWORD).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
    }

    companion object {
        /** `assembly: `, `with `, nothing after `field`; nothing when the keyword is chosen by typing a character. */
        private val KEYWORD_HANDLER = InsertHandler<LookupElement> { context, item ->
            if (context.completionChar != Lookup.NORMAL_SELECT_CHAR && context.completionChar != Lookup.REPLACE_SELECT_CHAR) return@InsertHandler
            val text = when (item.lookupString) {
                "assembly", "module" -> ": "
                "field", "get", "set", "init", "add", "remove" -> return@InsertHandler
                else -> " "
            }
            val offset = context.tailOffset
            if (context.document.charsSequence.getOrNull(offset) == text.first()) {
                context.editor.caretModel.moveToOffset(offset + 1)
                return@InsertHandler
            }
            context.document.insertString(offset, text)
            context.editor.caretModel.moveToOffset(offset + text.length)
        }

        fun isNative(element: LookupElement): Boolean {
            var current: LookupElement? = element
            while (current != null) {
                if (current.getUserData(NativeCSharpCompletion.NATIVE) == true) return true
                current = (current as? LookupElementDecorator<*>)?.delegate
            }
            return false
        }

        /** A keyword item of the native list: bold, no icon, spelled as a keyword. */
        fun isKeyword(element: LookupElement): Boolean {
            val name = element.lookupString
            if (name !in NativeCSharpCompletionPlace.ALL_KEYWORDS && name !in NativeCSharpCompletionPlace.PREDEFINED_TYPES && name != "new()") return false
            return LookupElementPresentation.renderElement(element).icon == null
        }
    }
}

/**
 * Suggestion mode (Roslyn's `CSharpSuggestionModeCompletionProvider`, COMPLETION_GAPS 2.12): where a new name is written the list is only a
 * suggestion. A list that opens by itself there is [LookupFocusDegree.UNFOCUSED] (no item selected: Enter and space are the editor's), and
 * the commit characters do not choose from it; an arrow key selects an item as usual.
 */
object CSharpSuggestionMode {
    /** On the lookup: its completion is at a name place. */
    val KEY: Key<Boolean> = Key.create("dotnet.csharp.suggestionMode")

    private val NOT_A_TYPE = NativeCSharpCompletionPlace.ALL_KEYWORDS - setOf("var", "dynamic") - NativeCSharpCompletionPlace.PREDEFINED_TYPES.toSet()

    fun isOn(lookup: Lookup): Boolean = (lookup as? LookupImpl)?.getUserData(KEY) == true

    /** [leaf] (the identifier at the caret, in the copy completion works in) is the name of something being declared, or a lambda's parameter. */
    fun isNamePlace(leaf: PsiElement): Boolean {
        if (!CSharpLeaves.isIdentifier(leaf)) return false
        when (NativeCSharpCompletionPlace.of(leaf)?.kind) {
            NativeCompletionKind.DECLARATION_NAME -> return true
            NativeCompletionKind.KEYWORDS_ONLY, NativeCompletionKind.MEMBER_START, NativeCompletionKind.TOP_LEVEL, NativeCompletionKind.LABEL,
            NativeCompletionKind.USING_DIRECTIVE, NativeCompletionKind.ATTRIBUTE, NativeCompletionKind.MEMBER_ACCESS, NativeCompletionKind.THIS_MEMBERS -> return false
            else -> {}
        }
        val declared = when (val parent = leaf.parent) {
            is CSharpVariableDeclarator -> parent.identifier == leaf && (parent.parent as? CSharpVariableDeclaration)?.type?.text?.trim() !in NOT_A_TYPE
            is CSharpSingleVariableDesignation -> parent.identifier == leaf
            is CSharpForEachStatement -> parent.identifier == leaf
            is CSharpParameter -> parent.identifier == leaf
            is CSharpCatchDeclaration -> parent.identifier == leaf
            is CSharpTypeParameter -> parent.identifier == leaf
            is CSharpFromClause -> parent.identifier == leaf
            is CSharpLetClause -> parent.identifier == leaf
            is CSharpJoinClause -> parent.identifier == leaf
            is CSharpJoinIntoClause -> parent.identifier == leaf
            is CSharpQueryContinuation -> parent.identifier == leaf
            is CSharpLocalFunctionStatement -> parent.identifier == leaf
            is CSharpTupleElement -> parent.identifier == leaf
            is CSharpBaseTypeDeclaration -> parent.identifier == leaf
            is CSharpDelegateDeclaration -> parent.identifier == leaf
            is CSharpMethodDeclaration -> parent.identifier == leaf
            is CSharpPropertyDeclaration -> parent.identifier == leaf
            is CSharpEventDeclaration -> parent.identifier == leaf
            is CSharpEnumMemberDeclaration -> parent.identifier == leaf
            else -> false
        }
        return declared || isLambdaParameterPlace(leaf)
    }

    /**
     * Where a lambda's parameter may be written: an argument just begun (after `(` or `,`) at a parameter of a delegate type in some
     * overload of the call (`items.Where(|`, `list.ForEach(|`), or the parameters of a lambda in parentheses (`Select((x, |`).
     */
    fun isLambdaParameterPlace(leaf: PsiElement): Boolean {
        val name = leaf.parent as? CSharpIdentifierName ?: return false
        val prev = NativeCSharpCompletionPlace.previousToken(leaf) ?: return false
        if (prev.text != "(" && prev.text != ",") return false
        val holder = name.parent
        val argument: CSharpArgument = when {
            holder is CSharpArgument && holder.expression == name && holder.parent is CSharpBaseArgumentList -> holder
            // `Select((x, |`: the parameters of a lambda in parentheses read as a tuple, or a parenthesized expression
            holder is CSharpArgument && holder.expression == name && holder.parent is CSharpTupleExpression -> holder.parent.parent as? CSharpArgument
            holder is CSharpParenthesizedExpression && prev == holder.firstChild -> holder.parent as? CSharpArgument
            else -> null
        } ?: return false
        val list = argument.parent as? CSharpBaseArgumentList ?: return false
        val file = leaf.containingFile as? CSharpFile ?: return false
        return delegateAt(file, list, list.arguments.indexOf(argument))
    }

    /**
     * Some overload of the call or creation of [list] takes a delegate at argument [index]: a parameter whose type resolves to a delegate type
     * of the sources or the assemblies, whatever its name (`delegate bool Rule(Order o)`, `Expression<Func<T, bool>>`); where the type does not
     * resolve, by the name of the type as parameter info writes it ([isDelegateType]).
     */
    fun delegateAt(file: CSharpFile, list: CSharpBaseArgumentList, index: Int): Boolean {
        if (index < 0) return false
        if (resolvesToDelegate(file, list, index)) return true
        val rows = runCatching { NativeCSharpParameterInfo.rows(file, list) }.getOrDefault(emptyList())
        return rows.any { row ->
            val parameter = row.parameters.getOrNull(index) ?: row.parameters.lastOrNull()?.takeIf { it.startsWith("params ") }
            parameter != null && isDelegateType(parameter)
        }
    }

    private fun resolvesToDelegate(file: CSharpFile, list: CSharpBaseArgumentList, index: Int): Boolean {
        if (DumbService.isDumb(file.project)) return false
        val owner = list.parent
        if (owner !is CSharpInvocationExpression && owner !is CSharpBaseObjectCreationExpression) return false
        return runCatching {
            val resolver = CSharpSemanticSession(file.project).resolver(file)
            val candidates = NativeCSharpParameterInfo.candidates(owner, resolver)
            candidates.isNotEmpty() && resolver.expressions.parameterTypesAt(owner as CSharpExpression, candidates, index).any { type ->
                type != null && resolver.expressions.unwrapExpression(type)?.let(resolver.expressions::delegateSignature) != null
            }
        }.getOrDefault(false)
    }

    private val DELEGATES = Regex("""^(?:(?:System\.)?(?:Linq\.Expressions\.)?Expression<)?(?:System\.)?(Func|Action|Predicate|Comparison|Converter|EventHandler|AsyncCallback)\b""")

    /** `Func<int, bool> predicate`, `Action<T> action`, `Expression<Func<T, bool>> filter`, `EventHandler handler`, `SomethingHandler h`, `Callback c`. */
    fun isDelegateType(parameter: String): Boolean {
        var type = parameter.substringBefore(" = ").trim()
        for (modifier in listOf("this ", "params ", "scoped ", "ref ", "in ", "out ")) type = type.removePrefix(modifier)
        type = type.substringBeforeLast(' ').trim().removeSuffix("?")
        if (DELEGATES.containsMatchIn(type)) return true
        val simple = type.substringBefore('<').substringAfterLast('.')
        return simple.endsWith("Handler") || simple.endsWith("Callback") || simple.endsWith("Delegate") && simple != "Delegate"
    }
}

/**
 * Middle matching (COMPLETION_GAPS 3.10): from three letters on, an item that has what is typed anywhere in it matches too (`rite` →
 * `WriteLine`), as in Rider. What the matcher of the list ([CSharpCaseInsensitiveCompletion]: CamelHumps, no case) matches stays a start
 * match; the platform puts start matches above the others ([isStartMatch]), so the middle ones are always under them.
 */
class CSharpMiddleMatcher private constructor(prefix: String, private val camel: PrefixMatcher) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean = camel.prefixMatches(name) || middle(name)
    override fun prefixMatches(element: LookupElement): Boolean = camel.prefixMatches(element) || element.allLookupStrings.any(::middle)
    override fun isStartMatch(name: String): Boolean = camel.isStartMatch(name)
    override fun isStartMatch(element: LookupElement): Boolean = camel.isStartMatch(element)
    override fun matchingDegree(string: String): Int = if (camel.prefixMatches(string)) camel.matchingDegree(string) else Int.MIN_VALUE / 2
    override fun cloneWithPrefix(prefix: String): PrefixMatcher = if (prefix == myPrefix) this else CSharpMiddleMatcher(prefix, camel.cloneWithPrefix(prefix))

    private fun middle(name: String): Boolean = myPrefix.length >= MIN_LENGTH && name.contains(myPrefix, ignoreCase = true)

    companion object {
        const val MIN_LENGTH = 3

        fun of(matcher: PrefixMatcher): PrefixMatcher = if (matcher is CSharpMiddleMatcher) matcher else CSharpMiddleMatcher(matcher.prefix, matcher)
    }
}

/**
 * Commit characters of C# (COMPLETION_GAPS 2.9): `.`, `,`, `;`, space, `=`, `[`, `)` and `(` choose the selected item of a list that
 * opened by itself and are typed after it, as in Rider and Visual Studio (the platform does so only with "Insert selected suggestion by
 * pressing space, dot, or other context-dependent keys"). Not while nothing is typed and nothing is selected by hand, not for an item that
 * matches only in the middle, and never in suggestion mode ([CSharpSuggestionMode]): there the character is typed and the list closes.
 */
class CSharpCommitCharFilter : CharFilter() {
    override fun acceptChar(c: Char, prefixLength: Int, lookup: Lookup): Result? {
        if (c !in COMMIT && c != ':') return null
        if (!lookup.isCompletion || lookup.psiFile !is CSharpFile) return null
        val impl = lookup as? LookupImpl ?: return null
        if (CSharpSuggestionMode.isOn(lookup)) return Result.HIDE_LOOKUP
        if (c == ':') return null
        val item = lookup.currentItem ?: return null
        // a template is expanded by Tab / Enter only: `foreach (` and `if (` are typed, not the template of that name (robot, 0.1.91)
        if (isTemplate(item)) return null
        if (!impl.isSelectionTouched) {
            if (prefixLength == 0) return Result.HIDE_LOOKUP
            // `override str` + space is the return type being typed, `public overr` + `i` the keyword: a whole member is chosen by Enter / Tab only
            if (NativeCSharpOverrides.rowOf(item) != null) return Result.HIDE_LOOKUP
            if (!impl.itemMatcher(item).isStartMatch(item)) return Result.HIDE_LOOKUP
            // what is typed is the item already: the character is just typed
            if (item.lookupString == impl.itemPattern(item)) return null
        }
        if (impl.lookupFocusDegree == LookupFocusDegree.SEMI_FOCUSED) impl.lookupFocusDegree = LookupFocusDegree.FOCUSED
        // `Where(` chosen by `(`: the list of the argument opens where a lambda may go, as after a typed `(`
        if (c == '(') CSharpCompletionAutoPopup.afterCommit(lookup.editor)
        return Result.SELECT_ITEM_AND_FINISH_LOOKUP
    }

    companion object {
        val COMMIT = setOf('.', ',', ';', ' ', '=', '[', ')', '(')

        private fun isTemplate(item: LookupElement): Boolean {
            var current: LookupElement? = item
            while (current != null) {
                val name = current.javaClass.name
                if (name.contains("LiveTemplateLookupElement") || name.contains("PostfixTemplateLookupElement")) return true
                current = (current as? LookupElementDecorator<*>)?.delegate
            }
            return false
        }
    }
}

/**
 * What was chosen before goes up (COMPLETION_GAPS 2.8; [SuggestionStats], the same numbers the server's list uses in its ranking), but only
 * among items of one priority: after the priority of the kind and the prefix match, so Rider's order — locals, members, types, keywords —
 * holds, and the most used member is the first member, the one the list selects when members are on top.
 */
class CSharpSuggestionStatsWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        if (location.completionParameters.originalFile !is CSharpFile) return 0
        val count = SuggestionStats.getInstance().labelCount(element.lookupString.removeSuffix("<>"))
        return if (count <= 0) 0 else 1 + (ln(count.toDouble()) / ln(2.0)).toInt()
    }
}
