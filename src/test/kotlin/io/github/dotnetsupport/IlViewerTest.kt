package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.il.IlAnswer
import io.github.dotnetsupport.il.IlAssemblyLocator
import io.github.dotnetsupport.il.IlBody
import io.github.dotnetsupport.il.IlCaret
import io.github.dotnetsupport.il.IlHighlighter
import io.github.dotnetsupport.il.IlLexer
import io.github.dotnetsupport.il.IlLexing
import io.github.dotnetsupport.il.IlLineMapping
import io.github.dotnetsupport.il.IlRequest
import io.github.dotnetsupport.il.IlSource
import io.github.dotnetsupport.il.IlTokenKind
import io.github.dotnetsupport.il.IlViewState
import io.github.dotnetsupport.il.IlViewerLogic
import io.github.dotnetsupport.il.IlViewerPanel
import io.github.dotnetsupport.il.IlViewerService
import io.github.dotnetsupport.il.IlViewerToolWindowFactory
import io.github.dotnetsupport.il.IlHelperSource
import java.io.File

/** The IL Viewer on a fake source of IL: the states, the names for the helper, the bodies, the lines matched both ways, the colors. */
class IlViewerTest : BasePlatformTestCase() {
    private val addIl = """
        .method public hidebysig static int32 Add(int32 a, int32 b) cil managed
        {
          // Code size       9 (0x9)
          .maxstack  2
          .locals init (int32 V_0)
          IL_0000:  nop
          IL_0001:  ldarg.0
          IL_0002:  ldarg.1
          IL_0003:  add
          IL_0004:  stloc.0
          IL_0005:  br.s       IL_0007
          IL_0007:  ldloc.0
          IL_0008:  ret
        } // end of method Calc::Add
    """.trimIndent()

    // `{` of line 10, `return a + b;` of line 11, `}` of line 12
    private val addMapping = listOf(IlLineMapping(5, 0, 10, 5, 10, 6), IlLineMapping(6, 1, 11, 9, 11, 22), IlLineMapping(11, 7, 12, 5, 12, 6))
    private val add = IlBody("Calc::Add", IlBody.Kind.METHOD, addIl, atCaret = true, mapping = addMapping)

    private var source: IlSource? = null
    private var locator: IlAssemblyLocator? = null

    override fun setUp() {
        super.setUp()
        val service = IlViewerService.getInstance(project)
        source = service.source
        locator = service.locator
    }

    override fun tearDown() {
        try {
            val service = IlViewerService.getInstance(project)
            source?.let { service.source = it }
            locator?.let { service.locator = it }
        } finally {
            super.tearDown()
        }
    }

    fun testNamesForTheHelper() {
        val text = """
            namespace Shop.Orders
            {
                public class Cache<K, V>
                {
                    public class Entry
                    {
                        public int Size() { return 1; }
                    }
                    static Cache() { }
                    public Cache() { }
                    ~Cache() { }
                    public V this[K key] => default!;
                    public static Cache<K, V> operator +(Cache<K, V> a, Cache<K, V> b) => a;
                    public static Cache<K, V> operator -(Cache<K, V> a) => a;
                    public static explicit operator int(Cache<K, V> a) => 0;
                    private int count;
                }
            }
        """.trimIndent()
        fun at(marker: String) = IlViewerLogic.names(text, text.indexOf(marker))
        assertEquals("Shop.Orders.Cache`2+Entry" to "Size", at("return 1"))
        assertEquals("Shop.Orders.Cache`2" to ".cctor", at("static Cache()"))
        assertEquals("Shop.Orders.Cache`2" to ".ctor", at("public Cache()"))
        assertEquals("Shop.Orders.Cache`2" to "Finalize", at("~Cache"))
        assertEquals("Shop.Orders.Cache`2" to "Item", at("this[K key]"))
        assertEquals("Shop.Orders.Cache`2" to "op_Addition", at("operator +"))
        assertEquals("Shop.Orders.Cache`2" to "op_UnaryNegation", at("operator -"))
        assertEquals("Shop.Orders.Cache`2" to "op_Explicit", at("explicit operator"))
        assertEquals("Shop.Orders.Cache`2" to "count", at("count;"))
        assertEquals("the type header: no member", "Shop.Orders.Cache`2" to null, at("class Cache"))
        assertEquals("outside of any type", null to null, at("namespace"))

        val fileScoped = "namespace App;\n\nstatic class Program\n{\n    static void Main() { }\n}\n"
        assertEquals("App.Program" to "Main", IlViewerLogic.names(fileScoped, fileScoped.indexOf("{ }")))
        val global = "class Plain { int Twice(int x) => x * 2; }"
        assertEquals("Plain" to "Twice", IlViewerLogic.names(global, global.indexOf("x * 2")))
    }

    fun testOrderAndChoiceOfBodies() {
        val method = IlBody("Worker::RunAsync", IlBody.Kind.METHOD, "", atCaret = false, mapping = emptyList())
        val machine = IlBody("Worker/'<RunAsync>d__0'::MoveNext", IlBody.Kind.STATE_MACHINE, "", atCaret = true, mapping = emptyList())
        val lambda = IlBody("Worker/'<>c'::'<RunAsync>b__0_0'", IlBody.Kind.LAMBDA, "", atCaret = false, mapping = emptyList())
        val ordered = IlViewerLogic.ordered(listOf(method, machine, lambda))
        assertEquals("the body at the caret first, the rest as the helper sent them", listOf(machine, method, lambda), ordered)
        assertEquals(0, IlViewerLogic.choose(ordered, previous = null, sameMember = true))
        assertEquals("the choice of the user stays in the same member", 2, IlViewerLogic.choose(ordered, lambda.name, sameMember = true))
        assertEquals("and not in another one", 0, IlViewerLogic.choose(ordered, lambda.name, sameMember = false))
        assertEquals("a body that is gone: the first", 0, IlViewerLogic.choose(ordered, "Worker::Gone", sameMember = true))
        assertEquals("state machine", IlViewerLogic.kindTitle(IlBody.Kind.STATE_MACHINE))
        assertEquals("local function", IlViewerLogic.kindTitle(IlBody.Kind.LOCAL_FUNCTION))
    }

    fun testStaleBuild() {
        assertFalse(IlViewerLogic.isStale(assemblyModified = 2000, sourceModified = 1000, projectModified = 1000, sourceUnsaved = false))
        assertTrue("a source saved after the build", IlViewerLogic.isStale(2000, 3000, 1000, false))
        assertTrue("a project file saved after the build", IlViewerLogic.isStale(2000, 1000, 3000, false))
        assertTrue("a change not saved yet", IlViewerLogic.isStale(2000, 1000, 1000, true))
    }

    fun testSourceLineToIlLines() {
        assertEquals("`return a + b;`: its instructions up to the next sequence point", (6..10).toList(), IlViewerLogic.ilLines(add, 11))
        assertEquals(listOf(5), IlViewerLogic.ilLines(add, 10))
        assertEquals("the last one stops at the end of the instructions, not at `}`", listOf(11, 12), IlViewerLogic.ilLines(add, 12))
        assertEquals("a line with no code", emptyList<Int>(), IlViewerLogic.ilLines(add, 9))
        // a statement over several lines lights up from any of them
        val multiLine = add.copy(mapping = listOf(IlLineMapping(6, 1, 11, 9, 13, 10)))
        assertEquals((6..12).toList(), IlViewerLogic.ilLines(multiLine, 12))
    }

    fun testIlLineToSourceRange() {
        assertEquals(addMapping[1], IlViewerLogic.sourceRange(add, 8))
        assertEquals(addMapping[0], IlViewerLogic.sourceRange(add, 5))
        assertEquals(addMapping[2], IlViewerLogic.sourceRange(add, 12))
        assertNull("the header of the method", IlViewerLogic.sourceRange(add, 3))
        assertNull("the closing brace", IlViewerLogic.sourceRange(add, 13))

        val source = (1..9).joinToString("") { "// $it\n" } + "    {\n        return a + b;\n    }\n"
        val range = IlViewerLogic.sourceTextRange(source, addMapping[1])!!
        assertEquals("return a + b;", range.substring(source))
        assertEquals("{", IlViewerLogic.sourceTextRange(source, addMapping[0])!!.substring(source))
        assertNull("a line past the end of the file", IlViewerLogic.sourceTextRange(source, IlLineMapping(0, 0, 40, 1, 40, 2)))
    }

    fun testLexer() {
        val il = """
            .method public hidebysig static void Main() cil managed
            {
              .entrypoint
              // Code size       27 (0x1b)
              .maxstack  8
              IL_0000:  ldstr      "Hi \"there\""
              IL_0005:  call       void [System.Console]System.Console::WriteLine(string)
              IL_000a:  newobj     instance void Program/'<>c'::.ctor()
              IL_000f:  constrained. !!T
              IL_0015:  callvirt   instance string [System.Runtime]System.Object::ToString()
              IL_0016:  ldc.i4.s   10
              IL_0018:  br.s       IL_0000
              IL_001a:  add
            } // end of method Program::Main
        """.trimIndent()
        val tokens = IlLexing.tokens(il)
        assertEquals("the tokens cover the text", il.length, tokens.sumOf { it.end - it.start })
        fun kindOf(word: String, occurrence: Int = 0): IlTokenKind {
            var from = il.indexOf(word)
            repeat(occurrence) { from = il.indexOf(word, from + 1) }
            return tokens.single { it.start == from }.also { assertEquals(word, il.substring(it.start, it.end)) }.kind
        }
        assertEquals(IlTokenKind.DIRECTIVE, kindOf(".method"))
        assertEquals(IlTokenKind.DIRECTIVE, kindOf(".maxstack"))
        assertEquals(IlTokenKind.DIRECTIVE, kindOf(".entrypoint"))
        assertEquals(IlTokenKind.KEYWORD, kindOf("hidebysig"))
        assertEquals(IlTokenKind.KEYWORD, kindOf("managed"))
        assertEquals(IlTokenKind.COMMENT, kindOf("// Code size       27 (0x1b)"))
        assertEquals(IlTokenKind.NUMBER, kindOf("8"))
        assertEquals(IlTokenKind.LABEL, kindOf("IL_0000"))
        assertEquals(IlTokenKind.OPCODE, kindOf("ldstr"))
        assertEquals(IlTokenKind.STRING, kindOf("\"Hi \\\"there\\\"\""))
        assertEquals(IlTokenKind.OPCODE, kindOf("call"))
        assertEquals(IlTokenKind.TYPE_NAME, kindOf("[System.Console]System.Console"))
        assertEquals(IlTokenKind.IDENTIFIER, kindOf("WriteLine"))
        assertEquals(IlTokenKind.TYPE_NAME, kindOf("Program/'<>c'"))
        assertEquals(IlTokenKind.IDENTIFIER, kindOf(".ctor"))
        assertEquals("a prefix opcode", IlTokenKind.OPCODE, kindOf("constrained."))
        assertEquals(IlTokenKind.OPCODE, kindOf("ldc.i4.s"))
        assertEquals(IlTokenKind.OPCODE, kindOf("br.s"))
        assertEquals("the target of a branch", IlTokenKind.LABEL, kindOf("IL_0000", 1))
        assertEquals("an opcode after a label, not a name", IlTokenKind.OPCODE, kindOf("add"))
        assertEquals(IlTokenKind.COMMENT, kindOf("// end of method Program::Main"))

        // the editor lexer gives the same tokens, each with a color
        val lexer = IlLexer()
        lexer.start(il)
        val highlighter = IlHighlighter()
        var count = 0
        while (lexer.tokenType != null) {
            val token = tokens[count++]
            assertEquals(token.start, lexer.tokenStart)
            if (token.kind != IlTokenKind.WHITESPACE) assertTrue(highlighter.getTokenHighlights(lexer.tokenType!!).isNotEmpty())
            lexer.advance()
        }
        assertEquals(tokens.size, count)
    }

    fun testStates() {
        myFixture.addFileToProject("IlStates/IlStates.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        val code = myFixture.addFileToProject("IlStates/Calc.cs", "namespace IlStates;\n\npublic static class Calc\n{\n    public static int Add(int a, int b) => a + b;\n}\n").virtualFile
        val readme = myFixture.addFileToProject("IlStates/readme.txt", "text").virtualFile
        val loose = myFixture.addFileToProject("IlLoose/Loose.cs", "class Loose { }").virtualFile
        val assembly = File.createTempFile("IlStates", ".dll").apply { deleteOnExit() }
        val service = IlViewerService.getInstance(project)
        val requests = ArrayList<IlRequest>()
        var answer: (IlRequest) -> IlAnswer = { IlAnswer(it.assembly, Long.MAX_VALUE, null, listOf(add)) }
        service.source = object : IlSource {
            override fun il(request: IlRequest): IlAnswer = answer(request).also { requests += request }
        }
        var assemblyPath: String? = null
        service.locator = IlAssemblyLocator { assemblyPath }
        fun caret(file: VirtualFile, marker: String = "", unsaved: Boolean = false): IlCaret {
            val text = String(file.contentsToByteArray())
            val offset = text.indexOf(marker).coerceAtLeast(0)
            return IlCaret(file, text, offset, text.substring(0, offset).count { it == '\n' } + 1, unsaved)
        }

        assertEquals(IlViewState.Empty(IlViewState.NOT_CSHARP), service.compute(caret(readme)))
        assertEquals(IlViewState.Empty(IlViewState.NO_PROJECT), service.compute(caret(loose)))
        assertEquals("MSBuild cannot tell", IlViewState.NotBuilt(code.parent.findChild("IlStates.csproj")!!.path), service.compute(caret(code, "a + b")))
        assemblyPath = assembly.path + ".missing"
        assertTrue("no assembly on disk", service.compute(caret(code, "a + b")) is IlViewState.NotBuilt)
        assertTrue(requests.isEmpty())

        assemblyPath = assembly.path
        val shown = service.compute(caret(code, "a + b")) as IlViewState.Shown
        assertFalse(shown.stale)
        assertEquals(IlRequest(assembly.path, File(code.path).path, 5, "IlStates.Calc", "Add"), requests.single())
        assertTrue("a change not saved yet", (service.compute(caret(code, "a + b", unsaved = true)) as IlViewState.Shown).stale)
        answer = { IlAnswer(it.assembly, 0, null, listOf(add)) }
        assertTrue("the source is newer than the build", (service.compute(caret(code, "a + b")) as IlViewState.Shown).stale)

        answer = { throw HelperException("the assembly has no metadata") }
        assertEquals("the message of the helper", "the assembly has no metadata", (service.compute(caret(code, "a + b")) as IlViewState.Failed).message)
        answer = { throw IllegalStateException("boom") }
        assertTrue((service.compute(caret(code, "a + b")) as IlViewState.Failed).message.contains("boom"))
    }

    fun testPanel() {
        val panel = IlViewerPanel(project, testRootDisposable) { false }
        assertEquals(IlViewState.NOT_CSHARP, panel.statusText)

        panel.render(IlViewState.NotBuilt("/p/App.csproj"))
        assertEquals(IlViewState.NOT_BUILT, panel.statusText)
        assertEquals("", panel.shownText)

        panel.render(IlViewState.Failed("the helper has exited", "/p/App.csproj"))
        assertEquals("the helper has exited", panel.statusText)

        val request = IlRequest("/p/App.dll", "/p/Worker.cs", 11, "App.Worker", "RunAsync")
        val method = IlBody("Worker::RunAsync", IlBody.Kind.METHOD, ".method RunAsync", atCaret = false, mapping = emptyList())
        val machine = add.copy(name = "Worker/'<RunAsync>d__0'::MoveNext", kind = IlBody.Kind.STATE_MACHINE)
        panel.render(IlViewState.Shown(request, IlAnswer("/p/App.dll", 0, null, listOf(method, machine), warning = "No PDB next to the assembly"), "/p/App.csproj", stale = true))
        assertEquals(listOf(machine.name, method.name), panel.bodyNames)
        assertEquals("the body at the caret is shown first", addIl, panel.shownText)
        assertTrue(panel.isStaleBannerShown)
        assertEquals("No PDB next to the assembly", panel.warningText)

        panel.select(method.name)
        assertEquals(".method RunAsync", panel.shownText)
        panel.render(IlViewState.Shown(request.copy(line = 12), IlAnswer("/p/App.dll", 0, null, listOf(method, machine)), "/p/App.csproj", stale = false))
        assertEquals("the choice stays while the caret is in the same member", ".method RunAsync", panel.shownText)
        assertFalse(panel.isStaleBannerShown)
        assertNull(panel.warningText)
        panel.render(IlViewState.Shown(request.copy(memberName = "Other"), IlAnswer("/p/App.dll", 0, null, listOf(method, machine)), "/p/App.csproj", stale = false))
        assertEquals("another member: the body at the caret again", addIl, panel.shownText)

        panel.render(IlViewState.Shown(request, IlAnswer("/p/App.dll", 0, null, emptyList(), warning = "Nothing at line 11"), "/p/App.csproj", stale = false))
        assertEquals(IlViewState.NOTHING_HERE, panel.statusText)
        assertEquals("Nothing at line 11", panel.warningText)
    }

    fun testRegistration() {
        val window = ToolWindowEP.EP_NAME.extensionList.single { it.id == IlViewerToolWindowFactory.ID }
        assertEquals(IlViewerToolWindowFactory::class.java.name, window.factoryClass)
        assertEquals("right", window.anchor)
        assertEquals("IL Viewer", IlViewerToolWindowFactory.ID)

        val actions = ActionManager.getInstance()
        assertEquals("IL Viewer", actions.getAction("DotNet.IlViewer").templatePresentation.text)
        val menu = (actions.getAction("DotNet.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertTrue(menu.toString(), "DotNet.IlViewer" in menu)
        assertTrue("the helper is the source by default", source is IlHelperSource)
    }
}
