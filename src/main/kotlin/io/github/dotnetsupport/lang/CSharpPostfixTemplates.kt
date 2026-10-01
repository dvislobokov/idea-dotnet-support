package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Postfix templates of C# (`person.if`, `list.foreach`, `Load().await`, `x.return`...), the daily ones of Rider, by tokens: the expression
 * before the key is what [CSharpExpressions] finds, nothing is asked about its type. Statement templates (`if`, `return`, `var`...) apply
 * where a statement may start; expression templates (`not`, `par`, `await`...) anywhere. Braces go on their own lines, as the C# code
 * style of the SDK has it.
 */
class CSharpPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = CSharpPostfixTemplates.all(this)

    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'
    override fun preExpand(file: PsiFile, editor: Editor) = Unit
    override fun afterExpand(file: PsiFile, editor: Editor) = Unit
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}

/** What a template turns `expr` into: the text, where the caret goes in it, and what is selected (both as offsets inside [text]). */
class Expansion(val text: String, val caret: Int, val select: IntRange? = null)

/**
 * [statement] templates replace the whole expression at the start of a statement; the others replace the expression in place.
 * [render] gets the expression, the indentation of its line and one indentation unit.
 */
class CSharpPostfixTemplate(
    id: String, key: String, example: String, provider: PostfixTemplateProvider,
    private val statement: Boolean,
    private val render: (expression: String, indent: String, unit: String) -> Expansion,
) : PostfixTemplate("csharp.$id", key.removePrefix("."), key, example, provider) {

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean {
        if (context.containingFile !is CSharpFile) return false
        val text = copyDocument.charsSequence
        val range = CSharpExpressions.before(text, newOffset) ?: return false
        return !statement || CSharpExpressions.startsStatement(text, range.startOffset)
    }

    override fun expand(context: PsiElement, editor: Editor) {
        val document = editor.document
        val text = document.charsSequence
        val range = CSharpExpressions.before(text, editor.caretModel.offset) ?: return
        val indent = CSharpExpressions.indentAt(text, range.startOffset)
        val expansion = render(text.substring(range.startOffset, range.endOffset), indent, unitOf(context.containingFile))
        document.replaceString(range.startOffset, range.endOffset, expansion.text)
        PsiDocumentManager.getInstance(context.project).commitDocument(document)
        editor.caretModel.moveToOffset(range.startOffset + expansion.caret)
        expansion.select?.let { editor.selectionModel.setSelection(range.startOffset + it.first, range.startOffset + it.last + 1) }
    }

    private fun unitOf(file: PsiFile): String {
        val options = CodeStyle.getSettings(file).getIndentOptions(CSharpFileType)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
    }
}

object CSharpPostfixTemplates {
    /** `if (expr)` and an empty block below it, the caret on the empty line inside. */
    fun block(head: String, indent: String, unit: String): Expansion {
        val inside = "$head\n$indent{\n$indent$unit"
        return Expansion("$inside\n$indent}", inside.length)
    }

    private fun line(text: String, caret: Int = text.length, select: IntRange? = null) = Expansion(text, caret, select)

    fun all(provider: PostfixTemplateProvider): Set<PostfixTemplate> = linkedSetOf(
        CSharpPostfixTemplate("if", ".if", "if (expr)", provider, true) { e, i, u -> block("if ($e)", i, u) },
        CSharpPostfixTemplate("else", ".else", "if (!expr)", provider, true) { e, i, u -> block("if (!$e)", i, u) },
        CSharpPostfixTemplate("null", ".null", "if (expr == null)", provider, true) { e, i, u -> block("if ($e == null)", i, u) },
        CSharpPostfixTemplate("notnull", ".notnull", "if (expr != null)", provider, true) { e, i, u -> block("if ($e != null)", i, u) },
        CSharpPostfixTemplate("while", ".while", "while (expr)", provider, true) { e, i, u -> block("while ($e)", i, u) },
        CSharpPostfixTemplate("lock", ".lock", "lock (expr)", provider, true) { e, i, u -> block("lock ($e)", i, u) },
        CSharpPostfixTemplate("switch", ".switch", "switch (expr)", provider, true) { e, i, u -> block("switch ($e)", i, u) },
        CSharpPostfixTemplate("foreach", ".foreach", "foreach (var item in expr)", provider, true) { e, i, u -> block("foreach (var item in $e)", i, u) },
        CSharpPostfixTemplate("for", ".for", "for (var i = 0; i < expr; i++)", provider, true) { e, i, u -> block("for (var i = 0; i < $e; i++)", i, u) },
        CSharpPostfixTemplate("forr", ".forr", "for (var i = expr - 1; i >= 0; i--)", provider, true) { e, i, u -> block("for (var i = $e - 1; i >= 0; i--)", i, u) },
        CSharpPostfixTemplate("using", ".using", "using var value = expr;", provider, true) { e, _, _ -> line("using var value = $e;", "using var ".length, "using var ".length until "using var value".length) },
        CSharpPostfixTemplate("var", ".var", "var value = expr;", provider, true) { e, _, _ -> line("var value = $e;", "var ".length, "var ".length until "var value".length) },
        CSharpPostfixTemplate("return", ".return", "return expr;", provider, true) { e, _, _ -> line("return $e;") },
        CSharpPostfixTemplate("throw", ".throw", "throw expr;", provider, true) { e, _, _ -> line("throw $e;") },
        CSharpPostfixTemplate("yield", ".yield", "yield return expr;", provider, true) { e, _, _ -> line("yield return $e;") },
        CSharpPostfixTemplate("cw", ".cw", "Console.WriteLine(expr);", provider, true) { e, _, _ -> line("Console.WriteLine($e);") },
        CSharpPostfixTemplate("not", ".not", "!expr", provider, false) { e, _, _ -> line("!$e") },
        CSharpPostfixTemplate("par", ".par", "(expr)", provider, false) { e, _, _ -> line("($e)") },
        CSharpPostfixTemplate("cast", ".cast", "((T)expr)", provider, false) { e, _, _ -> line("((T)$e)", 2, 2 until 3) },
        CSharpPostfixTemplate("await", ".await", "await expr", provider, false) { e, _, _ -> line("await $e") },
        CSharpPostfixTemplate("nameof", ".nameof", "nameof(expr)", provider, false) { e, _, _ -> line("nameof($e)") },
        CSharpPostfixTemplate("typeof", ".typeof", "typeof(expr)", provider, false) { e, _, _ -> line("typeof($e)") },
        CSharpPostfixTemplate("new", ".new", "new expr()", provider, false) { e, _, _ -> line("new $e()", "new $e(".length) },
        CSharpPostfixTemplate("str", ".str", "expr.ToString()", provider, false) { e, _, _ -> line("$e.ToString()") },
    )
}
