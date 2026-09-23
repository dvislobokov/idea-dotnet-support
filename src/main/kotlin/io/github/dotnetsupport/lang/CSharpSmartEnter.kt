package io.github.dotnetsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.settings.DotNetSettings

/**
 * Complete Statement (Ctrl+Shift+Enter): the braces of an `if`, `for`, `foreach`, `while`, `using`, `lock`, `switch`, `else`,
 * `do`, `try`, `catch`, `finally` that has none yet, the parentheses of a call typed without them, or the `;` of a statement, and
 * the caret placed to keep typing. By the text of the line, as everything here — no parser.
 */
class CSharpSmartEnterProcessor : SmartEnterProcessor() {
    override fun process(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is CSharpFile) return false
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.getText(TextRange(start, end))
        val (replacement, opensBody) = CSharpStatements.complete(text) ?: return false
        val indent = text.takeWhile { it == ' ' || it == '\t' }
        val options = CodeStyle.getIndentOptions(file)
        val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
        document.replaceString(start, end, replacement)
        if (opensBody) {
            // `{` at the end: the body on the next line, the closing brace after it
            document.insertString(start + replacement.length, "\n$indent$unit\n$indent}")
            editor.caretModel.moveToOffset(start + replacement.length + 1 + indent.length + unit.length)
        } else {
            editor.caretModel.moveToOffset(start + replacement.length)
        }
        return true
    }
}

/**
 * `;` typed with Tab at the end of a statement: `var x = 1` becomes `var x = 1;` and the caret goes past it. Tab is left alone in
 * every other place — inside indentation, while a template is being filled, while a completion popup is open — so nothing that Tab
 * already does breaks. On by default, off in Settings | Tools | .NET.
 */
class CSharpTabFinishStatementHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!finish(editor, dataContext)) original.execute(editor, caret, dataContext)
    }

    private fun finish(editor: Editor, dataContext: DataContext?): Boolean {
        if (!DotNetSettings.getInstance().tabFinishesStatement) return false
        val project = editor.project ?: return false
        val file = dataContext?.let { CommonDataKeys.PSI_FILE.getData(it) } ?: PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        if (file !is CSharpFile) return false
        // Tab still fills templates and picks completions
        if (LookupManager.getActiveLookup(editor) != null || TemplateManagerImpl.getTemplateState(editor) != null) return false

        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        // the caret must sit at the end of the line's content, not in its indentation
        if (document.getText(TextRange(offset, end)).isNotBlank()) return false
        val text = document.getText(TextRange(start, end))
        val (replacement, opensBody) = CSharpStatements.complete(text) ?: return false
        if (opensBody || !replacement.endsWith(";") || replacement == text.trimEnd()) return false
        document.replaceString(start, end, replacement)
        editor.caretModel.moveToOffset(start + replacement.length)
        return true
    }
}

/** What Complete Statement adds to a line; pure, for the tests. */
object CSharpStatements {
    private val OPENERS = Regex("""^\s*(else\s+if|if|for|foreach|while|using|lock|fixed|switch|catch|else|do|try|finally)\b""")
    private val NEED_PARENS = setOf("if", "else if", "for", "foreach", "while", "using", "lock", "fixed", "switch")
    private val WHITESPACE = Regex("""\s+""")
    // a return type and a name before `(`: `void Foo(`, `Task<int> Run(` — a declaration, not a call to terminate
    private val DECLARATION = Regex("""[\w<>\[\].,]+\s+\w+\s*\(""")
    // lines that start with these produce a value, so `Foo()` after them is a call to terminate, not a declaration
    private val STATEMENT_STARTERS = setOf("return", "throw", "await", "yield", "new", "var", "ref", "out")

    /**
     * The line with what it lacks, and whether a body was opened: `if (x` -> `if (x) {` (body), `Foo(x` -> `Foo(x);` (no body), a
     * line that is already complete -> null. A control keyword that still lacks its parentheses is left for the user to finish.
     */
    fun complete(line: String): Pair<String, Boolean>? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || trimmed.endsWith("{") || trimmed.endsWith("}") || trimmed.endsWith(";") || trimmed.endsWith(":") || trimmed.endsWith(",")) return null
        var text = trimmed
        val openParens = text.count { it == '(' } - text.count { it == ')' }
        if (openParens > 0) text += ")".repeat(openParens)
        val openBrackets = text.count { it == '[' } - text.count { it == ']' }
        if (openBrackets > 0) text += "]".repeat(openBrackets)

        val opener = OPENERS.find(text)
        if (opener != null) {
            val keyword = opener.groupValues[1].replace(WHITESPACE, " ")
            if (keyword in NEED_PARENS && !text.contains('(')) return null
            return "$text {" to true
        }
        if (isStatement(text)) return "$text;" to false
        // parentheses were closed but it is not a statement to terminate: still an improvement, no semicolon
        return if (text != trimmed) text to false else null
    }

    private fun isStatement(text: String): Boolean {
        val head = text.trimStart()
        if (head.startsWith("[") || head.startsWith("//") || head.startsWith("*") || head.startsWith("#")) return false
        val last = text.last()
        val ends = last.isLetterOrDigit() || last == '_' || last == ')' || last == ']' || last == '"' || last == '\'' || last == '>' || text.endsWith("++") || text.endsWith("--")
        if (!ends) return false
        val firstWord = head.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (firstWord in STATEMENT_STARTERS) return true
        // `void Foo()` and the like are declarations; terminating them with `;` would be wrong
        if (last == ')' && DECLARATION.containsMatchIn(text)) return false
        return true
    }
}
