package io.github.dotnetsupport

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.razor.RazorFile
import io.github.dotnetsupport.frameworks.razor.RazorIndent
import io.github.dotnetsupport.frameworks.razor.RazorLanguage

/**
 * RAZOR_PLAN.md, R7: the indent of the lines of a Razor file — by the C# blocks and the elements around a line ([RazorIndent]) — for Enter
 * and Reformat Code. Found in the WSL sandbox (0.1.282): Enter after `{` of `@code`, `@if` and a method put the caret one level too
 * shallow, Reformat Code did nothing.
 */
class RazorIndentTest : BasePlatformTestCase() {
    private fun razor(text: String): RazorFile {
        myFixture.configureByText("Page${getTestName(false)}.razor", text)
        // with the caret in the C# of the file, the fixture's file is the injected one
        val top = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
        return top.viewProvider.getPsi(RazorLanguage) as RazorFile
    }

    fun testReformatIndentsTheMarkupAndTheCode() {
        razor(
            "@page \"/typing\"\n<div>\n<p>Count: @count</p>\n        <span>x</span>\n</div>\n\n@code {\nprivate int count;\n        private void Add()\n{\ncount++;\n}\n}\n",
        )
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        assertEquals(
            "@page \"/typing\"\n<div>\n    <p>Count: @count</p>\n    <span>x</span>\n</div>\n\n@code {\n    private int count;\n    private void Add()\n    {\n        count++;\n    }\n}\n",
            myFixture.editor.document.text,
        )
    }

    fun testReformatIndentsTheMarkupInACodeBlock() {
        razor("<ul>\n@foreach (var item in items)\n{\n<li>@item</li>\n}\n</ul>\n\n@code {\nstring[] items = [];\n}\n")
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        assertEquals("<ul>\n    @foreach (var item in items)\n    {\n        <li>@item</li>\n    }\n</ul>\n\n@code {\n    string[] items = [];\n}\n", myFixture.editor.document.text)
    }

    /** The children of `html` and `body` keep their indent, as the HTML of the IDE formats them ("Do not indent children of"). */
    fun testReformatLeavesTheChildrenOfHtmlAndBody() {
        val text = "<!DOCTYPE html>\n<html>\n<head>\n    <meta charset=\"utf-8\" />\n</head>\n<body>\n<Routes />\n</body>\n</html>\n"
        razor(text)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        assertEquals(text, myFixture.editor.document.text)
    }

    fun testReformatKeepsAPreAndAComment() {
        val text = "<div>\n<pre>\n  as\n     written\n</pre>\n@* a\n      comment *@\n</div>\n"
        razor(text)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        assertEquals("<div>\n    <pre>\n  as\n     written\n    </pre>\n    @* a\n      comment *@\n</div>\n", myFixture.editor.document.text)
    }

    private fun enterAfter(text: String, after: String): String {
        val offset = text.indexOf(after) + after.length
        razor(text.substring(0, offset) + "<caret>" + text.substring(offset))
        myFixture.type("\n")
        val document = myFixture.editor.document
        val line = document.getLineNumber(myFixture.editor.caretModel.offset)
        return document.text.substring(document.getLineStartOffset(line), myFixture.editor.caretModel.offset)
    }

    fun testEnterAfterTheBraceOfACodeBlock() {
        assertEquals("    ", enterAfter("<h3>x</h3>\n\n@code {\n}\n", "@code {"))
    }

    fun testEnterInAMethodOfACodeBlock() {
        assertEquals("        ", enterAfter("@code {\n    private void Add()\n    {\n    }\n}\n", "    {"))
    }

    fun testEnterInAnIfOfTheMarkup() {
        assertEquals("    ", enterAfter("<p>x</p>\n@if (true)\n{\n}\n", "@if (true)\n{"))
    }

    fun testEnterInAnElement() {
        assertEquals("    ", enterAfter("<div>\n</div>\n", "<div>"))
    }

    fun testTheShadowKeepsTheLinesAndTheCode() {
        val file = razor("@page \"/x\"\n<div>@count</div>\n@code {\n    int count;\n}\n")
        val lines = RazorIndent.shadowLines(file)
        assertEquals("the same lines", file.text.lines().size, lines.size)
        assertEquals("the directive is blank", "", lines[0])
        assertEquals("an element: a header with its brace, the expression in it blank", "x   {", lines[1].substringBefore("}").trimEnd())
        assertEquals("`@code {` is a brace at the indent of `@code`", "{", lines[2])
        assertEquals("the code as it is", "    int count;", lines[3])
    }
}
