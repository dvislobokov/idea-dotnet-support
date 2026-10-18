package io.github.dotnetsupport.frameworks.razor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil

/**
 * Refactor → Extract Component (RAZOR_PLAN.md, R9), as in Rider: the markup selected in a `.razor` file (whole lines) becomes the markup of
 * a new component beside it — `<Name>.razor`, its lines without their common indent — and the selection a tag of it, `<Name />`, at the
 * indent of the first line. The C# the markup reads (fields of `@code`, locals of an `@foreach`) is not turned into parameters: the new
 * component shows it red until it gets them.
 */
object RazorExtractComponent {
    /** The lines [range] of [document] covers, from the start of its first line to the end of its last (without the line break). */
    fun lines(document: Document, range: TextRange): TextRange {
        val first = document.getLineNumber(range.startOffset)
        var last = document.getLineNumber(range.endOffset)
        // a selection that ends at the start of a line does not take that line
        if (last > first && range.endOffset == document.getLineStartOffset(last)) last--
        return TextRange(document.getLineStartOffset(first), document.getLineEndOffset(last))
    }

    /** The markup of the new component: [text] (whole lines) without the indent its lines share, ending with a line break. */
    fun markup(text: String): String {
        val lines = text.lines()
        val common = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return lines.joinToString("\n") { if (it.isBlank()) "" else it.substring(common) }.trimEnd() + "\n"
    }

    /**
     * Whether [text] is whole markup on its own: the compiler's parser finds no syntax error in it (a selection starting in a comment, ending
     * inside `@code { }` or in the middle of a tag would leave both files broken — the WSL sandbox, 0.1.283).
     */
    fun isWholeMarkup(project: com.intellij.openapi.project.Project, text: String): Boolean {
        val file = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText("Extracted.razor", RazorFileType, text) ?: return false
        val razor = file.viewProvider.getPsi(RazorLanguage) as? RazorFile ?: return false
        return razor.syntaxTree?.diagnostics.orEmpty().none { RazorErrors.isSyntax(it) }
    }

    /** A name of a component: a C# identifier starting with a capital (Blazor's rule for a component's tag). */
    fun isComponentName(name: String): Boolean = name.isNotEmpty() && name[0].isUpperCase() && name.all { it.isLetterOrDigit() || it == '_' }
}

class RazorExtractComponentAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val available = file?.extension.equals("razor", ignoreCase = true) && editor != null && editor.selectionModel.hasSelection()
        e.presentation.isVisible = file?.extension.equals("razor", ignoreCase = true) || !e.isFromContextMenu
        e.presentation.isEnabled = available
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val folder = file.parent ?: return
        val document = editor.document
        val selection = TextRange(editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd)
        val lines = RazorExtractComponent.lines(document, selection)
        val name = Messages.showInputDialog(project, "Name of the new component:", "Extract Component", null, "NewComponent", null)?.trim() ?: return
        when {
            !RazorExtractComponent.isComponentName(name) -> return Messages.showErrorDialog(project, "'$name' is no component name: a C# identifier starting with a capital letter", "Extract Component")
            folder.findChild("$name.razor") != null -> return Messages.showErrorDialog(project, "$name.razor already exists in ${folder.name}", "Extract Component")
        }
        val text = document.getText(lines)
        if (!RazorExtractComponent.isWholeMarkup(project, text)) {
            return Messages.showErrorDialog(project, "The selection is not whole markup: it starts or ends inside a comment, a code block or a tag", "Extract Component")
        }
        val indent = text.takeWhile { it == ' ' || it == '\t' }
        val directory = com.intellij.psi.PsiManager.getInstance(project).findDirectory(folder) ?: return
        val source = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        // one command over both files, global: Ctrl+Z takes back the new file and the tag together
        WriteCommandAction.writeCommandAction(project, source).withName("Extract Component $name").withGlobalUndo().run<RuntimeException> {
            val created = directory.createFile("$name.razor")
            val createdDocument = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(created)
            if (createdDocument != null) createdDocument.setText(RazorExtractComponent.markup(text)) else VfsUtil.saveText(created.virtualFile, RazorExtractComponent.markup(text))
            document.replaceString(lines.startOffset, lines.endOffset, "$indent<$name />")
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(lines.startOffset + indent.length)
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        FileDocumentManager.getInstance().saveAllDocuments()
    }
}
