package io.github.dotnetsupport.debugger

import com.google.gson.JsonObject
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase
import io.github.dotnetsupport.lang.CSharpDebugCompletion
import io.github.dotnetsupport.lang.CSharpFileType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The editors of Evaluate, of a watch and of a breakpoint condition: C# fragments, highlighted and marked for [DotNetExpressionCompletionContributor]. */
class DotNetEditorsProvider : XDebuggerEditorsProviderBase() {
    override fun getFileType(): FileType = CSharpFileType

    override fun createExpressionCodeFragment(project: Project, text: String, context: PsiElement?, isPhysical: Boolean): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("expression.cs", CSharpFileType, text, LocalTimeCounter.currentTime(), isPhysical)
            .also { it.putUserData(EXPRESSION, true) }

    companion object {
        val EXPRESSION: Key<Boolean> = Key.create("io.github.dotnetsupport.debugger.expression")
    }
}

/**
 * Completion in the expressions of the debugger. `dotnet-debugger` has no `completions` request (it needs a compiler inside the adapter),
 * so the names are taken from the stopped program: locals and parameters of the frame and the members of `this` at a name that stands
 * alone, the members of the value after `value.`. The value is found with `evaluate`, which is why only plain chains of names are
 * completed (see [CSharpDebugCompletion]): a call would run while the user types.
 */
class DotNetExpressionCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.getUserData(DotNetEditorsProvider.EXPRESSION) != true) return
        val session = XDebuggerManager.getInstance(parameters.originalFile.project).currentSession?.takeIf { it.isSuspended } ?: return
        val process = session.debugProcess as? DotNetDebugProcess ?: return
        val frameId = (session.currentStackFrame as? DotNetStackFrame)?.id ?: return
        val context = CSharpDebugCompletion.contextAt(parameters.editor.document.immutableCharSequence, parameters.offset) ?: return

        val names = if (context.qualifier == null) locals(process, frameId) else members(process, context.qualifier, frameId)
        val variables = try {
            // the adapter answers in milliseconds; a busy one must not hold the completion popup
            ApplicationUtil.runWithCheckCanceled({ names.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }, ProgressManager.getInstance().progressIndicator ?: return)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val prefixed = result.withPrefixMatcher(context.prefix)
        for (variable in variables.distinctBy { it.string("name") }) {
            val name = variable.string("name") ?: continue
            if (!CSharpDebugCompletion.isName(name)) continue
            prefixed.addElement(
                LookupElementBuilder.create(name).withTypeText(variable.string("type"), true)
                    .withIcon(if (context.qualifier == null) AllIcons.Nodes.Variable else AllIcons.Nodes.Property),
            )
        }
        // nothing else knows the names of a running program: the words of the other contributors would only be noise
        result.stopHere()
    }

    /** Locals and parameters, and what is reachable without a qualifier: the members of `this`. */
    private fun locals(process: DotNetDebugProcess, frameId: Int): CompletableFuture<List<JsonObject>> =
        process.connection.request("scopes", json("frameId" to frameId)).thenCompose { answer ->
            val scopes = answer.objects("scopes").filter { it.bool("expensive") != true }.mapNotNull { it.int("variablesReference") }
            val all = scopes.map { named(process, it) }
            CompletableFuture.allOf(*all.toTypedArray()).thenCompose {
                val variables = all.flatMap { it.join() }
                val self = variables.firstOrNull { it.string("name") == "this" && (it.int("variablesReference") ?: 0) > 0 }
                if (self == null) CompletableFuture.completedFuture(variables)
                else named(process, self.int("variablesReference")!!).thenApply { variables + it }
            }
        }

    private fun members(process: DotNetDebugProcess, expression: String, frameId: Int): CompletableFuture<List<JsonObject>> =
        process.evaluate(expression, frameId, "watch").thenCompose { value ->
            val reference = value.int("variablesReference") ?: 0
            if (reference > 0) named(process, reference) else CompletableFuture.completedFuture(emptyList())
        }

    /** Named children only, one page: the elements of a collection are not names, and there may be millions of them. */
    private fun named(process: DotNetDebugProcess, reference: Int): CompletableFuture<List<JsonObject>> =
        process.connection.request("variables", json("variablesReference" to reference, "filter" to "named", "start" to 0, "count" to DotNetValueChildren.PAGE))
            .thenApply { it.objects("variables") }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
    }
}
