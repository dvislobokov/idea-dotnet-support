package io.github.dotnetsupport

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.index.AssemblyDocs
import io.github.dotnetsupport.index.AssemblyIndex
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpGhostCalls
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/** A gray text that ends on the name of a method gets its call, as the row of the list writes it (0.1.167): `.Sel` + `ect` → `Select(|)`. */
class GhostCallsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { ASSEMBLIES }
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

    /** [line] in a method, `<caret>` where the gray text ended; the line after [NativeCSharpGhostCalls.closeCall], `|` at the caret. */
    private fun after(line: String): String {
        val text = "using System;\nusing System.Linq;\nusing System.Collections.Generic;\n\nclass Orders\n{\n    public int Count { get; set; }\n" +
            "    void Save() { }\n    event EventHandler Changed;\n    void OnChanged(object s, EventArgs e) { }\n" +
            "    void M(List<int> items)\n    {\n        $line\n    }\n}\n"
        myFixture.configureByText("GhostCalls${counter++}.cs", text)
        WriteCommandAction.runWriteCommandAction(project) { NativeCSharpGhostCalls.closeCall(myFixture.editor) }
        val document = myFixture.editor.document
        val caret = myFixture.editor.caretModel.offset
        val start = document.getLineStartOffset(document.getLineNumber(caret))
        val end = document.getLineEndOffset(document.getLineNumber(caret))
        return (document.charsSequence.subSequence(start, caret).toString() + "|" + document.charsSequence.subSequence(caret, end)).trim()
    }

    fun testALinqMethodGetsItsParentheses() {
        assertEquals("var x = items.Select(|);", after("var x = items.Select<caret>"))
    }

    fun testAMethodWithoutArgumentsEndsTheStatement() {
        assertEquals("Save();|", after("Save<caret>"))
    }

    fun testNoCallWhereNoMethodOrAlreadyACall() {
        assertEquals("var c = Count|", after("var c = Count<caret>"))
        assertEquals("var x = items.Select|(i => i)", after("var x = items.Select<caret>(i => i)"))
        assertEquals("Changed += OnChanged|;", after("Changed += OnChanged<caret>;"))
        assertEquals("var n = nameof(Save|)", after("var n = nameof(Save<caret>)"))
    }

    companion object {
        private var counter = 0
        private fun bytes(name: String): ByteArray? = GhostCallsTest::class.java.getResourceAsStream("/index/$name")?.use { it.readBytes() }
        private fun fixture(name: String): AssemblyIndex = AssemblyIndex.read(bytes("$name.dnix")!!, bytes("$name.dnxd")?.let(AssemblyDocs::read))

        val ASSEMBLIES: AssemblyIndexSet by lazy {
            AssemblyIndexSet(listOf("IndexFixture", "System.Runtime", "System.Console", "System.Linq", "System.Collections").map(::fixture))
        }
    }
}
