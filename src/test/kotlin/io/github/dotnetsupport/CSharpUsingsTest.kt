package io.github.dotnetsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpPostfixTemplateProvider
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.CSharpUsingNames
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lang.NativeCSharpServerActions
import io.github.dotnetsupport.lang.NativeCSharpUsingCompletion
import io.github.dotnetsupport.lang.NativeCSharpUsingEdits
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * `using` directives and `using` / `await using` statements on the native tree (CSHARP_PSI_MIGRATION.md, task A8): completion of the
 * directives and of `using var` / `await using var`, `.using` / `.awaitusing`, the conversions statement ↔ declaration, "Wrap in 'using'
 * statement", "Sort 'using' directives", "Convert to 'global using'", "Make method async" on `await using` / `await foreach`, broken code,
 * undo, the server's duplicate rows.
 */
class CSharpUsingsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0
    private var autocomplete = true

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            settings.state.features = mutableMapOf()
            settings.state.enabled = true
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun native(text: String, name: String = "Usings${files++}.cs"): List<String> {
        myFixture.configureByText(name, text)
        myFixture.completeBasic()
        return myFixture.lookupElements?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }?.map { it.lookupString }.orEmpty()
    }

    private fun choose(text: String, item: String): String {
        myFixture.configureByText("Usings${files++}.cs", text)
        val elements = myFixture.completeBasic()?.toList().orEmpty()
        val element = elements.firstOrNull { it.lookupString == item } ?: error("no $item in ${elements.map { it.lookupString }}")
        myFixture.lookup.currentItem = element
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    /** [text] after the intention [title] at `<caret>`; the text with `<caret>` where the caret is. */
    private fun apply(text: String, title: String): String {
        myFixture.configureByText("Usings${files++}.cs", text)
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return myFixture.editor.document.text
    }

    private fun available(text: String, title: String): Boolean {
        myFixture.configureByText("Usings${files++}.cs", text)
        return myFixture.filterAvailableIntentions(title).any { it.text == title }
    }

    private fun undo() {
        IdeDocumentHistory.getInstance(project)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
    }

    // ---- completion: statements

    fun testUsingVarAtTheStartOfAStatement() {
        val list = native("class A { void M() { <caret> } }")
        assertTrue(list.containsAll(listOf("using var", "await using var", "using")))
        val getter = native("class A { int P { get { <caret>\n return 1; } } }")
        assertTrue(getter.contains("using var"))
        assertFalse("await is not legal in a getter", getter.contains("await using var"))
        assertFalse("nor in lock", native("class A { void M(object o) { lock (o) { <caret>\n } } }").contains("await using var"))
        assertFalse("not in an expression", native("class A { void M() { var x = <caret> } }").contains("using var"))
    }

    fun testUsingVarIsInserted() {
        assertEquals("class A { void M() { using var \n } }", choose("class A { void M() { usi<caret>\n } }", "using var"))
    }

    fun testAwaitUsingMakesTheMethodAsync() {
        assertEquals(
            "using System.Threading.Tasks; class A { async Task Run() { await using var \n } }",
            choose("using System.Threading.Tasks; class A { void Run() { aw<caret>\n } }", "await using var"),
        )
        assertEquals(
            "class A { async System.Threading.Tasks.Task Run() { await using var \n } }",
            choose("class A { async System.Threading.Tasks.Task Run() { aw<caret>\n } }", "await using var"),
        )
    }

    fun testUsingParenthesisOffersLocalsAndNew() {
        val list = native("class A { void M(System.IO.Stream stream) { var other = stream; using (<caret>) } }")
        assertTrue(list.toString(), list.containsAll(listOf("stream", "other", "var", "new")))
    }

    // ---- completion: directives

    fun testDirectiveNamespacesOfTheSolution() {
        myFixture.addFileToProject("usingDir/Shop.cs", "namespace UsingShop.Orders { public class Order {} }\nnamespace UsingShop.Billing { public static class Tax { public static int Rate = 1; } }")
        val top = native("using <caret>")
        assertTrue(top.toString(), top.containsAll(listOf("UsingShop", "static")))
        val under = native("using UsingShop.<caret>")
        assertEquals(listOf("Billing", "Orders"), under.sorted())
        assertFalse("no types after a plain using", native("using UsingShop.Billing.<caret>").contains("Tax"))
        assertTrue("types after using static", native("using static UsingShop.Billing.<caret>").contains("Tax"))
        assertTrue("types after an alias", native("using O = UsingShop.Orders.<caret>").contains("Order"))
        assertFalse("static only as the first word", native("using static <caret>").contains("static"))
        assertTrue("global using too", native("global using UsingShop.<caret>").contains("Orders"))
    }

    fun testDirectiveNamespacesOfTheAssemblies() {
        val indexes = listOf("System.Console", "System.Linq").map { name -> AssemblyIndex.read(javaClass.getResourceAsStream("/index/$name.dnix")!!.use { it.readBytes() }) }
        val dir = "asm${files++}"
        val projectFile = myFixture.addFileToProject("$dir/App/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""").virtualFile
        AssemblyIndexService.getInstance(project).set(projectFile, indexes)
        fun at(text: String): List<String> {
            myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("$dir/App/F${files++}.cs", text).virtualFile)
            myFixture.completeBasic()
            return myFixture.lookupElements?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }?.map { it.lookupString }
                ?: listOf("<no lookup: ${myFixture.editor.document.text}>")
        }
        val top = at("using <caret>")
        assertTrue(top.toString(), top.contains("System"))
        val system = at("using System.<caret>")
        assertTrue(system.toString(), system.contains("Linq"))
        assertFalse(system.contains("Console"))
        assertTrue(at("using static System.<caret>").contains("Console"))
    }

    fun testChildNamespaces() {
        assertEquals(listOf("A", "System"), NativeCSharpUsingCompletion.childNamespaces("", listOf("System.Linq", "A.B", "System", "")).toList())
        assertEquals(listOf("Collections", "Linq"), NativeCSharpUsingCompletion.childNamespaces("System", listOf("System.Linq", "System.Collections.Generic", "System", "SystemX.A")).toList())
    }

    fun testGlobalUsingAtTheTopOfAFile() {
        assertTrue(native("<caret>").contains("global using"))
        assertTrue(native("global using System;\n<caret>").contains("global using"))
        assertFalse("not after a plain using", native("using System;\n<caret>").contains("global using"))
        assertFalse("not in a namespace", native("namespace N { <caret> }").contains("global using"))
        assertFalse("not after a type", native("class A { }\n<caret>").contains("global using"))
        assertEquals(listOf("using"), native("global <caret>"))
    }

    // ---- postfix

    private fun expand(template: PostfixTemplate, before: String): String {
        myFixture.configureByText("Postfix${files++}.cs", before)
        val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        WriteCommandAction.runWriteCommandAction(project) { template.expand(context, myFixture.editor) }
        return myFixture.editor.document.text
    }

    fun testPostfixUsingAndAwaitUsing() {
        val templates = CSharpPostfixTemplateProvider().templates.associateBy { it.key }
        assertEquals(
            "class A { void M(string path) { using var reader = new StreamReader(path); } }",
            expand(templates.getValue(".using"), "class A { void M(string path) { new StreamReader(path)<caret> } }"),
        )
        assertEquals("reader", myFixture.editor.selectionModel.selectedText)
        assertEquals(
            "using System.Threading.Tasks; class A { async Task M(Db db) { await using var transaction = db.BeginTransactionAsync(); } }",
            expand(templates.getValue(".awaitusing"), "using System.Threading.Tasks; class A { void M(Db db) { db.BeginTransactionAsync()<caret> } }"),
        )
        assertEquals("transaction", myFixture.editor.selectionModel.selectedText)
        assertEquals("value", CSharpUsingNames.of("stream"))
        assertEquals("connection", CSharpUsingNames.of("new SqlConnection(text)"))
        assertEquals("stream", CSharpUsingNames.of("File.OpenStream(path)"))
    }

    // ---- statement ↔ declaration

    fun testConvertToUsingDeclaration() {
        val before = """
            class A
            {
                void M(string path)
                {
                    Log();
                    <caret>using (var reader = new StreamReader(path))
                    {
                        // read it
                        Console.WriteLine(reader.ReadLine());

                        Console.WriteLine(reader.ReadLine());
                    }
                }
            }
        """.trimIndent()
        val after = """
            class A
            {
                void M(string path)
                {
                    Log();
                    using var reader = new StreamReader(path);
                    // read it
                    Console.WriteLine(reader.ReadLine());

                    Console.WriteLine(reader.ReadLine());
                }
            }
        """.trimIndent()
        assertEquals(after, apply(before, "Convert to 'using' declaration"))
        undo()
        assertEquals(before.replace("<caret>", ""), myFixture.editor.document.text)
    }

    fun testConvertStackedAndAwaitUsingToDeclarations() {
        val before = "class A\n{\n    async Task M()\n    {\n        await <caret>using (var a = Open())\n        using (var b = Open())\n            Use(a, b);\n    }\n}"
        val after = "class A\n{\n    async Task M()\n    {\n        await using var a = Open();\n        using var b = Open();\n        Use(a, b);\n    }\n}"
        assertEquals(after, apply(before, "Convert to 'using' declaration"))
    }

    fun testNoDeclarationWhereItWouldChangeTheScope() {
        assertFalse("not the last statement", available("class A { void M() { <caret>using (var r = Open()) { Use(r); } Log(); } }", "Convert to 'using' declaration"))
        assertFalse("an expression has no name", available("class A { void M(R r) { <caret>using (r) { Use(r); } } }", "Convert to 'using' declaration"))
        assertFalse("broken: no closing parenthesis", available("class A { void M() { <caret>using (var r = Open() { Use(r); } } }", "Convert to 'using' declaration"))
        assertFalse("not in the body", available("class A { void M() { using (var r = Open()) { <caret>Use(r); } } }", "Convert to 'using' declaration"))
    }

    fun testConvertToUsingStatement() {
        val before = "class A\n{\n    void M()\n    {\n        <caret>using var reader = Open(); // the file\n        Use(reader);\n\n        Log();\n    }\n}"
        val after = "class A\n{\n    void M()\n    {\n        using (var reader = Open()) // the file\n        {\n            Use(reader);\n\n            Log();\n        }\n    }\n}"
        assertEquals(after, apply(before, "Convert to 'using' statement"))
        undo()
        assertEquals(before.replace("<caret>", ""), myFixture.editor.document.text)
        assertEquals(
            "class A\n{\n    async Task M()\n    {\n        await using (var reader = Open())\n        {\n        }\n    }\n}",
            apply("class A\n{\n    async Task M()\n    {\n        await using var <caret>reader = Open();\n    }\n}", "Convert to 'using' statement"),
        )
        assertFalse("not a plain local", available("class A { void M() { var <caret>r = Open(); } }", "Convert to 'using' statement"))
        assertFalse("broken: no semicolon", available("class A { void M() { using var <caret>r = Open()\n } }", "Convert to 'using' statement"))
    }

    fun testWrapInUsing() {
        val before = "class A\n{\n    void M(string path)\n    {\n        var <caret>reader = new StreamReader(path);\n        Use(reader);\n    }\n}"
        val after = "class A\n{\n    void M(string path)\n    {\n        using (var reader = new StreamReader(path))\n        {\n            Use(reader);\n        }\n    }\n}"
        assertEquals(after, apply(before, "Wrap in 'using' statement"))
        assertFalse("not a value of a call: whether it is disposable is for the semantics", available("class A { void M() { var <caret>r = Open(); } }", "Wrap in 'using' statement"))
        assertFalse("not two variables", available("class A { void M() { StreamReader <caret>a = new(p), b = new(p); } }", "Wrap in 'using' statement"))
        assertFalse("not a using declaration", available("class A { void M() { using var <caret>r = new R(); } }", "Wrap in 'using' statement"))
        assertFalse("not a const", available("class A { void M() { const string <caret>s = \"\"; } }", "Wrap in 'using' statement"))
    }

    // ---- directives

    fun testSortUsings() {
        val before = "using Shop.Orders;\nusing static System.Math;\nusing <caret>System.Linq;\nusing Json = System.Text.Json;\nusing System;\nusing Acme;\n\nclass A { }"
        val after = "using System;\nusing System.Linq;\nusing Acme;\nusing Shop.Orders;\nusing static System.Math;\nusing Json = System.Text.Json;\n\nclass A { }"
        assertEquals(after, apply(before, "Sort 'using' directives"))
        undo()
        assertEquals(before.replace("<caret>", ""), myFixture.editor.document.text)
        assertEquals(
            "global using System;\nglobal using Acme;\nusing System.IO;\nusing Zeta;",
            apply("global using Acme;\nusing Zeta;\nglobal using System;\nusing <caret>System.IO;", "Sort 'using' directives"),
        )
        assertEquals(
            "namespace N\n{\n    using System;\n    using Acme;\n}",
            apply("namespace N\n{\n    using Acme;\n    using <caret>System;\n}", "Sort 'using' directives"),
        )
        assertFalse("sorted already", available("using System;\nusing <caret>Acme;", "Sort 'using' directives"))
        assertFalse("a comment between", available("using Zeta;\n// why\nusing <caret>Acme;", "Sort 'using' directives"))
        assertFalse("broken: no semicolon", available("using Zeta\nusing <caret>Acme;", "Sort 'using' directives"))
    }

    fun testSortKey() {
        assertTrue(NativeCSharpUsingEdits.sortKey("System.Linq") < NativeCSharpUsingEdits.sortKey("Acme"))
        assertTrue(NativeCSharpUsingEdits.sortKey("acme") < NativeCSharpUsingEdits.sortKey("Beta"))
        assertTrue(NativeCSharpUsingEdits.sortKey("SystemX") > NativeCSharpUsingEdits.sortKey("Acme"))
    }

    fun testConvertToGlobalUsingMakesGlobalUsingsCs() {
        val dir = "glob${files++}"
        myFixture.addFileToProject("$dir/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""")
        val file = myFixture.addFileToProject("$dir/Models/Order.cs", "using System;\nusing Shop.Orders;\n\nclass Order { }")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Shop"))
        myFixture.launchAction(myFixture.findSingleIntention("Convert to 'global using'"))
        assertEquals("using System;\n\nclass Order { }", myFixture.editor.document.text)
        val created = myFixture.findFileInTempDir("$dir/GlobalUsings.cs")
        assertNotNull("made in the project's folder", created)
        assertEquals("global using Shop.Orders;\n", com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(created!!)!!.text)
    }

    fun testConvertToGlobalUsingJoinsTheProjectsFile() {
        val dir = "glob${files++}"
        myFixture.addFileToProject("$dir/App.csproj", """<Project Sdk="Microsoft.NET.Sdk"/>""")
        val usings = myFixture.addFileToProject("$dir/Usings.cs", "global using System;\nglobal using System.Linq;\n")
        myFixture.addFileToProject("$dir/obj/Debug/App.GlobalUsings.g.cs", "global using global::System.IO;\n")
        val file = myFixture.addFileToProject("$dir/Order.cs", "using static System.Math;\nclass Order { }")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(2)
        myFixture.launchAction(myFixture.findSingleIntention("Convert to 'global using'"))
        assertEquals("class Order { }", myFixture.editor.document.text)
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(usings)!!
        assertEquals("global using System;\nglobal using System.Linq;\nglobal using static System.Math;\n", document.text)
        assertNull("not a new file", myFixture.findFileInTempDir("$dir/GlobalUsings.cs"))
    }

    fun testConvertToGlobalUsingInAFileOfGlobalUsings() {
        assertEquals(
            "global using System;\nglobal using Acme;\nusing Zeta;\n",
            apply("global using System;\nusing <caret>Acme;\nusing Zeta;\n", "Convert to 'global using'"),
        )
        assertFalse("global already", available("global using <caret>Acme;", "Convert to 'global using'"))
        assertFalse("in a namespace it would mean another thing", available("namespace N { using <caret>Acme; }", "Convert to 'global using'"))
        assertFalse("broken: no semicolon", available("using <caret>Acme\nclass A { }", "Convert to 'global using'"))
    }

    // ---- await using / await foreach make the method async

    fun testMakeAsyncOnAwaitUsingAndForeach() {
        assertEquals(
            "class A { async Task M() { await using var r = Open(); } }",
            apply("class A { void M() { <caret>await using var r = Open(); } }", "Make method async"),
        )
        assertEquals(
            "class A { async Task<int> M() { await using (Open()) { } return 1; } }",
            apply("class A { int M() { <caret>await using (Open()) { } return 1; } }", "Make method async"),
        )
        assertEquals(
            "class A { async Task M() { await foreach (var x in Items()) { } } }",
            apply("class A { void M() { <caret>await foreach (var x in Items()) { } } }", "Make method async"),
        )
    }

    // ---- the server

    fun testTheServersRowsStandBackForNativeOnes() {
        settings.setSource(CSharpFeature.EDITING, CSharpFeatureSource.NATIVE)
        assertTrue(NativeCSharpServerActions.shadowed("Use simple 'using' statement", project))
        assertFalse(NativeCSharpServerActions.shadowed("Use implicit type", project))
        assertTrue(NativeCSharpServerActions.shadowed("Make method async", project))
        // and their Fix All rows (above the plugin's row, robot 0.1.63)
        assertTrue(NativeCSharpServerActions.shadowed("Fix All: Make method async", project))
        assertTrue(NativeCSharpServerActions.shadowed("Fix All: Use simple 'using' statement", project))
        assertFalse(NativeCSharpServerActions.shadowed("Fix All: Use implicit type", project))
        settings.setSource(CSharpFeature.EDITING, CSharpFeatureSource.ROSLYN)
        settings.setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.ROSLYN)
        assertFalse(NativeCSharpServerActions.shadowed("Use simple 'using' statement", project))
        assertFalse(NativeCSharpServerActions.shadowed("Make method async", project))
        // no server ready in tests: the native action stays
        assertTrue(available("class A { void M() { <caret>using (var r = Open()) { Use(r); } } }", "Convert to 'using' declaration"))
    }

    // ---- the playground

    /** `debug-playground/Console/Editor/Usings.cs`: every marker's place offers what its EXPECT says. */
    fun testThePlaygroundScenario() {
        val text = java.io.File("debug-playground/Console/Editor/Usings.cs").readText().replace("\r\n", "\n")
        for (marker in listOf("TYPE:using-var", "TYPE:using-await", "TYPE:using-directive", "TYPE:using-postfix", "TYPE:using-to-declaration",
            "TYPE:using-to-statement", "TYPE:using-wrap", "TYPE:using-sort", "TYPE:using-global")) assertTrue(marker, text.contains(marker))
        fun at(anchor: String, title: String) {
            val offset = text.indexOf(anchor)
            assertTrue(anchor, offset >= 0)
            myFixture.configureByText("Usings${files++}.cs", text.substring(0, offset) + "<caret>" + text.substring(offset))
            assertTrue("$title at $anchor", myFixture.filterAvailableIntentions(title).any { it.text == title })
        }
        at("using (var reader = new StringReader(\"declaration\"))", "Convert to 'using' declaration")
        at("using var writer = new StringWriter();", "Convert to 'using' statement")
        at("var wrapped = new StringReader(\"wrap\");", "Wrap in 'using' statement")
        at("using System.Text;", "Sort 'using' directives")
        at("using System.Text;", "Convert to 'global using'")
    }
}
