package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.format.DotNetFormattingSettings
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lang.CSharpCompletionAutoPopup
import io.github.dotnetsupport.lang.CSharpCreateFromUsage
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpGenerator
import io.github.dotnetsupport.lang.CSharpPostfixMembers
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpDocumentation
import io.github.dotnetsupport.lang.NativeCSharpGenerateRunner
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynOptions

/**
 * The options of Settings | .NET | Language Server obeyed by the plugin's own C# as by the server (rule of the user 2026-10-06): each one
 * on and off, on the native features, with the fixture assemblies of src/test/resources/index. The ones of Go to Class / Symbol and of the
 * decompiler are in `AssemblyGotoLibraryTest`, where the indexes of the libraries are set up.
 */
class NativeServerOptionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var autocomplete = true
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        for (feature in listOf(CSharpFeature.COMPLETION, CSharpFeature.DOCUMENTATION, CSharpFeature.FORMATTING)) {
            settings.setSource(feature, CSharpFeatureSource.NATIVE)
        }
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            NativeCSharpGenerateRunner.setChooserForTests(null)
            DotNetFormattingSettings.getInstance(project).formatter = FormatterChoice.AUTO
            settings.state.options = mutableMapOf()
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun set(section: String, value: String) = settings.setValue(RoslynOptions.option(section), value)

    private fun lookup(text: String): List<LookupElement> {
        myFixture.configureByText("Options${files++}.cs", text.trimIndent())
        myFixture.completeBasic()
        return myFixture.lookupElements?.toList().orEmpty()
    }

    private fun shown(element: LookupElement): String = LookupElementPresentation.renderElement(element).let { it.itemText + it.tailText.orEmpty() }
    private fun rows(text: String): List<String> = lookup(text).map(::shown)
    private fun native(text: String): List<String> = lookup(text).filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }.map { it.lookupString }

    // ---- completion

    private fun body(statements: String, usings: String = "using System;"): String =
        "$usings\nclass Sample\n{\n    void Run(string text, System.Collections.Generic.List<int> numbers)\n    {\n        $statements\n    }\n}\n"

    fun testItemsFromUnimportedNamespaces() {
        val text = body("StringBu<caret>")
        val on = rows(text)
        assertTrue(on.toString(), "StringBuilder (in System.Text)" in on)
        set("completion.dotnet_show_completion_items_from_unimported_namespaces", "false")
        val off = rows(text)
        assertFalse(off.toString(), off.any { it.startsWith("StringBuilder") })
        // what a `using` sees is still there
        val imported = rows(body("Conso<caret>"))
        assertTrue(imported.toString(), imported.any { it.startsWith("Console") })
    }

    fun testUnimportedExtensionMethods() {
        val text = body("numbers.Su<caret>")
        assertTrue(rows(text).toString(), rows(text).any { it.startsWith("Sum") })
        set("completion.dotnet_show_completion_items_from_unimported_namespaces", "false")
        assertFalse(rows(text).toString(), rows(text).any { it.startsWith("Sum") })
    }

    fun testNameSuggestions() {
        val text = "using System.Text; class A { void M() { StringBuilder <caret>\n } }"
        assertEquals(listOf("builder", "stringBuilder"), native(text))
        set("completion.dotnet_show_name_completion_suggestions", "false")
        assertEquals(emptyList<String>(), native(text).filter { it == "builder" || it == "stringBuilder" })
        val tuple = "class A { void M((int Count, string Name) pair) { var (<caret>) = pair; } }"
        assertEquals(emptyList<String>(), native(tuple).filter { it == "count" })
        set("completion.dotnet_show_name_completion_suggestions", "true")
        assertTrue(native(tuple).toString(), "count" in native(tuple))
    }

    fun testRegexCompletion() {
        val text = "using System.Text.RegularExpressions;\nclass A { void M() { var r = new Regex(@\"\\<caret>\"); } }"
        assertTrue(lookup(text).map { it.lookupString }.any { it.contains("d") })
        set("completion.dotnet_provide_regex_completions", "false")
        assertEquals("the RegExp contributors are stopped", emptyList<String>(), lookup(text).map { it.lookupString })
        // the injection stays: colors and matching of the pattern
        val file = myFixture.configureByText("Options${files++}.cs", "using System.Text.RegularExpressions;\nclass A { void M() { var r = new Regex(@\"\\d+\"); } }")
        assertEquals("RegExp", InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, file.text.indexOf("\\d"))?.containingFile?.language?.id)
    }

    fun testCompletionInArgumentLists() {
        myFixture.configureByText("Options${files++}.cs", "class A { int item; void Run(System.Func<int, bool> f) { } void M() { Run(<caret> } }")
        assertTrue(CSharpCompletionAutoPopup.schedules('(', project, myFixture.editor, myFixture.file))
        set("completion.dotnet_trigger_completion_in_argument_lists", "false")
        assertFalse(CSharpCompletionAutoPopup.schedules('(', project, myFixture.editor, myFixture.file))
        assertFalse(CSharpCompletionAutoPopup.inArgumentLists)
    }

    // ---- documentation

    fun testRemarksInQuickDocumentation() {
        val text = "class A { void M() { Fixture.Sha|pe s = null; } }"
        fun doc(): NativeCSharpDocumentation.Doc {
            val source = text.replace("|", "")
            val file = myFixture.configureByText("Options${files++}.cs", source) as CSharpFile
            return NativeCSharpDocumentation.at(file, text.indexOf('|'))!!
        }
        assertTrue(doc().html, doc().html.contains("Remarks:"))
        set("quick_info.dotnet_show_remarks_in_quick_info", "false")
        assertFalse(doc().html, doc().html.contains("Remarks:"))
    }

    // ---- editing

    fun testAutoInsertOfDocComments() {
        val before = "class A\n{\n    //<caret>\n    public int Sum(int a, int b) => a + b;\n}\n"
        myFixture.configureByText("Options${files++}.cs", before)
        myFixture.type("/")
        assertTrue(myFixture.editor.document.text.contains("/// <summary>"))
        set("auto_insert.dotnet_enable_auto_insert", "false")
        myFixture.configureByText("Options${files++}.cs", before)
        myFixture.type("/")
        assertEquals(before.replace("<caret>", "/"), myFixture.editor.document.text)
        myFixture.configureByText("Options${files++}.cs", "class A\n{\n    /// first<caret>\n    void M() { }\n}\n")
        myFixture.type("\n")
        assertFalse(myFixture.editor.document.text, myFixture.editor.document.text.contains("first\n    /// "))
    }

    fun testHighlightingOfRegexAndJsonStrings() {
        val text = "using System.Text.RegularExpressions;\nusing System.Text.Json;\nclass A { void M() { var r = new Regex(@\"\\d+\"); var j = JsonDocument.Parse(\"[1]\"); } }"
        fun injected(marker: String): String? {
            val file = myFixture.configureByText("Options${files++}.cs", text)
            return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, file.text.indexOf(marker))?.containingFile?.language?.id
        }
        assertEquals("RegExp", injected("\\d"))
        assertEquals("JSON", injected("[1]"))
        set("highlighting.dotnet_highlight_related_regex_components", "false")
        assertNull(injected("\\d"))
        assertEquals("JSON", injected("[1]"))
        set("highlighting.dotnet_highlight_related_json_components", "false")
        assertNull(injected("[1]"))
    }

    // ---- formatting

    private fun reformat(file: PsiFile): String {
        val documents = PsiDocumentManager.getInstance(project)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
        documents.commitDocument(documents.getDocument(file)!!)
        return documents.getDocument(file)!!.text
    }

    private val formatted = "\n\nnamespace Acme\n{\n    class Thing\n    {\n    }\n}\n\nclass A\n{\n    void M(List<int> list, Thing t)\n    {\n        Console.WriteLine(list.Count);\n    }\n}\n"

    fun testOrganizeImportsOnFormat() {
        val source = "using System.Text;\nusing System.Collections.Generic;\nusing Acme;\nusing System;$formatted"
        val file = myFixture.addFileToProject("Organize${files++}/A.cs", source)
        assertEquals("off by default: the directives stay", source, reformat(file))
        set("formatting.dotnet_organize_imports_on_format", "true")
        assertEquals("using System;\nusing System.Collections.Generic;\nusing Acme;$formatted", reformat(file))
        assertEquals("idempotent", "using System;\nusing System.Collections.Generic;\nusing Acme;$formatted", reformat(file))
    }

    fun testOrganizeImportsWithoutSystemFirst() {
        set("formatting.dotnet_organize_imports_on_format", "true")
        val folder = "Organize${files++}"
        myFixture.addFileToProject("$folder/.editorconfig", "root = true\n[*.cs]\ndotnet_sort_system_directives_first = false\n")
        val file = myFixture.addFileToProject("$folder/A.cs", "using System;\nusing System.Collections.Generic;\nusing Acme;$formatted")
        assertEquals("using Acme;\nusing System;\nusing System.Collections.Generic;$formatted", reformat(file))
    }

    // ---- code generation

    fun testGeneratedMembersAtTheEnd() {
        val text = "class Order\n{\n    private readonly int _id;<caret>\n\n    public void Save() { }\n}\n"
        fun generated(): String {
            myFixture.configureByText("Options${files++}.cs", text)
            NativeCSharpGenerateRunner.run(CSharpGenerator.CONSTRUCTOR, project, myFixture.editor, myFixture.file)
            return myFixture.editor.document.text
        }
        assertTrue(generated(), generated().contains("_id;\n\n    public Order(int id)"))
        set("type_members.dotnet_member_insertion_location", "at_the_end")
        val atEnd = generated()
        assertTrue(atEnd, atEnd.contains("public void Save() { }\n\n    public Order(int id)"))
    }

    fun testCreatedPropertyWithTheOthersOrAtTheEnd() {
        val source = "class Repo\n{\n    public int Id { get; set; }\n\n    public void Save() { }\n}\n"
        fun created(): String {
            val file = myFixture.configureByText("Options${files++}.cs", source) as CSharpFile
            val type = file.compilationUnit!!.members.single() as CSharpTypeDeclaration
            val document = myFixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { CSharpCreateFromUsage.insertMember(document, type, "public int Count { get; set; }", field = false, after = null, unit = "    ", property = true) }
            return document.text
        }
        assertEquals("class Repo\n{\n    public int Id { get; set; }\n\n    public int Count { get; set; }\n\n    public void Save() { }\n}\n", created())
        set("type_members.dotnet_member_insertion_location", "at_the_end")
        assertEquals("class Repo\n{\n    public int Id { get; set; }\n\n    public void Save() { }\n\n    public int Count { get; set; }\n}\n", created())
    }

    fun testPostfixPropertyAtTheEnd() {
        val source = "class C\n{\n    private int _x;\n\n    void M(int count)\n    {\n    }\n}\n"
        val type = CSharpDeclarations.scan(source).all().first { it.name == "C" }
        val edit = CSharpPostfixMembers.declareProperty(source, type, "public int Count { get; set; }", "    ")
        assertEquals(source.indexOf("_x;") + 3, edit.offset)
        set("type_members.dotnet_member_insertion_location", "at_the_end")
        val atEnd = CSharpPostfixMembers.declareProperty(source, type, "public int Count { get; set; }", "    ")
        assertEquals(source.lastIndexOf("    }") + 5, atEnd.offset)
    }

    fun testPropertyGenerationBehavior() {
        val text = "interface IShape\n{\n    string Name { get; set; }\n}\n\nclass Circle : IShape\n{\n    <caret>\n}\n"
        fun implemented(): String {
            myFixture.configureByText("Options${files++}.cs", text)
            NativeCSharpGenerateRunner.run(CSharpGenerator.MISSING_MEMBERS, project, myFixture.editor, myFixture.file)
            return myFixture.editor.document.text
        }
        val throwing = implemented()
        assertTrue(throwing, throwing.contains("    public string Name\n    {\n        get => throw new NotImplementedException();\n        set => throw new NotImplementedException();\n    }"))
        assertTrue(throwing, throwing.startsWith("using System;\n"))
        set("type_members.dotnet_property_generation_behavior", "prefer_auto_properties")
        val auto = implemented()
        assertTrue(auto, auto.contains("    public string Name { get; set; }"))
        assertFalse(auto, auto.startsWith("using System;\n"))
    }
}
