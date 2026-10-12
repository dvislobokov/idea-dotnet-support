package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CSharpEnterAfterBraceTest : BasePlatformTestCase() {
    private fun enter(name: String, text: String): String {
        myFixture.configureByText(name, text)
        myFixture.type("\n")
        val document = myFixture.editor.document
        val caret = myFixture.editor.caretModel.offset
        return document.text.substring(0, caret) + "|" + document.text.substring(caret)
    }

    /** The braces of the file do not balance (the property is not closed), but the brace of the class has its pair right below, at its own indent. */
    fun testBraceThatIsClosedByIndentationGetsNoSecondOne() {
        assertEquals(
            "namespace N;\n\npublic class A\n{\n    |\n    public string Hello { get; set;\n}\n",
            enter("Unbalanced.cs", "namespace N;\n\npublic class A\n{<caret>\n    public string Hello { get; set;\n}\n"),
        )
    }

    fun testBraceWithoutAPairGetsOne() {
        assertEquals("public class A\n{\n    |\n}", enter("Open.cs", "public class A\n{<caret>"))
        assertEquals(
            "class A\n{\n    void M()\n    {\n        |\n    }\n}\n",
            enter("OpenMethod.cs", "class A\n{\n    void M()\n    {<caret>\n}\n"),
        )
    }

    fun testBetweenBraces() {
        assertEquals("class A\n{\n    void M()\n    {\n        |\n    }\n}\n", enter("Between.cs", "class A\n{\n    void M()\n    {<caret>}\n}\n"))
        assertEquals("    var x = new Foo {\n        |\n    };\n", enter("BetweenInitializer.cs", "    var x = new Foo {<caret>};\n"))
    }
}
