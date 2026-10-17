package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.RazorDump
import io.github.dotnetsupport.razor.RazorMetrics
import io.github.dotnetsupport.razor.RazorTestUtil
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.SyntaxSerializer
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tree gate on the solutions of the corpus (RAZOR_PLAN.md, R2): every `.razor` / `.cshtml` of MudBlazor, nopCommerce and eShop
 * parsed by us against `razordump tree` (the compiler of the installed SDK, the Roslyn tokenizer, span edit handlers on), line for line.
 * Metrics: `testData/metrics/<name>-tree.json` (`same` may only grow, `exceptions` only fall); differences: `build/razor-gate/<name>.txt`.
 * Skipped without the corpus or razordump (`dotnet build tools/razor/razordump -c Release`).
 */
class RazorTreeCorpusTest : TestCase() {

    fun testEshop() = gate("eshop", "razor/eshop/src")
    fun testMudblazor() = gate("mudblazor", "razor/mudblazor/src")
    fun testNopcommerce() = gate("nopcommerce", "razor/nopcommerce/src")

    private fun gate(name: String, relative: String) {
        val root = RazorTestUtil.corpusRoot().resolve(relative)
        if (!Files.isDirectory(root)) return println("RazorTreeCorpusTest: $root is missing (tools/razor/fetch-corpus.sh), $name skipped")
        if (RazorDump.dll() == null) return println("RazorTreeCorpusTest: razordump is not built (dotnet build tools/razor/razordump -c Release), $name skipped")
        val work = RazorTestUtil.buildDir("razor-gate")
        val dump = work.resolve("$name.oracle.txt")
        println("  razordump tree: " + RazorDump.tree(root, dump))
        val oracle = RazorDump.parse(Files.readString(dump))
        var same = 0
        var exceptions = 0
        val diffs = ArrayList<String>()
        val started = System.nanoTime()
        for ((path, expectedRaw) in oracle) {
            val file = root.resolve(path)
            // as razordump reads it (.NET File.ReadAllText): the BOM picks the encoding, malformed bytes are replaced; line breaks as the IDE document
            // (razordump also drops a U+FEFF the decoded text still starts with: a few files of MudBlazor carry two BOMs)
            val text = RazorTestUtil.readText(file).removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            val expected = normalize(expectedRaw)
            val actual = try {
                normalize(SyntaxSerializer.serialize(RazorParser.parse(text, RazorParserOptions.forFile(path, RazorLanguageVersion.LATEST, useRoslynTokenizer = true).copy(enableSpanEditHandlers = true), path).root))
            } catch (e: Throwable) {
                exceptions++
                diffs += "-- $path: ${e.javaClass.simpleName}: ${e.message}\n   at ${e.stackTrace.firstOrNull()}"
                continue
            }
            if (expected == actual) same++
            else {
                // -Drazorpsi.dump=<substring of a path>: both trees in full, build/razor-gate/<file>.{oracle,ours}.txt
                val dumpName = System.getProperty("razorpsi.dump")
                if (!dumpName.isNullOrEmpty() && dumpName in path) {
                    val base = work.resolve(path.substringAfterLast('/'))
                    Files.writeString(Path.of("$base.oracle.txt"), expected)
                    Files.writeString(Path.of("$base.ours.txt"), actual)
                }
                val a = expected.lines()
                val b = actual.lines()
                val i = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
                diffs += "-- $path line $i\n   oracle: ${a.getOrNull(i) ?: "<end>"}\n   ours:   ${b.getOrNull(i) ?: "<end>"}"
            }
        }
        val report = StringBuilder("files ${oracle.size} same $same exceptions $exceptions, ${(System.nanoTime() - started) / 1_000_000} ms\n\n")
        diffs.take(500).forEach { report.append(it).append('\n') }
        val reportFile = work.resolve("$name.txt")
        Files.writeString(reportFile, report.toString())
        println("RazorTreeCorpusTest $name: files ${oracle.size} same $same exceptions $exceptions (report: $reportFile)")
        RazorMetrics.check(
            Path.of(RazorTestUtil.testDataPath("metrics")).resolve("$name-tree.json"),
            linkedMapOf("files" to oracle.size.toLong(), "same" to same.toLong(), "exceptions" to exceptions.toLong()),
            higherIsBetter = setOf("same"), lowerIsBetter = setOf("exceptions"),
        )
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace(Regex(";K\\d+"), ";K").trimEnd('\n')
}
