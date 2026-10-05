package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpTestUtil
import java.io.File

/**
 * Fast fuzz gate (in `test`): small seeds, [MUTATIONS] seeded mutations each, hard rules of [CSharpParserFuzzTestBase]
 * (no exception, no logged error, lossless coverage of the text). Seeds, sorted and spread to at most [MAX_SOURCES]:
 *  - every `*.cs` under `testData/parser` and `testData/lexer` (goldens, so new parser goldens become seeds);
 *  - the step-0 slice snippets `testData/parser/slice/` wrapped into a method body (`.stmt` as a statement, `.expr`
 *    as `return <expr>;`), which puts statement and expression mutations inside a member body.
 * Locality is reported, not asserted here (it is a metric of [CSharpParserFuzzCorpusTest]).
 */
class CSharpParserFuzzTest : CSharpParserFuzzTestBase() {

    fun testMutantsParseLosslessly() {
        val base = File(CSharpTestUtil.testDataPath())
        val files = listOf("parser", "lexer").flatMap { dir ->
            File(base, dir).walkTopDown().filter { it.isFile && it.extension == "cs" }.toList()
        }.sortedBy { it.invariantSeparatorsPath }
        val snippets = File(base, "parser/slice").walkTopDown().filter { it.isFile && (it.extension == "stmt" || it.extension == "expr") }
            .sortedBy { it.invariantSeparatorsPath }.toList()
        val sources = (files.map { it.relativeTo(base).invariantSeparatorsPath to normalize(it.readText()) } +
            snippets.map { "${it.relativeTo(base).invariantSeparatorsPath}.cs" to wrap(normalize(it.readText()).trimEnd(), it.extension == "stmt") })
        assertTrue("expected fuzz seeds under testData, found ${sources.size}", sources.size >= 20)
        val sample = if (sources.size <= MAX_SOURCES) sources else (0 until MAX_SOURCES).map { sources[it * sources.size / MAX_SOURCES] }

        val started = System.nanoTime()
        val result = fuzz(sample, MUTATIONS, SEED)
        printSummary("CSharpParserFuzzTest", result, worst = 5)
        println("  time: ${(System.nanoTime() - started) / 1_000_000} ms")
        assertNoHardFailures(result)
    }

    private fun normalize(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun wrap(snippet: String, statement: Boolean): String {
        val body = if (statement) snippet else "return $snippet;"
        return "class C\n{\n    object M()\n    {\n        " + body.replace("\n", "\n        ") + "\n    }\n\n    int P { get { return 1; } }\n}\n"
    }

    private companion object {
        const val SEED = 20261004L
        const val MAX_SOURCES = 60
        const val MUTATIONS = 6
    }
}
