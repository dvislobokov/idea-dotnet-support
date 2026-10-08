package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.MacroCallNode
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.codeInsight.template.macro.CompleteMacro
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lang.semantic.CSharpPostfixFacts
import io.github.dotnetsupport.lang.semantic.NamedType
import io.github.dotnetsupport.lsp.RoslynServerStatus

/**
 * Postfix templates of C# (`person.if`, `list.foreach`, `Load().await`, `x.return`...), Rider's list (jetbrains.com/help/rider/Postfix_Templates,
 * its descriptions and examples) and a few of the plugin's own (`.cw`, `.str`, `.nameof`, `.awaitusing`). The expression before the key is
 * what [CSharpExpressions] finds; its type ([CSharpPostfixFacts], COMPLETION_GAPS 2.1) narrows the list as Rider does — `.await` of a
 * task, `.foreach` of a collection, `.if` of a `bool` — and offers everything when it is not known. Statement templates (`if`, `return`,
 * `var`...) apply where a statement may start inside a body; `.inject` between the members of a type; expression templates anywhere.
 * Braces go on their own lines, as the C# code style of the SDK has it.
 */
class CSharpPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = CSharpPostfixTemplates.all(this)

    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'
    override fun preExpand(file: PsiFile, editor: Editor) = Unit
    override fun afterExpand(file: PsiFile, editor: Editor) = Unit
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}

/**
 * What a template turns `expr` into: the text, where the caret goes in it, and what is selected (both as offsets inside [text]). With
 * [parts], a live template instead: its stops are names to choose (`.var`) or places to complete (`.to`, `.arg`). [edits] change the text
 * elsewhere (`.field` declares the field); [replace]: what the expansion replaces, when not the expression.
 */
class Expansion(
    val text: String, val caret: Int, val select: IntRange? = null,
    val parts: List<Part>? = null, val edits: List<CSharpPostfixMembers.Edit> = emptyList(), val replace: TextRange? = null,
) {
    sealed class Part
    class Text(val text: String) : Part()
    /** A stop of the template: the first of a name offers [choices] (the first one typed in), the next ones repeat it. */
    class Stop(val name: String, val choices: List<String>, val complete: Boolean = false) : Part()
    data object End : Part()

    companion object {
        fun template(vararg parts: Any): Expansion {
            val list = parts.map { if (it is String) Text(it) else it as Part }
            return Expansion(list.filterIsInstance<Text>().joinToString("") { it.text }, 0, parts = list)
        }
    }
}

/** Where a template is asked for: the expression, its line's indentation, one indentation unit, the facts of its type, the whole text. */
class PostfixSite(
    val expression: String, val indent: String, val unit: String, val facts: CSharpPostfixFacts?, val text: CharSequence, val range: TextRange,
) {
    /** Names the new variable must not take: the identifiers of the declaration around the expression. */
    val taken: Set<String> by lazy {
        val around = CSharpSyntaxModel.current.declarations(text).pathTo(range.startOffset).lastOrNull()?.range
        CSharpExpressionNames.identifiersIn(around?.let { text.subSequence(it.startOffset, it.endOffset) } ?: text)
    }
}

/** Where a template applies: [STATEMENT] at the start of a statement inside a body, [MEMBER] between the members of a type. */
enum class PostfixPlace { EXPRESSION, STATEMENT, MEMBER }

class CSharpPostfixTemplate(
    id: String, key: String, private val description: String, example: String, provider: PostfixTemplateProvider,
    private val place: PostfixPlace,
    /** `await` in the expansion: the function around it is made `async`, as choosing `await` in completion does ([NativeCSharpCommonCalls.makeAsyncAt]). */
    private val awaits: Boolean = false,
    /** Whether the template fits the type of the expression; asked only when the type is known ([CSharpPostfixFacts]). */
    private val fits: (CSharpPostfixFacts) -> Boolean = { true },
    /** Whether the expression's text fits (`.inject` of a type name). */
    private val fitsText: (String) -> Boolean = { true },
    private val render: (PostfixSite) -> Expansion?,
) : PostfixTemplate("csharp.$id", key.removePrefix("."), key, example, provider) {

    override fun getDescription(): String = description

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean {
        val file = context.containingFile as? CSharpFile ?: return false
        val site = CSharpPostfixTemplates.site(file, copyDocument, newOffset) ?: return false
        val placed = when (place) {
            PostfixPlace.EXPRESSION -> !site.memberLevel
            PostfixPlace.STATEMENT -> site.startsStatement && !site.memberLevel
            PostfixPlace.MEMBER -> site.startsStatement && site.memberLevel
        }
        if (!placed || !fitsText(site.expression)) return false
        when (site.namedType) {
            NamedType.ENUM -> return false
            NamedType.TYPE -> if (key !in CSharpPostfixTemplates.OF_TYPES) return false
            null -> {}
        }
        return site.facts?.let(fits) ?: true
    }

    override fun expand(context: PsiElement, editor: Editor) {
        val project = context.project
        val document = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val text = document.immutableCharSequence
        val range = CSharpExpressions.before(text, editor.caretModel.offset) ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: context.containingFile
        val facts = CSharpPostfixFacts.of(file, range)
        val site = PostfixSite(text.substring(range.startOffset, range.endOffset), CSharpExpressions.indentAt(text, range.startOffset), unitOf(file), facts, text, range)
        val expansion = render(site) ?: return
        val replaced = expansion.replace ?: range
        val main = CSharpPostfixMembers.Edit(replaced.startOffset, replaced.length, if (expansion.parts != null) "" else expansion.text)
        var start = replaced.startOffset
        for (edit in (expansion.edits + main).sortedByDescending { it.offset }) {
            document.replaceString(edit.offset, edit.offset + edit.length, edit.text)
            if (edit !== main && edit.offset <= replaced.startOffset) start += edit.text.length - edit.length
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        if (expansion.parts != null) {
            editor.caretModel.moveToOffset(start)
            CSharpPostfixTemplates.startTemplate(editor, expansion.parts)
        } else {
            editor.caretModel.moveToOffset(start + expansion.caret)
            expansion.select?.let { editor.selectionModel.setSelection(start + it.first, start + it.last + 1) }
        }
        if (awaits) {
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
            if (!CSharpFeatures.native(CSharpFeature.COMPLETION, psi) && RoslynServerStatus.isReady(project, psi.virtualFile)) return
            NativeCSharpCommonCalls.makeAsyncAt(psi, start, editor)
        }
    }

    private fun unitOf(file: PsiFile): String {
        val options = CodeStyle.getSettings(file).getIndentOptions(CSharpFileType)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
    }
}

object CSharpPostfixTemplates {
    /** What every template of one popup asks about the same place: computed once per copy of the document and offset. */
    class Site(val expression: String, val startsStatement: Boolean, val memberLevel: Boolean, factsOf: () -> CSharpPostfixFacts?, namedTypeOf: () -> NamedType? = { null }) {
        val facts: CSharpPostfixFacts? by lazy(factsOf)
        /** A type name before the key (`OrderStatus.`): the members of the type are what is wanted there, not templates of a value. */
        val namedType: NamedType? by lazy(namedTypeOf)
    }

    /** The templates that take a type name: `.new` and `.inject` of a class, `.typeof` of any type (as Rider). */
    val OF_TYPES = setOf(".new", ".inject", ".typeof")

    private class Cached(val document: Document, val stamp: Long, val offset: Int, val site: Site?)

    @Volatile
    private var cached: Cached? = null

    fun site(file: PsiFile, document: Document, offset: Int): Site? {
        cached?.let { if (it.document === document && it.stamp == document.modificationStamp && it.offset == offset) return it.site }
        val text = document.immutableCharSequence
        val site = CSharpExpressions.before(text, offset)?.let { range ->
            Site(
                text.substring(range.startOffset, range.endOffset), CSharpExpressions.startsStatement(text, range.startOffset),
                CSharpPostfixMembers.atMemberLevel(text, range.startOffset),
                { CSharpPostfixFacts.of(file, range) }, { CSharpPostfixFacts.namedType(file, range) },
            )
        }
        cached = Cached(document, document.modificationStamp, offset, site)
        return site
    }

    /** Runs [parts] as a live template at the caret: the stops in their order, then the end. */
    fun startTemplate(editor: Editor, parts: List<Expansion.Part>) {
        val project = editor.project ?: return
        val manager = TemplateManager.getInstance(project)
        val template = manager.createTemplate("", "")
        template.isToReformat = false
        // the text carries its own indent: the template must not add the line's one again
        (template as? TemplateImpl)?.setToIndent(false)
        val declared = HashSet<String>()
        for (part in parts) when (part) {
            is Expansion.Text -> template.addTextSegment(part.text)
            is Expansion.End -> template.addEndVariable()
            is Expansion.Stop -> if (declared.add(part.name)) {
                val expression: Expression = if (part.complete) MacroCallNode(CompleteMacro()) else Choices(part.choices)
                template.addVariable(part.name, expression, ConstantNode(part.choices.firstOrNull().orEmpty()), true)
            } else template.addVariableSegment(part.name)
        }
        manager.startTemplate(editor, template)
    }

    /** A stop that types in the first of [choices] and offers the others in a list. */
    private class Choices(val choices: List<String>) : Expression() {
        override fun calculateResult(context: ExpressionContext): Result = TextResult(choices.firstOrNull().orEmpty())
        override fun calculateLookupItems(context: ExpressionContext): Array<LookupElement>? =
            if (choices.size < 2) null else choices.map { LookupElementBuilder.create(it) }.toTypedArray()
    }

    /** `if (expr)` and an empty block below it, the caret on the empty line inside. */
    fun block(head: String, indent: String, unit: String): Expansion {
        val inside = "$head\n$indent{\n$indent$unit"
        return Expansion("$inside\n$indent}", inside.length)
    }

    /** The same as a template: [head] with its stops, the end inside the block. */
    private fun blockTemplate(indent: String, unit: String, vararg head: Any): Expansion =
        Expansion.template(*head, "\n$indent{\n$indent$unit", Expansion.End, "\n$indent}")

    private fun line(text: String, caret: Int = text.length, select: IntRange? = null) = Expansion(text, caret, select)

    /** The bound `.for` / `.forr` count to: `xs.Count`, `xs.Length`, the number itself, else the expression as typed (its type unknown). */
    private fun bound(site: PostfixSite): String {
        val facts = site.facts ?: return site.expression
        val count = facts.countMember ?: return site.expression
        val receiver = if (CSharpExpressions.before(site.expression, site.expression.length)?.let { it.startOffset == 0 } == true) site.expression else "(${site.expression})"
        return "$receiver.$count"
    }

    private fun fitsFor(facts: CSharpPostfixFacts): Boolean = facts.countMember != null || facts.isInteger != false

    /** `IOrderService`, `Services.IClock`: a name of a type, what `.inject` and `.new` are typed after. */
    private val TYPE_NAME = Regex("""^(?:[A-Za-z_]\w*\.)*[A-Z]\w*$""")
    private val SIMPLE_NAME = Regex("""^@?[A-Za-z_]\w*$""")

    /** The types `.parse` / `.tryparse` offer, `int` first, as Rider's chooser. */
    private val PARSED = listOf("int", "long", "double", "decimal", "float", "bool", "DateTime", "DateTimeOffset", "TimeSpan", "Guid", "byte", "short")

    private fun varNames(site: PostfixSite): List<String> = CSharpExpressionNames.forExpression(site.expression, site.facts?.type, site.taken)

    private fun field(site: PostfixSite, property: Boolean): Expansion? {
        val place = CSharpPostfixMembers.place(site.text, site.range.startOffset) ?: return null
        val member = place.member ?: return null
        val inConstructor = member.kind == DeclarationKind.CONSTRUCTOR
        val static = if ("static" in member.modifiers) "static " else ""
        val type = site.facts?.written ?: "object"
        // a parameter or a local keeps its name (`name.field` → `_name`), anything else is named as `.var` names it
        val base = (if (SIMPLE_NAME.matches(site.expression)) site.expression else CSharpExpressionNames.forExpression(site.expression, site.facts?.type).first())
            .removePrefix("@").trimStart('_')
        val taken = CSharpPostfixMembers.memberNames(place.type)
        return if (property) {
            val name = CSharpVariableNames.unique(base.replaceFirstChar { it.uppercase() }, taken)
            val accessors = if (inConstructor) "{ get; }" else "{ get; set; }"
            val declaration = CSharpPostfixMembers.declareProperty(site.text, place.type, "public $static$type $name $accessors", site.unit)
            Expansion("$name = ${site.expression};", "$name = ${site.expression};".length, edits = listOf(declaration))
        } else {
            val name = CSharpVariableNames.unique("_$base", taken)
            val readonly = if (inConstructor) "readonly " else ""
            val declaration = CSharpPostfixMembers.declareField(site.text, place.type, "private $static$readonly$type $name;", site.unit)
            Expansion("$name = ${site.expression};", "$name = ${site.expression};".length, edits = listOf(declaration))
        }
    }

    private fun inject(site: PostfixSite): Expansion? {
        val place = CSharpPostfixMembers.place(site.text, site.range.startOffset) ?: return null
        val edits = CSharpPostfixMembers.inject(site.text, place.type, site.expression, site.unit) ?: return null
        return Expansion("", 0, edits = edits)
    }

    @Suppress("LongMethod")
    fun all(provider: PostfixTemplateProvider): Set<PostfixTemplate> {
        val e = PostfixPlace.EXPRESSION
        val s = PostfixPlace.STATEMENT
        fun t(id: String, description: String, example: String, place: PostfixPlace, awaits: Boolean = false, fits: (CSharpPostfixFacts) -> Boolean = { true },
              fitsText: (String) -> Boolean = { true }, render: (PostfixSite) -> Expansion?) =
            CSharpPostfixTemplate(id, ".$id", description, example, provider, place, awaits, fits, fitsText, render)
        // Rider's list, in its order, then the plugin's own
        return linkedSetOf(
            t("arg", "Surrounds expression with invocation", "Method(expr)", e) { Expansion.template(Expansion.Stop("METHOD", emptyList(), complete = true), "(${it.expression})", Expansion.End) },
            t("await", "Awaits expressions of 'Task' type", "await expr", e, awaits = true, fits = { it.isAwaitable != false }) { line("await ${it.expression}") },
            t("cast", "Surrounds expression with cast", "((SomeType) expr)", e) { line("((T)${it.expression})", 2, 2 until 3) },
            t("else", "Checks boolean expression to be 'false'", "if (!expr)", s, fits = { it.isBool != false }) { block("if (!${it.expression})", it.indent, it.unit) },
            t("field", "Introduces field for expression", "_field = expr;", s) { field(it, property = false) },
            t("for", "Iterates over collection with index", "for (var i = 0; i < xs.Length; i++)", s, fits = ::fitsFor) {
                block("for (var i = 0; i < ${bound(it)}; i++)", it.indent, it.unit)
            },
            t("foreach", "Iterates over enumerable collection", "foreach (var x in expr)", s, fits = { it.isEnumerable != false }) {
                val names = CSharpExpressionNames.forElement(it.expression, it.facts?.elementType, it.taken)
                blockTemplate(it.indent, it.unit, "foreach (var ", Expansion.Stop("NAME", names), " in ${it.expression})")
            },
            t("forr", "Iterates over collection in reverse with index", "for (var i = xs.Length-1; i >= 0; i--)", s, fits = ::fitsFor) {
                block("for (var i = ${bound(it)} - 1; i >= 0; i--)", it.indent, it.unit)
            },
            t("if", "Checks boolean expression to be 'true'", "if (expr)", s, fits = { it.isBool != false }) { block("if (${it.expression})", it.indent, it.unit) },
            CSharpPostfixTemplate(
                "inject", ".inject", "Introduces primary constructor parameter of type", "class Component(IDependency dependency)", provider, PostfixPlace.MEMBER,
                fitsText = { TYPE_NAME.matches(it) }, render = ::inject,
            ),
            t("lock", "Surrounds expression with lock block", "lock (expr)", s, fits = { it.isReference != false }) { block("lock (${it.expression})", it.indent, it.unit) },
            t("new", "Produces instantiation expression for type", "new SomeType()", e, fitsText = { TYPE_NAME.matches(it) }) { line("new ${it.expression}()", "new ${it.expression}(".length) },
            t("not", "Negates boolean expression", "!expr", e, fits = { it.isBool != false }) { line("!${it.expression}") },
            t("notnull", "Checks expression to be not-null", "if (expr != null)", s, fits = { it.canBeNull != false }) { block("if (${it.expression} != null)", it.indent, it.unit) },
            t("null", "Checks expression to be null", "if (expr == null)", s, fits = { it.canBeNull != false }) { block("if (${it.expression} == null)", it.indent, it.unit) },
            t("par", "Parenthesizes current expression", "(expr)", e) { line("(${it.expression})") },
            t("parse", "Parses string as value of some type", "int.Parse(expr)", e, fits = { it.isString != false }) {
                Expansion.template(Expansion.Stop("TYPE", PARSED), ".Parse(${it.expression})", Expansion.End)
            },
            t("prop", "Introduces property for expression", "Property = expr;", s) { field(it, property = true) },
            t("return", "Returns expression from current function", "return expr;", s) { line("return ${it.expression};") },
            t("sel", "Selects expression in editor", "|selected + expression|", e) { line(it.expression, it.expression.length, it.expression.indices) },
            t("switch", "Produces switch statement", "switch (expr)", s) { block("switch (${it.expression})", it.indent, it.unit) },
            t("throw", "Throws expression of 'Exception' type", "throw expr;", s, fits = { it.isException != false }) { line("throw ${it.expression};") },
            t("to", "Assigns current expression to some variable", "lvalue = expr;", s) { Expansion.template(Expansion.Stop("TARGET", emptyList(), complete = true), " = ${it.expression};", Expansion.End) },
            t("tryparse", "Parses string as value of some type", "int.TryParse(expr, out value)", e, fits = { it.isString != false }) {
                Expansion.template(Expansion.Stop("TYPE", PARSED), ".TryParse(${it.expression}, out var ", Expansion.Stop("NAME", listOf(CSharpVariableNames.unique("value", it.taken))), ")", Expansion.End)
            },
            t("typeof", "Wraps type usage with typeof() expression", "typeof(TExpr)", e) { line("typeof(${it.expression})") },
            t("using", "Wraps resource with using statement", "using (expr)", s, fits = { it.isDisposable != false }) {
                val names = (listOf(CSharpUsingNames.of(it.expression)).filter { n -> n != "value" } + varNames(it)).map { n -> CSharpVariableNames.unique(n, it.taken) }.distinct()
                Expansion.template("using var ", Expansion.Stop("NAME", names), " = ${it.expression};", Expansion.End)
            },
            t("var", "Introduces variable for expression", "var x = expr;", s) { Expansion.template("var ", Expansion.Stop("NAME", varNames(it)), " = ${it.expression};", Expansion.End) },
            t("while", "Iterating while boolean statement is 'true'", "while (expr)", s, fits = { it.isBool != false }) { block("while (${it.expression})", it.indent, it.unit) },
            t("yield", "Yields value from iterator method", "yield return expr;", s) { line("yield return ${it.expression};") },
            // the plugin's own
            t("awaitusing", "Wraps resource with await using statement", "await using var value = expr;", s, awaits = true, fits = { it.isAsyncDisposable != false }) {
                val names = (listOf(CSharpUsingNames.of(it.expression)).filter { n -> n != "value" } + varNames(it)).map { n -> CSharpVariableNames.unique(n, it.taken) }.distinct()
                Expansion.template("await using var ", Expansion.Stop("NAME", names), " = ${it.expression};", Expansion.End)
            },
            t("cw", "Writes expression to the console", "Console.WriteLine(expr);", s) { line("Console.WriteLine(${it.expression});") },
            t("nameof", "Wraps expression with nameof()", "nameof(expr)", e) { line("nameof(${it.expression})") },
            t("str", "Converts expression to string", "expr.ToString()", e) { line("${it.expression}.ToString()") },
        )
    }
}
