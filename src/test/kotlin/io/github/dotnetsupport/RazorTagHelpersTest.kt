package io.github.dotnetsupport

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorLanguage
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperService
import io.github.dotnetsupport.frameworks.razor.RazorTagHelpers
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind

/**
 * RAZOR_PLAN.md, R5c / R5d: the components and tag helpers of a project — its `.razor` components (declared by the engine), its C# components,
 * the directive attributes of the assemblies (a stand-in of Microsoft.AspNetCore.Components: `src/test/resources/index/AspNetCoreComponents.dnix`)
 * — and the C# document of a Razor file made with them: a component's element is `OpenComponent<T>` with its parameters, `@onclick` an
 * event callback. The gates on the corpus are the harness's (`tagHelperGate`) and razor-psi-core's (`RazorCodegenCorpusTest.test*TagHelpers`).
 */
class RazorTagHelpersTest : BasePlatformTestCase() {
    private lateinit var projectFile: VirtualFile

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val indexes = listOf("System.Runtime", "AspNetCoreComponents").map(CSharpUsingTypesTest::fixture)
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(indexes, "App") }
        projectFile = myFixture.addFileToProject("App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Razor\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>").virtualFile
        AssemblyIndexService.getInstance(project).set(projectFile, indexes, isComplete = true)
        myFixture.addFileToProject("App/_Imports.razor", "@using Microsoft.AspNetCore.Components.Web\n")
        myFixture.addFileToProject(
            "App/Components/Greeting.razor",
            """
            <h1>@Title</h1>
            @ChildContent

            @code {
                [Parameter, EditorRequired] public string Title { get; set; } = "";
                [Parameter] public EventCallback<string> TitleChanged { get; set; }
                [Parameter] public RenderFragment? ChildContent { get; set; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "App/Components/Panel.cs",
            "using Microsoft.AspNetCore.Components;\nnamespace App.Components;\npublic class Panel : ComponentBase\n{\n    [Parameter] public int Size { get; set; }\n}\n",
        )
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

    private val home = """
        @using App.Components
        <Greeting @bind-Title="name">Hello</Greeting>
        <Panel Size="3" />
        <button @onclick="Click">x</button>

        @code {
            string name = "a";
            void Click() { }
        }
    """.trimIndent()

    private fun razor(file: PsiFile): RazorFile = file.viewProvider.getPsi(RazorLanguage) as RazorFile

    fun testTheComponentsOfTheProjectAndTheDirectiveAttributesOfItsAssemblies() {
        val found = RazorTagHelpers.discover(project, projectFile)
        val byName = found.groupBy { it.name }
        val greeting = byName.getValue("App.Components.Greeting").first { it.kind == TagHelperKind.Component && !it.isFullyQualifiedNameMatch }
        assertEquals("Greeting", greeting.tagMatchingRules.single().tagName)
        assertEquals(listOf("Title", "TitleChanged", "ChildContent"), greeting.boundAttributes.map { it.name })
        assertTrue("[EditorRequired]", greeting.boundAttributes.first().isEditorRequired)
        // the fully qualified match, the child content and `@bind-Title` of the Title / TitleChanged pair
        assertTrue(found.any { it.kind == TagHelperKind.Component && it.isFullyQualifiedNameMatch && it.tagMatchingRules.single().tagName == "App.Components.Greeting" })
        assertTrue(found.any { it.kind == TagHelperKind.ChildContent && it.tagMatchingRules.single().tagName == "ChildContent" })
        assertTrue(found.any { it.kind == TagHelperKind.Bind && it.boundAttributes.any { a -> a.name == "@bind-Title" } })
        // a component of the C# files, its parameter
        val panel = byName.getValue("App.Components.Panel").first { it.kind == TagHelperKind.Component }
        assertEquals(listOf("Size"), panel.boundAttributes.map { it.name })
        // of the assembly: `@onclick` from [EventHandler] of EventHandlers, `@bind` of the elements, the fallback `@bind-...`, `@ref`, `@key`
        assertTrue(found.any { it.kind == TagHelperKind.EventHandler && it.name == "onclick" })
        assertTrue(found.any { it.kind == TagHelperKind.Bind && it.tagMatchingRules.any { r -> r.tagName == "select" } })
        assertTrue(found.any { it.kind == TagHelperKind.Ref } && found.any { it.kind == TagHelperKind.Key })
    }

    /** A Razor file of the project in the editor, the caret at `|`, after the project's descriptors are discovered. */
    private fun open(path: String, text: String): RazorFile {
        val offset = text.indexOf('|')
        val file = myFixture.addFileToProject(path, text.removeRange(offset, offset + 1))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(offset)
        val service = RazorTagHelperService.getInstance(project)
        service.tagHelpers(projectFile)
        PlatformTestUtil.waitWithEventsDispatching("the tag helpers of the project", { service.tagHelpers(projectFile) != null }, 60)
        return razor(file)
    }

    fun testCompletionOffersTheTagsOfTheComponentsInScope() {
        open("App/Pages/Tags.razor", "@using App.Components\n<Gr|\n")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), "Greeting" in items)
        // a component of a namespace not imported only by its full name
        open("App/Pages/Tags2.razor", "<App.Components.Pa|\n")
        val qualified = myFixture.completeBasic()?.map { it.lookupString }.orEmpty() + listOfNotNull(myFixture.editor.document.text.takeIf { "App.Components.Panel" in it })
        assertTrue(qualified.toString(), qualified.any { it == "App.Components.Panel" || "App.Components.Panel" in it })
    }

    fun testCompletionOffersTheParametersAndDirectiveAttributesOfAnElement() {
        open("App/Pages/Attributes.razor", "@using App.Components\n<Greeting T| />\n")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), items.containsAll(listOf("Title", "TitleChanged")))
        open("App/Pages/Attributes2.razor", "@using App.Components\n<Greeting @| />\n")
        val directives = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(directives.toString(), directives.containsAll(listOf("@bind-Title", "@onclick", "@ref", "@key")))
    }

    fun testGoToDeclarationFromATagAndAnAttribute() {
        val file = open("App/Pages/Navigate.razor", "@using App.Components\n<Greeting Title=\"x\" />\n<Panel Size=\"3\" />\n|")
        fun targets(word: String, occurrence: Int = 0): List<com.intellij.psi.PsiElement> {
            var at = -1
            repeat(occurrence + 1) { at = myFixture.editor.document.text.indexOf(word, at + 1) }
            val element = myFixture.file.viewProvider.findElementAt(at, com.intellij.lang.html.HTMLLanguage.INSTANCE)
            return io.github.dotnetsupport.frameworks.razor.RazorTagHelperGotoDeclarationHandler().getGotoDeclarationTargets(element, at, myFixture.editor)?.toList().orEmpty()
        }
        // a component of a `.razor` file: the file; its parameter: where `@code` declares it
        assertEquals("Greeting.razor", (targets("Greeting").single() as? com.intellij.psi.PsiFile)?.name)
        val title = targets("Title").single()
        assertEquals("Greeting.razor", title.containingFile.name)
        assertTrue(title.containingFile.text.substring(title.textRange.startOffset).startsWith("Title"))
        // a component of a C# file: its class; its parameter: the property
        assertEquals("Panel", (targets("Panel").single() as io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration).identifier?.text)
        assertEquals("Size", (targets("Size").single() as io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration).identifier?.text)
        assertNotNull(file)
        // the names are markup, not C# of the document: the handler is the only target
        val manager = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project)
        for (word in listOf("Greeting", "Title", "Panel", "Size")) assertNull(word, manager.findInjectedElementAt(myFixture.file, myFixture.editor.document.text.indexOf(word)))
    }

    fun testTheDocumentOfARazorFileIsMadeWithTheTagHelpersOfItsProject() {
        val file = razor(myFixture.addFileToProject("App/Pages/Home.razor", home))
        val service = RazorTagHelperService.getInstance(project)
        // discovered in the background: the document is made again once they are ready
        service.tagHelpers(projectFile)
        PlatformTestUtil.waitWithEventsDispatching("the tag helpers of the project", { service.tagHelpers(projectFile) != null }, 60)
        val code = RazorCSharpDocument.generate(file)!!.code
        for (expected in listOf(
            "__builder.OpenComponent<global::App.Components.Greeting>(",
            // `@bind-Title`: the value and a callback setting it back through TitleChanged
            "__builder.AddComponentParameter(1, nameof(global::App.Components.Greeting.",
            "nameof(global::App.Components.Greeting.TitleChanged)",
            "CreateInferredEventCallback(this, __value => name = __value, name)",
            "__builder.OpenComponent<global::App.Components.Panel>(",
            "TypeCheck<global::System.Int32>(",
            "global::Microsoft.AspNetCore.Components.EventCallback.Factory.Create<global::Microsoft.AspNetCore.Components.Web.MouseEventArgs>(this,",
        )) assertTrue("$expected in\n$code", code.contains(expected))
        assertFalse("a component is no markup element any more", code.contains("OpenElement(0, \"Greeting\")"))
    }
}
