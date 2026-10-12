package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `"` gets its pair, the prefixes of interpolated and verbatim strings too: `$"|"`, `@"|"`, `$@"|"`. */
class CSharpQuotesTest : BasePlatformTestCase() {
    private var files = 0

    private fun typed(before: String, keys: String): String {
        myFixture.configureByText("Quotes${files++}.cs", "class A { void M() { $before } }")
        myFixture.type(keys)
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return (text.substring(0, caret) + "|" + text.substring(caret)).removePrefix("class A { void M() { ").removeSuffix(" } }")
    }

    fun testAQuoteGetsItsPair() {
        assertEquals("Console.WriteLine(\"|\");", typed("Console.WriteLine(<caret>);", "\""))
        assertEquals("the closing quote is stepped over", "Console.WriteLine(\"a\"|);", typed("Console.WriteLine(<caret>);", "\"a\""))
    }

    fun testThePrefixOfAStringDoesNotStopThePair() {
        assertEquals("Console.WriteLine($\"|\");", typed("Console.WriteLine(<caret>);", "$\""))
        assertEquals("var s = @\"|\";", typed("var s = <caret>;", "@\""))
        assertEquals("var s = $@\"|\";", typed("var s = <caret>;", "$@\""))
        assertEquals("var s = @$\"|\";", typed("var s = <caret>;", "@$\""))
        assertEquals("the closing quote is stepped over", "var s = $\"{x}\"|;", typed("var s = <caret>;", "$\"{x}\""))
    }

    /** The editor's tokens split a literal at escapes and holes (0.1.71): its closing quote is still stepped over, a new one still paired. */
    fun testSplitLiterals() {
        assertEquals("after an escape", "var s = \"a\\n\"|;", typed("var s = \"a\\n<caret>\";", "\""))
        assertEquals("after a hole and an escape", "var s = \$\"{x}\\t\"|;", typed("var s = \$\"{x}\\t<caret>\";", "\""))
        assertEquals("verbatim", "var s = @\"a\"\"b\"|;", typed("var s = @\"a\"\"b<caret>\";", "\""))
        assertEquals("a char", "var c = '\\n'|;", typed("var c = '\\n<caret>';", "'"))
        assertEquals("before a closed string with an escape", "M(\"|\", \"a\\n\");", typed("M(<caret>, \"a\\n\");", "\""))
    }

    fun testAQuoteInsideAStringIsTypedAsIs() {
        assertEquals("var s = \"a\"|b\";", typed("var s = \"a<caret>b\";", "\""))
    }
}
