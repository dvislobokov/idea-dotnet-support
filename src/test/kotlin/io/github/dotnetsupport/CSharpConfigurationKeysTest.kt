package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpConfigurationKeys
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpServiceRegistrations
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Configuration keys and service registrations (task 3.9 of docs/COMPLETION_GAPS.md, 0.1.93): the keys of the project's
 * `appsettings*.json` in `configuration["…"]`, `GetSection`, `GetValue<T>`, `GetConnectionString`; the implementations of the service
 * in `AddScoped<IService, |>`, the solution's classes first.
 */
class CSharpConfigurationKeysTest : BasePlatformTestCase() {
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

    private val appsettings = """
        {
          "ConnectionStrings": { "Shop": "Host=localhost", "Cache": "redis:6379" },
          "Serilog": { "MinimumLevel": { "Default": "Information" } },
          "AllowedHosts": "*",
          "Endpoints": [ { "Url": "http://a" } ]
        }
    """.trimIndent()

    /** A project with appsettings.json and appsettings.Development.json; the code at `<caret>` in its Program.cs. */
    private fun lookup(statements: String): List<String> {
        configureProject(statements)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun configureProject(statements: String) {
        val name = "Cfg${counter++}"
        myFixture.addFileToProject("$name/$name.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>")
        myFixture.addFileToProject("$name/appsettings.json", appsettings)
        myFixture.addFileToProject("$name/appsettings.Development.json", "{ \"Feature\": { \"Enabled\": true } }")
        val code = "using System.Collections.Generic;\nclass Program\n{\n    void Run(IConfiguration configuration, Dictionary<string, string> map)\n    {\n        $statements\n    }\n}\n"
        val offset = code.indexOf("<caret>")
        val file = myFixture.addFileToProject("$name/Program.cs", code.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(offset)
    }

    fun testKeysOfJson() {
        val keys = CSharpConfigurationKeys.keys(appsettings)
        assertEquals(
            listOf("ConnectionStrings", "ConnectionStrings:Shop", "ConnectionStrings:Cache", "Serilog", "Serilog:MinimumLevel", "Serilog:MinimumLevel:Default",
                "AllowedHosts", "Endpoints", "Endpoints:0", "Endpoints:0:Url"),
            keys.keys.toList(),
        )
        assertEquals("Information", keys["Serilog:MinimumLevel:Default"])
        assertEquals(emptyMap<String, String>(), CSharpConfigurationKeys.keys("{ broken"))
    }

    fun testKeysInAnIndexer() {
        val items = lookup("var value = configuration[\"<caret>\"];")
        assertTrue(items.toString(), items.containsAll(listOf("ConnectionStrings:Shop", "Serilog:MinimumLevel:Default", "AllowedHosts", "Feature:Enabled")))
        assertFalse("the shallow keys first: $items", ':' in items.first())
        assertEquals("not a configuration", emptyList<String>(), lookup("var value = map[\"<caret>\"];").filter { it.startsWith("Serilog") })
    }

    fun testConnectionStrings() {
        assertEquals(setOf("Shop", "Cache"), lookup("var cs = configuration.GetConnectionString(\"<caret>\");").toSet())
    }

    fun testKeysOfASection() {
        val items = lookup("var level = configuration.GetSection(\"Serilog\")[\"<caret>\"];")
        assertTrue(items.toString(), items.containsAll(listOf("MinimumLevel", "MinimumLevel:Default")))
        assertFalse(items.contains("AllowedHosts"))
        assertTrue(lookup("var on = configuration.GetValue<bool>(\"Feature:<caret>\");").contains("Feature:Enabled"))
    }

    fun testChoosingAKey() {
        configureProject("var level = configuration.GetSection(\"Serilog:Min<caret>\");")
        myFixture.completeBasic()
        val element = myFixture.lookupElements?.first { it.lookupString == "Serilog:MinimumLevel" }
        if (element != null) {
            myFixture.lookup.currentItem = element
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("GetSection(\"Serilog:MinimumLevel\")"))
    }

    // ---- services

    fun testPlaceOfARegistration() {
        fun place(text: String) = CSharpServiceRegistrations.placeAt(text, text.length)
        assertEquals("IOrderService", place("services.AddScoped<IOrderService, ")?.service)
        assertEquals("IRepository", place("services.TryAddSingleton<Shop.IRepository<Order>, Rep")?.service)
        assertEquals("Rep", place("services.TryAddSingleton<Shop.IRepository<Order>, Rep")?.prefix)
        assertEquals("IClock", place("builder.Services.AddKeyedTransient<IClock,")?.service)
        assertNull(place("services.AddScoped<IOrderService>("))
        assertNull(place("var map = new Dictionary<string, "))
    }

    fun testImplementationsFirst() {
        val n = counter++
        val text = "namespace Svc$n;\ninterface IOrderService$n { }\nclass OrderService$n : IOrderService$n { }\nclass CachedOrderService$n : OrderService$n { }\n" +
            "abstract class OrderServiceBase$n : IOrderService$n { }\nclass Unrelated$n { }\n" +
            "class Startup$n\n{\n    void Configure(IServiceCollection services)\n    {\n        services.AddScoped<IOrderService$n, <caret>>();\n    }\n}\n"
        myFixture.configureByText("Services$n.cs", text)
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertEquals(items.toString(), setOf("OrderService$n", "CachedOrderService$n"), items.take(2).toSet())
        assertEquals("once", 1, items.count { it == "OrderService$n" })
        assertFalse(items.take(2).contains("OrderServiceBase$n"))
    }

    fun testImplementationOfAnotherNamespaceAddsItsUsing() {
        val n = counter++
        myFixture.addFileToProject("Impl$n.cs", "namespace Shop$n.Impl { public class FastClock$n : Shop$n.IClock$n { } }")
        val text = "namespace Shop$n { public interface IClock$n { } }\nnamespace Shop$n.App\n{\n    class Startup\n    {\n        void Configure(IServiceCollection services)\n        {\n" +
            "            services.AddSingleton<IClock$n, <caret>>();\n        }\n    }\n}\n"
        myFixture.configureByText("Clock$n.cs", text)
        myFixture.completeBasic()
        val element = myFixture.lookupElements?.firstOrNull { it.lookupString == "FastClock$n" } ?: error("no FastClock$n in ${myFixture.lookupElementStrings}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val result = myFixture.editor.document.text
        assertTrue(result, result.startsWith("using Shop$n.Impl;"))
        assertTrue(result, result.contains("AddSingleton<IClock$n, FastClock$n>()"))
    }

    /** On the files of `debug-playground/ShopApi` as they are: its appsettings.json and the scenario of the playground. */
    fun testShopApiPlayground() {
        val root = java.io.File("debug-playground/ShopApi")
        val name = "ShopCopy${counter++}"
        myFixture.addFileToProject("$name/ShopApi.csproj", java.io.File(root, "ShopApi.csproj").readText())
        myFixture.addFileToProject("$name/appsettings.json", java.io.File(root, "appsettings.json").readText())
        val scenario = java.io.File(root, "Playground/AspNetCompletion.cs").readText().replace("\r\n", "\n")
        fun complete(from: String, to: String): List<String> {
            val code = scenario.replace(from, to)
            assertTrue(from, code != scenario)
            val file = myFixture.addFileToProject("$name/Playground/AspNetCompletion${counter++}.cs", code.replace("<caret>", ""))
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            myFixture.editor.caretModel.moveToOffset(code.indexOf("<caret>"))
            myFixture.completeBasic()
            return myFixture.lookupElementStrings.orEmpty()
        }
        val keys = complete("return configuration.GetValue<string>(\"AllowedHosts\");", "return configuration[\"<caret>\"];")
        assertTrue(keys.toString(), keys.containsAll(listOf("AllowedHosts", "ConnectionStrings", "ConnectionStrings:Shop", "Serilog:MinimumLevel:Default", "OTEL_EXPORTER_OTLP_ENDPOINT")))
        assertEquals(listOf("Shop"), complete("return configuration.GetValue<string>(\"AllowedHosts\");", "return configuration.GetConnectionString(\"<caret>\");"))
        val implementations = complete("services.AddSingleton<IPriceCalculator, StandardPriceCalculator>();", "services.AddScoped<IPriceCalculator, <caret>>();")
        assertEquals(implementations.toString(), setOf("DiscountPriceCalculator", "StandardPriceCalculator"), implementations.take(2).toSet())
    }

    private companion object {
        var counter = 0
    }
}
