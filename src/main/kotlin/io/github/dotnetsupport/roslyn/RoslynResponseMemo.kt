package io.github.dotnetsupport.roslyn

import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.json.ResponseJsonAdapter
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicLong

/**
 * The answers of the server to questions asked again while nothing in the workspace has changed: the platform asks the same thing many times
 * (`codeLens/resolve` after every refresh, `codeAction/resolve` whenever the light bulb is computed, `documentHighlight` when the caret comes
 * back, `inlayHint` when a range scrolls into view again). Any change anywhere — a document edited, saved or closed, a file on disk, the
 * configuration, a command, the projects (re)loaded, the server restarted — starts a new generation and forgets every answer, so an answer
 * is never older than the workspace (opening a file is no change: its text is the one on disk). Kept as JSON and parsed anew for every
 * caller: the platform may change what it gets.
 *
 * Not here: diagnostics and semantic tokens (their own handling), completion lists (the platform filters them itself), edits (rename).
 */
@Service(Service.Level.PROJECT)
class RoslynResponseMemo {
    private val generation = AtomicLong()
    private val answers = object : LinkedHashMap<String, Pair<Long, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?): Boolean = size > MAX_ANSWERS
    }

    /** The generation a question is asked in; an answer is kept only if it is still the current one when the answer comes. */
    val current: Long get() = generation.get()

    fun invalidate() {
        generation.incrementAndGet()
        synchronized(answers) { answers.clear() }
    }

    fun get(key: String): String? = synchronized(answers) { answers[key]?.takeIf { it.first == generation.get() }?.second }

    fun put(key: String, askedIn: Long, json: String) {
        synchronized(answers) { if (askedIn == generation.get()) answers[key] = askedIn to json }
    }

    val size: Int get() = synchronized(answers) { answers.size }

    companion object {
        private const val MAX_ANSWERS = 512

        /** The methods of lsp4j's `TextDocumentService` whose answer depends only on the workspace and the question. */
        val CACHEABLE = setOf(
            "documentHighlight", "inlayHint", "resolveInlayHint", "foldingRange", "hover", "codeLens", "resolveCodeLens",
            "codeAction", "resolveCodeAction", "documentSymbol", "resolveCompletionItem", "signatureHelp",
            "definition", "typeDefinition", "implementation", "references",
        )

        /**
         * Notifications of `TextDocumentService` / `WorkspaceService` that change what the server knows. Not `didOpen`: the text of a file
         * that is opened is the one on disk, the server knew it already, and opening another tab forgot every answer (seen live: code lens,
         * folding and inlay hints were asked again on every switch back). `didClose` does: the server goes back to the text on disk.
         */
        val CHANGES = setOf(
            "didChange", "didClose", "didSave",
            "didChangeWatchedFiles", "didChangeConfiguration", "didChangeWorkspaceFolders", "didCreateFiles", "didRenameFiles", "didDeleteFiles",
            "executeCommand",
        )

        /** lsp4j's own Gson: it knows `Either` and the enums of the protocol. */
        val GSON: Gson = MessageJsonHandler(emptyMap()).gson

        fun key(lspMethod: String, arguments: Array<out Any?>): String = lspMethod + " " + GSON.toJson(arguments)

        /** `T` of the `CompletableFuture<T>` a request method of lsp4j returns. */
        fun resultType(method: Method): Type? = (method.genericReturnType as? ParameterizedType)?.actualTypeArguments?.firstOrNull()

        private val adapters = java.util.concurrent.ConcurrentHashMap<Method, TypeAdapter<Any?>>()

        /**
         * How the answer of [method] is read and written: lsp4j's own adapter of the method where it has one (`@ResponseJsonAdapter`: the
         * answer of `codeAction` is a list of `Command` or `CodeAction`, which plain Gson cannot tell apart), the adapter of its type otherwise.
         */
        @Suppress("UNCHECKED_CAST")
        fun adapter(method: Method): TypeAdapter<Any?>? {
            adapters[method]?.let { return it }
            val type = resultType(method) ?: return null
            val token = TypeToken.get(type) as TypeToken<Any?>
            val own = method.getAnnotation(ResponseJsonAdapter::class.java)?.value?.java?.getDeclaredConstructor()?.newInstance() as? TypeAdapterFactory
            val adapter = (own?.create(GSON, token) ?: GSON.getAdapter(token)) as TypeAdapter<Any?>
            return adapter.also { adapters[method] = it }
        }
    }
}
