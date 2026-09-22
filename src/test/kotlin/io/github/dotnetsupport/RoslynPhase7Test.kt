package io.github.dotnetsupport

import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.roslyn.RoslynDecompiled
import io.github.dotnetsupport.roslyn.RoslynFileRename
import io.github.dotnetsupport.roslyn.RoslynGotoImplementationAction
import io.github.dotnetsupport.roslyn.RoslynNavigation
import java.io.File

/** Phase 7 of LSP_PLAN.md: Go to Implementation, decompiled sources, the file renamed with its type. The server is not started. */
class RoslynPhase7Test : BasePlatformTestCase() {
    private fun record(name: String) = File(javaClass.getResource("/roslyn/capture-5.12/01-initialize.json")!!.toURI()).parentFile
        .listFiles()!!.single { it.name.endsWith("-$name.json") }.let { JsonParser.parseString(it.readText()).asJsonObject }

    /** The action of the platform, replaced under its own id: its shortcut and texts stay, other languages still get the handler of the platform. */
    fun testGotoImplementationIsReplaced() {
        val action = ActionManager.getInstance().getAction("GotoImplementation")
        assertTrue(action.javaClass.name, action is RoslynGotoImplementationAction)
        assertFalse(action.templatePresentation.text.isNullOrBlank())
    }

    /** A method chosen in completion gets `()` (Roslyn sends the bare name), except as an event handler and when chosen by `.` / `;`. */
    fun testParenthesesOfAChosenMethod() {
        val policy = io.github.dotnetsupport.roslyn.RoslynCompletionPolicy
        assertTrue(policy.isCallable(org.eclipse.lsp4j.CompletionItemKind.Method))
        assertFalse("a property", policy.isCallable(org.eclipse.lsp4j.CompletionItemKind.Property))
        assertFalse("new Person: the type", policy.isCallable(org.eclipse.lsp4j.CompletionItemKind.Class))

        val call = "        Console.WriteLine"
        val start = call.indexOf("WriteLine")
        assertTrue("Enter", policy.addsParentheses(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR, call, start, call.length))
        assertTrue("Tab", policy.addsParentheses(com.intellij.codeInsight.lookup.Lookup.REPLACE_SELECT_CHAR, call, start, call.length))
        assertTrue("typed (", policy.addsParentheses('(', call, start, call.length))
        assertFalse("typed .", policy.addsParentheses('.', call, start, call.length))
        assertFalse("( opens the parameter info, not the list of types", policy.isTrigger('('))
        assertTrue(policy.isTrigger('.'))
        assertTrue(policy.isTrigger(' '))

        val subscription = "void M()\n{\n    PriceFeed.Changed += OnChanged"
        assertFalse("an event handler is a method group", policy.addsParentheses(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR, subscription,
            subscription.indexOf("OnChanged"), subscription.length))
        val later = "x += 1;\nFoo"
        assertTrue("+= of another line", policy.addsParentheses(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR, later, later.indexOf("Foo"), later.length))
    }

    /** Ctrl+P lists every overload; the parameters come from the label of the signature, as Roslyn names a parameter by its name only. */
    fun testParametersOfASignature() {
        val signatures = io.github.dotnetsupport.roslyn.RoslynSignatures
        fun signature(label: String, vararg names: String) = org.eclipse.lsp4j.SignatureInformation(label).apply {
            parameters = names.map { org.eclipse.lsp4j.ParameterInformation(it) }
        }
        assertEquals(emptyList<String>(), signatures.parameters(signature("void Console.WriteLine()")))
        assertEquals(listOf("string format", "object? arg0"), signatures.parameters(signature("void Console.WriteLine(string format, object? arg0)", "format", "arg0")))
        assertEquals("commas inside generic arguments do not split", listOf("Dictionary<string, int> map", "params ReadOnlySpan<object?> arg"),
            signatures.parameters(signature("void M(Dictionary<string, int> map, params ReadOnlySpan<object?> arg)", "map", "arg")))
        assertEquals("a generic method", listOf("T value"), signatures.parameters(signature("void List<T>.Add<T>(T value)", "value")))

        val two = listOf("string format", "object? arg0")
        assertEquals(0 until 13, signatures.rangeOf(two, 0))
        assertEquals(15 until 27, signatures.rangeOf(two, 1))
        assertNull("past the last parameter", signatures.rangeOf(two, 2))
        assertEquals("params takes the rest", 15 until 36, signatures.rangeOf(listOf("string format", "params object?[]? arg"), 5))
    }

    /** Alt+Enter lists every action once: refactorings as context actions, fixes of a diagnostic with that diagnostic (kinds as recorded). */
    fun testEveryCodeActionOnce() {
        val policy = io.github.dotnetsupport.roslyn.RoslynCodeActionPolicy
        fun action(title: String, kind: String?, vararg codes: String) = org.eclipse.lsp4j.CodeAction(title).apply {
            this.kind = kind
            diagnostics = codes.map { org.eclipse.lsp4j.Diagnostic().apply { code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(it) } }
        }
        val useImplicitType = action("Use implicit type", "refactor")
        val extract = action("Extract method", "refactor.extract")
        val remove = action("Remove unused variable", "quickfix", "CS0219")
        val suppress = action("Suppress or configure issues", "quickfix")

        assertFalse("a refactoring is a context action", policy.isFixOfDiagnostic(useImplicitType))
        assertFalse(policy.isFixOfDiagnostic(extract))
        assertTrue(policy.isFixOfDiagnostic(remove))
        assertTrue(policy.isFixOfDiagnostic(suppress))

        assertTrue(policy.isContextAction(useImplicitType))
        assertFalse("listed with its diagnostic already", policy.isContextAction(remove))
        assertTrue("a fix that names no diagnostic stays where it comes", policy.isContextAction(suppress))
        assertTrue("no kind at all", policy.isContextAction(action("Something", null)) && policy.isFixOfDiagnostic(action("Something", null)))
    }

    /** The tail and the type of a completion row, from the documentation of a resolved item (strings as the server sent them). */
    fun testSignatureTails() {
        val tails = io.github.dotnetsupport.roslyn.RoslynSignatureTail
        val nl = "\r\n"
        val writeLine = tails.parse("```csharp${nl}void Console.WriteLine()$nl```$nl&nbsp;\\(\\+ 18 overloads\\)  ${nl}Writes the current line terminator.", "WriteLine")!!
        assertEquals("()  +18 overloads", writeLine.tail)
        assertEquals("void", writeLine.type)
        val beep = tails.parse("```csharp${nl}void Console.Beep()$nl```$nl&nbsp;\\(\\+ 1 overload\\)", "Beep")!!
        assertEquals("()  +1 overload", beep.tail)
        val title = tails.parse("```csharp${nl}string Console.Title { get; set; }$nl```", "Title")!!
        assertNull("a property has no parameters", title.tail)
        assertEquals("string", title.type)
        val readLine = tails.parse("```csharp${nl}string? Console.ReadLine()$nl```", "ReadLine")!!
        assertEquals("()", readLine.tail)
        assertEquals("string?", readLine.type)
        val generic = tails.parse("```csharp${nl}Task<Dictionary<string, int>> Service.LoadAsync<T>(T key, CancellationToken token)$nl```", "LoadAsync")!!
        assertEquals("<T>(T key, CancellationToken token)", generic.tail)
        assertEquals("a type with a space inside its generic arguments", "Task<Dictionary<string, int>>", generic.type)
        val local = tails.parse("```csharp$nl(local variable) int count$nl```", "count")!!
        assertEquals("int", local.type)
        assertNull("no signature, no tail", tails.parse("Just text", "WriteLine"))
    }

    /** `await` comes with the typed text as its edit (`p`): it is matched by its label, so it leaves the list for `p` and stays for `aw`. */
    fun testAwaitIsMatchedByItsLabel() {
        val policy = io.github.dotnetsupport.roslyn.RoslynCompletionPolicy
        val await = org.eclipse.lsp4j.CompletionItem("await").apply { textEditText = "p"; kind = org.eclipse.lsp4j.CompletionItemKind.Keyword }
        assertEquals("await", policy.lookupStringOverride(await))
        assertNull("an ordinary item", policy.lookupStringOverride(org.eclipse.lsp4j.CompletionItem("person")))
        assertNull("the edit is the label", policy.lookupStringOverride(org.eclipse.lsp4j.CompletionItem("typeof").apply { textEditText = "typeof" }))

        // what the platform builds for it: the edit as the lookup string, the label beside it
        val platform = com.intellij.codeInsight.lookup.LookupElementBuilder.create(await, "p").withLookupString("await")
        assertTrue("before: p matches", com.intellij.codeInsight.completion.impl.CamelHumpMatcher("p").prefixMatches(platform))
        val fixed = io.github.dotnetsupport.roslyn.MatchedByLabel(platform, "await")
        assertFalse(com.intellij.codeInsight.completion.impl.CamelHumpMatcher("p").prefixMatches(fixed))
        assertTrue(com.intellij.codeInsight.completion.impl.CamelHumpMatcher("aw").prefixMatches(fixed))
    }

    fun testWordAt() {
        val text = "shape.Area();"
        assertEquals("Area", RoslynNavigation.wordAt(text, text.indexOf("Area")))
        assertEquals("Area", RoslynNavigation.wordAt(text, text.indexOf("Area") + 2))
        assertEquals("Area", RoslynNavigation.wordAt(text, text.indexOf("(")))
        assertEquals("", RoslynNavigation.wordAt(text, text.length))
        assertEquals("shape", RoslynNavigation.wordAt(text, 0))
    }

    /** The answer the server gives for a call of an interface method (recorded): points at the names of the implementations. */
    fun testImplementationsAsRecorded() {
        val locations = record("phase7_implementation_of_a_call_of_an_interface_method").getAsJsonArray("result")
        assertEquals(2, locations.size())
        assertTrue(locations.all { it.asJsonObject.get("uri").asString.endsWith("/Console/Scenarios.cs") })
    }

    /** The head of a decompiled file as Roslyn writes it (recorded), and where such files live. */
    fun testDecompiledSource() {
        val head = record("phase7_decompiled_file_head").getAsJsonObject("result")
        val origin = RoslynDecompiled.origin(head.getAsJsonArray("head").joinToString("\n") { it.asString })!!
        assertEquals("System.Console", origin.assembly)
        assertEquals("9.0.0.0", origin.version)
        assertTrue(origin.path!!.endsWith("System.Console.dll"))
        assertTrue(RoslynDecompiled.isDecompiledPath(head.get("uri").asString))
        // the definitions inside a decompiled file lead to other decompiled files
        assertTrue(RoslynDecompiled.isDecompiledPath(record("phase7_decompiled_definition").getAsJsonArray("result")[0].asJsonObject.get("uri").asString))
        assertTrue(RoslynDecompiled.isDecompiledPath("C:\\Users\\me\\AppData\\Local\\Temp\\MetadataAsSource\\a\\b\\List.cs"))
        assertFalse(RoslynDecompiled.isDecompiledPath("C:/work/Shop/Order.cs"))
        assertNull(RoslynDecompiled.origin("namespace Shop;\nclass Order { }"))
        // and the server does not report problems in it
        assertEquals(0, record("phase7_decompiled_diagnostic").getAsJsonObject("result").getAsJsonArray("items").size())
    }

    fun testDecompiledFileIsReadOnly() {
        val decompiled = myFixture.addFileToProject("MetadataAsSource/1/DecompilationMetadataAsSourceFileProvider/2/Console.cs",
            "#region Assembly System.Console, Version=10.0.0.0, Culture=neutral\n// C:\\packs\\System.Console.dll\n#endregion\nnamespace System;").virtualFile
        val ordinary = myFixture.addFileToProject("Phase7/Order.cs", "class Order { }").virtualFile
        val access = io.github.dotnetsupport.roslyn.RoslynDecompiledWritingAccess(project)
        assertFalse(access.isPotentiallyWritable(decompiled))
        assertTrue(access.isPotentiallyWritable(ordinary))
        assertEquals(listOf(decompiled), access.requestWriting(listOf(decompiled, ordinary)).toList())
        assertEquals("Console.cs [System.Console]", io.github.dotnetsupport.roslyn.RoslynDecompiledTabTitle().getEditorTabTitle(project, decompiled))
        assertNull(io.github.dotnetsupport.roslyn.RoslynDecompiledTabTitle().getEditorTabTitle(project, ordinary))
    }

    /** The recorded rename of `Scenarios` edits the text of two files and renames no file: that is what the plugin adds. */
    fun testServerRenamesNoFile() {
        val changes = record("phase7_rename_of_a_type_named_as_its_file").getAsJsonObject("result").getAsJsonArray("documentChanges")
        assertTrue(changes.size() >= 2)
        assertTrue("only text edits", changes.all { it.asJsonObject.has("edits") && !it.asJsonObject.has("kind") })
    }

    fun testWhichFileIsRenamed() {
        val order = "namespace Shop;\n\npublic class Order\n{\n    public void Order2() { }\n}\n"
        val nameAt = order.indexOf("Order\n")
        val program = "var order = new Order();"
        fun edited(vararg files: Pair<String, Pair<String, List<Int>>>) = files.associate { (path, value) -> path to (value.first as CharSequence to value.second) }

        assertEquals("C:/w/Order.cs", RoslynFileRename.fileToRename(edited("C:/w/Program.cs" to (program to listOf(16)), "C:/w/Order.cs" to (order to listOf(nameAt))), "Order", "Purchase"))
        assertEquals("backslashes", "C:\\w\\Order.cs", RoslynFileRename.fileToRename(edited("C:\\w\\Order.cs" to (order to listOf(nameAt))), "Order", "Purchase"))
        // the edit is already in: the type is found under its new name
        val applied = order.replaceFirst("class Order", "class Purchase")
        assertEquals("C:/w/Order.cs", RoslynFileRename.fileToRename(edited("C:/w/Order.cs" to (applied to listOf(nameAt))), "Order", "Purchase"))
        // not the declaration of the type: a method elsewhere in the file with the name of the file
        assertNull(RoslynFileRename.fileToRename(edited("C:/w/Order.cs" to (order to listOf(order.indexOf("Order2")))), "Order", "Purchase"))
        // the file is named otherwise, the name is not valid, nothing changes
        assertNull(RoslynFileRename.fileToRename(edited("C:/w/Orders.cs" to (order to listOf(nameAt))), "Order", "Purchase"))
        assertNull(RoslynFileRename.fileToRename(edited("C:/w/Order.cs" to (order to listOf(nameAt))), "Order", "Pur chase"))
        assertNull(RoslynFileRename.fileToRename(edited("C:/w/Order.cs" to (order to listOf(nameAt))), "Order", "Order"))

        assertTrue(RoslynFileRename.isApplied(applied, "Order", "Purchase"))
        assertFalse(RoslynFileRename.isApplied(order, "Order", "Purchase"))
        assertTrue(RoslynFileRename.isIdentifier("@class"))
        assertFalse(RoslynFileRename.isIdentifier("1st"))
    }
}
