package io.github.dotnetsupport.csharp.benchmark

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilderFactory
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import io.github.dotnetsupport.csharp.lang.CSharpLanguage
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexer
import io.github.dotnetsupport.csharp.lang.lexer.CSharpLexerDiffCorpusTest
import io.github.dotnetsupport.csharp.lang.parser.CSharpBodyBlockType
import io.github.dotnetsupport.csharp.lang.parser.ParserGateSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.random.Random

/**
 * Parser benchmarks (step 6), `benchmark` task; harness and thresholds: [BenchmarkSupport]. Two fixed samples of the
 * corpus (skipped when `.corpus` is missing):
 *  - `roslynParser`: every `.cs` file of Roslyn's `Compilers/CSharp/Portable/Parser` (LanguageParser.cs and friends);
 *  - `runtimeSample`: [RUNTIME_FILES] files of at most 100 KB of `runtime/src/libraries`, a seeded shuffle of the
 *    sorted paths.
 * Measured:
 *  - `lexer.<sample>`: ms/MB of [CSharpLexer] over the sample;
 *  - `parse.<sample>`: ms/MB of `PsiFileFactory.createFileFromText(...).node` plus a walk of the whole AST (lazy
 *    elements included), the path the tree gates and the IDE take; `builderOnly.<sample>` (report only) is the same
 *    parse through `PsiBuilderFactory` + `ParserDefinition.createParser` without a `PsiFile`, to show the overhead of
 *    the file path;
 *  - `bodyReparse`: one character typed into an identifier inside a method body of LanguageParser.cs (an editor
 *    document, `PsiDocumentManager.commitDocument`, i.e. the platform's `BlockSupport` reparse and `DiffLog`), the
 *    median over the iterations, toggling insert and delete. The method body is a reparseable body
 *    ([CSharpBodyBlockType], docs/csharp-psi/GRAMMAR.md "Reparseable bodies"): the platform reparses that body alone and merges
 *    it by a tree diff; the report line says how many commits were body reparses and whether a leaf far from the edit
 *    kept its PSI identity.
 * Parser numbers of the placeholder parser (no syntax nodes, [ParserGateSupport.hasNodes]) are reported only: the first
 * run of the wired parser with `-Dcsharppsi.benchmark.update=true` stores the thresholds.
 */
class CSharpParserBenchmark : BasePlatformTestCase() {

    fun testLexer() {
        for ((sample, files) in samples()) {
            val mb = megabytes(files)
            BenchmarkSupport.run("CSharpParserBenchmark.lexer.$sample", "ms/MB", mb) {
                val lexer = CSharpLexer()
                for ((_, text) in files) {
                    lexer.start(text, 0, text.length, 0)
                    while (lexer.tokenType != null) lexer.advance()
                }
            }
        }
    }

    fun testParse() {
        val wired = parserBuildsNodes()
        for ((sample, files) in samples()) {
            val mb = megabytes(files)
            BenchmarkSupport.run("CSharpParserBenchmark.parse.$sample", "ms/MB", mb, recordable = wired) {
                for ((name, text) in files) walk(PsiFileFactory.getInstance(project).createFileFromText(name, CSharpLanguage, text).node)
            }
            val definition = CSharpParserDefinition()
            val times = (0 until BenchmarkSupport.WARMUPS + BenchmarkSupport.ITERATIONS).map {
                BenchmarkSupport.timed {
                    for ((_, text) in files) {
                        val builder = PsiBuilderFactory.getInstance().createBuilder(definition, CSharpLexer(), text)
                        definition.createParser(project).parse(CSharpParserDefinition.FILE, builder)
                    }
                }
            }.drop(BenchmarkSupport.WARMUPS)
            val median = BenchmarkSupport.median(times)
            BenchmarkSupport.report(
                "CSharpParserBenchmark.builderOnly.$sample",
                "median=${BenchmarkSupport.format(median)} ms/MB=${BenchmarkSupport.format(median / mb)} (top level, no PsiFile)",
            )
        }
    }

    fun testBodyReparse() {
        val file = corpus().resolve("roslyn/src/Compilers/CSharp/Portable/Parser/LanguageParser.cs")
        if (!Files.isRegularFile(file)) {
            println("CSharpParserBenchmark: $file is missing (tools/csharp-psi/fetch-roslyn.sh), skipped")
            return
        }
        val text = CSharpLexerDiffCorpusTest.readSource(file)
        val offset = editOffset(text)
        myFixture.configureByText("LanguageParser.cs", text)
        val document = myFixture.editor.document
        val manager = PsiDocumentManager.getInstance(project)
        manager.commitAllDocuments()
        val psiFile = myFixture.file
        walk(psiFile.node)
        val far = psiFile.findElementAt(text.length / 8)
        var inserted = false
        val acceptedBefore = CSharpBodyBlockType.acceptedCount()
        var commits = 0
        BenchmarkSupport.run("CSharpParserBenchmark.bodyReparse", "ms", 1.0, recordable = parserBuildsNodes()) {
            WriteCommandAction.runWriteCommandAction(project) {
                if (inserted) document.deleteString(offset, offset + 1) else document.insertString(offset, "x")
                inserted = !inserted
                manager.commitDocument(document)
            }
            commits++
            psiFile.node
        }
        BenchmarkSupport.report(
            "CSharpParserBenchmark.bodyReparse.identity",
            "edit at $offset in ${text.length} chars; body reparses: ${CSharpBodyBlockType.acceptedCount() - acceptedBefore} of $commits commits; " +
                "a leaf at ${text.length / 8} kept its PSI: ${far?.isValid}",
        )
    }

    // --- samples ---------------------------------------------------------------------------------

    private fun corpus(): Path = CSharpTestUtil.corpusRoot()

    private fun samples(): List<Pair<String, List<Pair<String, String>>>> {
        val result = ArrayList<Pair<String, List<Pair<String, String>>>>()
        val parserDir = corpus().resolve("roslyn/src/Compilers/CSharp/Portable/Parser")
        if (Files.isDirectory(parserDir)) {
            val files = Files.list(parserDir).use { s -> s.filter { it.isRegularFile() && it.extension == "cs" }.sorted().toList() }
            result += "roslynParser" to files.map { it.name to CSharpLexerDiffCorpusTest.readSource(it) }
        } else {
            println("CSharpParserBenchmark: $parserDir is missing, sample roslynParser skipped")
        }
        val libraries = corpus().resolve("runtime/src/libraries")
        if (Files.isDirectory(libraries)) {
            val candidates = Files.walk(libraries).use { s ->
                s.filter { it.isRegularFile() && it.extension == "cs" && Files.size(it) <= 100_000 }
                    .map { libraries.relativize(it).toString().replace('\\', '/') }.sorted().toList()
            }
            val chosen = candidates.shuffled(Random(SEED)).take(RUNTIME_FILES).sorted()
            result += "runtimeSample" to chosen.map { it.substringAfterLast('/') to CSharpLexerDiffCorpusTest.readSource(libraries.resolve(it)) }
        } else {
            println("CSharpParserBenchmark: $libraries is missing, sample runtimeSample skipped")
        }
        for ((name, files) in result) {
            println("  sample $name: ${files.size} files, ${BenchmarkSupport.format(megabytes(files))} MB")
        }
        return result
    }

    private fun megabytes(files: List<Pair<String, String>>) = files.sumOf { it.second.length } / 1_000_000.0

    /** True when the parser builds syntax nodes (not the placeholder): only then are parser thresholds meaningful. */
    private fun parserBuildsNodes(): Boolean {
        val node = PsiFileFactory.getInstance(project).createFileFromText("Probe.cs", CSharpLanguage, "class C { void M() { } }").node
        return ParserGateSupport.hasNodes(ParserGateSupport.newDump().map(node))
    }

    private fun walk(node: ASTNode) {
        var c = node.firstChildNode
        while (c != null) {
            walk(c)
            c = c.treeNext
        }
    }

    /**
     * After the first character of the first identifier of a statement line (indented by 12 spaces: a method body of a
     * class in a block namespace) in the second half of the file.
     */
    private fun editOffset(text: String): Int {
        val lexer = CSharpLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            val start = lexer.tokenStart
            if (start >= text.length / 2 && lexer.tokenType == SyntaxKind.IdentifierToken && start >= 13 && text.regionMatches(start - 13, "\n            ", 0, 13) &&
                lexer.tokenEnd - start > 1
            ) {
                return start + 1
            }
            lexer.advance()
        }
        error("no statement line in a method body found")
    }

    private companion object {
        const val SEED = 20261004L
        const val RUNTIME_FILES = 150
    }
}
