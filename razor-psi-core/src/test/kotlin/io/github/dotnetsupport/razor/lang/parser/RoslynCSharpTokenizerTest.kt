package io.github.dotnetsupport.razor.lang.parser

import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind
import junit.framework.TestCase

/** The Roslyn-style C# tokenizer on csharp-psi's lexer: the token kinds and extents the parser relies on. */
class RoslynCSharpTokenizerTest : TestCase() {

    private fun tokens(text: String, from: Int = 0): String {
        val source = SeekableTextReader(text)
        source.position = from
        val tokenizer = RoslynCSharpTokenizer(source)
        val out = StringBuilder()
        while (true) {
            val t = tokenizer.nextToken() ?: break
            out.append(t.kind.name).append('[').append(t.content.replace("\n", "\\n")).append(']')
            if (t.diagnostics.isNotEmpty()) out.append(t.diagnostics.joinToString(","))
            out.append(' ')
        }
        return out.toString().trim()
    }

    fun testIdentifiersKeywordsAndOperators() {
        assertEquals("Keyword[var] Whitespace[ ] Identifier[x] Whitespace[ ] Assign[=] Whitespace[ ] NumericLiteral[1] CSharpOperator[+] NumericLiteral[2.5] Semicolon[;]", tokens("var x = 1+2.5;"))
        assertEquals("Identifier[a] LeftBracket[[] NumericLiteral[1] Dot[.] Dot[.] RightBracket[]]", tokens("a[1..]"))
    }

    fun testTransitionsAndLiterals() {
        assertEquals("Transition[@] Identifier[item] LessThan[<] CSharpOperator[/] Identifier[p] GreaterThan[>] RightParenthesis[)]", tokens("@item</p>)"))
        assertEquals("StringLiteral[\"a\"] Whitespace[ ] StringLiteral[\$\"x{1}y\"] Whitespace[ ] CharacterLiteral['c'] Whitespace[ ] StringLiteral[@\"v\"]", tokens("\"a\" \$\"x{1}y\" 'c' @\"v\""))
        assertEquals("StringLiteral[\"abc]RZ1000(0:0,0 [1] )", tokens("\"abc"))
    }

    fun testTriviaAndDirectives() {
        assertEquals("CSharpComment[// c] NewLine[\\n] CSharpDirective[#if !X\\n] Identifier[a] NewLine[\\n] CSharpDirective[#endif]", tokens("// c\n#if !X\na\n#endif"))
        assertEquals("CSharpDirective[#if X\\n] CSharpDisabledText[a\\n] CSharpDirective[#endif]", tokens("#if X\na\n#endif"))
        assertEquals("Identifier[x] Semicolon[;] Whitespace[ ] CSharpComment[#pragma y\\n]RZ1043(3:0,3 [10] ) Identifier[z]", tokens("x; #pragma y\nz"))
        assertEquals("RazorCommentTransition[@] RazorCommentStar[*] RazorCommentLiteral[ c ] RazorCommentStar[*] RazorCommentTransition[@] Whitespace[ ] Identifier[x]", tokens("@* c *@ x"))
    }

    fun testStartsInTheMiddleOfTheText() {
        assertEquals("Identifier[item] LessThan[<]", tokens("<p>@item<", from = 4))
    }
}
