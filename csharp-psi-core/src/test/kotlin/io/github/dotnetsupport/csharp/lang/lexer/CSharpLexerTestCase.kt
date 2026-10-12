package io.github.dotnetsupport.csharp.lang.lexer

import com.intellij.lexer.Lexer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.LexerTestCase
import io.github.dotnetsupport.csharp.CSharpTestUtil
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Base of lexer tests. Golden tests lex `testData/lexer/<TestName>.cs` into `<TestName>.txt` (`TYPE ('text')` per
 * token, rewritten with `-Dcsharppsi.updateGoldens=true`). Every lexed text is also checked for restartability.
 * [symbols]: the preprocessor symbols of [createLexer] (none, as `roslyndump` without `--define`).
 */
abstract class CSharpLexerTestCase : LexerTestCase() {

    protected var symbols: Set<String> = emptySet()

    override fun createLexer(): Lexer = CSharpLexer(symbols)

    override fun getDirPath(): String = "lexer"

    override fun getPathToTestDataFile(extension: String): String =
        CSharpTestUtil.testDataPath(dirPath) + "/" + getTestName(false) + extension

    /** Lexes `<TestName>.cs` and compares with `<TestName>.txt`; checks restartability. */
    protected fun doGoldenTest() {
        val source = Paths.get(getPathToTestDataFile(".cs"))
        val text = StringUtil.convertLineSeparators(Files.readString(source))
        val actual = printTokens(text, 0)
        val golden = Paths.get(getPathToTestDataFile(".txt"))
        if (CSharpTestUtil.updateGoldens || !Files.exists(golden)) {
            Files.writeString(golden, actual)
            if (!CSharpTestUtil.updateGoldens) fail("Golden created: $golden")
        } else {
            assertEquals(StringUtil.convertLineSeparators(Files.readString(golden)), actual)
        }
        assertRestartable(text)
    }

    protected fun assertTokens(text: String, expected: String) {
        assertEquals(expected.trimIndent().trim(), printTokens(text, 0).trim())
        assertRestartable(text)
    }

    /**
     * The IntelliJ incremental relexing invariant: lexing from any token start whose state is 0 gives the same tokens
     * as lexing from the beginning. Also: tokens are contiguous, non-empty and cover the text.
     */
    protected fun assertRestartable(text: CharSequence) {
        val all = lex(text, 0)
        var expectedStart = 0
        for (t in all) {
            assertEquals("gap or overlap before $t", expectedStart, t.start)
            assertTrue("empty token $t", t.end > t.start)
            expectedStart = t.end
        }
        assertEquals("tokens do not cover the text", text.length, expectedStart)
        // The platform's own check (restarts at state-0 tokens, states compared too).
        checkCorrectRestart(text.toString())
        for ((index, t) in all.withIndex()) {
            if (t.state != 0 || index == 0) continue
            val restarted = lex(text, t.start)
            val tail = all.subList(index, all.size).map { it.copy(state = 0) }
            assertEquals("restart at ${t.start} (${t.type})", tail, restarted.map { it.copy(state = 0) })
        }
    }

    protected data class Token(val type: IElementType, val start: Int, val end: Int, val state: Int)

    protected fun lex(text: CharSequence, start: Int): List<Token> {
        val lexer = createLexer()
        lexer.start(text, start, text.length, 0)
        val result = ArrayList<Token>()
        while (true) {
            val type = lexer.tokenType ?: break
            result += Token(type, lexer.tokenStart, lexer.tokenEnd, lexer.state)
            lexer.advance()
        }
        return result
    }
}
