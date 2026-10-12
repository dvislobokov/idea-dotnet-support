package io.github.dotnetsupport.ml

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.util.Key
import com.intellij.openapi.project.Project
import io.github.completionml.core.rank.FeatureSchema
import io.github.completionml.core.rank.FileState
import io.github.completionml.core.spi.ContextKind
import io.github.dotnetsupport.lang.NativeCSharpMappingCompletion
import io.github.dotnetsupport.lang.NativeCSharpMlInfo
import io.github.dotnetsupport.suggest.CSharpAcceptanceMemory

/**
 * The ML ranking of the native completion list (ML_RANKER_EXPORT_TASK.md §4, ADAPTER.md §4): the file before the caret feeds the per-file
 * cache of the language model, every candidate gets the 13 common features (`FeatureExtractor` of the engine) and the 19 language
 * features ([CSharpMlFeatures.languageBlock]) — the very code of the offline export (`CSharpMlExporter`) — and the linear ranker
 * `e18-rank.cml` scores them. The contributor collects the whole list first ([Batch]: the native items and what the other contributors
 * pass through), because the list-relative features need all of it, then adds every item with its score ([SCORE], read by
 * [CSharpMlCompletionWeigher]) and a grey "ML" mark. Abstains (the plugin's own order) when the ranker is off, the models are not loaded
 * yet (loading starts in the background), or the list is trivial.
 *
 * Parity with the export: the same text (the original file, not the completion copy), the same tokens (up to the caret minus the typed
 * prefix), the same candidates (one per lookup string, `NativeCSharpMlInfo.candidateOf`, in [CSharpMlFeatures.ordered] order), the same
 * feature code — `CSharpMlRankerParityTest`.
 */
object CSharpMlCompletionRanker {
    /** The score of the ranker on an item of a list it ordered, and the features it was computed from (tests). */
    class Score(val value: Double, val features: FloatArray)

    val SCORE: Key<Score> = Key.create("dotnet.mlScore")

    /** A ranker pair other than the service's, for tests (the vocabulary of the fixture, no n-gram). */
    @Volatile internal var modelsForTests: CSharpMlModels.Loaded? = null

    /** The marker shown after a scored row, or null when the setting hides it. */
    val marker: String? get() = if (CSharpMlSettings.getInstance().showMarker) "ML" else null

    /** The models when the ranking is on and they are loaded (the first call starts loading); null to abstain. Never blocks. */
    fun models(): CSharpMlModels.Loaded? {
        modelsForTests?.let { return it }
        val settings = CSharpMlSettings.getInstance()
        if (!settings.rankerEnabled) return null
        if (!CSharpMlModels.isRankerBundled && settings.modelDirectory.isBlank()) return null
        return CSharpMlModels.getInstance().get(settings.modelDirectory)
    }

    /**
     * Scores the candidates of one list, in list order: [text] is the file as it is (the identifier being typed included), [caret] the
     * offset of the caret in it, [prefix] what is typed of the identifier. Null when the list has fewer than two candidates.
     */
    fun scores(
        models: CSharpMlModels.Loaded, text: CharSequence, caret: Int, prefix: String, candidates: List<CSharpMlCandidate>,
        bonus: ((ContextKind, String) -> Double)? = null,
    ): List<Score>? {
        if (candidates.size < 2) return null
        // the tokens before the caret, without the prefix being typed: what the export fed to the file state
        val end = (caret - prefix.length).coerceIn(0, text.length)
        val tokens = CSharpMlLanguage.tokenizer.tokens(text.subSequence(0, end).toString())
        val state = FileState(models.lm.vocab, withCache = CSharpMlModels.CACHE_LAMBDA > 0)
        state.addAll(tokens)
        val names = Array(candidates.size) { candidates[it].lookupString }
        val afterDot = CSharpMlFeatures.isAfterDot(tokens, tokens.size)
        val language = CSharpMlFeatures.languageBlock(caret, afterDot, candidates)
        val base = models.extractor.features(state, prefix, names, language)
        val kind = CSharpMlFeatures.contextKind(tokens, tokens.size)
        return List(candidates.size) { c ->
            val full = FloatArray(models.ranker.schema.size)
            FeatureSchema.expand(base[c], kind, full)
            // the memory of chosen items (0.1.135, CSharpAcceptanceMemory): an additive bonus on the ranker's scale
            Score(models.ranker.score(full).toDouble() + (bonus?.invoke(kind, names[c]) ?: 0.0), base[c])
        }
    }

    /** The bonus of the memory of chosen items for the lists of [project]: null when the memory or its weight is off (nothing is read then). */
    fun acceptanceBonus(project: Project): ((ContextKind, String) -> Double)? {
        if (!CSharpAcceptanceMemory.isEnabled()) return null
        val weight = CSharpMlSettings.getInstance().acceptanceWeight
        if (weight <= 0.0) return null
        val memory = CSharpAcceptanceMemory.getInstance(project)
        return { kind, name -> CSharpAcceptanceMemory.bonus(memory.count(kind, name), weight) }
    }

    /** Starts a batch for [parameters] when the ranking is active, else null (the contributor then adds its items directly). */
    fun batch(parameters: CompletionParameters, result: CompletionResultSet): Batch? {
        val models = models() ?: return null
        return Batch(models, parameters, result)
    }

    /**
     * The items of one list, held back until the contributor has them all, then scored and added. The native items go through
     * [add], the results of the other contributors through [pass] (their own matcher and sorter stay); [flush] adds everything.
     */
    class Batch(private val models: CSharpMlModels.Loaded, private val parameters: CompletionParameters, private val result: CompletionResultSet) {
        private val own = ArrayList<LookupElement>()
        private val passed = ArrayList<CompletionResult>()

        /** What the result set would keep: the items that match its prefix (the native list is made for the place, not for the prefix). */
        fun add(element: LookupElement) { if (result.prefixMatcher.prefixMatches(element)) own.add(element) }
        fun pass(found: CompletionResult) { passed.add(found) }

        fun flush() {
            val elements = own + passed.map { it.lookupElement }
            val scored = score(elements)
            for (e in own) result.addElement(scored[e] ?: e)
            for (r in passed) {
                val wrapped = scored[r.lookupElement]
                if (wrapped == null) result.passResult(r) else CompletionResult.wrap(wrapped, r.prefixMatcher, r.sorter)?.let(result::passResult)
            }
            own.clear(); passed.clear()
        }

        /** Every element with its score attached (through the marker decorator), by identity; empty when the list is trivial. */
        private fun score(elements: List<LookupElement>): Map<LookupElement, LookupElement> {
            val candidates = CSharpMlFeatures.ordered(elements.map(NativeCSharpMlInfo::candidateOf).distinctBy { it.lookupString })
            val text = parameters.originalFile.viewProvider.contents
            val prefix = result.prefixMatcher.prefix
            val scores = scores(models, text, parameters.offset, prefix, candidates, acceptanceBonus(parameters.originalFile.project)) ?: return emptyMap()
            val byName = HashMap<String, Score>(scores.size * 2)
            for (i in candidates.indices) byName[candidates[i].lookupString] = scores[i]
            val marker = marker
            return elements.associateWithTo(java.util.IdentityHashMap()) { e ->
                // the mapping rows (0.1.134) keep their own place: first when the context is clearly a mapping, last otherwise
                if (NativeCSharpMappingCompletion.isMappingRow(e)) return@associateWithTo e
                val score = byName[e.lookupString] ?: return@associateWithTo e
                Marked(e, marker).also { it.putUserData(SCORE, score) }
            }
        }
    }

    /** The row of a scored item: the delegate's presentation with the grey marker after it; the delegate's user data shows through. */
    class Marked(delegate: LookupElement, private val marker: String?) : LookupElementDecorator<LookupElement>(delegate) {
        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            if (marker != null) presentation.appendTailText(" $marker", true)
        }

        override fun <T : Any?> getUserData(key: Key<T>): T? = super.getUserData(key) ?: delegate.getUserData(key)
    }

    /** The score of an item of a list the ranker ordered, through decorators; null for an item it did not score. */
    fun scoreOf(element: LookupElement): Score? {
        var e: LookupElement? = element
        while (e != null) {
            e.getUserData(SCORE)?.let { return it }
            e = (e as? LookupElementDecorator<*>)?.delegate
        }
        return null
    }
}

/**
 * Orders the rows by the ranker's score (larger first; the platform orders completion weigher results descending), before the priority
 * of the plugin's rules, which then only breaks ties. Rows without a score (a list the ranker did not see, the second Ctrl+Space) keep
 * the order of the rules among themselves, after the scored ones.
 */
class CSharpMlCompletionWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        val score = CSharpMlCompletionRanker.scoreOf(element) ?: return if (NativeCSharpMappingCompletion.isTopRow(element)) MAPPING_TOP else UNSCORED
        return score.value
    }

    private companion object {
        val UNSCORED = Double.NEGATIVE_INFINITY
        /** A mapping row of a context that is clearly a mapping (0.1.134): before every scored row. */
        val MAPPING_TOP = Double.POSITIVE_INFINITY
    }
}
