package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.lang.PsiBuilderFactory
import com.intellij.lexer.Lexer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.CSharpFileType
import io.github.dotnetsupport.csharp.lang.CSharpParserDefinition
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.parser.SliceParseHarness
import kotlin.random.Random

/** The lexer's directives inside the platform: file symbols, the parser's view, incremental relexing in an editor. */
class CSharpPreprocessorPlatformTest : BasePlatformTestCase() {

    private val text = "#if DEBUG\nclass A { }\n#else\nclass B { }\n#endif\n"

    private fun disabledTexts(file: com.intellij.psi.PsiFile): List<String> =
        PsiTreeUtil.collectElements(file) { it.node.elementType == SyntaxKind.DisabledTextTrivia }.map { it.text }

    fun testFileUsesIdeDefaultSymbols() {
        val file = myFixture.configureByText("A.cs", text)
        assertEquals(listOf("class B { }\n"), disabledTexts(file))
    }

    fun testFileSymbolsFromUserData() {
        val virtualFile = LightVirtualFile("B.cs", CSharpFileType, text)
        virtualFile.putUserData(CSharpPreprocessorSymbols.KEY, emptySet())
        val file = PsiManager.getInstance(project).findFile(virtualFile)!!
        assertEquals(listOf("class A { }\n"), disabledTexts(file))
    }

    /**
     * Symbols on the `VirtualFile` survive reparses: `BlockSupportImpl.makeFullParse` parses a copy whose virtual file
     * is a new `LightVirtualFile` and whose `originalFile` is the original `PsiFile`, neither of which carries the key.
     */
    fun testVirtualFileSymbolsSurviveReparse() {
        val virtualFile = myFixture.tempDirFixture.createFile("C.cs", text)
        virtualFile.putUserData(CSharpPreprocessorSymbols.KEY, emptySet())
        myFixture.configureFromExistingVirtualFile(virtualFile)
        val documentManager = PsiDocumentManager.getInstance(project)
        fun psiFile() = PsiManager.getInstance(project).findFile(virtualFile)!!
        assertEquals(listOf("class A { }\n"), disabledTexts(psiFile()))
        val document = documentManager.getDocument(psiFile())!!
        val edits = listOf<(Document) -> Unit>(
            { it.setText(text + "class C { }\n") },
            { it.insertString(it.textLength, "class D { }\n") },
            { it.insertString("#if DEBUG\nclass A { ".length, "int x; ") },
            { it.insertString(0, "// c\n") },
            { it.setText(text) },
        )
        for ((i, edit) in edits.withIndex()) {
            WriteCommandAction.runWriteCommandAction(project) { edit(document) }
            documentManager.commitDocument(document)
            val texts = disabledTexts(psiFile())
            assertEquals("edit $i:\n${document.text}\n$texts", 1, texts.size)
            assertTrue("edit $i:\n${document.text}\n$texts", texts.single().startsWith("class A {"))
        }
    }

    /** PsiBuilder skips directive tokens and disabled text (comment tokens of the parser definition). */
    fun testParserNeverSeesDirectives() {
        val source = "a\n#if X\n+ b\n#elif Y || !Z // c\n- c\n#pragma warning disable CS1\n#endif\n;"
        val builder = PsiBuilderFactory.getInstance().createBuilder(CSharpParserDefinition(), CSharpLexer(), source)
        val seen = ArrayList<IElementType>()
        while (!builder.eof()) {
            seen += builder.tokenType!!
            builder.advanceLexer()
        }
        assertEquals(
            listOf(SyntaxKind.IdentifierToken, SyntaxKind.MinusToken, SyntaxKind.IdentifierToken, SyntaxKind.SemicolonToken),
            seen,
        )
    }

    /** The token window of the ported parser treats them as trivia too: `a + #if .. b` is one binary expression. */
    fun testSliceParserSkipsDirectives() {
        val result = SliceParseHarness.parse("a +\n#if X\nzzz +\n#else\n#region r\nb\n#endregion\n#endif\n", false)
        assertEquals(0, result.errorCount)
        assertEquals(0, result.errorElements)
        assertEquals("AddExpression", result.roots.single().kind)
    }

    private class Highlighter(private val symbols: Set<String>) : SyntaxHighlighterBase() {
        override fun getHighlightingLexer(): Lexer = CSharpLexer(symbols)
        override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = TextAttributesKey.EMPTY_ARRAY
    }

    /**
     * IntelliJ's incremental relexing (`LexerEditorHighlighter`: restart at a state-0 token before the change, stop at
     * a state-0 token with the old type after it) gives the tokens of a full lexing after random edits of directives.
     */
    fun testIncrementalRelexingMatchesFullLexing() {
        val symbols = setOf("DEBUG", "A")
        val fragments = listOf(
            "#if A\n", "#if !A\n", "#elif B\n", "#else\n", "#endif\n", "#define B\n", "#undef A\n", "#region r\n",
            "#endregion\n", "#", "A", "!", "\n", " ", "x; ", "/*", "*/", "// c", "\"", "class C { }\n", "$\"{", "}\"", "/// d\n",
        )
        val base = "class P { }\n#region R\n#if DEBUG\nclass A { }\n#elif A\nclass B { }\n#else\nclass C { }\n#endif\n" +
            "#define Z\n#if Z && !Q\nint z;\n#endif\n#endregion\nclass D { }\n"
        val random = Random(20261004)
        repeat(4) { round ->
            val document = EditorFactory.getInstance().createDocument(base)
            val highlighter = LexerEditorHighlighter(Highlighter(symbols), EditorColorsManager.getInstance().globalScheme)
            highlighter.setEditor(object : com.intellij.openapi.editor.highlighter.HighlighterClient {
                override fun getProject() = this@CSharpPreprocessorPlatformTest.project
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
                        document.deleteString(start, minOf(length, start + 1 + random.nextInt(8)))
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
                val lexer = CSharpLexer(symbols)
                lexer.start(document.immutableCharSequence)
                while (lexer.tokenType != null) {
                    expected += "${lexer.tokenType} ${lexer.tokenStart} ${lexer.tokenEnd}"
                    lexer.advance()
                }
                assertEquals("round $round, step $step, text:\n${document.text}", expected.joinToString("\n"), actual.joinToString("\n"))
            }
        }
    }
}
