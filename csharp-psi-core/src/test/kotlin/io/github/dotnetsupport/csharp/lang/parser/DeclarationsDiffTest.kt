package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.oracle.DiffReport
import java.nio.file.Paths

/**
 * Fast gate of the declarations: the hand-written snippets of `testData/parser/decl` (`*.member` against
 * `roslyndump member`, `*.cs` against `roslyndump tree` through the file parser) must match the oracle, valid and
 * invalid alike, with no exception ([SliceGate] for what is compared). Report: `build/slice-gate/decl.txt`.
 */
class DeclarationsDiffTest : CSharpParsingTestCase("parser/decl") {

    fun testDeclarations() {
        val root = Paths.get(CSharpTestUtil.testDataPath("parser/decl"))
        val gate = SliceGate("decl")
        gate.run(root, SliceParseHarness.Mode.Member, listOf { _ -> true })
        gate.run(root, SliceParseHarness.Mode.File, listOf { _ -> true })
        val reportFile = gate.writeReport(CSharpTestUtil.buildDir("slice-gate"))
        println(gate.summary())
        println("  report: $reportFile")
        DiffReport.report("decl", gate.diff, facts = mapOf("summary" to gate.summary()))
        gate.exceptions.forEach { println("  EXCEPTION $it") }
        assertTrue("exceptions:\n" + gate.exceptions.joinToString("\n"), gate.exceptions.isEmpty())
        assertEquals("valid snippets with mismatches (see $reportFile)", 0L, gate.stats.validMismatchedFiles)
        assertEquals("invalid snippets with mismatches (see $reportFile)", 0L, gate.stats.invalidMismatchedFiles)
    }
}
