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
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * Route templates of ASP.NET Core (task 3.7 of docs/COMPLETION_GAPS.md), as Rider finds them: the template of `[Route("…")]` and of
 * `[HttpGet("…")]` / `HttpPost` / `HttpPut` / `HttpDelete` / `HttpPatch` / `HttpHead` / `HttpOptions`, the pattern of the minimal API's
 * `MapGet` / `MapPost` / `MapPut` / `MapDelete` / `MapPatch` / `MapMethods` / `MapGroup` / `MapFallback` / `MapHub` /
 * `MapControllerRoute(name, pattern)`, and a literal after `// lang=route`. The parameters a template binds are those of the action
 * (the method under the attribute) or of the handler (the lambda, or a method of the same type passed by name).
 */
object CSharpRouteTemplates {
    private val ATTRIBUTES = setOf("Route", "HttpGet", "HttpPost", "HttpPut", "HttpDelete", "HttpPatch", "HttpHead", "HttpOptions")
    private val MAP = setOf("MapGet", "MapPost", "MapPut", "MapDelete", "MapPatch", "MapMethods", "MapGroup", "MapFallback", "MapHub", "MapFallbackToFile", "MapFallbackToPage", "MapRazorPages")
    private val COMMENT = Regex("""(?i)\b(lang|language)\s*=\s*route\b""")

    /** What the binder fills from the services, the body or the request itself: no route parameter. */
    private val NOT_ROUTE_TYPES = setOf(
        "CancellationToken", "HttpContext", "HttpRequest", "HttpResponse", "ClaimsPrincipal", "IFormFile", "IFormFileCollection", "IFormCollection",
        "Stream", "PipeReader",
    )
    private val NOT_ROUTE_ATTRIBUTES = setOf("FromBody", "FromServices", "FromKeyedServices", "FromHeader", "FromForm", "FromQuery", "AsParameters")

    enum class Kind { BRACE, PARAMETER, CONSTRAINT, TOKEN }

    /** A colored part of a template over `[start, end)` of the literal's text. */
    class Part(val kind: Kind, val start: Int, val end: Int)

    /** A route constraint with what it accepts and what it inserts (the caret goes between the parentheses of the ones with arguments). */
    class Constraint(val name: String, val title: String, val arguments: String? = null)

    /** The constraints of ASP.NET Core (`RouteOptions.ConstraintMap`), in Rider's order. */
    val CONSTRAINTS = listOf(
        Constraint("int", "32-bit integer"), Constraint("long", "64-bit integer"), Constraint("guid", "GUID"), Constraint("bool", "true or false"),
        Constraint("datetime", "DateTime"), Constraint("decimal", "decimal number"), Constraint("double", "64-bit floating-point"),
        Constraint("float", "32-bit floating-point"), Constraint("alpha", "letters a–z only"), Constraint("required", "non-empty value"),
        Constraint("nonfile", "not a file name"), Constraint("minlength", "string of at least n characters", "n"),
        Constraint("maxlength", "string of at most n characters", "n"), Constraint("length", "string of n (or min..max) characters", "n"),
        Constraint("min", "integer at least n", "n"), Constraint("max", "integer at most n", "n"), Constraint("range", "integer in min..max", "min,max"),
        Constraint("regex", "matches a regular expression", "expression"),
    )

    fun isRoute(literal: CSharpLiteralExpression): Boolean = attribute(literal) != null || call(literal) != null || CSharpRegexPlaces.commentSays(literal, COMMENT) ||
        CSharpStringArguments.callOf(literal)?.let { CSharpRegexPlaces.syntaxParameter(literal.project, it.method, it.index, it.named, "Route") } == true

    /** The attribute whose template [literal] is. */
    private fun attribute(literal: CSharpLiteralExpression): CSharpStringArguments.Attribute? {
        val attribute = CSharpStringArguments.attributeOf(literal) ?: return null
        if (attribute.name !in ATTRIBUTES) return null
        val template = when (attribute.named) {
            null -> attribute.index == 0
            "template", "Template" -> true
            else -> false
        }
        return attribute.takeIf { template }
    }

    private fun call(literal: CSharpLiteralExpression): CSharpStringArguments.Call? {
        val call = CSharpStringArguments.callOf(literal) ?: return null
        if (call.receiver == null) return null
        val pattern = when (call.method) {
            in MAP -> if (call.named != null) call.named == "pattern" else call.index == 0
            "MapControllerRoute", "MapAreaControllerRoute" -> if (call.named != null) call.named == "pattern" else call.index == if (call.method == "MapControllerRoute") 1 else 2
            else -> false
        }
        return call.takeIf { pattern }
    }

    /** The parameters of the action or the handler a route parameter may name. */
    fun handlerParameters(literal: CSharpLiteralExpression): List<CSharpParameter> {
        attribute(literal)?.let { return CSharpStringArguments.parametersOf(CSharpStringArguments.annotatedMethod(it.attribute)).filter(::bindable) }
        val call = call(literal) ?: return emptyList()
        val handler = call.arguments.firstOrNull { it.nameColon?.nameElement?.text == "handler" }?.expression
            ?: call.arguments.drop(call.index + 1).lastOrNull { it.nameColon == null }?.expression ?: return emptyList()
        val function: PsiElement? = when (handler) {
            is CSharpAnonymousFunctionExpression -> handler
            is CSharpSimpleName, is CSharpMemberAccessExpression -> methodNamed(handler, CSharpStringArguments.lastName(handler as CSharpExpression))
            else -> null
        }
        return CSharpStringArguments.parametersOf(function).filter(::bindable)
    }

    /** A method of the type around [place] (or a local function) named [name]: the handler passed as a method group. */
    private fun methodNamed(place: PsiElement, name: String?): PsiElement? {
        name ?: return null
        val type = PsiTreeUtil.getParentOfType(place, CSharpBaseTypeDeclaration::class.java)
        val scope: PsiElement = type ?: place.containingFile ?: return null
        PsiTreeUtil.findChildrenOfType(scope, CSharpMethodDeclaration::class.java).firstOrNull { it.identifier?.text == name }?.let { return it }
        return PsiTreeUtil.findChildrenOfType(scope, CSharpLocalFunctionStatement::class.java).firstOrNull { it.identifier?.text == name }
    }

    private fun bindable(parameter: CSharpParameter): Boolean {
        val type = parameter.type?.text?.trim()?.removeSuffix("?")?.substringAfterLast('.') ?: return true
        if (type in NOT_ROUTE_TYPES) return false
        // services and other interfaces come from the container
        if (type.length > 1 && type[0] == 'I' && type[1].isUpperCase()) return false
        return parameter.attributeLists.none { list -> list.attributes.any { it.nameElement?.text?.substringAfterLast('.')?.removeSuffix("Attribute") in NOT_ROUTE_ATTRIBUTES } }
    }

    /** Is [literal] the template of an attribute, where `[controller]` / `[action]` / `[area]` are tokens? */
    fun allowsTokens(literal: CSharpLiteralExpression): Boolean = attribute(literal) != null

    /** The parts of the literal [text]: braces, the names of the parameters, the names of the constraints, the `[controller]` tokens. */
    fun parts(text: String, tokens: Boolean): List<Part> {
        val shape = CSharpStringLiterals.shape(text) ?: return emptyList()
        val out = ArrayList<Part>()
        val end = shape.contentEnd
        var i = shape.contentStart
        while (i < end) {
            val c = text[i]
            val next = if (i + 1 < end) text[i + 1] else '\u0000'
            when {
                (c == '{' || c == '}') && next == c -> i += 2
                tokens && c == '[' && next != '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close in 0 until end && text.substring(i + 1, close).let { it.isNotEmpty() && it.all { ch -> ch.isLetter() } }) {
                        out += Part(Kind.TOKEN, i, close + 1)
                        i = close + 1
                    } else i++
                }
                c == '{' -> i = parameter(text, i, end, out)
                else -> i++
            }
        }
        return out
    }

    /** `{*name:constraint(args):other?=default}` at [open]; returns where the text after it starts. */
    private fun parameter(text: String, open: Int, end: Int, out: MutableList<Part>): Int {
        var i = open + 1
        while (i < end && text[i] == '*') i++
        val nameStart = i
        while (i < end && text[i] != ':' && text[i] != '?' && text[i] != '=' && text[i] != '}' && text[i] != '{') i++
        if (i >= end || text[i] == '{') return open + 1
        val parts = ArrayList<Part>()
        if (i > nameStart) parts += Part(Kind.PARAMETER, nameStart, i)
        while (i < end && text[i] == ':') {
            i++
            val constraintStart = i
            while (i < end && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            if (i > constraintStart) parts += Part(Kind.CONSTRAINT, constraintStart, i)
            if (i < end && text[i] == '(') {
                // the argument of `regex(…)` may hold anything but its own closing parenthesis; `{{` / `}}` escape braces in it
                var depth = 0
                while (i < end) {
                    if (text[i] == '(') depth++
                    if (text[i] == ')' && --depth == 0) break
                    i++
                }
                if (i < end) i++
            }
        }
        if (i < end && text[i] == '?') i++
        if (i < end && text[i] == '=') while (i < end && text[i] != '}') i++
        if (i >= end || text[i] != '}') return open + 1
        out += Part(Kind.BRACE, open, open + 1)
        out += parts
        out += Part(Kind.BRACE, i, i + 1)
        return i + 1
    }

    fun key(kind: Kind): TextAttributesKey = when (kind) {
        Kind.BRACE, Kind.TOKEN -> CSharpSyntaxHighlighter.FORMAT_ITEM
        Kind.PARAMETER -> CSharpColors.PARAMETER
        Kind.CONSTRAINT -> CSharpColors.METHOD
    }
}

/** Colors the parts of a route template: braces and `[controller]` as format items, the parameter as a parameter, a constraint as a method. */
class CSharpRouteTemplateAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is CSharpStringLiteralLeaf) return
        val literal = CSharpStringArguments.literalOf(element) ?: return
        if (!CSharpRouteTemplates.isRoute(literal)) return
        val base = element.textRange.startOffset
        for (part in CSharpRouteTemplates.parts(element.text, CSharpRouteTemplates.allowsTokens(literal))) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange(base + part.start, base + part.end)).textAttributes(CSharpRouteTemplates.key(part.kind)).create()
        }
    }
}

/**
 * COMPLETION in a route template (task 3.7): after `{` the parameters of the action or the handler the template does not name yet,
 * after `:` of a parameter the route constraints (`int`, `guid`, `minlength(…)`…), after `[` of an attribute's template `controller`,
 * `action`, `area`. Nothing else in the template.
 */
class CSharpRouteTemplateCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original)) return
        val leaf = parameters.position
        val literal = CSharpStringArguments.literalOf(leaf) ?: return
        if (!CSharpRouteTemplates.isRoute(literal)) return
        val typed = CSharpStringArguments.typedBefore(leaf, parameters.offset, parameters.editor.document.charsSequence) ?: return
        val open = typed.lastIndexOf('{')
        val token = typed.lastIndexOf('[')
        when {
            open >= 0 && open > typed.lastIndexOf('}') && (open == 0 || typed[open - 1] != '{') -> inParameter(literal, typed, open, parameters, result)
            token >= 0 && token > typed.lastIndexOf(']') && CSharpRouteTemplates.allowsTokens(literal) -> {
                val prefix = typed.substring(token + 1)
                if (prefix.all { it.isLetter() }) {
                    val set = result.withPrefixMatcher(prefix)
                    for (name in listOf("controller", "action", "area")) set.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Variable).withInsertHandler(closing(']')))
                }
            }
        }
        result.stopHere()
    }

    private fun inParameter(literal: CSharpLiteralExpression, typed: String, open: Int, parameters: CompletionParameters, result: CompletionResultSet) {
        val inside = typed.substring(open + 1)
        val colon = inside.lastIndexOf(':')
        if (colon >= 0) {
            val prefix = inside.substring(colon + 1)
            if (!prefix.all { it.isLetterOrDigit() }) return
            val set = result.withPrefixMatcher(prefix)
            for ((index, constraint) in CSharpRouteTemplates.CONSTRAINTS.withIndex()) {
                val element = LookupElementBuilder.create(constraint.name).withIcon(AllIcons.Nodes.Method)
                    .withTailText(constraint.arguments?.let { "($it)" }, true).withTypeText(constraint.title, true)
                    .withInsertHandler(if (constraint.arguments != null) PARENTHESES else null)
                set.addElement(PrioritizedLookupElement.withPriority(element, (CSharpRouteTemplates.CONSTRAINTS.size - index).toDouble()))
            }
            return
        }
        val prefix = inside.trimStart('*')
        if (!prefix.all { it.isLetterOrDigit() || it == '_' }) return
        val original = parameters.originalPosition?.takeIf { it is CSharpStringLiteralLeaf }
        val named = original?.let { leaf -> CSharpRouteTemplates.parts(leaf.text, false).filter { it.kind == CSharpRouteTemplates.Kind.PARAMETER }.map { leaf.text.substring(it.start, it.end).lowercase() } }.orEmpty().toSet()
        val set = result.withPrefixMatcher(prefix)
        val candidates = CSharpRouteTemplates.handlerParameters(literal).filter { it.identifier?.text?.lowercase() !in named }
        for ((index, parameter) in candidates.withIndex()) {
            val name = parameter.identifier?.text?.removePrefix("@") ?: continue
            val element = LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Parameter).withTypeText(parameter.type?.text?.trim(), true).withInsertHandler(closing('}'))
            set.addElement(PrioritizedLookupElement.withPriority(element, (candidates.size - index).toDouble()))
        }
    }

    private companion object {
        /** Writes [c] after the name unless the template has it there already; the caret stays before it, where `:constraint` goes. */
        fun closing(c: Char) = InsertHandler<LookupElement> { context, _ ->
            val tail = context.tailOffset
            if (context.document.charsSequence.getOrNull(tail) != c) context.document.insertString(tail, c.toString())
            context.editor.caretModel.moveToOffset(tail)
        }

        val PARENTHESES = InsertHandler<LookupElement> { context, _ ->
            val tail = context.tailOffset
            if (context.document.charsSequence.getOrNull(tail) != '(') context.document.insertString(tail, "()")
            context.editor.caretModel.moveToOffset(tail + 1)
        }
    }
}
