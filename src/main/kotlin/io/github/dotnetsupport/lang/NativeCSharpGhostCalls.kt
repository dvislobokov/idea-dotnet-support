package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import io.github.dotnetsupport.csharp.lang.psi.CSharpLocalFunctionStatement
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol

/**
 * The call after a gray text that ends on the name of a method (0.1.167, the user's report): Tab on `.Sel` + gray `ect` wrote `.Select`
 * and nothing more, while the row of the list writes `Select(|)` with the parameter info. Here the gray text gets what the row gets
 * ([NativeCSharpCalls.callHandler]): `()` / `();` / `(|)` by the shape of the method, the parameter info when arguments go there. Only
 * when every candidate of the name is a method and no `(` / `<` follows; not after `+=` / `-=` (a method group) or in `nameof(`.
 */
object NativeCSharpGhostCalls {
    private val SUBSCRIPTION = Regex("""[+-]=\s*$""")

    /** Called on the EDT right after a gray text was inserted, the caret at its end. */
    fun closeCall(editor: Editor) {
        val project = editor.project ?: return
        if (DumbService.isDumb(project)) return
        val document = editor.document
        val offset = editor.caretModel.offset
        val text = document.charsSequence
        if (offset <= 0 || !(text[offset - 1].isLetterOrDigit() || text[offset - 1] == '_')) return
        if (offset < text.length && (text[offset] == '(' || text[offset] == '<' || text[offset].isLetterOrDigit() || text[offset] == '_')) return
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val before = text.subSequence(lineStart, start)
        if (SUBSCRIPTION.containsMatchIn(before) || before.trimEnd().endsWith("nameof(")) return
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, file)) return
        val shape = runCatching {
            val leaf = file.findElementAt(offset - 1) ?: return
            val r = CSharpSemanticSession(project).resolver(file)
            val symbols = r.resolve(leaf)?.symbols?.takeIf { it.isNotEmpty() } ?: return
            if (!symbols.all(r::isMethod)) return
            val returnsNothing = symbols.all { symbol ->
                when (symbol) {
                    is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.returnType?.text == "void" ||
                        (symbol.element as? CSharpLocalFunctionStatement)?.returnType?.text == "void"
                    else -> r.returnType(symbol, emptyList())?.let(r::definitionName) == "System.Void"
                }
            }
            val takesArguments = symbols.any { r.signature(it, r.isExtension(it))?.isNotEmpty() ?: true }
            returnsNothing to takesArguments
        }.getOrElse { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it else return }
        val call = CSharpCalls.call(shape.first, shape.second, false, CSharpCalls.endsStatement(text, start), CSharpCalls.restOfLine(text, offset))
        document.insertString(offset, call.text)
        editor.caretModel.moveToOffset(offset + call.caret)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        if (call.caret < call.text.length) NativeCSharpCallPopups.afterCall(editor)
    }
}
