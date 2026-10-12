package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.oracle.DiffReport
import java.nio.file.Paths

/**
 * Fast gate of the doc comments (step 5 of docs/csharp-psi/PLAN.md): the hand-written files of `testData/parser/doc` against
 * `roslyndump tree` through the file parser, every doc comment expanded and compared with Roslyn's structured trivia by
 * [DocCommentDiff] (inside [SliceGate]): XML constructs, cref and name attribute forms, malformed XML, `/** */` with
 * and without leading `*`. Valid and invalid alike must match. Report: `build/slice-gate/doc.txt`.
 */
class DocCommentDiffTest : CSharpParsingTestCase("parser/doc") {

    fun testDocComments() {
        val root = Paths.get(CSharpTestUtil.testDataPath("parser/doc"))
        val gate = SliceGate("doc")
        gate.run(root, SliceParseHarness.Mode.File, listOf { _ -> true })
        val reportFile = gate.writeReport(CSharpTestUtil.buildDir("slice-gate"))
        println(gate.summary())
        println("  report: $reportFile")
        DiffReport.report("doc", gate.diff, facts = mapOf("summary" to gate.summary()))
        gate.exceptions.forEach { println("  EXCEPTION $it") }
        assertTrue("exceptions:\n" + gate.exceptions.joinToString("\n"), gate.exceptions.isEmpty())
        assertTrue("doc comments compared: ${gate.stats.docComments}", gate.stats.docComments >= 30)
        assertEquals("doc comment mismatches (see $reportFile)", 0L, gate.stats.docCommentMismatches)
        assertEquals("valid files with mismatches (see $reportFile)", 0L, gate.stats.validMismatchedFiles)
        assertEquals("invalid files with mismatches (see $reportFile)", 0L, gate.stats.invalidMismatchedFiles)
    }
}
