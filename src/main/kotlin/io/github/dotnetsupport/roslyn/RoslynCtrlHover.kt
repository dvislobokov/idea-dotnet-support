package io.github.dotnetsupport.roslyn

import com.intellij.model.Symbol
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.lang.CSharpDeclaration
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpTokenTypes
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position

/**
 * Ctrl + hover over a name: the underline, the hand and the hint with the target. The LSP client of the platform gives a reference
 * only while Go to Declaration is being performed (it looks at the current action, not to ask a server on every move of the mouse),
 * so Ctrl + click works and Ctrl + hover shows nothing. Roslyn answers `textDocument/definition` in 3-11 ms once warm, which is
 * cheap enough for a hover. Registered last: on a click the reference of the platform comes first.
 */
class RoslynCtrlHoverReferenceProvider : ImplicitReferenceProvider {
    override fun getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference? {
        if (element.elementType != CSharpTokenTypes.IDENTIFIER || element.containingFile !is CSharpFile) return null
        if (!element.project.service<RoslynWorkspace>().isLoaded) return null
        return Reference(element)
    }

    private class Reference(private val identifier: PsiElement) : PsiSymbolReference {
        override fun getElement(): PsiElement = identifier
        override fun getRangeInElement(): TextRange = TextRange(0, identifier.textLength)

        override fun resolveReference(): Collection<Symbol> {
            // a hover is resolved in the background; whoever asks on EDT gets nothing rather than a frozen UI
            if (ApplicationManager.getApplication().isDispatchThread) return emptyList()
            val project = identifier.project
            val file = identifier.containingFile?.virtualFile ?: return emptyList()
            val client = project.service<RoslynWorkspace>().clients.firstOrNull() ?: return emptyList()
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
            val params = DefinitionParams(client.getDocumentIdentifier(file), position(document, identifier.textRange.startOffset))
            val answer = runCatching { client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.definition(params) } }.getOrNull() ?: return emptyList()
            val locations = if (answer.isLeft) answer.left.map { it.uri to it.range.start } else answer.right.map { it.targetUri to it.targetSelectionRange.start }
            return locations.mapNotNull { (uri, start) ->
                val targetFile = client.descriptor.findFileByUri(uri) ?: return@mapNotNull null
                val targetDocument = FileDocumentManager.getInstance().getDocument(targetFile) ?: return@mapNotNull null
                val offset = offset(targetDocument, start) ?: return@mapNotNull null
                val leaf = PsiManager.getInstance(project).findFile(targetFile)?.findElementAt(offset) ?: return@mapNotNull null
                // the declaration shows itself in the hint as "class Person in Types.cs"; a local variable is just its token
                val declaration = PsiTreeUtil.getParentOfType(leaf, CSharpDeclaration::class.java)?.takeIf { it.nameIdentifier == leaf }
                // the name under the mouse is the declaration itself: nowhere to go, no link
                (declaration ?: leaf).takeIf { leaf != identifier }?.let(PsiSymbolService.getInstance()::asSymbol)
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 300

        fun position(document: Document, offset: Int): Position {
            val line = document.getLineNumber(offset)
            return Position(line, offset - document.getLineStartOffset(line))
        }

        fun offset(document: Document, position: Position): Int? {
            if (position.line !in 0 until document.lineCount) return null
            return (document.getLineStartOffset(position.line) + position.character).takeIf { it <= document.getLineEndOffset(position.line) }
        }
    }
}
