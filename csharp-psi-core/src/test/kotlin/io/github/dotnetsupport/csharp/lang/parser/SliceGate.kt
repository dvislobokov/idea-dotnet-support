package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import io.github.dotnetsupport.csharp.lang.oracle.Mismatch
import io.github.dotnetsupport.csharp.lang.oracle.RoslynDump
import io.github.dotnetsupport.csharp.lang.oracle.TreeDiff
import java.nio.file.Files
import java.nio.file.Path

/**
 * Diffs every `*.expr` / `*.stmt` snippet under a directory against `roslyndump expr|stmt` through
 * [SliceParseHarness]. A snippet is *valid* when the oracle reports no diagnostic (`D` records).
 *
 * Every snippet is diffed: the parser ports the whole grammar (the out-of-scope bucket of the step-0 slice is gone).
 *
 * What the comparison checks and does not check (see docs/csharp-psi/GRAMMAR.md, "What the gates compare"):
 *  - valid snippets: every node and token by kind and span, plus **no error element and no counted error** in our tree
 *    (a zero-width missing token has no counterpart in the oracle's tree after normalisation and would otherwise pass
 *    unnoticed; reported as the class `spurious error`);
 *  - invalid snippets: nodes and tokens by kind and span, where Roslyn's missing tokens are ignored, our zero-width
 *    elements are positioned with Roslyn's rule ([io.github.dotnetsupport.csharp.lang.oracle.PsiToDump]), non-empty
 *    error elements do not extend spans, and tokens inside the oracle's `SkippedTokensTrivia` are stripped from our
 *    tree. Not checked: which tokens are missing, where exactly they are, how many diagnostics there are. The counts
 *    [Stats.invalidErrorElements] (ours) against [Stats.invalidOracleDiagnostics] and [Stats.invalidOracleSkipped]
 *    (Roslyn's `D` records and `SkippedTokensTrivia`) are recorded as informational metrics.
 */
class SliceGate(val name: String) {
    class Stats {
        var validFiles = 0L
        var validMismatchedFiles = 0L
        var validMismatches = 0L
        /** Valid snippets where our tree has an error element or a counted error (a subset of [validMismatchedFiles]). */
        var validSpuriousErrorFiles = 0L
        var invalidFiles = 0L
        var invalidExceptions = 0L
        var invalidMismatchedFiles = 0L
        var invalidMismatches = 0L
        var invalidErrorElements = 0L
        var invalidOracleDiagnostics = 0L
        var invalidOracleSkipped = 0L
        var validExceptions = 0L
        /** Doc comments of the oracle compared by [DocCommentDiff]; their mismatches count in the buckets above. */
        var docComments = 0L
        var docCommentMismatches = 0L
    }

    val stats = Stats()
    val diff = TreeDiff()
    val report = StringBuilder()
    val exceptions = ArrayList<String>()

    /** Runs the oracle over [root] for one mode and diffs all its snippets; [filter] selects the files of this gate. */
    fun run(root: Path, mode: String, filter: (DumpFile) -> Boolean = { true }) {
        run(root, SliceParseHarness.Mode.entries.single { it.name.lowercase() == mode || it.dumpMode == mode }, listOf(filter))
    }

    /**
     * Runs the oracle once for [mode] at [version] (both sides parse at it), over the files of [root] or only over
     * [includes] (relative paths); each dumped file goes to the first gate (of [gates]) whose filter takes it.
     */
    fun run(
        root: Path,
        mode: SliceParseHarness.Mode,
        filters: List<(DumpFile) -> Boolean>,
        gates: List<SliceGate> = listOf(this),
        version: CSharpLanguageVersion = CSharpLanguageVersion.Preview,
        includes: List<String> = emptyList(),
    ) {
        val run = RoslynDump.run(mode.dumpMode, root, langVersion = version.displayString, includes = includes)
        val scope = if (includes.isEmpty()) "" else " (${includes.size} inputs)"
        println("  roslyndump ${mode.dumpMode} --langversion ${version.displayString} $root$scope: ${run.summary}")
        for (dump in run.files) {
            val i = filters.indexOfFirst { it(dump) }
            if (i < 0) continue
            gates[i].compare(root, dump, mode, version)
        }
    }

    fun compare(root: Path, dump: DumpFile, mode: SliceParseHarness.Mode, version: CSharpLanguageVersion = CSharpLanguageVersion.Preview) {
        val file = root.resolve(dump.path)
        val text = CSharpLexerDiffCorpusTest.readSource(file)
        val valid = dump.diagnostics.isEmpty()
        val label = if (version == CSharpLanguageVersion.Preview) dump.path else "${dump.path} @${version.displayString}"
        val result = try {
            SliceParseHarness.parse(text, mode, version)
        } catch (e: Throwable) {
            if (valid) stats.validExceptions++ else stats.invalidExceptions++
            exceptions += "$label: $e"
            report.append(label).append(" EXCEPTION ").append(e.toString()).append('\n')
            e.stackTrace.take(12).forEach { report.append("    at ").append(it).append('\n') }
            return
        }
        compareParsed(dump, text, result, label)
    }

    /** The comparison proper; separated from the parse so that tests can feed a synthetic [dump]. */
    fun compareParsed(dump: DumpFile, text: String, result: SliceParseHarness.Result, label: String = dump.path) {
        val valid = dump.diagnostics.isEmpty()
        val mismatches = ArrayList(diff.diff(dump, result.roots))
        val docs = DocCommentDiff.compare(dump.path, dump, result.root, diff)
        stats.docComments += docs.comments
        stats.docCommentMismatches += docs.mismatches.size
        mismatches += docs.mismatches
        if (valid) {
            stats.validFiles++
            if (result.errorElements > 0 || result.errorCount > 0) {
                stats.validSpuriousErrorFiles++
                val m = Mismatch(dump.path, 0, "spurious error", "no error", "errorElements=${result.errorElements} errorCount=${result.errorCount}")
                diff.mismatches += m
                mismatches += m
            }
            if (mismatches.isNotEmpty()) {
                stats.validMismatchedFiles++
                stats.validMismatches += mismatches.size
            }
        } else {
            stats.invalidFiles++
            stats.invalidErrorElements += result.errorElements
            stats.invalidOracleDiagnostics += dump.diagnostics.size
            stats.invalidOracleSkipped += dump.trivia.count { it.kind == "SkippedTokensTrivia" }
            if (mismatches.isNotEmpty()) {
                stats.invalidMismatchedFiles++
                stats.invalidMismatches += mismatches.size
            }
        }
        if (mismatches.isNotEmpty()) {
            report.append(label).append(if (valid) " valid" else " invalid").append(" mismatches=").append(mismatches.size).append('\n')
            report.append("  text: ").append(text.replace("\n", "\\n").take(200)).append('\n')
            mismatches.take(8).forEach { report.append("  ").append(it).append('\n') }
        }
    }

    fun writeReport(dir: Path): Path {
        val file = dir.resolve("$name.txt")
        Files.writeString(file, report)
        return file
    }

    fun summary(): String =
        "$name: validFiles=${stats.validFiles} validMismatchedFiles=${stats.validMismatchedFiles} validMismatches=${stats.validMismatches} " +
            "validSpuriousErrorFiles=${stats.validSpuriousErrorFiles} " +
            "invalidFiles=${stats.invalidFiles} invalidMismatchedFiles=${stats.invalidMismatchedFiles} invalidMismatches=${stats.invalidMismatches} " +
            "invalidExceptions=${stats.invalidExceptions} validExceptions=${stats.validExceptions} " +
            "invalidErrorElements=${stats.invalidErrorElements} invalidOracleDiagnostics=${stats.invalidOracleDiagnostics} invalidOracleSkipped=${stats.invalidOracleSkipped} " +
            "docComments=${stats.docComments} docCommentMismatches=${stats.docCommentMismatches}"
}
