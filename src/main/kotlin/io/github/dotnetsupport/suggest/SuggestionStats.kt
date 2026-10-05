package io.github.dotnetsupport.suggest

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import io.github.dotnetsupport.lang.CSharpFile
import java.awt.datatransfer.StringSelection
import java.util.Locale
import java.util.TreeMap

/** The names of the suggestions that are counted, as the report shows them. */
object SuggestionRules {
    const val AUTO_PROPERTY = "auto-property"
    const val INITIALIZER = "new()"
    const val CONSTRUCTOR_ASSIGNMENT = "constructor assignment"
    const val NAMESPACE = "namespace"
    const val TYPE_NAME = "type name"
    const val LOGGER = "ILogger<>"
    const val CONSTRUCTOR_PARAMETERS = "constructor parameters"
    const val CATCH = "catch"
    const val VALUE = "value"
    const val ARGUMENTS = "arguments"
    const val LAMBDA = "lambda"
    const val SEMICOLON = "semicolon"
    const val NOT_IMPLEMENTED = "not implemented"
    const val BREAK = "break"
    const val TASK_RETURN = "Task return"
    const val NEW_BY_NAME = "new by name"
    const val CLOSE_CALL = "close call"
    const val FILL_INITIALIZER = "fill initializer"
    const val MEMBER_VALUE = "member value"
    const val DECLARATION_NAME = "declaration name"
    const val AFTER_LOOKUP_ITEM = "after the selected item"

    /** What made an item of the completion list go up. */
    const val SIGNAL_TYPE = "expected type"
    const val SIGNAL_NAME = "name"
    const val SIGNAL_LOCAL = "declared nearby"
    const val SIGNAL_USED = "chosen before"

    /** An item of the index of assemblies: a static member of a type that was not imported. */
    const val SIGNAL_INDEX = "not imported"
}

/**
 * How often a suggestion is shown and how often it is taken: without the numbers there is no telling a rule that helps from one that
 * is in the way. Kept on this machine only, in the settings of the IDE; nothing is sent anywhere. The report is .NET | Suggestion
 * Statistics.
 */
@Service(Service.Level.APP)
@State(name = "DotNetSuggestionStats", storages = [Storage("dotnetSuggestionStats.xml", roamingType = RoamingType.DISABLED)])
class SuggestionStats : PersistentStateComponent<SuggestionStats.Data> {
    class Data {
        /** Gray text: the rule -> how many times. */
        @JvmField var shown: MutableMap<String, Int> = TreeMap()
        @JvmField var accepted: MutableMap<String, Int> = TreeMap()

        /** The completion list: where the chosen item stood, and what had moved it there. */
        @JvmField var positions: MutableMap<String, Int> = TreeMap()
        @JvmField var signals: MutableMap<String, Int> = TreeMap()

        /** What was chosen in the list, to offer it sooner next time. */
        @JvmField var labels: MutableMap<String, Int> = TreeMap()
    }

    private var data = Data()
    private val lastPlace = HashMap<String, String>()

    @Synchronized override fun getState(): Data = data

    @Synchronized override fun loadState(state: Data) {
        data = state
    }

    /**
     * Gray text of [rule] is on the screen at [place] (a file and a line). While a name is typed the same suggestion comes again at
     * every letter: it is one suggestion, counted once.
     */
    @Synchronized
    fun shown(rule: String, place: String) {
        if (lastPlace.put(rule, place) == place) return
        data.shown.merge(rule, 1, Int::plus)
    }

    @Synchronized
    fun accepted(rule: String) {
        data.accepted.merge(rule, 1, Int::plus)
        // the next one at the same place is a new one
        lastPlace.remove(rule)
    }

    /** An item of the completion list is chosen: [position] from 0, [signals] the reasons the plugin had moved it up for. */
    @Synchronized
    fun completionAccepted(position: Int, signals: Collection<String>, label: String) {
        data.positions.merge(bucket(position), 1, Int::plus)
        for (signal in signals) data.signals.merge(signal, 1, Int::plus)
        if (label.isNotBlank() && label.length <= MAX_LABEL_LENGTH) {
            data.labels.merge(label, 1, Int::plus)
            if (data.labels.size > MAX_LABELS) forgetRareLabels()
        }
    }

    @Synchronized fun labelCount(label: String): Int = data.labels[label] ?: 0

    @Synchronized
    fun reset() {
        data = Data()
        lastPlace.clear()
    }

    @Synchronized fun report(): String = SuggestionReport.text(data)

    private fun forgetRareLabels() {
        val kept = data.labels.entries.sortedByDescending { it.value }.take(MAX_LABELS * 3 / 4)
        data.labels = TreeMap<String, Int>().apply { kept.forEach { put(it.key, it.value) } }
    }

    companion object {
        const val MAX_LABELS = 400
        const val MAX_LABEL_LENGTH = 80
        val BUCKETS = listOf("first", "2-3", "4-10", "lower")

        /** On the element of the list the plugin has made: why it stands where it does. */
        val SIGNALS: Key<Set<String>> = Key.create("dotnet.completion.signals")

        fun getInstance(): SuggestionStats = service()

        fun bucket(position: Int): String = when {
            position <= 0 -> BUCKETS[0]
            position <= 2 -> BUCKETS[1]
            position <= 9 -> BUCKETS[2]
            else -> BUCKETS[3]
        }
    }
}

/** The numbers as a text table: fixed width, to be read in a dialog and pasted anywhere. */
object SuggestionReport {
    fun text(data: SuggestionStats.Data): String = buildString {
        appendLine("Gray text                 shown  taken   rate")
        val rules = (data.shown.keys + data.accepted.keys).toSortedSet()
        if (rules.isEmpty()) appendLine("  nothing yet")
        for (rule in rules) {
            val shown = data.shown[rule] ?: 0
            val accepted = data.accepted[rule] ?: 0
            // taken without being counted as shown: the counting began in between
            appendLine(String.format(Locale.ROOT, "  %-22s %6d %6d  %5s", rule, maxOf(shown, accepted), accepted, rate(accepted, maxOf(shown, accepted))))
        }
        appendLine()
        val total = data.positions.values.sum()
        appendLine("Completion list: $total chosen")
        for (bucket in SuggestionStats.BUCKETS) {
            val count = data.positions[bucket] ?: 0
            appendLine(String.format(Locale.ROOT, "  %-22s %6d         %5s", "position $bucket", count, rate(count, total)))
        }
        if (data.signals.isNotEmpty()) {
            appendLine()
            appendLine("Chosen items the plugin had moved up, by reason")
            for ((signal, count) in data.signals) appendLine(String.format(Locale.ROOT, "  %-22s %6d         %5s", signal, count, rate(count, total)))
        }
    }.trimEnd()

    fun rate(part: Int, whole: Int): String = if (whole <= 0) "-" else String.format(Locale.ROOT, "%d%%", Math.round(part * 100.0 / whole))
}

/** Counts what is chosen in the completion list of a C# file, and where it stood. */
class CompletionAcceptListener : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        val lookup = newLookup ?: return
        if (lookup.psiFile !is CSharpFile) return
        lookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val item = event.item ?: return
                val position = lookup.items.indexOf(item)
                SuggestionStats.getInstance().completionAccepted(position, signalsOf(item), item.lookupString.removeSuffix("<>"))
            }
        })
    }

    companion object {
        /** The platform wraps the element of the plugin into its own: the reasons are on one of the wrapped. */
        fun signalsOf(item: LookupElement): Set<String> {
            var element: LookupElement? = item
            while (element != null) {
                element.getUserData(SuggestionStats.SIGNALS)?.let { return it }
                element = (element as? LookupElementDecorator<*>)?.delegate
            }
            return emptySet()
        }
    }
}

class ShowSuggestionStatsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stats = SuggestionStats.getInstance()
        val report = stats.report()
        val choice = Messages.showDialog(project, "<html><pre>$report</pre>Counted on this machine only.</html>", "Suggestion Statistics",
            arrayOf("Close", "Copy", "Reset"), 0, Messages.getInformationIcon())
        when (choice) {
            1 -> CopyPasteManager.getInstance().setContents(StringSelection(report))
            2 -> stats.reset()
        }
    }
}
