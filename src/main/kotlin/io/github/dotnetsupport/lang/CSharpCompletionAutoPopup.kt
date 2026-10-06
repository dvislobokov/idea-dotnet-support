package io.github.dotnetsupport.lang

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lsp.RoslynOptions

/**
 * The list opens by itself with nothing typed where Rider opens it (COMPLETION_GAPS 2.10; Rider dumps 1, 10, 15, 23, 33, 36): after `#` at
 * the start of a line (the directives), after `<` of type arguments (`new List<`), after `(` / `,` where an argument may be a lambda (a
 * parameter of a delegate type: the list is in suggestion mode there, [CSharpSuggestionMode]), after `[` that begins an attribute list,
 * and after a space that follows `new`, `case`, `==`, `!=`, `is`, `as`, `override`, `using`, `throw new`. With the language server off
 * nothing else opens the list after these characters (the platform does so after `.` and letters only).
 *
 * Which items the opened list shows is decided where they are made: [NativeCSharpCompletion.opensByItself] (with [opensAt] for the places
 * of this class) and the contributors of the other items.
 */
class CSharpCompletionAutoPopup : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (schedules(c, project, editor, file)) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }

    companion object {
        /** Whether [c] just typed opens the list: a C# file, one caret, a place of [triggers], and for `(` / `,` an argument where a lambda goes, when the option says so. */
        fun schedules(c: Char, project: Project, editor: Editor, file: PsiFile): Boolean {
            if (file !is CSharpFile || c !in TRIGGERS || editor.caretModel.caretCount != 1) return false
            if (LookupManager.getActiveLookup(editor) != null) return false
            val text = editor.document.immutableCharSequence
            val offset = editor.caretModel.offset
            if (!triggers(text, offset)) return false
            return !(c == '(' || c == ',') || (inArgumentLists && delegateArgument(project, editor, file, offset))
        }

        /** `completion.dotnet_trigger_completion_in_argument_lists` of the server's page: off, `(` and `,` open nothing, as with the server. */
        val inArgumentLists: Boolean get() = RoslynOptions.isOn("completion.dotnet_trigger_completion_in_argument_lists")

        /** After a method chosen with `(` ([CSharpCommitCharFilter]): when the caret ends in an argument where a lambda may go, the list opens. */
        fun afterCommit(editor: Editor) {
            val project = editor.project ?: return
            if (!inArgumentLists) return
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || editor.isDisposed || LookupManager.getActiveLookup(editor) != null) return@invokeLater
                val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@invokeLater
                val offset = editor.caretModel.offset
                if (editor.document.charsSequence.getOrNull(offset - 1) != '(') return@invokeLater
                if (delegateArgument(project, editor, file, offset)) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }, ModalityState.defaultModalityState())
        }

        private fun delegateArgument(project: Project, editor: Editor, file: PsiFile, offset: Int): Boolean {
            if (DumbService.isDumb(project)) return false
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            val csharp = file as? CSharpFile ?: return false
            val list = NativeCSharpParameterInfo.listAt(csharp, offset) ?: return false
            return CSharpSuggestionMode.delegateAt(csharp, list, NativeCSharpParameterInfo.argumentIndex(list, offset))
        }

        private val TRIGGERS = setOf('#', '<', '(', ',', '[', ' ')
        private val SPACE_AFTER = setOf("new", "case", "is", "as", "override", "using")
        private val SPACE_AFTER_OPERATORS = listOf("==", "!=")
        private val HEADERS = setOf("if", "while", "for", "foreach", "switch", "using", "lock", "catch", "fixed", "nameof", "typeof", "sizeof", "default", "checked", "unchecked")

        /** The character just typed before [offset] of [text] opens the list (by the text; `(` / `,` need a delegate parameter too). */
        fun triggers(text: CharSequence, offset: Int): Boolean {
            val typed = text.getOrNull(offset - 1) ?: return false
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            val line = text.subSequence(lineStart, offset - 1)
            if (inStringOrComment(line)) return false
            return when (typed) {
                '#' -> line.isBlank()
                '<' -> CSharpAngleBrackets.opensGeneric(text, offset - 1)
                '[' -> line.isBlank() || line.trimEnd().endsWith("]")
                '(' -> line.lastOrNull()?.let { it.isLetterOrDigit() || it == '_' || it == '>' } == true && lastWord(line) !in HEADERS
                ',' -> '(' in line
                ' ' -> {
                    val before = line.trimEnd()
                    if (before.length != line.length) return false
                    SPACE_AFTER_OPERATORS.any { before.endsWith(it) } || lastWord(before) in SPACE_AFTER
                }
                else -> false
            }
        }

        private fun lastWord(text: CharSequence): String {
            var start = text.length
            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
            if (start > 0 && (text[start - 1] == '.' || text[start - 1] == '@')) return ""
            return text.subSequence(start, text.length).toString()
        }

        /** A rough look at the line before the caret: an open string or a `//` comment. */
        private fun inStringOrComment(line: CharSequence): Boolean {
            var quotes = 0
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\\') i++ else if (c == '"') quotes++
                else if (c == '/' && line.getOrNull(i + 1) == '/' && quotes % 2 == 0) return true
                i++
            }
            return quotes % 2 == 1
        }

        /**
         * The native list at the places this class opens it with nothing typed: types after `<` of type arguments, the names where an
         * argument may be a lambda (after `(` / `,`), the values after `== ` / `!= ` / `case `. Asked by [NativeCSharpCompletion.opensByItself].
         */
        fun opensAt(parameters: CompletionParameters, kind: NativeCompletionKind): Boolean {
            val text = parameters.editor.document.charsSequence
            val offset = parameters.offset
            var before = offset
            while (before > 0 && text[before - 1].isWhitespace() && text[before - 1] != '\n') before--
            val char = text.getOrNull(before - 1) ?: return false
            return when {
                char == '<' -> kind == NativeCompletionKind.TYPE
                char == '(' || char == ',' -> (kind == NativeCompletionKind.EXPRESSION) && CSharpSuggestionMode.isLambdaParameterPlace(parameters.position)
                before < offset && (text.subSequence(0, before).endsWith("==") || text.subSequence(0, before).endsWith("!=")) -> kind == NativeCompletionKind.EXPRESSION
                before < offset && lastWord(text.subSequence(0, before)) in setOf("case", "is") -> kind == NativeCompletionKind.EXPRESSION
                else -> false
            }
        }
    }
}
