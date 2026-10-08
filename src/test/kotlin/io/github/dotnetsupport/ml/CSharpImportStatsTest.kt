package io.github.dotnetsupport.ml

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.completionml.core.imports.ImportsModel
import io.github.dotnetsupport.CSharpUsingTypesTest
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.settings.DotNetSettings
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The order of the namespaces of «Import type» and of the not-yet-imported rows of the completion list by the corpus statistics
 * (0.1.138, [CSharpImportStats]): a stub [ImportsModel] where `Widget` is `Alpha` 80 % / `Beta` 20 % and `Beta` goes with `Beta.Helpers`
 * (PMI +3), two solution classes `Widget` in `Alpha` and `Beta`; the real `ml-models/csharp/cs-imports-e20.cml` on stable names.
 */
class CSharpImportStatsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true
    private var enabled = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        enabled = DotNetSettings.getInstance().importStatistics
        DotNetSettings.getInstance().importStatistics = true
        CSharpImportStats.modelForTests = stub()
        myFixture.addFileToProject("Stats138/Alpha/Widget.cs", "namespace Alpha { public class Widget { } public class Gadget { } }\n")
        myFixture.addFileToProject("Stats138/Beta/Widget.cs", "namespace Beta { public class Widget { } public class Gadget { } }\n")
        myFixture.addFileToProject("Stats138/Beta/Helper.cs", "namespace Beta.Helpers { public class Helper138 { } }\n")
    }

    override fun tearDown() {
        try {
            CSharpImportStats.modelForTests = null
            DotNetSettings.getInstance().importStatistics = enabled
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun q(p: Double) = (-ln(p) * 16).roundToInt()

    private fun stub(): ImportsModel {
        val file = File.createTempFile("imports-stub", ".cml").also { it.deleteOnExit() }
        ImportsModel.write(
            file, "csharp", mapOf("lambda" to "1.0", "ctx_norm" to "sum"), 100,
            paths = listOf("Alpha", "Beta", "Beta.Helpers", "System"), pathDocs = listOf(50, 20, 10, 90),
            names = mapOf("Widget" to (50 to listOf("Alpha" to q(0.8), "Beta" to q(0.2)))),
            co = mapOf("Beta" to listOf("Beta.Helpers" to 48)),
        )
        return ImportsModel.read(file)
    }

    // ---- the quick fix

    /** Launches «Import type 'name'…» (in tests it takes the first namespace) and returns the text of the file. */
    private fun imported(usings: String, name: String): String {
        myFixture.configureByText("Import138_${counter++}.cs", "${usings}namespace App138;\n\nclass A { void M() { $name<caret> x = null!; } }\n")
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.findSingleIntention("Import type '$name'…"))
        return myFixture.editor.document.text
    }

    fun testTheFixTakesTheNamespaceTheStatisticsExpect() {
        assertTrue(imported("", "Widget").startsWith("using Alpha;"))
        // Beta.Helpers present: Beta goes with it
        val withContext = imported("using Beta.Helpers;\n\n", "Widget")
        assertTrue(withContext, withContext.startsWith("using Beta;\nusing Beta.Helpers;"))
    }

    fun testAnUnknownNameKeepsTheIndexOrder() {
        val text = imported("using Beta.Helpers;\n\n", "Gadget")
        assertTrue(text, text.startsWith("using Alpha;\nusing Beta.Helpers;"))
    }

    fun testTheSettingOffLeavesTheIndexOrder() {
        DotNetSettings.getInstance().importStatistics = false
        val text = imported("using Beta.Helpers;\n\n", "Widget")
        assertTrue(text, text.startsWith("using Alpha;\nusing Beta.Helpers;"))
    }

    // ---- the completion list

    private fun rows(usings: String, typed: String): List<String> {
        myFixture.configureByText("Rows138_${counter++}.cs", "${usings}class Sample\n{\n    void Run()\n    {\n        $typed<caret>\n    }\n}\n")
        myFixture.completeBasic()
        return myFixture.lookupElements.orEmpty().map { LookupElementPresentation.renderElement(it).let { p -> p.itemText + p.tailText.orEmpty() } }
    }

    fun testTheListPutsTheExpectedNamespaceFirst() {
        val plain = rows("using System;\n", "Widg").filter { it.startsWith("Widget (in ") }
        assertEquals(plain.toString(), listOf("Widget (in Alpha)", "Widget (in Beta)"), plain)
        val withContext = rows("using System;\nusing Beta.Helpers;\n", "Widg").filter { it.startsWith("Widget (in ") }
        assertEquals(withContext.toString(), listOf("Widget (in Beta)", "Widget (in Alpha)"), withContext)
    }

    // ---- the real statistics

    fun testTheRealStatisticsOnStableNames() {
        val file = listOf(File("ml-models/csharp"), File("../ml-models/csharp")).map { File(it, CSharpImportStats.FILE) }.firstOrNull { it.isFile }
        if (file == null) { println("CSharpImportStatsTest: no ml-models/csharp/${CSharpImportStats.FILE}, skipped"); return }
        CSharpImportStats.modelForTests = ImportsModel.read(file)
        val stats = CSharpImportStats.getInstance()
        val json = listOf("Newtonsoft.Json", "System.Text.Json")
        assertEquals("System.Text.Json", stats.order("JsonSerializer", emptyList(), json).first())
        assertEquals("System.Text.Json", stats.order("JsonSerializer", listOf("System.Text.Json.Serialization"), json).first())
        assertEquals("Newtonsoft.Json.Linq", stats.order("JObject", emptyList(), listOf("Newtonsoft.Json", "Newtonsoft.Json.Linq")).first())
        assertEquals("System.Threading.Tasks", stats.order("Task", emptyList(), listOf("Microsoft.Build.Utilities", "System.Threading.Tasks")).first())
        assertEquals("Microsoft.Extensions.Logging", stats.order("ILogger", listOf("Microsoft.Extensions.DependencyInjection"), listOf("Serilog", "Microsoft.Extensions.Logging")).first())
        // the context flips the answer: Color is Unity's by frequency, System.Drawing's next to System.Drawing.Imaging
        val color = listOf("System.Drawing", "UnityEngine")
        assertEquals("UnityEngine", stats.order("Color", emptyList(), color).first())
        assertEquals("System.Drawing", stats.order("Color", listOf("System.Drawing.Imaging"), color).first())
        // a name the corpus never saw keeps the given order
        assertEquals(listOf("Zeta", "Alpha"), stats.order("NoSuchType138", emptyList(), listOf("Zeta", "Alpha")))
    }

    companion object {
        private var counter = 0
    }
}
