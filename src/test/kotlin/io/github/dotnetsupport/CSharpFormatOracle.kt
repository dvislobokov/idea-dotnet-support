package io.github.dotnetsupport

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lang.CSharpFormatOptions
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The oracle of the native formatter (`CSharpFeature.FORMATTING`): `dotnet format whitespace` on the same files. Not a test of `test`
 * (it runs `dotnet`): `./gradlew formatOracle`, or tools/csharp-psi/format-oracle.sh. Options (`-PformatOracle.<name>=`):
 * `sample` files of the corpus (300), `corpus` its folder (`.corpus` of the repository or `~/csharp-psi/.corpus`), `variants`
 * (`orig,flat,knr`: the files as they are; every indent removed and the spaces between tokens doubled; `{` moved up to the line before),
 * `out` the folder of inputs, results and the report (`build/format-oracle`), `examples` per category (25), `dotnet` the command,
 * `styles` (`dotnet,rider`).
 *
 * Two styles of the formatter: `dotnet` without Rider's lists ([CSharpFormatOptions.riderLists] off), which must be exactly
 * `dotnet format`; `rider`, as Reformat Code runs it, whose multi-line initializers, collection expressions, argument and parameter
 * lists are Rider's on purpose (0.1.68). A file of `rider` that differs counts as "only Rider's lists" when the difference is theirs:
 * `dotnet` leaves the output of `dotnet format` as it is and `rider` makes of it what it made of the input. Rider's own layout is
 * checked by the golden pairs of `resources/formatting/rider` (from `jb cleanupcode`), not here.
 *
 * Besides the comparison, every output must keep the code (only whitespace changed) and be stable (formatting it again changes nothing).
 */
class CSharpFormatOracle : BasePlatformTestCase() {
    private fun option(name: String): String? = System.getProperty("formatOracle.$name")?.takeIf { it.isNotBlank() }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testAgainstDotnetFormat() {
        val repo = File(option("repoRoot") ?: ".").absoluteFile
        val out = File(option("out") ?: File(repo, "build/format-oracle").path).absoluteFile
        val sample = option("sample")?.toInt() ?: 300
        val examples = option("examples")?.toInt() ?: 25
        val variants = (option("variants") ?: "orig,flat,knr").split(',').map { it.trim() }
        val styles = (option("styles") ?: "$DOTNET,$RIDER").split(',').map { it.trim() }.onEach { require(it == DOTNET || it == RIDER) { "unknown style $it" } }
        val corpus = option("corpus")?.let(::File)
            ?: listOf(File(repo, ".corpus"), File(repo.parentFile, "csharp-psi/.corpus"), File(System.getProperty("user.home"), "csharp-psi/.corpus")).firstOrNull { it.isDirectory }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.FORMATTING, CSharpFeatureSource.NATIVE)

        val sources = sources(repo, corpus, sample)
        println("format oracle: ${sources.size} files (corpus: ${corpus ?: "none"}), variants $variants, out $out")
        out.deleteRecursively()
        val input = File(out, "input")
        val expected = File(out, "expected")
        val actual = File(out, "actual")
        val cases = ArrayList<Case>()
        for (variant in variants) {
            for ((index, source) in sources.withIndex()) {
                val text = perturb(variant, source.second) ?: continue
                val name = "%04d_%s".format(index, source.first.substringAfterLast('/'))
                cases += Case(variant, name, source.first, text)
                File(input, "$variant/$name").apply { parentFile.mkdirs() }.writeText(text)
            }
        }
        input.copyRecursively(expected)
        // the default style of .NET; LF, as the IDE keeps documents
        File(expected, ".editorconfig").writeText("root = true\n\n[*.cs]\nindent_style = space\nindent_size = 4\ntab_width = 4\nend_of_line = lf\n")
        val started = System.currentTimeMillis()
        val process = ProcessBuilder(option("dotnet") ?: "dotnet", "format", "whitespace", "--folder", expected.path).directory(expected).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.MINUTES)) { "dotnet format did not finish" }
        println("dotnet format whitespace: exit ${process.exitValue()}, ${(System.currentTimeMillis() - started) / 1000} s ${log.lines().filter { it.isNotBlank() }.take(5)}")

        val report = StringBuilder()
        val byVariant = LinkedHashMap<String, Stats>()
        val byCategory = LinkedHashMap<String, MutableList<String>>()
        var nativeTime = 0L
        for (style in styles) for (case in cases) {
            val key = "$style/${case.variant}"
            val stats = byVariant.getOrPut(key) { Stats() }
            stats.files++
            val reference = normalize(File(expected, "${case.variant}/${case.name}").readText())
            val begin = System.nanoTime()
            val formatted = try {
                format(case.text, case.name, style)
            } catch (e: Throwable) {
                stats.exceptions++
                byCategory.getOrPut("$style: exception") { ArrayList() } += "$key/${case.name} (${case.source}): $e\n    ${e.stackTrace.take(6).joinToString("\n    ")}"
                continue
            }
            nativeTime += System.nanoTime() - begin
            File(actual, "$key/${case.name}").apply { parentFile.mkdirs() }.writeText(formatted)
            if (nonWhitespace(formatted) != nonWhitespace(case.text)) {
                stats.codeChanged++
                byCategory.getOrPut("$style: code changed") { ArrayList() } += "$key/${case.name} (${case.source})"
            }
            val again = runCatching { format(formatted, case.name, style) }.getOrNull()
            if (again != formatted) {
                stats.unstable++
                byCategory.getOrPut("$style: not idempotent") { ArrayList() } += "$key/${case.name}: ${firstDifference(formatted, again ?: "<exception>")}"
            }
            if (formatted == reference) {
                stats.same++
                continue
            }
            // Rider's lists differ from dotnet format on purpose: the difference is theirs when dotnet format's own output is left as it
            // is without them and becomes, with them, what the input became
            if (style == RIDER && runCatching { format(reference, case.name, DOTNET) == reference && format(reference, case.name, RIDER) == formatted }.getOrDefault(false)) {
                stats.riderLists++
                byCategory.getOrPut("$style: Rider's lists (declared)") { ArrayList() } +=
                    "$key/${case.name} (${case.source}): ${firstDifference(reference, formatted)}"
                continue
            }
            val expectedLines = reference.lines()
            val actualLines = formatted.lines()
            val inputLines = case.text.lines()
            val category = when {
                expectedLines.size != actualLines.size -> "line breaks"
                expectedLines.indices.all { expectedLines[it].trimStart() == actualLines[it].trimStart() } -> "indent"
                else -> "spaces"
            }
            stats.categories.merge(category, 1, Int::plus)
            val diffs = expectedLines.indices.filter { it < actualLines.size && expectedLines[it] != actualLines[it] }
            stats.lines += diffs.size
            val line = diffs.firstOrNull() ?: expectedLines.indices.firstOrNull { it >= actualLines.size || expectedLines[it] != actualLines[it] } ?: 0
            byCategory.getOrPut("$style: $category") { ArrayList() } += buildString {
                append("$key/${case.name} (${case.source}) line ${line + 1}, ${diffs.size} lines differ\n")
                for (k in maxOf(0, line - 2)..minOf(line + 2, expectedLines.size - 1)) {
                    append("    exp|").append(expectedLines[k]).append('\n')
                    append("    act|").append(actualLines.getOrElse(k) { "<none>" }).append('\n')
                }
                if (category != "line breaks") append("    in |").append(inputLines.getOrElse(line) { "" }).append('\n')
            }
        }
        report.append("format oracle: native formatter vs dotnet format whitespace, ${sources.size} files ")
        report.append("(dotnet: without Rider's lists; rider: as Reformat Code does, the differences of Rider's lists declared)\n")
        for ((key, stats) in byVariant) {
            val rider = if (key.startsWith("$RIDER/")) "${stats.riderLists} only Rider's lists, " else ""
            report.append("  $key: ${stats.files} files, ${stats.same} identical, $rider${stats.files - stats.same - stats.riderLists - stats.exceptions} differ (${stats.lines} lines) ${stats.categories}")
            report.append(", code changed ${stats.codeChanged}, not idempotent ${stats.unstable}, exceptions ${stats.exceptions}\n")
        }
        report.append("  native formatter: ${nativeTime / 1_000_000} ms in all\n")
        val summary = report.toString()
        for ((category, list) in byCategory) {
            report.append("\n== $category: ${list.size}\n")
            list.take(examples).forEach { report.append(it).append('\n') }
        }
        File(out, "report.txt").writeText(report.toString())
        println(summary)
        println("report: ${File(out, "report.txt")}")
    }

    private companion object {
        val GENERATED_NAME = Regex("(?i)\\.(g|g\\.i|generated|designer)\\.cs$")
        val GENERATED_HEADER = Regex("(?i)<auto-?generated")

        /** The formatter without Rider's lists ([CSharpFormatOptions.riderLists]): what must be exactly `dotnet format`. */
        const val DOTNET = "dotnet"

        /** The formatter as Reformat Code runs it: Rider's lists on. */
        const val RIDER = "rider"
    }

    private class Case(val variant: String, val name: String, val source: String, val text: String)

    private class Stats {
        var files = 0
        var same = 0
        var riderLists = 0
        var lines = 0
        var codeChanged = 0
        var unstable = 0
        var exceptions = 0
        val categories = sortedMapOf<String, Int>()
    }

    /** The playground and an even sample of runtime and aspnetcore, as (name, text). */
    private fun sources(repo: File, corpus: File?, sample: Int): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        fun add(root: File, file: File) {
            val text = runCatching { normalize(file.readText()) }.getOrNull() ?: return
            // `dotnet format` leaves generated code alone
            if (GENERATED_NAME.containsMatchIn(file.name) || text.lineSequence().take(15).any { GENERATED_HEADER.containsMatchIn(it) }) return
            result += file.relativeTo(root).path.replace('\\', '/') to text
        }
        val playground = File(repo, "debug-playground")
        playground.walkTopDown().onEnter { it.name != "bin" && it.name != "obj" }.filter { it.isFile && it.extension == "cs" }.sortedBy { it.path }.forEach { add(repo, it) }
        if (corpus != null) {
            val repos = listOf("runtime", "aspnetcore").map { File(corpus, it) }.filter { it.isDirectory }
            for (dir in repos) {
                val all = dir.walkTopDown().onEnter { it.name != "bin" && it.name != "obj" }
                    .filter { it.isFile && it.extension == "cs" && it.length() in 500..60_000 }.map { it }.sortedBy { it.path }.toList()
                val take = sample / repos.size
                if (all.isEmpty() || take == 0) continue
                val step = all.size.toDouble() / take
                for (k in 0 until take) add(corpus, all[(k * step).toInt()])
            }
        }
        return result
    }

    private fun normalize(text: String): String = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')

    private fun nonWhitespace(text: String): String = text.filterNot { it.isWhitespace() }

    private fun firstDifference(a: String, b: String): String {
        val al = a.lines()
        val bl = b.lines()
        val k = al.indices.firstOrNull { it >= bl.size || al[it] != bl[it] } ?: return "lines ${al.size} vs ${bl.size}"
        return "line ${k + 1}: '${al[k]}' then '${bl.getOrElse(k) { "<none>" }}'"
    }

    private fun parse(text: String): PsiFile =
        PsiManager.getInstance(project).findFile(LightVirtualFile("Oracle.cs", CSharpFileType, text).apply { putUserData(CSharpPreprocessorSymbols.KEY, emptySet()) })!!

    /** The text of a variant, or null when the variant changes nothing of this file. */
    private fun perturb(variant: String, text: String): String? = when (variant) {
        "orig" -> text
        "flat" -> rewriteWhitespace(text) { whitespace, previous, next ->
            when {
                whitespace.contains('\n') -> whitespace.substring(0, whitespace.lastIndexOf('\n') + 1)
                isCode(previous) && isCode(next) -> whitespace + whitespace
                else -> whitespace
            }
        }
        "knr" -> rewriteWhitespace(text) { whitespace, previous, next ->
            if (whitespace.contains('\n') && next.elementType === SyntaxKind.OpenBraceToken && isCode(previous) && next.parent?.elementType !== SyntaxKind.Interpolation) " " else whitespace
        }
        else -> error("unknown variant $variant")
    }?.takeIf { variant == "orig" || it != text }

    private fun isCode(leaf: PsiElement?): Boolean = leaf != null && leaf !is PsiWhiteSpace && leaf.textLength > 0 && leaf.elementType !in CSharpTokenTypes.COMMENTS &&
        PsiTreeUtil.getParentOfType(leaf, com.intellij.psi.PsiComment::class.java, false) == null

    /** Whitespace leaves of the file's own level (not inside comments), each replaced by [replace]. */
    private fun rewriteWhitespace(text: String, replace: (String, PsiElement?, PsiElement) -> String): String {
        val file = parse(text)
        val result = StringBuilder()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        var previous: PsiElement? = null
        while (leaf != null) {
            if (leaf is PsiWhiteSpace && PsiTreeUtil.getParentOfType(leaf, com.intellij.psi.PsiComment::class.java) == null) {
                // a run of whitespace leaves (a line break and the indent after it are two)
                val run = StringBuilder(leaf.text)
                var next: PsiElement? = PsiTreeUtil.nextLeaf(leaf)
                while (next != null && (next.textLength == 0 || next is PsiWhiteSpace)) {
                    run.append(next.text)
                    leaf = next
                    next = PsiTreeUtil.nextLeaf(next)
                }
                result.append(if (next == null || previous == null || next.elementType === SyntaxKind.DisabledTextTrivia || previous.elementType === SyntaxKind.DisabledTextTrivia)
                    run else replace(run.toString(), previous, next))
            } else {
                result.append(leaf.text)
                if (leaf.textLength > 0) previous = leaf
            }
            leaf = PsiTreeUtil.nextLeaf(leaf!!)
        }
        return result.toString()
    }

    private fun format(text: String, name: String, style: String): String {
        // no #if symbols, as `dotnet format --folder`
        val virtualFile = LightVirtualFile(name, CSharpFileType, text).apply { putUserData(CSharpPreprocessorSymbols.KEY, emptySet()) }
        val file = PsiManager.getInstance(project).findFile(virtualFile)!!
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file)!!
        CSharpFormatOptions.dotnetFormatOnly = style == DOTNET
        try {
            WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
        } finally {
            CSharpFormatOptions.dotnetFormatOnly = false
        }
        documents.commitDocument(document)
        return document.text
    }
}
