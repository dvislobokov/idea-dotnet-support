package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/** 0.1.93: the list opens by itself after `{` of a message template and of a route, after `:` of a route parameter, after `AddScoped<I, `. */
class CSharpAspNetStringsAutoPopupTest : BasePlatformTestCase() {
    private lateinit var tester: CompletionAutoPopupTester

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun typed(text: String, type: String): List<String> {
        var items: List<String> = emptyList()
        tester.runWithAutoPopupEnabled {
            myFixture.configureByText("AspNetPopup${counter++}.cs", text)
            tester.typeWithPauses(type)
            items = myFixture.lookupElementStrings.orEmpty()
        }
        return items
    }

    private fun body(statement: String): String =
        "interface IClock { }\nclass SystemClock : IClock { }\nclass Sample\n{\n    void Run(ILogger logger, Order order, WebApplication app, IServiceCollection services)\n    {\n        $statement\n    }\n}\nclass Order { public long Id; }\n"

    fun testABraceOfATemplate() {
        assertEquals(listOf("OrderId", "Id"), typed(body("logger.LogInformation(\"Order <caret>\", order.Id);"), "{"))
    }

    fun testABraceOfARoute() {
        assertEquals(listOf("id"), typed(body("app.MapGet(\"/orders/<caret>\", (long id) => id);"), "{"))
    }

    fun testAColonOfARouteParameter() {
        assertTrue(typed(body("app.MapGet(\"/orders/{id<caret>}\", (long id) => id);"), ":").contains("long"))
    }

    fun testARegistration() {
        assertEquals("SystemClock", typed(body("services.AddSingleton<IClock,<caret>"), " ").firstOrNull())
    }

    fun testNothingInOtherStrings() {
        tester.runWithAutoPopupEnabled {
            myFixture.configureByText("AspNetPopupNone.cs", body("System.Console.WriteLine(\"Order <caret>\", order.Id);"))
            tester.typeWithPauses("{")
            assertNull(myFixture.lookup)
        }
    }

    private companion object {
        var counter = 0
    }
}
