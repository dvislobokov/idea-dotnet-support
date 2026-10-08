package io.github.dotnetsupport.ml

import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure part of the grey text of the network: what it is given and what of its answer is shown (a fake engine, no platform, no model). */
class CSharpNnInlineTest {
    private class FakeEngine(val answer: CSharpNnInline.Answer?) : CSharpNnEngine {
        var seen: CSharpNnInline.Context? = null
        override suspend fun complete(editor: Any, context: CSharpNnInline.Context): CSharpNnInline.Answer? { seen = context; return answer }
    }

    private fun shown(answer: CSharpNnInline.Answer?, text: String = "Console.Wri)\n", offset: Int = 11): String? = runBlocking {
        val engine = FakeEngine(answer)
        val context = CSharpNnInline.context(text, offset, "Program.cs")
        CSharpNnInline.text(engine.complete(Any(), context), context.after)
    }

    @Test fun shownAnswerGivesItsText() = assertEquals("teLine(\"hi\"", shown(CSharpNnInline.Answer("teLine(\"hi\"", show = true, confProd = 0.93)))

    @Test fun hiddenOrEmptyAnswerGivesNothing() {
        assertNull(shown(CSharpNnInline.Answer("teLine(x", show = false, confProd = 0.4)))
        assertNull(shown(CSharpNnInline.Answer("", show = true, confProd = 0.99)))
        assertNull(shown(null))
    }

    @Test fun whatTheLineAlreadyHasAfterTheCaretIsNotRepeated() {
        val line = "    public int Validate(int count) {\n"
        assertEquals(", string name", shown(CSharpNnInline.Answer(", string name) {", show = true, confProd = 0.86), text = line, offset = line.indexOf(") {")))
        assertEquals(", error", CSharpNnInline.trimOverlap(", error) {", ") {"))
        assertEquals("items", CSharpNnInline.trimOverlap("items)", ")\n}"))
        assertEquals("f(x)", CSharpNnInline.trimOverlap("f(x)", ""))
        assertEquals("", CSharpNnInline.trimOverlap(") {", ") {"))
        assertNull(shown(CSharpNnInline.Answer(") {", show = true, confProd = 0.9), text = "f() {\n", offset = 2))
        assertEquals(") {", CSharpNnInline.restOfLine(") {\n}\n".toByteArray()))
        assertEquals("", CSharpNnInline.restOfLine("\n}".toByteArray()))
    }

    @Test fun whatRepeatsTheLineIsDropped() {
        fun b(s: String) = s.toByteArray()
        // the suggestion is exactly what follows the caret on the line: nothing (the overlap trim leaves nothing either)
        assertNull(CSharpNnInline.text(CSharpNnInline.Answer("items);", show = true, confProd = 0.9), b("items);\n}\n")))
        assertEquals("o.", CSharpNnInline.text(CSharpNnInline.Answer("o.items);", show = true, confProd = 0.9), b("items);\n}\n")))
        // the line would copy the previous one: `a.Name = b.Name;` twice (the model repeats the line above)
        assertTrue(CSharpNnInline.repeatsPreviousLine(b("void F() {\n    a.Name = b.Name;\n    a."), "Name = b.Name;"))
        assertTrue(CSharpNnInline.repeatsPreviousLine(b("    a.Name = b.Name;\n    "), "a.Name = b.Name;"))
        assertNull(CSharpNnInline.text(CSharpNnInline.Answer("Name = b.Name;", show = true, confProd = 0.9), b("\n}\n"), b("void F() {\n    a.Name = b.Name;\n    a.")))
        // a different line, a longer or shorter one, a differing indentation, an empty previous line: shown
        assertFalse(CSharpNnInline.repeatsPreviousLine(b("    a.Name = b.Name;\n    a."), "Age = b.Age;"))
        assertFalse(CSharpNnInline.repeatsPreviousLine(b("    a.Name = b.Name;\n    a."), "Name = b.Name2;"))
        assertFalse(CSharpNnInline.repeatsPreviousLine(b("    a.Name = b.Name;\n        a."), "Name = b.Name;"))
        assertFalse(CSharpNnInline.repeatsPreviousLine(b("\n    a."), "Name"))
        assertFalse(CSharpNnInline.repeatsPreviousLine(b("    a."), "Name"))
        assertFalse(CSharpNnInline.repeatsPreviousLine(b(""), "x"))
        assertEquals("Age = b.Age;", CSharpNnInline.text(CSharpNnInline.Answer("Age = b.Age;", show = true, confProd = 0.9), b("\n}\n"), b("    a.Name = b.Name;\n    a.")))
    }

    @Test fun suggestionIsEmptyWithoutText() {
        assertSame(InlineCompletionSuggestion.Empty, CSharpNnInline.suggestion(null))
        assertNotSame(InlineCompletionSuggestion.Empty, CSharpNnInline.suggestion("Line()"))
    }

    @Test fun contextIsBytesAroundTheCaret() {
        val text = "using System;\n\nclass P {\n    static void Main() {\n        Console.WriteLine(\"привет\");\n    }\n}\n"
        val caret = text.indexOf("(\"")
        val c = CSharpNnInline.context(text, caret, "src/App/Program.cs")
        assertArrayEquals(text.substring(0, caret).toByteArray(), c.before)
        assertArrayEquals(text.substring(caret).toByteArray(), c.after)
        assertArrayEquals("src/App/Program.cs".toByteArray(), c.path)
    }

    @Test fun contextKeepsTheLimits() {
        val line = "var x = 1; // ё\n"
        val text = line.repeat(10_000) + "y" + "z".repeat(100) + "\n" + line.repeat(10_000)
        val caret = line.length * 10_000 + 1
        val c = CSharpNnInline.context(text, caret, "a.cs")
        assertEquals(CSharpNnInline.PREFIX_BYTES, c.before.size)
        assertArrayEquals(text.substring(0, caret).toByteArray().let { it.copyOfRange(it.size - CSharpNnInline.PREFIX_BYTES, it.size) }, c.before)
        assertEquals(100 + CSharpNnInline.SUFFIX_BYTES, c.after.size)
        assertEquals("z".repeat(100) + "\n" + line, String(c.after, 0, 101 + line.toByteArray().size))
    }

    @Test fun afterDotIsTheLowerGate() {
        assertTrue(CSharpNnInline.afterDot("order.".toByteArray()))
        assertTrue(CSharpNnInline.afterDot("order?.".toByteArray()))
        assertTrue(CSharpNnInline.afterDot("global::".toByteArray()))
        assertTrue(CSharpNnInline.afterDot("p->".toByteArray()))
        assertFalse(CSharpNnInline.afterDot("order".toByteArray()))
        assertFalse(CSharpNnInline.afterDot("x > ".toByteArray()))
        assertFalse(CSharpNnInline.afterDot("a ? b :".toByteArray()))
        assertFalse(CSharpNnInline.afterDot(ByteArray(0)))
    }

    @Test fun codeConfidenceCountsTheCodeOnly() {
        fun b(s: String) = s.toByteArray()
        fun conf(line: String, tokens: List<String>, lp: FloatArray, stop: Float) = CSharpNnInline.codeConfidence(b(line), tokens.map { b(it) }, lp, stop)
        // `throw new ` → `ArgumentException("count must be positive");`: the words of the message are unlikely, the code around them is not
        assertEquals(Math.exp(-0.2), conf("            throw new ", listOf("ArgumentException", "(\"", "count", " must", " be", " positive", "\");"), floatArrayOf(-0.1f, -0.1f, -3f, -2f, -2f, -2f, -0.1f), -0.1f), 1e-6)
        // the caret inside a string: only the end of the line counts
        assertEquals(Math.exp(-0.1), conf("        Console.WriteLine(\"no ", listOf("items", "\");"), floatArrayOf(-4f, -1f), -0.1f), 1e-6)
        // an escaped quote does not close the string; a verbatim string ignores backslashes and closes at the quote; a comment is text to the end of the line
        assertEquals(Math.exp(-0.3), conf("        var x = \"a\\\"", listOf(" b", "\"", " + y"), floatArrayOf(-4f, -1f, -0.3f), Float.NaN), 1e-6)
        assertEquals(Math.exp(-0.1), conf("        var p = @\"C:\\", listOf("temp", "\"", " + y"), floatArrayOf(-4f, -1f, -0.1f), Float.NaN), 1e-6)
        assertEquals(1.0, conf("        var x = 1; // the", listOf(" answer"), floatArrayOf(-4f), -5f), 1e-6)
        // a character literal is text too
        assertEquals(Math.exp(-0.2), conf("        if (c == '", listOf("x", "'", ")"), floatArrayOf(-3f, -1f, -0.2f), Float.NaN), 1e-6)
        // nothing but code: the plain product, including the end of the line
        assertEquals(Math.exp(-0.6), conf("        return ", listOf("items", ".Count;"), floatArrayOf(-0.2f, -0.3f), -0.1f), 1e-6)
        assertArrayEquals(b("        return order."), CSharpNnInline.lineBefore(b("void F() {\n        return order.Tot"), 3))
        assertArrayEquals(b("x"), CSharpNnInline.lineBefore(b("x"), 0))
    }

    @Test fun certainStartOfAnUncertainLineIsShown() {
        fun b(s: String) = s.toByteArray()
        fun lp(vararg p: Double) = FloatArray(p.size) { Math.log(p[it]).toFloat() }
        fun prefix(tokens: List<String>, probs: FloatArray, typed: Int, gate: Double = 0.7) = CSharpNnInline.certainPrefix(tokens.map(::b), probs, typed, gate)?.let { String(it) }
        // `if (it` → `ems.Count == 0)`: every token certain but ` ==`; typed `it` is inside the first token
        val tokens = listOf(" items", ".", "Count", " ==", " 0", ")")
        val probs = lp(1.0, 1.0, 0.97, 0.53, 0.98, 0.96)
        assertEquals("ems.Count", prefix(tokens, probs, typed = 3))
        assertNull(prefix(tokens, lp(0.5, 1.0, 0.97, 0.53, 0.98, 0.96), typed = 3))
        assertNull(prefix(listOf(" items", " =="), lp(1.0, 0.5), typed = 4))
        assertNull(prefix(listOf(")", " x"), lp(1.0, 0.5), typed = 0))
        // a fresh line: a lone `return` is no suggestion, `return 0` is one (`return 0,` loses its comma)
        assertNull(prefix(listOf("if", " (", "x"), lp(0.9, 0.2, 1.0), typed = 0, gate = 0.25))
        assertEquals("return 0", prefix(listOf("return", " 0", ",", " x"), lp(0.8, 0.9, 0.9, 0.3), typed = 0, gate = 0.25))
        // an open call is not finished; never cut inside an identifier
        assertEquals("tring.Format", prefix(listOf(" string", ".", "Format", "(", "\"x\""), lp(1.0, 1.0, 1.0, 1.0, 0.1), typed = 2))
        assertNull(prefix(listOf(" o", ".", "Curr", "ency", " =="), lp(1.0, 1.0, 1.0, 0.5, 1.0), typed = 1))
        assertEquals("o.Count()", prefix(listOf(" o", ".", "Count", "()", " =="), lp(1.0, 1.0, 1.0, 1.0, 0.5), typed = 1))
    }

    @Test fun blankLineIsIndentationOnly() {
        assertTrue(CSharpNnInline.blankLine("void F() {\n    ".toByteArray()))
        assertTrue(CSharpNnInline.blankLine("".toByteArray()))
        assertTrue(CSharpNnInline.blankLine("x\n".toByteArray()))
        assertFalse(CSharpNnInline.blankLine("void F() {\n    r".toByteArray()))
        assertFalse(CSharpNnInline.blankLine("    return ".toByteArray()))
    }

    @Test fun pathIsRelativeToTheProject() {
        assertEquals("src/App/Program.cs", CSharpNnInline.relativePath("C:/work/proj/", "C:/work/proj/src/App/Program.cs"))
        assertEquals("Program.cs", CSharpNnInline.relativePath("C:\\work\\proj", "C:/work/other/Program.cs"))
        assertEquals("a.cs", CSharpNnInline.relativePath(null, "/tmp/a.cs"))
    }
}
