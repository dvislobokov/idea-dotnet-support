package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * Message templates of logging (task 3.8 of docs/COMPLETION_GAPS.md), as Rider treats them: the message of `logger.LogInformation(…)`
 * and the other extensions of Microsoft.Extensions.Logging (`LogTrace` … `LogCritical`, `Log(level, …)`, `BeginScope`), of Serilog
 * (`Log.Information(…)`, `logger.Warning(…)` — a receiver named like a logger) and `[LoggerMessage(Message = "…")]` of the source
 * generator. The template is the first string literal argument; the arguments after it fill its placeholders by position, the parameters
 * of the method fill those of `[LoggerMessage]` by name. By the syntax only, so the same on any project.
 */
object CSharpLoggerTemplates {
    private val EXTENSIONS = setOf("LogTrace", "LogDebug", "LogInformation", "LogWarning", "LogError", "LogCritical", "Log", "BeginScope")
    private val SERILOG = setOf("Verbose", "Debug", "Information", "Warning", "Error", "Fatal", "Write")
    private val SERILOG_RECEIVER = Regex("""(?i)^_?log$|logger$""")
    private val MESSAGE_NAMES = setOf("message", "messageTemplate", "messageFormat")

    /** A placeholder `{Name}`, `{@Name}`, `{Name,5:N2}` over `[start, end)` of the literal's text; [name] without `@` / `$`. */
    class Hole(val start: Int, val end: Int, val name: String)

    /**
     * The template [literal] is: [arguments] fill its placeholders by position (null when they are passed as an array, so not countable);
     * [parameters] are those of a `[LoggerMessage]` method that fill them by name.
     */
    class Template(val literal: CSharpLiteralExpression, val arguments: List<CSharpExpression>?, val parameters: List<CSharpParameter>?)

    fun templateOf(literal: CSharpLiteralExpression): Template? {
        CSharpStringArguments.callOf(literal)?.let { return ofCall(it) }
        val attribute = CSharpStringArguments.attributeOf(literal) ?: return null
        if (attribute.name != "LoggerMessage") return null
        val message = when (attribute.named) {
            "Message", "message" -> true
            null -> attribute.arguments.count { it.nameEquals == null && it.nameColon == null && isStringLiteral(it.expression) } == 1
            else -> false
        }
        if (!message) return null
        val method = CSharpStringArguments.annotatedMethod(attribute.attribute) ?: return null
        val parameters = CSharpStringArguments.parametersOf(method).filter { parameter ->
            val type = parameter.type?.text?.trim().orEmpty()
            !(type == "ILogger" || type.startsWith("ILogger<") || type.endsWith(".ILogger") || type == "LogLevel" || type.endsWith(".LogLevel"))
        }
        // the first exception is the exception of the entry, not a value of the message
        val exception = parameters.firstOrNull { it.type?.text?.trim()?.removeSuffix("?")?.endsWith("Exception") == true }
        return Template(literal, null, parameters.filter { it !== exception })
    }

    private fun ofCall(call: CSharpStringArguments.Call): Template? {
        val extension = call.method in EXTENSIONS && call.receiver != null
        val serilog = call.method in SERILOG && CSharpStringArguments.lastName(call.receiver)?.let(SERILOG_RECEIVER::containsMatchIn) == true
        if (!extension && !serilog) return null
        if (call.named != null && call.named !in MESSAGE_NAMES) return null
        val before = call.arguments.subList(0, call.index)
        // the message is the first string; `Log(level, …)` has one argument in front of it at least
        if (before.any { '"' in it.text }) return null
        if (call.method == "Log" && before.isEmpty() && call.named == null) return null
        val after = call.arguments.subList(call.index + 1, call.arguments.size)
        val spread = after.any { it.nameColon != null } ||
            after.singleOrNull()?.expression.let { it is CSharpArrayCreationExpression || it is CSharpImplicitArrayCreationExpression || it is CSharpCollectionExpression }
        return Template(call.literal, if (spread) null else after.mapNotNull { it.expression }, null)
    }

    private fun isStringLiteral(expression: CSharpExpression?): Boolean = (expression as? CSharpLiteralExpression)?.token is CSharpStringLiteralLeaf

    /** The placeholders of the literal [text] (`{{` and `}}` are text). */
    fun holes(text: String): List<Hole> {
        val shape = CSharpStringLiterals.shape(text) ?: return emptyList()
        val out = ArrayList<Hole>()
        val end = shape.contentEnd
        var i = shape.contentStart
        while (i < end) {
            val c = text[i]
            val next = if (i + 1 < end) text[i + 1] else '\u0000'
            when {
                c == '\\' && shape.kind == CSharpStringLiterals.Kind.REGULAR -> i += 2
                (c == '{' || c == '}') && next == c -> i += 2
                c == '{' -> {
                    val close = text.indexOf('}', i + 1)
                    if (close < 0 || close >= end) return out
                    val inner = text.substring(i + 1, close)
                    if ('{' in inner || '"' in inner) {
                        i++
                        continue
                    }
                    val name = inner.trimStart().removePrefix("@").removePrefix("$").takeWhile { it != ',' && it != ':' }.trim()
                    if (name.isNotEmpty()) out += Hole(i, close + 1, name)
                    i = close + 1
                }
                else -> i++
            }
        }
        return out
    }

    /** The names a placeholder of the value [expression] takes, the best first: `order.Id` gives `OrderId` and `Id`, `GetName()` gives `Name`. */
    fun namesOf(expression: CSharpExpression?): List<String> {
        fun pascal(name: String?): String? = name?.removePrefix("@")?.trimStart('_')?.takeIf { it.isNotEmpty() && it[0].isLetter() }?.let { it[0].uppercaseChar() + it.substring(1) }
        fun member(receiver: CSharpExpression?, name: String?): List<String> {
            val last = pascal(name) ?: return emptyList()
            val owner = (receiver as? CSharpSimpleName)?.let { pascal(it.identifier?.text) }
            return listOfNotNull(owner?.let { it + last }, last)
        }
        return when (expression) {
            is CSharpSimpleName -> listOfNotNull(pascal(expression.identifier?.text))
            is CSharpMemberAccessExpression -> member(expression.expression, expression.nameElement?.identifier?.text)
            is CSharpConditionalAccessExpression -> member(expression.expression, (expression.whenNotNull as? CSharpMemberBindingExpression)?.nameElement?.identifier?.text)
            is CSharpInvocationExpression -> namesOf(expression.expression).map { if (it.length > 3 && it.startsWith("Get") && it[3].isUpperCase()) it.substring(3) else it }
            is CSharpParenthesizedExpression -> namesOf(expression.expression)
            is CSharpAwaitExpression -> namesOf(expression.expression)
            is CSharpCastExpression -> namesOf(expression.expression)
            is CSharpPostfixUnaryExpression -> namesOf(expression.operand)
            is CSharpBinaryExpression -> if (expression.operatorToken?.text == "??") namesOf(expression.left) else emptyList()
            else -> emptyList()
        }.distinct()
    }

    /** The problems of the template: placeholders without an argument (parameter) and arguments without a placeholder, as Rider and CA2017 / SYSLIB1014 tell them. */
    fun problems(template: Template): List<Pair<TextRange, String>> {
        val leaf = template.literal.token ?: return emptyList()
        val base = leaf.textRange.startOffset
        val holes = holes(leaf.text)
        fun range(hole: Hole) = TextRange(base + hole.start, base + hole.end)
        val out = ArrayList<Pair<TextRange, String>>()
        template.parameters?.let { parameters ->
            val names = parameters.mapNotNull { it.identifier?.text?.removePrefix("@")?.lowercase() }.toSet()
            for (hole in holes) if (hole.name.lowercase() !in names) out += range(hole) to "No method parameter for the placeholder '${hole.name}' of the message template"
            return out
        }
        val arguments = template.arguments ?: return out
        val distinct = holes.map { it.name }.distinct().size
        if (arguments.size == holes.size || arguments.size == distinct) return out
        if (arguments.size < holes.size) {
            for (hole in holes.drop(arguments.size)) out += range(hole) to "No argument for the placeholder '${hole.name}' of the message template"
        } else {
            for (argument in arguments.drop(holes.size)) out += argument.textRange to "The argument is not used in the message template"
        }
        return out
    }
}

/**
 * The placeholders of a message template in the color of format items (Rider: `FORMAT_STRING_ITEM`, the second of two side by side in
 * the second color), and the warnings of [CSharpLoggerTemplates.problems] on the call or the attribute that holds the template.
 */
class CSharpLoggerTemplateAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is CSharpStringLiteralLeaf -> colors(element, holder)
            is CSharpInvocationExpression, is CSharpAttribute -> {
                val arguments = when (element) {
                    is CSharpInvocationExpression -> element.argumentList?.arguments.orEmpty().map { it.expression }
                    else -> (element as CSharpAttribute).argumentList?.arguments.orEmpty().map { it.expression }
                }
                val literal = arguments.firstNotNullOfOrNull { (it as? CSharpLiteralExpression)?.takeIf { l -> l.token is CSharpStringLiteralLeaf } } ?: return
                val template = CSharpLoggerTemplates.templateOf(literal) ?: return
                if (template.arguments != null && analyzerReports(element)) return
                // the problems of another template (a nested call) are reported by its own call
                for ((range, message) in CSharpLoggerTemplates.problems(template)) {
                    if (!element.textRange.contains(range)) continue
                    holder.newAnnotation(HighlightSeverity.WARNING, message).range(range).create()
                }
            }
        }
    }

    /** The analyzers of the project (CodeAnalysisService) reported CA2017 in the file at the last save: theirs stands, no second warning. */
    private fun analyzerReports(element: PsiElement): Boolean {
        val service = element.project.getServiceIfCreated(io.github.dotnetsupport.codeanalysis.CodeAnalysisService::class.java) ?: return false
        if (!service.analyzersActive) return false
        val path = element.containingFile?.viewProvider?.virtualFile?.path ?: return false
        return service.analyzedFile(path)?.diagnostics?.any { it.id == "CA2017" } == true
    }

    private fun colors(leaf: CSharpStringLiteralLeaf, holder: AnnotationHolder) {
        val literal = CSharpStringArguments.literalOf(leaf) ?: return
        CSharpLoggerTemplates.templateOf(literal) ?: return
        val base = leaf.textRange.startOffset
        var lastEnd = -1
        var lastSecond = false
        for (hole in CSharpLoggerTemplates.holes(leaf.text)) {
            val second = hole.start == lastEnd && !lastSecond
            val key = if (second) CSharpSyntaxHighlighter.FORMAT_ITEM_2 else CSharpSyntaxHighlighter.FORMAT_ITEM
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange(base + hole.start, base + hole.end)).textAttributes(key).create()
            lastEnd = hole.end
            lastSecond = second
        }
    }
}

/**
 * COMPLETION in a message template (task 3.8): after `{` the names of the arguments in the place of the placeholder (`order.Id` gives
 * `OrderId`, `Id`; the argument of that position first), the parameters of a `[LoggerMessage]` method; in the text, Ctrl+Space offers
 * `{Name}` for each argument no placeholder takes yet, as Rider does.
 */
class CSharpLoggerTemplateCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original)) return
        val leaf = parameters.position
        val literal = CSharpStringArguments.literalOf(leaf) ?: return
        val template = CSharpLoggerTemplates.templateOf(literal) ?: return
        val text = parameters.editor.document.charsSequence
        val typed = CSharpStringArguments.typedBefore(leaf, parameters.offset, text) ?: return
        val hole = openHole(typed)
        if (hole == null) {
            plainText(parameters, result, template, typed, text)
            return
        }
        val inside = typed.substring(hole + 1).removePrefix("@").removePrefix("$")
        if (inside.any { !it.isLetterOrDigit() && it != '_' }) {
            result.stopHere()
            return
        }
        val position = CSharpLoggerTemplates.holes("\"" + typed.substring(0, hole) + "\"").size
        val set = result.withPrefixMatcher(inside)
        val candidates = candidates(template, position)
        for ((index, candidate) in candidates.withIndex()) {
            val element = LookupElementBuilder.create(candidate.first).withTypeText(candidate.second, true).withIcon(AllIcons.Nodes.Parameter)
                .withInsertHandler(CLOSE_BRACE)
            set.addElement(PrioritizedLookupElement.withPriority(element, (candidates.size - index).toDouble()))
        }
        result.stopHere()
    }

    /** Ctrl+Space in the text: `{Name}` for the arguments after those the placeholders take. Nothing in a list that opened by itself. */
    private fun plainText(parameters: CompletionParameters, result: CompletionResultSet, template: CSharpLoggerTemplates.Template, typed: String, text: CharSequence) {
        if (parameters.isAutoPopup) return
        val arguments = template.arguments ?: return
        val original = parameters.originalPosition?.takeIf { it is CSharpStringLiteralLeaf } ?: return
        val taken = CSharpLoggerTemplates.holes(original.text).size
        val prefix = typed.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        val set = result.withPrefixMatcher(prefix)
        val free = arguments.drop(taken)
        for ((index, argument) in free.withIndex()) {
            val name = CSharpLoggerTemplates.namesOf(argument).firstOrNull() ?: continue
            val element = LookupElementBuilder.create("{$name}").withLookupString(name).withTypeText(argument.text.take(40), true).withIcon(AllIcons.Nodes.Parameter)
            set.addElement(PrioritizedLookupElement.withPriority(element, (free.size - index).toDouble()))
        }
        if (free.isNotEmpty()) result.stopHere()
    }

    /** The names to offer with what they come from: the argument at [position] first, then the others in order. */
    private fun candidates(template: CSharpLoggerTemplates.Template, position: Int): List<Pair<String, String>> {
        template.parameters?.let { parameters ->
            return parameters.mapNotNull { p -> p.identifier?.text?.removePrefix("@")?.let { it to (p.type?.text?.trim() ?: "") } }
        }
        val arguments = template.arguments ?: return emptyList()
        val ordered = listOfNotNull(arguments.getOrNull(position)) + arguments.filterIndexed { i, _ -> i != position }
        val seen = HashSet<String>()
        return ordered.flatMap { argument -> CSharpLoggerTemplates.namesOf(argument).filter(seen::add).map { it to argument.text.take(40) } }
    }

    private companion object {
        /** The `{` of the placeholder the caret is in (in [typed]), or null in the text. */
        fun openHole(typed: String): Int? {
            var i = typed.length - 1
            while (i >= 0) {
                when (typed[i]) {
                    '}' -> return null
                    '{' -> {
                        var braces = 0
                        while (i - braces >= 0 && typed[i - braces] == '{') braces++
                        return if (braces % 2 == 1) i else null
                    }
                }
                i--
            }
            return null
        }

        val CLOSE_BRACE = InsertHandler<LookupElement> { context, _ ->
            val document = context.document
            val tail = context.tailOffset
            if (document.charsSequence.getOrNull(tail) != '}') document.insertString(tail, "}")
            context.editor.caretModel.moveToOffset(tail + 1)
        }
    }
}
