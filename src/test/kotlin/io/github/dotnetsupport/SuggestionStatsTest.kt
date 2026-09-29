package io.github.dotnetsupport

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpGhostText
import io.github.dotnetsupport.suggest.CompletionAcceptListener
import io.github.dotnetsupport.suggest.SuggestionReport
import io.github.dotnetsupport.suggest.SuggestionRules
import io.github.dotnetsupport.suggest.SuggestionStats

/** How often a suggestion is shown and taken: counted on the machine, shown in .NET | Suggestion Statistics. */
class SuggestionStatsTest : BasePlatformTestCase() {
    fun testGrayTextIsCountedOncePerPlace() {
        val stats = SuggestionStats()
        // `public string Na`, `Nam`, `Name`: the same suggestion at every letter
        repeat(3) { stats.shown(SuggestionRules.AUTO_PROPERTY, "Order.cs:5") }
        stats.shown(SuggestionRules.AUTO_PROPERTY, "Order.cs:6")
        stats.accepted(SuggestionRules.AUTO_PROPERTY)
        // taken, and the next one on the same line is a new one
        stats.shown(SuggestionRules.AUTO_PROPERTY, "Order.cs:6")
        stats.shown(SuggestionRules.CATCH, "Order.cs:6")

        assertEquals(3, stats.state.shown[SuggestionRules.AUTO_PROPERTY])
        assertEquals(1, stats.state.accepted[SuggestionRules.AUTO_PROPERTY])
        assertEquals(1, stats.state.shown[SuggestionRules.CATCH])
        assertNull(stats.state.accepted[SuggestionRules.CATCH])
    }

    fun testChosenItemsOfTheList() {
        val stats = SuggestionStats()
        stats.completionAccepted(0, setOf(SuggestionRules.SIGNAL_TYPE, SuggestionRules.SIGNAL_LOCAL), "count")
        stats.completionAccepted(0, emptySet(), "count")
        stats.completionAccepted(2, setOf(SuggestionRules.SIGNAL_NAME), "order")
        stats.completionAccepted(7, emptySet(), "ToString")
        stats.completionAccepted(40, emptySet(), "")
        stats.completionAccepted(-1, emptySet(), "x".repeat(200))

        assertEquals(mapOf("first" to 3, "2-3" to 1, "4-10" to 1, "lower" to 1), stats.state.positions.toMap())
        assertEquals(2, stats.labelCount("count"))
        assertEquals(1, stats.labelCount("order"))
        assertEquals(0, stats.labelCount("never"))
        assertEquals("neither an empty label nor a text is a name", setOf("count", "order", "ToString"), stats.state.labels.keys)
        assertEquals(1, stats.state.signals[SuggestionRules.SIGNAL_TYPE])

        for (i in 0 until SuggestionStats.MAX_LABELS + 10) stats.completionAccepted(0, emptySet(), "name$i")
        assertTrue(stats.state.labels.size <= SuggestionStats.MAX_LABELS)
        assertEquals("the ones chosen most stay", 2, stats.labelCount("count"))

        stats.reset()
        assertTrue(stats.state.labels.isEmpty() && stats.state.positions.isEmpty() && stats.state.shown.isEmpty())
    }

    fun testReport() {
        val stats = SuggestionStats()
        assertTrue(stats.report().contains("nothing yet"))
        repeat(4) { stats.shown(SuggestionRules.INITIALIZER, "A.cs:$it") }
        stats.accepted(SuggestionRules.INITIALIZER)
        stats.accepted(SuggestionRules.LAMBDA)
        stats.completionAccepted(0, setOf(SuggestionRules.SIGNAL_TYPE), "count")
        stats.completionAccepted(5, emptySet(), "order")
        val report = stats.report()
        assertTrue(report, Regex("""new\(\)\s+4\s+1\s+25%""").containsMatchIn(report))
        assertTrue("taken before the counting of the shown began", Regex("""lambda\s+1\s+1\s+100%""").containsMatchIn(report))
        assertTrue(report, report.contains("Completion list: 2 chosen"))
        assertTrue(report, Regex("""position first\s+1\s+50%""").containsMatchIn(report))
        assertTrue(report, Regex("""expected type\s+1\s+50%""").containsMatchIn(report))
        assertEquals("-", SuggestionReport.rate(0, 0))
        assertEquals("33%", SuggestionReport.rate(1, 3))
    }

    fun testStateSurvivesSaving() {
        val stats = SuggestionStats()
        stats.shown(SuggestionRules.CATCH, "A.cs:1")
        stats.completionAccepted(0, emptySet(), "count")
        val saved = com.intellij.util.xmlb.XmlSerializer.serialize(stats.state)
        val loaded = com.intellij.util.xmlb.XmlSerializer.deserialize(saved, SuggestionStats.Data::class.java)
        assertEquals(1, loaded.shown[SuggestionRules.CATCH])
        assertEquals(1, loaded.labels["count"])
        assertEquals(1, loaded.positions["first"])
    }

    fun testSignsAreFoundUnderTheWrappersOfThePlatform() {
        val inner = PrioritizedLookupElement.withPriority(LookupElementBuilder.create("count"), 65.0)
        inner.putUserData(SuggestionStats.SIGNALS, setOf(SuggestionRules.SIGNAL_TYPE))
        val wrapped = LookupElementDecorator.withInsertHandler(inner) { _, _ -> }
        assertEquals(setOf(SuggestionRules.SIGNAL_TYPE), CompletionAcceptListener.signalsOf(wrapped))
        assertEquals(emptySet<String>(), CompletionAcceptListener.signalsOf(LookupElementBuilder.create("plain")))
    }

    fun testEveryGhostHasItsRule() {
        fun rule(text: String): String? {
            val offset = text.indexOf('|')
            return CSharpGhostText.ghost(text.removeRange(offset, offset + 1), offset)?.rule
        }
        assertEquals(SuggestionRules.AUTO_PROPERTY, rule("class A\n{\n    public string Name|\n}\n"))
        assertEquals(SuggestionRules.INITIALIZER, rule("class A\n{\n    private readonly List<int> _items = |\n}\n"))
        assertEquals(SuggestionRules.CATCH, rule("class A\n{\n    void M()\n    {\n        try { } catch|\n    }\n}\n"))
        assertEquals(SuggestionRules.CONSTRUCTOR_ASSIGNMENT, rule("class A\n{\n    private string _name;\n    public A(string name)\n    {\n        |\n    }\n}\n"))
        assertNull(rule("class A\n{\n    void M() { |\n}\n"))
    }

    fun testTheReportIsInTheMenu() {
        val actions = ActionManager.getInstance()
        val menu = actions.getAction("DotNet.MainMenu") as DefaultActionGroup
        assertTrue(menu.childActionsOrStubs.any { actions.getId(it) == "DotNet.SuggestionStats" })
    }
}
