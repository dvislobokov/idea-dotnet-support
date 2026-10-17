package io.github.dotnetsupport

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFile
import io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument
import io.github.dotnetsupport.frameworks.razor.RazorCodeLiteral
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorLanguage
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpGotoDeclarationHandler
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * RAZOR_PLAN.md, R3a: the virtual C# document of a Razor file is one injection of the C# pieces of the compiler's tree ([RazorCSharpDocument]);
 * the plugin's C# works through it in the Razor editor — completion after `@` and `@Model.`, Go to Declaration from the markup to `@code`
 * and to a `.cs` of the project.
 */
class RazorCSharpDocumentTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private val counter = """
        @page "/counter"
        @using System.Globalization
        @inject ILogger<Counter> Logger

        <h1 class="title">Count: @currentCount</h1>
        <p>@(currentCount + 1) at @DateTime.Now.ToString("t", CultureInfo.InvariantCulture)</p>

        @if (currentCount > 3)
        {
            <p>Many</p>
        }

        @code {
            [Parameter] public int Step { get; set; }
            private int currentCount;

            private void Increment()
            {
                currentCount += Step;
            }
        }
    """.trimIndent()

    private fun razor(file: PsiFile): RazorFile = file.viewProvider.getPsi(RazorLanguage) as RazorFile

    fun testTheDocumentOfAComponent() {
        val file = razor(myFixture.configureByText("Counter.razor", counter))
        val text = RazorCSharpDocument.text(file)
        // the places glued are the generated code of the compiler's pipeline, the Razor text of the hosts as written
        assertEquals(RazorCSharpDocument.generate(file)!!.code, text)
        for (expected in listOf(
            "[global::Microsoft.AspNetCore.Components.RouteAttribute(",
            "using System.Globalization",
            "public partial class Counter : global::Microsoft.AspNetCore.Components.ComponentBase",
            "protected override void BuildRenderTree(global::Microsoft.AspNetCore.Components.Rendering.RenderTreeBuilder __builder)",
            "__builder.AddContent(3, currentCount",
            "__builder.AddContent(6, currentCount + 1",
            "DateTime.Now.ToString(\"t\", CultureInfo.InvariantCulture)",
            "if (currentCount > 3)",
            "    [Parameter] public int Step { get; set; }",
            "[global::Microsoft.AspNetCore.Components.InjectAttribute] private ",
        )) assertTrue(text, text.contains(expected))
        // the hosts: the C# of the file, each once, in the order of the file (the layout pass puts `@inject` before the render method, which
        // the markup after it continues in a second method)
        val places = RazorCSharpDocument.places(file)
        val hosts = places.map { it.range.substring(it.host.text) }
        assertEquals(hosts.toString(), hosts.size, hosts.toSet().size)
        assertTrue(hosts.toString(), "currentCount" in hosts && "ILogger<Counter>" in hosts && "Logger" in hosts)
        val offsets = places.map { it.host.textRange.startOffset + it.range.startOffset }
        assertEquals(offsets.toString(), offsets.sorted(), offsets)
        assertTrue(text, text.indexOf("ILogger<Counter>") < text.indexOf("void BuildRenderTree("))
    }

    fun testTheDocumentOfAView() {
        val view = listOf(
            "@model Shop.Order", "@{", "    var count = Model.Lines;", "}", "<p>@Model.Id - @count</p>", "@section Scripts {",
            "<script>console.log(\"@count\");</script>", "}", "@functions {", "    string Title() => \"x\";", "}", "",
        ).joinToString("\n")
        val file = razor(myFixture.configureByText("Index.cshtml", view))
        val text = RazorCSharpDocument.text(file)
        assertEquals(RazorCSharpDocument.generate(file)!!.code, text)
        assertTrue(text, text.contains("internal sealed class Index : global::Microsoft.AspNetCore.Mvc.Razor.RazorPage<\n#nullable restore\n#line (1,8)-(1,18) \"/Index.cshtml\"\nShop.Order\n"))
        assertTrue(text, text.contains("public async override global::System.Threading.Tasks.Task ExecuteAsync()"))
        assertTrue(text, text.contains("    var count = Model.Lines;\n"))
        assertTrue(text, text.contains("Write(Model.Id\n"))
        assertTrue(text, text.contains("DefineSection(\"Scripts\", async() => {"))
        assertTrue(text, text.contains("    string Title() => \"x\";\n"))
    }

    fun testTheInjectedFileIsTheDocument() {
        val file = razor(myFixture.configureByText("Counter.razor", counter))
        val host = PsiTreeUtil.findChildrenOfType(file, RazorCodeLiteral::class.java).first { it.text == "currentCount" }
        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)?.map { it.first }.orEmpty()
        assertEquals(1, injected.size)
        val csharp = injected.single() as CSharpFile
        assertEquals(RazorCSharpDocument.text(file), csharp.text)
        assertNotNull(csharp.compilationUnit)
        // the same file for every host: one document
        val other = PsiTreeUtil.findChildrenOfType(file, RazorCodeLiteral::class.java).first { it.text.startsWith("if (currentCount") }
        assertSame(csharp, InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(other)?.single()?.first)
    }

    fun testCompletionOfAMemberOfCodeInTheMarkup() {
        myFixture.configureByText("Counter.razor", counter.replace("<p>Many</p>", "<p>@cur<caret></p>"))
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings
        assertNotNull(items)
        assertTrue(items.toString(), "currentCount" in items!!)
    }

    fun testCompletionAfterModelOfAView() {
        myFixture.addFileToProject("Order.cs", "namespace Shop { public class Order { public int Id { get; set; } public string Customer { get; set; } } }")
        // the base class of a view, as the sources of the test project (the fixture assemblies hold no ASP.NET Core)
        myFixture.addFileToProject("RazorPage.cs", "namespace Microsoft.AspNetCore.Mvc.Razor { public abstract class RazorPage<TModel> { public TModel Model { get; } public abstract System.Threading.Tasks.Task ExecuteAsync(); } }")
        myFixture.configureByText("Index.cshtml", "@model Shop.Order\n<p>@Model.<caret></p>\n")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings
        assertNotNull(items)
        assertTrue(items.toString(), "Customer" in items!! && "Id" in items)
    }

    fun testGotoDeclarationFromTheMarkupToCodeAndToACsFile() {
        myFixture.addFileToProject("Order.cs", "namespace Shop { public class Order { public int Id { get; set; } } }")
        myFixture.configureByText("Counter.razor", counter.replace("<p>Many</p>", "<p>@currentCount @(new Shop.Order().Id)</p>"))
        val text = myFixture.editor.document.text
        fun targets(at: String): List<String> {
            val offset = text.indexOf(at) + 1
            assertTrue(at, offset > 0)
            // the handler on the injected leaf, and the action as the IDE runs it: on the editor of the injected fragment at the caret
            val leaf = InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, offset)
            assertNotNull("an injected element at '$at'", leaf)
            val direct = CSharpGotoDeclarationHandler().getGotoDeclarationTargets(leaf, leaf!!.textOffset, null).orEmpty()
            myFixture.editor.caretModel.moveToOffset(offset)
            val editor = InjectedLanguageEditorUtil.getEditorForInjectedLanguageNoCommit(myFixture.editor, myFixture.file)
            assertTrue("the editor at '$at' is the injected one", editor is EditorWindow)
            val action = GotoDeclarationAction.findAllTargetElements(project, editor, editor.caretModel.offset)
            assertEquals(direct.map { it.text }, action.map { it.text })
            return action.map { it.containingFile.name + ":" + it.text.lines().first() }
        }
        assertEquals(listOf("Counter.razor:private int currentCount"), targets("currentCount @(new").map { it.substringBefore(";") })
        assertEquals(listOf("Order.cs:public int Id"), targets("Id)</p>").map { it.substringBefore(" {") })
    }
}
