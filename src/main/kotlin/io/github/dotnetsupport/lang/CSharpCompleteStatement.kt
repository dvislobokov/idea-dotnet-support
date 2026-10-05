package io.github.dotnetsupport.lang

import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Complete Statement (Ctrl+Shift+Enter): adds the missing `;` at the end of the statement on the caret's line and starts a new line.
 * Without a parser the safe cases only — an assignment, a call, `return` / `throw` / `break` and the like — so a header such as
 * `if (x)` or a declaration such as `void M()` is never turned into `if (x);`. On anything else it just opens a new line, as the action does.
 * With `EDITING` native and a file of the native tree the statement at the caret decides ([NativeCSharpCompleteStatement]); where the
 * tree gives up, this answers.
 */
class CSharpSmartEnterProcessor : SmartEnterProcessor() {
    override fun process(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        if (psiFile !is CSharpFile) return false
        val document = editor.document
        if (NativeCSharpEditing.usable(psiFile)) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
            val plan = NativeCSharpCompleteStatement.plan(psiFile, editor.caretModel.offset, NativeCSharpCompleteStatement.indentUnit(psiFile))
            if (plan != null) {
                val inserts = plan.inserts.withIndex().sortedWith(compareByDescending<IndexedValue<NativeCSharpCompleteStatement.Insert>> { it.value.offset }.thenByDescending { it.index })
                for (insert in inserts) document.insertString(insert.value.offset, insert.value.text)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                editor.caretModel.moveToOffset(plan.caretAfter())
                if (plan.newLine) startNewLine(editor)
                return true
            }
        }
        val line = document.getLineNumber(editor.caretModel.offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val lineText = document.immutableCharSequence.subSequence(lineStart, lineEnd).toString()
        val codeEnd = CSharpCompleteStatement.codeEndIn(lineText)
        if (codeEnd > 0 && CSharpCompleteStatement.needsSemicolon(lineText.substring(0, codeEnd))) {
            document.insertString(lineStart + codeEnd, ";")
            PsiDocumentManager.getInstance(project).commitDocument(document)
            editor.caretModel.moveToOffset(lineStart + codeEnd + 1)
        } else {
            editor.caretModel.moveToOffset(lineEnd)
        }
        startNewLine(editor)
        return true
    }

    /** An indented line below, like the action does (our enter handlers / indent provider run through it). */
    private fun startNewLine(editor: Editor) {
        val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
        EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_START_NEW_LINE)
            .execute(editor, editor.caretModel.currentCaret, dataContext)
    }
}

object CSharpCompleteStatement {
    // headers want a block or a different terminator, not a `;`; get / set / add / remove are accessor headers
    private val HEADERS = setOf(
        "if", "for", "foreach", "while", "switch", "do", "else", "lock", "fixed", "using", "try", "catch", "finally",
        "case", "default", "namespace", "class", "struct", "interface", "enum", "record", "get", "set", "add", "remove",
    )

    // single- or two-word statements that end with `;`
    private val STATEMENTS = setOf("return", "throw", "yield", "await", "break", "continue", "goto")

    /** The offset in [lineText] right after the last code token (before a trailing `//` or `/* */` comment and trailing spaces), or 0. */
    fun codeEndIn(lineText: String): Int = CSharpExpressions.tokenize(lineText).lastOrNull()?.end ?: 0

    /** Whether a `;` completes [code] — one line's code with any trailing comment and spaces already removed. Conservative by design. */
    fun needsSemicolon(code: String): Boolean {
        val tokens = CSharpExpressions.tokenize(code)
        val last = tokens.lastOrNull() ?: return false
        // already a terminator, a block boundary, a label, or a list continuation
        if (code.substring(0, last.end).trimEnd().lastOrNull() in SKIP_AFTER) return false
        // an operator or a dot at the end means the expression is unfinished (`x = `, `a +`, `obj.`)
        if (last.type == CSharpTokenTypes.OPERATOR || last.type == CSharpTokenTypes.DOT) return false
        if (!balanced(tokens)) return false
        val first = tokens.first()
        if (first.type == CSharpTokenTypes.KEYWORD && first.text in HEADERS) return false
        if (first.type == CSharpTokenTypes.KEYWORD && first.text in STATEMENTS) return true
        if (hasTopLevelAssignment(tokens)) return true
        // a call statement: an identifier chain that ends in a closed call
        return first.isIdentifier && last.type == CSharpTokenTypes.RPAREN
    }

    private val SKIP_AFTER = setOf(';', '{', '}', ':', ',')

    private fun balanced(tokens: List<CSharpExpressions.Token>): Boolean {
        var paren = 0
        var bracket = 0
        var brace = 0
        for (t in tokens) when (t.type) {
            CSharpTokenTypes.LPAREN -> paren++
            CSharpTokenTypes.RPAREN -> paren--
            CSharpTokenTypes.LBRACKET -> bracket++
            CSharpTokenTypes.RBRACKET -> bracket--
            CSharpTokenTypes.LBRACE -> brace++
            CSharpTokenTypes.RBRACE -> brace--
        }
        return paren == 0 && bracket == 0 && brace == 0
    }

    /** A plain or compound assignment (`=`, `+=`, ...) at the top level — not `==`, `!=`, `<=`, `>=` or the `=>` of a lambda. */
    private fun hasTopLevelAssignment(tokens: List<CSharpExpressions.Token>): Boolean {
        var depth = 0
        for (i in tokens.indices) {
            val t = tokens[i]
            when (t.type) {
                CSharpTokenTypes.LPAREN, CSharpTokenTypes.LBRACKET, CSharpTokenTypes.LBRACE -> depth++
                CSharpTokenTypes.RPAREN, CSharpTokenTypes.RBRACKET, CSharpTokenTypes.RBRACE -> depth--
            }
            if (depth != 0 || t.type != CSharpTokenTypes.OPERATOR || t.text != "=") continue
            val previous = tokens.getOrNull(i - 1)?.text
            val next = tokens.getOrNull(i + 1)?.text
            if (previous in setOf("=", "!", "<", ">")) continue // the second char of == != <= >=
            if (next == "=" || next == ">") continue // == or the => of a lambda
            return true
        }
        return false
    }
}
