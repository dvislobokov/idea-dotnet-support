package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.ui.SimpleListCellRenderer
import io.github.dotnetsupport.lang.CSharpFile
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

/**
 * Alt+Insert (Generate) in a C# file: the generators Roslyn offers at the caret — constructor, Equals and GetHashCode, overrides,
 * the members of an interface or an abstract class, a `DebuggerDisplay` — as one list, the way Rider's Generate menu shows them.
 * They are the code actions of the server with the matching titles; anything else Alt+Enter still has.
 */
class RoslynGenerateAction : AnAction("Generate..."), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = project != null && file is CSharpFile && project.service<RoslynWorkspace>().isLoaded
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val workspace = project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()?.takeIf { workspace.isLoaded } ?: return
        val position = RoslynNavigation.position(editor.document, editor.caretModel.offset)
        val params = CodeActionParams(client.getDocumentIdentifier(file), Range(position, position), CodeActionContext(emptyList()))
        val actions = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<CodeAction>, RuntimeException>({
            client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.codeAction(params) }.orEmpty().mapNotNull { if (it.isRight) it.right else null }.filter { isGenerator(it.title) }
        }, "Looking for What Can Be Generated", true, project)
        if (actions.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "Nothing to generate here: put the caret on a type or a member")
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(actions)
            .setTitle("Generate")
            .setRenderer(SimpleListCellRenderer.create("") { it.title })
            .setNamerForFiltering { it.title }
            .setItemChosenCallback { apply(project, client, file, it) }
            .createPopup().showInBestPositionFor(editor)
    }

    private fun apply(project: Project, client: LspClient, file: VirtualFile, action: CodeAction) {
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

    companion object {
        private const val TIMEOUT_MS = 15_000
        private val GENERATOR = Regex("""^(Generate |Implement |Add 'DebuggerDisplay'|Extract interface|Add missing)""")

        fun isGenerator(title: String?): Boolean = title != null && GENERATOR.containsMatchIn(title)
    }
}
