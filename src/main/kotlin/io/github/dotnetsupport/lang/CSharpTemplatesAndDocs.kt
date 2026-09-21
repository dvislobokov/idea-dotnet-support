package io.github.dotnetsupport.lang

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** Live templates of `liveTemplates/CSharp.xml` apply to C# files. */
class CSharpTemplateContext : TemplateContextType("C#") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean = templateActionContext.file is CSharpFile
}

/** `csharpTypeName()`: the type around the caret, for the `ctor` template. */
class CSharpTypeNameMacro : MacroBase("csharpTypeName", "csharpTypeName()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val file = context.psiElementAtStartOffset?.containingFile ?: return null
        val structure = CSharpDeclarations.scan(context.editor?.document?.immutableCharSequence ?: file.viewProvider.contents)
        return structure.pathTo(context.startOffset).lastOrNull { it.kind.isType }?.let { TextResult(it.name) }
    }
}

/** XML documentation comments: what `///` above a declaration expands to. */
object CSharpDocComments {
    /** `a`, `b` of `(int a, string b = "x", params object[] rest)`: the last word of each parameter before its default value. */
    fun parameterNames(parameters: String?): List<String> {
        val inner = parameters?.trim()?.removePrefix("(")?.removeSuffix(")")?.removePrefix("[")?.removeSuffix("]") ?: return emptyList()
        val result = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        val current = StringBuilder()
        for (c in "$inner,") {
            // a default value may be a string with commas and brackets of its own
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c in "<([{" -> depth++
                c in ">)]}" -> depth--
            }
            if (c == ',' && depth <= 0 && quote == null) {
                WORD.findAll(current.toString().substringBefore('=')).lastOrNull()?.value?.let { result += it }
                current.clear()
            } else current.append(c)
        }
        return result
    }

    /** The lines after the `///` that has just been typed; the caret goes to the end of the first one. */
    fun stub(declaration: CSharpDeclarationInfo, indent: String): Pair<String, Int> {
        val first = " <summary>\n$indent/// "
        val lines = buildList {
            add("</summary>")
            parameterNames(declaration.parameters).forEach { add("<param name=\"$it\"></param>") }
            val returns = declaration.type != null && declaration.type != "void" && declaration.kind in setOf(DeclarationKind.METHOD, DeclarationKind.DELEGATE, DeclarationKind.OPERATOR)
            if (returns) add("<returns></returns>")
        }
        return (first + lines.joinToString("") { "\n$indent/// $it" }) to first.length
    }

    /** The declaration that starts right after [offset], with nothing but whitespace in between. */
    fun declarationAfter(text: CharSequence, offset: Int): CSharpDeclarationInfo? {
        val next = (offset until text.length).firstOrNull { !text[it].isWhitespace() } ?: return null
        return CSharpDeclarations.scan(text).all().firstOrNull { it.range.startOffset == next && it.kind != DeclarationKind.NAMESPACE }
    }

    private val WORD = Regex("""[A-Za-z_@]\w*""")
}

/** The third `/` above a declaration makes the summary, with `param` and `returns` for a method. */
class CSharpDocCommentTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '/' || file !is CSharpFile) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val lineText = document.immutableCharSequence.subSequence(lineStart, document.getLineEndOffset(line)).toString()
        if (lineText.trim() != "///" || offset != lineStart + lineText.indexOf("///") + 3) return Result.CONTINUE
        val declaration = CSharpDocComments.declarationAfter(document.immutableCharSequence, document.getLineEndOffset(line)) ?: return Result.CONTINUE

        val (text, caret) = CSharpDocComments.stub(declaration, lineText.substringBefore("///"))
        document.insertString(offset, text)
        editor.caretModel.moveToOffset(offset + caret)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        return Result.STOP
    }
}

/** Enter inside a `///` comment continues it on the next line. */
class CSharpDocCommentEnterHandler : EnterHandlerDelegateAdapter() {
    private var inDocComment = false

    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        inDocComment = false
        if (file !is CSharpFile) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val offset = caretOffset.get()
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val before = document.immutableCharSequence.subSequence(lineStart, offset).toString()
        // after the slashes: Enter before them only moves the comment down
        inDocComment = before.trimStart().startsWith("///")
        return EnterHandlerDelegate.Result.Continue
    }

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (!inDocComment) return EnterHandlerDelegate.Result.Continue
        inDocComment = false
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        if (line == 0) return EnterHandlerDelegate.Result.Continue
        val previous = document.immutableCharSequence.subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).toString()
        val indent = previous.substringBefore("///")
        // the platform has indented the new line already, or not: the prefix is made the one of the line above
        val lineStart = document.getLineStartOffset(line)
        val existingIndent = document.immutableCharSequence.subSequence(lineStart, offset).toString()
        if (existingIndent.isNotBlank()) return EnterHandlerDelegate.Result.Continue
        document.replaceString(lineStart, offset, "$indent/// ")
        editor.caretModel.moveToOffset(lineStart + indent.length + 4)
        return EnterHandlerDelegate.Result.Continue
    }
}
