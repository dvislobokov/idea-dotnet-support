package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.dotnetsupport.lang.CSharpExpectations
import io.github.dotnetsupport.lang.CSharpExpected
import io.github.dotnetsupport.lang.CSharpNameLikeness
import io.github.dotnetsupport.lang.CSharpScopeTypes
import io.github.dotnetsupport.lang.CSharpTypeNames
import io.github.dotnetsupport.suggest.SuggestionRules
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import kotlin.math.ln

/**
 * What decides the order of the list besides the kind of an item ([RoslynCompletionPolicy.priority]): what is wanted at the caret
 * and what is known of the item. The server sorts by name; Rider puts on top what fits the place — the variable of the type of the
 * parameter, the one named as the parameter is, the one declared a line above, the one chosen here before.
 */
object RoslynCompletionRanking {
    class Context(
        val expected: CSharpExpected?,
        /** The names of the file the caret can see; empty after a dot, where the list is of the members of something else. */
        val symbols: Map<String, CSharpScopeTypes.Symbol>,
        /** The line of the caret, from 0. */
        val line: Int,
    ) {
        companion object {
            val NONE = Context(null, emptyMap(), 0)
        }
    }

    class Bonus(val value: Double, val signals: Set<String>)

    const val TYPE = 25.0
    const val NAME_EXACT = 30.0
    const val NAME_PARTIAL = 12.0
    const val LOCAL = 3.0
    const val NEARBY = 1.0
    const val NEARBY_LINES = 5
    const val USED_MAX = 5.0

    private val NAMED = setOf(
        CompletionItemKind.Variable, CompletionItemKind.Field, CompletionItemKind.Property, CompletionItemKind.Constant, CompletionItemKind.EnumMember,
        CompletionItemKind.Method, CompletionItemKind.Function,
    )

    /** `int amount = |`: the `amount` that is being declared is no candidate for its own initializer (the server lists it: it is in scope). */
    fun isBeingDeclared(name: String, kind: CompletionItemKind?, context: Context): Boolean =
        kind == CompletionItemKind.Variable && context.expected?.declared == true && context.expected.name == name

    /** [name] is the label of the item without the `<>` of a generic; [chosenBefore] how many times it was chosen in the list. */
    fun bonus(name: String, kind: CompletionItemKind?, context: Context, chosenBefore: Int): Bonus {
        var value = 0.0
        val signals = LinkedHashSet<String>()
        if (kind in NAMED) {
            val symbol = context.symbols[name]
            if (symbol != null && CSharpTypeNames.matches(context.expected?.type, symbol.type)) {
                value += TYPE
                signals += SuggestionRules.SIGNAL_TYPE
            }
            when (CSharpNameLikeness.of(context.expected?.name, name)) {
                CSharpNameLikeness.Likeness.EXACT -> { value += NAME_EXACT; signals += SuggestionRules.SIGNAL_NAME }
                CSharpNameLikeness.Likeness.PARTIAL -> { value += NAME_PARTIAL; signals += SuggestionRules.SIGNAL_NAME }
                CSharpNameLikeness.Likeness.NONE -> Unit
            }
            if (symbol?.local == true) {
                value += LOCAL
                if (context.line - symbol.line in 0..NEARBY_LINES) value += NEARBY
                signals += SuggestionRules.SIGNAL_LOCAL
            }
        }
        if (chosenBefore > 0) {
            // 1 -> 1, 3 -> 2, 7 -> 3, 31 -> 5: what is chosen often goes up, and never over what fits the place
            value += (ln(1.0 + chosenBefore) / ln(2.0)).coerceAtMost(USED_MAX)
            signals += SuggestionRules.SIGNAL_USED
        }
        return Bonus(value, signals)
    }

    /** `Order order`, `CancellationToken cancellationToken = default`, `params object[] args` of a signature: what an argument is to be. */
    fun expectedOf(parameter: String): CSharpExpected? {
        val (name, type) = CSharpScopeTypes.parameters(parameter).singleOrNull() ?: return null
        return CSharpExpected(type?.removeSuffix("[]")?.takeIf { parameter.trimStart().startsWith("params ") } ?: type, name)
    }

    fun expectedOf(help: SignatureHelp): CSharpExpected? {
        val signature = help.signatures.orEmpty().getOrNull(help.activeSignature ?: 0) ?: return null
        val parameters = RoslynSignatures.parameters(signature)
        val declared = parameters.getOrNull(help.activeParameter ?: 0) ?: parameters.lastOrNull()?.takeIf { it.trimStart().startsWith("params ") } ?: return null
        return expectedOf(declared)
    }

    /** Where the name that is being completed begins. */
    fun nameStart(text: CharSequence, offset: Int): Int {
        var start = offset.coerceIn(0, text.length)
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '@')) start--
        return start
    }

    /** By the text alone: what the file says of the place. The argument of a call is added by [RoslynCompletionContext]. */
    fun contextOf(text: CharSequence, offset: Int): Context {
        val start = nameStart(text, offset)
        var before = start - 1
        while (before >= 0 && (text[before] == ' ' || text[before] == '\t')) before--
        val afterDot = before >= 0 && text[before] == '.'
        val all = CSharpScopeTypes.at(text, start)
        // `int amount = order.|`: the list is of the members of `order`, none of them is the `amount` being declared
        val expected = CSharpExpectations.at(text, start, all)?.let { if (afterDot && it.declared) CSharpExpected(it.type, it.name) else it }
        return Context(expected, if (afterDot) emptyMap() else all, CSharpScopeTypes.lineOf(text, start))
    }
}

/**
 * The context of the list that is being filled: computed once for a place, asked for by every item of the list. Typing on changes
 * the text and not the place, so what was found for `Save(` holds for `Save(or`.
 */
@Service(Service.Level.PROJECT)
class RoslynCompletionContext(private val project: Project) {
    /** The file and where the name begins; [stamp] is the state of the document it was last asked in. */
    private var place: String? = null
    private var before = 0
    private var stamp = -1L
    private var context = RoslynCompletionRanking.Context.NONE

    @Synchronized
    fun at(parameters: CompletionParameters): RoslynCompletionRanking.Context {
        val file = parameters.originalFile.virtualFile ?: return RoslynCompletionRanking.Context.NONE
        val document = parameters.editor.document
        val text = document.immutableCharSequence
        val start = RoslynCompletionRanking.nameStart(text, parameters.offset)
        val here = file.url + ":" + start
        // asked by every item of a list: nothing has changed since the item before
        if (here == place && document.modificationStamp == stamp) return context
        // the text has changed: the same place still, when what was typed is the name itself
        val hash = text.subSequence(0, start).hashCode()
        val same = here == place && hash == before
        place = here
        before = hash
        stamp = document.modificationStamp
        if (same) return context
        val found = runCatching { RoslynCompletionRanking.contextOf(text, parameters.offset) }.getOrDefault(RoslynCompletionRanking.Context.NONE)
        val argument = if (LambdaSuggestions.atArgumentStart(text, start)) argument(parameters, start) else null
        context = if (argument == null) found else RoslynCompletionRanking.Context(argument, found.symbols, found.line)
        return context
    }

    private fun argument(parameters: CompletionParameters, start: Int): CSharpExpected? {
        val workspace = project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded } ?: return null
        val file = parameters.originalFile.virtualFile ?: return null
        val position = RoslynNavigation.position(parameters.editor.document, start)
        val params = SignatureHelpParams(client.getDocumentIdentifier(file), position)
        val help = runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) } }.getOrNull() ?: return null
        return RoslynCompletionRanking.expectedOf(help)
    }

    private companion object {
        /** The list waits for it: shorter than what the lambda of the same place is given. */
        const val TIMEOUT_MS = 400
    }
}
