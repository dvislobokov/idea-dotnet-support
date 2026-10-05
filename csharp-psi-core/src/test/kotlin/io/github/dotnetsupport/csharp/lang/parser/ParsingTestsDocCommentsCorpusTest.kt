package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import io.github.dotnetsupport.csharp.lang.oracle.DiffReport
import io.github.dotnetsupport.csharp.lang.oracle.RoslynDump
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Corpus gate of the doc comments over Roslyn's own cref and name attribute tests (step 5): the `*.doc` snippets that
 * `roslyndump extract-tests` takes from `CrefParsingTests`, `VerbatimCrefParsingTests` (`/// <see cref="{0}"/>`) and
 * `NameAttributeValueParsingTests` (`/// <param name="{0}"/>`), in `.corpus/parsing-tests-doc` (an extraction of its
 * own, beside `.corpus/parsing-tests`), against `roslyndump doc` through the file parser, compared by [SliceGate] with
 * [DocCommentDiff]. Roslyn reports no XML or cref diagnostics with DocumentationMode.Parse, so almost every snippet is
 * valid, malformed crefs included; all must match. Metrics: `testData/metrics/parsing-tests-doc.json`.
 */
class ParsingTestsDocCommentsCorpusTest : CSharpParsingTestCase("parser/doc") {

    fun testDocComments() {
        val root = CSharpTestUtil.corpusRoot().resolve("parsing-tests-doc")
        if (!Files.isDirectory(root)) {
            println("ParsingTestsDocCommentsCorpusTest: $root is missing (roslyndump extract-tests ... --out $root), skipped")
            return
        }
        val gate = SliceGate("parsing-tests-doc")
        val t0 = System.currentTimeMillis()
        val run = RoslynDump.run("doc", root)
        println("  roslyndump doc $root: ${run.summary}")
        for (dump in run.files) {
            val text = CSharpLexerDiffCorpusTest.readSource(root.resolve(dump.path))
            val result = try {
                SliceParseHarness.parse(text, SliceParseHarness.Mode.File)
            } catch (e: Throwable) {
                if (dump.diagnostics.isEmpty()) gate.stats.validExceptions++ else gate.stats.invalidExceptions++
                gate.exceptions += "${dump.path}: $e"
                continue
            }
            gate.compareParsed(dump, text, result)
        }
        val reportFile = gate.writeReport(CSharpTestUtil.buildDir("slice-gate"))
        println(gate.summary() + " millis=${System.currentTimeMillis() - t0}")
        println("  report: $reportFile")
        DiffReport.report("parsing-tests-doc", gate.diff, facts = mapOf("summary" to gate.summary()))
        gate.exceptions.take(20).forEach { println("  EXCEPTION $it") }

        val s = gate.stats
        CorpusMetrics.check(
            Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("parsing-tests-doc.json"),
            linkedMapOf(
                "validFiles" to s.validFiles,
                "validMismatchedFiles" to s.validMismatchedFiles,
                "validMismatches" to s.validMismatches,
                "validSpuriousErrorFiles" to s.validSpuriousErrorFiles,
                "validExceptions" to s.validExceptions,
                "invalidFiles" to s.invalidFiles,
                "invalidExceptions" to s.invalidExceptions,
                "invalidMismatchedFiles" to s.invalidMismatchedFiles,
                "invalidMismatches" to s.invalidMismatches,
                "docComments" to s.docComments,
                "docCommentMismatches" to s.docCommentMismatches,
            ),
            informational = setOf("validFiles", "invalidFiles", "docComments"),
        )
        assertTrue("doc snippets compared: ${s.docComments}", s.docComments >= 100)
        assertEquals("exceptions", 0L, s.validExceptions + s.invalidExceptions)
        assertEquals("doc comment mismatches (see $reportFile)", 0L, s.docCommentMismatches)
    }
}
