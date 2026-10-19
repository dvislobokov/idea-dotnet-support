package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Completion in a Razor file beyond the C# expressions (the user's check 2026-10-19 in the sandbox): the names of the directives after `@`,
 * the arguments of the directives, the name of an `@inject`, and no members of the generator's glue (`__builder`, `BuildRenderTree`).
 */
class RazorCompletionTest : BasePlatformTestCase() {

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

    private fun items(name: String, text: String): List<String> {
        myFixture.configureByText(name, text)
        myFixture.completeBasic()
        // null: no list, the only item was inserted (or nothing came) — the text then tells which
        return myFixture.lookupElementStrings ?: listOf("<no list: ${myFixture.editor.document.text.replace("\n", "\\n")}>")
    }

    private val component = "@page \"/x\"\n<h1>Title</h1>\n%s\n@code {\n    private int count;\n    private void Increment() => count++;\n}\n"

    /** The text of the Razor file after completion (the fixture's editor is the injected document's while the caret is in it). */
    private fun razorText(): String = com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document.text

    fun testDirectivesAfterTheTransition() {
        // the only item: inserted, with the space before the type
        items("Directives.razor", component.format("@inj<caret>"))
        assertTrue(razorText(), razorText().contains("\n@inject \n"))
        val all = items("All.razor", component.format("@<caret>"))
        for (name in listOf("inject", "code", "using", "page", "layout", "implements", "inherits", "attribute", "typeparam", "if", "foreach")) assertTrue("$name: $all", name in all)
        assertTrue(all.toString(), "count" in all)
    }

    fun testNoMembersOfTheGlue() {
        val all = items("Glue.razor", component.format("@<caret>"))
        assertTrue(all.toString(), "count" in all && "Increment" in all)
        assertFalse(all.toString(), all.any { it.startsWith("__") })
        assertFalse(all.toString(), "BuildRenderTree" in all)
    }

    fun testTheNamespaceOfAUsing() {
        // the only namespace of the fixture's assemblies that starts so: inserted
        myFixture.configureByText("Using.razor", "@using Sys<caret>\n<p>x</p>\n")
        myFixture.completeBasic()
        assertTrue(razorText(), razorText().startsWith("@using System\n"))
    }

    fun testTheTypeOfImplementsAndAttribute() {
        assertTrue(items("Implements.razor", "@implements IDisp<caret>\n<p>x</p>\n").contains("IDisposable"))
        assertTrue(items("Attribute.razor", "@attribute [Obsol<caret>]\n<p>x</p>\n").contains("Obsolete"))
    }

    /**
     * The head of the class after an `@inject` (the sandbox: `@implements`, `@using`, `@attribute` after the injects had no completion — the
     * generator writes them before the members, the document's pieces go in the order of the file): an injection of its own, not checked.
     */
    fun testTheHeadOfTheClassAfterAnInject() {
        val head = "@inject System.IServiceProvider Services\n%s\n<p>@Services</p>\n"
        assertTrue(items("HeadImplements.razor", head.format("@implements IDisp<caret>")).contains("IDisposable"))
        assertTrue(items("HeadAttribute.razor", head.format("@attribute [Obsol<caret>]")).contains("Obsolete"))
        myFixture.configureByText("HeadUsing.razor", head.format("@using Sys<caret>"))
        myFixture.completeBasic()
        assertTrue(razorText(), razorText().contains("@using System\n"))
        // the piece is its own injection; the document keeps the inject and the markup, and the piece is not checked there
        val file = myFixture.configureByText("HeadPlaces.razor", head.format("@implements System.IDisposable")).viewProvider.getPsi(io.github.dotnetsupport.frameworks.razor.RazorLanguage) as io.github.dotnetsupport.frameworks.razor.RazorFile
        val heads = io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument.headPlaces(file)
        assertEquals(listOf("System.IDisposable"), heads.map { it.range.substring(it.host.text) })
        val injected = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(heads.single().host)!!.single().first as com.intellij.psi.PsiFile
        assertTrue(injected.text, Regex("""class HeadPlaces : global::Microsoft\.AspNetCore\.Components\.ComponentBase, \n(#.*\n)*System\.IDisposable\n""").containsMatchIn(injected.text))
        assertTrue(injected.text, injected.text.trimEnd().endsWith("{\n}\n}"))
        assertFalse(io.github.dotnetsupport.frameworks.razor.RazorCodeGenerators().isDocumentCompiled(injected)!!)
        assertTrue(io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument.places(file).any { it.host.text == "Services" })
    }

    /**
     * The static members of a library type of `using static` by simple name — `@rendermode InteractiveServer` of `RenderMode` that a Blazor
     * `_Imports.razor` imports, `Abs` of `using static System.Math;` (the sandbox 2026-10-19: none came, only the solution's types had them).
     */
    fun testUsingStaticOfALibraryType() {
        val math = items("MathStatic.cs", "using static System.Math;\nclass C { static object M => Ab<caret>; }\n")
        assertTrue(math.toString(), "Abs" in math || math.single().contains("Abs("))
        items("ConsoleStatic.cs", "using static System.Console;\nclass C { void M() { WriteL<caret> } }\n")
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("WriteLine("))
        // not the extension methods: `using static` brings them as extensions only
        val linq = items("LinqStatic.cs", "using static System.Linq.Enumerable;\nclass C { static object M => Whe<caret>; }\n")
        assertTrue(linq.toString(), linq.none { it == "Where" || it.contains("Where(") })
        val mode = items("Mode.razor", "@using static System.Math\n@inject System.IServiceProvider Services\n@rendermode Ab<caret>\n<p>@Services</p>\n")
        assertTrue(mode.toString(), "Abs" in mode || razorText().contains("@rendermode Abs"))
    }

    /**
     * `@rendermode` of a .NET 9 project: the compiler writes the mode without a mapping below Razor 11 (hot reload), the editor's document
     * maps it — `@rendermode Inter` had no completion in the WSL sandbox (0.1.295) while `@Inter` in the markup had.
     */
    fun testTheRenderModeOfANet9ProjectIsCSharpOfTheEditor() {
        myFixture.addFileToProject("Mode9/Mode9.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"><PropertyGroup><TargetFramework>net9.0</TargetFramework></PropertyGroup></Project>")
        val text = "@inject System.IServiceProvider Services\n@rendermode InteractiveServer\n<p>@Services</p>\n@code { int count; }\n"
        val file = myFixture.addFileToProject("Mode9/Mode.razor", text).viewProvider.getPsi(io.github.dotnetsupport.frameworks.razor.RazorLanguage) as io.github.dotnetsupport.frameworks.razor.RazorFile
        val document = io.github.dotnetsupport.frameworks.razor.RazorCSharpDocument
        val compiled = document.compilerOutput(file)!!
        assertTrue(compiled, Regex("""ModeImpl => InteractiveServer""").containsMatchIn(compiled))
        val hosts = document.places(file).map { it.range.substring(it.host.text) }
        assertEquals(listOf("System.IServiceProvider", "Services", "InteractiveServer", "Services", " int count; "), hosts)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("Mode9/Abs.razor", "@using static System.Math\n@inject System.IServiceProvider Services\n@rendermode Ab<caret>\n<p>@Services</p>\n").virtualFile)
        myFixture.completeBasic()
        assertTrue(razorText(), (myFixture.lookupElementStrings ?: emptyList()).contains("Abs") || razorText().contains("@rendermode Abs"))
    }

    /** A namespace after `using`: no postfix templates (`using Microsoft.` listed `.arg`, `.await`, `.cast`… in .cs and Razor). */
    fun testNoPostfixTemplatesInAUsing() {
        for ((name, text) in listOf("PostfixUsing.cs" to "using System.<caret>\nclass C { }\n", "PostfixUsing.razor" to "@using System.<caret>\n<p>x</p>\n")) {
            val found = items(name, text)
            assertTrue("$name: $found", found.none { it.startsWith(".") || it in setOf("arg", "await", "cast", "not", "par") })
            assertTrue("$name: $found", "Linq" in found)
        }
        fun inName(text: String, name: String) = io.github.dotnetsupport.lang.CSharpPostfixTemplates.inDirectiveName(text, text.lastIndexOf(name))
        assertTrue(inName("using \n#nullable restore\n#line (1,8)-(1,17) \"/A.razor\"\nMicrosoft", "Microsoft"))
        assertTrue(inName("namespace Shop", "Shop"))
        assertTrue(inName("using Alias = System", "System"))
        assertFalse(inName("using (var x = stream", "stream"))
        assertFalse(inName("using var x = stream", "stream"))
        assertFalse(inName("void M() { var x = stream", "stream"))
    }

    fun testTheNameOfAnInject() {
        val items = items("Inject.razor", "@inject System.IServiceProvider <caret>\n<p>x</p>\n")
        assertEquals(setOf("ServiceProvider", "Provider"), items.toSet())
        val names = io.github.dotnetsupport.frameworks.razor.RazorCSharpCompletion
        assertEquals(listOf("NavigationManager", "Manager"), names.namesOf("NavigationManager"))
        assertEquals(listOf("JSRuntime", "Runtime"), names.namesOf("IJSRuntime"))
        assertEquals(listOf("Logger"), names.namesOf("ILogger<Counter>"))
        assertEquals(listOf("HttpClientFactory", "ClientFactory", "Factory"), names.namesOf("System.Net.Http.IHttpClientFactory"))
    }

    fun testTheGlueAfterADotIsTheLibrarys() {
        val names = io.github.dotnetsupport.frameworks.razor.RazorCSharpCompletion
        assertTrue(names.isGlue("__builder", afterDot = true))
        assertTrue(names.isGlue("BuildRenderTree__2", afterDot = false))
        assertTrue(names.isGlue("ExecuteAsync", afterDot = false))
        assertFalse(names.isGlue("ExecuteAsync", afterDot = true))
    }

    fun testTheDirectivesOfAView() {
        val all = items("Index.cshtml", "@model System.String\n<h1>x</h1>\n@<caret>\n")
        for (name in listOf("model", "inject", "using", "section", "functions", "addTagHelper")) assertTrue("$name: $all", name in all)
        assertFalse(all.toString(), "code" in all)
        assertFalse(all.toString(), "ExecuteAsync" in all)
        // not at the start of a line: statements only
        val inline = items("Inline.razor", "<p>@<caret></p>\n@code { int count; }\n")
        assertTrue(inline.toString(), "if" in inline && "count" in inline)
        assertFalse(inline.toString(), "inject" in inline)
    }
}
