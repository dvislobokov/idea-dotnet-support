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

    /**
     * RAZOR_PLAN.md, R4: the inlay hints of the C# of a Razor file are shown in the markup and in `@code` — at text the user wrote only:
     * `@currentCount` is `__builder.AddContent(3, currentCount)` of the generator, whose `sequence:` / `textContent:` are not the user's.
     */
    fun testInlayHintsOfTheCSharpOfTheFile() {
        val text = """
            <p>Count: @currentCount, sum @Sum(2, 3)</p>
            @code {
                private int currentCount;
                private int Sum(int left, int right) => left + right;
                private void Twice() { var total = Sum(currentCount, 1); }
            }
        """.trimIndent()
        val file = razor(myFixture.configureByText("Hints.razor", text))
        val options = io.github.dotnetsupport.lang.NativeCSharpInlayHints.Options(hideObvious = false)
        fun hints(kind: io.github.dotnetsupport.lang.NativeCSharpInlayHints.Kind) =
            io.github.dotnetsupport.frameworks.razor.RazorInlayHintsProvider.hintsOf(file, options, kind).map { (offset, hint) -> hint.text to text.substring(offset).take(12) }
        val parameters = hints(io.github.dotnetsupport.lang.NativeCSharpInlayHints.Kind.PARAMETER)
        assertEquals(listOf("left:" to "2, 3)</p>\n@c", "right:" to "3)</p>\n@code", "left:" to "currentCount", "right:" to "1); }\n}"), parameters.map { it.first to it.second })
        assertTrue("no hint of the generator's glue: $parameters", parameters.none { it.second.startsWith("currentCount, s") })
        val types = hints(io.github.dotnetsupport.lang.NativeCSharpInlayHints.Kind.TYPE)
        assertTrue(types.toString(), types.any { it.first.contains("int") && it.second.startsWith("total = Sum") })
    }

    /**
     * The C# of a Razor file its project leaves out is not checked, as the compiler does not compile it: the views nopCommerce's build copies
     * into `Nop.Web/Plugins` (`<Content Remove="Plugins\**" />`) gave 467 errors (the harness, 0.1.289). The same code elsewhere is checked.
     */
    fun testTheCSharpOfAnExcludedRazorFileIsNotChecked() {
        myFixture.addFileToProject("Shop89/Shop89.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"><ItemGroup><Content Remove=\"Plugins\\**\" /></ItemGroup></Project>")
        val code = "@{\n    int x = \"a\";\n}\n"
        fun problems(path: String): List<String> {
            val file = razor(myFixture.addFileToProject(path, code))
            val host = RazorCSharpDocument.places(file).first().host
            val injected = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first as io.github.dotnetsupport.lang.CSharpFile
            return io.github.dotnetsupport.lang.semantic.CSharpSemanticChecks(io.github.dotnetsupport.lang.semantic.CSharpSemanticSession(project).resolver(injected)).run().map { it.code }
        }
        assertTrue("a view of the project", "CS0029" in problems("Shop89/Views/Home/Index.cshtml"))
        val (excluded, trace) = io.github.dotnetsupport.lang.semantic.SemanticTrace.collectForTests { problems("Shop89/Plugins/Misc.Forums/Views/Index.cshtml") }
        assertEmpty("a view the project leaves out", excluded)
        assertTrue(trace.toString(), trace.any { "the build does not compile it" in it })
    }

    /**
     * An `@inject` between a code block and the markup does not split the render method: the markup still sees the locals of the block
     * (nopCommerce's ProductReview/List.cshtml was CS0103 for `hideSearchBlock`, the harness, 0.1.289).
     */
    fun testAnInjectAfterACodeBlockKeepsOneRenderMethod() {
        val text = "@{\n    const string b = \"x\";\n    var c = 1;\n}\n@inject System.IServiceProvider sp\n<div class=\"@(c > 0 ? \"y\" : \"\")\" data-x=\"@b\"></div>\n"
        val file = razor(myFixture.configureByText("Inject89.cshtml", text))
        val document = RazorCSharpDocument.text(file)
        assertFalse(document, "ExecuteAsync__2" in document)
        assertTrue(document, document.indexOf("var c = 1;") < document.indexOf("c > 0") && document.indexOf("c > 0") < document.indexOf("IServiceProvider sp"))
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
