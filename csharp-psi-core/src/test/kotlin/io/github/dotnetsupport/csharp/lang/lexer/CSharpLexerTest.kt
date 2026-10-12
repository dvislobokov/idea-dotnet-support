package io.github.dotnetsupport.csharp.lang.lexer

import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * Golden lexer tests (`testData/lexer/<Name>.cs` -> `<Name>.txt`); the same inputs are a root of the token gate
 * `CSharpLexerDiffCorpusTest`, which checks them against Roslyn. Inline tests pin single rules.
 */
class CSharpLexerTest : CSharpLexerTestCase() {

    fun testKeywords() = doGoldenTest()
    fun testContextual() = doGoldenTest()
    fun testNumbers() = doGoldenTest()
    fun testStrings() = doGoldenTest()
    fun testInterpolated() = doGoldenTest()
    fun testComments() = doGoldenTest()
    fun testOperators() = doGoldenTest()
    fun testBadCharacters() = doGoldenTest()
    fun testUnterminated() = doGoldenTest()
    fun testUnterminated2() = doGoldenTest()
    fun testUnterminated3() = doGoldenTest()
    fun testDirectives() = doGoldenTest()
    fun testDirectivesEvaluation() = doGoldenTest()
    fun testDirectivesForms() = doGoldenTest()
    fun testDirectivesMisplaced() = doGoldenTest()
    fun testDirectivesRegionQuirk() = doGoldenTest()
    fun testDirectivesUnterminated() = doGoldenTest()
    fun testConflictMarkers() = doGoldenTest()

    /** Every prefix of every golden input (unterminated strings, holes, comments at the end) lexes and covers its text. */
    fun testEveryPrefixOfGoldenInputs() {
        val dir = java.nio.file.Paths.get(io.github.dotnetsupport.csharp.CSharpTestUtil.testDataPath("lexer"))
        java.nio.file.Files.list(dir).use { files ->
            for (file in files.filter { it.toString().endsWith(".cs") }.toList()) {
                val text = com.intellij.openapi.util.text.StringUtil.convertLineSeparators(java.nio.file.Files.readString(file))
                for (length in 0..text.length) {
                    val tokens = lex(text.substring(0, length), 0)
                    assertEquals("$file, prefix $length", length, tokens.lastOrNull()?.end ?: 0)
                }
            }
        }
    }

    fun testShiftIsTwoGreaterThanTokens() = assertTokens(
        "a>>=b",
        """
        IdentifierToken ('a')
        GreaterThanToken ('>')
        GreaterThanEqualsToken ('>=')
        IdentifierToken ('b')
        """,
    )

    fun testDotDotBeforeDigit() = assertTokens(
        "..5",
        """
        DotToken ('.')
        DotToken ('.')
        NumericLiteralToken ('5')
        """,
    )

    /**
     * Restart points are line starts (the lexer tracks Roslyn's trivia position on the line): a string at a line start
     * has state 0, its parts do not, the first token of the next line has state 0 again.
     */
    fun testNestedInterpolationKeepsNonZeroState() {
        val tokens = lex("\$\"a{\$\"b{c}\"}\";\nx", 0)
        val start = tokens.indexOfFirst { it.type == SyntaxKind.InterpolatedStringStartToken }
        assertEquals(0, tokens[start].state)
        val end = tokens.indexOfLast { it.type == SyntaxKind.InterpolatedStringEndToken }
        for (i in start + 1..end) assertTrue("state of ${tokens[i]}", tokens[i].state != 0)
        assertEquals(0, tokens.last().state)
    }

    fun testHashInHoleIsBadCharacter() = assertTokens(
        "\$\"{#}\"",
        """
        InterpolatedStringStartToken ('${'$'}"')
        OpenBraceToken ('{')
        BAD_CHARACTER ('#')
        CloseBraceToken ('}')
        InterpolatedStringEndToken ('"')
        """,
    )

    fun testEscapedNewLineContinuesString() = assertTokens(
        "\"a\\\nb\"",
        """
        StringLiteralToken ('"a\\nb"')
        """,
    )

    fun testDocCommentAtEndOfFile() = assertTokens(
        "/// a\n/// b",
        """
        SingleLineDocumentationCommentTrivia ('/// a\n/// b')
        """,
    )

    fun testEscapedKeywordIsIdentifier() = assertTokens(
        "\\u0069nt",
        """
        IdentifierToken ('\u0069nt')
        """,
    )
}

