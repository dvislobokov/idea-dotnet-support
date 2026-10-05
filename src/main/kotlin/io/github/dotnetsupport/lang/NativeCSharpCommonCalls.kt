package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lsp.RoslynServerStatus

/**
 * The common calls of the roadmap («Подсказки для частых вызовов»), the syntactic ones: what is written where a task is returned and
 * where `await` is typed.
 *  - `return |` in a method or local function that returns `Task<T>` / `ValueTask<T>` and is not `async`: `Task.FromResult(|)` /
 *    `ValueTask.FromResult(|)` first in the list and as gray text ([CSharpGhostText]); of `Task` / `ValueTask`: `Task.CompletedTask` /
 *    `ValueTask.CompletedTask` (and `default` for `ValueTask`). In an `async` one nothing of the kind: there a value is returned.
 *  - `await` in a function that is not `async`: the keyword item of the native list and the intention "Make method async" add `async`
 *    to the header and make the return type a task (`void` → `Task`, `int` → `Task<int>`), as Rider and Roslyn's fix do.
 */
object NativeCSharpCommonCalls {
    /** `Task<T>`, `ValueTask<T>` (qualified or not): group 1 the task, group 2 the `T`. */
    val TASK_OF = Regex("""^(?:global::)?(?:System\.Threading\.Tasks\.)?(Task|ValueTask)<(.+)>$""")
    private val TASK = Regex("""^(?:global::)?(?:System\.Threading\.Tasks\.)?(Task|ValueTask)$""")

    /** What a non-`async` function of [returnType] returns with no value at hand: the call and the text it inserts, the caret offset in it. */
    class TaskReturn(val lookup: String, val text: String, val caret: Int)

    fun taskReturns(returnType: String): List<TaskReturn> {
        TASK_OF.matchEntire(returnType)?.let { match ->
            val task = match.groupValues[1]
            return listOf(TaskReturn("$task.FromResult", "$task.FromResult()", "$task.FromResult(".length))
        }
        TASK.matchEntire(returnType)?.let { match ->
            val task = match.groupValues[1]
            val completed = TaskReturn("$task.CompletedTask", "$task.CompletedTask", "$task.CompletedTask".length)
            return if (task == "ValueTask") listOf(completed, TaskReturn("default", "default", "default".length)) else listOf(completed)
        }
        return emptyList()
    }

    /** The function whose body [element] is in: a method, a local function, a lambda or anonymous method, an accessor, a top-level statement. */
    fun function(element: PsiElement): PsiElement? {
        var current: PsiElement? = element.parent
        while (current != null && current !is CSharpFile) {
            when (current) {
                is CSharpAnonymousFunctionExpression, is CSharpLocalFunctionStatement, is CSharpBaseMethodDeclaration, is CSharpAccessorDeclaration,
                is CSharpGlobalStatement -> return current
                is CSharpMemberDeclaration -> return current
            }
            current = current.parent
        }
        return null
    }

    /** The return type as written of a method or local function; null for anything else (a lambda's is not written). */
    fun returnType(function: PsiElement): String? = when (function) {
        is CSharpMethodDeclaration -> function.returnType
        is CSharpLocalFunctionStatement -> function.returnType
        else -> null
    }?.text?.let(CSharpStubsText::collapse)

    fun isAsync(function: PsiElement): Boolean = modifiers(function).any { it.text == "async" }

    fun returnsNothing(function: PsiElement): Boolean = when (function) {
        is CSharpMethodDeclaration, is CSharpLocalFunctionStatement -> returnType(function) == "void" || isAsync(function) && TASK.matches(returnType(function).orEmpty())
        is CSharpConstructorDeclaration, is CSharpDestructorDeclaration -> true
        is CSharpAccessorDeclaration -> function.keyword?.text in setOf("set", "init", "add", "remove")
        else -> false
    }

    private fun modifiers(function: PsiElement): List<PsiElement> = when (function) {
        is CSharpMemberDeclaration -> function.modifiers
        is CSharpLocalFunctionStatement -> function.modifiers
        is CSharpAnonymousFunctionExpression -> function.modifiers
        else -> emptyList()
    }

    /** The items of `return |` (see the class comment); empty elsewhere. */
    fun items(place: NativeCSharpCompletionPlace): List<LookupElement> {
        val name = place.name ?: return emptyList()
        val holder = name.parent
        if (holder !is CSharpReturnStatement && !(holder is CSharpArrowExpressionClause && holder.parent !is CSharpAccessorDeclaration)) return emptyList()
        val function = function(name) ?: return emptyList()
        if (isAsync(function)) return emptyList()
        val type = returnType(function) ?: return emptyList()
        return taskReturns(type).mapIndexed { index, task ->
            var builder = LookupElementBuilder.create(task.lookup).withIcon(AllIcons.Nodes.Method).bold()
            if (task.lookup.contains('.')) builder = builder.withLookupStrings(setOf(task.lookup, task.lookup.substringAfter('.')))
            builder = builder.withInsertHandler(InsertHandler { context, _ ->
                val document = context.document
                val start = context.startOffset
                document.replaceString(start, context.tailOffset, task.text)
                val end = start + task.text.length
                val rest = CSharpCalls.restOfLine(document.charsSequence, end)
                val statement = holder is CSharpReturnStatement && rest.isBlank() && context.completionChar != ';'
                if (statement) document.insertString(end, ";")
                context.editor.caretModel.moveToOffset(start + task.caret)
                context.commitDocument()
            })
            builder.putUserData(NativeCSharpCompletion.NATIVE, true)
            PrioritizedLookupElement.withPriority(builder, NativeCSharpCompletion.COMMON_CALL - index).also { it.putUserData(NativeCSharpCompletion.NATIVE, true) }
        }
    }

    /**
     * The gray text of `return |` on a line of its own ([CSharpGhostText]): `Task.FromResult();` / `Task.CompletedTask;` for a non-`async`
     * method or local function of a task. [tree] is the file with [offset] right after `return `.
     */
    fun ghost(tree: CSharpFile, offset: Int): String? {
        val keyword = tree.findElementAt(offset - 1)?.let { if (it.text.isBlank()) NativeCSharpCompletionPlace.previousToken(it) else it } ?: return null
        if (keyword.text != "return") return null
        val statement = keyword.parent as? CSharpReturnStatement ?: return null
        if (statement.expression != null && statement.expression!!.textLength > 0) return null
        val function = function(statement) ?: return null
        if (isAsync(function)) return null
        val type = returnType(function) ?: return null
        val task = taskReturns(type).firstOrNull() ?: return null
        return task.text + ";"
    }

    // ---- await and async

    enum class AwaitState { ASYNC, FIXABLE, NO }

    /** Whether `await` may stand at [element]: the function is `async` (or the top-level statements), can be made so, or neither (an accessor, a constructor, `lock`). */
    fun awaitState(element: PsiElement): AwaitState {
        var current: PsiElement? = element.parent
        while (current != null && current !is CSharpFile) {
            when (current) {
                is CSharpLockStatement -> if (current.statement?.let { PsiTreeUtil.isAncestor(it, element, false) } == true) return AwaitState.NO
                is CSharpGlobalStatement -> return AwaitState.ASYNC
                is CSharpAnonymousFunctionExpression, is CSharpLocalFunctionStatement, is CSharpMethodDeclaration ->
                    return if (isAsync(current)) AwaitState.ASYNC else AwaitState.FIXABLE
                is CSharpMemberDeclaration, is CSharpAccessorDeclaration -> return AwaitState.NO
            }
            current = current.parent
        }
        return AwaitState.NO
    }

    /** The function of [element] that `await` there needs to be `async`, when it is not yet. */
    fun functionToMakeAsync(element: PsiElement): PsiElement? {
        if (awaitState(element) != AwaitState.FIXABLE) return null
        return generateSequence(element.parent) { it.parent }.takeWhile { it !is CSharpFile }
            .firstOrNull { it is CSharpAnonymousFunctionExpression || it is CSharpLocalFunctionStatement || it is CSharpMethodDeclaration }
    }

    /** The edits that make [function] `async`: the modifier before the return type (or the lambda) and the return type a task. Ascending offsets. */
    fun makeAsync(function: PsiElement): List<Pair<TextRange, String>> {
        val edits = ArrayList<Pair<TextRange, String>>()
        val returnType = when (function) {
            is CSharpMethodDeclaration -> function.returnType
            is CSharpLocalFunctionStatement -> function.returnType
            else -> null
        }
        val anchor = returnType?.textRange?.startOffset ?: when (function) {
            is CSharpAnonymousFunctionExpression -> function.textRange.startOffset.let { start ->
                // after the attributes of a lambda, before its `static` or its parameters
                (function as? CSharpLambdaExpression)?.attributeLists?.lastOrNull()?.textRange?.endOffset?.let { it + 1 }?.coerceAtMost(function.textRange.endOffset) ?: start
            }
            else -> return emptyList()
        }
        edits += TextRange(anchor, anchor) to "async "
        if (returnType != null) {
            val text = CSharpStubsText.collapse(returnType.text)
            val task = when {
                text == "void" -> if (isEventHandler(function)) null else "Task"
                TASK.matches(text) || TASK_OF.matches(text) || text.startsWith("IAsyncEnumerable") || text.startsWith("IAsyncEnumerator") -> null
                else -> "Task<$text>"
            }
            if (task != null) edits += returnType.textRange to task
        }
        return edits
    }

    /** `void OnClick(object sender, EventArgs e)`: stays `async void`, as Roslyn's fix leaves an event handler. */
    private fun isEventHandler(function: PsiElement): Boolean {
        val parameters = (function as? CSharpMethodDeclaration)?.parameterList?.parameters ?: return false
        return parameters.size == 2 && parameters[0].type?.text?.removeSuffix("?") == "object" && parameters[1].type?.text?.endsWith("EventArgs") == true
    }

    /** Applies [makeAsync] to the function at [offset] of [file] (committed first), keeping the caret where it is in the text. */
    fun makeAsyncAt(file: PsiFile, offset: Int, editor: Editor) {
        val document = editor.document
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        val leaf = file.findElementAt(offset) ?: return
        val function = functionToMakeAsync(leaf) ?: return
        apply(document, makeAsync(function), editor)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
    }

    fun apply(document: Document, edits: List<Pair<TextRange, String>>, editor: Editor?) {
        var caret = editor?.caretModel?.offset
        for ((range, text) in edits.sortedWith(compareByDescending<Pair<TextRange, String>> { it.first.startOffset }.thenByDescending { it.first.endOffset })) {
            document.replaceString(range.startOffset, range.endOffset, text)
            if (caret != null && caret >= range.endOffset) caret += text.length - range.length
        }
        if (caret != null) editor?.caretModel?.moveToOffset(caret)
    }
}

/**
 * Alt+Enter on `await` (also of `await using` and `await foreach`) in a method, local function or lambda that is not `async`: "Make method
 * async" — `async` into the header and the return type a task ([NativeCSharpCommonCalls.makeAsync]). On the native tree; while the
 * language server is ready and completion is its, its own fix (CS4032 / CS4033) is offered instead, and when completion is NATIVE the
 * server's row of the same title is dropped ([NativeCSharpServerActions]), so the two do not stand side by side.
 */
class NativeCSharpMakeAsyncIntention : IntentionAction, PriorityAction {
    override fun getText(): String = "Make method async"
    override fun getFamilyName(): String = "Make method async"
    override fun startInWriteAction(): Boolean = true
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null) return false
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, project) && RoslynServerStatus.isReady(project)) return false
        return awaitAt(file, editor.caretModel.offset)?.let(NativeCSharpCommonCalls::functionToMakeAsync) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is CSharpFile) return
        val function = awaitAt(file, editor.caretModel.offset)?.let(NativeCSharpCommonCalls::functionToMakeAsync) ?: return
        NativeCSharpCommonCalls.apply(editor.document, NativeCSharpCommonCalls.makeAsync(function), editor)
    }

    /** The `await` keyword at or right before the caret: of an expression, of `await using` (statement or declaration), of `await foreach`. */
    private fun awaitAt(file: CSharpFile, offset: Int): PsiElement? =
        listOfNotNull(file.findElementAt(offset), file.findElementAt(offset - 1)).firstOrNull { it.text == "await" && isAwait(it) }

    private fun isAwait(keyword: PsiElement): Boolean = when (val parent = keyword.parent) {
        is CSharpAwaitExpression -> true
        is CSharpUsingStatement -> parent.awaitKeyword == keyword
        is CSharpLocalDeclarationStatement -> parent.awaitKeyword == keyword
        is CSharpCommonForEachStatement -> parent.awaitKeyword == keyword
        else -> false
    }
}
