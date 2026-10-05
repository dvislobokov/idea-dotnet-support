package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase
import com.intellij.psi.PsiDocumentManager
import java.util.UUID

/**
 * Macros of the C# live templates (COMPLETION_GAPS 3.1), what Rider's templates compute: names of variables by type
 * ([CSharpVariableNames]) and by collection ([CSharpExpressionNames]), a new GUID, the constructor of `ctorf` / `ctorp`. Named with
 * `csharp`: the Java plugin of IDEA has its own `suggestVariableName()`.
 */
private fun argument(params: Array<Expression>, context: ExpressionContext): String? =
    params.firstOrNull()?.calculateResult(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

private fun lookup(names: List<String>): Array<LookupElement>? = if (names.size < 2) null else names.map { LookupElementBuilder.create(it) }.toTypedArray()

/** `csharpSuggestVariableName(TYPE)`: `builder` (then `stringBuilder`) for `StringBuilder`, `orders` for `List<Order>`; nothing for `int`. */
class CSharpSuggestVariableNameMacro : MacroBase("csharpSuggestVariableName", "csharpSuggestVariableName(type)") {
    private fun names(params: Array<Expression>, context: ExpressionContext): List<String> =
        argument(params, context)?.let { CSharpVariableNames.forType(it) }.orEmpty()

    // no name of the type (`int`, `string`): the template's default value stays
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? = names(params, context).firstOrNull()?.let(::TextResult)
    override fun calculateLookupItems(params: Array<Expression>, context: ExpressionContext): Array<LookupElement>? = lookup(names(params, context))
}

/** `csharpSuggestElementName(COLLECTION)`: `order` for `orders`, `customer` for `GetCustomers()`, else `item`. */
class CSharpSuggestElementNameMacro : MacroBase("csharpSuggestElementName", "csharpSuggestElementName(collection)") {
    private fun names(params: Array<Expression>, context: ExpressionContext): List<String> = CSharpExpressionNames.forElement(argument(params, context).orEmpty())

    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result = TextResult(names(params, context).first())
    override fun calculateLookupItems(params: Array<Expression>, context: ExpressionContext): Array<LookupElement>? = lookup(names(params, context))
}

/** `csharpNewGuid()`: Rider's `nguid`. */
class CSharpNewGuidMacro : MacroBase("csharpNewGuid", "csharpNewGuid()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result = TextResult(UUID.randomUUID().toString())
}

/**
 * What `ctorf` / `ctorp` initialize: the instance fields (`"fields"`) or auto-properties (`"properties"`) of the type around the template
 * without an initializer, as Rider's "Constructor initializing all fields / properties" takes them.
 */
object CSharpConstructorMembers {
    class Member(val name: String, val type: String) {
        /** `_name` / `Name` → `name`. */
        val parameter: String get() = CSharpVariableNames.unique(name.trimStart('_').replaceFirstChar { it.lowercase() }.let { if (it in NativeCSharpCompletionPlace.RESERVED) "@$it" else it }, emptySet())
        val assignment: String get() = if (parameter == name) "this.$name = $parameter;" else "$name = $parameter;"
    }

    fun of(text: CharSequence, offset: Int, kind: String): List<Member> {
        val type = CSharpSyntaxModel.current.declarations(text).pathTo(offset).lastOrNull { it.kind.isType } ?: return emptyList()
        val wanted = if (kind == "properties") DeclarationKind.PROPERTY else DeclarationKind.FIELD
        return type.children.filter { member ->
            val code = text.subSequence(member.range.startOffset, member.range.endOffset).toString()
            member.kind == wanted && member.type != null && "static" !in member.modifiers && "const" !in member.modifiers && when (wanted) {
                DeclarationKind.FIELD -> !code.contains('=')
                // an auto-property, not one with a body or an initializer
                else -> code.contains("get;") && !code.substringAfterLast('}').contains('=')
            }
        }.map { Member(it.name, it.type!!) }
    }

    fun parameters(members: List<Member>): String = members.joinToString(", ") { "${it.type} ${it.parameter}" }

    /** The assignments, one a line; the lines after the first indented by [indent]. */
    fun body(members: List<Member>, indent: String): String = members.joinToString("\n$indent") { it.assignment }
}

abstract class CSharpConstructorMacro(name: String) : MacroBase(name, "$name(\"fields\")") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val editor = context.editor ?: return null
        val text = editor.document.immutableCharSequence
        val members = CSharpConstructorMembers.of(text, context.templateStartOffset, argument(params, context) ?: "fields")
        return TextResult(compute(members, text, context))
    }

    abstract fun compute(members: List<CSharpConstructorMembers.Member>, text: CharSequence, context: ExpressionContext): String
}

/** `csharpConstructorParameters("fields")`: `int count, string name`. */
class CSharpConstructorParametersMacro : CSharpConstructorMacro("csharpConstructorParameters") {
    override fun compute(members: List<CSharpConstructorMembers.Member>, text: CharSequence, context: ExpressionContext) = CSharpConstructorMembers.parameters(members)
}

/** `csharpConstructorBody("fields")`: `_count = count;` a line each, under the first one. */
class CSharpConstructorBodyMacro : CSharpConstructorMacro("csharpConstructorBody") {
    override fun compute(members: List<CSharpConstructorMembers.Member>, text: CharSequence, context: ExpressionContext): String {
        val file = context.project.let { project -> context.editor?.document?.let { PsiDocumentManager.getInstance(project).getPsiFile(it) } }
        val unit = file?.let { CodeStyle.getSettings(it).getIndentOptions(CSharpFileType) }?.let { if (it.USE_TAB_CHARACTER) "\t" else " ".repeat(it.INDENT_SIZE) } ?: "    "
        // the template indents every line of its text by the line it starts on: the lines get the step inside the body only
        return CSharpConstructorMembers.body(members, unit)
    }
}
