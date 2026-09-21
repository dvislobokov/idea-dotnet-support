package io.github.dotnetsupport.dap

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.platform.dap.CommandScope
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase
import io.github.dotnetsupport.lang.CSharpDebugCompletion
import io.github.dotnetsupport.lang.CSharpFileType
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.debug.EvaluateArguments
import org.eclipse.lsp4j.debug.EvaluateArgumentsContext
import org.eclipse.lsp4j.debug.ScopesArguments
import org.eclipse.lsp4j.debug.Variable
import org.eclipse.lsp4j.debug.VariablesArguments
import org.eclipse.lsp4j.debug.VariablesArgumentsFilter
import java.util.concurrent.TimeUnit

/**
 * The editors of Evaluate, of a watch and of a breakpoint condition. The platform DAP client makes them plain text; here they are C#
 * fragments: highlighted, and marked for [DotNetExpressionCompletionContributor].
 */
class DotNetEditorsProvider : XDebuggerEditorsProviderBase() {
    override fun getFileType(): FileType = CSharpFileType

    override fun createExpressionCodeFragment(project: Project, text: String, context: PsiElement?, isPhysical: Boolean): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("expression.cs", CSharpFileType, text, LocalTimeCounter.currentTime(), isPhysical)
            .also { it.putUserData(EXPRESSION, true) }

    companion object {
        val EXPRESSION: Key<Boolean> = Key.create("io.github.dotnetsupport.dap.expression")
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
        val frameId = (session.currentStackFrame as? DefaultDapXStackFrame)?.frame?.id ?: return
        val context = CSharpDebugCompletion.contextAt(parameters.editor.document.immutableCharSequence, parameters.offset) ?: return

        val names = process.dapDebugSession.commandProcessor.submitCommandAsync {
            if (context.qualifier == null) locals(frameId) else members(context.qualifier, frameId)
        }.asCompletableFuture()
        val variables = try {
            // the adapter answers in milliseconds; a busy one must not hold the completion popup
            ApplicationUtil.runWithCheckCanceled({ names.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }, ProgressManager.getInstance().progressIndicator ?: return)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            return
        }

        val prefixed = result.withPrefixMatcher(context.prefix)
        for (variable in variables.distinctBy { it.name }) {
            if (!CSharpDebugCompletion.isName(variable.name)) continue
            prefixed.addElement(
                LookupElementBuilder.create(variable.name).withTypeText(variable.type, true)
                    .withIcon(if (context.qualifier == null) AllIcons.Nodes.Variable else AllIcons.Nodes.Property),
            )
        }
        // nothing else knows the names of a running program: the words of the other contributors would only be noise
        result.stopHere()
    }

    /** Locals and parameters, and what is reachable without a qualifier: the members of `this`. */
    private suspend fun CommandScope.locals(frameId: Int): List<Variable> {
        val scopes = server.scopes(ScopesArguments().apply { this.frameId = frameId }).await().scopes.filter { !it.isExpensive }
        val variables = scopes.flatMap { scope -> server.variables(VariablesArguments().apply { variablesReference = scope.variablesReference }).await().variables.toList() }
        val self = variables.firstOrNull { it.name == "this" && it.variablesReference > 0 } ?: return variables
        return variables + named(self.variablesReference)
    }

    private suspend fun CommandScope.members(expression: String, frameId: Int): List<Variable> {
        val value = server.evaluate(EvaluateArguments().apply { this.expression = expression; this.frameId = frameId; context = EvaluateArgumentsContext.WATCH }).await()
        return if (value.variablesReference > 0) named(value.variablesReference) else emptyList()
    }

    /** Named children only: the elements of a collection are not names, and there may be millions of them. */
    private suspend fun CommandScope.named(reference: Int): List<Variable> =
        server.variables(VariablesArguments().apply { variablesReference = reference; filter = VariablesArgumentsFilter.NAMED }).await().variables.toList()

    private companion object {
        const val TIMEOUT_SECONDS = 3L
    }
}
