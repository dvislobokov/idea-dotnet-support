package io.github.dotnetsupport

import com.intellij.psi.TokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.razor.RazorFileKind
import io.github.dotnetsupport.frameworks.razor.RazorLexer
import io.github.dotnetsupport.frameworks.razor.RazorTokenTypes
import java.io.File

/**
 * RAZOR_PLAN.md, R1: the lexer against the Razor compiler's own parser tests (`.corpus/razor/razor-compiler`, `test/TestFiles/ParserTests`;
 * `tools/razor/README.md`). The input of a test is the first line of its `.stree.txt` (`RazorDocument - [0..N)::N - [text]`, a line break
 * written `LF`), the compiler's answer its `.cspans.txt`: Markup, Transition, MetaCode, Code, Comment spans. Each character is compared by
 * that class; the agreement over the characters that are not whitespace may only grow: `src/test/resources/razor/lexer-gate.txt` holds
 * the floor, the report — `build/reports/razor/lexer-gate.txt`. Skipped without the corpus. The tag helper tests are out: their spans
 * are of the rewritten tree (R5); the tests of custom directives stay in and count against us (the lexer knows the built-in ones only).
 */
class RazorLexerGateTest : BasePlatformTestCase() {
    private class Case(val name: String, val text: String, val expected: CharArray)

    fun testAgreementWithTheCompilersSpans() {
        val root = corpus() ?: return println("RazorLexerGateTest: no .corpus/razor/razor-compiler, skipped")
        val cases = root.walkTopDown().filter { it.name.endsWith(".cspans.txt") && !it.path.contains("TagHelper") }.mapNotNull(::case).toList()
        assertTrue("the parser tests of the compiler (${cases.size})", cases.size > 500)
        var compared = 0
        var agreed = 0
        val worst = ArrayList<Triple<Double, Case, String>>()
        val byGroup = sortedMapOf<String, IntArray>()
        for (case in cases) {
            // the kind is the test's (`ParseDocumentTest(…, RazorFileKind.Component)`): those tests are named so, or use `@code`
            val actual = classes(case.text, if ("Component" in case.name || "@code" in case.text) RazorFileKind.COMPONENT else RazorFileKind.LEGACY)
            var n = 0
            var same = 0
            for (i in case.text.indices) {
                if (case.expected[i] == '-' || case.text[i].isWhitespace()) continue
                n++
                if (case.expected[i] == actual[i]) same++
            }
            compared += n
            agreed += same
            byGroup.getOrPut(case.name.substringBefore('/')) { IntArray(2) }.let { it[0] += n; it[1] += same }
            if (n > 0 && same < n) worst += Triple(same.toDouble() / n, case, String(actual))
        }
        val agreement = agreed.toDouble() / compared
        val report = StringBuilder()
        report.append("cases ${cases.size}, characters ${compared}, agreement %.4f\n".format(java.util.Locale.ROOT, agreement))
        val builtIn = byGroup.filterKeys { it != "RazorDirectivesTest" }.values
        report.append("without RazorDirectivesTest (its directives are the test's own): %.4f\n\n".format(java.util.Locale.ROOT, builtIn.sumOf { it[1] }.toDouble() / builtIn.sumOf { it[0] }))
        byGroup.forEach { (group, counts) -> report.append("%-40s %6d %.4f\n".format(java.util.Locale.ROOT, group, counts[0], counts[1].toDouble() / counts[0])) }
        report.append("\nworst (M markup, T transition, X metacode, C code, K comment, - none):\n")
        worst.sortedBy { it.first }.take(60).forEach { (score, case, actual) ->
            report.append("\n%.3f %s\n  text     %s\n  razor    %s\n  ours     %s\n".format(java.util.Locale.ROOT, score, case.name, case.text.visible(), String(case.expected), actual))
        }
        File("build/reports/razor").mkdirs()
        File("build/reports/razor/lexer-gate.txt").writeText(report.toString())
        val floor = javaClass.getResource("/razor/lexer-gate.txt")!!.readText().lines().first { it.startsWith("agreement=") }.substringAfter('=').trim().toDouble()
        assertTrue("agreement %.4f fell under %.4f: build/reports/razor/lexer-gate.txt".format(java.util.Locale.ROOT, agreement, floor), agreement >= floor - 0.00005)
        println("RazorLexerGateTest: agreement %.4f (floor %.4f)".format(java.util.Locale.ROOT, agreement, floor))
    }

    /**
     * The files of the three solutions of the corpus (MudBlazor, nopCommerce, eShop): every one lexed without a gap, each `@{`, `@code {`,
     * `@section … {`, `@(` closed by its own Razor brace (a block that runs to the end of a file the compiler builds is the lexer's
     * mistake), and the restart at a token of state 0 giving the tokens of the whole run — on the first 200 files.
     */
    fun testTheSolutionsOfTheCorpus() {
        val root = corpus()?.let { generateSequence(it) { f -> f.parentFile }.firstOrNull { f -> f.name == "razor" && File(f, "mudblazor").isDirectory } }
            ?: return println("RazorLexerGateTest: no .corpus/razor, skipped")
        val files = listOf("mudblazor", "nopcommerce", "eshop").flatMap { name ->
            File(root, name).walkTopDown().onEnter { it.name != "bin" && it.name != "obj" && it.name != "node_modules" }
                .filter { it.isFile && (it.extension == "razor" || it.extension == "cshtml") }.toList()
        }
        assertTrue("the files of the corpus (${files.size})", files.size > 2000)
        val unclosed = ArrayList<String>()
        var tokens = 0
        val started = System.nanoTime()
        for ((index, file) in files.withIndex()) {
            val text = file.readText().replace("\r\n", "\n")
            val kind = RazorFileKind.of(file.name)
            val lexer = RazorLexer(kind)
            lexer.start(text, 0, text.length, 0)
            var offset = 0
            var open = 0
            val all = ArrayList<Triple<Int, Int, Int>>()
            while (lexer.tokenType != null) {
                assertEquals("${file.path}: a gap", offset, lexer.tokenStart)
                offset = lexer.tokenEnd
                when (lexer.tokenType) {
                    RazorTokenTypes.LBRACE, RazorTokenTypes.LPAREN -> open++
                    RazorTokenTypes.RBRACE, RazorTokenTypes.RPAREN -> open--
                }
                if (index < 200) all += Triple(lexer.tokenStart, lexer.tokenEnd, lexer.state)
                tokens++
                lexer.advance()
            }
            assertEquals("${file.path}: the whole text", text.length, offset)
            if (open != 0) unclosed += "${file.path.substringAfter("razor${File.separator}")}: $open"
            for ((start, _, state) in all) {
                if (state != 0 || start == 0 || all.size > 3000) continue
                val again = RazorLexer(kind).also { it.start(text, start, text.length, 0) }
                for (expected in all.filter { it.first >= start }.take(50)) {
                    assertEquals("${file.path}: restart at $start", expected, Triple(again.tokenStart, again.tokenEnd, again.state))
                    again.advance()
                }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        File("build/reports/razor").mkdirs()
        File("build/reports/razor/lexer-corpus.txt").writeText("files ${files.size}, tokens $tokens, $millis ms (with the restarts)\nunclosed Razor braces: ${unclosed.size}\n" + unclosed.joinToString("\n"))
        assertEquals(unclosed.joinToString("\n"), 0, unclosed.size)
    }

    private fun String.visible() = replace("\r\n", "¶").replace("\n", "¶").replace("\t", "→")

    private fun corpus(): File? {
        System.getProperty("razor.corpus")?.let { return File(it).takeIf(File::isDirectory) }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, ".corpus/razor/razor-compiler/src/Razor/src/Compiler/Microsoft.AspNetCore.Razor.Language/test/TestFiles/ParserTests")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private fun case(spans: File): Case? {
        val tree = File(spans.path.removeSuffix(".cspans.txt") + ".stree.txt").takeIf(File::isFile) ?: return null
        val header = tree.useLines { it.firstOrNull() }?.removePrefix("﻿") ?: return null
        val match = Regex("""^RazorDocument - \[0\.\.(\d+)\)::\d+ - \[(.*)]$""").find(header) ?: return null
        val length = match.groupValues[1].toInt()
        val text = listOf("\r\n", "\n").map { match.groupValues[2].replace("LF", it) }.firstOrNull { it.length == length } ?: return null
        val expected = CharArray(length) { '-' }
        for (line in spans.readLines()) {
            val span = Regex("""^﻿?(\w+) span at \((\d+):\d+,\d+ \[(\d+)] \)""").find(line) ?: continue
            val c = when (span.groupValues[1]) {
                "Markup" -> 'M'
                "Transition" -> 'T'
                "MetaCode" -> 'X'
                "Code" -> 'C'
                "Comment" -> 'K'
                else -> '-'
            }
            val start = span.groupValues[2].toInt()
            for (i in start until minOf(length, start + span.groupValues[3].toInt())) expected[i] = c
        }
        return Case(spans.parentFile.name + "/" + spans.name.removeSuffix(".cspans.txt"), text, expected)
    }

    /** The class of each character by [RazorLexer]'s tokens, as the compiler's spans have them. */
    private fun classes(text: String, kind: RazorFileKind): CharArray {
        val result = CharArray(text.length) { '-' }
        val lexer = RazorLexer(kind)
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            val s = lexer.tokenStart
            val e = lexer.tokenEnd
            when (lexer.tokenType) {
                RazorTokenTypes.COMMENT -> {
                    val closed = e - s >= 4 && text.startsWith("*@", e - 2)
                    for (i in s until e) result[i] = when {
                        i == s || (closed && i == e - 1) -> 'T'
                        i == s + 1 || (closed && i == e - 2) -> 'X'
                        else -> 'K'
                    }
                }
                TokenType.WHITE_SPACE -> {}
                else -> result.fill(CLASSES[lexer.tokenType] ?: '-', s, e)
            }
            lexer.advance()
        }
        return result
    }

    private companion object {
        val CLASSES = mapOf(
            RazorTokenTypes.MARKUP to 'M', RazorTokenTypes.ESCAPE to 'M', RazorTokenTypes.DIRECTIVE_ATTRIBUTE to 'M', RazorTokenTypes.ATTRIBUTE_VALUE to 'M',
            RazorTokenTypes.TRANSITION to 'T', RazorTokenTypes.TEXT_TAG to 'T',
            RazorTokenTypes.DIRECTIVE to 'X', RazorTokenTypes.LBRACE to 'X', RazorTokenTypes.RBRACE to 'X', RazorTokenTypes.LPAREN to 'X',
            RazorTokenTypes.RPAREN to 'X', RazorTokenTypes.COLON to 'X',
            RazorTokenTypes.CSHARP to 'C', RazorTokenTypes.CSHARP_CODE to 'C', RazorTokenTypes.DIRECTIVE_VALUE to 'C',
        )
    }
}
