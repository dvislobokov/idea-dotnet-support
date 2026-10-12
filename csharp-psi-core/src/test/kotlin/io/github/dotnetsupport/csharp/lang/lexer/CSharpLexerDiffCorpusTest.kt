package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.psi.TokenType
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import io.github.dotnetsupport.csharp.lang.oracle.RoslynDump
import junit.framework.TestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Token gate of the lexer (step 4): our tokens against `roslyndump tokens` (and the structured trivia spans of
 * `roslyndump tree`) on a corpus, by the rules of `docs/csharp-psi/GRAMMAR.md`, "Lexer vs Roslyn's tokens". The lexer runs with
 * no preprocessor symbols, as `roslyndump` without `--define`.
 *  - compared are tokens (kind, start, end) except whitespace and new lines — directive tokens by their Roslyn kind
 *    ([CSharpDirectiveTokenType.roslynKind]) — plus the trivia `V` records of comments, conflict markers,
 *    `DisabledTextTrivia` (excluded `#if` branches, conflict regions, a bad `#elif`'s expression) and
 *    `PreprocessingMessageTrivia`, and doc comments (span of the tree's documentation trivia, from its first exterior
 *    (`///` or slash-star-star), without the final new line);
 *  - the oracle's parser-merged `>>`, `>>=`, `>>>`, `>>>=`, `..` are split into the lexer's `>`, `>=`, `.` and its
 *    contextual keywords are identifiers, outside directives only; zero-width tokens and trivia (missing, empty format
 *    text, empty disabled text) are dropped; tokens inside doc comments are not compared (their XML is not lexed into
 *    tokens yet); `BAD_CHARACTER` is Roslyn's `BadToken`;
 *  - a misplaced directive (Roslyn: one `BadToken` in `SkippedTokensTrivia`, from `#` through its new line) is
 *    compared with the union of our tokens over the same span, which must start with a directive `#`.
 *
 * Roots: `.corpus/roslyn/src` and `testData/lexer` always; `-Dcsharppsi.lexer.corpora=runtime,aspnetcore` (or the environment variable `CSHARPPSI_LEXER_CORPORA`) adds
 * `.corpus/<name>/src`. Metrics: `testData/metrics/<root>-lexer.json`; mismatches: `build/lexer-gate/<root>.txt`.
 */
class CSharpLexerDiffCorpusTest : TestCase() {

    fun testRoslynSrc() = gate("roslyn-src", CSharpTestUtil.corpusRoot().resolve("roslyn/src"))

    fun testLexerTestData() = gate("testdata", Paths.get(CSharpTestUtil.testDataPath("lexer")))

    fun testExtraCorpora() {
        val names = (System.getProperty("csharppsi.lexer.corpora") ?: System.getenv("CSHARPPSI_LEXER_CORPORA"))?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (names.isNullOrEmpty()) {
            println("CSharpLexerDiffCorpusTest: no -Dcsharppsi.lexer.corpora, runtime/aspnetcore skipped")
            return
        }
        for (name in names) gate(name, CSharpTestUtil.corpusRoot().resolve("$name/src"))
    }

    private class Tok(val kind: String, val start: Int, val end: Int) {
        override fun toString() = "$kind $start $end"
    }

    private class Stats {
        var files = 0L
        var tokens = 0L
        var directiveTokens = 0L
        var disabledTexts = 0L
        var misplacedDirectives = 0L
        var badCharacters = 0L
        var tokenMismatches = 0L
        var filesWithMismatches = 0L
    }

    /** Structured trivia spans of one file from `roslyndump tree` (the `V` records). */
    private class TreeTrivia {
        val docComments = ArrayList<Tok>()
        val directives = ArrayList<Tok>()
    }

    private fun gate(name: String, root: Path) {
        if (!Files.isDirectory(root)) {
            println("CSharpLexerDiffCorpusTest: $root is missing (tools/csharp-psi/fetch-roslyn.sh, tools/csharp-psi/fetch-corpus.sh), $name skipped")
            return
        }
        val work = CSharpTestUtil.buildDir("lexer-gate")
        val tokensDump = work.resolve("$name.tokens")
        val treeDump = work.resolve("$name.tree")
        val t0 = System.currentTimeMillis()
        println("  roslyndump tokens: " + RoslynDump.runToFile("tokens", root, tokensDump))
        println("  roslyndump tree: " + RoslynDump.runToFile("tree", root, treeDump))
        val trees = readTreeTrivia(treeDump)
        val stats = Stats()
        val report = StringBuilder()
        RoslynDump.forEachFile(tokensDump) { dump ->
            compareFile(root, dump, trees[dump.path] ?: TreeTrivia(), stats, report)
        }
        Files.deleteIfExists(treeDump)
        Files.deleteIfExists(tokensDump)
        val reportFile = work.resolve("$name.txt")
        Files.writeString(reportFile, report)
        println(
            "CSharpLexerDiffCorpusTest[$name]: files=${stats.files} tokens=${stats.tokens} " +
                "directiveTokens=${stats.directiveTokens} disabledTexts=${stats.disabledTexts} " +
                "misplacedDirectives=${stats.misplacedDirectives} badCharacters=${stats.badCharacters} " +
                "tokenMismatches=${stats.tokenMismatches} filesWithMismatches=${stats.filesWithMismatches} " +
                "skippedForPreprocessor=0 millis=${System.currentTimeMillis() - t0} report=$reportFile",
        )
        report.lineSequence().take(40).forEach { println("    $it") }
        val metrics = linkedMapOf(
            "files" to stats.files,
            "tokens" to stats.tokens,
            "directiveTokens" to stats.directiveTokens,
            "disabledTexts" to stats.disabledTexts,
            "misplacedDirectives" to stats.misplacedDirectives,
            "badCharacters" to stats.badCharacters,
            "tokenMismatches" to stats.tokenMismatches,
            "filesWithMismatches" to stats.filesWithMismatches,
            // Nothing is skipped any more: directives are lexed and #if is evaluated (kept at 0 for the history).
            "skippedForPreprocessor" to 0L,
        )
        CorpusMetrics.check(
            Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("$name-lexer.json"),
            metrics,
            informational = setOf("files", "tokens", "directiveTokens", "disabledTexts", "misplacedDirectives"),
        )
    }

    /** Streams a `tree` dump keeping only the `V` records of doc comments and directives. */
    private fun readTreeTrivia(dump: Path): Map<String, TreeTrivia> {
        val result = HashMap<String, TreeTrivia>()
        var current: TreeTrivia? = null
        Files.newBufferedReader(dump, StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("file ")) {
                    current = TreeTrivia().also { result[line.substring(5)] = it }
                } else if (line.startsWith("V ")) {
                    val f = line.split(' ')
                    val tok = Tok(f[1], f[2].toInt(), f[3].toInt())
                    val trivia = current ?: continue
                    when {
                        tok.kind.endsWith("DocumentationCommentTrivia") -> trivia.docComments += tok
                        tok.kind.endsWith("DirectiveTrivia") -> trivia.directives += tok
                    }
                }
            }
        }
        return result
    }

    private fun compareFile(root: Path, dump: DumpFile, tree: TreeTrivia, stats: Stats, report: StringBuilder) {
        val text = readSource(root.resolve(dump.path))
        stats.files++

        // Ours, whitespace and new lines included (for the boundaries of misplaced directives).
        val all = ArrayList<Tok>()
        val directiveHashes = HashSet<Int>()
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            lexer.advance()
            val kind = when {
                type == TokenType.BAD_CHARACTER -> "BadToken"
                type is CSharpDirectiveTokenType -> type.roslynKind.toString()
                else -> type.toString()
            }
            if (type is CSharpDirectiveTokenType && type.roslynKind == SyntaxKind.HashToken) directiveHashes += start
            if (type == TokenType.BAD_CHARACTER) stats.badCharacters++
            all += Tok(kind, start, end)
        }

        // Misplaced directives: the oracle's BadToken from `#` through the new line against the union of ours.
        val misplaced = dump.roots.filter {
            it.kind == "BadToken" && it.end > it.start && it.start in directiveHashes
        }.map { Tok("BadToken", it.start, it.end) }
        var oursAll: List<Tok> = all
        if (misplaced.isNotEmpty()) {
            val ends = all.mapTo(HashSet()) { it.end }
            for (m in misplaced) {
                if (m.end !in ends) continue // boundaries differ: left for the comparison to report
                stats.misplacedDirectives++
                oursAll = oursAll.filter { !(it.start >= m.start && it.end <= m.end) } + m
            }
        }
        val ours = oursAll.filter { it.kind != "WhitespaceTrivia" && it.kind != "EndOfLineTrivia" }

        // The oracle's.
        val excluded = ArrayList<Tok>() // doc comments: not compared inside
        val exteriorEnds = HashMap<Int, Int>()
        dump.trivia.filter { it.kind == "DocumentationCommentExteriorTrivia" }.forEach { exteriorEnds.putIfAbsent(it.end, it.start) }
        val oracle = ArrayList<Tok>()
        for (doc in tree.docComments) {
            val start = exteriorEnds[doc.start] ?: doc.start
            var end = doc.end
            if (doc.kind == "SingleLineDocumentationCommentTrivia" && end > start && end <= text.length && text[end - 1] == '\n') end--
            oracle += Tok(doc.kind, start, end)
            // The XML tokens up to the full end (the final new line included) are not compared.
            excluded += Tok(doc.kind, start, doc.end)
        }
        excluded += ours.filter { it.kind == "SingleLineDocumentationCommentTrivia" || it.kind == "MultiLineDocumentationCommentTrivia" }
        val excludedSpans = Spans(excluded.filter { it.end > it.start }.map { it.start to it.end })
        fun isExcluded(start: Int, end: Int) = excludedSpans.contains(start, end)
        val directiveSpans = Spans(tree.directives.filter { it.end > it.start }.map { it.start to it.end })
        for (t in dump.roots) {
            if (t.end <= t.start || isExcluded(t.start, t.end)) continue
            if (directiveSpans.contains(t.start, t.end)) {
                stats.directiveTokens++
                oracle += Tok(t.kind, t.start, t.end)
            } else {
                split(normalizeKind(t.kind), t.start, t.end, oracle)
            }
        }
        // An interpolated string the parser skipped without splitting (error recovery): our parts as one token.
        var oursMerged: List<Tok> = ours
        for (t in dump.roots) {
            if (t.kind != "InterpolatedStringToken" || t.end <= t.start) continue
            val inside = oursMerged.filter { it.start >= t.start && it.end <= t.end }
            if (inside.isEmpty()) continue
            oursMerged = oursMerged.filter { !(it.start >= t.start && it.end <= t.end) } +
                Tok("InterpolatedStringToken", inside.minOf { it.start }, inside.maxOf { it.end })
        }
        for (v in dump.trivia) {
            if (v.kind !in COMPARED_TRIVIA || v.end <= v.start) continue
            if (isExcluded(v.start, v.end)) continue
            if (v.kind == "DisabledTextTrivia") stats.disabledTexts++
            oracle += Tok(v.kind, v.start, v.end)
        }
        stats.tokens += oracle.size

        val a = oracle.sortedWith(compareBy<Tok>({ it.start }, { it.end }, { it.kind }))
        val b = oursMerged.sortedWith(compareBy<Tok>({ it.start }, { it.end }, { it.kind }))
        var i = 0
        var j = 0
        var mismatches = 0
        val lines = ArrayList<String>()
        fun miss(tok: Tok, side: String) {
            mismatches++
            if (lines.size < 20) lines += "  $side $tok `${excerpt(text, tok)}`"
        }
        while (i < a.size || j < b.size) {
            val x = a.getOrNull(i)
            val y = b.getOrNull(j)
            when {
                x != null && y != null && x.kind == y.kind && x.start == y.start && x.end == y.end -> { i++; j++ }
                y == null || (x != null && (x.start < y.start || (x.start == y.start && x.end <= y.end))) -> { miss(x!!, "roslyn"); i++ }
                else -> { miss(y, "ours"); j++ }
            }
        }
        if (mismatches > 0) {
            stats.tokenMismatches += mismatches
            stats.filesWithMismatches++
            report.append(dump.path).append(" mismatches=").append(mismatches).append('\n')
            lines.forEach { report.append(it).append('\n') }
        }
    }

    /** Union of half-open spans; [contains] is a binary search. */
    private class Spans(spans: List<Pair<Int, Int>>) {
        private val starts: IntArray
        private val ends: IntArray

        init {
            val merged = ArrayList<IntArray>()
            for ((s, e) in spans.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && s < last[1]) last[1] = maxOf(last[1], e) else merged += intArrayOf(s, e)
            }
            starts = IntArray(merged.size) { merged[it][0] }
            ends = IntArray(merged.size) { merged[it][1] }
        }

        fun contains(start: Int, end: Int): Boolean {
            var i = java.util.Arrays.binarySearch(starts, start)
            if (i < 0) i = -i - 2
            return i >= 0 && end <= ends[i]
        }
    }

    /** The lexer's tokens for a parser-merged oracle token. */
    private fun split(kind: String, start: Int, end: Int, out: MutableList<Tok>) {
        val parts = when (kind) {
            "GreaterThanGreaterThanToken" -> listOf("GreaterThanToken" to 1, "GreaterThanToken" to 1)
            "GreaterThanGreaterThanEqualsToken" -> listOf("GreaterThanToken" to 1, "GreaterThanEqualsToken" to 2)
            "GreaterThanGreaterThanGreaterThanToken" -> listOf("GreaterThanToken" to 1, "GreaterThanToken" to 1, "GreaterThanToken" to 1)
            "GreaterThanGreaterThanGreaterThanEqualsToken" ->
                listOf("GreaterThanToken" to 1, "GreaterThanToken" to 1, "GreaterThanEqualsToken" to 2)
            "DotDotToken" -> listOf("DotToken" to 1, "DotToken" to 1)
            else -> {
                out += Tok(kind, start, end)
                return
            }
        }
        var p = start
        for ((k, len) in parts) {
            out += Tok(k, p, p + len)
            p += len
        }
    }

    private fun excerpt(text: String, tok: Tok): String {
        val s = text.substring(tok.start.coerceIn(0, text.length), tok.end.coerceIn(0, text.length))
        return (if (s.length > 60) s.take(60) + "..." else s).replace("\n", "\\n")
    }

    companion object {
        /** Reserved keyword kinds; any other `*Keyword` of the parser's tokens is an identifier for the lexer. */
        private val RESERVED = io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts.reservedKeywords.values.map { it.toString() }.toSet()

        /** Trivia without structure that is compared (whitespace and new lines are not). */
        private val COMPARED_TRIVIA = setOf(
            "SingleLineCommentTrivia", "MultiLineCommentTrivia", "ConflictMarkerTrivia", "DisabledTextTrivia",
            "PreprocessingMessageTrivia",
        )

        /** Contextual keywords and `_` are identifiers in the lexer (the parser converts them); not in directives. */
        private fun normalizeKind(kind: String): String =
            if ((kind.endsWith("Keyword") && kind !in RESERVED) || kind == "UnderscoreToken") "IdentifierToken" else kind

        /** As `File.ReadAllText` of roslyndump (BOM detection, UTF-8 by default), then CRLF/CR to LF. */
        fun readSource(path: Path): String {
            val bytes = Files.readAllBytes(path)
            val text = when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
                else -> String(bytes, StandardCharsets.UTF_8)
            }
            return text.replace("\r\n", "\n").replace('\r', '\n')
        }
    }
}
