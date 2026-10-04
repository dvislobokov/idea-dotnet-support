package io.github.dotnetsupport.roslyn

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jServerWrapper
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.dotnetsupport.lsp.RoslynServerStatus
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.DocumentDiagnosticParams
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
import java.util.concurrent.TimeUnit

/**
 * Everything the IDE asks the server passes here, which makes it the one place for what the platform has no public hook for:
 * - Roslyn marks every diagnostic with tags of Visual Studio (`2147483642`...); lsp4j reads them as nulls, the platform sends them back
 *   in `textDocument/codeAction`, and the server refuses the whole request. The nulls are dropped on the way out;
 * - semantic tokens come from [RoslynTokensCache] while the solution loads, and the answers of a loaded server go into it;
 * - the answer of a rename of a type goes to [RoslynFileRename], which renames the file of the type as well;
 * - every request is timed into [RoslynRequestStats].
 * - an answer to a question asked again in the same state of the workspace comes from [RoslynResponseMemo];
 * The hook (`LspClientManager.addLsp4jServerWrapper`) is `@Internal` in the platform.
 */
@Suppress("UnstableApiUsage", "DEPRECATION")
class RoslynServerWrapper : Lsp4jServerWrapper {
    override fun wrapLsp4jServer(lspServer: LspServer, lsp4jServer: LanguageServer): LanguageServer {
        if (lspServer.descriptor !is RoslynClientDescriptor) return lsp4jServer
        val project = lspServer.project
        val stats = project.service<RoslynRequestStats>()
        val tokens = CachedTokens(project, lspServer)
        val memo = project.service<RoslynResponseMemo>().apply { invalidate() }
        val documents = proxy(TextDocumentService::class.java, lsp4jServer.textDocumentService) { method, arguments, proceed ->
            val argument = arguments.firstOrNull()
            if (method.name in RoslynResponseMemo.CHANGES) memo.invalidate()
            when (method.name) {
                // asked about what has changed since: the server refuses, the platform does not expect it to (see RoslynStaleResolve)
                in RoslynStaleResolve.METHODS -> return@proxy RoslynStaleResolve.orUnresolved(timed(stats, method, proceed), argument)
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
                // the errors of an open document, for the Project Errors tab: see RoslynSolutionProblems.documentReport
                "diagnostic" -> (argument as? DocumentDiagnosticParams)?.textDocument?.uri?.let { uri ->
                    return@proxy timed(stats, method, proceed).also { future ->
                        (future as? CompletableFuture<*>)?.thenAcceptAsync({ report ->
                            val full = (report as? DocumentDiagnosticReport)?.takeIf { it.isLeft }?.left ?: return@thenAcceptAsync
                            val file = lspServer.descriptor.findFileByUri(uri) ?: return@thenAcceptAsync
                            project.service<RoslynSolutionProblems>().documentReport(uri, file, full.items.orEmpty())
                        }, AppExecutorUtil.getAppExecutorService())
                    }
                }
                "didClose" -> (argument as? DidCloseTextDocumentParams)?.textDocument?.uri?.let { project.service<RoslynSolutionProblems>().documentClosed(it) }
                // now the platform may ask for the tokens of a file it has shown since before the server was there (from the cache)
                "didOpen" -> (argument as? DidOpenTextDocumentParams)?.textDocument?.uri?.let { uri ->
                    return@proxy timed(stats, method, proceed).also {
                        AppExecutorUtil.getAppExecutorService().execute {
                            val file = lspServer.descriptor.findFileByUri(uri) ?: return@execute
                            ReadAction.run<RuntimeException> { project.service<RoslynWorkspace>().takeUnless { project.isDisposed }?.documentOpened(file) }
                        }
                    }
                }
                "semanticTokensFull" -> {
                    val request = (argument as? SemanticTokensParams)?.let(tokens::request)
                    request?.let(tokens::cached)?.let { cached ->
                        stats.record(lspName(method), 0.0, fromCache = true)
                        return@proxy CompletableFuture.completedFuture(cached)
                    }
                    return@proxy timed(stats, method, proceed).also { future ->
                        if (request != null) (future as? CompletableFuture<*>)?.thenAcceptAsync({ tokens.answered(request, it as? SemanticTokens) }, AppExecutorUtil.getAppExecutorService())
                    }
                }
            }
            if (method.name in RoslynResponseMemo.CACHEABLE) remembered(memo, stats, method, arguments, proceed) else timed(stats, method, proceed)
        }
        val workspace = proxy(WorkspaceService::class.java, lsp4jServer.workspaceService) { method, _, proceed ->
            if (method.name in RoslynResponseMemo.CHANGES) memo.invalidate()
            timed(stats, method, proceed)
        }
        return proxy(RoslynServer::class.java, lsp4jServer) { method, _, proceed ->
            when (method.name) {
                "getTextDocumentService" -> documents
                "getWorkspaceService" -> workspace
                // solution/open, project/open, initialize, shutdown: what the server knows changes
                else -> {
                    memo.invalidate()
                    timed(stats, method, proceed)
                }
            }
        }
    }

    /** The answer of this generation if the same question has been asked already (see [RoslynResponseMemo]), otherwise the server's, kept. */
    private fun remembered(memo: RoslynResponseMemo, stats: RoslynRequestStats, method: Method, arguments: Array<out Any?>, proceed: () -> Any?): Any? {
        val adapter = RoslynResponseMemo.adapter(method) ?: return timed(stats, method, proceed)
        val key = RoslynResponseMemo.key(lspName(method), arguments)
        memo.get(key)?.let { json ->
            runCatching { adapter.fromJson(json) }.onSuccess { copy ->
                stats.record(lspName(method), 0.0, fromCache = true)
                return CompletableFuture.completedFuture(copy)
            }
        }
        val askedIn = memo.current
        return timed(stats, method, proceed).also { future ->
            (future as? CompletableFuture<*>)?.thenAccept { answer -> runCatching { memo.put(key, askedIn, adapter.toJson(answer)) } }
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
     * is loaded (the answers of a half-loaded workspace are not worth keeping). Either way the heuristic colors of the plugin step aside
     * for the file only when these tokens are on screen ([RoslynServerStatus.coloredByServer]).
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
            shown(request.file)
            return SemanticTokens(data)
        }

        /** The answer of the server: kept when the solution is loaded, and then it is what colors the file. */
        fun answered(request: Request, answer: SemanticTokens?) {
            val data = answer?.data ?: return
            if (!workspace.isLoaded) return
            shown(request.file)
            if (data.isEmpty()) return
            val unchanged = ReadAction.compute<Boolean, RuntimeException> { FileDocumentManager.getInstance().getDocument(request.file)?.modificationStamp == request.stamp }
            if (unchanged) cache.put(request.key, data)
        }

        /**
         * The heuristic colors of the plugin step aside for [file] a moment after its tokens have been handed to the platform: it decodes
         * and applies them in a coroutine of its own, and a pass of the daemon before that would show neither.
         */
        private fun shown(file: VirtualFile) {
            if (project.isDisposed || file in project.service<RoslynServerStatus>().coloredByServer) return
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (!project.isDisposed && project.service<RoslynServerStatus>().coloredByServer.add(file)) workspace.restartHighlighting(listOf(file))
            }, STEP_ASIDE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        /** From the tokens handed to the platform to the heuristics stepping aside; the platform shows tokens in tens of milliseconds. */
        const val STEP_ASIDE_DELAY_MS = 300L

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

/**
 * A code lens, an inlay hint or a completion item is resolved a moment after it is shown, and the document may have changed by then.
 * Roslyn answers such a request with an error — `Resolve version '…-10258-0' does not match current version '…-10278-0'`, the code
 * ContentModified — which is what the protocol says to do and a client is to ignore. The LSP client of the platform does not: the error
 * ends its coroutine as an unhandled exception (60 of them in the log of one morning of typing). Here such an answer becomes the item
 * as it was asked about, unresolved: the platform shows it as it is, and asks anew for the changed document anyway.
 */
object RoslynStaleResolve {
    val METHODS: Set<String> = setOf("resolveCodeLens", "resolveInlayHint", "resolveCompletionItem")

    /** ContentModified and ServerCancelled of the protocol, RequestCancelled of JSON-RPC. */
    private val CODES = setOf(-32801, -32802, -32800)

    fun isStale(failure: Throwable?): Boolean {
        var cause = failure
        while (cause != null && cause !is org.eclipse.lsp4j.jsonrpc.ResponseErrorException && cause.cause != null && cause.cause !== cause) cause = cause.cause
        val error = (cause as? org.eclipse.lsp4j.jsonrpc.ResponseErrorException)?.responseError ?: return false
        return error.code in CODES || error.message.orEmpty().contains("does not match current version")
    }

    /** [answer] as it is, unless it fails as stale: then it completes with [unresolved]. Cancelling the result cancels the request. */
    fun orUnresolved(answer: Any?, unresolved: Any?): Any? {
        val request = answer as? CompletableFuture<*> ?: return answer
        if (unresolved == null) return answer
        val result = CompletableFuture<Any?>()
        request.whenComplete { value, failure ->
            when {
                failure == null -> result.complete(value)
                isStale(failure) -> result.complete(unresolved)
                else -> result.completeExceptionally(failure)
            }
        }
        result.whenComplete { _, _ -> if (result.isCancelled) request.cancel(true) }
        return result
    }
}
