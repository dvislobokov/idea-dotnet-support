package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpRouteTemplates
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Route templates and JSON in strings (task 3.7 of docs/COMPLETION_GAPS.md, 0.1.93): the parts of `[HttpGet("{id:long}")]` and
 * `MapGet("/{id}")` colored, the route constraints after `:`, the parameters of the action or the handler after `{`, `[controller]`
 * tokens; JSON injected after `// lang=json`, into `[StringSyntax(Json)]` parameters and `JsonDocument.Parse`.
 */
class CSharpRouteTemplatesTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun minimal(statements: String, members: String = ""): String =
        "using System;\nusing System.Text.Json;\nusing System.Threading;\nclass Order { }\ninterface IMediator { }\n" +
            "class Endpoints\n{\n$members\n    void Map(WebApplication app)\n    {\n        $statements\n    }\n}\n"

    private fun configure(text: String): PsiFile = myFixture.configureByText("Route${counter++}.cs", text)

    private fun lookup(text: String): List<String> {
        configure(text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun choose(text: String, item: String): String {
        configure(text)
        myFixture.completeBasic()
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == item }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    fun testParts() {
        val text = "\"/orders/{id:long:min(1)}/{{x}}/{*path}/{slug?}/{name=all}/{code:regex(^\\\\d{{3}}$)}\""
        val parts = CSharpRouteTemplates.parts(text, false).map { it.kind.name + " " + text.substring(it.start, it.end) }
        assertEquals(
            listOf(
                "BRACE {", "PARAMETER id", "CONSTRAINT long", "CONSTRAINT min", "BRACE }", "BRACE {", "PARAMETER path", "BRACE }", "BRACE {", "PARAMETER slug", "BRACE }",
                "BRACE {", "PARAMETER name", "BRACE }", "BRACE {", "PARAMETER code", "CONSTRAINT regex", "BRACE }",
            ),
            parts,
        )
        assertEquals(listOf("TOKEN [controller]"), CSharpRouteTemplates.parts("\"api/[controller]\"", true).map { it.kind.name + " " + "\"api/[controller]\"".substring(it.start, it.end) })
    }

    fun testTemplatesAreColored() {
        configure(minimal("app.MapGet(\"/orders/{id:long}\", (long id) => id); Console.WriteLine(\"{x:int}\");",
            "    [HttpGet(\"items/{itemId:guid}\")] public void Get(Guid itemId) { }\n"))
        val infos = myFixture.doHighlighting()
        val constraints = infos.filter { it.forcedTextAttributesKey == CSharpColors.METHOD }.map { it.text }
        assertTrue(constraints.toString(), constraints.containsAll(listOf("long", "guid")))
        assertFalse("not a route", "int" in constraints)
        assertEquals(4, infos.count { it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM && (it.text == "{" || it.text == "}") })
    }

    fun testConstraintsAfterAColon() {
        val items = lookup(minimal("app.MapGet(\"/orders/{id:<caret>}\", (long id) => id);"))
        assertTrue(items.toString(), items.containsAll(listOf("int", "long", "guid", "alpha", "minlength", "range", "regex")))
        assertEquals("int", items.first())
        val text = choose(minimal("app.MapGet(\"/orders/{name:minl<caret>}\", (string name) => name);"), "minlength")
        assertTrue(text, text.contains("{name:minlength()}"))
        assertEquals('(', myFixture.editor.document.charsSequence[myFixture.caretOffset - 1])
    }

    fun testParametersOfTheHandler() {
        assertEquals(listOf("id", "slug"), lookup(minimal("app.MapGet(\"/orders/{<caret>\", (long id, string slug, CancellationToken token, IMediator mediator) => id);")))
        assertEquals("named already", listOf("slug"), lookup(minimal("app.MapGet(\"/orders/{id}/{<caret>\", (long id, string slug) => id);")))
        val text = choose(minimal("app.MapGet(\"/orders/{<caret>\", (long id) => id);"), "id")
        assertTrue(text, text.contains("\"/orders/{id}\""))
        assertEquals("a method group", listOf("code"), lookup(minimal("app.MapGet(\"/by-code/{<caret>}\", ByCode);", "    static string ByCode(string code) => code;\n")))
    }

    fun testParametersOfTheAction() {
        assertEquals(listOf("id"), lookup(minimal("", "    [HttpGet(\"{<caret>}\")] public void Get(int id, [FromBody] Order body, CancellationToken cancellationToken) { }\n")))
        assertEquals(listOf("action", "area", "controller"), lookup(minimal("", "    [Route(\"api/[<caret>\")] public void Get() { }\n")).sorted())
    }

    fun testCommentMakesARoute() {
        val items = lookup(minimal("// lang=route\n        var pattern = \"/a/{x:<caret>}\";"))
        assertTrue(items.contains("int"))
    }

    // ---- JSON

    private fun isJsonAt(file: PsiFile, marker: String): Boolean {
        val offset = file.text.indexOf(marker)
        assertTrue("no $marker", offset >= 0)
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, offset)?.containingFile?.language?.id == "JSON"
    }

    fun testJsonInjection() {
        val file = configure(
            minimal(
                "// lang=json\n        var a = \"{\\\"first\\\": 1}\";\n        var doc = JsonDocument.Parse(\"[\\\"second\\\"]\");\n" +
                    "        Send(\"{\\\"third\\\": true}\");\n        var plain = \"{\\\"fourth\\\": 1}\";",
                "    static void Send([System.Diagnostics.CodeAnalysis.StringSyntax(System.Diagnostics.CodeAnalysis.StringSyntaxAttribute.Json)] string json) { }\n",
            ),
        )
        assertTrue(isJsonAt(file, "first"))
        assertTrue(isJsonAt(file, "second"))
        assertTrue(isJsonAt(file, "third"))
        assertFalse(isJsonAt(file, "fourth"))
        val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, file.text.indexOf("first"))!!.containingFile
        // the injected tree is parsed from the decoded text, its leaves keep the text of the host
        assertNull(injected.text, com.intellij.psi.util.PsiTreeUtil.findChildOfType(injected, com.intellij.psi.PsiErrorElement::class.java))
        assertTrue(com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(injected, com.intellij.psi.PsiElement::class.java).any { it.javaClass.simpleName.contains("JsonProperty") })
    }

    /** The scenario file of the playground as it is: colors where its EXPECT lines say, no warnings of templates in the code as written. */
    fun testPlaygroundScenario() {
        val text = java.io.File("debug-playground/ShopApi/Playground/AspNetCompletion.cs").readText().replace("\r\n", "\n")
        val file = myFixture.configureByText("AspNetCompletion${counter++}.cs", text)
        val infos = myFixture.doHighlighting()
        val items = infos.filter { it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM || it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM_2 }.map { it.text }
        assertTrue(items.toString(), items.containsAll(listOf("{OrderId}", "{Total}", "{orderId}", "{reason}", "[controller]")))
        assertTrue(infos.filter { it.forcedTextAttributesKey == CSharpColors.METHOD }.map { it.text }.containsAll(listOf("long", "guid")))
        assertEquals(emptyList<String>(), infos.filter { it.description?.contains("message template") == true }.map { it.description })
        assertTrue(isJsonAt(file, "\"sku\": \"A-1\""))
        assertTrue(isJsonAt(file, "[1, 2, 3]\")"))
    }

    private companion object {
        var counter = 0
    }
}
