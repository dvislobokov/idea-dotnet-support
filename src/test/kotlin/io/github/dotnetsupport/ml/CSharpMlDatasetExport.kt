package io.github.dotnetsupport.ml

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.completionml.core.ngram.NgramModel
import io.github.completionml.core.rank.ExampleShards
import io.github.completionml.core.rank.FeatureExtractor
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File

/**
 * Offline dataset export for the ML ranker (ML_RANKER_EXPORT_TASK.md; `../idea-ml-completion/docs/ADAPTER.md` §3): runs the plugin's real
 * completion headlessly over C# repositories and writes one example shard per repository. Not a test of behaviour — the `testIde` task
 * `mlDataset` runs it, the regular `test` task excludes `*MlDatasetExport`. The per-file work is [CSharpMlExporter].
 *
 * System properties (set by Gradle from `-Pml.*`):
 *  - `ml.repos`    file with repository directory names, one per line (`#` comments), e.g. `~/work/ml-data/csharp/sets/rank.txt`
 *  - `ml.data`     corpus root containing `repos/<name>/` (default `../ml-data/csharp`)
 *  - `ml.lm`       the n-gram model whose vocabulary and probabilities feed the common features (`ml-models/csharp/e15-a.cml`: trained on the lm fold, not on these repositories)
 *  - `ml.out`      output directory for `<repo>.cmlx` (default `<data>/shards`); a repository whose shard exists is skipped (resumable)
 *  - `ml.perFile`  sampled completion positions per file (10), `ml.maxFiles` per repository (120, 0 = all), `ml.cache` λ of the file cache (0.3),
 *    `ml.maxCopy` at most this many `.cs` files copied into the content root (0 = all; the sampled sources always are),
 *    `ml.names` write candidate names into the shards (false), `ml.seed` (7)
 *
 * Per position: the identifier token is cut to a 0–2 character prefix, the caret is put there, `completeBasic()` runs, the answer is the
 * identifier that was in the source. Lists without the answer are counted (the plugin's recall) and skipped. One broken repository is
 * logged and skipped (with `intellij.testFramework.rethrow.logged.errors=false` set by the task).
 */
class CSharpMlDatasetExport : BasePlatformTestCase() {
    private val data = File(System.getProperty("ml.data") ?: "../ml-data/csharp")
    private val reposFile = System.getProperty("ml.repos")
    private val lmFile = System.getProperty("ml.lm")
    private val out = File(System.getProperty("ml.out") ?: File(data, "shards").path)
    private val options = CSharpMlExporter.Options(
        perFile = System.getProperty("ml.perFile")?.toInt() ?: 10,
        maxFiles = System.getProperty("ml.maxFiles")?.toInt() ?: 120,
        cacheLambda = System.getProperty("ml.cache")?.toDouble() ?: 0.3,
        seed = System.getProperty("ml.seed")?.toLong() ?: 7L,
        maxCopy = System.getProperty("ml.maxCopy")?.toInt() ?: 0,
    )
    private val withNames = System.getProperty("ml.names")?.toBoolean() ?: false
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false   // a single candidate must stay a list, not an insertion
        // Every document change in the fixture editor otherwise schedules the daemon and the plugin's CodeVision pass over the whole
        // file: on the server 34 CodeVision threads burnt thousands of CPU seconds per repository while the export itself needed minutes.
        runCatching { com.intellij.codeInsight.codeVision.settings.CodeVisionSettings.getInstance().codeVisionEnabled = false }
            .onFailure { println("ml: cannot disable code vision: $it") }
        runCatching { com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).disableUpdateByTimer(testRootDisposable) }
            .onFailure { println("ml: cannot disable the daemon timer: $it") }
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testExport() {
        requireNotNull(reposFile) { "-Pml.repos=<file with repository names> is required" }
        requireNotNull(lmFile) { "-Pml.lm=<lm.cml> is required (train it on repositories disjoint from ml.repos)" }
        val lm = NgramModel.read(File(lmFile))
        val extractor = FeatureExtractor(CSharpMlFeatures.schema, lm.vocab, lm, options.cacheLambda)
        val exporter = CSharpMlExporter(myFixture, project, myFixture.module, testRootDisposable, lm.vocab, extractor, options)
        out.mkdirs()
        val repos = File(reposFile).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val total = CSharpMlExporter.Stats()
        var done = 0; var failed = 0
        val t0 = System.currentTimeMillis()
        for (repo in repos) {
            val dir = File(data, "repos/$repo")
            if (!dir.isDirectory) { println("ml: skip $repo (no directory)"); continue }
            val shard = File(out, "$repo${ExampleShards.EXTENSION}")
            if (shard.exists()) { println("ml: skip $repo (shard exists)"); continue }
            val stats = CSharpMlExporter.Stats()
            val partial = File(out, "$repo${ExampleShards.EXTENSION}.part")
            try {
                ExampleShards.Writer(partial, CSharpMlLanguage.id, "idea-dotnet-support CSharpMlDatasetExport lm=${File(lmFile).name}", CSharpMlFeatures.schema, withNames).use { writer ->
                    exporter.exportRepository(dir, writer, stats)
                }
                if (!partial.renameTo(shard)) error("cannot rename $partial to $shard")
                println("ml: $repo ${stats.summary()}")
                total.add(stats)
                done++
            } catch (e: Throwable) {
                // one broken project must not fail the export of hundreds: log, drop the partial shard, continue with the next repository
                failed++
                partial.delete()
                println("ml: FAILED $repo after ${stats.summary()}: $e")
                e.printStackTrace(System.out)
            }
        }
        println("ml: TOTAL repos=$done failed=$failed ${total.summary()} in ${(System.currentTimeMillis() - t0) / 1000} s; shards in $out")
    }
}
