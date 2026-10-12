package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider

/** `csharp_indent_*` of the `.editorconfig` files above a source file; the nearest file wins, `root = true` ends the search. */
object CSharpIndentOptions {
    private val OPTION = Regex("""^\s*(csharp_indent_\w+)\s*=\s*([\w-]+)""", RegexOption.MULTILINE)
    private val ROOT = Regex("""^\s*root\s*=\s*true""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    /** Sections are not told apart: options of C# indentation are only ever written for C# files. */
    fun parse(editorConfig: String): Map<String, String> = OPTION.findAll(editorConfig).associate { it.groupValues[1].lowercase() to it.groupValues[2].lowercase() }

    fun of(file: VirtualFile?): Map<String, String> {
        val result = HashMap<String, String>()
        var directory = file?.parent
        while (directory != null) {
            val text = directory.findChild(".editorconfig")?.let { runCatching { String(it.contentsToByteArray(), it.charset) }.getOrNull() }
            if (text != null) {
                parse(text).forEach { (name, value) -> result.putIfAbsent(name, value) }
                if (ROOT.containsMatchIn(text)) break
            }
            directory = directory.parent
        }
        return result
    }
}

object CSharpIndenter {
    /** The indent for the line of [offset], as text; null when the rules leave the line alone. */
    fun indentFor(project: Project, document: Document, offset: Int): String? {
        val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document)
        val options = file?.let { CodeStyle.getIndentOptions(it) } ?: CodeStyle.getSettings(project).getIndentOptions(CSharpFileType)
        val sizes = IndentSizes(options.INDENT_SIZE, options.CONTINUATION_INDENT_SIZE, options.TAB_SIZE, options.USE_TAB_CHARACTER)
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset.coerceIn(0, document.textLength)))
        val editorConfig = CSharpIndentOptions.of(FileDocumentManager.getInstance().getFile(document))
        val columns = CSharpIndentRules.DEFAULT.indentOf(document.immutableCharSequence, lineStart, sizes, editorConfig)?.first ?: return null
        return sizes.text(columns)
    }
}

/** Enter, and everything else of the platform that asks "what is the indent of this line" of a language without a formatter model. */
class CSharpLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language == CSharpLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? =
        // the rules having nothing to say means "as it is", not "ask somebody else": nobody else knows C#
        CSharpIndenter.indentFor(project, editor.document, offset) ?: LineIndentProvider.DO_NOT_ADJUST
}

/**
 * Enter right after `{`. The platform adds a closing brace when the braces of the file do not balance from there on, and it
 * counts them: one unclosed `{` anywhere below (code that is being typed) makes it close the brace at the caret a second
 * time. The layout says more than the count: a `}` below, at the indent this brace's own `}` would have, with nothing
 * shallower in between, is its pair. Then the new line is made here and the platform is not asked.
 */
class CSharpEnterAfterBraceHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file !is CSharpFile || editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val text = document.immutableCharSequence
        val offset = caretOffset.get()
        val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
        val before = (offset - 1 downTo 0).firstOrNull { text[it] != ' ' && text[it] != '\t' } ?: return EnterHandlerDelegate.Result.Continue
        // `{|}` on one line is for the platform: it opens the pair into three lines
        if (text[before] != '{' || text.subSequence(offset, lineEnd).isNotBlank()) return EnterHandlerDelegate.Result.Continue
        if (!hasPairByLayout(file.project, text, offset, lineEnd)) return EnterHandlerDelegate.Result.Continue

        document.insertString(offset, "\n")
        val indent = CSharpIndenter.indentFor(file.project, document, offset + 1).orEmpty()
        document.insertString(offset + 1, indent)
        editor.caretModel.moveToOffset(offset + 1 + indent.length)
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE)
        return EnterHandlerDelegate.Result.Stop
    }

    companion object {
        /** True when a line below starts with `}` at the indent the brace before [offset] closes at, and no line before it is that shallow. */
        fun hasPairByLayout(project: Project, text: CharSequence, offset: Int, lineEnd: Int): Boolean {
            val options = CodeStyle.getSettings(project).getIndentOptions(CSharpFileType)
            val sizes = IndentSizes(options.INDENT_SIZE, options.CONTINUATION_INDENT_SIZE, options.TAB_SIZE, options.USE_TAB_CHARACTER)
            return hasPairByLayout(text, offset, lineEnd, sizes)
        }

        fun hasPairByLayout(text: CharSequence, offset: Int, lineEnd: Int, sizes: IndentSizes): Boolean {
            // where the rules would put the closing brace of this one
            val probe = text.subSequence(0, offset).toString() + "\n}"
            val closing = CSharpIndentRules.DEFAULT.indentOf(probe, offset + 1, sizes)?.first ?: return false
            var lineStart = lineEnd + 1
            while (lineStart < text.length) {
                var end = lineStart
                while (end < text.length && text[end] != '\n') end++
                val line = text.subSequence(lineStart, end)
                if (line.isNotBlank()) {
                    val columns = columnsOf(line, sizes.tab)
                    if (columns <= closing) return columns == closing && line.trimStart().startsWith("}")
                }
                lineStart = end + 1
            }
            return false
        }

        private fun columnsOf(line: CharSequence, tabSize: Int): Int {
            var columns = 0
            for (c in line) {
                when (c) {
                    ' ' -> columns++
                    '\t' -> columns += if (tabSize > 0) tabSize - columns % tabSize else 1
                    else -> return columns
                }
            }
            return columns
        }
    }
}

/**
 * A bracket that is typed first on its line goes where it belongs: `}` under its `{`, and `{` under the header after
 * Enter has given the line a continuation indent (the header could have gone on).
 */
class CSharpBracketTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c !in BRACKETS || file !is CSharpFile) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val before = document.immutableCharSequence.subSequence(lineStart, offset).toString()
        if (before.trim() != c.toString()) return Result.CONTINUE
        val indent = CSharpIndenter.indentFor(project, document, offset) ?: return Result.CONTINUE
        val current = before.takeWhile { it == ' ' || it == '\t' }
        if (indent != current) document.replaceString(lineStart, lineStart + current.length, indent)
        return Result.CONTINUE
    }

    private companion object {
        const val BRACKETS = "{})]"
    }
}
