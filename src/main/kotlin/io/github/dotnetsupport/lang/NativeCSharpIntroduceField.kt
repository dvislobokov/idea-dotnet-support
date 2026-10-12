package io.github.dotnetsupport.lang

import com.intellij.idea.ActionsBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.actions.IntroduceFieldAction
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession

/**
 * Introduce Field of Rider's Refactor This on the native tree (CSHARP_PSI_MIGRATION.md, task C4d): the selected expression (or the one at the
 * caret, as Introduce Variable takes it) into a field `_name` of its type. An expression of no local or parameter initializes the field
 * where it is declared (`private readonly T _name = …;`, as Rider's "field initializer"); else the field is assigned before the statement
 * ("current member"). The field goes after the last field of the type (at its top when there is none).
 */
object NativeCSharpIntroduceField {
    class Plan(
        val expression: CSharpExpression, val statement: CSharpStatement, val whole: Boolean, val owner: CSharpTypeDeclaration, val type: String, val name: String,
        val isStatic: Boolean, val inInitializer: Boolean, val usings: Set<String>,
    )

    sealed class Result {
        class Ok(val plan: Plan) : Result()
        class Error(val message: String) : Result()
    }

    fun analyze(file: CSharpFile, selection: TextRange?, offset: Int): Result {
        if (file.compilationUnit == null) return Result.Error("Introduce Field needs the native C# tree")
        val semantic = CSharpSemanticSession(file.project).resolver(file)
        val extraction = NativeCSharpContextEdits.extractionAt(file, selection, offset, semantic) ?: return Result.Error("Select an expression of a known type inside a body")
        val expression = extraction.expression
        var member: PsiElement? = extraction.statement.parent
        while (member != null && !(member is CSharpMemberDeclaration && member.parent is CSharpTypeDeclaration)) member = member.parent
        member as CSharpMemberDeclaration? ?: return Result.Error("Introduce Field works inside a member of a type")
        val owner = member.parent as CSharpTypeDeclaration
        val writer = CSharpCodeWriter(semantic, member, CSharpGenerateSite.nullableContext(file, expression.textRange.startOffset))
        val type = writer.type(semantic.typeOf(expression) ?: return Result.Error("Cannot infer the type of the expression")) ?: return Result.Error("Cannot infer the type of the expression")
        val names = PsiTreeUtil.findChildrenOfType(expression, CSharpSimpleName::class.java) + listOfNotNull(expression as? CSharpSimpleName)
        val usesLocals = names.any { name -> name.identifier?.let(semantic.syntax::symbolAt)?.kind.let { it == LocalSymbolKind.LOCAL || it == LocalSymbolKind.PARAMETER || it == LocalSymbolKind.LOCAL_FUNCTION } }
        val memberIsStatic = member.modifiers.any { it.text == "static" }
        val usesInstance = names.any { name -> name.identifier?.let(semantic.syntax::symbolAt) == null && NativeCSharpExtractMethod.usesInstance(name, semantic) } ||
            expression is CSharpInstanceExpression || PsiTreeUtil.findChildrenOfType(expression, CSharpInstanceExpression::class.java).isNotEmpty()
        val taken = HashSet<String>()
        for (part in owner.members) when (part) {
            is CSharpBaseFieldDeclaration -> part.declaration?.variables?.forEach { v -> v.identifier?.text?.let(taken::add) }
            else -> CSharpDeclarationNames.nameElement(part)?.text?.let(taken::add)
        }
        val name = CSharpVariableNames.unique("_" + extraction.name.removePrefix("@"), taken)
        val inInitializer = !usesLocals && !usesInstance && !extraction.whole
        return Result.Ok(Plan(expression, extraction.statement, extraction.whole, owner, type, name, memberIsStatic, inInitializer, writer.usings))
    }

    class Edit(val text: String, val fieldNameOffset: Int)

    fun apply(plan: Plan, text: String, unit: String): Edit {
        val edits = ArrayList<Pair<TextRange, String>>()
        val expression = plan.expression
        val modifiers = listOfNotNull("private", "static".takeIf { plan.isStatic }, "readonly".takeIf { plan.inInitializer }).joinToString(" ")
        val field = "$modifiers ${plan.type} ${plan.name}" + (if (plan.inInitializer) " = ${expression.text}" else "") + ";"
        val typeIndent = NativeCSharpUsingEdits.indentOf(text, plan.owner.textRange.startOffset).orEmpty()
        // "at the end" of the page of the server: after the last member of any kind
        val lastField = if (CSharpGenerationOptions.atEnd) plan.owner.members.lastOrNull() else plan.owner.members.lastOrNull { it is CSharpFieldDeclaration }
        val fieldAt: Int
        val fieldText: String
        if (lastField != null) {
            fieldAt = lastField.textRange.endOffset
            fieldText = "\n$typeIndent$unit$field"
        } else {
            fieldAt = plan.owner.openBraceToken!!.textRange.endOffset
            fieldText = "\n$typeIndent$unit$field\n"
        }
        edits += TextRange(fieldAt, fieldAt) to fieldText
        if (plan.inInitializer) {
            edits += if (plan.whole) NativeCSharpContextEdits.lineRange(text, plan.statement.textRange) to "" else expression.textRange to plan.name
        } else if (plan.whole) {
            edits += plan.statement.textRange to "${plan.name} = ${expression.text};"
        } else {
            val indent = NativeCSharpUsingEdits.indentOf(text, plan.statement.textRange.startOffset).orEmpty()
            edits += TextRange(plan.statement.textRange.startOffset, plan.statement.textRange.startOffset) to "${plan.name} = ${expression.text};\n$indent"
            edits += expression.textRange to plan.name
        }
        var result = text
        var nameAt = fieldAt + fieldText.indexOf(plan.name)
        for ((range, replacement) in edits.sortedWith(compareByDescending<Pair<TextRange, String>> { it.first.startOffset }.thenByDescending { it.first.endOffset })) {
            result = result.substring(0, range.startOffset) + replacement + result.substring(range.endOffset)
            if (range.startOffset < fieldAt) nameAt += replacement.length - range.length
        }
        for (namespace in plan.usings) {
            if (CSharpUsings.isVisible(namespace, result)) continue
            val insertion = CSharpUsings.insertion(result, namespace) ?: continue
            result = result.substring(0, insertion.offset) + insertion.text + result.substring(insertion.offset)
            if (insertion.offset <= nameAt) nameAt += insertion.text.length
        }
        return Edit(result, nameAt)
    }

    fun perform(project: Project, editor: Editor, file: CSharpFile) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val selection = editor.selectionModel.let { if (it.hasSelection()) TextRange(it.selectionStart, it.selectionEnd) else null }
        val result = ReadAction.compute<Result, RuntimeException> { analyze(file, selection, editor.caretModel.offset) }
        val plan = when (result) {
            is Result.Error -> {
                CommonRefactoringUtil.showErrorHint(project, editor, result.message, TITLE, null)
                return
            }
            is Result.Ok -> result.plan
        }
        var nameAt = -1
        WriteCommandAction.writeCommandAction(project, file).withName(TITLE).run<RuntimeException> {
            val edit = apply(plan, editor.document.text, NativeCSharpContextEdits.unit(file))
            nameAt = edit.fieldNameOffset
            val old = editor.document.charsSequence
            var prefix = 0
            val max = minOf(old.length, edit.text.length)
            while (prefix < max && old[prefix] == edit.text[prefix]) prefix++
            var suffix = 0
            while (suffix < max - prefix && old[old.length - 1 - suffix] == edit.text[edit.text.length - 1 - suffix]) suffix++
            editor.document.replaceString(prefix, old.length - suffix, edit.text.substring(prefix, edit.text.length - suffix))
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(edit.fieldNameOffset)
        }
        if (nameAt < 0) return
        // the name in a box at the field and its uses, as Introduce Variable and Rider's Introduce Field
        val owner = PsiTreeUtil.getParentOfType(file.findElementAt(nameAt), CSharpTypeDeclaration::class.java) ?: return
        val uses = PsiTreeUtil.collectElements(owner) { it.firstChild == null && it.text == plan.name && CSharpLeaves.isIdentifier(it) }.map { it.textRange.startOffset }
        NativeCSharpInplaceName.start(project, editor, file, nameAt, uses.filter { it != nameAt }, plan.name, TITLE)
    }

    const val TITLE = "Introduce Field"
}

/** Refactor | Introduce Field (Ctrl+Alt+F) and the row of Refactor This: native in a C# file of the native tree, the platform's elsewhere. */
class CSharpIntroduceFieldAction : AnAction(), DumbAware {
    private val platform = IntroduceFieldAction()

    init {
        templatePresentation.setText(ActionsBundle.actionText(ID))
        templatePresentation.description = ActionsBundle.actionDescription(ID)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun native(e: AnActionEvent): Boolean = (e.getData(CommonDataKeys.PSI_FILE) as? CSharpFile)?.compilationUnit != null && e.getData(CommonDataKeys.EDITOR) != null

    override fun update(e: AnActionEvent) {
        if (!native(e)) return platform.update(e)
        val project = e.project
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null && !DumbService.isDumb(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!native(e)) return platform.actionPerformed(e)
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) as? CSharpFile ?: return
        NativeCSharpIntroduceField.perform(project, editor, file)
    }

    companion object {
        const val ID = "IntroduceField"
    }
}
