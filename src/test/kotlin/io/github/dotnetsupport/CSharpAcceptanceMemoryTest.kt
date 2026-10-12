package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.completionml.core.ngram.NgramModel
import io.github.completionml.core.rank.Rankers
import io.github.completionml.core.spi.ContextKind
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.ml.CSharpMlCandidate
import io.github.dotnetsupport.ml.CSharpMlCandidateKind
import io.github.dotnetsupport.ml.CSharpMlCompletionRanker
import io.github.dotnetsupport.ml.CSharpMlModels
import io.github.dotnetsupport.ml.CSharpMlScope
import io.github.dotnetsupport.ml.CSharpMlSettings
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.suggest.CSharpAcceptanceMemory
import io.github.dotnetsupport.suggest.SuggestionStats
import java.io.File
import kotlin.math.ln

/**
 * The memory of chosen items (0.1.135, [CSharpAcceptanceMemory], ML_ACCEPTANCE.md): counts kept and halved by the month, the weigher
 * that lifts the chosen among rows of one priority, the bonus of the ML ranker, a real choice in the list recorded, and no effect
 * with the setting off.
 */
class CSharpAcceptanceMemoryTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true
    private var files = 0
    private val memory get() = CSharpAcceptanceMemory.getInstance(project)
    // the machine-wide statistics of 0.1.91 count the same choices: kept out of the way
    private var savedStats: SuggestionStats.Data? = null

    override fun setUp() {
        super.setUp()
        savedStats = SuggestionStats.getInstance().state
        SuggestionStats.getInstance().loadState(SuggestionStats.Data())
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        DotNetSettings.getInstance().rememberChoices = true
        memory.reset()
    }

    override fun tearDown() {
        try {
            savedStats?.let { SuggestionStats.getInstance().loadState(it) }
            memory.reset()
            DotNetSettings.getInstance().rememberChoices = true
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            runCatching { myFixture.lookup?.hideLookup(true) }
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Memory${files++}.cs", text)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun native(text: String): List<String> = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    private fun assertOrder(list: List<String>, vararg names: String) {
        val positions = names.map { name -> list.indexOf(name).also { assertTrue("$name in $list", it >= 0) } }
        assertEquals("the order of ${names.toList()} in $list", positions.sorted(), positions)
    }

    // ---- the counts

    fun testCountsPersistAndDecayByTheMonth() {
        val month = CSharpAcceptanceMemory.currentMonth()
        repeat(3) { memory.record(ContextKind.STATEMENT_START, "zeta") }
        memory.record(ContextKind.AFTER_DOT, "zeta")
        assertEquals(3, memory.count(ContextKind.STATEMENT_START, "zeta"))
        assertEquals(1, memory.count(ContextKind.AFTER_DOT, "zeta"))
        assertEquals(0, memory.count(ContextKind.ARGUMENT, "zeta"))
        // the state as the workspace file holds it, loaded again
        val saved = memory.state
        assertEquals(mapOf("STATEMENT_START|zeta" to 3, "AFTER_DOT|zeta" to 1), saved.counts)
        assertEquals(month, saved.month)
        memory.reset()
        assertEquals(0, memory.count(ContextKind.STATEMENT_START, "zeta"))
        memory.loadState(saved)
        assertEquals(3, memory.count(ContextKind.STATEMENT_START, "zeta"))
        // a month later: halved; three months later: gone
        memory.decayIfDue(month + 1)
        assertEquals(1, memory.count(ContextKind.STATEMENT_START, "zeta"))
        assertEquals(0, memory.count(ContextKind.AFTER_DOT, "zeta"))
        assertEquals(mapOf("STATEMENT_START|zeta" to 1), memory.entries())
        memory.decayIfDue(month + 3)
        assertEquals(emptyMap<String, Int>(), memory.entries())
        // not counted at all: blank, too long, `<>` dropped
        memory.record(ContextKind.OTHER, "  ")
        memory.record(ContextKind.OTHER, "x".repeat(200))
        memory.record(ContextKind.OTHER, "List<>")
        assertEquals(mapOf("OTHER|List" to 1), memory.entries())
    }

    fun testTheKindOfThePlace() {
        assertEquals(ContextKind.AFTER_DOT, CSharpAcceptanceMemory.contextOf("class C { void M() { order.Na", 29))
        assertEquals(ContextKind.STATEMENT_START, CSharpAcceptanceMemory.contextOf("class C { void M() { Na", 23))
        assertEquals(ContextKind.ARGUMENT, CSharpAcceptanceMemory.contextOf("class C { void M() { Save(or", 28))
        assertEquals(ContextKind.ASSIGN_RHS, CSharpAcceptanceMemory.contextOf("class C { void M() { var x = or", 31))
        assertEquals(ContextKind.OTHER, CSharpAcceptanceMemory.contextOf("", 0))
    }

    // ---- the weigher

    fun testChosenBeforeGoesUpAmongItsPriorityOnly() {
        val text = "class Stats { int alpha; int zeta; void Beta() { } void Omega() { } void M() { int local = 0; <caret> } }"
        val before = native(text)
        assertOrder(before, "local", "alpha", "zeta")
        assertOrder(before, "Beta", "Omega")
        myFixture.lookup?.hideLookup(true)
        repeat(3) { memory.record(ContextKind.STATEMENT_START, "zeta") }
        repeat(2) { memory.record(ContextKind.STATEMENT_START, "Omega") }
        repeat(9) { memory.record(ContextKind.STATEMENT_START, "while") }
        // another kind of place: no effect here
        repeat(9) { memory.record(ContextKind.AFTER_DOT, "alpha") }
        val after = native(text)
        assertOrder(after, "local", "zeta", "alpha", "Omega", "Beta", "Stats", "while")
    }

    fun testAChoiceInTheListIsRecorded() {
        val elements = lookup("class Stats { int alpha; int zeta; void M() { ze<caret> } }")
        myFixture.lookup.currentItem = elements.first { it.lookupString == "zeta" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        // the kind is read on a pooled thread
        val deadline = System.currentTimeMillis() + 10_000
        while (memory.count(ContextKind.STATEMENT_START, "zeta") == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, memory.count(ContextKind.STATEMENT_START, "zeta"))
    }

    fun testOffNothingIsWeighedOrRecorded() {
        DotNetSettings.getInstance().rememberChoices = false
        val text = "class Stats { int alpha; int zeta; void M() { <caret> } }"
        repeat(5) { memory.record(ContextKind.STATEMENT_START, "zeta") }
        assertOrder(native(text), "alpha", "zeta")
        myFixture.lookup?.hideLookup(true)
        assertNull(CSharpMlCompletionRanker.acceptanceBonus(project))
        val elements = lookup("class Stats { int alpha; int zeta; void M() { ze<caret> } }")
        myFixture.lookup.currentItem = elements.first { it.lookupString == "zeta" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        Thread.sleep(300)
        assertEquals(5, memory.count(ContextKind.STATEMENT_START, "zeta"))
        DotNetSettings.getInstance().rememberChoices = true
        assertOrder(native(text), "zeta", "alpha")
    }

    // ---- the ML ranker

    fun testTheBonusOfTheRanker() {
        assertEquals(0.0, CSharpAcceptanceMemory.bonus(0, 0.3))
        assertEquals(0.0, CSharpAcceptanceMemory.bonus(5, 0.0))
        assertEquals(0.3 * ln(4.0), CSharpAcceptanceMemory.bonus(3, 0.3), 1e-9)
        val modelDir = listOf(File("ml-models/csharp"), File("../ml-models/csharp")).firstOrNull { File(it, CSharpMlModels.LM).isFile && File(it, CSharpMlModels.RANKER).isFile }
        if (modelDir == null) { println("CSharpAcceptanceMemoryTest: no ml-models/csharp, the bonus in the score is not checked"); return }
        val models = CSharpMlModels.Loaded(NgramModel.read(File(modelDir, CSharpMlModels.LM)), Rankers.read(File(modelDir, CSharpMlModels.RANKER)), modelDir.path)
        val text = "class Stats { int alpha; int zeta; void M() { "
        val candidates = listOf("alpha", "zeta").map { CSharpMlCandidate(it, CSharpMlCandidateKind.FIELD, false, CSharpMlScope.MEMBER, false, 0, null, 40.0) }
        val plain = CSharpMlCompletionRanker.scores(models, text, text.length, "", candidates)!!
        repeat(3) { memory.record(ContextKind.STATEMENT_START, "zeta") }
        val weight = CSharpMlSettings.getInstance().acceptanceWeight
        assertEquals(0.3, weight, 1e-6)
        val bonus = CSharpMlCompletionRanker.acceptanceBonus(project)!!
        assertEquals(0.3 * ln(4.0), bonus(ContextKind.STATEMENT_START, "zeta"), 1e-9)
        assertEquals(0.0, bonus(ContextKind.AFTER_DOT, "zeta"))
        val boosted = CSharpMlCompletionRanker.scores(models, text, text.length, "", candidates, bonus)!!
        assertEquals(plain[0].value, boosted[0].value, 1e-9)
        assertEquals(plain[1].value + 0.3 * ln(4.0), boosted[1].value, 1e-9)
    }
}
