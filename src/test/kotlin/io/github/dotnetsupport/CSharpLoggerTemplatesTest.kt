package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpLoggerTemplates
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Message templates of logging (task 3.8 of docs/COMPLETION_GAPS.md, 0.1.93): the placeholders of `logger.LogX("…")`, Serilog's
 * `Log.Information("…")` and `[LoggerMessage(Message = "…")]` in the color of format items, the warnings of a count of arguments that
 * does not match (CA2017) and of a placeholder without a parameter (SYSLIB1014), the names of the arguments after `{`, `{Name}` in the text.
 */
class CSharpLoggerTemplatesTest : BasePlatformTestCase() {
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

    private fun body(statements: String, members: String = ""): String =
        "using System;\nusing Microsoft.Extensions.Logging;\nclass Order { public long Id; public decimal Total; public string Customer = \"\"; }\n" +
            "partial class Sample\n{\n$members\n    void Run(ILogger logger, Order order, decimal total, string name, Exception ex)\n    {\n        $statements\n    }\n}\n"

    private fun configure(text: String) = myFixture.configureByText("Logger${counter++}.cs", text)

    private fun items(statements: String, members: String = ""): List<String> {
        configure(body(statements, members))
        return myFixture.doHighlighting().filter { it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM || it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM_2 }.map { it.text }
    }

    private fun warnings(statements: String, members: String = ""): List<Pair<String, String>> {
        configure(body(statements, members))
        return myFixture.doHighlighting(HighlightSeverity.WARNING).filter { it.description?.contains("message template") == true }.map { it.text to it.description }
    }

    private fun lookup(statements: String): List<String> {
        configure(body(statements))
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    /** DEV_JOURNEY 4.8 (0.1.100): the static `Log` of Serilog imported with `using Serilog;`, in a top-level program as the journey wrote it. */
    fun testTheStaticLogOfSerilogInATopLevelProgram() {
        configure(
            "using Serilog;\n\nLog.Logger = new LoggerConfiguration().CreateLogger();\nvar orders = new System.Collections.Generic.List<int>();\nvar paid = 1.5m;\n" +
                "Log.Information(\"Loaded {Count} orders, paid total {Total:N2}\", orders.Count, paid);\nLog.Warning(\"Slow {Id}\");\n",
        )
        val highlighted = myFixture.doHighlighting()
        assertEquals(listOf("{Count}", "{Total:N2}", "{Id}"),
            highlighted.filter { it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM || it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM_2 }.map { it.text })
        assertTrue("the count of arguments is checked: $highlighted", highlighted.any { it.description?.contains("message template") == true && it.text == "{Id}" })
    }

    fun testHoles() {
        val holes = CSharpLoggerTemplates.holes("\"Order {OrderId} at {@When,10:HH:mm} {{escaped}} {\$Raw}\"")
        assertEquals(listOf("OrderId", "When", "Raw"), holes.map { it.name })
        assertEquals(emptyList<String>(), CSharpLoggerTemplates.holes("\"{} and {unclosed\"").map { it.name })
        assertEquals(listOf("A", "B"), CSharpLoggerTemplates.holes("\"\"\"\n  {A}\n  {B}\n  \"\"\"").map { it.name })
    }

    fun testPlaceholdersAreHighlighted() {
        assertEquals(listOf("{OrderId}", "{Total:N2}"), items("logger.LogInformation(\"Order {OrderId}: {Total:N2}\", order.Id, total);"))
        assertEquals("after an exception", listOf("{Id}"), items("logger.LogError(ex, \"Failed {Id}\", order.Id);"))
        assertEquals("Log with a level", listOf("{Id}"), items("logger.Log(LogLevel.Warning, \"Slow {Id}\", order.Id);"))
        assertEquals("Serilog", listOf("{Name}"), items("Serilog.Log.Information(\"User {Name}\", name);"))
        assertEquals("a scope", listOf("{OrderId}"), items("using var scope = logger.BeginScope(\"Order {OrderId}\", order.Id);"))
        assertEquals("another method", emptyList<String>(), items("Describe(\"Order {OrderId}\", order.Id);", "    void Describe(string s, object o) { }\n"))
        assertEquals("a second string is no template", emptyList<String>(), items("logger.LogInformation(\"{A}\" + name, \"{B}\");").filter { it == "{B}" })
    }

    fun testLoggerMessageAttribute() {
        val members = "    [LoggerMessage(EventId = 1, Level = LogLevel.Information, Message = \"Order {orderId} placed by {customer}\")]\n" +
            "    static partial void Placed(ILogger logger, long orderId, Exception error);\n"
        assertEquals(listOf("{orderId}", "{customer}"), items("", members))
        assertEquals(listOf("{customer}" to "No method parameter for the placeholder 'customer' of the message template"), warnings("", members))
    }

    fun testCountOfArgumentsIsChecked() {
        assertEquals(
            listOf("{Total}" to "No argument for the placeholder 'Total' of the message template"),
            warnings("logger.LogWarning(\"Order {OrderId}: {Total}\", order.Id);"),
        )
        assertEquals(listOf("total" to "The argument is not used in the message template"), warnings("logger.LogError(ex, \"Order {OrderId}\", order.Id, total);"))
        assertEquals("matches", emptyList<Pair<String, String>>(), warnings("logger.LogInformation(\"Order {OrderId}: {Total}\", order.Id, total);"))
        assertEquals("a repeated name may take one argument", emptyList<Pair<String, String>>(), warnings("logger.LogInformation(\"{Id} and {Id}\", order.Id);"))
        assertEquals("an array", emptyList<Pair<String, String>>(), warnings("logger.LogInformation(\"{A} {B}\", new object[] { 1, 2 });"))
        assertEquals("Serilog", 1, warnings("Serilog.Log.Warning(\"User {Name} {Age}\", name);").size)
    }

    fun testNamesOfTheArgumentsAfterABrace() {
        val names = lookup("logger.LogInformation(\"Order {<caret>\", order.Id, total);")
        assertEquals(listOf("OrderId", "Id", "Total"), names)
        assertEquals("the argument of the second placeholder first", "Total", lookup("logger.LogInformation(\"Order {OrderId}: {<caret>\", order.Id, total);").first())
        assertEquals("`GetX()` gives X", listOf("Name"), lookup("logger.LogInformation(\"{<caret>\", GetName());"))
    }

    fun testChoosingANameClosesThePlaceholder() {
        configure(body("logger.LogInformation(\"Order {<caret>\", order.Id);"))
        myFixture.completeBasic()
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "OrderId" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("\"Order {OrderId}\", order.Id"))
    }

    fun testPlaceholdersForFreeArgumentsInTheText() {
        assertEquals(listOf("{Total}"), lookup("logger.LogInformation(\"Order {OrderId} <caret>\", order.Id, total);"))
    }

    fun testParametersOfLoggerMessage() {
        val text = body("", "    [LoggerMessage(Level = LogLevel.Information, Message = \"Order {<caret>\")]\n    static partial void Placed(ILogger logger, long orderId, string customer);\n")
        configure(text)
        myFixture.completeBasic()
        assertEquals(listOf("orderId", "customer"), myFixture.lookupElementStrings)
    }

    private companion object {
        var counter = 0
    }
}
