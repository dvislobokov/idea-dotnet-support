package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `;` and `)` typed among the parentheses the editor put (DEV_JOURNEY 4.3, 0.1.100): `;` inside the `()` of a call that ends the statement
 * goes after it, as in Rider; `)` after an interpolated string steps over the one that was put.
 */
class CSharpTypedBracketsTest : BasePlatformTestCase() {
    private var files = 0

    /** [line] (with `<caret>`) as the only statement of a method, [keys] typed; the line as it is after, `|` for the caret. */
    private fun typed(line: String, keys: String): String {
        myFixture.configureByText("Typed${files++}.cs", "class A\n{\n    void M()\n    {\n        $line\n    }\n}\n")
        myFixture.type(keys)
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        val marked = text.substring(0, caret) + "|" + text.substring(caret)
        return marked.lines()[4].trim()
    }

    fun testASemicolonInsideTheParenthesesThatEndTheStatementGoesAfterThem() {
        assertEquals("var repo = new Repository();|", typed("var repo = new Repository(<caret>)", ";"))
        assertEquals("Save(Load());|", typed("Save(Load(<caret>))", ";"))
        assertEquals("the one that is there is stepped over", "Save();|", typed("Save(<caret>);", ";"))
    }

    fun testASemicolonWhereItBelongsInsideTheParenthesesIsTyped() {
        assertEquals("for (;|)", typed("for (<caret>)", ";"))
        assertEquals("for (int i = 0;|)", typed("for (int i = 0<caret>)", ";"))
        assertEquals("M(\";|\")", typed("M(\"<caret>\")", ";"))
        assertEquals("something follows the parenthesis", "M(;|) + 1", typed("M(<caret>) + 1", ";"))
    }

    fun testTheWholeCallTypedAsTheHandTypesIt() {
        assertEquals("var repo = new Repository();|", typed("<caret>", "var repo = new Repository();"))
        assertEquals("Console.WriteLine(\$\"{o.Id,3} {o.Total:C}\");|", typed("<caret>", "Console.WriteLine(\$\"{o.Id,3} {o.Total:C}\");"))
        assertEquals("Console.WriteLine(\$\"a\");|", typed("<caret>", "Console.WriteLine(\$\"a\");"))
        assertEquals("Console.WriteLine(\"a\");|", typed("<caret>", "Console.WriteLine(\"a\");"))
    }

    /** DEV_JOURNEY 4.4: Enter in the editor, as the rules of `csharpIndent/rules.json` say (`control.body.without.braces`, the enum on one line). */
    fun testEnterAfterAHeaderWithoutBracesAndAfterAnEnumOnOneLine() {
        fun enter(text: String): String {
            myFixture.configureByText("Enter${files++}.cs", text)
            myFixture.type("\n")
            val document = myFixture.editor.document
            val line = document.getLineNumber(myFixture.editor.caretModel.offset)
            return document.text.substring(document.getLineStartOffset(line), myFixture.editor.caretModel.offset)
        }
        assertEquals("    ", enter("var orders = new int[0];\nforeach (var o in orders.OrderByDescending(o => o))<caret>\n"))
        assertEquals("            ", enter("class A\n{\n    void M(int a)\n    {\n        if (a > 0)<caret>\n    }\n}\n"))
        assertEquals("", enter("namespace N;\n\npublic enum OrderStatus { New, Paid, Shipped }<caret>\n"))
        assertEquals("    ", enter("class A\n{\n    enum Kind { A, B }<caret>\n}\n"))
    }

    fun testAClosingParenthesisAfterAnInterpolatedStringStepsOver() {
        assertEquals("M(\$\"{x}\")|;", typed("M(\$\"{x}\"<caret>);", ")"))
        assertEquals("M(\$@\"{x}\")|", typed("M(\$@\"{x}\"<caret>)", ")"))
    }
}
