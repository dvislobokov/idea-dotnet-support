package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.RazorMetrics
import io.github.dotnetsupport.razor.RazorTestUtil
import io.github.dotnetsupport.razor.lang.syntax.RazorDirectives
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorLanguageVersion
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.SyntaxSerializer
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The tree gate against the Razor compiler's own parser tests (RAZOR_PLAN.md, R2): every `.stree.txt` of `TestFiles/ParserTests`
 * (legacyTest and test) is parsed from the input on its first line (`RazorDocument - [0..N)::N - [text]`, line breaks written `LF`,
 * CRLF in the original) and the serialized tree must match the baseline — the compiler's tokenizer of those baselines is the native
 * * one for legacyTest and Roslyn's for test, the kind is guessed as the tests' (`Component` in the name or `@code` in the text), the directives are the engine's of that kind.
 * Out: the tag helper rewriter tests (their tree is the rewritten one, R5), `RazorDirectivesTest` (directives of the test's own) and the
 * few cases whose tests set options of their own ([TEST_OPTION_CASES], verified against razordump with the engine's options).
 * Metrics: `testData/metrics/baselines-tree.json` (`same` may only grow); the differences: `build/razor-gate/baselines.txt`.
 */
class RazorBaselineCorpusTest : TestCase() {

    fun testParserTestBaselines() {
        val compiler = RazorTestUtil.razorCompiler() ?: return println("RazorBaselineCorpusTest: no .corpus/razor/razor-compiler, skipped")
        val roots = listOf("Microsoft.AspNetCore.Razor.Language/legacyTest/TestFiles/ParserTests", "Microsoft.AspNetCore.Razor.Language/test/TestFiles/ParserTests")
            .map(compiler::resolve).filter(Files::isDirectory)
        // legacyTest's baselines were written with the native tokenizer and span edit handlers on, test's with the Roslyn tokenizer and no handlers
        val cases = roots.flatMap { root ->
            val editHandlers = "legacyTest" in root.toString()
            Files.walk(root).use { s -> s.filter { it.name.endsWith(".stree.txt") }.map { Case(root.relativize(it).toString().replace('\\', '/').removeSuffix(".stree.txt"), it, editHandlers) }.toList() }
        }.sortedBy { it.name }
        assertTrue("the parser tests of the compiler (${cases.size})", cases.size > 2000)
        var same = 0
        var compared = 0
        var exceptions = 0
        val byGroup = sortedMapOf<String, IntArray>()
        val report = StringBuilder()
        val diffs = ArrayList<String>()
        for ((name, file, editHandlers) in cases) {
            val group = name.substringBefore('/')
            if (group in SKIPPED_GROUPS || name in TEST_OPTION_CASES) continue
            val baseline = Files.readString(file).removePrefix("﻿").replace("\r\n", "\n")
            val input = inputOf(baseline) ?: continue
            compared++
            val counts = byGroup.getOrPut(group) { IntArray(2) }
            counts[0]++
            // the test's kind: the baseline shows whether `code` was a directive
            val kind = if ("Component" in name || "Directive:{code;" in baseline) RazorFileKind.Component else RazorFileKind.Legacy
            val options = RazorParserOptions(RazorLanguageVersion.LATEST, kind, RazorDirectives.forFileKind(kind, RazorLanguageVersion.LATEST), useRoslynTokenizer = !editHandlers, enableSpanEditHandlers = editHandlers)
            val expected = normalize(baseline)
            val actual = try {
                normalize(SyntaxSerializer.serialize(RazorParser.parse(input, options).root))
            } catch (e: Throwable) {
                exceptions++
                diffs += "-- $name: ${e.javaClass.simpleName}: ${e.message}\n   at ${e.stackTrace.firstOrNull()}"
                continue
            }
            if (expected == actual) {
                same++
                counts[1]++
            } else {
                // -Drazorpsi.dump=<substring of a case name>: both trees in full, build/razor-gate/<name>.{expected,actual}.txt
                val dump = System.getProperty("razorpsi.dump")
                if (!dump.isNullOrEmpty() && dump in name) {
                    val dir = RazorTestUtil.buildDir("razor-gate").resolve(name.replace('/', '_'))
                    Files.writeString(Path.of("$dir.expected.txt"), expected)
                    Files.writeString(Path.of("$dir.actual.txt"), actual)
                }
                val a = expected.lines()
                val b = actual.lines()
                val i = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
                diffs += "-- $name line $i\n   baseline: ${a.getOrNull(i) ?: "<end>"}\n   ours:     ${b.getOrNull(i) ?: "<end>"}"
            }
        }
        report.append("cases $compared same $same exceptions $exceptions\n")
        byGroup.forEach { (group, counts) -> report.append("%-40s %5d %5d\n".format(group, counts[0], counts[1])) }
        report.append('\n')
        diffs.take(400).forEach { report.append(it).append('\n') }
        val reportFile = RazorTestUtil.buildDir("razor-gate").resolve("baselines.txt")
        Files.writeString(reportFile, report.toString())
        println("RazorBaselineCorpusTest: cases $compared same $same exceptions $exceptions (report: $reportFile)")
        RazorMetrics.check(
            Path.of(RazorTestUtil.testDataPath("metrics")).resolve("baselines-tree.json"),
            linkedMapOf("cases" to compared.toLong(), "same" to same.toLong(), "exceptions" to exceptions.toLong()),
            higherIsBetter = setOf("same"), lowerIsBetter = setOf("exceptions"),
        )
    }

    private data class Case(val name: String, val file: Path, val editHandlers: Boolean)

    companion object {
        private val SKIPPED_GROUPS = setOf("TagHelperBlockRewriterTest", "TagHelperParseTreeRewriterTest", "RazorDirectivesTest", "WhiteSpaceRewriterTest")

        /** Cases whose tests parse with options of their own (design time, experimental flag, an older language version, no directives). */
        private val TEST_OPTION_CASES = setOf(
            "CSharpFunctionsTest/MarkupInFunctionsBlock_DoesNotParseWhenNotSupported",
            "CSharpReservedWordsTest/ReservedWord",
            "CSharpToMarkupSwitchTest/GivesAllWhitespaceOnSameLineWithTrailingNewLineToMarkupExclPreceedingNewline",
            "CSharpToMarkupSwitchTest/GivesSpacesToCodeOnAtColonTransitionInDesignTimeMode",
            "CSharpToMarkupSwitchTest/GivesSpacesToCodeOnAtTagTemplateTransitionInDesignTimeMode",
            "CSharpToMarkupSwitchTest/GivesSpacesToCodeOnInvalidAtTagTransitionInDesignTimeMode",
            "CSharpToMarkupSwitchTest/GivesSpacesToCodeOnTagTransitionInDesignTimeMode",
            "HtmlAttributeTest/ConditionalAttributesAreEnabledForDataAttributesWithExperimentalFlag",
            "HtmlToCodeSwitchTest/CSharpCodeParserDoesNotAcceptLeadingOrTrailingWhitespaceInDesignMode",
            // preprocessor symbols given to the test, span edit handlers on in the test root
            "CSharpPreprocessorTest/IfDefFromParseOptions_Symbol", "CSharpPreprocessorTest/IfDefFromParseOptions_Symbol2", "CSharpPreprocessorTest/SimplePragma",
            "CSharpSpecialBlockTest/NamespaceImportInsideCodeBlockCausesError", "CSharpSpecialBlockTest/TypeAliasInsideCodeBlockIsNotHandledSpecially",
            // RZ2002 of nested sections comes from the engine's DefaultDirectiveSyntaxTreePass, not the parser (R6)
            "CSharpSectionTest/ParserOutputsErrorOnMultipleNestedSections", "CSharpSectionTest/ParserOutputsErrorOnNestedSections",
        )

        /** The input of a baseline: the text of its first line, `LF` back to the CRLF the tests wrote. */
        fun inputOf(baseline: String): String? {
            val firstLine = baseline.lineSequence().firstOrNull() ?: return null
            var start = firstLine.indexOf(" - [")
            if (start >= 0) start = firstLine.indexOf(" - [", start + 4)
            if (start < 0 || !firstLine.endsWith("]")) return null
            return firstLine.substring(start + 4, firstLine.length - 1).replace("LF", "\r\n")
        }

        /** The baseline's `)::N` is the serializer's ` - Width: N`; the keyword count of implicit expressions depends on the directives registered. */
        fun normalize(text: String): String =
            text.replace("\r\n", "\n").replace(Regex("\\)::(\\d+)"), ") - Width: $1").replace(Regex(";K\\d+"), ";K").trimEnd('\n')
    }
}
