package io.github.dotnetsupport.ml

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import io.github.completionml.core.imports.ImportsModel
import io.github.dotnetsupport.settings.DotNetSettings
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Corpus statistics of which namespace supplies a type name (engine experiment e20, [ImportsModel]: `-ln p(namespace | name)` plus the
 * pointwise mutual information with the `using`s already in the file). They ORDER what the plugin's own resolution found — the «Import
 * type» quick fix ([io.github.dotnetsupport.lang.semantic.CSharpSemanticChecks]) and the rows of not-yet-imported types of the completion
 * list ([io.github.dotnetsupport.lang.NativeCSharpImportCompletion], weigher [CSharpImportStatsWeigher]); a namespace the index does
 * not know is never offered. Off with [DotNetSettings.importStatistics].
 *
 * The artifact `cs-imports-e20.cml` (2.6 MB, ~100 ms to load, a few µs per query) comes from [CSharpMlSettings.modelDirectory] when set,
 * else from the plugin's resources (a build with `-PmlEnabled=true`), else from `ml-models/csharp` of the working directory (the plain
 * build run from the repository). Loaded once, on a pooled thread, on the first query; until then and without the file the callers keep
 * their own order. In unit tests only [modelForTests] is used.
 */
@Service(Service.Level.APP)
class CSharpImportStats {
    private sealed class State {
        object Idle : State()
        object Loading : State()
        class Ready(val model: ImportsModel?, val key: String) : State()
    }

    private val state = AtomicReference<State>(State.Idle)

    /** The loaded model, or null while it loads, when there is none or when the feature is off. Never blocks. */
    fun model(): ImportsModel? {
        if (!isEnabled()) return null
        if (ApplicationManager.getApplication().isUnitTestMode) return modelForTests
        val key = CSharpMlSettings.getInstance().modelDirectory.trim()
        when (val s = state.get()) {
            is State.Ready -> if (s.key == key) return s.model
            State.Loading -> return null
            State.Idle -> {}
        }
        if (state.compareAndSet(state.get().takeUnless { it === State.Loading } ?: return null, State.Loading)) {
            ApplicationManager.getApplication().executeOnPooledThread { state.set(State.Ready(load(key), key)) }
        }
        return null
    }

    /**
     * [candidates] (namespaces the index found for the type [name], attributes as `FooAttribute`) in the order of the statistics given the
     * namespaces [currentImports] the file already sees: the ones the model knows for the name best first, then the rest in their given
     * order. The given order when the name is unknown, the model is not loaded or the feature is off.
     */
    fun order(name: String, currentImports: Collection<String>, candidates: List<String>): List<String> {
        if (candidates.size < 2) return candidates
        val model = model() ?: return candidates
        val ranked = model.rankImports(name, currentImports)
        if (ranked.isEmpty()) return candidates
        val position = HashMap<String, Int>(ranked.size * 2)
        ranked.forEachIndexed { i, s -> position.putIfAbsent(s.path, i) }
        if (candidates.none { it in position }) return candidates
        // stable: unknown candidates keep their relative order after the known ones
        return candidates.withIndex().sortedWith(compareBy<IndexedValue<String>> { position[it.value] ?: Int.MAX_VALUE }.thenBy { it.index }).map { it.value }
    }

    /**
     * The position of [namespace] among the model's namespaces for [name] (0 = the most likely) given [currentImports]; null when the
     * model is not there, the name is unknown or the namespace is not among its answers.
     */
    fun rank(name: String, namespace: String, currentImports: Collection<String>): Int? {
        val model = model() ?: return null
        val ranked = model.rankImports(name, currentImports)
        val i = ranked.indexOfFirst { it.path == namespace }
        return if (i < 0) null else i
    }

    private fun load(key: String): ImportsModel? {
        val started = System.currentTimeMillis()
        return try {
            val model = fromDirectory(key.takeIf { it.isNotEmpty() }?.let(::File)) ?: fromResources() ?: fromDirectory(File(DEFAULT_DIRECTORY))
            if (model == null) LOG.info("Import statistics: no $FILE in ${key.ifEmpty { "the plugin" }}, namespaces keep the index order")
            else LOG.info("Import statistics: ${model.nameCount} names, ${model.paths.size} namespaces loaded in ${System.currentTimeMillis() - started} ms")
            model
        } catch (e: Exception) {
            LOG.warn("Import statistics could not be loaded", e)
            null
        }
    }

    private fun fromDirectory(dir: File?): ImportsModel? = dir?.let { File(it, FILE) }?.takeIf { it.isFile }?.let { ImportsModel.read(it) }

    private fun fromResources(): ImportsModel? =
        CSharpImportStats::class.java.classLoader.getResourceAsStream("$RESOURCE_DIR/$FILE")?.use { ImportsModel.read(it, "bundled $FILE") }

    companion object {
        private val LOG = logger<CSharpImportStats>()

        /** The artifact of `ml-models/csharp`. */
        const val FILE = "cs-imports-e20.cml"
        private const val RESOURCE_DIR = "ml/csharp"
        private const val DEFAULT_DIRECTORY = "ml-models/csharp"

        /** On the rows of not-yet-imported types: minus the [rank] of the namespace, for [CSharpImportStatsWeigher]. */
        val WEIGHT: Key<Int> = Key.create("dotnet.importStats")

        /** The model unit tests use (the service loads nothing in tests); reset to null in tearDown. */
        @Volatile var modelForTests: ImportsModel? = null

        fun isEnabled(): Boolean = DotNetSettings.getInstance().importStatistics
        fun isBundled(): Boolean = CSharpImportStats::class.java.classLoader.getResource("$RESOURCE_DIR/$FILE") != null
        fun getInstance(): CSharpImportStats = service()

        /** The value of [WEIGHT] through the decorators (`PrioritizedLookupElement`). */
        fun weightOf(element: LookupElement): Int? {
            var e: LookupElement? = element
            while (e != null) {
                e.getUserData(WEIGHT)?.let { return it }
                e = (e as? LookupElementDecorator<*>)?.delegate
            }
            return null
        }
    }
}

/**
 * After the priority of the kind: among the rows of not-yet-imported types, the namespace the corpus statistics expect for the name (given
 * the file's `using`s) goes first ([CSharpImportStats.WEIGHT], set when the row is built). The ML ranker's weigher runs before the
 * priority and keeps the last word over the whole list; rows without the mark (imported types, members, keywords) weigh 0.
 */
class CSharpImportStatsWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> = CSharpImportStats.weightOf(element) ?: 0
}
