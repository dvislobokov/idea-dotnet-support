package io.github.dotnetsupport.suggest

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import io.github.completionml.core.spi.ContextKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.ml.CSharpMlFeatures
import io.github.dotnetsupport.ml.CSharpMlLanguage
import io.github.dotnetsupport.settings.DotNetSettings
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap
import kotlin.math.ln

/**
 * What the user chose in the completion list of this project ("learns from me", 0.1.135; ML_ACCEPTANCE.md): a counter per (kind of
 * place, lookup string), the kind being the one the ML ranker's schema knows ([ContextKind]: after a dot, the start of a statement, an
 * argument, a type, the right side of `=`, other — [contextOf], the lexer of the engine on the text before the caret). Kept in the
 * workspace file of the project (not shared, not synced); halved on the first use of every new month, so a habit of last year weighs
 * little; capped in count and in size. Read by [CSharpAcceptedBeforeWeigher] (the plugin's own order: among rows of one priority the
 * chosen ones first) and by the ML ranker (`CSharpMlCompletionRanker`: `weight × ln(1 + count)` added to the score). Nothing is recorded,
 * weighed or loaded while `DotNetSettings.rememberChoices` is off.
 */
@Service(Service.Level.PROJECT)
@State(name = "DotNetAcceptanceMemory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CSharpAcceptanceMemory : PersistentStateComponent<CSharpAcceptanceMemory.Data> {
    class Data {
        /** The month (years × 12 + month) the counts were last halved; 0: never. */
        @JvmField var month: Int = 0
        /** `kind|lookupString` → how many times chosen. */
        @JvmField var counts: MutableMap<String, Int> = TreeMap()
    }

    private var data = Data()

    @Synchronized override fun getState(): Data = data

    @Synchronized override fun loadState(state: Data) {
        data = state
        decayIfDue(currentMonth())
    }

    /** An item [name] chosen at a place of [kind]: one more. */
    @Synchronized
    fun record(kind: ContextKind, name: String) {
        val label = label(name) ?: return
        decayIfDue(currentMonth())
        val key = key(kind, label)
        data.counts[key] = minOf(MAX_COUNT, (data.counts[key] ?: 0) + 1)
        if (data.counts.size > MAX_ENTRIES) forgetRare()
    }

    /** How many times [name] was chosen at a place of [kind] (after the decay). */
    @Synchronized
    fun count(kind: ContextKind, name: String): Int = label(name)?.let { data.counts[key(kind, it)] } ?: 0

    /** A snapshot of every counter, `kind|lookupString` → count. */
    @Synchronized fun entries(): Map<String, Int> = TreeMap(data.counts)

    @Synchronized fun size(): Int = data.counts.size

    @Synchronized
    fun reset() {
        data = Data()
    }

    /**
     * Halves every count once per month gone since the last decay ([month] is the current one); drops what reaches zero. The first use
     * only stamps the month.
     */
    @Synchronized
    fun decayIfDue(month: Int) {
        if (data.month == 0) { data.month = month; return }
        val months = month - data.month
        if (months <= 0) return
        data.month = month
        val it = data.counts.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val decayed = if (months >= 31) 0 else entry.value shr months
            if (decayed <= 0) it.remove() else entry.setValue(decayed)
        }
    }

    private fun forgetRare() {
        val kept = data.counts.entries.sortedByDescending { it.value }.take(MAX_ENTRIES * 3 / 4)
        data.counts = TreeMap<String, Int>().apply { kept.forEach { put(it.key, it.value) } }
    }

    companion object {
        const val MAX_COUNT = 1000
        const val MAX_ENTRIES = 2000
        const val MAX_LABEL_LENGTH = 80

        /** How many characters before the caret the kind of the place is read from: the last tokens decide, a whole file is not lexed. */
        private const val WINDOW = 4096

        fun getInstance(project: Project): CSharpAcceptanceMemory = project.service()

        fun isEnabled(): Boolean = DotNetSettings.getInstance().rememberChoices

        fun key(kind: ContextKind, label: String): String = "${kind.name}|$label"

        /** The lookup string as the counts know it: trimmed, `<>` dropped, blank or very long ones not counted. */
        fun label(lookupString: String): String? = lookupString.trim().removeSuffix("<>").takeIf { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH }

        /** The kind of the place at [caret] of [text], by the tokens before it ([CSharpMlFeatures.contextKind]; the identifier being typed is not among them). */
        fun contextOf(text: CharSequence, caret: Int): ContextKind {
            var start = caret.coerceIn(0, text.length)
            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '@')) start--
            val from = maxOf(0, start - WINDOW)
            return runCatching {
                val tokens = CSharpMlLanguage.tokenizer.tokens(text.subSequence(from, start).toString())
                CSharpMlFeatures.contextKind(tokens, tokens.size)
            }.getOrDefault(ContextKind.OTHER)
        }

        /** The bonus for the ML ranker: `weight × ln(1 + count)`, 0 when the memory is off or the weight is. */
        fun bonus(count: Int, weight: Double): Double = if (count <= 0 || weight <= 0.0) 0.0 else weight * ln(1.0 + count)

        /** The kind of the place of one completion, computed once per list ([CompletionLocation] lives as long as the list). */
        val KIND: Key<ContextKind> = Key.create("dotnet.acceptance.kind")

        fun currentMonth(): Int = LocalDate.now(ZoneOffset.UTC).let { it.year * 12 + it.monthValue }
    }
}

/**
 * Among the rows of one priority (after the priority of the kind and the prefix match, as [io.github.dotnetsupport.lang.CSharpSuggestionStatsWeigher]):
 * what was chosen more often at this kind of place in this project goes first. With the ML ranker active the score already carries the
 * memory; here it only breaks ties of equal scores. 0 for everything while the memory is off: nothing is read.
 */
class CSharpAcceptedBeforeWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        if (!CSharpAcceptanceMemory.isEnabled()) return 0
        val parameters = location.completionParameters
        val file = parameters.originalFile as? CSharpFile ?: return 0
        val kind = location.getUserData(CSharpAcceptanceMemory.KIND) ?: CSharpAcceptanceMemory.contextOf(file.viewProvider.contents, parameters.offset).also {
            location.putUserData(CSharpAcceptanceMemory.KIND, it)
        }
        return CSharpAcceptanceMemory.getInstance(file.project).count(kind, element.lookupString)
    }
}

/** Counts every item chosen in a completion list of a C# file of the project ([CSharpAcceptanceMemory]); the kind of the place is read off the EDT. */
class CSharpAcceptanceListener : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        val lookup = newLookup ?: return
        if (lookup.psiFile !is CSharpFile) return
        lookup.addLookupListener(object : LookupListener {
            // the start of the lookup and the text before it are taken while the lookup is alive: at itemSelected it is hidden already
            private var text: CharSequence? = null
            private var caret = -1

            override fun beforeItemSelected(event: LookupEvent): Boolean {
                if (CSharpAcceptanceMemory.isEnabled()) {
                    text = lookup.editor.document.immutableCharSequence
                    caret = lookup.lookupStart
                }
                return true
            }

            override fun itemSelected(event: LookupEvent) {
                if (!CSharpAcceptanceMemory.isEnabled()) return
                val item = event.item ?: return
                val label = CSharpAcceptanceMemory.label(item.lookupString) ?: return
                val project = lookup.project
                val text = text ?: return
                val caret = caret.takeIf { it >= 0 } ?: return
                ApplicationManager.getApplication().executeOnPooledThread {
                    if (project.isDisposed) return@executeOnPooledThread
                    CSharpAcceptanceMemory.getInstance(project).record(CSharpAcceptanceMemory.contextOf(text, caret), label)
                }
            }
        })
    }
}
