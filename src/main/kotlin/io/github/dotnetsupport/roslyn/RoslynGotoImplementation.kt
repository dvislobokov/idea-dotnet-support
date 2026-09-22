package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.navigation.actions.GotoImplementationAction
import com.intellij.idea.ActionsBundle
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.SimpleListCellRenderer
import io.github.dotnetsupport.lang.CSharpDeclaration
import io.github.dotnetsupport.lang.CSharpFile
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Position
import javax.swing.Icon

/**
 * Go to Implementation (Ctrl+Alt+B) in C#: the platform looks for the implementations through the PSI of the language, which the
 * plugin does not have (a declaration here is a node of its scanner, a usage is a plain token), and the LSP client of the platform does
 * not ask `textDocument/implementation`. In a C# file with a loaded server the action asks it; anywhere else it is the action of the
 * platform, unchanged.
 */
class RoslynGotoImplementationAction : GotoImplementationAction() {
    init {
        // registered by a plugin, the action would look for its texts in the bundle of the plugin and show an empty menu item
        templatePresentation.setText(ActionsBundle.actionText(ID))
        templatePresentation.description = ActionsBundle.actionDescription(ID)
    }

    override fun getHandler(): CodeInsightActionHandler = RoslynGotoImplementationHandler(super.getHandler())

    private companion object {
        const val ID = "GotoImplementation"
    }
}

class RoslynGotoImplementationHandler(private val platform: CodeInsightActionHandler) : CodeInsightActionHandler {
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val workspace = project.service<RoslynWorkspace>()
        val client = workspace.clients.firstOrNull()
        val virtualFile = file.virtualFile
        if (file !is CSharpFile || virtualFile == null || client == null || !workspace.isLoaded) return platform.invoke(project, editor, file)

        val offset = editor.caretModel.offset
        val word = RoslynNavigation.wordAt(editor.document.immutableCharSequence, offset)
        val params = ImplementationParams(client.getDocumentIdentifier(virtualFile), RoslynNavigation.position(editor.document, offset))
        val targets = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<RoslynNavigation.Target>, RuntimeException>({
            val answer = runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.implementation(params) } }.getOrNull()
            ReadAction.compute<List<RoslynNavigation.Target>, RuntimeException> { RoslynNavigation.targets(project, client, answer) }
        }, "Searching for Implementations of ${word.ifEmpty { "the Symbol" }}", true, project)

        when (targets.size) {
            0 -> HintManager.getInstance().showErrorHint(editor, "No implementations found")
            1 -> targets.single().navigate(project)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                .setTitle("Choose Implementation of $word")
                .setRenderer(SimpleListCellRenderer.create { label, target, _ ->
                    label.text = target.text
                    label.icon = target.icon
                })
                .setNamerForFiltering { it.text }
                .setItemChosenCallback { it.navigate(project) }
                .createPopup().showInBestPositionFor(editor)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

/** From the locations of the server to places of the IDE, with the name and the icon of the declaration there. */
object RoslynNavigation {
    class Target(val file: VirtualFile, val offset: Int, val text: String, val icon: Icon?) {
        fun navigate(project: Project) = OpenFileDescriptor(project, file, offset).navigate(true)
    }

    fun position(document: com.intellij.openapi.editor.Document, offset: Int): Position {
        val line = document.getLineNumber(offset)
        return Position(line, offset - document.getLineStartOffset(line))
    }

    /** The identifier around [offset], for the titles: `Area` for a caret anywhere in `Area` or right after it. */
    fun wordAt(text: CharSequence, offset: Int): String {
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        while (end < text.length && Character.isJavaIdentifierPart(text[end])) end++
        return text.substring(start, end)
    }

    /** Read action: the answer of `textDocument/implementation` / `definition` as targets, in the order of the server, without repeats. */
    fun targets(project: Project, client: LspClient, answer: org.eclipse.lsp4j.jsonrpc.messages.Either<out List<org.eclipse.lsp4j.Location>, out List<org.eclipse.lsp4j.LocationLink>>?): List<Target> {
        val places = when {
            answer == null -> emptyList()
            answer.isLeft -> answer.left.map { it.uri to it.range.start }
            else -> answer.right.map { it.targetUri to it.targetSelectionRange.start }
        }
        return places.distinct().mapNotNull { (uri, start) ->
            val file = client.descriptor.findFileByUri(uri) ?: return@mapNotNull null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
            if (start.line !in 0 until document.lineCount) return@mapNotNull null
            val offset = (document.getLineStartOffset(start.line) + start.character).coerceAtMost(document.getLineEndOffset(start.line))
            val declaration = PsiManager.getInstance(project).findFile(file)?.findElementAt(offset)?.let { PsiTreeUtil.getParentOfType(it, CSharpDeclaration::class.java) }
            val line = document.charsSequence.subSequence(document.getLineStartOffset(start.line), document.getLineEndOffset(start.line)).trim()
            val name = declaration?.presentation?.presentableText ?: line.toString()
            val container = declaration?.containerName?.takeIf { it.isNotEmpty() }?.let { " in $it" }.orEmpty()
            Target(file, offset, "$name$container  (${file.name}:${start.line + 1})", declaration?.getIcon(0))
        }
    }
}
