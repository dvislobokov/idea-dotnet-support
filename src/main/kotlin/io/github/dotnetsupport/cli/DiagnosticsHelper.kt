package io.github.dotnetsupport.cli

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Which CLR a process has loaded: [flavor] `desktop` (.NET Framework) or `core`, null for none. */
data class ProcessRuntime(val flavor: String?, val version: String?, val bitness: Int?) {
    val isDesktop: Boolean get() = flavor == DESKTOP

    companion object {
        const val DESKTOP = "desktop"
        const val CORE = "core"
    }
}

/** A type of a heap: its objects, their own bytes, and what the type keeps alive as a whole (no object counted twice). */
class TypeRetained(val name: String, val methodTable: String, val count: Long, val shallow: Long, val retained: Long)

/** An object of the dominator tree; [children]: how many objects it dominates immediately, [root]: the kind of root that holds it, if any. */
class DominatorObject(val address: String, val type: String, val size: Long, val retained: Long, val children: Int, val root: String?)

/** `retained` of the helper: [types] the largest retainers first, [dominators] the top of the dominator tree. */
class HeapRetained(
    val objects: Long, val bytes: Long, val unreachableObjects: Long, val unreachableBytes: Long, val types: List<TypeRetained>,
    val dominators: List<DominatorObject>, val omittedDominators: Int, val elapsedMs: Long, val peakWorkingSet: Long,
) {
    private val byMethodTable by lazy { types.associateBy { normalAddress(it.methodTable) } }

    /** The type of `dumpheap -stat` with the method table [methodTable] (SOS pads it with zeros, the helper does not). */
    fun type(methodTable: String): TypeRetained? = byMethodTable[normalAddress(methodTable)]
}

/** `dominators` of the helper: what [parent] (null: the roots) dominates immediately, the largest first, and how much was left out. */
class Dominated(val parent: DominatorObject?, val children: List<DominatorObject>, val omitted: Int, val omittedRetained: Long)

/** `0x00007FF8A1` and `7ff8a1` are the same address. */
fun normalAddress(text: String): String = text.trim().lowercase().removePrefix("0x").trimStart('0').ifEmpty { "0" }

/** The answers of DiagnosticsHelper (`helpers/diagnostics`), from JSON: pure, tested on saved answers. */
object DiagnosticsAnswers {
    /** `{"1234": {flavor, version, bitness}, "5678": null}`: null for a process the helper could not open. */
    fun runtimes(answer: JsonElement): Map<Long, ProcessRuntime?> = answer.asJsonObject.entrySet().mapNotNull { (pid, value) ->
        val id = pid.toLongOrNull() ?: return@mapNotNull null
        id to value.takeIf { it.isJsonObject }?.asJsonObject?.let { ProcessRuntime(it.text("flavor"), it.text("version"), it.number("bitness")?.toInt()) }
    }.toMap()

    fun retained(answer: JsonElement): HeapRetained {
        val json = answer.asJsonObject
        return HeapRetained(
            json.number("objects") ?: 0, json.number("bytes") ?: 0, json.number("unreachableObjects") ?: 0, json.number("unreachableBytes") ?: 0,
            json.array("types").map { it.asJsonObject }.map {
                TypeRetained(it.text("name").orEmpty(), it.text("methodTable").orEmpty(), it.number("count") ?: 0, it.number("shallow") ?: 0, it.number("retained") ?: 0)
            },
            json.array("dominators").map(::dominator), json.number("omittedDominators")?.toInt() ?: 0, json.number("elapsedMs") ?: 0, json.number("peakWorkingSet") ?: 0,
        )
    }

    fun dominated(answer: JsonElement): Dominated {
        val json = answer.asJsonObject
        return Dominated(json.get("parent")?.takeIf { it.isJsonObject }?.let(::dominator), json.array("children").map(::dominator),
            json.number("omitted")?.toInt() ?: 0, json.number("omittedRetained") ?: 0)
    }

    private fun dominator(element: JsonElement): DominatorObject = element.asJsonObject.let {
        DominatorObject(it.text("address").orEmpty(), it.text("type").orEmpty(), it.number("size") ?: 0, it.number("retained") ?: 0,
            it.number("children")?.toInt() ?: 0, it.text("root"))
    }

    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.number(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
}

/**
 * The runtimes of all the processes of the machine, asked in one batch and kept for [ttlMs]: Attach to Process asks for each process of
 * its list in turn, and must not wait long for any. A refresh that has not answered within [waitMs] of its start is not waited for
 * again: the processes it would tell about are left out of this showing of the list and are there the next time.
 */
class ProcessRuntimes(
    private val query: (List<Long>) -> Map<Long, ProcessRuntime?>,
    private val processIds: () -> List<Long> = { ProcessHandle.allProcesses().use { stream -> stream.map { it.pid() }.toList() } },
    private val ttlMs: Long = 5_000,
    private val waitMs: Long = 1_500,
    private val now: () -> Long = System::currentTimeMillis,
    private val runAsync: (() -> Unit) -> Unit = { task -> ApplicationManager.getApplication()?.executeOnPooledThread(task) ?: Thread(task).start() },
) {
    private class Snapshot(val at: Long, val runtimes: Map<Long, ProcessRuntime?>)

    @Volatile private var snapshot: Snapshot? = null
    private var refresh: CompletableFuture<Snapshot>? = null
    private var refreshStarted = 0L

    /** The runtime of [pid] as far as known now: null when it has none, cannot be opened or is not known yet. */
    fun runtime(pid: Long): ProcessRuntime? {
        val current = snapshot
        if (current != null && now() - current.at < ttlMs) return current.runtimes[pid]
        val (future, started) = refresh()
        val left = started + waitMs - now()
        if (left <= 0) return current?.runtimes?.get(pid)
        return try {
            future.get(left, TimeUnit.MILLISECONDS).runtimes[pid]
        } catch (_: TimeoutException) {
            current?.runtimes?.get(pid)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun refresh(): Pair<CompletableFuture<Snapshot>, Long> {
        refresh?.takeIf { !it.isDone }?.let { return it to refreshStarted }
        val future = CompletableFuture<Snapshot>()
        refresh = future
        refreshStarted = now()
        runAsync {
            val runtimes = try {
                query(processIds())
            } catch (e: Exception) {
                PluginLog.warn(DiagnosticsHelperService.LOG_CATEGORY, "the runtimes of the processes are not known: ${e.message}")
                emptyMap() // not asked again until the snapshot is old: a helper that fails does not fail at every process of the list
            }
            Snapshot(now(), runtimes).also { snapshot = it; future.complete(it) }
        }
        return future to refreshStarted
    }
}

/**
 * The diagnostics helper (`helpers/diagnostics`, ClrMD), one process for the IDE: which CLR a process has loaded, the retained sizes and
 * the dominators of a heap dump. Blocking calls, not for the EDT.
 */
@Service(Service.Level.APP)
class DiagnosticsHelperService : Disposable {
    private val connection = HelperConnection.of(HELPER, LOG_CATEGORY)
    private val dumps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** For Attach to Process: whether [pid] has the desktop CLR loaded (hosts like `w3wp.exe` and Office, not found by the executable). */
    val runtimes = ProcessRuntimes(query = { pids -> runtimes(pids, RUNTIMES_TIMEOUT_MS) })

    fun runtimes(pids: List<Long>, timeoutMs: Long): Map<Long, ProcessRuntime?> =
        DiagnosticsAnswers.runtimes(connection.request("runtimes", JsonObject().apply { add("pids", JsonArray().apply { pids.forEach(::add) }) }, timeoutMs))

    /** Takes as long as reading the heap of the dump: seconds for a small one, minutes for gigabytes. */
    fun retained(dump: File, top: Int = 100): HeapRetained =
        DiagnosticsAnswers.retained(connection.request("retained", analysed(dump).apply { addProperty("top", top) }, RETAINED_TIMEOUT_MS))

    /** What the object at [address] (null: the roots) dominates; the analysis of [retained] is kept by the helper, so this is quick. */
    fun dominated(dump: File, address: String?, top: Int = 200): Dominated =
        DiagnosticsAnswers.dominated(connection.request("dominators", analysed(dump).apply { address?.let { addProperty("address", it) }; addProperty("top", top) }, RETAINED_TIMEOUT_MS))

    /** The helper forgets the dump (and stops an analysis of it), so that the file can be deleted. Nothing to do if it has never been asked. */
    fun close(dump: File) {
        if (!dumps.remove(dump.path)) return
        runCatching { connection.request("close", dumpParams(dump), CLOSE_TIMEOUT_MS) }
            .onFailure { PluginLog.warn(LOG_CATEGORY, "the helper did not let ${dump.name} go: ${it.message}") }
    }

    private fun dumpParams(dump: File) = JsonObject().apply { addProperty("dumpPath", dump.path) }

    private fun analysed(dump: File) = dumpParams(dump).also { dumps.add(dump.path) }

    override fun dispose() = connection.dispose()

    companion object {
        const val LOG_CATEGORY = "diagnostics"
        val HELPER = DotNetHelper("diagnostics", "DiagnosticsHelper", "HelperFramework", listOf("Program.cs", "Protocol.cs"))
        private const val RUNTIMES_TIMEOUT_MS = 20_000L
        private const val RETAINED_TIMEOUT_MS = 30 * 60_000L
        private const val CLOSE_TIMEOUT_MS = 10_000L

        fun getInstance(): DiagnosticsHelperService = service()
    }
}
