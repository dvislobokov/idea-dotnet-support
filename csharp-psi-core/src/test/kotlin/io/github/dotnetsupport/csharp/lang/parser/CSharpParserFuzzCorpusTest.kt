package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.random.Random

/**
 * Fuzz gate over a deterministic sample of `.corpus/runtime/src`: [FILES] files of at most [MAX_BYTES] bytes
 * chosen by a seeded shuffle of the sorted paths, [MUTATIONS] mutations each (see [CSharpParserFuzzTestBase] for the
 * mutations, the hard rules and the definition of locality). Hard failures (an incremental reparse that differs from
 * the full parse included) fail the test; `hardFailures` and `bodyReparses` (higher is better) are metrics in
 * `testData/metrics/runtime-fuzz.json` (only improve); `localityViolations` and `fullReparses` are informational. `-Dcsharppsi.fuzz.files=N` and
 * `-Dcsharppsi.fuzz.mutations=M` change the sample for a quick or a deeper run; metrics are then not checked.
 */
class CSharpParserFuzzCorpusTest : CSharpParserFuzzTestBase() {

    fun testRuntimeSample() {
        val root = CSharpTestUtil.corpusRoot().resolve("runtime/src")
        if (!Files.isDirectory(root)) {
            println("CSharpParserFuzzCorpusTest: $root is missing (tools/csharp-psi/fetch-corpus.sh), skipped")
            return
        }
        val files = System.getProperty("csharppsi.fuzz.files")?.toIntOrNull() ?: FILES
        val mutations = System.getProperty("csharppsi.fuzz.mutations")?.toIntOrNull() ?: MUTATIONS
        val candidates = Files.walk(root).use { s ->
            s.filter { it.isRegularFile() && it.extension == "cs" && Files.size(it) <= MAX_BYTES }
                .map { root.relativize(it).toString().replace('\\', '/') }.sorted().toList()
        }
        assertTrue("expected > 1000 files under $root", candidates.size > 1000)
        val sample = candidates.shuffled(Random(SEED)).take(files).sorted()
        val sources = sample.map { it to CSharpLexerDiffCorpusTest.readSource(root.resolve(it)) }

        val started = System.nanoTime()
        val result = fuzz(sources, mutations, SEED)
        printSummary("CSharpParserFuzzCorpusTest", result)
        println("  time: ${(System.nanoTime() - started) / 1_000_000} ms, ${sources.sumOf { it.second.length } / 1000} K chars")

        val metricsFile = Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("runtime-fuzz.json")
        when {
            files != FILES || mutations != MUTATIONS -> println("  metrics: not checked (custom sample)")
            result.sourcesWithNodes == 0 ->
                println("  metrics: not checked, the parser builds no syntax nodes (placeholder); the first run of the wired parser creates $metricsFile")
            else -> CorpusMetrics.check(
                metricsFile,
                linkedMapOf(
                    "files" to result.sources.toLong(),
                    "mutants" to result.mutants.toLong(),
                    "hardFailures" to result.failures.size.toLong(),
                    "localityChecked" to result.localityChecked.toLong(),
                    "localityViolations" to result.violations.size.toLong(),
                    "bodyReparses" to result.bodyReparses.toLong(),
                    "fullReparses" to result.fullReparses.toLong(),
                ),
                informational = setOf("files", "mutants", "localityChecked", "localityViolations", "fullReparses"),
                higherIsBetter = setOf("bodyReparses"),
            )
        }
        assertNoHardFailures(result)
    }

    private companion object {
        const val SEED = 20261004L
        const val FILES = 300
        const val MUTATIONS = 20
        const val MAX_BYTES = 100_000L
    }
}
