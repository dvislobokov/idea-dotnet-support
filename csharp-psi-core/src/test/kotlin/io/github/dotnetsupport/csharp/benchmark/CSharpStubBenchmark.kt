package io.github.dotnetsupport.csharp.benchmark

import com.intellij.psi.PsiFileFactory
import com.intellij.psi.stubs.SerializationManagerEx
import com.intellij.psi.stubs.StubElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.CSharpLanguage
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubDefinition
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.random.Random

/**
 * Stub building (CSHARP_PSI_MIGRATION.md, step 8: "замер индексации dotnet/runtime"), `benchmark` task, report only. What the stub index costs per
 * file is a parse of the whole file (bodies are parsed eagerly, docs/csharp-psi/GRAMMAR.md "Stubs") plus the walk over the declarations and the
 * serialization; measured on files of `runtime/src/libraries` (seeded shuffle, at most 100 KB each):
 *  - `stubs.parse`: `createFileFromText(...).node` (the parse alone);
 *  - `stubs.build`: the same plus `CSharpStubBuilder.buildStubTree`;
 *  - `stubs.serialize`: the same plus `SerializationManagerEx.serialize` (what the indexer stores);
 *  - `stubs.runtimeLarge`: one pass of `stubs.serialize` over [LARGE_FILES] files, stubs per MB and bytes per MB of source.
 */
class CSharpStubBenchmark : BasePlatformTestCase() {
    fun testStubBuilding() {
        val libraries = CSharpTestUtil.corpusRoot().resolve("runtime/src/libraries")
        if (!Files.isDirectory(libraries)) {
            println("CSharpStubBenchmark: $libraries is missing, skipped")
            return
        }
        val candidates = Files.walk(libraries).use { s ->
            s.filter { it.isRegularFile() && it.extension == "cs" && Files.size(it) <= 100_000 }.map { libraries.relativize(it).toString().replace('\\', '/') }.sorted().toList()
        }.shuffled(Random(SEED))
        fun load(paths: List<String>) = paths.map { it.substringAfterLast('/') to CSharpLexerDiffCorpusTest.readSource(libraries.resolve(it)) }
        val sample = load(candidates.take(SAMPLE_FILES))
        val mb = sample.sumOf { it.second.length } / 1_000_000.0
        println("  sample: ${sample.size} files, ${BenchmarkSupport.format(mb)} MB")
        val builder = CSharpStubDefinition().builder
        val factory = PsiFileFactory.getInstance(project)
        measure("CSharpStubBenchmark.stubs.parse", mb) {
            for ((name, text) in sample) factory.createFileFromText(name, CSharpLanguage, text).node
        }
        measure("CSharpStubBenchmark.stubs.build", mb) {
            for ((name, text) in sample) builder.buildStubTree(factory.createFileFromText(name, CSharpLanguage, text))
        }
        measure("CSharpStubBenchmark.stubs.serialize", mb) {
            for ((name, text) in sample) serialize(builder.buildStubTree(factory.createFileFromText(name, CSharpLanguage, text)))
        }

        val large = load(candidates.take(LARGE_FILES))
        val largeMb = large.sumOf { it.second.length } / 1_000_000.0
        var stubs = 0L
        var bytes = 0L
        val ms = BenchmarkSupport.timed {
            for ((name, text) in large) {
                val root = builder.buildStubTree(factory.createFileFromText(name, CSharpLanguage, text))
                stubs += count(root)
                bytes += serialize(root)
            }
        }
        BenchmarkSupport.report(
            "CSharpStubBenchmark.stubs.runtimeLarge",
            "${large.size} files, ${BenchmarkSupport.format(largeMb)} MB: ${BenchmarkSupport.format(ms)} ms, ${BenchmarkSupport.format(ms / largeMb)} ms/MB, " +
                "${BenchmarkSupport.format(stubs / largeMb)} stubs/MB, ${BenchmarkSupport.format(bytes / largeMb / 1000)} KB of stubs per MB",
        )
    }

    /** The median of [BenchmarkSupport.ITERATIONS] runs after [BenchmarkSupport.WARMUPS], reported (no thresholds yet). */
    private fun measure(name: String, mb: Double, body: () -> Unit) {
        val times = (0 until BenchmarkSupport.WARMUPS + BenchmarkSupport.ITERATIONS).map { BenchmarkSupport.timed(body) }.drop(BenchmarkSupport.WARMUPS)
        val median = BenchmarkSupport.median(times)
        BenchmarkSupport.report(name, "median=${BenchmarkSupport.format(median)} ms/MB=${BenchmarkSupport.format(median / mb)}")
    }

    private fun serialize(root: StubElement<*>): Int = ByteArrayOutputStream().also { SerializationManagerEx.getInstanceEx().serialize(root, it) }.size()

    private fun count(stub: StubElement<*>): Int = 1 + stub.childrenStubs.sumOf { count(it) }

    private companion object {
        const val SEED = 20261004L
        const val SAMPLE_FILES = 150
        const val LARGE_FILES = 3000
    }
}
