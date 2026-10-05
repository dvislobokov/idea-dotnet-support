package io.github.dotnetsupport

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpHighlightingLexer
import io.github.dotnetsupport.lang.CSharpStringLiteralLeaf
import io.github.dotnetsupport.lang.CSharpSyntaxTrees

/**
 * Regular expressions in C# strings (task 2.5 of docs/COMPLETION_GAPS.md): the platform's RegExp language in the pattern of `Regex`, of
 * `[GeneratedRegex]`, of a `[StringSyntax(Regex)]` parameter of the solution and after `// lang=regex`; escapes of the literal decoded,
 * the .NET named groups valid, the colors of the editor's lexer untouched, nothing in other strings.
 */
class CSharpRegexInjectionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
    }

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun file(statements: String, members: String = ""): PsiFile = myFixture.configureByText(
        "Regex${counter++}.cs",
        "using System;\nusing System.Text.RegularExpressions;\npartial class Sample\n{\n$members\n    void Run(string input)\n    {\n        $statements\n    }\n}\n",
    )

    /** The injected file at the first occurrence of [marker] in the file. */
    private fun injectedAt(file: PsiFile, marker: String): PsiFile? {
        val offset = file.text.indexOf(marker)
        assertTrue("no $marker", offset >= 0)
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, offset)?.containingFile
    }

    private fun isRegexAt(file: PsiFile, marker: String): Boolean = injectedAt(file, marker)?.language?.id == "RegExp"

    fun testThePatternOfARegexIsARegularExpression() {
        val file = file("var r = new Regex(@\"(?<year>\\d{4})-(?'month'\\d\\d)\"); var ok = Regex.IsMatch(input, \"^a+b$\"); Console.WriteLine(\"plain(text\");")
        assertTrue(isRegexAt(file, "(?<year>"))
        assertTrue("the pattern of a static call", isRegexAt(file, "^a+b$"))
        assertFalse("a string of another call", isRegexAt(file, "plain(text"))
        assertTrue(file.findElementAt(file.text.indexOf("(?<year>")) is CSharpStringLiteralLeaf)
        val injected = injectedAt(file, "(?<year>")!!
        assertEquals("(?<year>\\d{4})-(?'month'\\d\\d)", injected.text)
        assertNull("the named groups of .NET parse: ${injected.text}", PsiTreeUtil.findChildOfType(injected, PsiErrorElement::class.java))
    }

    /** Found by the robot: a target-typed `new` outside a local declaration. */
    fun testTargetTypedNewInMembers() {
        val file = file(
            "Regex local = new(\"l1+\"); object other = new(\"o1+\");",
            "    static Regex Field = new(\"f1+\");\n    Regex Prop { get; } = new(\"p1+\");\n    static Regex Arrow() => new(@\"a1+\");\n" +
                "    static Regex Ret() { return new(\"r1+\"); }\n    static string NotRegex() => new(\"n1+\");\n",
        )
        for (marker in listOf("l1+", "f1+", "p1+", "a1+", "r1+")) assertTrue(marker, isRegexAt(file, marker))
        assertFalse(isRegexAt(file, "o1+"))
        assertFalse(isRegexAt(file, "n1+"))
    }

    fun testTheInputOfAStaticCallIsNoPattern() {
        val file = file("var replaced = Regex.Replace(\"input(text\", \"a+\", \"b\");")
        assertFalse(isRegexAt(file, "input(text"))
        assertTrue(isRegexAt(file, "a+"))
        assertFalse("the replacement", isRegexAt(file, "\"b\"") || isRegexAt(file, "b\");"))
    }

    fun testEscapesOfARegularStringAreDecoded() {
        val file = file("var r = new Regex(\"\\\\d+\\\\.\\\\w*\");")
        val host = file.findElementAt(file.text.indexOf("\\\\d+")) as CSharpStringLiteralLeaf
        val decoded = StringBuilder()
        host.createLiteralTextEscaper().decode(com.intellij.openapi.util.TextRange(1, host.textLength - 1), decoded)
        assertEquals("\\d+\\.\\w*", decoded.toString())
        // the injected tree is parsed from the decoded text (its leaves keep the text of the host): `\\d` is the class of digits
        val injected = injectedAt(file, "\\\\d+")!!
        val classes = PsiTreeUtil.findChildrenOfType(injected, com.intellij.psi.PsiElement::class.java).filter { it.javaClass.simpleName.contains("SimpleClass") }
        assertEquals(listOf("\\\\d", "\\\\w"), classes.map { it.text })
        assertNull(PsiTreeUtil.findChildOfType(injected, PsiErrorElement::class.java))
    }

    /** An escape out of the range of code points is text being typed: invalid, not an exception of the highlighting. */
    fun testAnEscapeOutOfTheCodePointsIsInvalid() {
        val file = file("var r = new Regex(\"a\\UFFFFFFFFb\\U0011FFFF\\x\");")
        val host = file.findElementAt(file.text.indexOf("a\\U")) as CSharpStringLiteralLeaf
        val decoded = StringBuilder()
        assertFalse(host.createLiteralTextEscaper().decode(com.intellij.openapi.util.TextRange(1, host.textLength - 1), decoded))
    }

    fun testGeneratedRegexAndAComment() {
        val members = "    [GeneratedRegex(\"[a-z]+x\", RegexOptions.IgnoreCase)]\n    private static partial Regex Words();\n" +
            "    // lang=regex\n    private const string Pattern = \"[0-9]+y\";\n    private const string Text = \"[0-9]+z\";\n"
        val file = file("var inline = /* language=regex */ \"q+w\";", members)
        assertTrue(isRegexAt(file, "[a-z]+x"))
        assertTrue("a comment on the line before", isRegexAt(file, "[0-9]+y"))
        assertFalse(isRegexAt(file, "[0-9]+z"))
        assertTrue("a comment in front of the literal", isRegexAt(file, "q+w"))
    }

    fun testAParameterMarkedAsRegexInTheSolution() {
        val members = "    static bool Check([System.Diagnostics.CodeAnalysis.StringSyntax(System.Diagnostics.CodeAnalysis.StringSyntaxAttribute.Regex)] string pattern, string text) => true;\n"
        val file = file("Check(\"x+y\", \"text(\");", members)
        assertTrue(isRegexAt(file, "x+y"))
        assertFalse(isRegexAt(file, "text("))
    }

    fun testARawStringLineByLine() {
        val file = file("var r = new Regex(\"\"\"\n            ^(?<a>\\d+)\n            \\s*$\n            \"\"\");")
        assertEquals("^(?<a>\\d+)\n\\s*$", injectedAt(file, "^(?<a>")!!.text)
    }

    fun testCompletionInsideThePattern() {
        myFixture.configureByText("RegexCompletion${counter++}.cs", "using System.Text.RegularExpressions;\nclass A { void M() { var r = new Regex(@\"\\<caret>\"); } }")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), items.any { it.contains("d") })
    }

    fun testTheColorsOfTheLiteralStay() {
        // the editor's lexer splits the literal as before: the injection is a layer of its own
        val text = "var r = new Regex(\"\\\\d\");"
        val lexer = CSharpHighlightingLexer()
        lexer.start(text, 0, text.length, 0)
        val types = ArrayList<String>()
        while (lexer.tokenType != null) {
            types += lexer.tokenType.toString()
            lexer.advance()
        }
        assertTrue(types.toString(), types.any { it.contains("STRING_ESCAPE") })
    }

    private companion object {
        var counter = 0
    }
}
