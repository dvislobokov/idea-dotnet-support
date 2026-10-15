package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.SemanticTrace
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The debug log of the C# analysis (CSHARP_PSI_MIGRATION.md, D6e1): where the semantics gives up or falls back, a line is written; with the
 * log off nothing is built; collecting the lines in a test leaves the log as it was.
 */
class SemanticTraceTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The lines the analysis of [text] writes to the log. */
    private fun traced(text: String): List<String> = SemanticTrace.collectForTests {
        myFixture.configureByText("Trace${files++}.cs", text.trimIndent())
        myFixture.doHighlighting()
    }.second.toList()

    private fun assertLine(lines: List<String>, prefix: String, vararg parts: String) {
        assertTrue("no line starting with `$prefix` containing ${parts.toList()}; lines:\n" + lines.joinToString("\n"),
            lines.any { line -> line.startsWith(prefix) && parts.all { line.contains(it) } })
    }

    fun testAConversionLeftToTheOldRulesIsLogged() {
        // a list of a type that does not resolve, given to a `string` parameter: the port proves no conversion, the old rules cannot tell
        // (the argument of the list is not known) — a refusal, logged; "no conversion" between two plain types is a decision, not logged
        val lines = traced("""
            using System.Collections.Generic;
            class C { static void Take(string s) { } void M(List<Nowhere> list) { Take(list); } }
        """)
        assertLine(lines, "classify System.Collections.Generic.List<", "port unproved", "old rules UNKNOWN")
    }

    fun testAnInferenceThatGivesUpIsLogged() {
        val lines = traced("""
            using System;
            class C
            {
                static T Pick<T>(Func<T> make) => make();
                void M() { var x = Pick(() => Nowhere.Value); }
            }
        """)
        assertLine(lines, "inference Pick", "Unknown")
        assertLine(lines, "typeArguments Pick", "the simple one answers")
    }

    fun testNothingIsBuiltWhenTheLogIsOff() {
        val was = SemanticTrace.enabled
        SemanticTrace.enabled = false
        try {
            SemanticTrace.trace { fail("built"); "" }
        } finally {
            SemanticTrace.enabled = was
        }
    }

    fun testCollectingLeavesTheLogAlone() {
        val was = SemanticTrace.enabled
        try {
            for (state in listOf(false, true)) {
                SemanticTrace.enabled = state
                val (result, lines) = SemanticTrace.collectForTests { SemanticTrace.write("one"); SemanticTrace.trace { "two" }; 42 }
                assertEquals(42, result)
                assertEquals(listOf("one", "two"), lines)
                assertEquals(state, SemanticTrace.enabled)
            }
        } finally {
            SemanticTrace.enabled = was
        }
    }
}
