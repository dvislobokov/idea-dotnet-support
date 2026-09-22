package io.github.dotnetsupport.lang

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * `expr.if`, `value.var`, `items.foreach`, `x.null`, `e.throw`: the postfix templates of Rider, by text. The expression is what
 * stands left of the caret — a chain of names, calls, indexes, generic arguments and literals, found by [CSharpPostfixExpressions].
 * What comes out is a live template, so the names to type (`value` of `.var`, the type of `.cast`) are stops of it. Roslyn does not
 * send postfix completion (it is a ReSharper feature), so these work always and offline and never clash with the server.
 */
class CSharpPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = setOf(
        CSharpPostfixTemplate("if", "if (expr) {}", "if (\$EXPR$) {\n    \$END$\n}", this),
        CSharpPostfixTemplate("else", "if (!expr) {}", "if (!\$EXPR$) {\n    \$END$\n}", this),
        CSharpPostfixTemplate("null", "if (expr == null) {}", "if (\$EXPR$ == null) {\n    \$END$\n}", this),
        CSharpPostfixTemplate("notnull", "if (expr != null) {}", "if (\$EXPR$ != null) {\n    \$END$\n}", this),
        CSharpPostfixTemplate("not", "!expr", "!\$EXPR$\$END$", this),
        CSharpPostfixTemplate("var", "var v = expr;", "var \$NAME$ = \$EXPR$;\$END$", this, "NAME" to "value"),
        CSharpPostfixTemplate("foreach", "foreach (var item in expr) {}", "foreach (var \$NAME$ in \$EXPR$) {\n    \$END$\n}", this, "NAME" to "item"),
        CSharpPostfixTemplate("for", "for (var i = 0; i < expr; i++) {}", "for (var \$I$ = 0; \$I$ < \$EXPR$; \$I$++) {\n    \$END$\n}", this, "I" to "i"),
        CSharpPostfixTemplate("forr", "for (var i = expr - 1; i >= 0; i--) {}", "for (var \$I$ = \$EXPR$ - 1; \$I$ >= 0; \$I$--) {\n    \$END$\n}", this, "I" to "i"),
        CSharpPostfixTemplate("while", "while (expr) {}", "while (\$EXPR$) {\n    \$END$\n}", this),
        CSharpPostfixTemplate("return", "return expr;", "return \$EXPR$;\$END$", this),
        CSharpPostfixTemplate("nameof", "nameof(expr)", "nameof(\$EXPR$)\$END$", this),
        CSharpPostfixTemplate("typeof", "typeof(expr)", "typeof(\$EXPR$)\$END$", this),
        CSharpPostfixTemplate("cast", "((T)expr)", "((\$TYPE$)\$EXPR$)\$END$", this, "TYPE" to "T"),
        CSharpPostfixTemplate("using", "using (expr) {}", "using (\$EXPR$) {\n    \$END$\n}", this),
        CSharpPostfixTemplate("await", "await expr", "await \$EXPR$\$END$", this),
        CSharpPostfixTemplate("switch", "switch (expr) {}", "switch (\$EXPR$) {\n    case \$CASE$:\n        \$END$\n}", this, "CASE" to ""),
        CSharpPostfixTemplate("throw", "throw expr;", "throw \$EXPR$;\$END$", this),
        CSharpPostfixTemplate("par", "(expr)", "(\$EXPR$)\$END$", this),
        CSharpPostfixTemplate("sout", "Console.WriteLine(expr);", "Console.WriteLine(\$EXPR$);\$END$", this),
    )

    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'
    override fun preExpand(file: PsiFile, editor: Editor) {}
    override fun afterExpand(file: PsiFile, editor: Editor) {}
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
    override fun getId(): String = "csharp"
    override fun getPresentableName(): String = "C#"
}

/** One template: [text] is a live template with `$EXPR$` for the expression and stops for what is to be typed, in the order of [stops]. */
class CSharpPostfixTemplate(private val key: String, example: String, private val text: String, provider: PostfixTemplateProvider, private vararg val stops: Pair<String, String>) :
    PostfixTemplate("csharp.$key", key, example, provider) {

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        context.containingFile is CSharpFile && CSharpPostfixExpressions.rangeBefore(copyDocument.immutableCharSequence, newOffset) != null

    override fun expand(context: PsiElement, editor: Editor) {
        val document = editor.document
        val offset = editor.caretModel.offset
        val range = CSharpPostfixExpressions.rangeBefore(document.immutableCharSequence, offset) ?: return
        val expression = document.getText(range)
        document.deleteString(range.startOffset, range.endOffset)
        editor.caretModel.moveToOffset(range.startOffset)
        val template = TemplateManager.getInstance(context.project).createTemplate("csharp.postfix.$key", "csharp", text)
        template.isToReformat = false
        template.addVariable("EXPR", ConstantNode(expression), false)
        for ((name, default) in stops) template.addVariable(name, ConstantNode(default), ConstantNode(default), true)
        TemplateManager.getInstance(context.project).startTemplate(editor, template)
    }
}

/** The expression a postfix key applies to: what stands right before the caret, read backwards. */
object CSharpPostfixExpressions {
    /**
     * `a.b(c)[0]`, `f(x, y)`, `GetValues<Color>()`, `"text"`, `42`, `!ok`, `-n`: from the caret back over names, dots, balanced
     * brackets, generic argument lists and literals, then over a prefix operator. Null when there is nothing (the statement starts
     * here) or the piece is not an expression (a keyword such as `if` or `return`).
     */
    fun rangeBefore(text: CharSequence, offset: Int): TextRange? {
        var end = offset.coerceIn(0, text.length)
        // the key has been deleted; a dot that is left over is not a part of the expression
        if (end > 0 && text[end - 1] == '.') end--
        var i = end
        while (i > 0) {
            val c = text[i - 1]
            when {
                c.isLetterOrDigit() || c == '_' -> i--
                c == '.' && i - 1 > 0 && (text[i - 2].isLetterOrDigit() || text[i - 2] == '_' || text[i - 2] == ')' || text[i - 2] == ']') -> i--
                c == ')' || c == ']' -> i = matching(text, i - 1) ?: return null
                c == '>' -> i = genericStart(text, i - 1) ?: break
                c == '"' || c == '\'' -> i = stringStart(text, i - 1, c) ?: return null
                else -> break
            }
        }
        // `!ok`, `-n`, `~x`
        if (i > 0 && text[i - 1] in "!-~" && (i == 1 || !text[i - 2].isLetterOrDigit())) i--
        if (i >= end) return null
        // `new Foo()`, `new Dictionary<...>()`: the object creation is one expression, `new` is a part of it, not left stranded
        if (i < end) {
            var j = i
            while (j > 0 && (text[j - 1] == ' ' || text[j - 1] == '\t')) j--
            if (j >= 3 && text.subSequence(j - 3, j).toString() == "new" && (j == 3 || !text[j - 4].isLetterOrDigit() && text[j - 4] != '_')) i = j - 3
        }
        val expression = text.substring(i, end)
        if (expression.isBlank() || expression.first().isDigit() && !expression.all { it.isDigit() || it == '.' || it == '_' || it in "xXeEfFdDmMlLuU" }) return null
        val head = expression.takeWhile { it.isLetterOrDigit() || it == '_' }
        // keywords are not expressions to build on, but the value keywords are, and `new Foo()` is an object creation
        if (head in CSharpTokenTypes.KEYWORDS && head !in VALUE_KEYWORDS && !(head == "new" && expression.length > 3)) return null
        return TextRange(i, end)
    }

    /** Value keywords that may stand as the whole expression: `x.this`, `expr.true` do not, but these do. */
    private val VALUE_KEYWORDS = setOf("this", "base", "true", "false", "null", "value")

    /** The index of the bracket that [close] (`)` or `]`) at [closeIndex] matches; strings inside are skipped. */
    private fun matching(text: CharSequence, closeIndex: Int): Int? {
        var depth = 0
        var i = closeIndex
        while (i >= 0) {
            when (text[i]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (--depth == 0) return i
                '"', '\'' -> i = stringStart(text, i, text[i]) ?: return null
            }
            i--
        }
        return null
    }

    /**
     * The start of a generic argument list ending with `>` at [closeIndex], e.g. the `<` of `GetValues<Color>`; null when the `>`
     * is a comparison, not a type argument. Only names, dots, commas, `?`, brackets and nested `<>` are allowed between the angles.
     */
    private fun genericStart(text: CharSequence, closeIndex: Int): Int? {
        var depth = 0
        var i = closeIndex
        while (i >= 0) {
            val c = text[i]
            when {
                c == '>' -> depth++
                c == '<' -> if (--depth == 0) return if (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) i else null
                c.isLetterOrDigit() || c == '_' || c == '.' || c == ',' || c == '?' || c == '[' || c == ']' || c == ' ' -> {}
                else -> return null
            }
            i--
        }
        return null
    }

    /** The opening quote of the string or char that ends with the quote at [closeIndex]; the `@`/`$` prefix is taken in. */
    private fun stringStart(text: CharSequence, closeIndex: Int, quote: Char): Int? {
        var i = closeIndex - 1
        while (i >= 0) {
            if (text[i] == quote && text.getOrNull(i - 1) != '\\') {
                // a verbatim `@"..."` doubles the quote, so `""` inside is not the end; treat a doubled quote as content
                if (text.getOrNull(i - 1) == quote) { i -= 2; continue }
                var start = i
                while (start > 0 && (text[start - 1] == '@' || text[start - 1] == '$')) start--
                return start
            }
            if (text[i] == '\n') return null
            i--
        }
        return null
    }
}
