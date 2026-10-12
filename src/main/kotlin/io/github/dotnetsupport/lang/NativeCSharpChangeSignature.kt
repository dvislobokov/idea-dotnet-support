package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSearchTarget
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * A parameter of the new signature of Change Signature (Jellyfin № 25 / 51, 0.1.174): [oldIndex] is its place in the old signature, -1 for a
 * new one. [modifier] — `ref` / `out` / `in` / `params` / `this` / empty; [defaultValue] — the default of the declaration (`= 0`), null for
 * none; [callValue] — what a new parameter gets at the call sites (Rider's "value at call sites"; empty: `default`, or nothing when the
 * parameter has a default value).
 */
class CSharpSignatureParameter(
    val name: String, val type: String, val modifier: String = "", val defaultValue: String? = null, val oldIndex: Int = -1, val callValue: String = "",
) {
    fun text(): String = listOf(modifier, type, name).filter { it.isNotBlank() }.joinToString(" ") + (defaultValue?.takeIf { it.isNotBlank() }?.let { " = $it" } ?: "")
}

/** The new signature: [returnType] null for a constructor (and kept as is when unchanged). */
class CSharpNewSignature(val name: String, val returnType: String?, val parameters: List<CSharpSignatureParameter>) {
    fun preview(): String = listOfNotNull(returnType, name).joinToString(" ") + "(" + parameters.joinToString(", ") { it.text() } + ")"
}

/**
 * Change Signature of the C# methods, constructors, local functions, delegates and indexers of the solution, without the language server
 * (Rider's "Change signature…"): the declaration, every member of its hierarchy (what it overrides or implements and what overrides or
 * implements it, as rename cascades), the uses of renamed parameters in the bodies, and every call across the solution — arguments
 * reordered, named ones kept by name, removed ones dropped, new ones given the value at call sites; method groups, `nameof` and `cref`
 * left alone. What it cannot update is a conflict, shown before anything is written; the edits are one command (one Undo).
 */
object NativeCSharpChangeSignature {
    class Edit(val range: TextRange, val text: String)

    /** Every edit per file and what could not be updated. */
    class Plan(val edits: Map<CSharpFile, List<Edit>>, val conflicts: List<String>)

    /** The member Change Signature is about at [offset]: its header, or a call / use of it. */
    fun targetAt(file: CSharpFile, offset: Int): PsiElement? {
        NativeCSharpNavigationIntentions.headerAt(file, offset)?.let { header ->
            return header.takeIf { it is CSharpMethodDeclaration || it is CSharpConstructorDeclaration || it is CSharpLocalFunctionStatement || it is CSharpIndexerDeclaration }
        }
        val leaf = file.findElementAt(offset) ?: (if (offset > 0) file.findElementAt(offset - 1) else null) ?: return null
        PsiTreeUtil.getParentOfType(leaf, CSharpDelegateDeclaration::class.java, false)?.let { delegate ->
            if (delegate.parameterList?.let { leaf.textRange.startOffset >= it.textRange.endOffset } != true) return delegate
        }
        if (!CSharpLeaves.isIdentifier(leaf) || DumbService.isDumb(file.project)) return null
        NativeCSharpResolver(file).symbolAt(leaf)?.let { symbol ->
            return symbol.declaration.parent?.takeIf { it is CSharpLocalFunctionStatement && symbol.kind == LocalSymbolKind.LOCAL_FUNCTION }
        }
        val target = CSharpSolutionSearch.targetAt(leaf, CSharpSemanticSession(file.project)) ?: return null
        return target.primary?.takeIf { it is CSharpMethodDeclaration || it is CSharpConstructorDeclaration || it is CSharpDelegateDeclaration }
    }

    fun parametersOf(declaration: PsiElement): List<CSharpParameter> = when (declaration) {
        is CSharpBaseMethodDeclaration -> declaration.parameterList?.parameters
        is CSharpLocalFunctionStatement -> declaration.parameterList?.parameters
        is CSharpDelegateDeclaration -> declaration.parameterList?.parameters
        is CSharpIndexerDeclaration -> declaration.parameterList?.parameters
        else -> null
    }.orEmpty()

    private fun parameterList(declaration: PsiElement): CSharpBaseParameterList? = when (declaration) {
        is CSharpBaseMethodDeclaration -> declaration.parameterList
        is CSharpLocalFunctionStatement -> declaration.parameterList
        is CSharpDelegateDeclaration -> declaration.parameterList
        is CSharpIndexerDeclaration -> declaration.parameterList
        else -> null
    }

    private fun returnTypeOf(declaration: PsiElement): CSharpType? = when (declaration) {
        is CSharpMethodDeclaration -> declaration.returnType
        is CSharpLocalFunctionStatement -> declaration.returnType
        is CSharpDelegateDeclaration -> declaration.returnType
        is CSharpIndexerDeclaration -> declaration.type
        else -> null
    }

    private fun nameElementOf(declaration: PsiElement): PsiElement? = when (declaration) {
        is CSharpMethodDeclaration -> declaration.identifier
        is CSharpLocalFunctionStatement -> declaration.identifier
        is CSharpDelegateDeclaration -> declaration.identifier
        else -> null
    }

    /** The signature [declaration] has now: the starting point of the dialog. */
    fun current(declaration: PsiElement): CSharpNewSignature {
        val name = when (declaration) {
            is CSharpConstructorDeclaration -> declaration.identifier?.text.orEmpty()
            is CSharpIndexerDeclaration -> "this"
            else -> nameElementOf(declaration)?.text.orEmpty()
        }
        val parameters = parametersOf(declaration).mapIndexed { i, p ->
            CSharpSignatureParameter(p.identifier?.text.orEmpty(), p.type?.text.orEmpty(), p.modifiers.joinToString(" ") { it.text }, p.default?.value?.text, i)
        }
        return CSharpNewSignature(name, returnTypeOf(declaration)?.text, parameters)
    }

    /** What is wrong with [signature] itself, before any search; null when it can be applied. */
    fun validate(declaration: PsiElement, signature: CSharpNewSignature): String? {
        if (declaration !is CSharpConstructorDeclaration && declaration !is CSharpIndexerDeclaration && !NativeCSharpRename.isIdentifier(signature.name)) return "'${signature.name}' is not a valid name"
        val names = HashSet<String>()
        for (p in signature.parameters) {
            if (!NativeCSharpRename.isIdentifier(p.name)) return "'${p.name}' is not a valid parameter name"
            if (!names.add(p.name.removePrefix("@"))) return "Parameter '${p.name}' is declared twice"
            if (p.type.isBlank()) return "Parameter '${p.name}' has no type"
        }
        val params = signature.parameters.indexOfFirst { it.modifier.split(' ').contains("params") }
        if (params >= 0 && params != signature.parameters.lastIndex) return "A 'params' parameter must be the last one"
        val receiver = signature.parameters.indexOfFirst { it.modifier.split(' ').contains("this") }
        if (receiver > 0) return "The 'this' parameter of an extension method must be the first one"
        return null
    }

    // ---- the plan

    /** Every edit [signature] makes: in a read action, under the progress of the caller (it searches the whole solution). */
    fun plan(project: Project, declaration: PsiElement, signature: CSharpNewSignature): Plan {
        val old = current(declaration)
        val edits = LinkedHashMap<CSharpFile, MutableList<Edit>>()
        val conflicts = ArrayList<String>()
        fun edit(element: PsiElement?, range: TextRange, text: String) {
            val file = element?.containingFile as? CSharpFile ?: return
            val list = edits.getOrPut(file) { ArrayList() }
            if (list.none { it.range.intersects(range) && !(it.range.isEmpty || range.isEmpty) }) list += Edit(range, text)
        }
        val session = CSharpSemanticSession(project)
        val primaryTarget = if (declaration is CSharpLocalFunctionStatement) null else CSharpSolutionSearch.targetOf(declaration)
        val family = when {
            primaryTarget == null -> listOf(declaration)
            declaration is CSharpMethodDeclaration || declaration is CSharpIndexerDeclaration -> CSharpSolutionSearch.withHierarchy(project, primaryTarget, session).declarations
            else -> listOf(declaration)
        }
        val renamed = signature.name != old.name && declaration !is CSharpConstructorDeclaration && declaration !is CSharpIndexerDeclaration
        val parametersChanged = signature.parameters.size != old.parameters.size || signature.parameters.withIndex().any { (i, p) ->
            p.oldIndex != i || p.type != old.parameters[i].type || p.modifier != old.parameters[i].modifier
        }
        // a renamed parameter changes the calls that name it (`old: x`)
        val callsChanged = parametersChanged || signature.parameters.any { p -> p.oldIndex >= 0 && p.name != old.parameters[p.oldIndex].name }
        // the declarations: the name, the return type, the parameter list, the uses of renamed / removed parameters in the bodies
        for (member in family) {
            val memberParameters = parametersOf(member)
            if (renamed) nameElementOf(member)?.let { edit(member, it.textRange, NativeCSharpRename.written(signature.name)) }
            if (signature.returnType != null && signature.returnType != old.returnType) returnTypeOf(member)?.let { edit(member, it.textRange, signature.returnType) }
            val list = parameterList(member)
            if (list != null) {
                val open = list.firstChild
                val close = list.lastChild
                if (open != null && close != null && open != close) {
                    val inner = TextRange(open.textRange.endOffset, close.textRange.startOffset)
                    val texts = signature.parameters.map { p ->
                        val before = memberParameters.getOrNull(p.oldIndex)
                        (before?.attributeLists?.joinToString(" ") { it.text }?.takeIf { it.isNotEmpty() }?.let { "$it " } ?: "") + p.text()
                    }
                    val newText = joined(member.containingFile.viewProvider.contents, memberParameters.map { it.textRange }, texts)
                    if (newText != member.containingFile.viewProvider.contents.subSequence(inner.startOffset, inner.endOffset).toString()) edit(member, inner, newText)
                }
            }
            val file = member.containingFile as? CSharpFile ?: continue
            val resolver = NativeCSharpResolver(file)
            for ((index, parameter) in memberParameters.withIndex()) {
                val identifier = parameter.identifier ?: continue
                val symbol = resolver.symbolAt(identifier) ?: continue
                val kept = signature.parameters.firstOrNull { it.oldIndex == index }
                if (kept == null) {
                    if (symbol.references.isNotEmpty()) conflicts += "Parameter '${symbol.name}' is used in the body of ${describe(member)}; the uses stay and will not compile"
                } else if (kept.name.removePrefix("@") != symbol.name.removePrefix("@")) {
                    for (reference in symbol.references) edit(reference, reference.textRange, NativeCSharpRename.written(kept.name))
                }
            }
        }
        // the calls
        val oldNames = HashMap<String, Int>()
        for (member in family) parametersOf(member).forEachIndexed { i, p -> p.identifier?.text?.removePrefix("@")?.let { oldNames.putIfAbsent(it, i) } }
        val oldParams = old.parameters.map { it.modifier.split(' ').contains("params") }
        val extension = old.parameters.firstOrNull()?.modifier?.split(' ')?.contains("this") == true
        val ownerName = CSharpSolutionSearch.ownerType(declaration)?.let(CSharpDeclarationNames::name)
        fun rewrite(list: CSharpBaseArgumentList?, anchor: PsiElement, receiverStyle: Boolean) {
            if (list == null) {
                // `new T { … }` without parentheses: arguments only when the new signature needs some
                val texts = arguments(emptyList(), signature, old, oldNames, oldParams, receiverStyle, conflicts, anchor) ?: return
                if (texts.isNotEmpty() && anchor.parent != null) edit(anchor, TextRange(anchor.textRange.endOffset, anchor.textRange.endOffset), "(" + texts.joinToString(", ") + ")")
                return
            }
            val open = list.firstChild
            val close = list.lastChild
            if (open == null || close == null || open == close) return
            val texts = arguments(list.arguments, signature, old, oldNames, oldParams, receiverStyle, conflicts, anchor) ?: return
            val text = list.containingFile.viewProvider.contents
            val inner = TextRange(open.textRange.endOffset, close.textRange.startOffset)
            val newText = joined(text, list.arguments.map { it.textRange }, texts)
            if (newText != text.subSequence(inner.startOffset, inner.endOffset).toString()) edit(list, inner, newText)
        }
        fun call(leaf: PsiElement) {
            val parent = leaf.parent
            // `base(…)` / `this(…)` / target-typed `new(…)`
            if (parent is CSharpConstructorInitializer && parent.thisOrBaseKeyword == leaf) return rewrite(parent.argumentList, leaf, false)
            if (parent is CSharpImplicitObjectCreationExpression && parent.newKeyword == leaf) return rewrite(parent.argumentList, leaf, false)
            val name = parent as? CSharpSimpleName ?: return
            if (renamed && declaration !is CSharpConstructorDeclaration) edit(leaf, leaf.textRange, NativeCSharpRename.written(signature.name))
            if (PsiTreeUtil.getParentOfType(leaf, CSharpCref::class.java) != null || inNameof(leaf)) return
            if (declaration is CSharpConstructorDeclaration) {
                var top: PsiElement = name
                while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
                val creation = top.parent as? CSharpObjectCreationExpression ?: return
                if (creation.type != top) return
                if (!callsChanged) return
                return rewrite(creation.argumentList, top, false)
            }
            var expression: PsiElement = name
            val owner = name.parent
            if (owner is CSharpMemberAccessExpression && owner.nameElement == name || owner is CSharpMemberBindingExpression && owner.nameElement == name) expression = owner
            val invocation = expression.parent as? CSharpInvocationExpression
            if (invocation == null || invocation.expression != expression) {
                if (parametersChanged) conflicts += "The method group '${leaf.text}' at ${where(leaf)} may no longer fit the delegate it is converted to"
                return
            }
            if (!callsChanged) return
            // `x.Twice()` of an extension method `Twice(this int x)`: the receiver is the first parameter, not an argument
            val receiverStyle = extension && when (expression) {
                is CSharpMemberAccessExpression -> expression.expression?.text?.substringAfterLast('.') != ownerName
                is CSharpMemberBindingExpression -> true
                else -> false
            }
            rewrite(invocation.argumentList, leaf, receiverStyle)
        }
        when {
            declaration is CSharpLocalFunctionStatement -> {
                val file = declaration.containingFile as? CSharpFile
                val identifier = declaration.identifier
                val symbol = if (file != null && identifier != null) NativeCSharpResolver(file).symbolAt(identifier) else null
                symbol?.references?.forEach { call(it) }
            }
            declaration is CSharpIndexerDeclaration || declaration is CSharpDelegateDeclaration -> {
                if (parametersChanged) conflicts += "The calls of ${describe(declaration)} are not updated: only its declaration${if (family.size > 1) " and its hierarchy" else ""} changes"
                if (declaration is CSharpDelegateDeclaration && renamed && primaryTarget != null) {
                    CSharpSolutionSearch.processUsages(project, primaryTarget, GlobalSearchScope.projectScope(project), session) { usage ->
                        if (!usage.implicit) edit(usage.leaf, usage.leaf.textRange, NativeCSharpRename.written(signature.name))
                        true
                    }
                }
            }
            primaryTarget != null -> {
                val whole = if (family.size > 1) CSharpSearchTarget(primaryTarget.name, primaryTarget.kind, family, primaryTarget.library) else primaryTarget
                CSharpSolutionSearch.processUsages(project, whole, GlobalSearchScope.projectScope(project), session) { usage -> call(usage.leaf); true }
            }
        }
        return Plan(edits.mapValues { (_, list) -> list.sortedBy { it.range.startOffset } }, conflicts.distinct())
    }

    private fun inNameof(leaf: PsiElement): Boolean {
        var current: PsiElement? = leaf.parent
        while (current != null && current !is CSharpStatement && current !is CSharpMemberDeclaration) {
            if (current is CSharpInvocationExpression && (current.expression as? CSharpIdentifierName)?.identifier?.text == "nameof") return true
            current = current.parent
        }
        return false
    }

    /**
     * The arguments of a call for [signature]: each old argument by the parameter it was for (position, or its name), named ones kept named
     * (the new name), a parameter left out — its default used — makes the next ones named, a new parameter gets its value at call sites.
     * Null (and a conflict) when the call cannot be mapped.
     */
    private fun arguments(
        arguments: List<CSharpArgument>, signature: CSharpNewSignature, old: CSharpNewSignature, oldNames: Map<String, Int>, oldParams: List<Boolean>,
        receiverStyle: Boolean, conflicts: MutableList<String>, anchor: PsiElement,
    ): List<String>? {
        class Passed(val text: String, val named: Boolean)
        val passed = HashMap<Int, Passed>()
        val group = ArrayList<String>()
        val offset = if (receiverStyle) 1 else 0
        var position = 0
        for (argument in arguments) {
            val value = argument.text.let { text -> argument.nameColon?.let { text.substring(it.textRange.endOffset - argument.textRange.startOffset).trimStart() } ?: text }
            val name = argument.nameColon?.nameElement?.identifier?.text?.removePrefix("@")
            if (name != null) {
                val index = oldNames[name] ?: return null.also { conflicts += "The argument '$name:' at ${where(anchor)} names no parameter" }
                passed[index] = Passed(value, true)
                position = index + 1 - offset
                continue
            }
            val index = position + offset
            if (index >= old.parameters.size || index == old.parameters.lastIndex && oldParams[index]) {
                if (oldParams.lastOrNull() != true) return null.also { conflicts += "The call at ${where(anchor)} has more arguments than parameters" }
                group += value
            } else passed[index] = Passed(value, false)
            position++
        }
        if (group.isNotEmpty()) passed[old.parameters.lastIndex] = Passed(group.joinToString(", "), false)
        if (receiverStyle && signature.parameters.firstOrNull()?.oldIndex != 0) return null.also { conflicts += "The call at ${where(anchor)} passes the receiver of the extension method, which is no longer the first parameter" }
        if (group.size > 1 && signature.parameters.lastOrNull()?.oldIndex != old.parameters.lastIndex) return null.also { conflicts += "The call at ${where(anchor)} passes several values to the 'params' parameter, which moved" }
        val out = ArrayList<String>()
        var named = false
        for ((i, p) in signature.parameters.withIndex()) {
            if (receiverStyle && i == 0) continue
            val name = NativeCSharpRename.written(p.name)
            if (p.oldIndex >= 0) {
                val argument = passed[p.oldIndex]
                if (argument == null) { named = true; continue }
                // after a named argument the next ones are named too: the order may have changed under them
                if (argument.named) named = true
                out += if (named) "$name: ${argument.text}" else argument.text
            } else {
                if (p.callValue.isBlank() && !p.defaultValue.isNullOrBlank()) { named = true; continue }
                val value = p.callValue.ifBlank { "default" }
                out += if (named) "$name: $value" else value
            }
        }
        return out
    }

    /** [texts] joined as the old items were: on one line, or one per line with the indentation of the second one. */
    private fun joined(text: CharSequence, old: List<TextRange>, texts: List<String>): String {
        if (old.size >= 2) {
            val between = text.subSequence(old[0].endOffset, old[1].startOffset).toString()
            if ('\n' in between) return texts.joinToString(",\n" + between.substringAfterLast('\n'))
        }
        return texts.joinToString(", ")
    }

    private fun describe(member: PsiElement): String {
        val owner = CSharpSolutionSearch.ownerType(member)?.let(CSharpDeclarationNames::name)
        val name = when (member) {
            is CSharpIndexerDeclaration -> "this[]"
            is CSharpConstructorDeclaration -> member.identifier?.text
            else -> nameElementOf(member)?.text
        } ?: "?"
        return "'" + (if (owner != null && member !is CSharpLocalFunctionStatement) "$owner.$name" else name) + "'"
    }

    private fun where(element: PsiElement): String {
        val file = element.containingFile ?: return "?"
        val line = PsiDocumentManager.getInstance(element.project).getDocument(file)?.getLineNumber(element.textRange.startOffset)?.plus(1)
        return "${file.name}:${line ?: "?"}"
    }

    // ---- applying

    /** Writes [plan] in one command; asks about its conflicts first. False when nothing was written. */
    fun apply(project: Project, plan: Plan): Boolean {
        if (plan.conflicts.isNotEmpty() && !confirm(project, plan.conflicts)) return false
        val documents = PsiDocumentManager.getInstance(project)
        WriteCommandAction.writeCommandAction(project, plan.edits.keys.toList()).withName("Change Signature").run<RuntimeException> {
            for ((file, edits) in plan.edits) {
                val document = documents.getDocument(file) ?: continue
                for (edit in edits.asReversed()) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
                documents.commitDocument(document)
            }
        }
        return true
    }

    private fun confirm(project: Project, conflicts: List<String>): Boolean {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            if (BaseRefactoringProcessor.ConflictsInTestsException.isTestIgnore()) return true
            throw BaseRefactoringProcessor.ConflictsInTestsException(conflicts)
        }
        return Messages.showYesNoDialog(project, conflicts.joinToString("\n") + "\n\nContinue?", "Change Signature: Conflicts", Messages.getWarningIcon()) == Messages.YES
    }

    /** The dialog, the search under a progress (never on the EDT), the conflicts, the edits. */
    fun invoke(project: Project, declaration: PsiElement) {
        val dialog = CSharpChangeSignatureDialog(project, declaration)
        if (!dialog.showAndGet()) return
        val signature = dialog.signature()
        validate(declaration, signature)?.let { Messages.showErrorDialog(project, it, "Change Signature"); return }
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<Plan, RuntimeException>({
            ReadAction.compute<Plan, RuntimeException> { plan(project, declaration, signature) }
        }, "Searching for Usages", true, project)
        apply(project, plan)
    }
}

/** Rider's dialog of Change Signature: the name, the return type, the parameters (add, remove, move) and the preview of the result. */
class CSharpChangeSignatureDialog(project: Project, private val declaration: PsiElement) : DialogWrapper(project, true) {
    private class Row(var modifier: String, var type: String, var name: String, var defaultValue: String, var callValue: String, val oldIndex: Int)

    private val initial = NativeCSharpChangeSignature.current(declaration)
    private val nameField = JBTextField(initial.name).apply { isEnabled = declaration !is CSharpConstructorDeclaration && declaration !is CSharpIndexerDeclaration }
    private val returnField = JBTextField(initial.returnType.orEmpty()).apply { isEnabled = initial.returnType != null }
    private val preview = JBLabel()
    private val model = ListTableModel<Row>(
        column("Modifier", { it.modifier }) { r, v -> r.modifier = v },
        column("Type", { it.type }) { r, v -> r.type = v },
        column("Name", { it.name }) { r, v -> r.name = v },
        column("Default Value", { it.defaultValue }) { r, v -> r.defaultValue = v },
        column("Value at Call Sites", { it.callValue }) { r, v -> r.callValue = v },
    ).apply { initial.parameters.forEach { addRow(Row(it.modifier, it.type, it.name, it.defaultValue.orEmpty(), "", it.oldIndex)) } }
    private val table = JBTable(model)

    init {
        title = "Change Signature"
        model.addTableModelListener { updatePreview() }
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updatePreview()
            override fun removeUpdate(e: DocumentEvent) = updatePreview()
            override fun changedUpdate(e: DocumentEvent) = updatePreview()
        }
        nameField.document.addDocumentListener(listener)
        returnField.document.addDocumentListener(listener)
        init()
        updatePreview()
    }

    private fun column(title: String, get: (Row) -> String, set: (Row, String) -> Unit) = object : ColumnInfo<Row, String>(title) {
        override fun valueOf(item: Row): String = get(item)
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: String) = set(item, value)
    }

    private fun updatePreview() {
        preview.text = signature().preview()
    }

    fun signature(): CSharpNewSignature = CSharpNewSignature(
        nameField.text.trim(), returnField.text.trim().takeIf { initial.returnType != null },
        model.items.map { CSharpSignatureParameter(it.name.trim(), it.type.trim(), it.modifier.trim(), it.defaultValue.trim().ifEmpty { null }, it.oldIndex, it.callValue.trim()) },
    )

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { model.addRow(Row("", "object", "parameter${model.rowCount + 1}", "", "", -1)); table.editCellAt(model.rowCount - 1, 1) }
            .createPanel().apply { preferredSize = Dimension(640, 220) }
        return panel {
            row("Name:") { cell(nameField).align(AlignX.FILL) }
            row("Return type:") { cell(returnField).align(AlignX.FILL) }
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row("Preview:") { cell(preview) }
        }
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        NativeCSharpChangeSignature.validate(declaration, signature())?.let { com.intellij.openapi.ui.ValidationInfo(it) }
}

/** Ctrl+F6 on a C# member: the platform's Change Signature action finds this handler through [CSharpRefactoringSupport]. */
class CSharpChangeSignatureHandler : ChangeSignatureHandler {
    override fun findTargetMember(element: PsiElement): PsiElement? {
        val file = element.containingFile as? CSharpFile ?: return null
        return NativeCSharpChangeSignature.targetAt(file, element.textRange.startOffset)
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val target = (file as? CSharpFile)?.let { NativeCSharpChangeSignature.targetAt(it, editor.caretModel.offset) }
        if (target == null) {
            Messages.showErrorDialog(project, targetNotFoundMessage, "Change Signature")
            return
        }
        NativeCSharpChangeSignature.invoke(project, target)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val target = elements.firstNotNullOfOrNull { findTargetMember(it) } ?: return
        NativeCSharpChangeSignature.invoke(project, target)
    }

    override fun getTargetNotFoundMessage(): String = "The caret should be on a method, constructor, local function, delegate or indexer"
}

/** The refactorings of the platform for C#: only Change Signature so far (Jellyfin № 25, 0.1.174). */
class CSharpRefactoringSupport : RefactoringSupportProvider() {
    override fun getChangeSignatureHandler(): ChangeSignatureHandler = CSharpChangeSignatureHandler()
}

/** Alt+Enter on the header or a call of a member: "Change signature…" (Rider; Refactor This lists it as "Change Signature"). */
class NativeCSharpChangeSignatureIntention : IntentionAction, PriorityAction, DumbAware {
    override fun getText(): String = "Change signature…"
    override fun getFamilyName(): String = "Change signature"
    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null || DumbService.isDumb(project)) return false
        if (!CSharpFeatures.native(CSharpFeature.CONTEXT_ACTIONS, file)) return false
        return NativeCSharpNavigationIntentions.headerAt(file, editor.caretModel.offset)
            ?.let { it is CSharpMethodDeclaration || it is CSharpConstructorDeclaration || it is CSharpLocalFunctionStatement || it is CSharpIndexerDeclaration } == true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val target = NativeCSharpChangeSignature.targetAt(file, editor.caretModel.offset) ?: return
        NativeCSharpChangeSignature.invoke(project, target)
    }
}
