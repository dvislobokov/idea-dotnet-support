package io.github.dotnetsupport.csharp.lang.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ReadAction
import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import io.github.dotnetsupport.csharp.lang.oracle.Mismatch
import io.github.dotnetsupport.csharp.lang.oracle.RoslynDump
import io.github.dotnetsupport.csharp.lang.parser.ParseGuard
import io.github.dotnetsupport.csharp.lang.parser.SliceParseHarness
import io.github.dotnetsupport.csharp.lang.parser.TreeCorpusGate
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The PSI accessor gate (step 3 of docs/csharp-psi/PLAN.md): every `*.cs` file of a corpus, parsed through the platform's public
 * path as the tree gates do, checked by [PsiAccessorCheck] against `roslyndump tree --fields` (streamed, never written
 * to disk). Run options are the tree gates' (`-Dcsharppsi.treeGate.limit|filter|dir|threads|timeoutMillis`,
 * [TreeCorpusGate.Options]). Metrics: `testData/metrics/<name>-psi.json` (only improve); `accessorMismatches`,
 * `nodesWithoutPsiClass`, `elementsWithWrongClass`, `alignmentMismatches` and failures must also be 0 on any run.
 * Mismatches: `build/psi-gate/<name>-mismatches.txt`. Doc comments are checked with their structure
 * ([PsiAccessorCheck]); other structured trivia are not.
 */
class PsiAccessorCorpusGate(
    val name: String,
    val root: Path,
    private val options: TreeCorpusGate.Options = TreeCorpusGate.Options.fromSystemProperties(),
    private val parse: (fileName: String, text: String) -> ASTNode,
) {
    private val total = PsiAccessorCheck()
    private var failures = 0L
    private var bytes = 0L
    private var oracleNodes = 0L
    private var checkMillis = 0L
    private val failureSamples = ArrayList<String>()
    private val classCounts = HashMap<String, Long>()
    private val examples = LinkedHashMap<String, MutableList<Mismatch>>()
    private val lock = Any()

    fun run() {
        val target = options.dir?.let { root.resolve(it) } ?: root
        if (!Files.isDirectory(target)) {
            println("$name PSI accessor gate: $target is missing (tools/csharp-psi/fetch-roslyn.sh, tools/csharp-psi/fetch-corpus.sh, -Dcsharppsi.playground), skipped")
            return
        }
        val mismatchFile = CSharpTestUtil.buildDir("psi-gate").resolve("$name-mismatches.txt")
        val t0 = System.currentTimeMillis()
        var summary = ""
        Files.newBufferedWriter(mismatchFile, StandardCharsets.UTF_8).use { out ->
            ParseGuard.interceptLoggedErrors {
                summary = stream(target) { dump -> compare(target, dump, out) }
            }
        }
        val millis = System.currentTimeMillis() - t0
        val t = total
        val rows = linkedMapOf<String, Any>(
            "root" to target,
            "oracle" to summary,
            "files" to "${t.files}, ${String.format(java.util.Locale.ROOT, "%.1f", bytes / 1e6)} MB, Roslyn nodes+tokens $oracleNodes",
            "checked" to "nodes ${t.nodes}, accessor checks ${t.accessorChecks}, doc comments ${t.docComments}",
            "mismatches" to "accessors ${t.accessorMismatches}, nodes without a PSI class ${t.nodesWithoutPsiClass}, " +
                "elements of a wrong class ${t.elementsWithWrongClass}, alignment ${t.alignmentMismatches}",
            "failures" to failures,
            "time" to "$millis ms wall, parse+check $checkMillis ms on ${options.threads} threads",
            "mismatch file" to mismatchFile,
        )
        if (options.partial) rows["partial run"] = "limit=${options.limit} filter=${options.filter} dir=${options.dir}: metrics not checked"
        val width = rows.keys.maxOf { it.length } + 1
        println("PsiAccessorCorpusGate[$name] summary")
        rows.forEach { (k, v) -> println("  ${"$k:".padEnd(width)} $v") }
        if (classCounts.isNotEmpty()) {
            println("  mismatch classes (top 30 of ${classCounts.size}):")
            classCounts.entries.sortedByDescending { it.value }.take(30).forEach { (cls, n) -> println("    %9d  %s".format(n, cls)) }
            examples.values.flatten().take(30).forEach { println("    $it") }
        }
        failureSamples.forEach { println("  FAILURE $it") }
        if (!options.partial && options.metrics && t.files > 0) {
            CorpusMetrics.check(
                Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("$name-psi.json"),
                linkedMapOf(
                    "files" to t.files,
                    "nodes" to t.nodes,
                    "accessorChecks" to t.accessorChecks,
                    "docComments" to t.docComments,
                    "accessorMismatches" to t.accessorMismatches,
                    "nodesWithoutPsiClass" to t.nodesWithoutPsiClass,
                    "elementsWithWrongClass" to t.elementsWithWrongClass,
                    "alignmentMismatches" to t.alignmentMismatches,
                    "failures" to failures,
                ),
                informational = setOf("files", "nodes", "accessorChecks", "docComments"),
            )
        }
        val bad = t.accessorMismatches + t.nodesWithoutPsiClass + t.elementsWithWrongClass + t.alignmentMismatches + failures
        junit.framework.TestCase.assertEquals("PSI accessor gate $name: mismatches and failures must be 0 (see $mismatchFile)", 0L, bad)
    }

    private class StopStreaming : RuntimeException(null, null, false, false)

    /** `roslyndump tree --fields <target>` from stdout, files handed to [options].threads workers; returns the summary. */
    private fun stream(target: Path, action: (DumpFile) -> Unit): String {
        val stderr = Files.createTempFile("roslyndump-psi", ".err")
        val process = ProcessBuilder(RoslynDump.options("tree", target, fields = true))
            .directory(CSharpTestUtil.repoRoot().toFile())
            .redirectError(stderr.toFile())
            .start()
        val executor = Executors.newFixedThreadPool(options.threads) { r -> Thread(r, "psi-gate-$name").apply { isDaemon = true } }
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
                    if (e !is StopStreaming && e.cause !is StopStreaming) throw e
                    process.destroy()
                }
            }
            executor.shutdown()
            check(executor.awaitTermination(6, TimeUnit.HOURS)) { "PSI gate $name did not finish" }
            firstError?.let { throw IllegalStateException("PSI gate $name: internal error", it) }
            val code = process.waitFor()
            val err = Files.readString(stderr)
            check(code == 0 || options.limit != null) { "roslyndump tree --fields $target failed ($code):\n$err" }
            return err.lines().lastOrNull { it.startsWith("# files=") } ?: "(no summary: stopped after ${options.limit} files)"
        } finally {
            executor.shutdownNow()
            process.destroyForcibly()
            Files.deleteIfExists(stderr)
        }
    }

    private fun compare(target: Path, dump: DumpFile, out: java.io.BufferedWriter) {
        val text = CSharpLexerDiffCorpusTest.readSource(target.resolve(dump.path))
        val fileName = dump.path.substringAfterLast('/')
        val check = PsiAccessorCheck()
        val started = System.nanoTime()
        val outcome = ParseGuard.run(options.timeoutMillis) {
            val node = parse(fileName, text)
            ReadAction.compute<Unit, RuntimeException> { check.check(dump.path, dump.roots, node, dump.trivia) }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        synchronized(lock) {
            bytes += text.length
            oracleNodes += dump.nodeCount()
            checkMillis += millis
            total.files += check.files
            total.nodes += check.nodes
            total.accessorChecks += check.accessorChecks
            total.accessorMismatches += check.accessorMismatches
            total.nodesWithoutPsiClass += check.nodesWithoutPsiClass
            total.elementsWithWrongClass += check.elementsWithWrongClass
            total.alignmentMismatches += check.alignmentMismatches
            total.docComments += check.docComments
            if (!outcome.ok || outcome.loggedErrors.isNotEmpty()) {
                failures++
                val line = "${dump.path}: ${outcome.describe()} ${outcome.loggedErrors.joinToString("; ")}"
                if (failureSamples.size < 20) failureSamples += line
                out.append("FAILURE ").append(line).append('\n')
                outcome.failure?.stackTrace?.take(12)?.forEach { out.append("    at ").append(it.toString()).append('\n') }
            }
            for (m in check.mismatches) {
                classCounts.merge(m.cls, 1L, Long::plus)
                val list = examples.getOrPut(m.cls) { ArrayList(3) }
                if (list.size < 3) list += m
            }
            check.mismatches.take(30).forEach { out.append(it.toString()).append('\n') }
        }
    }
}

/** Base of the `*PsiAccessorCorpusTest` classes: one [PsiAccessorCorpusGate] over [corpusDir], files as the tree gates parse them. */
abstract class PsiAccessorCorpusTestBase : CSharpParsingTestCase("parser") {
    /** Metrics name: `testData/metrics/<gateName>-psi.json`. */
    abstract val gateName: String
    abstract fun corpusDir(): Path

    protected fun runGate() {
        PsiAccessorCorpusGate(gateName, corpusDir()) { fileName, text -> createFile(fileName, text).node.also { it.firstChildNode } }.run()
    }
}

/** Roslyn's own sources (`.corpus/roslyn/src`). */
class RoslynSrcPsiAccessorCorpusTest : PsiAccessorCorpusTestBase() {
    override val gateName = "roslyn-src"
    override fun corpusDir(): Path = CSharpTestUtil.corpusRoot().resolve("roslyn/src")
    fun testAccessors() = runGate()
}

/** dotnet/runtime at `runtimeTag` (`.corpus/runtime/src`). */
class RuntimePsiAccessorCorpusTest : PsiAccessorCorpusTestBase() {
    override val gateName = "runtime"
    override fun corpusDir(): Path = CSharpTestUtil.corpusRoot().resolve("runtime/src")
    fun testAccessors() = runGate()
}

/** dotnet/aspnetcore at `aspnetcoreTag` (`.corpus/aspnetcore/src`). */
class AspnetcorePsiAccessorCorpusTest : PsiAccessorCorpusTestBase() {
    override val gateName = "aspnetcore"
    override fun corpusDir(): Path = CSharpTestUtil.corpusRoot().resolve("aspnetcore/src")
    fun testAccessors() = runGate()
}

/**
 * The PSI accessor gate over a directory of snippets (`roslyndump extract-tests`): every snippet in its mode through
 * [SliceParseHarness], both sides at `Preview` (the accessors are checked on whatever tree both parsers build; the
 * version buckets of the tree gates are not needed here), doc comments included. Accessor mismatches, classes and
 * exceptions must be 0; `alignmentMismatches` only improves. Metrics `<name>-psi.json`.
 */
abstract class SnippetPsiAccessorCorpusTestBase : CSharpParsingTestCase("parser") {
    protected fun runSnippets(name: String, dir: String, runs: List<Pair<String, SliceParseHarness.Mode>>, minDocComments: Long = 0) {
        val root = CSharpTestUtil.corpusRoot().resolve(dir)
        if (!Files.isDirectory(root)) {
            println("$name PSI accessor gate: $root is missing (roslyndump extract-tests), skipped")
            return
        }
        val check = PsiAccessorCheck()
        val exceptions = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        for ((dumpMode, mode) in runs) {
            val run = RoslynDump.run(dumpMode, root, fields = true)
            println("  roslyndump $dumpMode --fields: ${run.summary}")
            for (dump in run.files) {
                val text = CSharpLexerDiffCorpusTest.readSource(root.resolve(dump.path))
                try {
                    val result = SliceParseHarness.parse(text, mode)
                    if (!result.stackOverflow) check.check(dump.path, dump.roots, result.root, dump.trivia)
                } catch (e: Throwable) {
                    exceptions += "${dump.path}: $e"
                }
            }
        }
        println(
            "PsiAccessorCorpusGate[$name] files=${check.files} nodes=${check.nodes} accessorChecks=${check.accessorChecks} " +
                "docComments=${check.docComments} accessorMismatches=${check.accessorMismatches} " +
                "nodesWithoutPsiClass=${check.nodesWithoutPsiClass} elementsWithWrongClass=${check.elementsWithWrongClass} " +
                "alignmentMismatches=${check.alignmentMismatches} exceptions=${exceptions.size} millis=${System.currentTimeMillis() - t0}",
        )
        check.mismatches.take(30).forEach { println("    $it") }
        exceptions.take(10).forEach { println("  EXCEPTION $it") }
        CorpusMetrics.check(
            Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("$name-psi.json"),
            linkedMapOf(
                "files" to check.files,
                "nodes" to check.nodes,
                "accessorChecks" to check.accessorChecks,
                "docComments" to check.docComments,
                "accessorMismatches" to check.accessorMismatches,
                "nodesWithoutPsiClass" to check.nodesWithoutPsiClass,
                "elementsWithWrongClass" to check.elementsWithWrongClass,
                "alignmentMismatches" to check.alignmentMismatches,
                "exceptions" to exceptions.size.toLong(),
            ),
            informational = setOf("files", "nodes", "accessorChecks", "docComments"),
        )
        assertTrue("doc comments checked: ${check.docComments}", check.docComments >= minDocComments)
        assertEquals("accessor mismatches, classes, exceptions", 0L,
            check.accessorMismatches + check.nodesWithoutPsiClass + check.elementsWithWrongClass + exceptions.size)
    }
}

/**
 * Roslyn's parsing tests (`.corpus/parsing-tests`), mostly invalid code, every snippet in its mode (`expr`, `stmt`,
 * `member`, `cs`). `alignmentMismatches`: 1, the depth guard's `ParseBigExpression.cs` (docs/csharp-psi/GRAMMAR.md, "Known
 * differences in the gates"). Metrics `parsing-tests-psi.json`.
 */
class ParsingTestsPsiAccessorCorpusTest : SnippetPsiAccessorCorpusTestBase() {
    fun testAccessors() =
        runSnippets("parsing-tests", "parsing-tests", SliceParseHarness.Mode.entries.map { it.dumpMode to it })
}

/**
 * Roslyn's cref and name attribute tests (`.corpus/parsing-tests-doc`, the `*.doc` snippets of
 * `ParsingTestsDocCommentsCorpusTest`), `roslyndump doc --fields` against the file parser: the accessors of the XML and
 * cref nodes of doc comments. Metrics `parsing-tests-doc-psi.json`.
 */
class ParsingTestsDocPsiAccessorCorpusTest : SnippetPsiAccessorCorpusTestBase() {
    fun testAccessors() =
        runSnippets("parsing-tests-doc", "parsing-tests-doc", listOf("doc" to SliceParseHarness.Mode.File), minDocComments = 100)
}

/** The playground of the plugin (`-Dcsharppsi.playground=<dir>` or `debug-playground` of the repository); skipped when missing. */
class PlaygroundPsiAccessorCorpusTest : PsiAccessorCorpusTestBase() {
    override val gateName = "playground"
    override fun corpusDir(): Path =
        System.getProperty("csharppsi.playground")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: CSharpTestUtil.repoRoot().resolve("debug-playground")
    fun testAccessors() = runGate()
}
