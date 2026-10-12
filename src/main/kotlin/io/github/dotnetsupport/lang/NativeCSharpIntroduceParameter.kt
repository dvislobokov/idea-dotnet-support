package io.github.dotnetsupport.lang

import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.idea.ActionsBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch

/**
 * The name of what a refactoring has just written, edited in place at all its occurrences at once (Rider's inplace rename after Extract
 * Method, Introduce Field, Introduce Parameter): [primary] is the box the caret stands in, [others] follow it.
 */
object NativeCSharpInplaceName {
    fun start(project: Project, editor: Editor, file: PsiFile, primary: Int, others: List<Int>, name: String, title: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val main = file.findElementAt(primary)?.takeIf { it.text == name } ?: return
        val rest = others.mapNotNull { offset -> file.findElementAt(offset)?.takeIf { it.text == name && it != main } }.distinct()
        val container = rest.fold<PsiElement, PsiElement?>(main) { common, element -> common?.let { PsiTreeUtil.findCommonParent(it, element) } } ?: return
        val builder = TemplateBuilderImpl(container)
        builder.replaceElement(main, "NAME", ConstantNode(name), true)
        rest.forEachIndexed { i, element -> builder.replaceElement(element, "NAME_$i", "NAME", false) }
        WriteCommandAction.writeCommandAction(project, file).withName(title).run<RuntimeException> {
            editor.caretModel.moveToOffset(container.textRange.startOffset)
            builder.run(editor, true)
        }
    }
}

/**
 * Introduce Parameter of Rider's Refactor This (Ctrl+Alt+P) on the native tree, without the language server: the selected expression
 * (or the one at the caret) of a method or constructor body becomes a new parameter; every call in the solution (found by the plugin's own
 * search of usages) passes the expression, its parameters replaced by the arguments of that call. Optional: when the expression is a
 * constant, the parameter can take it as its default value instead, and the calls stay as they are. Not done (a hint says why): an
 * expression of locals, of the method's type parameters or `ref` / `out` / `params` parameters; a method that overrides, implements or is
 * overridden; a member of the instance when a call has another receiver or is a constructor call; a member of the type used by its simple
 * name when a call is outside the type; a call that uses the method as a method group.
 */
object NativeCSharpIntroduceParameter {
    class Plan(
        val file: CSharpFile, val expression: CSharpExpression, val method: CSharpBaseMethodDeclaration, val type: String, val name: String,
        val constant: Boolean, val usesInstance: Boolean, val usesOwnMembers: Boolean,
        /** The names of the method's parameters inside [expression]: replaced by the arguments of each call. */
        val parameterUses: List<Pair<TextRange, CSharpParameter>>, val usings: Set<String>,
    )

    sealed class Result<out T> {
        class Ok<T>(val value: T) : Result<T>()
        class Error(val message: String) : Result<Nothing>()
    }

    /** An insertion or replacement; [nameAt]: where in [text] the new name stands (-1: nowhere). */
    class Edit(val range: TextRange, val text: String, val nameAt: Int = -1)

    fun analyze(file: CSharpFile, selection: TextRange?, offset: Int): Result<Plan> {
        if (file.compilationUnit == null) return Result.Error("Introduce Parameter needs the native C# tree")
        val expression = NativeCSharpContextEdits.expressionAt(file, selection, offset) ?: return Result.Error("Select an expression inside a method body")
        if (expression is CSharpDeclarationExpression || expression is CSharpInitializerExpression || expression is CSharpThrowExpression) return Result.Error("Select an expression inside a method body")
        if ((expression.parent as? CSharpAssignmentExpression)?.left == expression) return Result.Error("An assignment target cannot become a parameter")
        if ((expression.parent as? CSharpArgument)?.refKindKeyword?.textLength ?: 0 > 0) return Result.Error("A 'ref' / 'out' argument cannot become a parameter")
        var at: PsiElement? = expression.parent
        var method: CSharpBaseMethodDeclaration? = null
        while (at != null && at !is PsiFile) {
            if (at is CSharpAnonymousFunctionExpression || at is CSharpLocalFunctionStatement) return Result.Error("Introduce Parameter works in a method, not in a lambda or a local function")
            if (at is CSharpMethodDeclaration || at is CSharpConstructorDeclaration) { method = at as CSharpBaseMethodDeclaration; break }
            if (at is CSharpMemberDeclaration) break
            at = at.parent
        }
        method ?: return Result.Error("Introduce Parameter works inside the body of a method or a constructor")
        if (method.parameterList == null || method.parameterList?.closeParenToken?.textLength == 0) return Result.Error("The method has no parameter list")
        if (method is CSharpMethodDeclaration && (method.explicitInterfaceSpecifier != null || method.modifiers.any { it.text == "override" || it.text == "extern" || it.text == "partial" }))
            return Result.Error("The method implements or overrides another one: its signature is not its own to change")
        if (method is CSharpConstructorDeclaration && method.modifiers.any { it.text == "static" }) return Result.Error("A static constructor takes no parameters")
        val semantic = CSharpSemanticSession(file.project).resolver(file)
        val syntax = semantic.syntax
        val parameters = method.parameterList?.parameters.orEmpty()
        val uses = ArrayList<Pair<TextRange, CSharpParameter>>()
        var usesInstance = expression is CSharpInstanceExpression || PsiTreeUtil.findChildrenOfType(expression, CSharpInstanceExpression::class.java).isNotEmpty()
        var usesOwnMembers = false
        for (name in PsiTreeUtil.findChildrenOfType(expression, CSharpSimpleName::class.java) + listOfNotNull(expression as? CSharpSimpleName)) {
            val leaf = name.identifier ?: continue
            val symbol = syntax.symbolAt(leaf)
            if (symbol != null) {
                if (expression.textRange.contains(symbol.declaration.textRange)) continue
                when (symbol.kind) {
                    LocalSymbolKind.PARAMETER -> {
                        val parameter = symbol.declaration.parent as? CSharpParameter
                        if (parameter == null || parameter !in parameters) return Result.Error("The expression uses '${symbol.name}', a parameter of a lambda or a local function")
                        if (parameter.modifiers.any { it.text in setOf("ref", "out", "params", "in") }) return Result.Error("The expression uses the '${parameter.modifiers.first().text}' parameter '${symbol.name}'")
                        if (NativeCSharpExtractMethod.isWritten(name)) return Result.Error("The expression assigns the parameter '${symbol.name}'")
                        uses += name.textRange to parameter
                    }
                    LocalSymbolKind.LOCAL -> return Result.Error("The expression uses the local '${symbol.name}': the calls do not have it")
                    LocalSymbolKind.LOCAL_FUNCTION -> return Result.Error("The expression calls the local function '${symbol.name}'")
                    LocalSymbolKind.TYPE_PARAMETER -> if (PsiTreeUtil.isAncestor(method, symbol.declaration, true)) return Result.Error("The expression uses the type parameter '${symbol.name}' of the method")
                    LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> usesInstance = true
                    LocalSymbolKind.LABEL -> {}
                }
                continue
            }
            if (NativeCSharpExtractMethod.usesInstance(name, semantic)) usesInstance = true
            else if (ownMember(name, semantic)) usesOwnMembers = true
        }
        val owner = method.parent as? CSharpTypeDeclaration
        val writer = CSharpCodeWriter(semantic, owner ?: method, CSharpGenerateSite.nullableContext(file, expression.textRange.startOffset))
        val semanticType = semantic.typeOf(expression) ?: return Result.Error("Cannot infer the type of the expression")
        if ((semanticType as? io.github.dotnetsupport.lang.semantic.SemanticType.Library)?.type?.fullName == "System.Void") return Result.Error("The expression has no value")
        val type = writer.type(semanticType) ?: return Result.Error("Cannot infer the type of the expression")
        val taken = parameters.mapNotNull { it.identifier?.text?.removePrefix("@") }.toSet()
        val name = CSharpVariableNames.unique(NativeCSharpContextEdits.suggestedName(expression, semantic, expression), taken)
        return Result.Ok(Plan(file, expression, method, type, name, isConstant(expression), usesInstance, usesOwnMembers, uses, writer.usings))
    }

    /** A name that stands for a static member of a type of the solution (by its simple name, so a call elsewhere would have to qualify it). */
    private fun ownMember(name: CSharpSimpleName, semantic: io.github.dotnetsupport.lang.semantic.CSharpNameResolver): Boolean {
        val parent = name.parent
        if (parent is CSharpMemberAccessExpression && parent.nameElement == name || parent is CSharpQualifiedName && parent.right == name || parent is CSharpMemberBindingExpression) return false
        if (parent is CSharpNameColon || parent is CSharpNameEquals || NativeCSharpTypePositions.isType(name)) return false
        val symbols = runCatching { semantic.resolveName(name)?.symbols }.getOrNull() ?: return false
        return symbols.any { it is io.github.dotnetsupport.lang.semantic.CSharpSymbol.SourceMember || it is io.github.dotnetsupport.lang.semantic.CSharpSymbol.LibraryMember }
    }

    /** What may be a default value of a parameter: a literal (signed), `default`, `nameof(…)`. */
    fun isConstant(expression: CSharpExpression?): Boolean = when (val e = NativeCSharpContextEdits.unwrap(expression)) {
        is CSharpLiteralExpression -> true
        is CSharpDefaultExpression -> true
        is CSharpPrefixUnaryExpression -> e.operatorToken?.text in setOf("-", "+") && isConstant(e.operand)
        is CSharpInvocationExpression -> (e.expression as? CSharpSimpleName)?.identifier?.text == "nameof"
        is CSharpInterpolatedStringExpression -> false
        else -> false
    }

    // ---- the calls

    /**
     * The edits of introducing [plan] under [name]: the parameter in the declaration, the name in place of the expression, and — unless
     * [optional] — the argument at every call of the solution.
     */
    fun edits(project: Project, plan: Plan, name: String = plan.name, optional: Boolean = false): Result<Map<PsiFile, List<Edit>>> {
        val method = plan.method
        val parameters = method.parameterList?.parameters.orEmpty()
        val session = CSharpSemanticSession(project)
        if (CSharpSolutionSearch.baseMembers(method, session).isNotEmpty()) return Result.Error("The method implements or overrides another one: its signature is not its own to change")
        val overriding = CSharpSolutionSearch.overridingMembers(project, method, session)
        if (overriding.isNotEmpty()) return Result.Error("The method is overridden or implemented elsewhere (${overriding.size}): change the hierarchy with Change Signature")
        val firstOptional = parameters.indexOfFirst { it.default != null || it.modifiers.any { m -> m.text == "params" } }.let { if (it < 0) parameters.size else it }
        val paramsAt = parameters.indexOfFirst { p -> p.modifiers.any { it.text == "params" } }.let { if (it < 0) parameters.size else it }
        val position = if (optional) paramsAt else firstOptional
        val result = LinkedHashMap<PsiFile, MutableList<Edit>>()
        fun add(file: PsiFile, edit: Edit) { result.getOrPut(file) { ArrayList() } += edit }

        // the declaration
        val declared = plan.type + " " + name + if (optional) " = " + plan.expression.text else ""
        val list = method.parameterList!!
        when {
            parameters.isEmpty() -> add(plan.file, Edit(TextRange.from(list.closeParenToken!!.textRange.startOffset, 0), declared, plan.type.length + 1))
            position < parameters.size -> add(plan.file, Edit(TextRange.from(parameters[position].textRange.startOffset, 0), "$declared, ", plan.type.length + 1))
            else -> add(plan.file, Edit(TextRange.from(parameters.last().textRange.endOffset, 0), ", $declared", plan.type.length + 3))
        }
        add(plan.file, Edit(plan.expression.textRange, name, 0))
        if (optional) return Result.Ok(result)

        val target = CSharpSolutionSearch.targetOf(method) ?: return Result.Error("Cannot find the calls of the method")
        val usages = CSharpSolutionSearch.usages(project, target, GlobalSearchScope.projectScope(project), session)
        val owner = method.parent
        for (usage in usages) {
            val leaf = usage.leaf
            val file = leaf.containingFile ?: continue
            if (insideNameof(leaf)) continue
            val call = callOf(leaf, method is CSharpConstructorDeclaration)
                ?: return Result.Error("'${target.name}' is used as a method group (${file.name}): the use cannot pass the new argument")
            if (plan.expression.textRange.intersects(leaf.textRange) && file == plan.file) return Result.Error("The expression calls the method itself")
            if (plan.usesInstance) {
                if (method is CSharpConstructorDeclaration) return Result.Error("The expression uses members of the instance, which a call of the constructor does not have")
                val receiver = call.receiver
                if (receiver != null && receiver !is CSharpThisExpression && receiver !is CSharpBaseExpression)
                    return Result.Error("The expression uses members of the instance and a call goes to another one ('${receiver.text}', ${file.name})")
            }
            if ((plan.usesOwnMembers || plan.usesInstance) && owner != null && !PsiTreeUtil.isAncestor(owner, leaf, true))
                return Result.Error("The expression uses members of '${(owner as? CSharpTypeDeclaration)?.identifier?.text}' by their names and a call is outside it (${file.name})")
            val value = argumentText(plan, call.arguments) ?: return Result.Error("A call does not pass a parameter the expression uses (${file.name})")
            add(file, callEdit(call, value, name, position))
        }
        return Result.Ok(result)
    }

    private class Call(val arguments: List<CSharpArgument>, val list: CSharpArgumentList?, val listAt: Int, val receiver: CSharpExpression?)

    private fun insideNameof(leaf: PsiElement): Boolean {
        val invocation = PsiTreeUtil.getParentOfType(leaf, CSharpInvocationExpression::class.java) ?: return false
        return (invocation.expression as? CSharpSimpleName)?.identifier?.text == "nameof"
    }

    /** The call [leaf] names: its arguments, where a missing list goes (`new T { … }`), the receiver of `x.M(…)`; null for a method group. */
    private fun callOf(leaf: PsiElement, constructor: Boolean): Call? {
        if (constructor) {
            when (val parent = leaf.parent) {
                is CSharpConstructorInitializer -> return Call(parent.argumentList?.arguments.orEmpty(), parent.argumentList, -1, null)
                is CSharpImplicitObjectCreationExpression -> return Call(parent.argumentList?.arguments.orEmpty(), parent.argumentList, -1, null)
            }
            val creation = PsiTreeUtil.getParentOfType(leaf, CSharpObjectCreationExpression::class.java) ?: return null
            if (creation.type?.textRange?.contains(leaf.textRange) != true) return null
            return Call(creation.argumentList?.arguments.orEmpty(), creation.argumentList, creation.type!!.textRange.endOffset, null)
        }
        var expression: PsiElement = leaf.parent as? CSharpSimpleName ?: return null
        var receiver: CSharpExpression? = null
        val parent = expression.parent
        if (parent is CSharpMemberAccessExpression && parent.nameElement == expression) { receiver = parent.expression; expression = parent }
        else if (parent is CSharpMemberBindingExpression) expression = parent
        val invocation = expression.parent as? CSharpInvocationExpression ?: return null
        if (invocation.expression != expression) return null
        return Call(invocation.argumentList?.arguments.orEmpty(), invocation.argumentList, -1, receiver)
    }

    /** The expression as this call passes it: each parameter it reads replaced by the call's argument (or the parameter's default). */
    private fun argumentText(plan: Plan, arguments: List<CSharpArgument>): String? {
        val parameters = plan.method.parameterList?.parameters.orEmpty()
        val byParameter = HashMap<CSharpParameter, String>()
        for ((i, argument) in arguments.withIndex()) {
            val named = argument.nameColon?.let { colon -> colon.text.removeSuffix(":").trim() }
            val parameter = if (named != null) parameters.firstOrNull { it.identifier?.text == named } else parameters.getOrNull(i)
            val value = argument.expression ?: continue
            if (parameter != null) byParameter[parameter] = if (primary(value)) value.text else "(${value.text})"
        }
        val start = plan.expression.textRange.startOffset
        var text = plan.expression.text
        for ((range, parameter) in plan.parameterUses.sortedByDescending { it.first.startOffset }) {
            val value = byParameter[parameter] ?: parameter.default?.value?.text?.let { if (primary(parameter.default?.value)) it else "($it)" } ?: return null
            text = text.substring(0, range.startOffset - start) + value + text.substring(range.endOffset - start)
        }
        return text
    }

    private fun primary(e: CSharpExpression?): Boolean = e is CSharpSimpleName || e is CSharpLiteralExpression || e is CSharpMemberAccessExpression || e is CSharpInvocationExpression ||
        e is CSharpElementAccessExpression || e is CSharpParenthesizedExpression || e is CSharpThisExpression || e is CSharpObjectCreationExpression

    /** The argument at [position] when the call passes everything before it by position; else by name at the end. */
    private fun callEdit(call: Call, value: String, name: String, position: Int): Edit {
        val arguments = call.arguments
        val positional = arguments.takeWhile { it.nameColon == null }.size
        val list = call.list
        if (list == null) return Edit(TextRange.from(call.listAt, 0), "($value)")
        val close = list.closeParenToken?.takeIf { it.textLength > 0 }?.textRange?.startOffset ?: list.textRange.endOffset
        return when {
            arguments.isEmpty() -> Edit(TextRange.from(close, 0), if (position == 0) value else "$name: $value")
            position < positional -> Edit(TextRange.from(arguments[position].textRange.startOffset, 0), "$value, ")
            position == positional && positional == arguments.size -> Edit(TextRange.from(arguments.last().textRange.endOffset, 0), ", $value")
            else -> Edit(TextRange.from(arguments.last().textRange.endOffset, 0), ", $name: $value")
        }
    }

    /** [text] with [edits]; the offsets where the new name stands afterwards. */
    fun applyText(text: String, edits: List<Edit>): Pair<String, List<Int>> {
        val sorted = edits.sortedWith(compareBy<Edit> { it.range.startOffset }.thenBy { it.range.endOffset })
        val result = StringBuilder()
        val names = ArrayList<Int>()
        var at = 0
        for (edit in sorted) {
            result.append(text, at, edit.range.startOffset)
            if (edit.nameAt >= 0) names += result.length + edit.nameAt
            result.append(edit.text)
            at = edit.range.endOffset
        }
        result.append(text, at, text.length)
        return result.toString() to names
    }

    // ---- in the editor

    @Volatile
    private var optionalForTests: Boolean? = null

    @org.jetbrains.annotations.TestOnly
    fun setOptionalForTests(optional: Boolean?) {
        optionalForTests = optional
    }

    fun perform(project: Project, editor: Editor, file: CSharpFile) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val selection = editor.selectionModel.let { if (it.hasSelection()) TextRange(it.selectionStart, it.selectionEnd) else null }
        val plan = when (val result = ReadAction.compute<Result<Plan>, RuntimeException> { analyze(file, selection, editor.caretModel.offset) }) {
            is Result.Error -> return CommonRefactoringUtil.showErrorHint(project, editor, result.message, TITLE, null)
            is Result.Ok -> result.value
        }
        val unitTest = ApplicationManager.getApplication().isUnitTestMode
        if (!plan.constant || unitTest) return run(project, editor, file, plan, optionalForTests ?: false)
        // a constant may stay where it is as the default value, as Rider offers it
        val rows = listOf("Pass It at Every Call", "Make the Parameter Optional (= ${plan.expression.text.take(40)})")
        JBPopupFactory.getInstance().createPopupChooserBuilder(rows)
            .setTitle("Introduce Parameter '${plan.name}'")
            .setItemChosenCallback { row -> run(project, editor, file, plan, optional = row == rows[1]) }
            .createPopup().showInBestPositionFor(editor)
    }

    private fun run(project: Project, editor: Editor, file: CSharpFile, plan: Plan, optional: Boolean) {
        val found = ProgressManager.getInstance().runProcessWithProgressSynchronously<Result<Map<PsiFile, List<Edit>>>, RuntimeException>({
            ReadAction.compute<Result<Map<PsiFile, List<Edit>>>, RuntimeException> { if (plan.method.isValid) edits(project, plan, optional = optional) else Result.Error("The file has changed") }
        }, "Looking for the Calls of the Method", true, project)
        val edits = when (found) {
            is Result.Error -> return CommonRefactoringUtil.showErrorHint(project, editor, found.message, TITLE, null)
            is Result.Ok -> found.value
        }
        val documents = PsiDocumentManager.getInstance(project)
        var names: List<Int> = emptyList()
        WriteCommandAction.writeCommandAction(project, *edits.keys.toTypedArray()).withName(TITLE).run<RuntimeException> {
            for ((target, list) in edits) {
                val document = documents.getDocument(target) ?: continue
                if (target == file) names = applyText(document.text, list).second
                for (edit in list.sortedByDescending { it.range.startOffset }) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
                documents.commitDocument(document)
            }
            // the usings the type needs, in this file
            for (namespace in plan.usings) {
                val text = editor.document.text
                if (CSharpUsings.isVisible(namespace, text)) continue
                val insertion = CSharpUsings.insertion(text, namespace) ?: continue
                editor.document.insertString(insertion.offset, insertion.text)
                names = names.map { if (insertion.offset <= it) it + insertion.text.length else it }
            }
            documents.commitDocument(editor.document)
            editor.selectionModel.removeSelection()
            names.firstOrNull()?.let(editor.caretModel::moveToOffset)
        }
        if (names.isNotEmpty()) NativeCSharpInplaceName.start(project, editor, file, names.last(), names.dropLast(1), plan.name, TITLE)
    }

    const val TITLE = "Introduce Parameter"
}

/** Refactor | Introduce Parameter (Ctrl+Alt+P) and the row of Refactor This: native in a C# file of the native tree, the platform's elsewhere. */
class CSharpIntroduceParameterAction : AnAction(), DumbAware {
    private val platform = com.intellij.refactoring.actions.IntroduceParameterAction()

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
        NativeCSharpIntroduceParameter.perform(project, editor, file)
    }

    companion object {
        const val ID = "IntroduceParameter"
    }
}
