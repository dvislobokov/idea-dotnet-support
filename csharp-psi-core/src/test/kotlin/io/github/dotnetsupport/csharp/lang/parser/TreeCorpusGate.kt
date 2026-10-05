package io.github.dotnetsupport.csharp.lang.parser

import com.intellij.lang.ASTNode
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import io.github.dotnetsupport.csharp.lang.oracle.Allowlist
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import io.github.dotnetsupport.csharp.lang.oracle.Mismatch
import io.github.dotnetsupport.csharp.lang.oracle.RoslynDump
import io.github.dotnetsupport.csharp.lang.oracle.TreeDiff
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Whole-file tree gate (step 6 of docs/csharp-psi/PLAN.md): every `*.cs` file of a corpus directory, parsed by our parser through
 * the platform's public path, against `roslyndump tree`, by [TreeDiff]. The gate tests (`*TreeCorpusTest`) extend
 * [TreeCorpusTestBase]; the rules are in docs/csharp-psi/TESTING.md, "Tree gates".
 *
 * - The oracle's output is streamed from the process's stdout ([RoslynDump.parse]): a corpus dump is gigabytes
 *   (runtime: 2.2 GB, 30 M nodes), so it is never written to disk and parsing overlaps with the oracle.
 * - Each file is parsed by [parse] (a `PsiFile` from `PsiFileFactory` over a `LightVirtualFile`, so that the
 *   `ParserDefinition`, the file element type and lazy elements run as in the IDE), mapped by [PsiToDump][ParserGateSupport.newDump]
 *   and diffed on [Options.threads] threads, under [ParseGuard] (per-file timeout, stack overflow, logged errors).
 * - Buckets: files the oracle parses without diagnostics (*valid*) and files with Roslyn diagnostics (*invalid*,
 *   compared with the normalisation of invalid input: missing tokens dropped, skipped tokens stripped, see
 *   docs/csharp-psi/GRAMMAR.md, "What the gates compare"). A valid file with an error element in our tree is a mismatch of
 *   class `spurious error` and counts in `spuriousErrorFiles`.
 * - Only counts and a bounded sample of mismatches are kept in memory; the per-file list (capped) goes to
 *   `build/tree-gate/<name>-mismatches.txt`, failures with stack traces to `build/tree-gate/<name>-failures.txt`.
 *
 * Metrics (`testData/metrics/<name>-tree.json`, only improve, [CorpusMetrics]) are checked only on a full run of a
 * parser that builds nodes: while `CSharpParserDefinition` has the placeholder parser (a flat file of tokens) the
 * gate reports and passes, and the first run of the wired parser creates the baseline. `-Dcsharppsi.treeGate.strict=true`
 * additionally fails on any mismatch, failure or spurious error in valid files (the end state of step 6).
 */
class TreeCorpusGate(
    val name: String,
    val root: Path,
    private val options: Options = Options.fromSystemProperties(),
    /** `roslyndump --langversion` (null: its default, `preview`); the [parse] function must parse at the same version. */
    private val langVersion: String? = null,
    /** Relative paths (files or directories) under [root] the gate covers; empty: all of it (`roslyndump --include`). */
    private val includes: List<String> = emptyList(),
    private val parse: (fileName: String, text: String) -> ASTNode,
) {
    /** Run options; all from `-Dcsharppsi.treeGate.*` (Gradle `-P` works as well). */
    class Options(
        /** Process at most this many files (in the oracle's order, after [filter]). */
        val limit: Int? = null,
        /** Only files whose relative path matches (`find`, not `matches`). */
        val filter: Regex? = null,
        /** Run the oracle on this subdirectory of the corpus root only. */
        val dir: String? = null,
        val threads: Int = defaultThreads(),
        val timeoutMillis: Long = 30_000,
        val strict: Boolean = false,
        /** `false` disables the metrics file even on a full run. */
        val metrics: Boolean = true,
        /** Mismatches per file written to the mismatches file. */
        val perFileMismatches: Int = 30,
    ) {
        val partial get() = limit != null || filter != null || dir != null

        companion object {
            private fun prop(key: String): String? = System.getProperty("csharppsi.treeGate.$key")?.takeIf { it.isNotBlank() }

            fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)

            fun fromSystemProperties() = Options(
                limit = prop("limit")?.toInt(),
                filter = prop("filter")?.let(::Regex),
                dir = prop("dir"),
                threads = prop("threads")?.toInt() ?: defaultThreads(),
                timeoutMillis = prop("timeoutMillis")?.toLong() ?: 30_000,
                strict = prop("strict")?.toBoolean() ?: false,
                metrics = prop("metrics")?.toBoolean() ?: true,
            )
        }
    }

    class Stats {
        var files = 0L
        var bytes = 0L
        var oracleNodes = 0L
        /** Files whose tree has at least one syntax node ([ParserGateSupport.hasNodes]). */
        var filesWithNodes = 0L

        var validFiles = 0L
        var mismatchedFiles = 0L
        var mismatches = 0L
        var spuriousErrorFiles = 0L

        var filesWithOracleErrors = 0L
        var invalidMismatchedFiles = 0L
        var invalidMismatches = 0L
        var invalidErrorElements = 0L
        var invalidOracleDiagnostics = 0L

        /** Files with any mismatch (tree or doc comment, valid or invalid), a spurious error or a failure. */
        var filesWithProblems = 0L

        var exceptions = 0L
        var stackOverflows = 0L
        var timeouts = 0L
        /** Leaves that do not spell the text or split a lexer token ([ParserGateSupport.checkTokenCoverage]). */
        var coverageFailures = 0L
        /** Errors the platform logged during a parse (PsiBuilder's unbalanced markers and the like). */
        var loggedErrors = 0L
        var allowlisted = 0L

        /** Doc comments of the oracle compared by [DocCommentDiff], and their mismatches in valid / invalid files. */
        var docComments = 0L
        var docCommentMismatches = 0L
        var invalidDocCommentMismatches = 0L

        var missingTokens = 0L
        var skippedTokens = 0L
        var parseMillis = 0L
        var maxParseMillis = 0L
        var slowest = ""
    }

    val stats = Stats()
    private val classCounts = HashMap<String, Long>()
    private val examples = LinkedHashMap<String, MutableList<Mismatch>>()
    private val failureSamples = ArrayList<String>()
    private val allowlist = Allowlist.read("parser", "$name-tree")
    private val usedAllowClasses = HashSet<String>()
    private val usedAllowKeys = HashSet<String>()
    private val lock = Any()

    /** Runs the oracle and the comparison; prints the summary and checks the metrics. */
    fun run() {
        val target = options.dir?.let { root.resolve(it) } ?: root
        if (!Files.isDirectory(target)) {
            println("$name tree gate: $target is missing (tools/csharp-psi/fetch-roslyn.sh, tools/csharp-psi/fetch-corpus.sh, -Dcsharppsi.playground), skipped")
            return
        }
        val out = CSharpTestUtil.buildDir("tree-gate")
        val mismatchFile = out.resolve("$name-mismatches.txt")
        val failureFile = out.resolve("$name-failures.txt")
        val t0 = System.currentTimeMillis()
        var oracleSummary = ""
        Files.newBufferedWriter(mismatchFile, StandardCharsets.UTF_8).use { mismatchOut ->
            Files.newBufferedWriter(failureFile, StandardCharsets.UTF_8).use { failureOut ->
                ParseGuard.interceptLoggedErrors {
                    oracleSummary = stream(target) { dump -> compare(target, dump, mismatchOut, failureOut) }
                }
            }
        }
        val millis = System.currentTimeMillis() - t0
        report(target, oracleSummary, millis, mismatchFile, failureFile)
        checkMetrics()
    }

    private class StopStreaming : RuntimeException(null, null, false, false)

    /**
     * Streams `roslyndump tree <target>` from stdout and hands every selected file to a worker; returns the oracle's
     * summary line. At most [Options.threads] files are in flight, so memory stays bounded.
     */
    private fun stream(target: Path, action: (DumpFile) -> Unit): String {
        val stderr = Files.createTempFile("roslyndump-tree", ".err")
        val includeList = if (includes.isEmpty() || options.dir != null) null
        else Files.createTempFile("roslyndump-include", ".txt").also { Files.write(it, includes) }
        val process = ProcessBuilder(RoslynDump.options("tree", target, langVersion = langVersion, includeList = includeList))
            .directory(CSharpTestUtil.repoRoot().toFile())
            .redirectError(stderr.toFile())
            .start()
        val executor = Executors.newFixedThreadPool(options.threads) { r -> Thread(r, "tree-gate-$name").apply { isDaemon = true } }
        val permits = Semaphore(options.threads * 2)
        var selected = 0
        var firstError: Throwable? = null
        try {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                try {
                    RoslynDump.parse(reader) { dump ->
                        if (options.filter?.containsMatchIn(dump.path) == false) return@parse
                        if (options.limit != null && selected >= options.limit) throw StopStreaming()
                        selected++
                        permits.acquire()
                        executor.execute {
                            try {
                                action(dump)
                            } catch (e: Throwable) {
                                synchronized(lock) { if (firstError == null) firstError = e }
                            } finally {
                                permits.release()
                            }
                        }
                    }
                } catch (e: RuntimeException) {
                    // RoslynDump.parse wraps exceptions of the action with the line number.
                    if (e !is StopStreaming && e.cause !is StopStreaming) throw e
                    process.destroy()
                }
            }
            executor.shutdown()
            check(executor.awaitTermination(6, TimeUnit.HOURS)) { "tree gate $name did not finish" }
            firstError?.let { throw IllegalStateException("tree gate $name: internal error", it) }
            val code = process.waitFor()
            val err = Files.readString(stderr)
            check(code == 0 || options.limit != null) { "roslyndump tree $target failed ($code):\n$err" }
            return err.lines().lastOrNull { it.startsWith("# files=") } ?: "(no summary: stopped after ${options.limit} files)"
        } finally {
            executor.shutdownNow()
            process.destroyForcibly()
            Files.deleteIfExists(stderr)
            includeList?.let { Files.deleteIfExists(it) }
        }
    }

    private fun compare(target: Path, dump: DumpFile, mismatchOut: BufferedWriter, failureOut: BufferedWriter) {
        val text = CSharpLexerDiffCorpusTest.readSource(target.resolve(dump.path))
        val valid = dump.diagnostics.isEmpty()
        val fileName = dump.path.substringAfterLast('/')
        val mapper = ParserGateSupport.newDump()
        val started = System.nanoTime()
        val outcome = ParseGuard.run(options.timeoutMillis) {
            val node = parse(fileName, text)
            val coverage = ParserGateSupport.checkTokenCoverage(node, text)
            // Doc comments are expanded by the coverage check already (lazy elements).
            Triple(mapper.map(node), coverage, DocCommentDiff.compare(dump.path, dump, node))
        }
        val parseMillis = (System.nanoTime() - started) / 1_000_000
        val (roots, coverage, docs) = outcome.value ?: Triple(null, null, null)
        val diff = TreeDiff()
        val found = if (roots != null) ArrayList(diff.diff(dump, roots)) else ArrayList()
        if (roots != null && valid && mapper.errorElements > 0) {
            found += Mismatch(dump.path, 0, "spurious error", "no error", "errorElements=${mapper.errorElements}")
        }
        val partition = allowlist.partition(found)

        synchronized(lock) {
            val s = stats
            s.files++
            s.bytes += text.length
            s.oracleNodes += dump.nodeCount()
            s.missingTokens += diff.missingTokens
            s.skippedTokens += diff.skippedTokens
            s.parseMillis += parseMillis
            if (parseMillis > s.maxParseMillis) {
                s.maxParseMillis = parseMillis
                s.slowest = "${dump.path} (${text.length} chars)"
            }
            if (roots != null && ParserGateSupport.hasNodes(roots)) s.filesWithNodes++
            if (outcome.loggedErrors.isNotEmpty()) s.loggedErrors += outcome.loggedErrors.size
            when (outcome.kind) {
                null -> {}
                ParseGuard.Kind.STACK_OVERFLOW -> s.stackOverflows++
                ParseGuard.Kind.TIMEOUT, ParseGuard.Kind.HUNG -> s.timeouts++
                ParseGuard.Kind.EXCEPTION -> s.exceptions++
            }
            if (coverage != null) s.coverageFailures++
            if (valid) {
                s.validFiles++
                if (partition.real.isNotEmpty()) {
                    s.mismatchedFiles++
                    s.mismatches += partition.real.size
                }
                if (roots != null && mapper.errorElements > 0) s.spuriousErrorFiles++
            } else {
                s.filesWithOracleErrors++
                s.invalidOracleDiagnostics += dump.diagnostics.size
                if (roots != null) s.invalidErrorElements += mapper.errorElements
                if (partition.real.isNotEmpty()) {
                    s.invalidMismatchedFiles++
                    s.invalidMismatches += partition.real.size
                }
            }
            if (docs != null) {
                s.docComments += docs.comments
                if (valid) s.docCommentMismatches += docs.mismatches.size else s.invalidDocCommentMismatches += docs.mismatches.size
                for (m in docs.mismatches) {
                    val cls = if (valid) "doc: ${m.cls}" else "invalid doc: ${m.cls}"
                    classCounts.merge(cls, 1L, Long::plus)
                    val list = examples.getOrPut(cls) { ArrayList(3) }
                    if (list.size < 3) list += m
                }
                if (docs.mismatches.isNotEmpty()) {
                    mismatchOut.append(dump.path).append(if (valid) " valid" else " invalid").append(" doc comment mismatches=")
                        .append(docs.mismatches.size.toString()).append('\n')
                    docs.mismatches.take(options.perFileMismatches).forEach { mismatchOut.append("  ").append(it.toString()).append('\n') }
                }
            }
            s.allowlisted += partition.allowed.size
            partition.allowed.forEach { m -> if (m.cls in allowlist.classes) usedAllowClasses += m.cls else usedAllowKeys += m.key }
            for (m in partition.real) {
                val cls = if (valid) m.cls else "invalid: ${m.cls}"
                classCounts.merge(cls, 1L, Long::plus)
                val list = examples.getOrPut(cls) { ArrayList(3) }
                if (list.size < 3) list += m
            }
            if (partition.real.isNotEmpty()) {
                mismatchOut.append(dump.path).append(if (valid) " valid" else " invalid").append(" mismatches=")
                    .append(partition.real.size.toString()).append('\n')
                partition.real.take(options.perFileMismatches).forEach { mismatchOut.append("  ").append(it.toString()).append('\n') }
            }
            val problems = ArrayList<String>()
            if (!outcome.ok) problems += outcome.describe()
            if (coverage != null) problems += "coverage: $coverage"
            outcome.loggedErrors.forEach { problems += "logged error: $it" }
            if (partition.real.isNotEmpty() || docs?.mismatches?.isNotEmpty() == true || problems.isNotEmpty() ||
                (valid && roots != null && mapper.errorElements > 0)
            ) {
                s.filesWithProblems++
            }
            if (problems.isNotEmpty()) {
                val line = "${dump.path}: ${problems.joinToString("; ")}"
                if (failureSamples.size < 20) failureSamples += line
                failureOut.append(line).append('\n')
                outcome.failure?.takeIf { outcome.kind == ParseGuard.Kind.EXCEPTION }?.stackTrace?.take(15)
                    ?.forEach { failureOut.append("    at ").append(it.toString()).append('\n') }
            }
        }
    }

    private fun report(target: Path, oracleSummary: String, millis: Long, mismatchFile: Path, failureFile: Path) {
        val s = stats
        val mb = s.bytes / 1_000_000.0
        val rows = linkedMapOf<String, Any>(
            "root" to target,
            "oracle options" to "--langversion ${langVersion ?: "preview (default)"}" +
                (if (includes.isEmpty()) "" else ", ${includes.size} included paths"),
            "oracle" to oracleSummary,
            "files" to "${s.files} (valid ${s.validFiles}, with Roslyn errors ${s.filesWithOracleErrors}), " +
                "${fmt(mb, 1)} MB, Roslyn nodes+tokens ${s.oracleNodes}",
            "parser" to if (s.filesWithNodes == 0L && s.files > 0) "builds no syntax nodes (placeholder)" else "nodes in ${s.filesWithNodes} files",
            "valid files" to "mismatched ${s.mismatchedFiles}, mismatches ${s.mismatches}, spurious errors ${s.spuriousErrorFiles}",
            "files with Roslyn errors" to "mismatched ${s.invalidMismatchedFiles}, mismatches ${s.invalidMismatches}, " +
                "our error elements ${s.invalidErrorElements} vs Roslyn diagnostics ${s.invalidOracleDiagnostics}",
            "failures" to "exceptions ${s.exceptions}, stack overflows ${s.stackOverflows}, timeouts ${s.timeouts}, " +
                "coverage ${s.coverageFailures}, logged errors ${s.loggedErrors}",
            "doc comments" to "${s.docComments}, mismatches ${s.docCommentMismatches} (valid files), ${s.invalidDocCommentMismatches} (with Roslyn errors)",
            "ignored tokens" to "missing ${s.missingTokens}, skipped ${s.skippedTokens}",
            "allowlisted" to "${s.allowlisted} (entries ${allowlist.size}, stale ${staleAllowlist().size})",
            "time" to "${millis} ms wall, parse+map ${s.parseMillis} ms on ${options.threads} threads " +
                "(${fmt(if (mb > 0) s.parseMillis / mb else 0.0, 0)} ms/MB of CPU), slowest ${s.maxParseMillis} ms ${s.slowest}",
            "mismatches" to mismatchFile,
            "failures file" to failureFile,
        )
        if (options.partial) rows["partial run"] = "limit=${options.limit} filter=${options.filter} dir=${options.dir}: metrics not checked"
        val width = rows.keys.maxOf { it.length } + 1
        println("TreeCorpusGate[$name] summary")
        rows.forEach { (k, v) -> println("  ${"$k:".padEnd(width)} $v") }
        println("  body block types so far (JVM-wide, informational): ${CSharpBodyBlockType.typeCount()} of ${CSharpBodyBlockType.MAX_TYPES}")
        if (classCounts.isNotEmpty()) {
            println("  mismatch classes (top 30 of ${classCounts.size}):")
            classCounts.entries.sortedByDescending { it.value }.take(30).forEach { (cls, n) -> println("    %9d  %s".format(n, cls)) }
            println("  first examples:")
            val byClass = classCounts.entries.sortedByDescending { it.value }.map { examples.getValue(it.key) }
            var printed = 0
            loop@ for (round in 0 until 3) for (list in byClass) {
                if (printed >= 30) break@loop
                list.getOrNull(round)?.let { println("    $it"); printed++ }
            }
        }
        failureSamples.forEach { println("  FAILURE $it") }
        staleAllowlist().take(20).forEach { println("  STALE ALLOWLIST $it") }
    }

    private fun fmt(v: Double, decimals: Int) = String.format(java.util.Locale.ROOT, "%.${decimals}f", v)

    private fun staleAllowlist(): List<String> =
        (allowlist.classes.filter { it !in usedAllowClasses }.map { "class $it" } + allowlist.keys.filter { it !in usedAllowKeys }).sorted()

    private fun checkMetrics() {
        val s = stats
        val metricsFile = Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("$name-tree.json")
        when {
            options.partial -> {}
            !options.metrics -> println("  metrics: disabled (-Dcsharppsi.treeGate.metrics=false)")
            s.files == 0L -> println("  metrics: no files, not checked")
            s.filesWithNodes == 0L ->
                println("  metrics: not checked, the parser builds no syntax nodes (placeholder); the first run of the wired parser creates $metricsFile")
            else -> CorpusMetrics.check(
                metricsFile,
                linkedMapOf(
                    "files" to s.files,
                    "nodes" to s.oracleNodes,
                    "mismatchedFiles" to s.mismatchedFiles,
                    "mismatches" to s.mismatches,
                    "spuriousErrorFiles" to s.spuriousErrorFiles,
                    "exceptions" to s.exceptions,
                    "stackOverflows" to s.stackOverflows,
                    "timeouts" to s.timeouts,
                    "coverageFailures" to s.coverageFailures,
                    "loggedErrors" to s.loggedErrors,
                    "filesWithOracleErrors" to s.filesWithOracleErrors,
                    "invalidMismatchedFiles" to s.invalidMismatchedFiles,
                    "invalidMismatches" to s.invalidMismatches,
                    "invalidErrorElements" to s.invalidErrorElements,
                    "invalidOracleDiagnostics" to s.invalidOracleDiagnostics,
                    "docComments" to s.docComments,
                    "docCommentMismatches" to s.docCommentMismatches,
                    "invalidDocCommentMismatches" to s.invalidDocCommentMismatches,
                ),
                informational = setOf("files", "nodes", "filesWithOracleErrors", "invalidErrorElements", "invalidOracleDiagnostics", "docComments"),
            )
        }
        if (options.strict) {
            val bad = s.mismatches + s.docCommentMismatches + s.spuriousErrorFiles + s.exceptions + s.stackOverflows + s.timeouts + s.coverageFailures + s.loggedErrors
            junit.framework.TestCase.assertEquals(
                "tree gate $name (strict): mismatches, spurious errors and failures in valid files must be 0", 0L, bad,
            )
        }
    }
}

/**
 * Base of the `*TreeCorpusTest` classes: one [TreeCorpusGate] over [corpusDir]. Light (`ParsingTestCase`) environment:
 * the PSI file is created by `PsiFileFactory` over a `LightVirtualFile`, which runs `CSharpParserDefinition` and the
 * file element type as the IDE does, without an editor or a project model.
 */
abstract class TreeCorpusTestBase : CSharpParsingTestCase("parser") {
    /** Metrics name: `testData/metrics/<gateName>-tree.json`, allowlist `testData/parser/<gateName>-tree-allowlist.txt`. */
    abstract val gateName: String
    abstract fun corpusDir(): Path

    /** `roslyndump --langversion` of the gate; the files are parsed at [languageVersion], which must be the same version. */
    protected open val langVersion: String? get() = null

    /** Relative paths under [corpusDir] the gate covers (empty: all). */
    protected open fun includes(): List<String> = emptyList()

    protected fun runGate() {
        TreeCorpusGate(gateName, corpusDir(), langVersion = langVersion, includes = includes()) { fileName, text ->
            createFile(fileName, text).node.also { it.firstChildNode }
        }.run()
    }
}
