package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Surround With (Ctrl+Alt+T) in C#: statements with `if`, `while`, `foreach`, `try`, `using`, `lock`, braces, `#region`, `#if`; an
 * expression with parentheses or `!( )`. By text: the selection is what is surrounded, whole lines when it spans them; the wrapped lines
 * are indented one unit deeper. The platform asks which elements are selected and hands them to the surrounder; the leaves here are
 * plain tokens, so the range of the selection is what matters.
 */
class CSharpSurroundDescriptor : SurroundDescriptor {
    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        if (file !is CSharpFile || startOffset >= endOffset) return PsiElement.EMPTY_ARRAY
        val first = file.findElementAt(startOffset) ?: return PsiElement.EMPTY_ARRAY
        val last = file.findElementAt(endOffset - 1) ?: return PsiElement.EMPTY_ARRAY
        return arrayOf(first, last)
    }

    override fun getSurrounders(): Array<Surrounder> = SURROUNDERS
    override fun isExclusive(): Boolean = true

    companion object {
        val SURROUNDERS: Array<Surrounder> = arrayOf(
            StatementSurrounder("if ()", 4) { "if ()" },
            StatementSurrounder("if () ... else", 4, tail = { i -> "\n${i}else\n$i{\n$i}" }) { "if ()" },
            StatementSurrounder("while ()", 7) { "while ()" },
            StatementSurrounder("for (int i = 0; i < ; i++)", 21) { "for (int i = 0; i < ; i++)" },
            StatementSurrounder("foreach (var item in )", 21) { "foreach (var item in )" },
            StatementSurrounder("try ... catch", null, tail = { i -> "\n${i}catch (Exception e)\n$i{\n$i}" }) { "try" },
            StatementSurrounder("try ... finally", null, tail = { i -> "\n${i}finally\n$i{\n$i}" }) { "try" },
            StatementSurrounder("try ... catch ... finally", null, tail = { i -> "\n${i}catch (Exception e)\n$i{\n$i}\n${i}finally\n$i{\n$i}" }) { "try" },
            StatementSurrounder("using ()", 7) { "using ()" },
            StatementSurrounder("lock ()", 6) { "lock ()" },
            StatementSurrounder("{ }", null) { null },
            LineSurrounder("#region ... #endregion", "#region ", "#endregion", 8),
            LineSurrounder("#if ... #endif", "#if ", "#endif", 4),
            ExpressionSurrounder("(expr)", "(", ")"),
            ExpressionSurrounder("!(expr)", "!(", ")"),
        )
    }
}

/** The lines of the selection, from the start of the first one to the end of the last one, and their common indentation. */
private class Lines(val range: TextRange, val indent: String, val text: String, val eol: String)

private fun lines(editor: Editor, elements: Array<out PsiElement>): Lines {
    val document = editor.document
    val text = document.charsSequence
    val selection = editor.selectionModel
    val start = if (selection.hasSelection()) selection.selectionStart else elements.first().textRange.startOffset
    val end = if (selection.hasSelection()) selection.selectionEnd else elements.last().textRange.endOffset
    val lineStart = document.getLineStartOffset(document.getLineNumber(start))
    val lastLine = document.getLineNumber((end - 1).coerceAtLeast(lineStart))
    val lineEnd = document.getLineEndOffset(lastLine)
    val eol = if (text.contains("\r\n")) "\r\n" else "\n"
    return Lines(TextRange(lineStart, lineEnd), CSharpExpressions.indentAt(text, lineStart), text.substring(lineStart, lineEnd), eol)
}

private fun unitOf(file: PsiFile): String {
    val options = CodeStyle.getSettings(file).getIndentOptions(CSharpFileType)
    return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
}

private fun isCSharp(elements: Array<out PsiElement>): Boolean = elements.isNotEmpty() && elements.first().containingFile is CSharpFile

/** `head` + the lines in a deeper block + [tail]; the caret goes [caretInHead] characters into the head, or after the whole thing. */
private class StatementSurrounder(
    private val description: String, private val caretInHead: Int?,
    private val tail: (indent: String) -> String = { "" }, private val head: () -> String?,
) : Surrounder {
    override fun getTemplateDescription(): String = description
    override fun isApplicable(elements: Array<out PsiElement>): Boolean = isCSharp(elements)

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val lines = lines(editor, elements)
        val unit = unitOf(elements.first().containingFile)
        val body = lines.text.lines().joinToString(lines.eol) { if (it.isBlank()) it else unit + it }
        val head = head()
        val prefix = if (head == null) "${lines.indent}{" else "${lines.indent}$head${lines.eol}${lines.indent}{"
        val replacement = prefix + lines.eol + body + lines.eol + lines.indent + "}" + tail(lines.indent).replace("\n", lines.eol)
        editor.document.replaceString(lines.range.startOffset, lines.range.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val caret = if (caretInHead != null) lines.range.startOffset + lines.indent.length + caretInHead else lines.range.startOffset + replacement.length
        return TextRange(caret, caret)
    }
}

/** `#region` / `#if`: a line before and a line after, nothing indented; the caret after the opening directive. */
private class LineSurrounder(private val description: String, private val open: String, private val close: String, private val caretInOpen: Int) : Surrounder {
    override fun getTemplateDescription(): String = description
    override fun isApplicable(elements: Array<out PsiElement>): Boolean = isCSharp(elements)

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val lines = lines(editor, elements)
        val replacement = lines.indent + open + lines.eol + lines.text + lines.eol + lines.indent + close
        editor.document.replaceString(lines.range.startOffset, lines.range.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val caret = lines.range.startOffset + lines.indent.length + caretInOpen
        return TextRange(caret, caret)
    }
}

/** Around the selected text on one line: `(expr)`, `!(expr)`. */
private class ExpressionSurrounder(private val description: String, private val open: String, private val close: String) : Surrounder {
    override fun getTemplateDescription(): String = description
    override fun isApplicable(elements: Array<out PsiElement>): Boolean = isCSharp(elements)

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val selection = editor.selectionModel
        val start = if (selection.hasSelection()) selection.selectionStart else elements.first().textRange.startOffset
        val end = if (selection.hasSelection()) selection.selectionEnd else elements.last().textRange.endOffset
        val inner = editor.document.charsSequence.substring(start, end)
        editor.document.replaceString(start, end, open + inner + close)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val caret = start + open.length + inner.length + close.length
        return TextRange(caret, caret)
    }
}
