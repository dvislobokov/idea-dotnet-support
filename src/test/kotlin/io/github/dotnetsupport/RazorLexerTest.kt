package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.frameworks.razor.RazorFileKind
import io.github.dotnetsupport.frameworks.razor.RazorLexer

/**
 * RAZOR_PLAN.md, R1: the tokens of [RazorLexer] for each construct of Razor, as the compiler's parser splits them (the spans of
 * `.cspans.txt`: Markup, Transition, MetaCode, Code, Comment); the gate on the compiler's own test files is [RazorLexerGateTest].
 */
class RazorLexerTest : BasePlatformTestCase() {
    private fun tokens(text: String, kind: RazorFileKind = RazorFileKind.COMPONENT, split: Boolean = false): String {
        val lexer = RazorLexer(kind, split)
        lexer.start(text, 0, text.length, 0)
        val lines = ArrayList<String>()
        var offset = 0
        while (lexer.tokenType != null) {
            assertEquals("no gap before ${lexer.tokenType} at ${lexer.tokenStart}", offset, lexer.tokenStart)
            assertTrue("a token of no length at $offset", lexer.tokenEnd > lexer.tokenStart)
            offset = lexer.tokenEnd
            val type = lexer.tokenType.toString().removePrefix("Razor:")
            lines += "$type '" + text.substring(lexer.tokenStart, lexer.tokenEnd).replace("\r", "\\r").replace("\n", "\\n") + "'"
            lexer.advance()
        }
        assertEquals("the whole text", text.length, offset)
        return lines.joinToString("\n")
    }

    private fun check(text: String, expected: String, kind: RazorFileKind = RazorFileKind.COMPONENT) =
        assertEquals(expected.trimIndent(), tokens(text, kind))

    fun testImplicitExpression() = check(
        "<p>Hello @Model.Name!</p>",
        """
        MARKUP '<p>Hello '
        TRANSITION '@'
        CSHARP 'Model.Name'
        MARKUP '!</p>'
        """,
    )

    fun testImplicitExpressionWithCallsIndexesAndNullOperators() = check(
        "@Items[0]?.Name!.Trim(\"(\").Length. done",
        """
        TRANSITION '@'
        CSHARP 'Items[0]?.Name!.Trim("(").Length'
        MARKUP '. done'
        """,
    )

    fun testAwait() = check(
        "<p>@await LoadAsync(1)</p>",
        """
        MARKUP '<p>'
        TRANSITION '@'
        CSHARP 'await LoadAsync(1)'
        MARKUP '</p>'
        """,
    )

    fun testExplicitExpression() = check(
        "<b>@(a + (b * 2))</b>",
        """
        MARKUP '<b>'
        TRANSITION '@'
        LPAREN '('
        CSHARP 'a + (b * 2)'
        RPAREN ')'
        MARKUP '</b>'
        """,
    )

    fun testAnUnclosedExplicitExpressionStopsAtTheMarkup() = check(
        "@(a + <p>x</p>",
        """
        TRANSITION '@'
        LPAREN '('
        CSHARP 'a + '
        MARKUP '<p>x</p>'
        """,
    )

    fun testCodeBlockWithMarkup() = check(
        "@{ var x = \"}\"; <p class=\"a\">@x</p>\n}",
        """
        TRANSITION '@'
        LBRACE '{'
        CSHARP_CODE ' var x = "}"; '
        MARKUP '<p class="a">'
        TRANSITION '@'
        CSHARP 'x'
        MARKUP '</p>\n'
        RBRACE '}'
        """,
    )

    fun testLessThanInCodeIsNotMarkup() = check(
        "@{ if (a < b) { x = a<b; } List<int> l; }",
        """
        TRANSITION '@'
        LBRACE '{'
        CSHARP_CODE ' if (a < b) { x = a<b; } List<int> l; '
        RBRACE '}'
        """,
    )

    fun testCodeDirective() = check(
        "@code {\n    private int count;\n    void Render() { <p>@count</p> }\n}",
        """
        TRANSITION '@'
        DIRECTIVE 'code'
        WHITE_SPACE ' '
        LBRACE '{'
        CSHARP_CODE '\n    private int count;\n    void Render() { '
        MARKUP '<p>'
        TRANSITION '@'
        CSHARP 'count'
        MARKUP '</p> '
        CSHARP_CODE '}\n'
        RBRACE '}'
        """,
    )

    fun testLineDirectives() = check(
        "@page \"/counter\"\n@using System.Linq\n@inject ILogger<Counter> Logger\n@rendermode InteractiveServer\n",
        """
        TRANSITION '@'
        DIRECTIVE 'page'
        CSHARP ' "/counter"'
        MARKUP '\n'
        TRANSITION '@'
        CSHARP 'using System.Linq'
        MARKUP '\n'
        TRANSITION '@'
        DIRECTIVE 'inject'
        CSHARP ' ILogger<Counter> Logger'
        MARKUP '\n'
        TRANSITION '@'
        DIRECTIVE 'rendermode'
        CSHARP ' InteractiveServer'
        MARKUP '\n'
        """,
    )

    fun testIfElse() = check(
        "@if (a) {\n  <b>x</b>\n} else if (b) { <i>y</i> } else {\n  @:text @y\n}\n<p/>",
        """
        TRANSITION '@'
        CSHARP_CODE 'if (a) {\n  '
        MARKUP '<b>x</b>\n'
        CSHARP_CODE '} else if (b) { '
        MARKUP '<i>y</i> '
        CSHARP_CODE '} else {\n  '
        TRANSITION '@'
        COLON ':'
        MARKUP 'text '
        TRANSITION '@'
        CSHARP 'y'
        MARKUP '\n'
        CSHARP_CODE '}'
        MARKUP '\n<p/>'
        """,
    )

    fun testForeachSwitchAndTemplates() = check(
        "@foreach (var i in items) { @switch (i) { case 1: <b>one</b> break; default: <text>@i</text> break; } }",
        """
        TRANSITION '@'
        CSHARP_CODE 'foreach (var i in items) { '
        TRANSITION '@'
        CSHARP_CODE 'switch (i) { case 1: '
        MARKUP '<b>one</b> '
        CSHARP_CODE 'break; default: '
        TEXT_TAG '<text>'
        TRANSITION '@'
        CSHARP 'i'
        TEXT_TAG '</text>'
        CSHARP_CODE ' break; } }'
        """,
    )

    fun testDoWhileAndTryCatch() = check(
        "@do { x++; } while (x < 3);@try { <a/> } catch (E e) { } finally { }!",
        """
        TRANSITION '@'
        CSHARP_CODE 'do { x++; } while (x < 3);'
        TRANSITION '@'
        CSHARP_CODE 'try { '
        MARKUP '<a/> '
        CSHARP_CODE '} catch (E e) { } finally { }'
        MARKUP '!'
        """,
    )

    fun testUsingStatementAndDirective() = check(
        "@using (Html.BeginForm()) { <form/> }\n@using Foo",
        """
        TRANSITION '@'
        CSHARP_CODE 'using (Html.BeginForm()) { '
        MARKUP '<form/> '
        CSHARP_CODE '}'
        MARKUP '\n'
        TRANSITION '@'
        CSHARP 'using Foo'
        """,
    )

    fun testTemplateInCode() = check(
        "@{ RenderFragment<int> f = @<td>@context</td>; }",
        """
        TRANSITION '@'
        LBRACE '{'
        CSHARP_CODE ' RenderFragment<int> f = '
        TRANSITION '@'
        MARKUP '<td>'
        TRANSITION '@'
        CSHARP 'context'
        MARKUP '</td>'
        CSHARP_CODE '; '
        RBRACE '}'
        """,
    )

    fun testCommentsEscapeAndEmail() = check(
        "@* a\n comment *@<a href=\"mailto:me@example.com\">@@me x@y</a>",
        """
        COMMENT '@* a\n comment *@'
        MARKUP '<a href="mailto:me@example.com">'
        ESCAPE '@'
        MARKUP '@'
        MARKUP 'me x@y</a>'
        """,
    )

    fun testCommentInCode() = check(
        "@{ @* note *@ var a = 1; }",
        """
        TRANSITION '@'
        LBRACE '{'
        CSHARP_CODE ' '
        COMMENT '@* note *@'
        CSHARP_CODE ' var a = 1; '
        RBRACE '}'
        """,
    )

    fun testTransitionInAttributeValue() = check(
        "<div class=\"a @cls b\" title='@(x)'>",
        """
        MARKUP '<div class="a '
        TRANSITION '@'
        CSHARP 'cls'
        MARKUP ' b" title=''
        TRANSITION '@'
        LPAREN '('
        CSHARP 'x'
        RPAREN ')'
        MARKUP ''>'
        """,
    )

    fun testDirectiveAttributesOfAComponent() = check(
        "<input @bind-Value=\"Name\" @bind-Value:event=\"oninput\" @onclick=\"@(() => n++)\" @key=item disabled />",
        """
        MARKUP '<input '
        DIRECTIVE_ATTRIBUTE '@bind-Value'
        ATTRIBUTE_VALUE '="'
        CSHARP 'Name'
        ATTRIBUTE_VALUE '"'
        MARKUP ' '
        DIRECTIVE_ATTRIBUTE '@bind-Value:event'
        ATTRIBUTE_VALUE '="oninput"'
        MARKUP ' '
        DIRECTIVE_ATTRIBUTE '@onclick'
        ATTRIBUTE_VALUE '="'
        TRANSITION '@'
        LPAREN '('
        CSHARP '() => n++'
        RPAREN ')'
        ATTRIBUTE_VALUE '"'
        MARKUP ' '
        DIRECTIVE_ATTRIBUTE '@key'
        ATTRIBUTE_VALUE '='
        CSHARP 'item'
        MARKUP ' disabled />'
        """,
    )

    fun testInAViewAnAttributeAreaTransitionIsCSharp() = check(
        "<input @attrs />",
        """
        MARKUP '<input '
        TRANSITION '@'
        CSHARP 'attrs'
        MARKUP ' />'
        """,
        RazorFileKind.LEGACY,
    )

    fun testSectionAndTagHelpersOfAView() = check(
        "@model Shop.Order\n@addTagHelper *, Microsoft.AspNetCore.Mvc.TagHelpers\n@section Scripts {\n<script>if (a) { b(); }</script>\n}",
        """
        TRANSITION '@'
        DIRECTIVE 'model'
        CSHARP ' Shop.Order'
        MARKUP '\n'
        TRANSITION '@'
        DIRECTIVE 'addTagHelper'
        WHITE_SPACE ' '
        DIRECTIVE_VALUE '*, Microsoft.AspNetCore.Mvc.TagHelpers'
        MARKUP '\n'
        TRANSITION '@'
        DIRECTIVE 'section'
        CSHARP ' Scripts'
        WHITE_SPACE ' '
        LBRACE '{'
        MARKUP '\n<script>if (a) { b(); }</script>\n'
        RBRACE '}'
        """,
        RazorFileKind.LEGACY,
    )

    fun testDirectivesDependOnTheKind() {
        assertEquals("TRANSITION '@'\nCSHARP 'code'\nMARKUP ' {'", tokens("@code {", RazorFileKind.LEGACY))
        assertEquals("TRANSITION '@'\nCSHARP 'section'\nMARKUP ' x'", tokens("@section x", RazorFileKind.COMPONENT))
        assertEquals("TRANSITION '@'\nCSHARP 'model'", tokens("@model", RazorFileKind.COMPONENT))
    }

    fun testScriptContentIsNotTags() = check(
        "@{ <script>var s = '</div>'; if (a < b) {}</script> }",
        """
        TRANSITION '@'
        LBRACE '{'
        CSHARP_CODE ' '
        MARKUP '<script>var s = '</div>'; if (a < b) {}</script> '
        RBRACE '}'
        """,
    )

    fun testTheEditorTokensSplitTheCSharp() {
        val text = "@code { int n = 1; }"
        assertEquals(
            """
            Razor:TRANSITION '@'
            Razor:DIRECTIVE 'code'
            WHITE_SPACE ' '
            Razor:LBRACE '{'
            WHITE_SPACE ' '
            CSharp:KEYWORD 'int'
            WHITE_SPACE ' '
            CSharp:IDENTIFIER 'n'
            WHITE_SPACE ' '
            CSharp:OPERATOR '='
            WHITE_SPACE ' '
            CSharp:NUMBER '1'
            CSharp:SEMICOLON ';'
            WHITE_SPACE ' '
            Razor:RBRACE '}'
            """.trimIndent(),
            RazorLexer(RazorFileKind.COMPONENT, split = true).let { lexer ->
                lexer.start(text, 0, text.length, 0)
                generateSequence { lexer.tokenType?.let { type -> "$type '${text.substring(lexer.tokenStart, lexer.tokenEnd)}'".also { lexer.advance() } } }.joinToString("\n")
            },
        )
    }

    /** The editor restarts the lexer at a token of state 0: from there it must give the same tokens as from the start of the file. */
    fun testRestartsAtStateZeroGiveTheSameTokens() {
        val text = """
            @page "/x"
            <div class="a @b" @onclick="Go">@Model.Name @* c *@ a@b.c</div>
            @if (x) { <p>@x</p> } else { <text>y</text> }
            <script>var a = "@b";</script>
            @code { void Go() { } }
            tail
        """.trimIndent()
        for (split in listOf(false, true)) {
            val all = lex(text, 0, split)
            for ((start, state) in all.map { it.first to it.third }) {
                if (state != 0) continue
                assertEquals("restart at $start (split=$split)", all.filter { it.first >= start }, lex(text, start, split))
            }
            assertTrue(all.count { it.third == 0 } > 5)
        }
    }

    private fun lex(text: String, start: Int, split: Boolean): List<Triple<Int, String, Int>> {
        val lexer = RazorLexer(RazorFileKind.COMPONENT, split)
        lexer.start(text, start, text.length, 0)
        val result = ArrayList<Triple<Int, String, Int>>()
        while (lexer.tokenType != null) {
            result += Triple(lexer.tokenStart, "${lexer.tokenType}:${lexer.tokenEnd}", lexer.state)
            lexer.advance()
        }
        return result
    }
}
