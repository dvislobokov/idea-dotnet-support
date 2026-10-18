package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.razor.RazorErrors
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorLanguage
import io.github.dotnetsupport.frameworks.razor.RazorTagHelperService
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * RAZOR_PLAN.md, R6: the Razor compiler's diagnostics in the editor — the parser's whatever the project, the components' once the project's
 * descriptors are known (before, every component would be "unexpected"). On the stand-in of Microsoft.AspNetCore.Components, as RazorTagHelpersTest.
 */
class RazorErrorsTest : BasePlatformTestCase() {
    private lateinit var projectFile: VirtualFile

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        val indexes = listOf("System.Runtime", "AspNetCoreComponents").map(CSharpUsingTypesTest::fixture)
        CSharpSemanticEnvironment.setAssembliesForTests { AssemblyIndexSet(indexes, "App") }
        projectFile = myFixture.addFileToProject("App/App.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Razor\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>").virtualFile
        AssemblyIndexService.getInstance(project).set(projectFile, indexes, isComplete = true)
        myFixture.addFileToProject("App/_Imports.razor", "@using Microsoft.AspNetCore.Components.Web\n@using App.Components\n")
        myFixture.addFileToProject("App/Components/Greeting.razor", "<h1>@Title</h1>\n\n@code {\n    [Parameter, EditorRequired] public string Title { get; set; } = \"\";\n}\n")
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

    private fun razor(path: String, text: String): RazorFile {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return file.viewProvider.getPsi(RazorLanguage) as RazorFile
    }

    private fun waitForTagHelpers() {
        val service = RazorTagHelperService.getInstance(project)
        service.tagHelpers(projectFile)
        // settled: a new file is a new component, its descriptors come in a run after the first one (and drop the PSI caches then)
        PlatformTestUtil.waitWithEventsDispatching("the tag helpers of the project", {
            service.tagHelpers(projectFile)
            service.isSettledForTests(projectFile)
        }, 60)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    /** The annotations of the Razor file in the editor: `RZ…` with the text they cover. */
    private fun shown(): List<String> = myFixture.doHighlighting(HighlightSeverity.WARNING)
        .filter { it.description?.startsWith("RZ") == true }
        .map { "${it.severity.name} ${it.description!!.substringBefore(':')} '${myFixture.editor.document.text.substring(it.startOffset, it.endOffset)}'" }

    fun testTheDescriptorsOfABrokenComponentAreStable() {
        razor("App/Pages/Unclosed2.razor", "<p>x</p>\n@{\n    var count = 1;\n")
        val first = io.github.dotnetsupport.frameworks.razor.RazorTagHelpers.discover(project, projectFile).map { it.toString() }
        val second = io.github.dotnetsupport.frameworks.razor.RazorTagHelpers.discover(project, projectFile).map { it.toString() }
        assertEquals((first - second.toSet()).toString() + " / " + (second - first.toSet()).toString(), first, second)
    }

    fun testAnUnclosedBlockIsAnErrorWhateverTheProject() {
        val file = razor("App/Pages/Unclosed.razor", "<p>x</p>\n@{\n    var count = 1;\n")
        waitForTagHelpers()
        val ids = RazorErrors.diagnostics(file).map { it.id }
        assertTrue(ids.toString(), "RZ1006" in ids)
        assertTrue(shown().toString(), shown().any { it.startsWith("ERROR RZ1006") })
    }

    fun testAnUnknownComponentIsAWarningOnceTheComponentsAreKnown() {
        val file = razor("App/Pages/Unknown.razor", "<Greeting Title=\"a\" />\n<Nowhere />\n")
        waitForTagHelpers()
        val diagnostics = RazorErrors.diagnostics(file)
        val unknown = diagnostics.filter { it.id == "RZ10012" }
        assertEquals(diagnostics.map { "${it.id} ${it.message}" }.toString(), 1, unknown.size)
        assertTrue(unknown.single().message, unknown.single().message.contains("Nowhere"))
        // on the element, as the compiler reports it
        assertTrue(shown().toString(), shown().contains("WARNING RZ10012 '<Nowhere />'"))
        // a known component is no "unexpected element"
        assertFalse(shown().toString(), shown().any { it.contains("'Greeting'") })
    }

    fun testAComponentWithoutItsRequiredParameterIsAWarning() {
        val file = razor("App/Pages/Required.razor", "<Greeting />\n")
        waitForTagHelpers()
        val ids = RazorErrors.diagnostics(file).map { it.id }
        assertTrue(ids.toString(), "RZ2012" in ids)
    }

    fun testOnlyTheParserSpeaksBeforeTheDescriptorsAreKnown() {
        // a project that discovers nothing: no context, so no components' diagnostics
        val other = myFixture.addFileToProject("Bare/Bare.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Razor\"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>").virtualFile
        assertNotNull(other)
        val file = razor("Bare/Page.razor", "<Nowhere />\n@{\n")
        val ids = RazorErrors.diagnostics(file).map { it.id }
        assertTrue(ids.toString(), "RZ1006" in ids)
        assertFalse("RZ10012 without the descriptors: $ids", "RZ10012" in ids && RazorTagHelperService.getInstance(project).tagHelpers(other) == null)
    }
}
