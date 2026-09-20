package io.github.dotnetsupport.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import java.io.File
import java.util.UUID

/** `dotnet sln migrate`: the XML solution format. The CLI converts one way only and keeps the old file. */
class ConvertSolutionToSlnxAction : SolutionAction() {
    override fun isAvailable(context: SolutionContext): Boolean =
        context.project == null && context.folderId == null && isConvertible(context.solutionFile)

    override fun perform(project: Project, context: SolutionContext) {
        val solutionFile = context.solutionFile
        val directory = solutionFile.parent
        val title = "Converting ${solutionFile.name} to .slnx"
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            listOf(DotNetCli.commandLine(directory.path, "sln", solutionFile.path, "migrate"))
        } ?: return

        DotNetCli.runInBackground(project, title, commands, refresh = listOf(File(directory.path))) {
            // Two solution files side by side are two solutions in the tree and an ambiguity for `dotnet build`.
            if (directory.findChild(slnxName(solutionFile)) == null || !solutionFile.isValid) return@runInBackground
            val answer = Messages.showYesNoDialog(
                project,
                "${slnxName(solutionFile)} is created. Delete the old ${solutionFile.name}?",
                "Convert to .slnx", "Delete", "Keep Both", Messages.getQuestionIcon(),
            )
            if (answer == Messages.YES) {
                WriteCommandAction.runWriteCommandAction(project, "Delete ${solutionFile.name}", null, { solutionFile.delete(this) })
            }
        }
    }

    companion object {
        fun isConvertible(solutionFile: VirtualFile): Boolean =
            solutionFile.extension.equals("sln", ignoreCase = true) && solutionFile.parent?.findChild(slnxName(solutionFile)) == null

        fun slnxName(solutionFile: VirtualFile): String = solutionFile.nameWithoutExtension + ".slnx"
    }
}

/** Inserts a new GUID at every caret, replacing selections; each caret gets its own value. */
class InsertGuidAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.document.isWritable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Insert GUID", null, {
            // from the end of the document, so that earlier offsets stay valid
            for (caret in editor.caretModel.allCarets.sortedByDescending { it.selectionStart }) {
                val end = insert(editor.document, caret.selectionStart, caret.selectionEnd, newGuid(editor.document, caret.selectionStart))
                caret.removeSelection()
                caret.moveToOffset(end)
            }
        })
    }

    companion object {
        /** Upper case inside solution files, where Visual Studio writes them that way; lower case elsewhere, as `Guid.ToString()` does. */
        fun newGuid(document: Document, offset: Int): String {
            val guid = UUID.randomUUID().toString()
            val before = document.immutableCharSequence.subSequence(0, offset).takeLast(200)
            val upperCase = Regex("""\{[0-9A-F]{8}-[0-9A-F]{4}-""").containsMatchIn(before)
            return if (upperCase) guid.uppercase() else guid
        }

        fun insert(document: Document, start: Int, end: Int, text: String): Int {
            document.replaceString(start, end, text)
            return start + text.length
        }
    }
}
