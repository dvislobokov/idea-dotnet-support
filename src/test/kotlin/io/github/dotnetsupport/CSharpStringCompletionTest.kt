package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * COMPLETION inside strings (tasks 2.6 and 2.7 of docs/COMPLETION_GAPS.md): names and members in the holes of interpolated strings of
 * every kind (`$"{order.|}"`, `$@"…"`, `$$"""{{…}}"""`), nothing in their text; format specifiers by the type of the value after `{x:`,
 * in `string.Format` / `Console.WriteLine` items, in `ToString("…")` and `ParseExact`, as Rider lists them (dumps 20, 43).
 */
class CSharpStringCompletionTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
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

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Strings${counter++}.cs", text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun strings(statements: String): List<String> = lookup(body(statements)).map { it.lookupString }

    private fun shown(element: LookupElement): String {
        val presentation = LookupElementPresentation.renderElement(element)
        return presentation.itemText + (presentation.typeText?.let { " : $it" } ?: "")
    }

    private fun choose(statements: String, item: String): String {
        val elements = lookup(body(statements))
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    private fun body(statements: String): String =
        "using System;\nusing System.Text;\nenum Color { Red, Blue }\n" +
            "class Order { public decimal Total { get; set; } public string Customer = \"\"; public DateTime Created; public int Count; public double Rate; }\n" +
            "class Sample\n{\n    Color _color;\n    void Run(Order order, string name, int count, TimeSpan span, Guid id, StringBuilder builder)\n    {\n        $statements\n    }\n}\n"

    // ---- 2.7: the holes of interpolated strings

    fun testNamesInAHole() {
        for (statement in listOf("var s = \$\"{<caret>}\";", "var s = \$\"text {<caret>\";", "var s = \$\"a {name} b {<caret>} c\";")) {
            val items = strings(statement)
            assertTrue("$statement: $items", items.containsAll(listOf("order", "name", "count")))
        }
    }

    fun testMembersAfterADotInAHoleOfEveryKindOfString() {
        for (statement in listOf(
            "var s = \$\"{order.<caret>}\";", "var s = \$\"{order.<caret>\";", "var s = \$@\"{order.<caret>}\";", "var s = @\$\"{order.<caret>}\";",
            "var s = \$\"\"\"{order.<caret>}\"\"\";", "var s = \$\$\"\"\"{{order.<caret>}}\"\"\";", "var s = \$\"\"\"\n            {order.<caret>}\n            \"\"\";",
            "var s = \$\"{(order.<caret>)}\";", "var s = \$\"{name.Length + order.<caret>}\";",
        )) {
            val items = strings(statement)
            assertTrue("$statement: $items", items.containsAll(listOf("Total", "Customer", "Created")))
            assertFalse("$statement: no names of the method after a dot: $items", "name" in items)
        }
    }

    fun testAPrefixInAHole() {
        assertEquals("var s = \$\"a {name} {order.Total}\";", choose("var s = \$\"a {name} {order.Tot<caret>}\";", "Total").lines()[9].trim())
    }

    fun testTheTextOfAStringHasNoList() {
        for (statement in listOf("var s = \"abc <caret>\";", "var s = \$\"text <caret> {name}\";", "var s = @\"abc <caret>\";", "var s = \"\"\"abc <caret>\"\"\";")) {
            assertEquals(statement, emptyList<String>(), strings(statement))
        }
    }

    fun testTheListOpensByItselfAfterADotInAHole() {
        myFixture.configureByText("Auto${counter++}.cs", body("var s = \$\"{order<caret>}\";"))
        myFixture.type(".")
        CodeCompletionHandlerBase(CompletionType.BASIC, false, true, true).invokeCompletion(project, myFixture.editor, 0)
        UIUtil.dispatchAllInvocationEvents()
        val items = myFixture.lookup?.items?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), "Total" in items)
    }

    // ---- 2.6: format specifiers

    fun testANumberInAnInterpolationAsRider() {
        val elements = lookup(body("var s = \$\"{order.Total:<caret>}\";"))
        val rider = listOf(
            "0000 - custom : 0123", "C - currency : ¤1,234.45", "C0 - currency : ¤1,234", "E - exponential : 1.234000E+004", "e2 - exponential : 1.23e+004",
            "E2 - exponential : 1.23E+004", "F - fixed-point : 123.45", "F1 - fixed-point : 123.4", "G - general : 1.234E+56", "g2 - general : 1.2e+45",
            "N - number : 12,345.68", "N0 - integer : 12,345", "N1 - number : 12,345.6", "P - percent : 123.45 %", "P1 - percent : 1,230.0 %",
        )
        val shown = elements.map(::shown)
        assertTrue(shown.toString(), shown.containsAll(rider))
        assertFalse("decimal has no hexadecimal: $shown", shown.any { it.startsWith("X ") })
        assertTrue("an int has", strings("var s = \$\"{count:<caret>}\";").containsAll(listOf("D", "X", "N2")))
        assertTrue("a double has", strings("var s = \$\"{order.Rate:<caret>}\";").containsAll(listOf("R", "F")))
    }

    fun testADateTimeAndAPrefix() {
        val items = strings("var s = \$\"{order.Created:<caret>}\";")
        assertTrue(items.toString(), items.containsAll(listOf("d", "D", "t", "T", "yyyy-MM-dd", "HH:mm:ss", "o", "s", "u")))
        assertFalse(items.toString(), "N2" in items)
        assertTrue(strings("var s = \$\"{order.Created:yyyy-<caret>}\";").contains("yyyy-MM-dd"))
        assertEquals("var s = \$\"{order.Created:yyyy-MM-dd}\";", choose("var s = \$\"{order.Created:yyyy-<caret>}\";", "yyyy-MM-dd").lines()[9].trim())
    }

    fun testCompositeFormatItemsTakeTheirArgument() {
        assertTrue(strings("Console.WriteLine(\"{0} {1:<caret>}\", name, order.Created);").contains("yyyy-MM-dd"))
        assertTrue(strings("var s = string.Format(\"{0,10:<caret>}\", order.Total);").contains("N2"))
        assertTrue(strings("builder.AppendFormat(\"{0:<caret>}\", id);").contains("B"))
        assertEquals("no item: plain text", emptyList<String>(), strings("Console.WriteLine(\"{{0:<caret>\", count);"))
    }

    fun testToStringAndParseExact() {
        assertTrue(strings("var s = order.Created.ToString(\"<caret>\");").contains("yyyy-MM-dd"))
        assertTrue(strings("var s = count.ToString(\"<caret>\");").contains("X"))
        assertTrue(strings("var d = DateTime.ParseExact(name, \"<caret>\", null);").contains("dd.MM.yyyy"))
    }

    fun testTimeSpanGuidAndEnum() {
        assertTrue(strings("var s = \$\"{span:<caret>}\";").containsAll(listOf("c", "g", "G")))
        // a custom TimeSpan format escapes `:` — with `\\` in a regular string, as it is in a verbatim one
        assertEquals("var s = span.ToString(\"hh\\\\:mm\\\\:ss\");", choose("var s = span.ToString(\"<caret>\");", "hh\\\\:mm\\\\:ss").lines()[9].trim())
        assertTrue(strings("var s = span.ToString(@\"<caret>\");").contains("hh\\:mm\\:ss"))
        assertTrue(strings("var s = \$\"{id:<caret>}\";").containsAll(listOf("N", "D", "B", "P")))
        assertTrue(strings("var s = \$\"{_color:<caret>}\";").containsAll(listOf("G", "F", "D", "X")))
    }

    fun testAValueOfAnotherTypeHasNoFormats() {
        assertEquals(emptyList<String>(), strings("var s = \$\"{name:<caret>}\";"))
    }

    private companion object {
        var counter = 0

        private fun bytes(name: String): ByteArray? = CSharpStringCompletionTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
