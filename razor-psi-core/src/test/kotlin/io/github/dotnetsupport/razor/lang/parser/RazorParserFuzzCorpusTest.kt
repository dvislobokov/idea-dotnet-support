package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.RazorMetrics
import io.github.dotnetsupport.razor.RazorTestUtil
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.random.Random

/**
 * The mutation fuzz of the parser (RAZOR_PLAN.md, R2): a deterministic sample of the corpus files, each mutated [MUTATIONS] times
 * (a Razor-significant character inserted or deleted, a line duplicated), parsed with both tokenizers: no exception, the tree's text
 * is the input. Metrics: `testData/metrics/razor-fuzz.json` (`exceptions` may only fall); the failures: `build/razor-gate/fuzz.txt`.
 */
class RazorParserFuzzCorpusTest : TestCase() {

    fun testCorpusSample() {
        val root = RazorTestUtil.corpusRoot().resolve("razor")
        if (!Files.isDirectory(root)) return println("RazorParserFuzzCorpusTest: $root is missing (tools/razor/fetch-corpus.sh), skipped")
        val candidates = listOf("eshop/src", "mudblazor/src", "nopcommerce/src").flatMap { sub ->
            val dir = root.resolve(sub)
            if (!Files.isDirectory(dir)) emptyList()
            else Files.walk(dir).use { s ->
                s.filter { it.isRegularFile() && (it.extension == "razor" || it.extension == "cshtml") && Files.size(it) <= MAX_BYTES && !it.toString().contains("\\obj\\") && !it.toString().contains("/obj/") }
                    .map { root.relativize(it).toString().replace('\\', '/') }.toList()
            }
        }.sorted()
        assertTrue("expected > 1000 files under $root", candidates.size > 1000)
        val sample = candidates.shuffled(Random(SEED)).take(FILES).sorted()
        var mutants = 0
        val failures = ArrayList<String>()
        val random = Random(SEED)
        for (relative in sample) {
            val original = RazorTestUtil.readText(root.resolve(relative)).removePrefix("﻿").replace("\r\n", "\n")
            repeat(MUTATIONS) {
                val text = mutate(original, random)
                mutants++
                for (roslyn in listOf(true, false)) {
                    try {
                        val tree = RazorParser.parse(text, RazorParserOptions.forFile(relative, RazorLanguageVersion.LATEST, useRoslynTokenizer = roslyn).copy(enableSpanEditHandlers = true), relative)
                        if (tree.root.text() != text) failures += "$relative (roslyn=$roslyn): the tree's text differs from the input"
                    } catch (e: Throwable) {
                        failures += "$relative (roslyn=$roslyn): ${e.javaClass.simpleName}: ${e.message} at ${e.stackTrace.firstOrNull()}"
                    }
                }
            }
        }
        val reportFile = RazorTestUtil.buildDir("razor-gate").resolve("fuzz.txt")
        Files.writeString(reportFile, "files ${sample.size} mutants $mutants failures ${failures.size}\n\n" + failures.take(300).joinToString("\n"))
        println("RazorParserFuzzCorpusTest: files ${sample.size} mutants $mutants failures ${failures.size} (report: $reportFile)")
        RazorMetrics.check(
            Path.of(RazorTestUtil.testDataPath("metrics")).resolve("razor-fuzz.json"),
            linkedMapOf("files" to sample.size.toLong(), "mutants" to mutants.toLong(), "exceptions" to failures.size.toLong()),
            higherIsBetter = emptySet(), lowerIsBetter = setOf("exceptions"),
        )
        assertTrue("the parser failed on ${failures.size} mutants: $reportFile", failures.isEmpty())
    }

    private fun mutate(text: String, random: Random): String {
        if (text.isEmpty()) return SIGNIFICANT[random.nextInt(SIGNIFICANT.length)].toString()
        val sb = StringBuilder(text)
        repeat(1 + random.nextInt(3)) {
            val at = random.nextInt(sb.length + 1)
            when (random.nextInt(4)) {
                0 -> sb.insert(at, SIGNIFICANT[random.nextInt(SIGNIFICANT.length)])
                1 -> if (at < sb.length) sb.deleteCharAt(at)
                2 -> if (at < sb.length) sb.delete(at, minOf(sb.length, at + 1 + random.nextInt(8)))
                else -> {
                    val lineStart = sb.lastIndexOf("\n", at - 1) + 1
                    val lineEnd = sb.indexOf("\n", at).let { if (it < 0) sb.length else it }
                    sb.insert(lineEnd, "\n" + sb.substring(lineStart, lineEnd))
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        const val SEED = 20261017L
        const val FILES = 120
        const val MUTATIONS = 8
        const val MAX_BYTES = 60_000L
        const val SIGNIFICANT = "@{}()<>/\"'*:#\n= "
    }
}
