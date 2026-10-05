package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * Where a string literal stands as an argument (0.1.93, tasks 3.7–3.9 of docs/COMPLETION_GAPS.md): of a call, of an attribute, of an
 * indexer. By the syntax only: the places of message templates, route templates, JSON and configuration keys are told by names, as Rider
 * tells most of them without the semantics.
 */
object CSharpStringArguments {
    /** [literal] is the [index]-th argument (named [named]) of [invocation], a call of [method] on [receiver] (null for a simple name). */
    class Call(
        val literal: CSharpLiteralExpression, val invocation: CSharpInvocationExpression, val method: String, val receiver: CSharpExpression?,
        val arguments: List<CSharpArgument>, val index: Int, val named: String?,
    )

    /** [literal] is the [index]-th argument (named by `x:` or `X =` as [named]) of [attribute], whose name has no `Attribute` suffix. */
    class Attribute(val literal: CSharpLiteralExpression, val attribute: CSharpAttribute, val name: String, val arguments: List<CSharpAttributeArgument>, val index: Int, val named: String?)

    /** The literal expression of a string literal token (regular, verbatim, raw); null for anything else. */
    fun literalOf(leaf: PsiElement?): CSharpLiteralExpression? {
        if (leaf !is CSharpStringLiteralLeaf) return null
        return leaf.parent as? CSharpLiteralExpression
    }

    fun callOf(literal: CSharpLiteralExpression): Call? {
        val argument = literal.parent as? CSharpArgument ?: return null
        val list = argument.parent as? CSharpArgumentList ?: return null
        val invocation = list.parent as? CSharpInvocationExpression ?: return null
        val (receiver, method) = when (val callee = invocation.expression) {
            is CSharpMemberAccessExpression -> callee.expression to callee.nameElement?.identifier?.text
            is CSharpSimpleName -> null to callee.identifier?.text
            is CSharpMemberBindingExpression -> null to callee.nameElement?.identifier?.text
            else -> return null
        }
        method ?: return null
        val arguments = list.arguments
        return Call(literal, invocation, method, receiver, arguments, arguments.indexOf(argument), argument.nameColon?.nameElement?.text)
    }

    fun attributeOf(literal: CSharpLiteralExpression): Attribute? {
        val argument = literal.parent as? CSharpAttributeArgument ?: return null
        val list = argument.parent as? CSharpAttributeArgumentList ?: return null
        val attribute = list.parent as? CSharpAttribute ?: return null
        val name = attribute.nameElement?.text?.substringAfterLast('.')?.trim()?.removeSuffix("Attribute") ?: return null
        val named = argument.nameEquals?.nameElement?.text ?: argument.nameColon?.nameElement?.text
        return Attribute(literal, attribute, name, list.arguments, list.arguments.indexOf(argument), named)
    }

    /** `x["…"]`: the indexed expression. */
    fun indexedBy(literal: CSharpLiteralExpression): CSharpElementAccessExpression? {
        val argument = literal.parent as? CSharpArgument ?: return null
        val list = argument.parent as? CSharpBracketedArgumentList ?: return null
        return list.parent as? CSharpElementAccessExpression
    }

    /** The last identifier of a receiver as written: `logger` of `this.logger`, `Configuration` of `builder.Configuration`. */
    fun lastName(expression: CSharpExpression?): String? = when (expression) {
        is CSharpSimpleName -> expression.identifier?.text
        is CSharpMemberAccessExpression -> expression.nameElement?.identifier?.text
        is CSharpParenthesizedExpression -> lastName(expression.expression)
        is CSharpPostfixUnaryExpression -> lastName(expression.operand)
        else -> null
    }?.removePrefix("@")

    /** The method or local function an attribute of [attribute] stands on. */
    fun annotatedMethod(attribute: CSharpAttribute): PsiElement? = (attribute.parent as? CSharpAttributeList)?.parent?.takeIf { it is CSharpMethodDeclaration || it is CSharpLocalFunctionStatement }

    /** The parameters of a method, local function or lambda. */
    fun parametersOf(function: PsiElement?): List<CSharpParameter> = when (function) {
        is CSharpMethodDeclaration -> function.parameterList?.parameters.orEmpty()
        is CSharpLocalFunctionStatement -> function.parameterList?.parameters.orEmpty()
        is CSharpParenthesizedLambdaExpression -> function.parameterList?.parameters.orEmpty()
        is CSharpSimpleLambdaExpression -> listOfNotNull(function.parameter)
        is CSharpAnonymousMethodExpression -> function.parameterList?.parameters.orEmpty()
        else -> emptyList()
    }

    /** The text of the literal's content from its start to [offset] of [text] (the document), or null when [offset] is not in the content. */
    fun typedBefore(leaf: PsiElement, offset: Int, text: CharSequence): String? {
        val shape = CSharpStringLiterals.shape(leaf.text) ?: return null
        val start = leaf.textRange.startOffset + shape.contentStart
        if (offset < start || offset > text.length) return null
        return text.subSequence(start, offset).toString()
    }
}

/**
 * The list opens by itself in the strings of 0.1.93 as in Rider: after `{` of a message template or a route template, after `:` of a
 * route parameter (constraints), after the quote of a configuration key, and after `, ` of `AddScoped<IService, `. The places are asked
 * of the tree after a commit, so nothing opens in other strings.
 */
class CSharpAspNetStringsAutoPopup : TypedHandlerDelegate() {
    override fun charTyped(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is CSharpFile || charTyped !in TRIGGERS || editor.caretModel.caretCount != 1) return Result.CONTINUE
        if (LookupManager.getActiveLookup(editor) != null || DumbService.isDumb(project)) return Result.CONTINUE
        val text = editor.document.immutableCharSequence
        val offset = editor.caretModel.offset
        if (charTyped == ' ' || charTyped == ',') {
            if (CSharpServiceRegistrations.placeAt(text, offset) != null) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.CONTINUE
        }
        // cheap by the text first, a commit after every `:` of the code would cost: `{` / `:` inside a string, `"` right after `[` or `(`
        val line = text.subSequence(text.lastIndexOf('\n', offset - 1) + 1, offset - 1)
        val candidate = if (charTyped == '"') line.trimEnd().lastOrNull().let { it == '[' || it == '(' } else line.indices.count { line[it] == '"' && (it == 0 || line[it - 1] != '\\') } % 2 == 1
        if (!candidate) return Result.CONTINUE
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        // the character is in the document: the literal is the token around the caret
        val literal = CSharpStringArguments.literalOf(file.findElementAt(offset - 1)) ?: CSharpStringArguments.literalOf(file.findElementAt(offset)) ?: return Result.CONTINUE
        val opens = when (charTyped) {
            '{' -> CSharpLoggerTemplates.templateOf(literal) != null || CSharpRouteTemplates.isRoute(literal)
            ':' -> CSharpRouteTemplates.isRoute(literal)
            '"' -> CSharpConfigurationKeys.placeOf(literal, semantic = false) != null
            else -> false
        }
        if (opens) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }

    private companion object {
        val TRIGGERS = setOf('{', ':', '"', ' ', ',')
    }
}
