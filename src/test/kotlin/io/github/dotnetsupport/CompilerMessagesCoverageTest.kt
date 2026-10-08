package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpWarningContext
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File

/**
 * The compiler errors of dotnet/docs' compiler-messages pages against the plugin's own diagnostics (the user's rule of 2026-10-08: every
 * error of the compiler is an error in the editor too). The corpus `compilerMessages/cases` (one .cs per example) is the examples of the pages
 * (`tools/compiler-messages/extract.py`), `cases.roslyn.txt` what Roslyn reports for each (`tools/compiler-messages/oracle.py`). Each
 * example is highlighted the way the editor does it (syntax + semantic annotators, the light project's assembly fixtures) and the
 * errors are matched with Roslyn's by code and line; `coverage.txt` is the committed state — `ok` matched, `missing` Roslyn's error the
 * plugin lacks, `extra` the plugin's error Roslyn does not report (a false positive, the worse of the two). The test fails on any
 * change of that state: an improvement too, so the new file (`build/reports/compilerMessages/coverage.txt`) is reviewed and copied
 * over the resource. `by-code.md` next to it ranks the missing codes by how many examples want them: the order of the work.
 */
class CompilerMessagesCoverageTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    private companion object {
        /** The usual fixtures plus System.ObjectModel (ObservableCollection of the cs0050 examples); the oracle compiles against the whole ref pack. */
        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections", "System.ObjectModel").map(CSharpUsingTypesTest::fixture))
        }
    }

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        CSharpWarningContext.setNullableForTests("enable")
        CSharpSemanticEnvironment.setGeneratedKnownForTests(true)   // no source generators in the corpus: what is not in the file is not anywhere
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpWarningContext.setNullableForTests(null, set = false)
            CSharpSemanticEnvironment.setGeneratedKnownForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The plugin's errors of [text] as `CSxxxx@line`; the file is removed afterwards, the examples repeat their type names. */
    private fun ours(name: String, text: String): Set<String> {
        val file = myFixture.configureByText(name, text)
        try {
            val document = myFixture.editor.document
            return myFixture.doHighlighting(HighlightSeverity.ERROR)
                .filter { it.severity == HighlightSeverity.ERROR && it.description?.startsWith("CS") == true }
                .map { it.description!!.substringBefore(':') + "@" + (document.getLineNumber(it.startOffset) + 1) }.toSet()
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { file.delete() }
        }
    }

    fun testTheCorpusAgainstRoslyn() {
        val dir = File("src/test/resources/compilerMessages")
        val cases = File(dir, "cases").listFiles { f -> f.name.endsWith(".cs") }!!.sortedBy { it.name }
        assertTrue("corpus missing", cases.isNotEmpty())
        val roslyn = HashMap<String, MutableSet<String>>()
        val texts = cases.associate { it.name to it.readText().replace("\r\n", "\n") }
        for (line in File(dir, "cases.roslyn.txt").readLines()) {
            if (line.isBlank()) continue
            val (file, start, _, code, severity) = line.split('\t')
            if (severity != "error") continue
            val text = texts[file] ?: continue
            roslyn.getOrPut(file) { LinkedHashSet() } += code + "@" + (text.substring(0, start.toInt().coerceIn(0, text.length)).count { it == '\n' } + 1)
        }
        val rows = ArrayList<String>()
        val byCode = HashMap<String, IntArray>()   // code -> [roslyn, matched, extra]
        val examplesMissing = HashMap<String, MutableList<String>>()
        var matched = 0; var total = 0; var extras = 0
        for (case in cases) {
            val expected = roslyn[case.name] ?: emptySet()
            val actual = ours(case.name, texts.getValue(case.name))
            for (e in expected.sorted()) {
                val code = e.substringBefore('@')
                val counts = byCode.getOrPut(code) { IntArray(3) }
                counts[0]++; total++
                if (e in actual) { counts[1]++; matched++; rows += "${case.name}\t$e\tok" }
                else { rows += "${case.name}\t$e\tmissing"; examplesMissing.getOrPut(code) { ArrayList() } += case.name }
            }
            for (a in (actual - expected).sorted()) {
                byCode.getOrPut(a.substringBefore('@')) { IntArray(3) }[2]++; extras++
                rows += "${case.name}\t$a\textra"
            }
        }
        val summary = "# ${cases.size} examples, Roslyn errors $total, matched $matched, missing ${total - matched}, extra $extras"
        val actualText = (listOf(summary) + rows).joinToString("\n") + "\n"
        val reports = File("build/reports/compilerMessages").apply { mkdirs() }
        File(reports, "coverage.txt").writeText(actualText)
        val codeRows = byCode.entries.sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] - it.value[1] }.thenBy { it.key })
        File(reports, "by-code.md").writeText(buildString {
            appendLine("| Code | Roslyn | matched | extra | examples missing it |"); appendLine("|---|---|---|---|---|")
            for ((code, c) in codeRows) appendLine("| $code | ${c[0]} | ${c[1]} | ${c[2]} | ${examplesMissing[code]?.distinct()?.joinToString(" ") ?: ""} |")
        })
        val baseline = File(dir, "coverage.txt")
        val expectedText = if (baseline.exists()) baseline.readText().replace("\r\n", "\n") else ""
        if (expectedText != actualText) {
            val old = expectedText.lines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
            val new = actualText.lines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
            val gone = (old - new).take(40); val came = (new - old).take(40)
            fail("$summary\nThe coverage changed; review build/reports/compilerMessages/coverage.txt (and by-code.md) and copy it over " +
                "src/test/resources/compilerMessages/coverage.txt.\n- gone:\n${gone.joinToString("\n")}\n+ new:\n${came.joinToString("\n")}")
        }
    }
}
