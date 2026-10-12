package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpAngleBrackets

/** `<` of a generic is paired with `>`; `<` of a comparison is not. */
class AngleBracketsTest : BasePlatformTestCase() {
    private fun typed(before: String, keys: String): String {
        myFixture.configureByText("Angle.cs", before)
        myFixture.type(keys)
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return text.substring(0, caret) + "|" + text.substring(caret)
    }

    fun testGenericGetsItsPair() {
        assertEquals("services.AddSingleton<|>", typed("services.AddSingleton<caret>", "<"))
        assertEquals("var list = new List<|>", typed("var list = new List<caret>", "<"))
        assertEquals("private readonly ILogger<|>", typed("private readonly ILogger<caret>", "<"))
        assertEquals("Task<|> Load()", typed("Task<caret> Load()", "<"))
        assertEquals("the parentheses of the call are there", "services.AddSingleton<|>();", typed("services.AddSingleton<caret>();", "<"))
        assertEquals("provider.GetRequiredService<|>()", typed("provider.GetRequiredService<caret>()", "<"))
        assertEquals("the closing one is stepped over", "new List<int>|", typed("new List<caret>", "<int>"))
        assertEquals("new Dictionary<string, List<int>>|", typed("new Dictionary<caret>", "<string, List<int>>"))
        assertEquals("services.AddSingleton<IFoo, Foo>|();", typed("services.AddSingleton<caret>();", "<IFoo, Foo>"))
    }

    fun testComparisonStaysAsTyped() {
        assertEquals("if (i <| n)", typed("if (i <caret> n)", "<"))
        assertEquals("if (count<|)", typed("if (count<caret>)", "<"))
        assertEquals("x = a <|", typed("x = a <caret>", "<"))
        assertEquals("in front of what is written", "Foo<|Bar", typed("Foo<caret>Bar", "<"))
        assertEquals("a string", "var s = \"List<|\";", typed("var s = \"List<caret>\";", "<"))
        assertEquals("a comment", "// List<|", typed("// List<caret>", "<"))
        assertEquals("a lambda arrow is not a bracket", "Func<int> f = () =>|", typed("Func<int> f = () =<caret>", ">"))
        assertEquals("no pair around: typed as is", "a >|> b", typed("a <caret>> b", ">"))
    }

    fun testComparisonWithACapitalLetterTakesThePairBack() {
        assertEquals("if (items.Count<5|)", typed("if (items.Count<caret>)", "<5"))
        assertEquals("var few = items.Count<5;|", typed("var few = items.Count<caret>", "<5;"))
        assertEquals("if (items.Count<=|)", typed("if (items.Count<caret>)", "<="))
        assertEquals("if (items.Count< |)", typed("if (items.Count<caret>)", "< "))
        assertEquals("var few = items.Count<limit;|", typed("var few = items.Count<caret>", "<limit;"))
        assertEquals("var few = a.Count<limit |", typed("var few = a.Count<caret>", "<limit "))
        assertEquals("var few = a.Count<-|", typed("var few = a.Count<caret>", "<-"))
        assertEquals("var few = a.Count<b.Length+|", typed("var few = a.Count<caret>", "<b.Length+"))

        assertEquals("types stay paired", "new Dictionary<string, List<int?[]>>|", typed("new Dictionary<caret>", "<string, List<int?[]>>"))
        assertEquals("new List<(int id, string name)>|", typed("new List<caret>", "<(int id, string name)>"))
        assertEquals("new List<global::System.String|>", typed("new List<caret>", "<global::System.String"))
        assertEquals("new List<T1|>", typed("new List<caret>", "<T1"))
    }

    fun testSemicolonStepsOverTheOneThatEndsTheLine() {
        assertEquals("Console.WriteLine(x);|", typed("Console.WriteLine(x<caret>);", ");"))
        assertEquals("Console.WriteLine();|\nnext();", typed("Console.WriteLine()<caret>;\nnext();", ";"))
        assertEquals("something follows: typed", "for (;|;)", typed("for (<caret>;)", ";"))
        assertEquals("var x = 1;|", typed("var x = 1<caret>", ";"))
        assertEquals("a;| // note", typed("a<caret> // note", ";"))
    }

    fun testParenthesisEntersTheCallAfterTypeArguments() {
        assertEquals("services.AddSingleton<IClock>(|);", typed("services.AddSingleton<IClock><caret>();", "("))
        assertEquals("the whole of it by hand", "services.AddSingleton<IClock>(|)", typed("services.AddSingleton<<caret>>()", "IClock>("))
        assertEquals("arguments are there: a pair of its own", "Make<int>(|)(1)", typed("Make<int><caret>(1)", "("))
    }

    fun testBackspaceTakesThePair() {
        myFixture.configureByText("Angle.cs", "new List<caret>")
        myFixture.type("<")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
        assertEquals("new List", myFixture.editor.document.text)

        myFixture.configureByText("Angle.cs", "if (a <<caret>> b)")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
        assertEquals("not a generic: only the one", "if (a > b)", myFixture.editor.document.text)
    }

    fun testWhatOpensAGeneric() {
        fun opens(text: String) = CSharpAngleBrackets.opensGeneric(text, text.indexOf('<'))
        assertTrue(opens("AddSingleton<"))
        assertTrue(opens("List<>"))
        assertTrue(opens("x.GetService<)"))
        assertFalse(opens("i <"))
        assertFalse(opens("count<"))
        assertFalse(opens("<"))
        assertFalse(opens("List<int"))
        assertTrue(opens("AddSingleton<()"))
    }
}
