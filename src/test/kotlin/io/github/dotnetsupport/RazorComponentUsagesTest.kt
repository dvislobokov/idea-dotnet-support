package io.github.dotnetsupport

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorLanguage
import io.github.dotnetsupport.frameworks.razor.RazorMarkupFindUsagesHandlerFactory
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperEditor
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperService
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpSolutionRename
import io.github.dotnetsupport.lang.semantic.CSharpInjections
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch

/**
 * RAZOR_PLAN.md, R5e part 2: Find Usages and rename of components and parameters — their tags and attributes in the markup, the `.razor` file
 * of a component with the files nested under it, the C# of the markup searched as C# (CSharpInjections: the document of a Razor file, without
 * the glue between its pieces). On the stand-in of Microsoft.AspNetCore.Components (`AspNetCoreComponents.dnix`), as RazorTagHelpersTest.
 */
class RazorComponentUsagesTest : BasePlatformTestCase() {
    private lateinit var projectFile: VirtualFile
    private var round = 0
    private lateinit var dir: String

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val indexes = listOf("System.Runtime", "AspNetCoreComponents").map(CSharpUsingTypesTest::fixture)
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(indexes, "Web") }
        NativeCSharpSolutionRename.answerHierarchyForTests(false)
    }

    override fun tearDown() {
        try {
            NativeCSharpSolutionRename.answerHierarchyForTests(null)
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A project of its own per test (the light project is shared): `Greeting.razor` with a code-behind and a stylesheet, `Panel.cs`, `Home.razor`. */
    private fun solution(generated: Boolean = false) {
        dir = "Web${round++}${getTestName(false)}"
        val indexes = listOf("System.Runtime", "AspNetCoreComponents").map(CSharpUsingTypesTest::fixture)
        projectFile = myFixture.addFileToProject("$dir/Web.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Razor\"><PropertyGroup><TargetFramework>net10.0</TargetFramework><RootNamespace>Web</RootNamespace></PropertyGroup></Project>").virtualFile
        AssemblyIndexService.getInstance(project).set(projectFile, indexes, isComplete = true)
        myFixture.addFileToProject("$dir/_Imports.razor", "@using Microsoft.AspNetCore.Components.Web\n")
        myFixture.addFileToProject("$dir/Components/Greeting.razor", "<h1>@Title</h1>\n@ChildContent\n\n@code {\n    [Parameter] public string Title { get; set; } = \"\";\n    [Parameter] public EventCallback<string> TitleChanged { get; set; }\n    [Parameter] public RenderFragment? ChildContent { get; set; }\n    string Upper => Title.ToUpper();\n}\n")
        myFixture.addFileToProject("$dir/Components/Greeting.razor.cs", "namespace Web.Components;\npublic partial class Greeting\n{\n    public int Shown { get; set; }\n}\n")
        myFixture.addFileToProject("$dir/Components/Greeting.razor.css", "h1 { color: red; }\n")
        myFixture.addFileToProject("$dir/Components/Panel.cs", "using Microsoft.AspNetCore.Components;\nnamespace Web.Components;\npublic class Panel : ComponentBase\n{\n    [Parameter] public int Size { get; set; }\n}\n")
        myFixture.addFileToProject("$dir/Components/Totals.cs", "namespace Web.Components;\npublic static class Totals\n{\n    public static int Count = 1;\n}\n")
        myFixture.addFileToProject("$dir/Pages/Home.razor", HOME)
        if (generated) {
            // the Razor generator's output as the helper leaves it: the C# files resolve the component's members there
            myFixture.addFileToProject("$dir/obj/generated/Microsoft.CodeAnalysis.Razor.Compiler/Components_Greeting_razor.g.cs",
                "using Microsoft.AspNetCore.Components;\nnamespace Web.Components\n{\n    public partial class Greeting : ComponentBase\n    {\n        [Parameter] public string Title { get; set; } = \"\";\n    }\n}\n")
            myFixture.addFileToProject("$dir/Components/Use.cs", "namespace Web.Components;\npublic static class Use\n{\n    public static System.Type Component = typeof(Greeting);\n    public static string Parameter = nameof(Greeting.Title);\n}\n")
        }
        myFixture.configureFromTempProjectFile("$dir/Pages/Home.razor")
        val service = RazorTagHelperService.getInstance(project)
        service.tagHelpers(projectFile)
        PlatformTestUtil.waitWithEventsDispatching("the tag helpers of the project", { service.tagHelpers(projectFile) != null }, 60)
    }

    private fun text(path: String): String = myFixture.findFileInTempDir("$dir/$path")?.let { FileDocumentManager.getInstance().getDocument(it)!!.text } ?: error("no $path")

    private fun razor(path: String): RazorFile = myFixture.psiManager.findFile(myFixture.findFileInTempDir("$dir/$path")!!)!!.viewProvider.getPsi(RazorLanguage) as RazorFile

    /** The markup's token at the [occurrence]-th [word] of Home.razor. */
    private fun markup(word: String, occurrence: Int = 0): PsiElement {
        var at = -1
        repeat(occurrence + 1) { at = myFixture.editor.document.text.indexOf(word, at + 1) }
        assertTrue(word, at >= 0)
        return myFixture.file.viewProvider.findElementAt(at, HTMLLanguage.INSTANCE)!!
    }

    private fun caret(word: String, occurrence: Int = 0) {
        var at = -1
        repeat(occurrence + 1) { at = myFixture.editor.document.text.indexOf(word, at + 1) }
        myFixture.editor.caretModel.moveToOffset(at + 1)
    }

    /** The texts of the usages of [element]: file name and the text at the usage. */
    private fun usages(element: PsiElement): List<String> = ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).findAll().map { reference ->
        val host = CSharpInjections.hostFile(reference.element.containingFile)!!
        val range = CSharpInjections.toHost(reference.element.containingFile, reference.rangeInElement.shiftRight(reference.element.textRange.startOffset))
        "${host.name}:${range.substring(host.viewProvider.document!!.text)}"
    }.sorted()

    fun testFindUsagesOfAComponentAreItsTags() {
        solution()
        val greeting = razor("Components/Greeting.razor")
        assertEquals(listOf("Home.razor:Greeting", "Home.razor:Greeting", "Home.razor:Greeting", "Home.razor:Greeting"), usages(greeting))
        // a component of a C# class: from its class
        val panel = RazorTagHelperEditor.markupTarget(markup("Panel")) as CSharpBaseTypeDeclaration
        assertEquals("Panel", panel.identifier?.text)
        assertEquals(listOf("Home.razor:Panel"), usages(panel).filter { it.startsWith("Home") })
    }

    fun testFindUsagesOfAParameterAreItsAttributesAndItsCSharp() {
        solution()
        // a parameter of `@code`: its C# document's property; the attributes, `@bind-Title`, the C# of the markup of its own file, not the glue
        val title = RazorTagHelperEditor.markupTarget(markup("Title")) as CSharpPropertyDeclaration
        assertTrue(title.containingFile.name, CSharpInjections.hostFile(title.containingFile)?.name == "Greeting.razor")
        assertEquals(listOf("Greeting.razor:Title", "Greeting.razor:Title", "Home.razor:Title", "Home.razor:Title", "Home.razor:Title"), usages(title))
        // a parameter of a C# class
        val size = RazorTagHelperEditor.markupTarget(markup("Size")) as CSharpPropertyDeclaration
        assertEquals(listOf("Home.razor:Size"), usages(size))
        // a child content tag is a usage of its parameter
        val childContent = RazorTagHelperEditor.markupTarget(markup("ChildContent")) as CSharpPropertyDeclaration
        assertEquals("ChildContent", childContent.identifier?.text)
        assertTrue(usages(childContent).toString(), usages(childContent).count { it == "Home.razor:ChildContent" } == 2)
    }

    fun testAltF7OnTheMarkupSearchesTheComponentOrTheParameter() {
        solution()
        val factory = RazorMarkupFindUsagesHandlerFactory()
        val tag = markup("Greeting").parent as XmlTag
        assertTrue(factory.canFindUsages(tag))
        assertEquals("Greeting.razor", (factory.createFindUsagesHandler(tag, false)!!.primaryElements.single() as? com.intellij.psi.PsiFile)?.name)
        // the platform's target at an attribute: the attribute (the XML evaluator gives none), then the parameter
        caret("Size")
        val target = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.editor, com.intellij.codeInsight.TargetElementUtil.getInstance().allAccepted)
        assertTrue("$target", target is XmlAttribute && factory.canFindUsages(target))
        assertEquals("Size", (factory.createFindUsagesHandler(target!!, false)!!.primaryElements.single() as CSharpPropertyDeclaration).identifier?.text)
        // an HTML element is no component
        assertFalse(factory.canFindUsages(markup("div").parent))
    }

    fun testUsagesOfTheCSharpOfTheSolutionInTheMarkup() {
        solution()
        val totals = myFixture.psiManager.findFile(myFixture.findFileInTempDir("$dir/Components/Totals.cs")!!) as CSharpFile
        val count = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(totals, io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration::class.java).single()
        val target = CSharpSolutionSearch.targetOf(count)!!
        val found = CSharpSolutionSearch.usages(project, target, GlobalSearchScope.projectScope(project)).map { CSharpInjections.hostFile(it.leaf.containingFile)?.name }
        assertEquals(listOf("Home.razor", "Home.razor"), found)
    }

    fun testRenameAComponentFromItsTag() {
        solution()
        caret("Greeting")
        myFixture.renameElementAtCaretUsingHandler("Welcome")
        assertNull(myFixture.findFileInTempDir("$dir/Components/Greeting.razor"))
        assertNotNull(myFixture.findFileInTempDir("$dir/Components/Welcome.razor"))
        assertNotNull("the nested files follow", myFixture.findFileInTempDir("$dir/Components/Welcome.razor.cs"))
        assertNotNull(myFixture.findFileInTempDir("$dir/Components/Welcome.razor.css"))
        assertTrue(text("Components/Welcome.razor.cs"), text("Components/Welcome.razor.cs").contains("public partial class Welcome"))
        val home = text("Pages/Home.razor")
        assertTrue(home, home.contains("<Welcome Title=\"a\">\n    <ChildContent>x</ChildContent>\n</Welcome>"))
        assertTrue(home, home.contains("<Welcome @bind-Title=\"name\" />"))
        assertTrue(home, home.contains("<Web.Components.Welcome Title=\"b\" />"))
        assertFalse(home, home.contains("Greeting"))
    }

    fun testTheCSharpNamingTheGeneratorsOutputFollows() {
        solution(generated = true)
        val title = RazorTagHelperEditor.markupTarget(markup("Title")) as CSharpPropertyDeclaration
        assertTrue(usages(title).toString(), "Use.cs:Title" in usages(title))
        // the component's file: its tags and the C# naming its class
        val component = usages(razor("Components/Greeting.razor"))
        assertTrue(component.toString(), component.count { it == "Use.cs:Greeting" } == 2 && component.count { it == "Home.razor:Greeting" } == 4)
        caret("Title")
        myFixture.renameElementAtCaretUsingHandler("Heading")
        assertTrue(text("Components/Use.cs"), text("Components/Use.cs").contains("nameof(Greeting.Heading)"))
        assertTrue(text("Pages/Home.razor"), text("Pages/Home.razor").contains("<Greeting Heading=\"a\">"))
        caret("Greeting")
        myFixture.renameElementAtCaretUsingHandler("Welcome")
        val use = text("Components/Use.cs")
        assertTrue(use, use.contains("typeof(Welcome)") && use.contains("nameof(Welcome.Heading)"))
    }

    fun testRenameAParameterFromItsAttribute() {

        solution()
        caret("Title")
        myFixture.renameElementAtCaretUsingHandler("Heading")
        val greeting = text("Components/Greeting.razor")
        assertTrue(greeting, greeting.contains("<h1>@Heading</h1>") && greeting.contains("public string Heading { get; set; }") && greeting.contains("Heading.ToUpper()"))
        val home = text("Pages/Home.razor")
        assertTrue(home, home.contains("<Greeting Heading=\"a\">") && home.contains("@bind-Heading=\"name\"") && home.contains("<Web.Components.Greeting Heading=\"b\" />"))
    }

    fun testTheRenameOfTheCSharpWritesTheMarkup() {
        solution()
        myFixture.configureFromTempProjectFile("$dir/Components/Panel.cs")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Size") + 1)
        myFixture.renameElementAtCaretUsingHandler("Width")
        assertTrue(text("Pages/Home.razor"), text("Pages/Home.razor").contains("<Panel Width=\"3\" />"))
        // the class of a component: its tags
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Panel") + 1)
        myFixture.renameElementAtCaretUsingHandler("Box")
        assertTrue(text("Pages/Home.razor"), text("Pages/Home.razor").contains("<Box Width=\"3\" />"))
        assertNotNull(myFixture.findFileInTempDir("$dir/Components/Box.cs"))
    }

    private companion object {
        val HOME = """
            @using Web.Components
            <div>
            <Greeting Title="a">
                <ChildContent>x</ChildContent>
            </Greeting>
            <Greeting @bind-Title="name" />
            <Web.Components.Greeting Title="b" />
            <Panel Size="3" />
            <p>@Totals.Count and @(Totals.Count + 1)</p>
            </div>

            @code {
                string name = "a";
            }
        """.trimIndent()
    }
}
