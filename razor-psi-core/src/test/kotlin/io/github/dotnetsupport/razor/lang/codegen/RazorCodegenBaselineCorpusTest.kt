package io.github.dotnetsupport.razor.lang.codegen

import com.intellij.testFramework.ParsingTestCase
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.razor.RazorDiagnosticSerializer
import io.github.dotnetsupport.razor.RazorIrSerializer
import io.github.dotnetsupport.razor.RazorMappingsSerializer
import io.github.dotnetsupport.razor.RazorMetrics
import io.github.dotnetsupport.razor.RazorTestUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * The code generation gate against the Razor compiler's own integration tests (RAZOR_PLAN.md, R3b): every case of [RazorCodegenCases]
 * (an input with a `.codegen.cs` beside it under the integration test folders of `Microsoft.AspNetCore.Razor.Language` and
 * `Microsoft.AspNetCore.Mvc.Razor.Extensions`) processed as its test does, and our generated C#, IR (`.ir.txt`), source mappings
 * (`.mappings.txt`, where the test has one) and diagnostics (`.diagnostics.txt`, none without the file) compared with the baselines,
 * line breaks aside. The cases whose test runs with tag helper descriptors of its own (`RunTagHelpersTest`, `RunDiscoveredTagHelpersTest`)
 * are skipped and listed in the report.
 *
 * Metrics: `testData/metrics/codegen-baselines.json` (`same`, `irSame`, `mappingsSame`, `diagnosticsSame` may only grow, `exceptions` only
 * fall); the differences, with the first differing line of each part: `build/razor-gate/codegen-baselines.txt`; `-Drazorpsi.dump=<substring
 * of a case>` writes both texts of every part in full to `build/razor-gate/codegen/`.
 *
 * A [ParsingTestCase] for its application: the markup split (on in these tests) parses the analysis class with csharp-psi's parser.
 */
class RazorCodegenBaselineCorpusTest : ParsingTestCase("", "cs", CSharpParserDefinition()) {

    override fun getTestDataPath(): String = RazorTestUtil.testDataPath()

    fun testCodegenBaselines() {
        val compiler = RazorTestUtil.razorCompiler() ?: return println("RazorCodegenBaselineCorpusTest: no .corpus/razor/razor-compiler, skipped")
        val cases = RazorCodegenCases.cases(compiler)
        assertTrue("the code generation cases of the compiler (${cases.size})", cases.size > 100)
        val work = RazorTestUtil.buildDir("razor-gate")
        var compared = 0
        var same = 0
        var irSame = 0
        var irBaselines = 0
        var mappingsSame = 0
        var mappingsBaselines = 0
        var diagnosticsSame = 0
        var exceptions = 0
        val skipped = ArrayList<String>()
        val differing = ArrayList<String>()
        val diffs = ArrayList<String>()
        val started = System.nanoTime()
        for (case in cases) {
            if (case.skipReason != null) {
                skipped += "${case.relative} (${case.skipReason})"
                continue
            }
            compared++
            val parts: Map<String, String> = try {
                val document = RazorCodegenCases.process(case)
                val csharpDocument = document.getCSharpDocument() ?: throw IllegalStateException("no C# document")
                val documentNode = document.getDocumentNode() ?: throw IllegalStateException("no document node")
                linkedMapOf(
                    "codegen.cs" to csharpDocument.generatedCode,
                    "ir.txt" to RazorIrSerializer.serialize(documentNode),
                    "mappings.txt" to RazorMappingsSerializer.serialize(csharpDocument, document.source),
                    "diagnostics.txt" to RazorDiagnosticSerializer.serializeAll(csharpDocument.diagnostics),
                )
            } catch (e: Throwable) {
                exceptions++
                differing += "${case.relative}: exception"
                diffs += "-- ${case.relative}: ${e.javaClass.simpleName}: ${e.message}\n   at ${e.stackTrace.take(3).joinToString("\n   at ")}"
                continue
            }
            val failed = ArrayList<String>()
            for ((part, ours) in parts) {
                val baseline = baseline(case, part)
                when (part) {
                    "codegen.cs" -> if (same(ours, baseline)) same++ else failed += part
                    "ir.txt" -> if (baseline != null) {
                        irBaselines++
                        if (same(ours, baseline)) irSame++ else failed += part
                    }
                    "mappings.txt" -> if (baseline != null) {
                        mappingsBaselines++
                        if (same(ours, baseline)) mappingsSame++ else failed += part
                    }
                    "diagnostics.txt" -> if (same(ours, baseline ?: "")) diagnosticsSame++ else failed += part
                }
                if (part !in failed) continue
                val expected = lf(baseline ?: "")
                val actual = lf(ours)
                // -Drazorpsi.dump=<substring of a case>: both texts in full, build/razor-gate/codegen/<case>.{baseline,ours}.<part>
                val dumpName = System.getProperty("razorpsi.dump")
                if (!dumpName.isNullOrEmpty() && dumpName in case.relative) {
                    val dir = work.resolve("codegen").also { Files.createDirectories(it) }
                    val base = case.relative.replace('/', '_')
                    Files.writeString(dir.resolve("$base.baseline.$part"), expected)
                    Files.writeString(dir.resolve("$base.ours.$part"), actual)
                }
                val a = expected.lines()
                val b = actual.lines()
                val i = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
                diffs += "-- ${case.relative} $part line ${i + 1}\n   baseline: ${a.getOrNull(i) ?: "<end>"}\n   ours:     ${b.getOrNull(i) ?: "<end>"}"
            }
            if (failed.isNotEmpty()) differing += "${case.relative}: ${failed.joinToString(" ")}" + if (case.csharpTrees.isNotEmpty() && RazorCodegenCases.tagHelpersFixture(case) == null) " [test C#: tag helpers not discovered]" else ""
        }
        val summary = "cases $compared same $same irSame $irSame/$irBaselines mappingsSame $mappingsSame/$mappingsBaselines diagnosticsSame $diagnosticsSame " +
            "exceptions $exceptions skipped ${skipped.size}"
        val report = StringBuilder("$summary, ${(System.nanoTime() - started) / 1_000_000} ms\n\n")
        report.append("differing:\n")
        differing.forEach { report.append("  ").append(it).append('\n') }
        report.append("\nskipped:\n")
        skipped.forEach { report.append("  ").append(it).append('\n') }
        report.append('\n')
        diffs.take(1000).forEach { report.append(it).append('\n') }
        val reportFile = work.resolve("codegen-baselines.txt")
        Files.writeString(reportFile, report.toString())
        println("RazorCodegenBaselineCorpusTest: $summary (report: $reportFile)")
        RazorMetrics.check(
            Path.of(RazorTestUtil.testDataPath("metrics")).resolve("codegen-baselines.json"),
            linkedMapOf(
                "cases" to compared.toLong(), "same" to same.toLong(), "irSame" to irSame.toLong(), "mappingsSame" to mappingsSame.toLong(),
                "diagnosticsSame" to diagnosticsSame.toLong(), "exceptions" to exceptions.toLong(),
            ),
            higherIsBetter = setOf("same", "irSame", "mappingsSame", "diagnosticsSame"), lowerIsBetter = setOf("exceptions"),
        )
    }

    /** The baseline `<Name>.<part>` beside the input, its BOM dropped; null when there is none. */
    private fun baseline(case: RazorCodegenCases.Case, part: String): String? {
        val file = case.inputPath.resolveSibling(case.name + "." + part)
        if (!Files.isRegularFile(file)) return null
        return RazorTestUtil.readText(file).removePrefix("﻿")
    }

    private fun same(actual: String, baseline: String?): Boolean = baseline != null && lf(actual) == lf(baseline)

    private fun lf(text: String): String = text.replace("\r\n", "\n")
}
