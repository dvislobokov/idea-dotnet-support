package io.github.dotnetsupport

import io.github.dotnetsupport.lang.CSharpStatements
import junit.framework.TestCase

class CSharpStatementsTest : TestCase() {
    private fun complete(line: String) = CSharpStatements.complete(line)

    fun testControlStatementsOpenABody() {
        assertEquals("        if (x) {" to true, complete("        if (x"))
        assertEquals("if (a > 0) {" to true, complete("if (a > 0)"))
        assertEquals("foreach (var i in items) {" to true, complete("foreach (var i in items"))
        assertEquals("while (running) {" to true, complete("while (running"))
        assertEquals("using (var s = Open()) {" to true, complete("using (var s = Open()"))
        assertEquals("switch (state) {" to true, complete("switch (state"))
    }

    fun testKeywordsWithoutParentheses() {
        assertEquals("else {" to true, complete("else"))
        assertEquals("do {" to true, complete("do"))
        assertEquals("try {" to true, complete("try"))
        assertEquals("finally {" to true, complete("finally"))
        assertEquals("catch (Exception e) {" to true, complete("catch (Exception e"))
        assertEquals("else if (retry) {" to true, complete("else if (retry"))
    }

    fun testStatementsGetSemicolon() {
        assertEquals("var a = 1;" to false, complete("var a = 1"))
        assertEquals("Compute(a, b);" to false, complete("Compute(a, b"))
        assertEquals("return total;" to false, complete("return total"))
        assertEquals("i++;" to false, complete("i++"))
        assertEquals("var name = person.Name;" to false, complete("var name = person.Name"))
    }

    fun testDeclarationsAreNotTerminated() {
        assertNull(complete("public void Foo()"))
        assertNull(complete("int Bar()"))
    }

    fun testControlKeywordWithoutParenIsLeftAlone() {
        assertNull(complete("if x"))
        assertNull(complete("while cond"))
    }

    fun testAlreadyCompleteOrNotAStatement() {
        assertNull(complete("var a = 1;"))
        assertNull(complete("if (x) {"))
        assertNull(complete("}"))
        assertNull(complete(""))
        assertNull(complete("   "))
        assertNull(complete("[Serializable]"))
    }
}
