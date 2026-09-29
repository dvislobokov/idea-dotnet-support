package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lang.CSharpExpressions
import io.github.dotnetsupport.lang.CSharpFile
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams

/**
 * A lambda where a delegate is expected, first in the list, as Rider offers it: at an argument whose parameter is `Func<IServiceProvider, object>`
 * the list starts with `serviceProvider => `, at `Action` with `() => `, at `EventHandler` with `(sender, e) => `. The parameter types
 * come from the signature help of the server (the active overload first, the other overloads after it); the names of the lambda
 * parameters are made from the types, as Rider names them. Roslyn itself offers nothing of the kind.
 */
class RoslynLambdaCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        val workspace = file.project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded } ?: return
        // only where an argument begins: right after `(` or `,`, or a name that is being typed there
        val text = parameters.editor.document.charsSequence
        val start = parameters.offset - result.prefixMatcher.prefix.length
        if (!LambdaSuggestions.atArgumentStart(text, start)) return
        val help = signatureHelp(client, file, parameters.offset) ?: return
        val indent = CSharpExpressions.indentAt(text, start)
        for ((index, lambda) in LambdaSuggestions.forHelp(help).withIndex()) {
            val priority = LambdaPriority.BASE - index
            result.addElement(PrioritizedLookupElement.withPriority(lambda.inlineElement(), priority))
            result.addElement(PrioritizedLookupElement.withPriority(lambda.blockElement(indent), priority - 0.5))
        }
    }

    private fun signatureHelp(client: com.intellij.platform.lsp.api.LspClient, file: PsiFile, offset: Int): SignatureHelp? {
        val virtualFile = file.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val params = SignatureHelpParams(client.getDocumentIdentifier(virtualFile), RoslynNavigation.position(document, offset))
        return runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.signatureHelp(params) } }.getOrNull()?.takeIf { it.signatures.orEmpty().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 1_500
    }
}

object LambdaPriority {
    /** Above everything the server sends (see RoslynCompletionPolicy.priority) and above its preselected item. */
    const val BASE = 200.0
}

/** A lambda for a delegate type: its parameter names, and the two ways to insert it. */
class LambdaSuggestion(val parameters: List<String>) {
    /** `x => `, `(a, b) => `, `() => ` */
    val head: String get() = when (parameters.size) {
        1 -> "${parameters.single()} => "
        else -> "(${parameters.joinToString(", ")}) => "
    }

    fun inlineElement() = LookupElementBuilder.create(head).withPresentableText(head.trimEnd()).withIcon(AllIcons.Nodes.Lambda).withTypeText("lambda", true)
        .withInsertHandler { context, _ -> context.editor.caretModel.moveToOffset(context.tailOffset) }

    /** `x =>` + a block below, the caret on the empty line inside. */
    fun blockElement(indent: String): com.intellij.codeInsight.lookup.LookupElement {
        val unit = "    "
        val text = "${head.trimEnd()}\n$indent{\n$indent$unit\n$indent}"
        return LookupElementBuilder.create(text).withPresentableText("${head.trimEnd()} { ... }").withIcon(AllIcons.Nodes.Lambda).withTypeText("lambda", true)
            .withInsertHandler { context, _ -> context.editor.caretModel.moveToOffset(context.startOffset + head.trimEnd().length + 1 + indent.length + 2 + indent.length + unit.length) }
    }
}

/** The delegate types a lambda is offered for, and the names of its parameters. */
object LambdaSuggestions {
    private val DELEGATE = Regex("""^(?:params\s+|ref\s+|in\s+|out\s+)?(?:System\.)?(?:Linq\.Expressions\.)?(Expression<(.+)>|Func<(.+)>|Action<(.+)>|Action|Predicate<(.+)>|Comparison<(.+)>|Converter<(.+)>|EventHandler<(.+)>|EventHandler|Func|Predicate)\??$""")
    private val WELL_KNOWN = mapOf("int" to "i", "long" to "l", "string" to "s", "bool" to "b", "double" to "d", "float" to "f", "decimal" to "d", "char" to "c", "object" to "o", "byte" to "b")

    /** The lambdas for the parameter at the caret, the one of the active overload first, without repeats. */
    fun forHelp(help: SignatureHelp): List<LambdaSuggestion> {
        val signatures = help.signatures.orEmpty()
        val active = help.activeSignature ?: 0
        val ordered = listOfNotNull(signatures.getOrNull(active)) + signatures.filterIndexed { i, _ -> i != active }
        val parameter = help.activeParameter ?: 0
        return ordered.mapNotNull { signature ->
            val parameters = RoslynSignatures.parameters(signature)
            val declared = parameters.getOrNull(parameter) ?: parameters.lastOrNull()?.takeIf { it.startsWith("params ") } ?: return@mapNotNull null
            forParameter(declared)
        }.distinctBy { it.head }
    }

    /** `Func<IServiceProvider, object> implementationFactory` -> `serviceProvider => `; null when the type is not a delegate the names are known for. */
    fun forParameter(declared: String): LambdaSuggestion? {
        val type = declared.trim().let { if (it.contains(' ') && !it.endsWith(">")) it.substringBeforeLast(' ') else it }.trim()
        val match = DELEGATE.matchEntire(type) ?: return null
        val kind = match.groupValues[1]
        val inner = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }
        if (kind.startsWith("Expression<")) return forParameter(inner.orEmpty())
        val arguments = inner?.let(::splitGenericArguments).orEmpty()
        val parameterTypes = when {
            kind.startsWith("Func") -> arguments.dropLast(1)
            kind.startsWith("Converter") -> arguments.take(1)
            kind.startsWith("EventHandler") -> return LambdaSuggestion(listOf("sender", "e"))
            kind.startsWith("Comparison") -> return LambdaSuggestion(listOf("x", "y"))
            else -> arguments // Action, Predicate
        }
        return LambdaSuggestion(names(parameterTypes))
    }

    fun splitGenericArguments(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in text.withIndex()) {
            when (c) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> depth--
                ',' -> if (depth == 0) { parts += text.substring(start, i).trim(); start = i + 1 }
            }
        }
        parts += text.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }

    /** `IServiceProvider` -> `serviceProvider`, `TService` -> `service`, `int` -> `i`, `Order[]` -> `orders`, `List<Order>` -> `orders`; repeats get numbers. */
    fun names(types: List<String>): List<String> {
        val result = ArrayList<String>()
        for (type in types) {
            val base = nameOf(type)
            var name = base
            var n = 1
            while (name in result) name = base + (++n)
            result += name
        }
        return result
    }

    private fun nameOf(type: String): String {
        var t = type.trim().removeSuffix("?")
        val array = t.endsWith("[]")
        t = t.removeSuffix("[]").substringAfterLast('.')
        val collection = t.matches(Regex("""(?:I?(?:List|Enumerable|Collection|ReadOnlyList|ReadOnlyCollection|Set|HashSet|Array|Queue|Stack))<.+>"""))
        if (collection) t = t.substringAfter('<').substringBeforeLast('>').substringAfterLast('.')
        t = t.substringBefore('<')
        WELL_KNOWN[t]?.let { return if (array || collection) it + "s" else it }
        if (t.length > 1 && t[0] == 'I' && t[1].isUpperCase()) t = t.substring(1)
        if (t.length > 1 && t[0] == 'T' && t[1].isUpperCase()) t = t.substring(1)
        if (t.isEmpty() || t == "T") return "x"
        val name = t.replaceFirstChar { it.lowercase() }
        return if (array || collection) name + "s" else name
    }

    /** Right after `(` or `,` of a call (whitespace aside), where an argument begins. */
    fun atArgumentStart(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i--
        return i >= 0 && (text[i] == '(' || text[i] == ',')
    }
}
