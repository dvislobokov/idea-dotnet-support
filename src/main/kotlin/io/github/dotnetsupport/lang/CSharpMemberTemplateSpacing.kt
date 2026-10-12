package io.github.dotnetsupport.lang

import com.intellij.codeInsight.template.impl.TemplatePreprocessor
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * A member written by a live template (`ctor`, `prop`, `svm`…) gets an empty line above it when it lands right under another member, as
 * Rider leaves it (0.1.169, Jellyfin № 10: `ctor` + Tab under the fields wrote the constructor glued to the last field). Not under `{`, an
 * attribute or a comment, and not when the line above is empty already.
 */
class CSharpMemberTemplateSpacing : TemplatePreprocessor {
    override fun preprocessTemplate(editor: Editor, file: PsiFile, caretOffset: Int, textToInsert: String, templateText: String) {
        if (file !is CSharpFile) return
        // a whole member (`ctor`, `svm`, `class`, `fact`…): several lines; a one-line `prop` stays with its neighbours
        if ('\n' !in templateText) return
        val document = editor.document
        val text = document.charsSequence
        if (caretOffset > text.length) return
        val lineStart = text.lastIndexOf('\n', caretOffset - 1) + 1
        // the key is gone already: only the indent is left on the line
        if (text.subSequence(lineStart, caretOffset).isNotBlank() || lineStart == 0) return
        val previousEnd = lineStart - 1
        val previousStart = text.lastIndexOf('\n', previousEnd - 1) + 1
        val previous = text.subSequence(previousStart, previousEnd).trim()
        if (previous.isEmpty() || previous.endsWith("{") || previous.startsWith("[") || previous.startsWith("//") || previous.startsWith("#")) return
        if (!(previous.endsWith(";") || previous.endsWith("}"))) return
        if (!atMemberLevel(file, caretOffset)) return
        document.insertString(lineStart, "\n")
    }

    /** The caret is between the members of a type or the types of a file, not in a body. */
    private fun atMemberLevel(file: PsiFile, offset: Int): Boolean {
        val element = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return false
        var current = element.parent
        while (current != null && current !is PsiFile) {
            if (current is io.github.dotnetsupport.csharp.lang.psi.CSharpBlock || current is io.github.dotnetsupport.csharp.lang.psi.CSharpArrowExpressionClause) return false
            current = current.parent
        }
        return true
    }
}
