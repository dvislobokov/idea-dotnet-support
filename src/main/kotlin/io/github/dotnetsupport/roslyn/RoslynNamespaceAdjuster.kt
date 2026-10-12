package io.github.dotnetsupport.roslyn

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import io.github.dotnetsupport.lang.CSharpSyntaxModel
import io.github.dotnetsupport.lang.CSharpNamespaceAdjuster
import io.github.dotnetsupport.lang.DeclarationKind
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

private val LOG = logger<RoslynNamespaceAdjuster>()

/**
 * After a move: the "Change namespace to '...'" refactoring of Roslyn (its sync-namespace refactoring, offered at the namespace declaration
 * once the folder and the namespace disagree) changes the declaration and every usage in the solution. The server learns about the moved
 * file on its own, a moment after the move: the action is asked for a few times.
 */
class RoslynNamespaceAdjuster : CSharpNamespaceAdjuster {
    override fun adjust(project: Project, file: VirtualFile, oldNamespace: String, newNamespace: String, done: (Boolean) -> Unit) {
        val workspace = project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()
        if (client == null || !workspace.isLoaded) return done(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val applied = runCatching { tryAdjust(client, file, newNamespace) }.getOrElse { LOG.warn("Change namespace after a move failed", it); false }
            done(applied)
        }
    }

    private fun tryAdjust(client: LspClient, file: VirtualFile, newNamespace: String): Boolean {
        val wanted = "Change namespace to '$newNamespace'"
        repeat(ATTEMPTS) { attempt ->
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
            val text = document.immutableCharSequence
            val declaration = CSharpSyntaxModel.current.declarations(text).declarations.singleOrNull { it.kind == DeclarationKind.NAMESPACE } ?: return false
            val range = Range(RoslynNavigation.position(document, declaration.nameRange.startOffset), RoslynNavigation.position(document, declaration.nameRange.endOffset))
            val params = CodeActionParams(client.getDocumentIdentifier(file), range, CodeActionContext(emptyList()))
            val answer = client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.codeAction(params) }
            val action = answer.orEmpty().mapNotNull { if (it.isRight) it.right else null }.firstOrNull { it.title.orEmpty().startsWith(wanted) || it.title.orEmpty().startsWith("Change namespace to") }
            if (action != null) {
                val resolved = if (action.edit != null) action else client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.resolveCodeAction(action) } ?: return false
                var applied = false
                ApplicationManager.getApplication().invokeAndWait({ applied = apply(client, file, resolved) }, ModalityState.nonModal())
                return applied
            }
            LOG.info("Change namespace after a move: not offered yet (attempt ${attempt + 1})")
            Thread.sleep(RETRY_MS)
        }
        return false
    }

    private fun apply(client: LspClient, file: VirtualFile, action: CodeAction): Boolean {
        if (client.project.isDisposed) return false
        val intention = LspIntentionAction(client, action)
        if (!intention.isAvailable()) return false
        CommandProcessor.getInstance().executeCommand(client.project, { intention.invoke(file) }, action.title, null)
        return true
    }

    private companion object {
        const val ATTEMPTS = 6
        const val RETRY_MS = 700L
        const val TIMEOUT_MS = 15_000
    }
}
