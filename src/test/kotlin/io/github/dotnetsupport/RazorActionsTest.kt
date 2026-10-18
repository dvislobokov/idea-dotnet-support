package io.github.dotnetsupport

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.endpoints.EndpointsModel
import io.github.dotnetsupport.frameworks.razor.RazorAddUsing
import io.github.dotnetsupport.frameworks.razor.RazorEndpoints
import io.github.dotnetsupport.frameworks.razor.RazorExtractComponent
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorGotoRelatedProvider
import io.github.dotnetsupport.frameworks.razor.RazorLanguage
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperService
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * RAZOR_PLAN.md, R9: the actions and the navigation of Razor — Go to Related between a component's files, `@page` routes in Endpoints,
 * "Add @using" for an element named like a component of another namespace, Extract Component.
 */
class RazorActionsTest : BasePlatformTestCase() {
    fun testRelatedFilesOfAComponentAndAPage() {
        myFixture.addFileToProject("Rel/Counter.razor.cs", "partial class Counter { }")
        myFixture.addFileToProject("Rel/Counter.razor.css", "h1 { }")
        myFixture.addFileToProject("Rel/Index.cshtml.cs", "class IndexModel { }")
        val razor = myFixture.addFileToProject("Rel/Counter.razor", "<h1>x</h1>")
        val related = RazorGotoRelatedProvider().getItems(razor).map { it.element!!.containingFile.name }
        assertEquals(listOf("Counter.razor.cs", "Counter.razor.css"), related)
        val codeBehind = myFixture.psiManager.findFile(myFixture.findFileInTempDir("Rel/Counter.razor.cs")!!)!!
        assertEquals(listOf("Counter.razor", "Counter.razor.css"), RazorGotoRelatedProvider().getItems(codeBehind).map { it.element!!.containingFile.name })
        val page = myFixture.addFileToProject("Rel/Index.cshtml", "@page\n")
        assertEquals(listOf("Index.cshtml.cs"), RazorGotoRelatedProvider().getItems(page).map { it.element!!.containingFile.name })
        val plain = myFixture.addFileToProject("Rel/Other.cs", "class Other { }")
        assertEmpty(RazorGotoRelatedProvider().getItems(plain))
    }

    fun testTheRoutesOfComponentsAndPages() {
        assertEquals("/Products", RazorEndpoints.pageRoute("Pages/Products/Index.cshtml", null))
        assertEquals("/Products/Edit/{id:int}", RazorEndpoints.pageRoute("Pages/Products/Edit.cshtml", "{id:int}"))
        assertEquals("/", RazorEndpoints.pageRoute("Pages/Index.cshtml", null))
        assertEquals("/custom", RazorEndpoints.pageRoute("Pages/Any.cshtml", "/custom"))
        assertEquals("/Admin/Users", RazorEndpoints.pageRoute("Areas/Admin/Pages/Users.cshtml", null))
        assertNull("a view of MVC", RazorEndpoints.pageRoute("Views/Home/Index.cshtml", null))
        myFixture.addFileToProject("RouteApp/RouteApp.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>")
        myFixture.addFileToProject("RouteApp/Components/Pages/Counter.razor", "@page \"/counter\"\n@page \"/counter/{start:int}\"\n<h1>x</h1>")
        myFixture.addFileToProject("RouteApp/Pages/Privacy.cshtml", "@page\n@model PrivacyModel\n<h1>x</h1>")
        myFixture.addFileToProject("RouteApp/Views/Home/Index.cshtml", "<h1>no route</h1>")
        val directory = myFixture.findFileInTempDir("RouteApp")!!
        val routes = EndpointsModel.endpointsIn(directory).map { "${it.endpoint.method} ${it.endpoint.route} ${it.file.name}" }
        assertEquals(listOf("GET /counter Counter.razor", "GET /counter/{start:int} Counter.razor", "GET /Privacy Privacy.cshtml"), routes)
    }

    fun testTheLineOfANewUsing() {
        assertEquals(0, RazorAddUsing.insertionOffset("<h1>x</h1>"))
        val page = "@page \"/x\"\n@layout MainLayout\n<h1>x</h1>"
        assertEquals(page.indexOf("<h1>"), RazorAddUsing.insertionOffset(page))
        val usings = "@page \"/x\"\n@using A\n@using B\n\n<h1>x</h1>"
        assertEquals(usings.indexOf("\n\n") + 1, RazorAddUsing.insertionOffset(usings))
    }

    fun testAddUsingForAnElementNamedLikeAComponent() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val indexes = listOf("System.Runtime", "AspNetCoreComponents").map(CSharpUsingTypesTest::fixture)
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(indexes, "Web") }
        try {
            val projectFile = myFixture.addFileToProject("UsingApp/UsingApp.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Razor\"><PropertyGroup><TargetFramework>net10.0</TargetFramework><RootNamespace>UsingApp</RootNamespace></PropertyGroup></Project>").virtualFile
            AssemblyIndexService.getInstance(project).set(projectFile, indexes, isComplete = true)
            myFixture.addFileToProject("UsingApp/Widgets/Gauge.razor", "<p>gauge</p>\n")
            val page = myFixture.addFileToProject("UsingApp/Pages/Home.razor", "@page \"/\"\n<h1>x</h1>\n<Gauge />\n")
            myFixture.configureFromExistingVirtualFile(page.virtualFile)
            val service = RazorTagHelperService.getInstance(project)
            PlatformTestUtil.waitWithEventsDispatching("the tag helpers", { service.tagHelpers(projectFile); service.isSettledForTests(projectFile) }, 60)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val razor = page.viewProvider.getPsi(RazorLanguage) as RazorFile
            assertEquals(listOf("UsingApp.Widgets"), RazorAddUsing.namespacesOf(razor, "Gauge"))
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("<Gauge") + 2)
            val fix = myFixture.getAllQuickFixes().firstOrNull { it.text == "Add @using UsingApp.Widgets" }
            assertNotNull(myFixture.getAllQuickFixes().map { it.text }.toString(), fix)
            myFixture.launchAction(fix!!)
            assertEquals("@page \"/\"\n@using UsingApp.Widgets\n<h1>x</h1>\n<Gauge />\n", myFixture.editor.document.text)
        } finally {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
    }

    /**
     * The action end to end: the new file and the tag; a selection cutting a comment in two is refused; one Ctrl+Z takes both back (found in
     * the WSL sandbox, 0.1.283: the file was made outside the command, Undo left it and the tag).
     */
    fun testExtractComponentAndUndoIt() {
        val page = myFixture.addFileToProject("Extract/Page.razor", "@* a\n   comment *@\n<h1>x</h1>\n<div>\n    <p>a</p>\n</div>\n")
        myFixture.configureFromExistingVirtualFile(page.virtualFile)
        val editor = myFixture.editor
        val text = editor.document.text
        com.intellij.openapi.ui.TestDialogManager.setTestInputDialog { "Card" }
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK)
        try {
            // half the comment: refused, nothing made
            editor.selectionModel.setSelection(text.indexOf("comment"), text.indexOf("</h1>"))
            myFixture.testAction(io.github.dotnetsupport.frameworks.razor.RazorExtractComponentAction())
            assertNull(myFixture.findFileInTempDir("Extract/Card.razor"))
            assertEquals(text, editor.document.text)
            editor.selectionModel.setSelection(text.indexOf("<div>"), text.indexOf("</div>") + "</div>".length)
            myFixture.testAction(io.github.dotnetsupport.frameworks.razor.RazorExtractComponentAction())
            val card = myFixture.findFileInTempDir("Extract/Card.razor")
            assertNotNull(card)
            assertEquals("<div>\n    <p>a</p>\n</div>\n", com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(card!!)!!.text)
            assertEquals("@* a\n   comment *@\n<h1>x</h1>\n<Card />\n", editor.document.text)
            val fileEditor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor(page.virtualFile)
            com.intellij.openapi.command.undo.UndoManager.getInstance(project).undo(fileEditor)
            assertEquals("one Ctrl+Z takes the tag back", text, editor.document.text)
            assertNull("and the new file", myFixture.findFileInTempDir("Extract/Card.razor"))
        } finally {
            com.intellij.openapi.ui.TestDialogManager.setTestInputDialog(com.intellij.openapi.ui.TestInputDialog.DEFAULT)
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.DEFAULT)
        }
    }

    fun testTheMarkupOfAnExtractedComponent() {
        assertEquals("<div>\n    <p>a</p>\n</div>\n", RazorExtractComponent.markup("    <div>\n        <p>a</p>\n    </div>"))
        assertTrue(RazorExtractComponent.isComponentName("ProductCard"))
        assertFalse(RazorExtractComponent.isComponentName("card"))
        assertFalse(RazorExtractComponent.isComponentName("Product-Card"))
        myFixture.configureByText("Lines.razor", "<h1>x</h1>\n<div>\n    <p>a</p>\n</div>\n<p>after</p>\n")
        val document = myFixture.editor.document
        val text = document.text
        // a selection from inside the first line to the start of the line after the last
        val lines = RazorExtractComponent.lines(document, TextRange(text.indexOf("<div>") + 2, text.indexOf("<p>after")))
        assertEquals("<div>\n    <p>a</p>\n</div>", document.getText(lines))
    }
}
