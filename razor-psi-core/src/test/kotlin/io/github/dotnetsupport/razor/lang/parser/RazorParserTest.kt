package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind
import io.github.dotnetsupport.razor.lang.syntax.RazorParserOptions
import io.github.dotnetsupport.razor.lang.syntax.SyntaxSerializer
import junit.framework.TestCase

/** Fast tests of the parser on snippets: the shape of the tree in the serializer's format (the gates are the corpus tests). */
class RazorParserTest : TestCase() {

    private fun tree(text: String, kind: RazorFileKind = RazorFileKind.Legacy): String =
        SyntaxSerializer.serialize(RazorParser.parse(text, RazorParserOptions.forFile(if (kind == RazorFileKind.Legacy) "a.cshtml" else "A.razor", useRoslynTokenizer = false)).root)

    fun testMarkupAndImplicitExpression() {
        val out = tree("<p>Hello @Model.Name!</p>")
        assertTrue(out, "MarkupElement - [0..25) - Width: 25" in out)
        assertTrue(out, "CSharpImplicitExpression - [9..20) - Width: 11" in out)
        assertTrue(out, "CSharpExpressionLiteral - [10..20) - Width: 10 - [Model.Name] - Gen<Expr>" in out)
        assertTrue(out, "EndOfFile;[];" in out)
    }

    fun testCodeBlockAndDirective() {
        val out = tree("@page \"/counter\"\r\n@code {\r\n    int count = 0;\r\n}\r\n<h1>@count</h1>", RazorFileKind.Component)
        assertTrue(out, "RazorDirective - [0..18) - Width: 18 - Directive:{page;SingleLine;FileScopedMultipleOccurring}" in out)
        assertTrue(out, "RazorDirective - [18..48) - Width: 30 - Directive:{code;CodeBlock;Unrestricted}" in out)
        assertTrue(out, "CSharpStatementLiteral - [25..47) - Width: 22 - [LF    int count = 0;LF] - Gen<Stmt>" in out)
    }

    fun testUnclosedBlockReportsAnError() {
        val tree = RazorParser.parse("@if (x) {\n<p>", RazorParserOptions.forFile("a.cshtml", useRoslynTokenizer = false))
        val ids = tree.diagnostics.map { it.id }
        assertTrue(ids.toString(), "RZ1006" in ids)
        assertTrue(ids.toString(), "RZ1025" in ids)
        assertEquals("@if (x) {\n<p>", tree.root.text())
    }

    fun testRoundTripOfTheCorpusShapes() {
        for (text in listOf("", "@", "@@", "@* comment", "<a href=\"@url\">x</a>", "@{ var x = 1; <text>t</text> }", "@section S { <p>@x</p> }", "<!-- c --> <![CDATA[ d ]]> <?xml ?>")) {
            val tree = RazorParser.parse(text, RazorParserOptions.forFile("a.cshtml", useRoslynTokenizer = false))
            assertEquals(text, tree.root.text())
        }
    }
}
