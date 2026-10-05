package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.random.Random

/**
 * Mutation gate: invalid code the corpus gates never see, against the oracle. A deterministic sample of [FILES] files of
 * `.corpus/runtime/src/libraries` (seeded shuffle of the sorted paths, at most [MAX_BYTES] each), [MUTANTS_PER_FILE]
 * mutants each (seeded per file): delete 1-15 characters, insert a token ([INSERTIONS]) or truncate, cycling in
 * that order. The mutants are written to `build/mutation-gate/mutants` (`<n>_<k>_<kind>_<file>.cs`, listed with their
 * source and change in `build/mutation-gate/mutants.txt`) and diffed by one [TreeCorpusGate] run, one `roslyndump`
 * process over the directory, with the gate's rules: mutants Roslyn parses without diagnostics must match exactly,
 * the others with the invalid-input normalisation (docs/csharp-psi/TESTING.md, "Mutation gate"). Both sides parse with no `#if`
 * symbols. Metrics `testData/metrics/runtime-mutants.json`: `mismatchedMutants` (mutants with any mismatch or failure,
 * only improves) and the failure counts; `mutants` is informational. The gate's summary lists the mismatch classes
 * and examples, `build/tree-gate/runtime-mutants-mismatches.txt` every mutant with mismatches.
 */
class MutationOracleCorpusTest : CSharpParsingTestCase("parser") {

    fun testRuntimeMutants() {
        val root = CSharpTestUtil.corpusRoot().resolve("runtime/src/libraries")
        if (!Files.isDirectory(root)) {
            println("MutationOracleCorpusTest: $root is missing (tools/csharp-psi/fetch-corpus.sh), skipped")
            return
        }
        val candidates = Files.walk(root).use { s ->
            s.filter { it.isRegularFile() && it.extension == "cs" && Files.size(it) <= MAX_BYTES }
                .map { root.relativize(it).toString().replace('\\', '/') }.sorted().toList()
        }
        assertTrue("expected > 1000 files under $root", candidates.size > 1000)
        val sample = candidates.shuffled(Random(SEED)).take(FILES).sorted()
        val out = writeMutants(root, sample)

        val gate = TreeCorpusGate(NAME, out, TreeCorpusGate.Options(metrics = false)) { fileName, text ->
            createFile(fileName, text).node.also { it.firstChildNode }
        }
        gate.run()
        val s = gate.stats
        println("MutationOracleCorpusTest: mutants=${s.files} mismatchedMutants=${s.filesWithProblems} " +
            "(valid ${s.mismatchedFiles + s.spuriousErrorFiles}, invalid ${s.invalidMismatchedFiles}) from ${sample.size} files")
        assertEquals("every mutant reached the oracle and the parser", (sample.size * MUTANTS_PER_FILE).toLong(), s.files)
        CorpusMetrics.check(
            Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("$NAME.json"),
            linkedMapOf(
                "mutants" to s.files,
                "mismatchedMutants" to s.filesWithProblems,
                "exceptions" to s.exceptions,
                "stackOverflows" to s.stackOverflows,
                "timeouts" to s.timeouts,
                "coverageFailures" to s.coverageFailures,
                "loggedErrors" to s.loggedErrors,
            ),
            informational = setOf("mutants"),
        )
    }

    /** Writes the mutants of [sample] (paths under [root]) into a fresh directory; returns it. */
    private fun writeMutants(root: Path, sample: List<String>): Path {
        val base = CSharpTestUtil.buildDir("mutation-gate")
        val out = base.resolve("mutants")
        if (Files.exists(out)) Files.walk(out).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        Files.createDirectories(out)
        val list = StringBuilder()
        for ((n, path) in sample.withIndex()) {
            val text = CSharpLexerDiffCorpusTest.readSource(root.resolve(path)).replace("\r\n", "\n").replace('\r', '\n')
            val random = Random(SEED * 31 + n)
            for (k in 0 until MUTANTS_PER_FILE) {
                val kind = Kind.entries[k % Kind.entries.size]
                val (mutant, change) = mutate(text, kind, random)
                val name = "%04d_%d_%s_%s".format(n, k, kind.name.lowercase(), path.substringAfterLast('/'))
                Files.writeString(out.resolve(name), mutant)
                list.append(name).append('\t').append(path).append('\t').append(change).append('\n')
            }
        }
        Files.writeString(base.resolve("mutants.txt"), list)
        return out
    }

    enum class Kind { DELETE, INSERT, TRUNCATE }

    /** The mutant and a description of the change (`delete@offset+length`, `insert@offset 'token'`, `truncate@offset`). */
    private fun mutate(text: String, kind: Kind, random: Random): Pair<String, String> {
        val at = random.nextInt(text.length + 1)
        return when (kind) {
            Kind.DELETE -> {
                val length = minOf(1 + random.nextInt(15), text.length - at)
                text.removeRange(at, at + length) to "delete@$at+$length"
            }
            Kind.INSERT -> {
                val token = INSERTIONS[random.nextInt(INSERTIONS.size)]
                text.substring(0, at) + token + text.substring(at) to "insert@$at '${token.trim()}'"
            }
            Kind.TRUNCATE -> text.substring(0, at) to "truncate@$at"
        }
    }

    private companion object {
        const val NAME = "runtime-mutants"
        const val SEED = 20261005L
        const val FILES = 6000
        const val MUTANTS_PER_FILE = 6
        const val MAX_BYTES = 60_000L

        /** Inserted with a space on either side or not at all: tokens, keywords, unterminated literals and comments. */
        val INSERTIONS = listOf(
            "(", ")", "{", "}", "[", "]", ";", ",", ".", "=", "=>", "<", ">", "?", ":", "?.", "!", "&", "*", "\"", "'",
            "\$\"", "@", "/*", "//", "class ", " class ", " struct ", " interface ", " namespace ", " using ", " public ",
            " static ", " async ", " await ", " new ", " var ", " if ", " else ", " for ", " foreach ", " while ",
            " switch ", " case ", " return ", " yield ", " throw ", " try ", " catch ", " in ", " is ", " as ", " out ",
            " ref ", " where ", " select ", " from ", " get ", " set ", " operator ", " delegate ", " record ", " not ",
            " and ", " or ", " with ", " => ", " int ", " void ", " this ", " base ", "0x", "1e", "'a", "\"x",
        )
    }
}
