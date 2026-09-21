package io.github.dotnetsupport

import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.BuildProblem
import io.github.dotnetsupport.build.BuildProblems
import io.github.dotnetsupport.build.BuildProblemsAnnotator
import io.github.dotnetsupport.build.MsBuildOutputParser
import io.github.dotnetsupport.lang.CSharpDeclarations
import io.github.dotnetsupport.lang.CSharpDocComments

class CSharpEditorAssistTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            BuildProblems.getInstance(project).clear()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testProblemFollowsItsLine() {
        val lines = listOf("class A", "{", "    int x = y;", "}")
        val problem = BuildProblem(3, 13, "CS0103", "The name 'y' does not exist", true, "int x = y;")
        assertEquals(2, BuildProblems.locate(problem, lines))
        // two lines were added above, one removed: the line is found again
        assertEquals(4, BuildProblems.locate(problem, listOf("// a", "// b") + lines))
        assertEquals(1, BuildProblems.locate(problem, lines.drop(1)))
        // the line itself was edited: the diagnostic of the old text says nothing about the new one
        assertNull(BuildProblems.locate(problem, listOf("class A", "{", "    int x = 1;", "}")))
        // built without the text of the line (the file could not be read): by the number
        assertEquals(2, BuildProblems.locate(BuildProblem(3, 1, null, "x", true, null), lines))
    }

    fun testRangeOfAProblem() {
        val text = "    int x = yyy + 1;"
        fun range(column: Int) = BuildProblemsAnnotator.rangeOf(text, 0, text.length, column).substring(text)
        assertEquals("yyy", range(13))
        assertEquals("+ 1;", range(17))
        assertEquals("the whole code of the line without a column", "int x = yyy + 1;", range(0))
    }

    fun testBuildDiagnosticsAreShownInTheEditor() {
        val source = "class A\n{\n    int x = missing;\n    int unused;\n}\n"
        val file = myFixture.configureByText("Broken.cs", source)
        val path = file.virtualFile.path
        val messages = listOf(
            // without the [project] suffix: the path of the in-memory file of a test is not absolute for java.io.File on Windows
            "$path(3,13): error CS0103: The name 'missing' does not exist in the current context",
            "$path(4,9): warning CS0169: The field 'A.unused' is never used",
            "C:\\repo\\Other.cs(1,1): error CS1002: ; expected [C:\\repo\\App.csproj]",
        ).mapNotNull(MsBuildOutputParser::parseLine)
        assertEquals(3, messages.size)
        BuildProblems.getInstance(project).replace(messages) { source.lines() }

        fun shown() = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WARNING }.map { Triple(it.severity, it.text, it.description) }
        assertEquals(
            listOf(
                Triple(HighlightSeverity.ERROR, "missing", "CS0103: The name 'missing' does not exist in the current context"),
                Triple(HighlightSeverity.WARNING, "unused", "CS0169: The field 'A.unused' is never used"),
            ),
            shown(),
        )
        // fixing the line drops its error, the other one stays where its line went
        myFixture.editor.document.let { document ->
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(source.indexOf("missing"), source.indexOf("missing") + "missing".length, "1")
                document.insertString(0, "using System;\n")
            }
        }
        assertEquals(listOf(Triple(HighlightSeverity.WARNING, "unused", "CS0169: The field 'A.unused' is never used")), shown())

        // the next build replaces everything
        BuildProblems.getInstance(project).replace(emptyList())
        assertTrue(shown().isEmpty())
    }

    fun testLiveTemplates() {
        val templates = TemplateSettings.getInstance().templates.filter { it.groupName == "C#" }
        assertTrue(templates.map { it.key }.containsAll(listOf("ctor", "prop", "cw", "foreach", "svm", "fact", "try", "region")))

        myFixture.configureByText("Templates.cs", "namespace Shop;\n\npublic class OrderService\n{\n    ctor<caret>\n}\n")
        myFixture.type("\t")
        // the name of the class is filled in, the body is indented like the line the template was typed on
        myFixture.checkResult("namespace Shop;\n\npublic class OrderService\n{\n    public OrderService(<caret>)\n    {\n        \n    }\n}\n")

        myFixture.configureByText("Foreach.cs", "class A\n{\n    void M()\n    {\n        foreach<caret>\n    }\n}\n")
        myFixture.type("\t")
        assertTrue(myFixture.editor.document.text, "        foreach (var item in collection)\n        {\n            \n        }\n" in myFixture.editor.document.text)
    }

    fun testDocCommentStub() {
        assertEquals(listOf("a", "b", "rest", "map"), CSharpDocComments.parameterNames("(int a, string b = \"x, y\", params object[] rest, Dictionary<string, int> map)"))
        assertEquals(emptyList<String>(), CSharpDocComments.parameterNames("()"))

        val method = CSharpDeclarations.scan("class A { public int Sum(int a, int b) => a + b; void Reset() { } }").all().toList()
        assertEquals(" <summary>\n    /// \n    /// </summary>\n    /// <param name=\"a\"></param>\n    /// <param name=\"b\"></param>\n    /// <returns></returns>", CSharpDocComments.stub(method[1], "    ").first)
        assertEquals(" <summary>\n/// \n/// </summary>", CSharpDocComments.stub(method[2], "").first)

        myFixture.configureByText("Docs.cs", "class A\n{\n    //<caret>\n    public int Sum(int a, int b) => a + b;\n}\n")
        myFixture.type("/")
        myFixture.checkResult(
            "class A\n{\n    /// <summary>\n    /// <caret>\n    /// </summary>\n    /// <param name=\"a\"></param>\n    /// <param name=\"b\"></param>\n    /// <returns></returns>\n" +
                "    public int Sum(int a, int b) => a + b;\n}\n",
        )
        // not above a declaration: just a comment
        myFixture.configureByText("NoDocs.cs", "class A\n{\n    void M()\n    {\n        //<caret>\n        var x = 1;\n    }\n}\n")
        myFixture.type("/")
        assertTrue("        ///\n        var x = 1;" in myFixture.editor.document.text)
    }

    fun testIndentWhileTyping() {
        fun typed(name: String, text: String, keys: String): String {
            myFixture.configureByText(name, text)
            myFixture.type(keys)
            return myFixture.editor.document.text
        }
        // Enter after an opening brace, after a control header, inside a chain and after it
        assertEquals("class A\n{\n    void M()\n    {\n        \n    }\n}\n", typed("IndentBody.cs", "class A\n{\n    void M()\n    {<caret>\n    }\n}\n", "\n"))
        assertEquals(
            "class A\n{\n    void M()\n    {\n        if (x)\n            return;\n    }\n}\n",
            typed("IndentIf.cs", "class A\n{\n    void M()\n    {\n        if (x)<caret>\n    }\n}\n", "\nreturn;"),
        )
        assertEquals(
            "class A\n{\n    void M()\n    {\n        var q = xs\n            .ToList();\n        return;\n    }\n}\n",
            typed("IndentChain.cs", "class A\n{\n    void M()\n    {\n        var q = xs<caret>\n    }\n}\n", "\n.ToList();\nreturn;"),
        )
        // a closing brace typed on a line of its own goes under its opening one
        assertEquals("class A\n{\n    void M()\n    {\n        Work();\n    }\n}\n", typed("IndentClose.cs", "class A\n{\n    void M()\n    {\n        Work();<caret>\n}\n", "\n}"))
        // an opening brace after a header: Enter has given a continuation indent, the brace takes it back
        assertTrue(typed("IndentOpen.cs", "class A\n{\n    void M()<caret>\n}\n", "\n{").contains("    void M()\n    {"))

        // indent_size of the code style is what the rules count in
        val settings = com.intellij.application.options.CodeStyle.getSettings(project).getIndentOptions(io.github.dotnetsupport.lang.CSharpFileType)
        val before = settings.INDENT_SIZE
        try {
            settings.INDENT_SIZE = 2
            assertEquals("class A\n{\n  \n}\n", typed("IndentTwo.cs", "class A\n{<caret>\n}\n", "\n"))
        } finally {
            settings.INDENT_SIZE = before
        }
        assertEquals(
            mapOf("csharp_indent_switch_labels" to "false", "csharp_indent_braces" to "true"),
            io.github.dotnetsupport.lang.CSharpIndentOptions.parse("root = true\n[*.cs]\nindent_size = 4\ncsharp_indent_switch_labels = false\n  csharp_indent_braces=true\n"),
        )
    }

    fun testEnterContinuesADocComment() {
        myFixture.configureByText("Enter.cs", "class A\n{\n    /// <summary>\n    /// Orders<caret>\n    /// </summary>\n    void M() { }\n}\n")
        myFixture.type("\n")
        myFixture.checkResult("class A\n{\n    /// <summary>\n    /// Orders\n    /// <caret>\n    /// </summary>\n    void M() { }\n}\n")

        // an ordinary comment and code are left to the platform
        myFixture.configureByText("EnterCode.cs", "class A\n{\n    int x;<caret>\n}\n")
        myFixture.type("\n")
        assertFalse("///" in myFixture.editor.document.text)
    }
}
