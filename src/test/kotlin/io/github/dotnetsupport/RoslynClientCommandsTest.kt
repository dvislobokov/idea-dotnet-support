package io.github.dotnetsupport

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.github.dotnetsupport.roslyn.RoslynClientCommands
import junit.framework.TestCase

/**
 * `roslyn.client.completionComplexEdit` of the server's `override` / `partial` items (robot 0.1.60: choosing one went round between the two
 * `executeCommand` overloads into a StackOverflowError, then left `partial ();`). The arguments are as server 5.12 sent them for
 * `public override ` + Equals on an empty line of a class.
 */
class RoslynClientCommandsTest : TestCase() {
    private val document = "class A\n{\n    public override \n    public int Read() => 1;\n}\n"

    private val arguments: List<JsonElement> = JsonParser.parseString(
        """
        [{"uri":"file:///c:/p/A.cs"},
         {"range":{"start":{"line":2,"character":0},"end":{"line":3,"character":11}},
          "newText":"    public override bool Equals(object? obj)\r\n    {\r\n        return base.Equals(obj);\r\n    }\r\n    public "},
         false, 95]
        """.trimIndent(),
    ).asJsonArray.toList()

    fun testArguments() {
        val edit = RoslynClientCommands.complexEdit(arguments)!!
        assertEquals("file:///c:/p/A.cs", edit.uri)
        assertEquals(2, edit.range.start.line)
        assertEquals(95, edit.newOffset)
        assertNull(RoslynClientCommands.complexEdit(arguments.take(1)))
    }

    /** The server writes `\r\n` and counts the caret in its own text with them: the document gets `\n`, the caret lands after `;`. */
    fun testAppliedToADocumentOfTheIde() {
        val (range, text, caret) = RoslynClientCommands.complexEdit(arguments)!!.on(document)!!
        val result = document.replaceRange(range.startOffset, range.endOffset, text)
        assertEquals("class A\n{\n    public override bool Equals(object? obj)\n    {\n        return base.Equals(obj);\n    }\n    public int Read() => 1;\n}\n", result)
        assertEquals(result.indexOf("return base.Equals(obj);") + "return base.Equals(obj);".length, caret)
    }

    fun testOutsideTheDocumentIsNothing() {
        val far = JsonParser.parseString("""[{"uri":"u"},{"range":{"start":{"line":9,"character":0},"end":{"line":9,"character":1}},"newText":"x"},false,-1]""")
        assertNull(RoslynClientCommands.complexEdit(far.asJsonArray.toList())!!.on(document))
        val noCaret = RoslynClientCommands.complexEdit(listOf(arguments[0], arguments[1], arguments[2]))!!.on(document)!!
        assertNull(noCaret.third)
    }
}
