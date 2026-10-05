package io.github.dotnetsupport

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.psi.TokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpColorSettingsPage
import io.github.dotnetsupport.lang.CSharpFormatItems
import io.github.dotnetsupport.lang.CSharpHighlightingLexer
import io.github.dotnetsupport.lang.CSharpSyntaxHighlighter
import kotlin.random.Random

/**
 * Colors inside literals, as in Rider: the editor's lexer ([CSharpHighlightingLexer]) splits strings into text, escapes, holes (their code
 * lexed as code) and alignment / format; [CSharpFormatItems] colors `{0,5:N2}` of `string.Format` and its kin. A token is written
 * `TYPE[text]`, whitespace left out.
 */
class CSharpStringColorsTest : BasePlatformTestCase() {
    private fun tokens(text: String): String {
        val lexer = CSharpHighlightingLexer()
        lexer.start(text)
        val out = ArrayList<String>()
        var last = 0
        while (true) {
            val type = lexer.tokenType ?: break
            assertEquals("no gaps", last, lexer.tokenStart)
            assertTrue("no empty tokens", lexer.tokenEnd > lexer.tokenStart)
            last = lexer.tokenEnd
            if (type != TokenType.WHITE_SPACE) out += "${type.toString().removePrefix("CSharp:")}[${text.substring(lexer.tokenStart, lexer.tokenEnd)}]"
            lexer.advance()
        }
        assertEquals("the whole text", text.length, last)
        return out.joinToString(" ")
    }

    fun testHolesAreCode() {
        assertEquals(
            """STRING[${'$'}"Order ] INTERPOLATION_BRACE[{] IDENTIFIER[x] OPERATOR[+] NUMBER[1] INTERPOLATION_BRACE[}] STRING_TEXT[ of ] """ +
                "INTERPOLATION_BRACE[{] IDENTIFIER[name] DOT[.] IDENTIFIER[Length] FORMAT_SPECIFIER[:N2] INTERPOLATION_BRACE[}] STRING_TEXT[ ] " +
                "INTERPOLATION_BRACE[{] LPAREN[(] IDENTIFIER[x] OPERATOR[>] NUMBER[0] OPERATOR[?] STRING[\"yes\"] OPERATOR[:] KEYWORD[null] RPAREN[)] " +
                "INTERPOLATION_BRACE[}] STRING_END[\"]",
            tokens("""${'$'}"Order {x + 1} of {name.Length:N2} {(x > 0 ? "yes" : null)}""""),
        )
    }

    fun testAlignmentFormatAndBraceEscapes() {
        assertEquals(
            "STRING[\$\"] STRING_ESCAPE[{{] STRING_TEXT[a] STRING_ESCAPE[}}] STRING_TEXT[ ] INTERPOLATION_BRACE[{] IDENTIFIER[b] FORMAT_SPECIFIER[,5:D] " +
                "INTERPOLATION_BRACE[}] STRING_TEXT[ ] INTERPOLATION_BRACE[{] IDENTIFIER[c] FORMAT_SPECIFIER[:yyyy-MM-dd] INTERPOLATION_BRACE[}] " +
                "STRING_ESCAPE[\\t] STRING_ESCAPE_2[\\n] STRING_END[\"]",
            tokens("\$\"{{a}} {b,5:D} {c:yyyy-MM-dd}\\t\\n\""),
        )
        assertEquals("alignment alone", "STRING[\$\"] INTERPOLATION_BRACE[{] IDENTIFIER[d] FORMAT_SPECIFIER[, -10] INTERPOLATION_BRACE[}] STRING_END[\"]",
            tokens("\$\"{d, -10}\""))
        assertEquals("`global::` is no format",
            "STRING[\$\"] INTERPOLATION_BRACE[{] KEYWORD[global] OPERATOR[:] OPERATOR[:] IDENTIFIER[System] DOT[.] IDENTIFIER[Int32] INTERPOLATION_BRACE[}] STRING_END[\"]",
            tokens("\$\"{global::System.Int32}\""))
    }

    fun testEscapes() {
        assertEquals(
            "STRING[\"a] STRING_ESCAPE[\\t] STRING_TEXT[b] STRING_ESCAPE[\\n] STRING_ESCAPE_2[\\r] STRING_ESCAPE[\\\\] STRING_INVALID_ESCAPE[\\q] " +
                "STRING_ESCAPE[\\x41] STRING_ESCAPE_2[\\u0041] STRING_INVALID_ESCAPE[\\u12] STRING_TEXT[z] STRING_ESCAPE[\\\"] STRING_END[\"]",
            tokens("\"a\\tb\\n\\r\\\\\\q\\x41\\u0041\\u12z\\\"\""),
        )
        assertEquals("verbatim: only doubled quotes", "STRING[@\"C:\\dir ] STRING_ESCAPE[\"\"] STRING_TEXT[x] STRING_ESCAPE[\"\"] STRING_END[\"]",
            tokens("@\"C:\\dir \"\"x\"\"\""))
        assertEquals("chars", "CHAR['] STRING_ESCAPE[\\n] CHAR_END['] CHAR['a'] CHAR['] STRING_INVALID_ESCAPE[\\z] CHAR_END[']", tokens("'\\n' 'a' '\\z'"))
        assertEquals("a literal without escapes is one token", "STRING[\"plain\"] STRING[@\"C:\\x\"] STRING[\"\"\"raw \\n {x}\"\"\"]",
            tokens("\"plain\" @\"C:\\x\" \"\"\"raw \\n {x}\"\"\""))
        assertEquals("unterminated: no end", "STRING[\"abc] STRING_ESCAPE[\\n] IDENTIFIER[x]", tokens("\"abc\\n\nx"))
    }

    fun testRawStringsWithDollars() {
        assertEquals(
            "STRING[\$\$\"\"\"{x} ] INTERPOLATION_BRACE[{{] IDENTIFIER[y] INTERPOLATION_BRACE[}}] STRING_TEXT[ {] INTERPOLATION_BRACE[{{] IDENTIFIER[z] " +
                "FORMAT_SPECIFIER[:N0] INTERPOLATION_BRACE[}}] STRING_END[}\"\"\"]",
            tokens("\$\$\"\"\"{x} {{y}} {{{z:N0}}}\"\"\""),
        )
        assertEquals("STRING[\$\"\"\"a ] INTERPOLATION_BRACE[{] IDENTIFIER[b] INTERPOLATION_BRACE[}] STRING_END[ \\n\"\"\"]", tokens("\$\"\"\"a {b} \\n\"\"\""))
    }

    fun testNestedLiterals() {
        assertEquals(
            "STRING[\$\"a ] INTERPOLATION_BRACE[{] STRING[\$\"b ] INTERPOLATION_BRACE[{] IDENTIFIER[c] INTERPOLATION_BRACE[}] STRING_ESCAPE[\\n] STRING_END[\"] " +
                "INTERPOLATION_BRACE[}] STRING_END[ d\"]",
            tokens("\$\"a {\$\"b {c}\\n\"} d\""),
        )
        assertEquals("STRING[\$@\"x ] INTERPOLATION_BRACE[{] IDENTIFIER[y] INTERPOLATION_BRACE[}] STRING_ESCAPE[\"\"] STRING_END[\"]", tokens("\$@\"x {y}\"\"\""))
    }

    /** The platform relexes from a token of state 0 before an edit: random edits of literals give the tokens of a full lexing. */
    fun testIncrementalRelexingMatchesFullLexing() {
        val fragments = listOf("\"", "\$\"", "@", "{", "}", "{{", "\\n", "\\t", "\"\"\"", "\$\$", "'", ":", ",", "x", " ", "\n", "(", ")", "// c\n")
        val base = "class P { string a = \$\"Order {x + 1,5:N2} \\t\\n{(y ? \"a\\n\" : \$\"{z}\")}\"; char c = '\\n';\n" +
            "string b = @\"C:\\a \"\"q\"\"\"; string r = \$\$\"\"\"{{w}} {x}\"\"\"; }\n"
        val random = Random(20261005)
        repeat(4) { round ->
            val document = EditorFactory.getInstance().createDocument(base)
            val highlighter = LexerEditorHighlighter(CSharpSyntaxHighlighter(), EditorColorsManager.getInstance().globalScheme)
            highlighter.setEditor(object : com.intellij.openapi.editor.highlighter.HighlighterClient {
                override fun getProject() = this@CSharpStringColorsTest.project
                override fun repaint(start: Int, end: Int) {}
                override fun getDocument() = document
            })
            highlighter.setText(document.immutableCharSequence)
            document.addDocumentListener(highlighter)
            repeat(150) { step ->
                WriteCommandAction.runWriteCommandAction(project) {
                    val length = document.textLength
                    if (length > 0 && random.nextInt(3) == 0) {
                        val start = random.nextInt(length)
                        document.deleteString(start, minOf(length, start + 1 + random.nextInt(6)))
                    } else {
                        document.insertString(random.nextInt(length + 1), fragments[random.nextInt(fragments.size)])
                    }
                }
                val actual = ArrayList<String>()
                val iterator = highlighter.createIterator(0)
                while (!iterator.atEnd()) {
                    actual += "${iterator.tokenType} ${iterator.start} ${iterator.end}"
                    iterator.advance()
                }
                val expected = ArrayList<String>()
                val lexer = CSharpHighlightingLexer()
                lexer.start(document.immutableCharSequence)
                while (lexer.tokenType != null) {
                    expected += "${lexer.tokenType} ${lexer.tokenStart} ${lexer.tokenEnd}"
                    lexer.advance()
                }
                assertEquals("round $round, step $step, text:\n${document.text}", expected.joinToString("\n"), actual.joinToString("\n"))
            }
        }
    }

    private fun keysAt(text: String, piece: String, occurrence: Int = 0): List<String> {
        var offset = -1
        repeat(occurrence + 1) { offset = text.indexOf(piece, offset + 1) }
        assertTrue("$piece in the text", offset >= 0)
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(offset)
        assertEquals("a token starts at $piece", offset, iterator.start)
        return iterator.textAttributesKeys.map(TextAttributesKey::getExternalName)
    }

    /** What the editor shows: keywords, numbers and operators of a hole in their colors, escapes and format in Rider's keys. */
    fun testTheEditorColors() {
        val text = "class StrColors { void M(int x, string name) { var s = \$\"Order {x + 1} of {name.Length,5:N2} {(x > 0 ? \"yes\" : null)}\\t\\n\"; } }"
        myFixture.configureByText("StrColors.cs", text)
        assertEquals(listOf("CSHARP_OPERATOR"), keysAt(text, "+ 1"))
        assertEquals(listOf("CSHARP_NUMBER"), keysAt(text, "1}"))
        assertEquals(listOf("CSHARP_KEYWORD"), keysAt(text, "null)"))
        assertEquals(listOf("CSHARP_STRING"), keysAt(text, "\"yes\""))
        assertEquals(listOf("CSHARP_BRACES"), keysAt(text, "{x + 1"))
        assertEquals(listOf("CSHARP_FORMAT_STRING_ITEM"), keysAt(text, ",5:N2"))
        assertEquals(listOf("CSHARP_ESCAPE_CHARACTER_1"), keysAt(text, "\\t"))
        assertEquals(listOf("CSHARP_ESCAPE_CHARACTER_2"), keysAt(text, "\\n"))
        assertEquals(listOf("CSHARP_STRING"), keysAt(text, " of "))
    }

    fun testFormatItems() {
        fun items(code: String): List<String> =
            CSharpFormatItems.items(code).map { (range, second) -> range.substring(code) + if (second) ":2" else "" }
        assertEquals(listOf("{0}", "{1,5:N2}", "{2}", "{3}:2"), items("var s = string.Format(\"a {0} b {1,5:N2} {{c}} {2}{3}\", x, y, z, w);"))
        assertEquals(listOf("{0:D}"), items("Console.WriteLine(\"n = {0:D}\", n);"))
        assertEquals(listOf("{0}"), items("builder.AppendFormat(CultureInfo.InvariantCulture, @\"\"\"{0}\"\"\", n);"))
        assertEquals("a provider first", listOf("{0}"), items("String.Format(CultureInfo.InvariantCulture, \"{0}\", n);"))
        assertEquals("no arguments to format: the string is printed as is", emptyList<String>(), items("Console.WriteLine(\"{0}\");"))
        assertEquals("interpolated", emptyList<String>(), items("string.Format(\$\"{0}\", n);"))
        assertEquals("a category, not arguments", emptyList<String>(), items("Debug.WriteLine(\"{0}\", \"category\");"))
        assertEquals("no item", emptyList<String>(), items("string.Format(\"{x} {} {0\", n);"))
        assertEquals("another method", emptyList<String>(), items("Log(\"{0}\", n);"))
    }

    fun testFormatItemsAreHighlighted() {
        myFixture.configureByText("StrFormat.cs", "class StrFormat { void M(double t) { var s = string.Format(\"{0} of {1:N2}\", 1, t); } }")
        val items = myFixture.doHighlighting().filter { it.forcedTextAttributesKey == CSharpSyntaxHighlighter.FORMAT_ITEM }.map { it.text }
        assertEquals(listOf("{0}", "{1:N2}"), items)
    }

    fun testColorPage() {
        val page = CSharpColorSettingsPage()
        val described = page.attributeDescriptors.associate { it.key to it.displayName }
        assertEquals("String//Escape sequence//Valid", described[CSharpSyntaxHighlighter.ESCAPE])
        assertEquals("String//Format item", described[CSharpSyntaxHighlighter.FORMAT_ITEM])
        for (key in listOf(CSharpSyntaxHighlighter.ESCAPE_2, CSharpSyntaxHighlighter.INVALID_ESCAPE, CSharpSyntaxHighlighter.FORMAT_ITEM_2)) assertTrue(key in described)
        val dark = checkNotNull(EditorColorsManager.getInstance().getScheme("Darcula"))
        assertEquals(0xD688D4, dark.getAttributes(CSharpSyntaxHighlighter.ESCAPE).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0x66C3CC, dark.getAttributes(CSharpSyntaxHighlighter.ESCAPE_2).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0xC191FF, dark.getAttributes(CSharpSyntaxHighlighter.FORMAT_ITEM).foregroundColor.rgb and 0xFFFFFF)
    }
}
