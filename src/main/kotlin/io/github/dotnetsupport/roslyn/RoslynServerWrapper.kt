package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jServerWrapper
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.lsp.RoslynServerStatus
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

/**
 * Everything the IDE asks the server passes here, which makes it the one place for what the platform has no public hook for:
 * - Roslyn marks every diagnostic with tags of Visual Studio (`2147483642`...); lsp4j reads them as nulls, the platform sends them back
 *   in `textDocument/codeAction`, and the server refuses the whole request. The nulls are dropped on the way out;
 * - semantic tokens come from [RoslynTokensCache] while the solution loads, and the answers of a loaded server go into it;
 * - the answer of a rename of a type goes to [RoslynFileRename], which renames the file of the type as well;
 * - every request is timed into [RoslynRequestStats].
 * The hook (`LspClientManager.addLsp4jServerWrapper`) is `@Internal` in the platform.
 */
@Suppress("UnstableApiUsage", "DEPRECATION")
class RoslynServerWrapper : Lsp4jServerWrapper {
    override fun wrapLsp4jServer(lspServer: LspServer, lsp4jServer: LanguageServer): LanguageServer {
        if (lspServer.descriptor !is RoslynClientDescriptor) return lsp4jServer
        val project = lspServer.project
        val stats = project.service<RoslynRequestStats>()
        val tokens = CachedTokens(project, lspServer)
        val documents = proxy(TextDocumentService::class.java, lsp4jServer.textDocumentService) { method, arguments, proceed ->
            val argument = arguments.firstOrNull()
            when (method.name) {
                "codeAction" -> (argument as? CodeActionParams)?.context?.diagnostics?.forEach(::dropUnknownTags)
                "rename" -> (argument as? RenameParams)?.let { params ->
                    // the name as it is before the rename: read now, the edit will have replaced it by the time the answer comes
                    val oldName = renamedName(lspServer, params) ?: return@let
                    return@proxy timed(stats, method, proceed).also { future ->
                        // off the thread that reads the messages of the server: it takes a read action
                        (future as? CompletableFuture<*>)?.thenAcceptAsync({ edit ->
                            if (edit is WorkspaceEdit) RoslynFileRename.afterRename(project, lspServer, edit, oldName, params.newName)
                        }, AppExecutorUtil.getAppExecutorService())
                    }
                }
                "semanticTokensFull" -> {
                    val request = (argument as? SemanticTokensParams)?.let(tokens::request)
                    request?.let(tokens::cached)?.let { cached ->
                        stats.record(lspName(method), 0.0, fromCache = true)
                        return@proxy CompletableFuture.completedFuture(cached)
                    }
                    return@proxy timed(stats, method, proceed).also { future ->
                        if (request != null) (future as? CompletableFuture<*>)?.thenAcceptAsync({ tokens.store(request, it as? SemanticTokens) }, AppExecutorUtil.getAppExecutorService())
                    }
                }
            }
            timed(stats, method, proceed)
        }
        val workspace = proxy(WorkspaceService::class.java, lsp4jServer.workspaceService) { method, _, proceed -> timed(stats, method, proceed) }
        return proxy(RoslynServer::class.java, lsp4jServer) { method, _, proceed ->
            when (method.name) {
                "getTextDocumentService" -> documents
                "getWorkspaceService" -> workspace
                else -> timed(stats, method, proceed)
            }
        }
    }

    /** The time from the call to the answer, for the requests (a notification is not waited for). */
    private fun timed(stats: RoslynRequestStats, method: Method, proceed: () -> Any?): Any? {
        val started = System.nanoTime()
        val result = proceed()
        (result as? CompletableFuture<*>)?.whenComplete { _, error -> stats.record(lspName(method), (System.nanoTime() - started) / 1e6, failed = error != null) }
        return result
    }

    /** Calls go to [target] through [around], which gets the method, its arguments and the way to call it. */
    private fun <T : Any> proxy(type: Class<T>, target: Any, around: (Method, Array<out Any?>, () -> Any?) -> Any?): T {
        val handler = InvocationHandler { _, method, arguments ->
            val actual = arguments ?: emptyArray()
            around(method, actual) {
                try {
                    method.invoke(target, *actual)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
    }

    /**
     * The tokens of one server: from the cache while the solution loads (only for the text they were made for), into the cache once it
     * is loaded (the answers of a half-loaded workspace are not worth keeping).
     */
    private class CachedTokens(private val project: Project, private val server: LspClient) {
        class Request(val file: VirtualFile, val key: String, val stamp: Long)

        private val cache get() = service<RoslynTokensCache>()
        private val workspace get() = project.service<RoslynWorkspace>()

        fun request(params: SemanticTokensParams): Request? = ReadAction.compute<Request?, RuntimeException> {
            val file = server.descriptor.findFileByUri(params.textDocument.uri) ?: return@compute null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            Request(file, RoslynTokenStore.key(legendKey(server), document.immutableCharSequence), document.modificationStamp)
        }

        fun cached(request: Request): SemanticTokens? {
            if (workspace.isLoaded) return null
            val data = cache.store.get(request.key) ?: return null
            // the heuristic colors of the plugin step aside for this file, as they do for a loaded server
            if (project.service<RoslynServerStatus>().coloredFromCache.add(request.file)) restartHighlighting(request.file)
            return SemanticTokens(data)
        }

        fun store(request: Request, answer: SemanticTokens?) {
            val data = answer?.data?.takeIf { it.isNotEmpty() } ?: return
            if (!workspace.isLoaded) return
            val unchanged = ReadAction.compute<Boolean, RuntimeException> { FileDocumentManager.getInstance().getDocument(request.file)?.modificationStamp == request.stamp }
            if (unchanged) cache.put(request.key, data)
        }

        private fun restartHighlighting(file: VirtualFile) = ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }, project.disposed)
    }

    companion object {
        fun dropUnknownTags(diagnostic: Diagnostic) {
            val tags = diagnostic.tags ?: return
            if (tags.any { it == null }) diagnostic.tags = tags.filterNotNull().ifEmpty { null }
        }

        /** The identifier the rename starts on, as the document has it now. */
        fun renamedName(server: LspClient, params: RenameParams): String? = ReadAction.compute<String?, RuntimeException> {
            val file = server.descriptor.findFileByUri(params.textDocument.uri) ?: return@compute null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            val offset = RoslynCtrlHoverReferenceProvider.offset(document, params.position) ?: return@compute null
            RoslynNavigation.wordAt(document.immutableCharSequence, offset).takeIf { it.isNotEmpty() }
        }

        fun legendKey(server: LspClient): String = RoslynTokenStore.legendKey(server.initializeResult?.capabilities?.semanticTokensProvider?.legend)

        /** `textDocument/completion` from the annotations of lsp4j: the name of the protocol, not of the Java method. */
        fun lspName(method: Method): String {
            val request = method.getAnnotation(JsonRequest::class.java)
            val notification = method.getAnnotation(JsonNotification::class.java)
            val name = (request?.value ?: notification?.value)?.takeIf { it.isNotEmpty() } ?: method.name
            // `semanticTokens/full` is written out whole: `useSegment = false`
            val useSegment = request?.useSegment ?: notification?.useSegment ?: true
            val segment = method.declaringClass.getAnnotation(JsonSegment::class.java)?.value?.takeIf { useSegment }?.let { "$it/" }.orEmpty()
            return segment + name
        }
    }
}
