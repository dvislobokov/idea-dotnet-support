package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticProblem
import io.github.dotnetsupport.lang.semantic.CSharpSymbol

/**
 * The quick fixes of the warnings of task D2, as Rider's: "Remove unused variable" on CS0168 / CS0219 of a local nothing refers to, "Add
 * 'await'" on CS4014. The edits are pure functions of the tree ([removeVariable], [addAwait]), tested without an editor.
 */
object NativeCSharpWarningFixes {
    fun fixesFor(file: CSharpFile, problem: CSharpSemanticProblem): List<IntentionAction> {
        val fix: (SmartPsiElementPointer<PsiElement>) -> IntentionAction = when (problem.code) {
            "CS0168", "CS0219" -> ::RemoveUnusedVariableFix
            "CS4014" -> ::AddAwaitFix
            else -> return emptyList()
        }
        // a pointer, not the offset: an edit elsewhere (another fix) moves the text between the highlighting and the invocation
        val leaf = file.findElementAt(problem.range.startOffset) ?: return emptyList()
        return listOf(fix(SmartPointerManager.createPointer(leaf)))
    }

    /** The declarator whose name is at [offset], when nothing refers to the local: its removal (the whole statement for the only one). */
    fun removeVariable(file: CSharpFile, offset: Int): CSharpTextEdit? {
        val leaf = file.findElementAt(offset) ?: return null
        val declarator = leaf.parent as? CSharpVariableDeclarator ?: return null
        if (declarator.identifier != leaf) return null
        val symbol = NativeCSharpScopes.of(file).symbolAt(leaf) ?: return null
        if (symbol.references.isNotEmpty()) return null
        val declaration = declarator.parent as? CSharpVariableDeclaration ?: return null
        val statement = declaration.parent as? CSharpLocalDeclarationStatement ?: return null
        val text = file.viewProvider.document?.charsSequence ?: file.text
        if (declaration.variables.size == 1) return CSharpTextEdit(NativeCSharpContextEdits.lineRange(text, statement.textRange), "")
        // `int a, b;`: the declarator with the comma before it (after it, for the first)
        val index = declaration.variables.indexOf(declarator)
        val separators = declaration.variablesSeparators
        val range = if (index > 0) TextRange(separators[index - 1].textRange.startOffset, declarator.textRange.endOffset)
        else TextRange(declarator.textRange.startOffset, declaration.variables[1].textRange.startOffset)
        return CSharpTextEdit(range, "")
    }

    /** `await ` before the call at [offset]. */
    fun addAwait(file: CSharpFile, offset: Int): CSharpTextEdit? {
        val statement = PsiTreeUtil.getParentOfType(file.findElementAt(offset), CSharpExpressionStatement::class.java) ?: return null
        val call = statement.expression as? CSharpInvocationExpression ?: return null
        if (call.textRange.startOffset != offset) return null
        return CSharpTextEdit(TextRange(offset, offset), "await ", "await ".length)
    }

    private abstract class Fix(private val title: String, private val at: SmartPsiElementPointer<PsiElement>) : IntentionAction, PriorityAction {
        private val offset: Int get() = at.range?.startOffset ?: -1

        override fun getText(): String = title
        override fun getFamilyName(): String = title
        override fun startInWriteAction(): Boolean = true
        override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is CSharpFile && offset >= 0 && edit(file, offset) != null

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            if (file !is CSharpFile) return
            val edit = offset.takeIf { it >= 0 }?.let { edit(file, it) } ?: return
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        abstract fun edit(file: CSharpFile, offset: Int): CSharpTextEdit?
    }

    private class RemoveUnusedVariableFix(at: SmartPsiElementPointer<PsiElement>) : Fix("Remove unused variable", at) {
        override fun edit(file: CSharpFile, offset: Int): CSharpTextEdit? = removeVariable(file, offset)
    }

    private class AddAwaitFix(at: SmartPsiElementPointer<PsiElement>) : Fix("Add 'await'", at) {
        override fun edit(file: CSharpFile, offset: Int): CSharpTextEdit? = addAwait(file, offset)
    }

    /** How many positional arguments get a name from the one at [offset] on (itself and the positional ones after it). */
    fun positionalTail(file: CSharpFile, offset: Int): Int {
        val leaf = file.findElementAt(offset) ?: return 0
        PsiTreeUtil.getParentOfType(leaf, CSharpArgument::class.java, CSharpAttributeArgument::class.java)?.let { argument ->
            return when (val list = argument.parent) {
                is CSharpArgumentList -> list.arguments.drop(list.arguments.indexOf(argument)).takeWhile { it.nameColon == null }.size
                is CSharpAttributeArgumentList -> list.arguments.drop(list.arguments.indexOf(argument)).takeWhile { it.nameColon == null && it.nameEquals == null }.size
                else -> 0
            }
        }
        return 0
    }

    /**
     * `name:` before the positional argument of an attribute at [offset] and the positional ones after it (`[GeneratedRegex(pattern: @"…")]`):
     * by the constructors of the attribute's type that take that many positional arguments, when they all name the parameters alike.
     */
    private fun attributeArgumentNames(file: CSharpFile, offset: Int, resolver: CSharpNameResolver): CSharpTextEdit? {
        val argument = PsiTreeUtil.getParentOfType(file.findElementAt(offset), CSharpAttributeArgument::class.java) ?: return null
        val list = argument.parent as? CSharpAttributeArgumentList ?: return null
        val attribute = list.parent as? CSharpAttribute ?: return null
        val arguments = list.arguments
        val index = arguments.indexOf(argument)
        if (arguments.take(index + 1).any { it.nameColon != null || it.nameEquals != null }) return null
        val positional = arguments.takeWhile { it.nameColon == null && it.nameEquals == null }
        val name = attribute.nameElement ?: return null
        val leaf = PsiTreeUtil.getDeepestLast(name).takeIf { CSharpLeaves.isIdentifier(it) } ?: return null
        val type = when (val symbol = resolver.resolve(leaf)?.symbols?.firstOrNull()) {
            is CSharpSymbol.SourceType -> resolver.selfType(symbol.info)
            is CSharpSymbol.LibraryType -> io.github.dotnetsupport.lang.semantic.SemanticType.Library(symbol.type, emptyList())
            else -> return null
        }
        val lists = NativeCSharpParameterInfo.constructorsOf(type).mapNotNull { resolver.parameterNames(it, false) }
            .filter { parameters -> parameters.size >= positional.size && parameters.take(positional.size).none { it.second } }
        val names = lists.map { parameters -> parameters.take(positional.size).map { it.first } }.distinct().singleOrNull() ?: return null
        if (names.any { it.isEmpty() }) return null
        val tail = positional.drop(index)
        if (tail.isEmpty()) return null
        val text = file.text
        val start = tail.first().textRange.startOffset
        val builder = StringBuilder()
        var at = start
        for ((i, a) in tail.withIndex()) {
            builder.append(text, at, a.textRange.startOffset).append(names[index + i]).append(": ")
            at = a.textRange.startOffset
        }
        builder.append(text, at, tail.last().textRange.endOffset)
        return CSharpTextEdit(TextRange(start, tail.last().textRange.endOffset), builder.toString(), names[index].length + 2)
    }

    /**
     * Rider's "Add argument name": `name:` before the positional argument at [offset] and the positional ones after it, for a call the
     * resolver ties to one method. Not inside a `params` tail, nor after a named argument.
     */
    fun addArgumentNames(file: CSharpFile, offset: Int, resolver: CSharpNameResolver): CSharpTextEdit? {
        attributeArgumentNames(file, offset, resolver)?.let { return it }
        val argument = PsiTreeUtil.getParentOfType(file.findElementAt(offset), CSharpArgument::class.java) ?: return null
        val list = argument.parent as? CSharpArgumentList ?: return null
        val call = list.parent as? CSharpInvocationExpression ?: return null
        val arguments = list.arguments
        val index = arguments.indexOf(argument)
        if (arguments.take(index + 1).any { it.nameColon != null }) return null
        val callee = when (val e = call.expression) {
            is CSharpSimpleName -> e
            is CSharpMemberAccessExpression -> e.nameElement
            is CSharpMemberBindingExpression -> e.nameElement
            else -> null
        } ?: return null
        val leaf = callee.identifier ?: return null
        val symbol = resolver.resolve(leaf)?.single ?: return null
        if (symbol !is CSharpSymbol.SourceMember && symbol !is CSharpSymbol.LibraryMember) return null
        val reduced = call.expression is CSharpMemberAccessExpression && resolver.isExtension(symbol)
        val parameters = resolver.parameterNames(symbol, reduced) ?: return null
        val tail = arguments.drop(index).takeWhile { it.nameColon == null }
        val names = tail.indices.map { i -> parameters.getOrNull(index + i)?.takeIf { (_, isParams) -> !isParams }?.first ?: return null }
        if (tail.size + index > parameters.size) return null
        val start = tail.first().textRange.startOffset
        val text = file.text
        val builder = StringBuilder()
        var at = start
        for ((i, a) in tail.withIndex()) {
            builder.append(text, at, a.textRange.startOffset).append(names[i]).append(": ")
            at = a.textRange.startOffset
        }
        builder.append(text, at, tail.last().textRange.endOffset)
        return CSharpTextEdit(TextRange(start, tail.last().textRange.endOffset), builder.toString(), names[0].length + 2)
    }
}

/**
 * Alt+Enter on a positional argument: its parameter's name before it, and before the positional arguments after it (Rider: "Add argument
 * name"). The caret may be anywhere in the argument, a string literal included, and the argument may be one of an attribute
 * (`[GeneratedRegex(@"…")]`, Jellyfin p22, 0.1.170). One name to add is in the title, as Rider's "Add argument name 'pattern'".
 */
class NativeCSharpAddArgumentNameIntention : NativeCSharpContextAction("Add argument name", serverHasIt = true, needsTypes = true) {
    @Volatile private var title = "Add argument name"

    override fun getText(): String = title

    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpWarningFixes.addArgumentNames(file, editor.caretModel.offset, resolver(file))?.also { edit ->
            val names = NativeCSharpWarningFixes.positionalTail(file, editor.caretModel.offset)
            title = if (names == 1) "Add argument name '${edit.text.substringBefore(':')}'" else "Add argument name"
        }
}
