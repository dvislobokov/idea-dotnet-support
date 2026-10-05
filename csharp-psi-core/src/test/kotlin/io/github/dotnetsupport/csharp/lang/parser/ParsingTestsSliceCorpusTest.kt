package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.oracle.DiffReport
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Corpus gate of the step-0 slice: every `*.expr` / `*.stmt` snippet extracted from Roslyn's `Test/Syntax/Parsing`
 * (`.corpus/parsing-tests`, with `index.tsv`) against `roslyndump expr|stmt`, regular kind. Buckets:
 *  - valid (no Roslyn diagnostic) / invalid, each with mismatched files and mismatches; a valid snippet with an error
 *    element in our tree is a mismatch (`validSpuriousErrorFiles`); both sides parse with `LanguageVersion.Preview`;
 *  - the tests written for a script or an explicit language version ([ParsingTestsIndex.isScriptOrOldVersion]): diffed
 *    at the test's own version by [ParsingTestsVersionGate] (metrics `oldLangVersion*`, `script*`).
 * What the comparison of invalid snippets does and does not check: [SliceGate]. The informational metrics
 * `invalidErrorElements` (our error elements) against `invalidOracleDiagnostics` / `invalidOracleSkipped` (Roslyn's
 * `D` records and `SkippedTokensTrivia`) give a rough measure of recovery agreement; they are not gated.
 * Metrics: `testData/metrics/parsing-tests-slice.json`; report: `build/slice-gate/parsing-tests.txt`.
 */
class ParsingTestsSliceCorpusTest : CSharpParsingTestCase("parser/slice") {

    fun testParsingTests() {
        val root = CSharpTestUtil.corpusRoot().resolve("parsing-tests")
        if (!Files.isDirectory(root)) {
            println("ParsingTestsSliceCorpusTest: $root is missing, skipped")
            return
        }
        val special = ParsingTestsIndex.special(root)

        val gate = SliceGate("parsing-tests")
        val versionGate = ParsingTestsVersionGate("parsing-tests")
        val t0 = System.currentTimeMillis()
        for (mode in listOf("expr", "stmt")) {
            gate.run(root, mode) { dump -> ParsingTestsIndex.normalize(dump.path) !in special }
            versionGate.run(root, SliceParseHarness.Mode.entries.single { it.dumpMode == mode }, special)
        }
        val reportDir = CSharpTestUtil.buildDir("slice-gate")
        val reports = listOf(gate, versionGate.versioned, versionGate.script).map { it.writeReport(reportDir) }
        println(gate.summary() + " millis=${System.currentTimeMillis() - t0}")
        println(versionGate.versioned.summary())
        println(versionGate.script.summary())
        println("  reports: ${reports.joinToString()}")
        DiffReport.report("parsing-tests-slice", gate.diff, facts = mapOf("summary" to gate.summary()))
        DiffReport.report("parsing-tests-slice-old-version", versionGate.versioned.diff, facts = mapOf("summary" to versionGate.versioned.summary()))
        for (g in listOf(gate, versionGate.versioned, versionGate.script)) g.exceptions.take(20).forEach { println("  EXCEPTION (${g.name}) $it") }

        val s = gate.stats
        val metrics = linkedMapOf(
            "validFiles" to s.validFiles,
            "validMismatchedFiles" to s.validMismatchedFiles,
            "validMismatches" to s.validMismatches,
            "validSpuriousErrorFiles" to s.validSpuriousErrorFiles,
            "validExceptions" to s.validExceptions,
            "invalidFiles" to s.invalidFiles,
            "invalidExceptions" to s.invalidExceptions,
            "invalidMismatchedFiles" to s.invalidMismatchedFiles,
            "invalidMismatches" to s.invalidMismatches,
            "invalidErrorElements" to s.invalidErrorElements,
            "invalidOracleDiagnostics" to s.invalidOracleDiagnostics,
            "invalidOracleSkipped" to s.invalidOracleSkipped,
        )
        versionGate.metrics(metrics)
        CorpusMetrics.check(
            Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("parsing-tests-slice.json"),
            metrics,
            informational = setOf(
                "validFiles", "invalidFiles",
                "invalidErrorElements", "invalidOracleDiagnostics", "invalidOracleSkipped",
            ) + ParsingTestsVersionGate.informational,
        )
    }
}
