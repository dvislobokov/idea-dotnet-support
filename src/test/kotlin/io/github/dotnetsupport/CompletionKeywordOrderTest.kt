package io.github.dotnetsupport

import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFeature
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.roslyn.RoslynCompletionPolicy
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails

/**
 * The order of keywords and types in a class body (0.1.44): `pub` put the unimported type `PublicKey` above `public`, `public s` and
 * `public str` (the TYPE of the `prop` template too) put `String`, `SByte`, `Stream` above `string`, `static`, `short`, out of sight. The
 * items are those roslyn-language-server 5.12 sent there (`roslyn/capture-5.12-keywords`), each with the priority the plugin gives it;
 * the order is the one of the lookup, with the contributor of the plugin that ignores the case in front.
 */
class CompletionKeywordOrderTest : BasePlatformTestCase() {
    private val captured: Map<String, List<JsonObject>> by lazy {
        val summary = javaClass.getResourceAsStream("/roslyn/capture-5.12-keywords/09-summary_of_keyword_positions.json")!!.reader().use(JsonParser::parseReader).asJsonObject
        summary.getAsJsonArray("result").associate { block -> block.asJsonObject["prefix"].asString to block.asJsonObject.getAsJsonArray("rows").map { it.asJsonObject } }
    }

    override fun setUp() {
        super.setUp()
        RoslynLanguageServerSettings.getInstance().state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        // the server's path (and the heuristics beside it): built-in is the default since 0.1.60
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.ROSLYN)
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport"))!!
        ApplicationManager.getApplication().extensionArea.getExtensionPoint(CompletionContributor.EP)
            .registerExtension(CompletionContributorEP("C#", CapturedServer::class.java.name, plugin), testRootDisposable)
    }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            RoslynLanguageServerSettings.getInstance().state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CapturedServer.items = emptyList()
        } finally {
            super.tearDown()
        }
    }

    private fun order(text: String, prefix: String): List<String> {
        CapturedServer.items = captured.getValue(prefix).map { row ->
            CompletionItem(row["label"].asString).apply {
                kind = CompletionItemKind.forValue(row["kind"].asInt)
                sortText = row["sortText"]?.takeIf { !it.isJsonNull }?.asString
                labelDetails = row["labelDetails"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { CompletionItemLabelDetails().apply { description = it["description"].asString } }
            }
        }
        myFixture.configureByText("Order.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testAKeywordAboveAnUnimportedType() {
        assertEquals(listOf("public", "PublicKey"), order("class A\n{\n    pub<caret>\n}\n", "pub"))
    }

    fun testKeywordsInTheirCaseAboveTypesMatchedWithoutIt() {
        val list = order("class A\n{\n    public s<caret>\n}\n", "s")
        assertEquals(listOf("sbyte", "sealed", "short", "static", "string", "struct"), list.take(6))
        assertTrue("the types after them: $list", list.indexOf("String") > list.indexOf("struct") && list.indexOf("SByte") > list.indexOf("struct"))
        assertTrue("a type of an unimported namespace after the imported ones: $list", list.indexOf("SafeHandle") > list.indexOf("String"))
    }

    fun testStringFirstOnStr() {
        val list = order("class A\n{\n    public str<caret>\n}\n", "str")
        assertEquals(listOf("string", "struct"), list.take(2))
        assertTrue(list.indexOf("String") > 1)
    }

    fun testATypeInItsCaseStaysAboveTheKeyword() {
        // `S`: the types are written so, the keywords are not
        val list = order("class A\n{\n    public S<caret>\n}\n", "s")
        assertTrue(list.indexOf("String") < list.indexOf("string"))
    }

    /**
     * `public RankedOrder Order`: the name suggestions of the server popped up after the type and stayed open, and the gray
     * ` { get; set; }` gives way to an open list (TYPE:stats-ghost showed nothing). No auto-popup there; Ctrl+Space still lists them.
     */
    fun testNoAutoPopupOverTheNameOfAProperty() {
        fun skipped(text: String): com.intellij.util.ThreeState {
            myFixture.configureByText("Names.cs", text)
            val offset = myFixture.caretOffset
            val element = myFixture.file.findElementAt((offset - 1).coerceAtLeast(0)) ?: myFixture.file
            return io.github.dotnetsupport.lang.CSharpPropertyNameConfidence().shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset)
        }
        assertEquals(com.intellij.util.ThreeState.YES, skipped("class RankedOrder { }\nclass A\n{\n    public RankedOrder <caret>\n}\n"))
        assertEquals(com.intellij.util.ThreeState.YES, skipped("class RankedOrder { }\nclass A\n{\n    public RankedOrder Ord<caret>\n}\n"))
        assertEquals(com.intellij.util.ThreeState.UNSURE, skipped("class RankedOrder { }\nclass A\n{\n    private RankedOrder <caret>\n}\n"))
        assertEquals(com.intellij.util.ThreeState.UNSURE, skipped("class A\n{\n    public Ran<caret>\n}\n"))
    }

    /** The items the server sent, with the priority of the plugin ([RoslynCompletionPolicy]). */
    class CapturedServer : CompletionContributor() {
        override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
            for (item in items) {
                val priority = RoslynCompletionPolicy.priority(item.kind, item.preselect == true, RoslynCompletionPolicy.isUnimported(item))
                result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(item.label), priority))
            }
        }

        companion object {
            @Volatile
            var items: List<CompletionItem> = emptyList()
        }
    }
}
