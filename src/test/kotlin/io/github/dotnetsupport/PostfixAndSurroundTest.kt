package io.github.dotnetsupport

import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpExpressions
import io.github.dotnetsupport.lang.CSharpPostfixTemplateProvider
import io.github.dotnetsupport.lang.CSharpSurroundDescriptor

/** Postfix templates and Surround With, by tokens. */
class PostfixAndSurroundTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    private fun expression(text: String): String? {
        val offset = text.indexOf('|')
        val clean = text.replace("|", "")
        return CSharpExpressions.before(clean, offset)?.let { clean.substring(it.startOffset, it.endOffset) }
    }

    fun testExpressionBeforeTheCaret() {
        assertEquals("person", expression("var x = person|"))
        assertEquals("person.Friend.Name", expression("Console.WriteLine(person.Friend.Name|)"))
        assertEquals("list[0]", expression("list[0]|"))
        assertEquals("Make(1, \"a)b\").Result", expression("var r = Make(1, \"a)b\").Result|"))
        assertEquals("new Foo(x)", expression("new Foo(x)|"))
        assertEquals("new List<Order>()", expression("new List<Order>()|"))
        assertEquals("Make<Dictionary<string, int[]>>(1)", expression("x = Make<Dictionary<string, int[]>>(1)|"))
        assertEquals("(b)", expression("a > (b)|"))
        assertEquals("await Load()", expression("await Load()|"))
        assertEquals("this.Items", expression("this.Items|"))
        assertEquals("!ready", expression("!ready|"))
        assertEquals("ready", expression("a && ready|"))
        assertEquals("\"text\"", expression("\"text\"|"))
        assertEquals("items?.Count", expression("items?.Count|"))
        assertEquals("b", expression("a + b|"))
        assertNull(expression("x = |"))
        assertNull(expression("a + |"))

        val text = "void M()\n{\n    foo();\n    bar; x = baz\n}"
        assertTrue(CSharpExpressions.startsStatement(text, text.indexOf("bar")))
        assertFalse(CSharpExpressions.startsStatement(text, text.indexOf("baz")))
        assertTrue(CSharpExpressions.startsStatement(text, text.indexOf("foo")))
        assertEquals("    ", CSharpExpressions.indentAt(text, text.indexOf("bar")))
        assertEquals("", CSharpExpressions.indentAt(text, 0))
    }

    private fun templates() = CSharpPostfixTemplateProvider().templates.associateBy { it.key }

    private fun expand(template: PostfixTemplate, before: String): String {
        // the platform has already removed the key: the caret is right after the expression
        myFixture.configureByText("A.cs", before)
        val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        WriteCommandAction.runWriteCommandAction(project) { template.expand(context, myFixture.editor) }
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        val text = myFixture.editor.document.text
        return text.substring(0, myFixture.caretOffset) + "<caret>" + text.substring(myFixture.caretOffset)
    }

    fun testPostfixTemplatesExpand() {
        val t = templates()
        assertTrue(t.keys.containsAll(listOf(".if", ".foreach", ".return", ".var", ".not", ".await", ".null", ".notnull", ".cw")))
        assertEquals(
            "class A { void M() {\n        if (ready)\n        {\n            <caret>\n        }\n    } }",
            expand(t.getValue(".if"), "class A { void M() {\n        ready<caret>\n    } }"),
        )
        assertEquals("class A { int M() { return Make(1)<caret>; } }".replace("<caret>;", ";<caret>"), expand(t.getValue(".return"), "class A { int M() { Make(1)<caret> } }"))
        assertEquals("class A { void M() { var load = Load();<caret> } }", expand(t.getValue(".var"), "class A { void M() { Load()<caret> } }"))
        assertEquals("class A { void M() { var x = !ready<caret>; } }", expand(t.getValue(".not"), "class A { void M() { var x = ready<caret>; } }"))
        // as choosing `await` in completion, the method becomes async
        assertEquals("class A { async Task M() { var x = await Load()<caret>; } }", expand(t.getValue(".await"), "class A { void M() { var x = Load()<caret>; } }"))
        assertEquals(
            "class A { void M() {\n    foreach (var item in items)\n    {\n        <caret>\n    }\n} }",
            expand(t.getValue(".foreach"), "class A { void M() {\n    items<caret>\n} }"),
        )
        assertEquals(
            "class A { void M() {\n    for (var i = 0; i < count; i++)\n    {\n        <caret>\n    }\n} }",
            expand(t.getValue(".for"), "class A { void M() {\n    count<caret>\n} }"),
        )
        // .cast wraps the expression; the caret lands on the type placeholder T (which is also selected for typing over)
        assertEquals("class A { void M() { var x = ((<caret>T)value); } }", expand(t.getValue(".cast"), "class A { void M() { var x = value<caret>; } }"))
    }

    /** Jellyfin № 7: the element of a collection of a predefined type is named by its letter, as Rider (`char` → `c`). */
    fun testTheElementOfAPredefinedTypeByItsLetter() {
        assertEquals(listOf("c", "item"), io.github.dotnetsupport.lang.CSharpExpressionNames.forElement("newName.ToCharArray()", "char"))
        assertEquals("s", io.github.dotnetsupport.lang.CSharpExpressionNames.forElement("Split()", "string").first())
        // the collection's own plural first, the type's names first of all
        assertEquals("name", io.github.dotnetsupport.lang.CSharpExpressionNames.forElement("names", "string").first())
        assertEquals("order", io.github.dotnetsupport.lang.CSharpExpressionNames.forElement("items", "Order").first())
    }

    fun testStatementTemplatesApplyOnlyWhereAStatementStarts() {
        val t = templates()
        myFixture.configureByText("B.cs", "class B { void M() { var x = ready<caret>; } }")
        val document = myFixture.editor.document
        val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        assertFalse(t.getValue(".if").isApplicable(context, document, myFixture.caretOffset))
        assertTrue(t.getValue(".not").isApplicable(context, document, myFixture.caretOffset))
        myFixture.configureByText("C.cs", "class C { void M() { ready<caret> } }")
        val context2 = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        assertTrue(t.getValue(".if").isApplicable(context2, myFixture.editor.document, myFixture.caretOffset))
    }

    fun testPostfixByTab() {
        myFixture.configureByText("D.cs", "class D { void M() {\n    ready.if<caret>\n} }")
        myFixture.type('\t')
        assertEquals("class D { void M() {\n    if (ready)\n    {\n        \n    }\n} }", myFixture.editor.document.text)
    }

    /** `oldName.Length > 0.if`: the whole expression of the statement, as Rider (Jellyfin № 3; the number's `0.` hid the postfix). */
    fun testStatementTemplatesTakeTheWholeExpressionOfTheStatement() {
        fun whole(text: String): String? {
            val offset = text.indexOf('|')
            val clean = text.replace("|", "")
            return CSharpExpressions.postfixRange(clean, offset)?.let { clean.substring(it.startOffset, it.endOffset) }
        }
        assertEquals("oldName.Length > 0", whole("{ oldName.Length > 0| }"))
        assertEquals("a && b.IsReady", whole("{ a && b.IsReady| }"))
        // mid-statement the operand chain stays: `var x = a > b` takes `b`
        assertEquals("b", whole("{ var x = a > b| }"))
        myFixture.configureByText("E.cs", "class E { void M(string oldName) {\n    oldName.Length > 0.if<caret>\n} }")
        myFixture.type('\t')
        assertEquals("class E { void M(string oldName) {\n    if (oldName.Length > 0)\n    {\n        \n    }\n} }", myFixture.editor.document.text)
    }

    /** `.return` not where the function returns nothing (`void`, `async Task`): it would be CS0127 / CS1997 (Jellyfin № 11). */
    fun testReturnOnlyWhereAValueIsReturned() {
        io.github.dotnetsupport.lang.CSharpSyntaxTrees.forceNativeTreeForTests(true)
        try {
            val t = templates()
            fun applicable(text: String): Boolean {
                myFixture.configureByText("R.cs", text)
                val context = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
                return t.getValue(".return").isApplicable(context, myFixture.editor.document, myFixture.caretOffset)
            }
            assertFalse(applicable("class R { async System.Threading.Tasks.Task M(string s) { s.Trim()<caret> } }"))
            assertFalse(applicable("class R { void M(string s) { s.Trim()<caret> } }"))
            assertTrue(applicable("class R { string M(string s) { s.Trim()<caret> } }"))
            assertTrue(applicable("class R { async System.Threading.Tasks.Task<string> M(string s) { s.Trim()<caret> } }"))
        } finally {
            io.github.dotnetsupport.lang.CSharpSyntaxTrees.forceNativeTreeForTests(null)
        }
    }

    private fun surround(description: String, before: String): String {
        myFixture.configureByText("S.cs", before)
        val surrounder = CSharpSurroundDescriptor.SURROUNDERS.first { it.templateDescription == description }
        SurroundWithHandler.invoke(project, myFixture.editor, myFixture.file, surrounder)
        return myFixture.editor.document.text
    }

    fun testSurroundStatementsAndExpressions() {
        val statements = "class S { void M() {\n    var a = 1;\n    <selection>Foo();\n    Bar();</selection>\n} }"
        assertEquals("class S { void M() {\n    var a = 1;\n    if ()\n    {\n        Foo();\n        Bar();\n    }\n} }", surround("if ()", statements))
        assertEquals(
            "class S { void M() {\n    var a = 1;\n    try\n    {\n        Foo();\n        Bar();\n    }\n    catch (Exception e)\n    {\n    }\n} }",
            surround("try ... catch", statements),
        )
        assertEquals("class S { void M() {\n    var a = 1;\n    #region \n    Foo();\n    Bar();\n    #endregion\n} }", surround("#region ... #endregion", statements))
        assertEquals("class S { void M() {\n    var a = 1;\n    {\n        Foo();\n        Bar();\n    }\n} }", surround("{ }", statements))
        assertEquals("class S { void M() { if (!(a && b)) { } } }", surround("!(expr)", "class S { void M() { if (<selection>a && b</selection>) { } } }"))
        assertEquals("class S { void M() { var x = (a + b) * 2; } }", surround("(expr)", "class S { void M() { var x = <selection>a + b</selection> * 2; } }"))
        // the elements the platform hands over: the first and the last token of the selection, C# only
        assertEquals(2, CSharpSurroundDescriptor().getElementsToSurround(myFixture.file, 20, 30).size)
        assertEquals(TextRange(0, 0).length, 0)
    }
}
