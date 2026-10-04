package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.LocationPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpDeclaration
import io.github.dotnetsupport.roslyn.RoslynCtrlHoverReferenceProvider
import io.github.dotnetsupport.roslyn.RoslynSymbolItem
import io.github.dotnetsupport.roslyn.RoslynWorkspaceSymbolSupport
import org.eclipse.lsp4j.Position
import java.io.File

/**
 * Rows of Go to Symbol / Class (0.1.44): `area` gave four identical rows "Area … GoToBase.cs". The answer of the server is the one
 * captured from roslyn-language-server 5.12 on debug-playground (`roslyn/capture-5.12-symbols`), on the file it was taken from.
 */
class RoslynWorkspaceSymbolsTest : BasePlatformTestCase() {
    private fun row(item: NavigationItem): String {
        val presentation: ItemPresentation = item.presentation!!
        val location = presentation as LocationPresentation
        return presentation.presentableText + location.locationPrefix + presentation.locationString + location.locationSuffix
    }

    fun testSymbolsOfTheServerAreTheDeclarationsOfThePluginWithTheirTypes() {
        val source = File("debug-playground/Console/Editor/GoToBase.cs").readText().replace("\r\n", "\n")
        val file = myFixture.configureByText("GoToBase.cs", source)
        val document = myFixture.editor.document
        val capture = javaClass.getResourceAsStream("/roslyn/capture-5.12-symbols/01-workspace_symbol_area.json")!!.reader().use(JsonParser::parseReader).asJsonObject
        val rows = capture.getAsJsonArray("result").map { symbol ->
            val start = symbol.asJsonObject.getAsJsonObject("location").getAsJsonObject("range").getAsJsonObject("start")
            val offset = RoslynCtrlHoverReferenceProvider.offset(document, Position(start["line"].asInt, start["character"].asInt))!!
            val item = RoslynWorkspaceSymbolSupport.declarationNamedAt(file, offset)
            assertTrue("a declaration of the plugin at ${symbol}", item is CSharpDeclaration)
            assertEquals(RoslynWorkspaceSymbolSupport.containerOf(symbol.asJsonObject["containerName"].asString), item!!.presentation!!.locationString)
            row(item)
        }
        assertEquals(listOf("Area() IBaseShape", "Area() BaseShape", "Area() MiddleShape", "Area() GoToBase"), rows)
    }

    fun testTheTypeOfAMemberAndOfANestedTypeIsShownAsInJava() {
        val file = myFixture.configureByText("Nested.cs", "namespace Shop.Orders;\nclass Outer { class Inner { void Run(int count) { } } }\n")
        val text = file.text
        fun at(name: String) = RoslynWorkspaceSymbolSupport.declarationNamedAt(file, text.indexOf(name))!!
        assertEquals("Run(int count) Outer.Inner", row(at("Run")))
        assertEquals("Inner Shop.Orders.Outer", row(at("Inner")))
        assertEquals("Outer Shop.Orders", row(at("Outer")))
        assertNull("not at a name", RoslynWorkspaceSymbolSupport.declarationNamedAt(file, text.indexOf("count")))
    }

    fun testTheContainerOfTheServer() {
        assertEquals("BaseShape", RoslynWorkspaceSymbolSupport.containerOf("in BaseShape (project Console (net9.0))"))
        assertEquals("Outer.Inner", RoslynWorkspaceSymbolSupport.containerOf("in Outer.Inner (project Console (net9.0))"))
        assertNull("a type: the project only", RoslynWorkspaceSymbolSupport.containerOf("project Console (net9.0)"))
        assertNull(RoslynWorkspaceSymbolSupport.containerOf(null))
        val platform = object : NavigationItem {
            override fun getName() = "Area"
            override fun getPresentation() = object : ItemPresentation {
                override fun getPresentableText() = "Area"
                override fun getIcon(unused: Boolean) = null
                override fun getLocationString() = "GoToBase.cs"
            }
        }
        assertEquals("Area BaseShape", row(RoslynSymbolItem(platform, "BaseShape")))
    }
}
