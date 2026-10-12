package io.github.dotnetsupport.roslyn

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lang.CSharpPopupContributor
import io.github.dotnetsupport.lang.CSharpPopupKind
import io.github.dotnetsupport.lang.NativeCSharpServerActions
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

/**
 * The rows of Rider's Generate (Alt+Insert) and Refactor This (Ctrl+Alt+Shift+T) that the C# language server offers at the caret: its
 * code actions with the matching titles — constructor, Equals and GetHashCode, overrides, the members of an interface for Generate;
 * Extract method, Introduce local, Inline, Change signature, Move type, Pull members up for Refactor This. Anything else Alt+Enter still has.
 * The lists themselves are the main part's ([io.github.dotnetsupport.lang.CSharpRiderPopups]).
 */
class RoslynPopupContributor : CSharpPopupContributor {
    override fun actions(kind: CSharpPopupKind, project: Project, editor: Editor, file: PsiFile): List<AnAction> {
        val workspace = project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded } ?: return emptyList()
        val virtualFile = file.virtualFile ?: return emptyList()
        val params = ReadAction.compute<CodeActionParams, RuntimeException> {
            // the selection, when there is one: Extract method / Introduce local work on the selected expression, as in Rider
            val caret = editor.caretModel.primaryCaret
            val start = RoslynNavigation.position(editor.document, if (caret.hasSelection()) caret.selectionStart else caret.offset)
            val end = RoslynNavigation.position(editor.document, if (caret.hasSelection()) caret.selectionEnd else caret.offset)
            CodeActionParams(client.getDocumentIdentifier(virtualFile), Range(start, end), CodeActionContext(emptyList()))
        }
        val actions = runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.codeAction(params) } }.getOrNull().orEmpty().mapNotNull { if (it.isRight) it.right else null }
        val rows = when (kind) {
            CSharpPopupKind.GENERATE -> actions.filter { isGenerator(it.title) }
            CSharpPopupKind.REFACTOR -> actions.filter { isRefactoring(it) && !isGenerator(it.title) }
        }
        return rows.filterNot { NativeCSharpServerActions.shadowed(it.title, project) }.distinctBy { it.title }.map { ServerCodeActionRow(project, client, virtualFile, it) }
    }

    /** A code action of the server as a row of the popup: resolved when chosen, applied as one command. */
    private class ServerCodeActionRow(private val project: Project, private val client: LspClient, private val file: VirtualFile, private val action: CodeAction) :
        AnAction(action.title), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = apply(project, client, file, action)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private val GENERATOR = Regex("""^(Generate |Implement |Add 'DebuggerDisplay'|Extract interface|Add missing)""")
        private val REFACTORING = Regex("""^(Extract |Introduce |Inline |Change signature|Move |Pull |Encapsulate |Make .*static|Convert to (method|property|indexer))""", RegexOption.IGNORE_CASE)

        fun isGenerator(title: String?): Boolean = title != null && GENERATOR.containsMatchIn(title)

        /** What Rider lists in Refactor This: the server's refactorings that move or reshape code, not the conversions of a construct. */
        fun isRefactoring(action: CodeAction): Boolean = REFACTORING.containsMatchIn(action.title.orEmpty())

        fun apply(project: Project, client: LspClient, file: VirtualFile, action: CodeAction) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val resolved = if (action.edit != null || action.command != null) action else client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.resolveCodeAction(action) } ?: return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    val intention = LspIntentionAction(client, resolved)
                    if (!intention.isAvailable()) return@invokeLater
                    CommandProcessor.getInstance().executeCommand(project, { intention.invoke(file) }, resolved.title, null)
                })
            }
        }
    }
}
