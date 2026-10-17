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
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpGotoDeclarationHandler
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * RAZOR_PLAN.md, R3a: the virtual C# document of a Razor file is one injection of the C# pieces of the compiler's tree ([RazorCSharpDocument]);
 * the plugin's C# works through it in the Razor editor — completion after `@` and `@Model.`, Go to Declaration from the markup to `@code`
 * and to a `.cs` of the project.
 */
class RazorCSharpDocumentTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        for (feature in listOf(CSharpFeature.COMPLETION, CSharpFeature.NAVIGATION, CSharpFeature.DIAGNOSTICS)) settings.setSource(feature, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
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
        val expected = listOf(
            "using System.Globalization;",
            "using global::System;",
            "using global::System.Collections.Generic;",
            "using global::System.Linq;",
            "using global::System.Threading.Tasks;",
            "using global::Microsoft.AspNetCore.Components;",
            "public partial class Counter : global::Microsoft.AspNetCore.Components.ComponentBase",
            "{",
            "#pragma warning disable 0414",
            "private static object __o = null;",
            "#pragma warning restore 0414",
            "[global::Microsoft.AspNetCore.Components.InjectAttribute] private ILogger<Counter> Logger { get; set; }",
            "protected override void BuildRenderTree(global::Microsoft.AspNetCore.Components.Rendering.RenderTreeBuilder __builder)",
            "{",
            "__o = currentCount;",
            "__o = (currentCount + 1);",
            "__o = DateTime.Now.ToString(\"t\", CultureInfo.InvariantCulture);",
            "if (currentCount > 3)",
            "{",
            "}",
            "}",
            "",
            "    [Parameter] public int Step { get; set; }",
            "    private int currentCount;",
            "",
            "    private void Increment()",
            "    {",
            "        currentCount += Step;",
            "    }",
            "",
            "}",
            "",
        ).joinToString("\n")
        assertEquals(expected, RazorCSharpDocument.text(file))
    }

    fun testTheDocumentOfAView() {
        val file = razor(myFixture.configureByText("Index.cshtml", "@model Shop.Order\n@{\n    var count = Model.Lines;\n}\n<p>@Model.Id — @count</p>\n@section Scripts {\n<script>console.log(\"@count\");</script>\n}\n@functions {\n    string Title() => \"x\";\n}\n"))
        val text = RazorCSharpDocument.text(file)
        assertTrue(text, text.contains("public partial class Index : global::Microsoft.AspNetCore.Mvc.Razor.RazorPage<Shop.Order>\n{"))
        assertTrue(text, text.contains("public override async global::System.Threading.Tasks.Task ExecuteAsync()\n{\n\n    var count = Model.Lines;\n\n__o = Model.Id;\n__o = count;\nDefineSection(\"Scripts\", async () => {\n__o = count;\n});\n}\n"))
        assertTrue(text, text.contains("\n    string Title() => \"x\";\n\n}\n"))
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
