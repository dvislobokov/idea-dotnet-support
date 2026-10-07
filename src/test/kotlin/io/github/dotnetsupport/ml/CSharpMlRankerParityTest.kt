package io.github.dotnetsupport.ml

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.completionml.core.ngram.NgramModel
import io.github.completionml.core.rank.ExampleShards
import io.github.completionml.core.rank.FeatureExtractor
import io.github.completionml.core.rank.LinearRanker
import io.github.completionml.core.spi.TokenKind
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpMlInfo
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.io.File
import java.util.Random

/**
 * The weigher computes the same feature vectors as the offline export for the same list (ADAPTER.md §4, ML_RANKER_EXPORT_TASK.md §5):
 * the export of the fixture repository of [CSharpMlDatasetExportTest] is replayed position by position with the ranker active
 * ([CSharpMlCompletionRanker.modelsForTests]: the real `e15-a.cml` + `e18-rank.cml`), and every candidate's features on the lookup
 * element equal those of the shard. Also the weigher itself: the rows carry a score and the "ML" mark and come in score order.
 * Skipped when the repository has no `ml-models/csharp`.
 */
class CSharpMlRankerParityTest : BasePlatformTestCase() {
    private var autocomplete = true
    private val modelDir = listOf(File("ml-models/csharp"), File("../ml-models/csharp")).firstOrNull { File(it, CSharpMlModels.LM).isFile && File(it, CSharpMlModels.RANKER).isFile }

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CSharpMlCompletionRanker.modelsForTests = null
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val order = """
        namespace Shop.Models
        {
            public class Order
            {
                private int count;
                public int Total { get; set; }
                public string Name { get; set; }
                public int Count() { return count; }
                public void Reset(int value) { count = value; Total = value; }
            }
        }
    """.trimIndent()

    private val program = """
        using Shop.Models;
        namespace Shop.App
        {
            public class Program
            {
                private static int limit = 3;
                public static void Run(Order order, int amount)
                {
                    int total = order.Total;
                    int count = order.Count();
                    order.Reset(amount);
                    if (total > limit) { order.Total = amount + count; }
                    Run(order, total);
                }
            }
        }
    """.trimIndent()

    fun testWeigherFeaturesEqualTheExport() {
        if (modelDir == null) { println("CSharpMlRankerParityTest: no ml-models/csharp, skipped"); return }
        val lm = NgramModel.read(File(modelDir, CSharpMlModels.LM))
        val ranker = LinearRanker.read(File(modelDir, CSharpMlModels.RANKER))
        val loaded = CSharpMlModels.Loaded(lm, ranker, modelDir.path)
        assertEquals(CSharpMlFeatures.schema.names, ranker.schema.names)

        // pass 1: the export, as mlDataset runs it (the same lambda, no type snapshot: the IDE path has none)
        val repo = FileUtil.createTempDirectory("csml-parity-", null, true)
        File(repo, "Shop/Models/Order.cs").apply { parentFile.mkdirs(); writeText(order) }
        File(repo, "Shop/App/Program.cs").apply { parentFile.mkdirs(); writeText(program) }
        val options = CSharpMlExporter.Options(perFile = 1000, maxFiles = 0, cacheLambda = CSharpMlModels.CACHE_LAMBDA, typeSnapshot = false)
        val extractor = FeatureExtractor(CSharpMlFeatures.schema, lm.vocab, lm, options.cacheLambda)
        val exporter = CSharpMlExporter(myFixture, project, myFixture.module, testRootDisposable, lm.vocab, extractor, options)
        val shard = File(repo.parentFile, repo.name + ExampleShards.EXTENSION)
        val stats = CSharpMlExporter.Stats()
        val examples = try {
            ExampleShards.Writer(shard, CSharpMlLanguage.id, "test", CSharpMlFeatures.schema, withNames = true).use { exporter.exportRepository(repo, it, stats) }
            ExampleShards.readAll(shard).second
        } finally { shard.delete(); FileUtil.delete(repo) }
        assertTrue("lists exported: ${stats.summary()}", examples.size > 5)

        // pass 2: the same files in the project, the same positions (the sampling of CSharpMlExporter.exportFile), the ranker active
        CSharpMlCompletionRanker.modelsForTests = loaded
        myFixture.addFileToProject("Shop/Models/Order.cs", order)
        myFixture.addFileToProject("Shop/App/Program.cs", program)
        var next = 0
        var compared = 0
        var sorted = 0
        // the export visits the files in path order: Shop/App/Program.cs before Shop/Models/Order.cs
        for ((path, text) in listOf("Shop/App/Program.cs" to program, "Shop/Models/Order.cs" to order)) {
            val vf = myFixture.findFileInTempDir(path)
            val tokens = CSharpMlLanguage.tokenizer.tokens(text)
            val rnd = Random(options.seed xor tokens.size.toLong())
            val identifiers = (1 until tokens.size).filter { tokens[it].kind == TokenKind.IDENT }
            myFixture.configureFromExistingVirtualFile(vf)
            val document = myFixture.editor.document
            for (i in identifiers) {
                val t = tokens[i]
                val prefixLen = when (rnd.nextInt(10)) { in 0..4 -> 0; in 5..7 -> 1; else -> 2 }.coerceAtMost(t.text.length)
                val caret = t.offset + prefixLen
                val modified = text.substring(0, caret) + text.substring(t.offset + t.text.length)
                try {
                    WriteCommandAction.runWriteCommandAction(project) { document.setText(modified) }
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    myFixture.editor.caretModel.moveToOffset(caret)
                    val items = myFixture.completeBasic() ?: continue
                    val candidates = items.map(NativeCSharpMlInfo::candidateOf).distinctBy { it.lookupString }
                    if (candidates.size < 2 || candidates.none { it.lookupString == t.text }) continue
                    val example = examples[next++]
                    val names = example.candidateNames!!.toList()
                    assertEquals("the list at ${vf.name}:$caret (${t.text})", names.toSet(), candidates.map { it.lookupString }.toSet())
                    val byName = HashMap<String, Int>().also { m -> names.forEachIndexed { c, n -> m[n] = c } }
                    // the platform lifts the start matches of the prefix over the middle matches before any weigher (PreferStartMatching):
                    // the scores never grow within each of the two groups
                    val previousByGroup = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE)
                    val prefix = text.substring(t.offset, caret)
                    for (item in items) {
                        val group = if (item.lookupString.startsWith(prefix, ignoreCase = true)) 0 else 1
                        val previous = previousByGroup[group]
                        val score = checkNotNull(CSharpMlCompletionRanker.scoreOf(item)) { "no score on ${item.lookupString} at ${vf.name}:$caret" }
                        val c = byName.getValue(item.lookupString)
                        val expected = example.base[c]
                        for (f in expected.indices) assertEquals("${CSharpMlFeatures.schema.baseNames[f]} of ${item.lookupString} at ${vf.name}:$caret", expected[f], score.features[f], 0f)
                        compared++
                        val presentation = LookupElementPresentation.renderElement(item)
                        assertTrue("the ML mark on ${item.lookupString}: ${presentation.tailText}", presentation.tailText?.endsWith(" ML") == true)
                        // the lookup lists its rows in the order of the weigher: the scores never grow
                        assertTrue("score order at ${vf.name}:$caret: ${item.lookupString} ${score.value} after $previous in ${items.map { it.lookupString + "=" + CSharpMlCompletionRanker.scoreOf(it)?.value + "/" + it.javaClass.simpleName + "/" + NativeCSharpMlInfo.candidateOf(it) }}", score.value <= previous + 1e-9)
                        previousByGroup[group] = score.value
                        sorted++
                    }
                } finally {
                    LookupManager.getInstance(project).hideActiveLookup()
                    WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
            }
        }
        assertEquals("every exported list was replayed", examples.size, next)
        assertTrue("candidates compared: $compared", compared > 20)
        println("CSharpMlRankerParityTest: ${examples.size} lists, $compared candidates with equal features, $sorted rows in score order")
    }
}
