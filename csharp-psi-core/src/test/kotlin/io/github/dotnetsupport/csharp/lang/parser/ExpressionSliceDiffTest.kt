package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.oracle.DiffReport
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import java.nio.file.Paths

/**
 * Fast gate of the step-0 slice: the hand-written snippets of `testData/parser/slice` (`*.expr`, `*.stmt`) against
 * `roslyndump expr|stmt`. Valid snippets (no Roslyn diagnostic) must match exactly and produce no error element;
 * invalid ones must not throw and must match too (what the comparison covers for them: [SliceGate]). Every snippet
 * is diffed: the parser ports the whole grammar. Mismatch report: `build/roslyndump/slice-mismatches.txt` and
 * `build/slice-gate/slice.txt`.
 */
class ExpressionSliceDiffTest : CSharpParsingTestCase("parser/slice") {

    fun testSlice() {
        val root = Paths.get(CSharpTestUtil.testDataPath("parser/slice"))
        val gate = SliceGate("slice")
        gate.run(root, "expr")
        gate.run(root, "stmt")
        val reportFile = gate.writeReport(CSharpTestUtil.buildDir("slice-gate"))
        println(gate.summary())
        println("  report: $reportFile")
        DiffReport.report("slice", gate.diff, facts = mapOf("summary" to gate.summary()))
        gate.exceptions.forEach { println("  EXCEPTION $it") }
        assertTrue("exceptions:\n" + gate.exceptions.joinToString("\n"), gate.exceptions.isEmpty())
        assertEquals("valid snippets with mismatches (see $reportFile)", 0L, gate.stats.validMismatchedFiles)
        assertEquals("invalid snippets with mismatches (see $reportFile)", 0L, gate.stats.invalidMismatchedFiles)
    }

    /** A valid snippet whose parse has an error element is a mismatch even when the trees align (`spurious error`). */
    fun testSpuriousErrorIsAMismatch() {
        val result = SliceParseHarness.parse("a +", statement = false)
        assertTrue(result.errorElements > 0)
        // The oracle's tree is replaced by our own, with no diagnostics: only the error elements can differ.
        val dump = DumpFile("synthetic.expr", result.roots, trivia = emptyList(), diagnostics = emptyList())
        val gate = SliceGate("synthetic")
        gate.compareParsed(dump, "a +", result)
        assertEquals(1L, gate.stats.validSpuriousErrorFiles)
        assertEquals(1L, gate.stats.validMismatchedFiles)
        assertEquals("spurious error", gate.diff.mismatches.single().cls)
    }

    /** No node kind is skipped any more: a tree with a local function is diffed like any other (here: a mismatch). */
    fun testEverySnippetIsDiffed() {
        val result = SliceParseHarness.parse("x = 1;", statement = true)
        val fake = io.github.dotnetsupport.csharp.lang.oracle.DumpNode("LocalFunctionStatement", 0, 6, result.roots)
        val dump = DumpFile("synthetic.stmt", listOf(fake), trivia = emptyList(), diagnostics = emptyList())
        val gate = SliceGate("synthetic")
        gate.compareParsed(dump, "x = 1;", result)
        assertEquals(1L, gate.stats.validFiles)
        assertEquals(1L, gate.stats.validMismatchedFiles)
    }
}
