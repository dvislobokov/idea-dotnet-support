package io.github.dotnetsupport.ml

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpSyntaxTrees

/**
 * The hard gate of the grey text: nothing inside a string or character literal or in a comment, code again right after the closing
 * quote — decided from the PSI leaf at the caret on either tree, or from the host lexer while the document is not committed.
 */
class CSharpNnInlineGateTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { CSharpSyntaxTrees.forceNativeTreeForTests(null) } finally { super.tearDown() }
    }

    private val file = """
        using System;

        /* a block
           comment */
        class Program
        {
            /// <summary>doc</summary>
            static void Main()
            {
                var s = "hello";
                var v = @"C:\temp";
                var i = ${'$'}"{s} and {s.Length}";
                var c = 'x';
                Console.WriteLine(s); // trailing
                //
            }
        }
    """.trimIndent() + "\n"

    private fun gate(native: Boolean, marker: String, at: Int = 0): Boolean {
        val offset = file.indexOf(marker) + at
        check(offset >= at) { "no $marker" }
        CSharpSyntaxTrees.forceNativeTreeForTests(native)
        myFixture.configureByText("Program.cs", file)
        return inGate(offset)
    }

    private fun inGate(offset: Int): Boolean = CSharpNnInline.inStringOrComment(myFixture.file, myFixture.editor.document, offset)

    private fun checkBothTrees() {
        for (native in listOf(true, false)) {
            val tree = if (native) "native" else "heuristic"
            // inside string and character literals
            assertTrue(tree, gate(native, "hello", 2))
            assertTrue(tree, gate(native, "\"hello\"", 1))               // right after the opening quote
            assertTrue(tree, gate(native, "\"hello\"", 6))               // before the closing quote
            assertTrue(tree, gate(native, "C:\\temp", 2))
            assertTrue(tree, gate(native, "'x'", 1))
            assertTrue(tree, gate(native, "'x'", 2))
            assertTrue(tree, gate(native, "{s} and", 4))                 // the text of the interpolated string
            // right after the closing quote: code
            assertFalse(tree, gate(native, "\"hello\"", 7))
            assertFalse(tree, gate(native, "C:\\temp\"", 8))
            assertFalse(tree, gate(native, "'x'", 3))
            assertFalse(tree, gate(native, "s.Length}\"", 10))
            assertFalse(tree, gate(native, "var s = ", 8))               // before the opening quote
            assertFalse(tree, gate(native, "Console.WriteLine", 7))
            // comments
            assertTrue(tree, gate(native, "// trailing", 5))
            assertTrue(tree, gate(native, "// trailing", 11))            // at the very end of the line comment
            assertTrue(tree, gate(native, "//\n", 2))                    // `// ⟨⟩` on an empty line comment: still suppressed
            assertTrue(tree, gate(native, "doc</summary>", 1))          // a doc comment
            assertTrue(tree, gate(native, "a block", 3))
            assertTrue(tree, gate(native, "comment */", 9))
            assertFalse(tree, gate(native, "comment */", 10))           // right after `*/`
            assertFalse(tree, gate(native, "class Program", 0))         // the line after the block comment
            assertFalse(tree, gate(native, "static void", 0))           // the line after the doc comment
            assertFalse(tree, gate(native, "    }\n}", 0))              // the line after the empty line comment
        }
    }

    fun testStringsAndCommentsOnBothTrees() = checkBothTrees()

    fun testAHoleOfAnInterpolatedStringIsCodeOnTheNativeTree() {
        assertFalse(gate(true, "{s.Length}", 9))     // `{s.Length⟨⟩}`
        assertFalse(gate(true, "{s} and", 2))        // `{s⟨⟩}`
    }

    fun testStartOfFile() {
        myFixture.configureByText("Program.cs", file)
        assertFalse(inGate(0))
    }

    fun testUncommittedDocumentUsesTheLexer() {
        myFixture.configureByText("Program.cs", file)
        val document = myFixture.editor.document
        val offset = file.indexOf("Console.WriteLine")
        // the user typed the start of a string: the document is ahead of the PSI
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, "var x = \"ab") }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(document))
        assertTrue(inGate(offset + 11))              // `var x = "ab⟨⟩`: an unterminated string
        assertFalse(inGate(offset))                  // before what was typed
        // the pure lexer path on the text alone
        val text = document.immutableCharSequence
        assertTrue(CSharpNnInline.inStringOrComment(text, text.indexOf("hello") + 1))
        assertFalse(CSharpNnInline.inStringOrComment(text, text.indexOf("\"hello\"") + 7))
        assertTrue(CSharpNnInline.inStringOrComment(text, text.indexOf("a block") + 2))
        assertTrue(CSharpNnInline.inStringOrComment(text, text.indexOf("//\n") + 2))
        assertTrue(CSharpNnInline.inStringOrComment(text, text.indexOf("doc</summary>") + 1))
        assertFalse(CSharpNnInline.inStringOrComment(text, text.indexOf("static void")))
        assertFalse(CSharpNnInline.inStringOrComment(text, 0))
        assertTrue(CSharpNnInline.inStringOrComment("var s = \"a\\\"", 12))     // an escaped quote does not close the string
        assertFalse(CSharpNnInline.inStringOrComment("var s = \"a\\\\\"", 13))  // an escaped backslash before the closing quote does
        assertTrue(CSharpNnInline.inStringOrComment("/* open", 7))
    }

    fun testClosedString() {
        assertTrue(CSharpNnInline.closedString("\"a\""))
        assertTrue(CSharpNnInline.closedString("'a'"))
        assertTrue(CSharpNnInline.closedString("'\\''"))
        assertTrue(CSharpNnInline.closedString("@\"a\\\""))        // verbatim: the backslash is no escape
        assertTrue(CSharpNnInline.closedString("$@\"a\\\""))
        assertTrue(CSharpNnInline.closedString("\"\"\"raw \"quoted\"\"\"\""))
        assertTrue(CSharpNnInline.closedString("\"a\"u8"))
        assertFalse(CSharpNnInline.closedString("\"a"))
        assertFalse(CSharpNnInline.closedString("\"a\\\""))
        assertFalse(CSharpNnInline.closedString("\""))
        assertFalse(CSharpNnInline.closedString("@\""))
        assertFalse(CSharpNnInline.closedString("abc"))              // the text of an interpolated string: no quotes at all
        assertFalse(CSharpNnInline.closedString(""))
    }
}
