package io.github.dotnetsupport.roslyn

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.TokenType
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.lang.CSharpTokenTypes
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CompletionContext
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.WorkspaceSymbolParams
import java.util.concurrent.CompletableFuture

private val LOG = logger<RoslynWarmUp>()

/**
 * The first request of a kind costs Roslyn 40-550 ms, the next ones 3-15 ms (`tools/roslyn-lsp/capture.py`: completion 153 ms, code
 * actions 523, workspace symbols 417). Right after the solution is loaded the plugin makes one of each in the file the user looks at,
 * so that the "first times" are paid before anyone presses a key. Only requests that change nothing. Measured live (menu .NET |
 * Language Server Timings): the first completion of the user 42 ms instead of 153, code actions 86-149 instead of 523. Not references:
 * their cost is the search for one symbol, not a first time (517 ms for the user after a warm-up of 585), so they are left out.
 */
object RoslynWarmUp {
    /** A member access in a body: `name.member`. [identifier] is where `name` starts, [afterDot] is right after the dot. */
    class Point(val identifier: Int, val identifierEnd: Int, val afterDot: Int)

    /** The first `name.member` after the first `{`, outside comments and strings; null for a file without one. */
    fun point(text: CharSequence): Point? {
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        var inBody = false
        var name: IntRange? = null
        var dotEnd = -1
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (type != TokenType.WHITE_SPACE) {
                when {
                    type == CSharpTokenTypes.LBRACE -> { inBody = true; name = null; dotEnd = -1 }
                    !inBody -> Unit
                    type == CSharpTokenTypes.IDENTIFIER && name != null && dotEnd >= 0 -> return Point(name.first, name.last + 1, dotEnd)
                    type == CSharpTokenTypes.IDENTIFIER -> { name = lexer.tokenStart until lexer.tokenEnd; dotEnd = -1 }
                    type == CSharpTokenTypes.DOT && name != null && dotEnd < 0 -> dotEnd = lexer.tokenEnd
                    else -> { name = null; dotEnd = -1 }
                }
            }
            lexer.advance()
        }
        return null
    }

    /** The file of the selected editor, or any other open C# file. */
    fun fileToWarmUp(project: Project): VirtualFile? {
        val editors = FileEditorManager.getInstance(project)
        return editors.selectedFiles.firstOrNull(::isCSharpSource) ?: editors.openFiles.firstOrNull(::isCSharpSource)
    }

    /** On a background thread; the requests go one after another, not to crowd the server in the moment it has just loaded. */
    fun run(project: Project, client: LspClient, file: VirtualFile) {
        val (document, text) = ReadAction.compute<Pair<Document, CharSequence>?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)?.let { it to it.immutableCharSequence }
        } ?: return
        val point = point(text) ?: return LOG.info("Roslyn warm-up: no member access in ${file.name}")
        fun position(offset: Int) = Position(document.getLineNumber(offset), offset - document.getLineStartOffset(document.getLineNumber(offset)))
        val id = client.getDocumentIdentifier(file)
        val name = position(point.identifier)
        val nameRange = Range(name, position(point.identifierEnd))
        val word = text.subSequence(point.identifier, point.identifierEnd).toString()

        val stats = project.service<RoslynRequestStats>()
        stats.warmingUp = true
        val started = System.nanoTime()
        try {
            val requests: List<Pair<String, (org.eclipse.lsp4j.services.LanguageServer) -> CompletableFuture<*>>> = listOf(
                "completion after a dot" to { it.textDocumentService.completion(CompletionParams(id, position(point.afterDot), CompletionContext(CompletionTriggerKind.TriggerCharacter, "."))) },
                "completion of a name" to { it.textDocumentService.completion(CompletionParams(id, name, CompletionContext(CompletionTriggerKind.Invoked))) },
                "hover" to { it.textDocumentService.hover(HoverParams(id, name)) },
                "definition" to { it.textDocumentService.definition(DefinitionParams(id, name)) },
                "document highlight" to { it.textDocumentService.documentHighlight(DocumentHighlightParams(id, name)) },
                "code actions" to { it.textDocumentService.codeAction(CodeActionParams(id, nameRange, CodeActionContext(emptyList()))) },
                "workspace symbols" to { it.workspaceService.symbol(WorkspaceSymbolParams(word)) },
            )
            for ((label, request) in requests) {
                if (project.isDisposed) return
                runCatching { client.sendRequestSync(TIMEOUT_MS) { request(it) } }.onFailure { LOG.info("Roslyn warm-up: $label failed", it) }
            }
        } finally {
            stats.warmingUp = false
        }
        LOG.info("Roslyn warm-up of ${file.name} at `$word`: %.0f ms".format((System.nanoTime() - started) / 1e6))
    }

    private const val TIMEOUT_MS = 10_000
}
