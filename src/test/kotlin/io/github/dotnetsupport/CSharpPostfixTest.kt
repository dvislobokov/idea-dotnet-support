package io.github.dotnetsupport

import io.github.dotnetsupport.lang.CSharpPostfixExpressions
import junit.framework.TestCase

class CSharpPostfixTest : TestCase() {
    /** The expression [CSharpPostfixExpressions] finds right before the `.` at the end of [code] (the caret), or null. */
    private fun expression(code: String): String? {
        val range = CSharpPostfixExpressions.rangeBefore(code, code.length) ?: return null
        return code.substring(range.startOffset, range.endOffset)
    }

    fun testNamesAndChains() {
        assertEquals("items", expression("        items."))
        assertEquals("order.Total", expression("var x = order.Total."))
        assertEquals("a.b.c", expression("a.b.c."))
    }

    fun testCallsAndIndexes() {
        assertEquals("Compute(a, b)", expression("x = Compute(a, b)."))
        assertEquals("items[0]", expression("items[0]."))
        assertEquals("repo.Where(x => x > 0)", expression("repo.Where(x => x > 0)."))
    }

    fun testGenericCall() {
        assertEquals("Enum.GetValues<Color>()", expression("var c = Enum.GetValues<Color>()."))
        assertEquals("new Dictionary<string, int>()", expression("new Dictionary<string, int>()."))
    }

    fun testLiteralsAndPrefixes() {
        assertEquals("\"text\"", expression("\"text\"."))
        assertEquals("42", expression("return 42."))
        assertEquals("!ok", expression("!ok."))
    }

    fun testKeywordsAreNotExpressions() {
        assertNull(expression("return."))
        assertNull(expression("if."))
        // but a value keyword may stand on its own
        assertEquals("this", expression("this."))
    }

    fun testNothingBeforeTheDot() {
        assertNull(expression("."))
        assertNull(expression("        ."))
        assertNull(expression("x = ."))
    }

    fun testComparisonIsNotGeneric() {
        // `a > b` then a dot: the `>` is a comparison, so only `b` is the expression, not `a > b`
        assertEquals("b", expression("var t = a > b."))
    }
}
