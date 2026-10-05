package io.github.dotnetsupport.csharp.lang.parser

import io.github.dotnetsupport.csharp.CSharpParsingTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.CorpusMetrics
import io.github.dotnetsupport.csharp.lang.oracle.DiffReport
import io.github.dotnetsupport.csharp.lang.oracle.DumpFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Corpus gate of the declarations (step 5): the `*.member` snippets of `.corpus/parsing-tests` against
 * `roslyndump member` (`SyntaxFactory.ParseMemberDeclaration`) and the `*.cs` ones against `roslyndump tree` through the
 * file parser of `CSharpParserDefinition` (the `CompilationUnit` node included). The comparison is [SliceGate]'s. Buckets,
 * each file in the first that takes it:
 *  - script or explicit language version ([ParsingTestsIndex.isScriptOrOldVersion]): each diffed at the test's own
 *    version by [ParsingTestsVersionGate] (metrics `oldLangVersion*`, `script*`);
 *  - the rest: valid (no Roslyn diagnostic) / invalid, at `Preview`.
 * Inputs with directives are in the normal buckets since the lexer evaluates `#if` (step 4; the separate
 * `preprocessor` bucket of the first version had 0 mismatches after the merge).
 * Metrics: `testData/metrics/parsing-tests-members.json`, `parsing-tests-files.json`; reports in `build/slice-gate/`.
 */
class ParsingTestsDeclarationsCorpusTest : CSharpParsingTestCase("parser/decl") {

    fun testMembers() = runGate(SliceParseHarness.Mode.Member, "parsing-tests-members")

    fun testFiles() = runGate(SliceParseHarness.Mode.File, "parsing-tests-files")

    private fun runGate(mode: SliceParseHarness.Mode, name: String) {
        val root = CSharpTestUtil.corpusRoot().resolve("parsing-tests")
        if (!Files.isDirectory(root)) {
            println("ParsingTestsDeclarationsCorpusTest: $root is missing, skipped")
            return
        }
        val special = ParsingTestsIndex.special(root)

        val gate = SliceGate(name)
        val versionGate = ParsingTestsVersionGate(name)
        val t0 = System.currentTimeMillis()
        gate.run(
            root, mode,
            filters = listOf<(DumpFile) -> Boolean>({ ParsingTestsIndex.normalize(it.path) !in special }),
        )
        versionGate.run(root, mode, special)
        val reportDir = CSharpTestUtil.buildDir("slice-gate")
        val reports = listOf(gate, versionGate.versioned, versionGate.script).map { it.writeReport(reportDir) }
        println(gate.summary() + " millis=${System.currentTimeMillis() - t0}")
        println(versionGate.versioned.summary())
        println(versionGate.script.summary())
        println("  reports: ${reports.joinToString()}")
        DiffReport.report(name, gate.diff, facts = mapOf("summary" to gate.summary()))
        DiffReport.report("$name-old-version", versionGate.versioned.diff, facts = mapOf("summary" to versionGate.versioned.summary()))
        for (g in listOf(gate, versionGate.versioned, versionGate.script)) {
            g.exceptions.take(20).forEach { println("  EXCEPTION (${g.name}) $it") }
        }

        val s = gate.stats
        val metrics = linkedMapOf<String, Long>(
            "validFiles" to s.validFiles,
            "validMismatchedFiles" to s.validMismatchedFiles,
            "validMismatches" to s.validMismatches,
            "validSpuriousErrorFiles" to s.validSpuriousErrorFiles,
            "validExceptions" to s.validExceptions,
            "invalidFiles" to s.invalidFiles,
            "invalidExceptions" to s.invalidExceptions,
            "invalidMismatchedFiles" to s.invalidMismatchedFiles,
            "invalidMismatches" to s.invalidMismatches,
            "invalidErrorElements" to s.invalidErrorElements,
            "invalidOracleDiagnostics" to s.invalidOracleDiagnostics,
            "invalidOracleSkipped" to s.invalidOracleSkipped,
        )
        versionGate.metrics(metrics)
        CorpusMetrics.check(
            Paths.get(CSharpTestUtil.testDataPath("metrics")).resolve("$name.json"),
            metrics,
            informational = setOf(
                "validFiles", "invalidFiles",
                "invalidErrorElements", "invalidOracleDiagnostics", "invalidOracleSkipped",
            ) + ParsingTestsVersionGate.informational,
        )
    }
}

/** `index.tsv` of `.corpus/parsing-tests`: which test options each snippet was written for. */
object ParsingTestsIndex {
    /** path -> "options|classDefault". */
    fun read(root: Path): Map<String, String> {
        val lines = Files.readAllLines(root.resolve("index.tsv"))
        val header = lines.first().split('\t')
        val iOptions = header.indexOf("options")
        val iDefault = header.indexOf("classDefault")
        val iPath = header.indexOf("path")
        return lines.drop(1).filter { it.isNotBlank() }.associate { line ->
            val f = line.split('\t')
            normalize(f.getOrElse(iPath) { "" }) to (f.getOrElse(iOptions) { "" } + "|" + f.getOrElse(iDefault) { "" })
        }
    }

    fun normalize(path: String) = path.replace('\\', '/')

    /** The inputs of the version gate: path → options, for every path [isScriptOrOldVersion] takes. */
    fun special(root: Path): Map<String, SnippetOptions> {
        val lines = Files.readAllLines(root.resolve("index.tsv"))
        val header = lines.first().split('\t')
        val iOptions = header.indexOf("options")
        val iDefault = header.indexOf("classDefault")
        val iPath = header.indexOf("path")
        val result = LinkedHashMap<String, SnippetOptions>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val f = line.split('\t')
            val options = f.getOrElse(iOptions) { "" }
            val classDefault = f.getOrElse(iDefault) { "" }
            if (isScriptOrOldVersion("$options|$classDefault")) {
                result[normalize(f.getOrElse(iPath) { "" })] = ParsingTestsOptions.resolve(options, classDefault)
            }
        }
        return result
    }

    fun isScriptOrOldVersion(options: String): Boolean {
        if (options.contains("Script")) return true
        if (options.contains("LanguageVersion")) return true
        if (Regex("Regular\\d").containsMatchIn(options)) return true
        if (options.contains("CSharp\\d".toRegex())) return true
        if (options.contains("WithoutRecursivePatterns") || options.contains("WithoutPatternCombinators")) return true
        return false
    }
}
