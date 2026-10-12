package io.github.dotnetsupport.csharp.lang.lexer

import io.github.dotnetsupport.csharp.lang.SyntaxKind

/**
 * Directives and `#if` evaluation in the lexer (`Lexer.LexDirectiveAndExcludedTrivia`, `DirectiveParser`,
 * `DirectiveStack`). The goldens `testData/lexer/Directives*.cs` (in [CSharpLexerTest]) are also checked against
 * Roslyn by the token gate; these tests pin single rules, the symbols and the lexer state.
 */
class CSharpPreprocessorLexerTest : CSharpLexerTestCase() {

    /** Identifiers of code (not of directives) that are active, i.e. not in disabled text; checks restarts too. */
    private fun active(text: String, vararg defined: String): List<String> {
        symbols = defined.toSet()
        try {
            assertRestartable(text)
            return lex(text, 0).filter { it.type == SyntaxKind.IdentifierToken }.map { text.substring(it.start, it.end) }
        } finally {
            symbols = emptySet()
        }
    }

    fun testSymbolsSelectTheBranch() {
        val text = "#if DEBUG\na\n#else\nb\n#endif\nc"
        assertEquals(listOf("a", "c"), active(text, "DEBUG"))
        assertEquals(listOf("b", "c"), active(text))
    }

    fun testSymbolsAreCaseSensitive() {
        assertEquals(listOf("b"), active("#if debug\na\n#else\nb\n#endif", "DEBUG"))
    }

    fun testDefineAndUndefOverrideSymbols() {
        assertEquals(listOf("b"), active("#undef DEBUG\n#if DEBUG\na\n#else\nb\n#endif", "DEBUG"))
        assertEquals(listOf("a"), active("#define X\n#if X\na\n#else\nb\n#endif"))
        // A #define in an excluded branch has no effect after its #endif (DirectiveStack.CompleteIf) ...
        assertEquals(listOf("b"), active("#if Q\n#define X\n#endif\n#if X\na\n#else\nb\n#endif"))
        // ... one in a taken branch stays.
        assertEquals(listOf("a"), active("#if true\n#define X\n#endif\n#if X\na\n#else\nb\n#endif"))
    }

    fun testBranchIsTakenOnce() {
        assertEquals(listOf("a"), active("#if A\na\n#elif A\nb\n#else\nc\n#endif", "A"))
        assertEquals(listOf("b"), active("#if A\na\n#elif B\nb\n#elif B\nc\n#else\nd\n#endif", "B"))
        assertEquals(listOf("d"), active("#if A\na\n#elif B\nb\n#else\nd\n#endif"))
    }

    fun testNestedIfInExcludedBranchIsNeverTaken() {
        // Inside an excluded branch a nested #if is not taken whatever its condition, and its #endif is matched.
        assertEquals(listOf("d"), active("#if A\n#if true\nb\n#else\nc\n#endif\n#else\nd\n#endif"))
    }

    fun testExpressionOperatorsAndQuirks() {
        assertEquals(listOf("a"), active("#if (A || B) && !C == true != false\na\n#endif", "B"))
        // bool.TryParse is case-insensitive and sees escapes decoded: TRUE and true are true.
        assertEquals(listOf("a"), active("#if TRUE && tru\\u0065\na\n#endif"))
        // ParseEquality is right-associative: false == (false == true) is true.
        assertEquals(listOf("a"), active("#if A == B == true\na\n#endif"))
        // A #define in an inactive branch is seen by a later #elif of the same #if (DirectiveStack.IsDefined).
        assertEquals(listOf("a"), active("#if Q\n#define X\n#elif X\na\n#endif"))
        // A missing name is "": `#define` without a name defines it, so `#if !` (a missing operand) is false.
        assertEquals(emptyList<String>(), active("#define\n#if !\na\n#endif"))
        assertEquals(listOf("a"), active("#if !\na\n#endif"))
    }

    fun testBadDirectivesHaveNoBranches() {
        assertEquals(listOf("a", "b"), active("#endif\na\n#else\nb"))
        // An #elif without #if is bad: its expression becomes disabled text, no branch.
        assertTokens(
            "#elif X // c\na",
            """
            HashTokenInDirectiveTrivia ('#')
            ElifKeywordInDirectiveTrivia ('elif')
            WhitespaceTrivia (' ')
            DisabledTextTrivia ('X // c')
            EndOfLineTrivia ('\n')
            IdentifierToken ('a')
            """,
        )
    }

    fun testUnterminatedIfRunsToTheEnd() = assertTokens(
        "#if X\na\n#region R\nb",
        """
        HashTokenInDirectiveTrivia ('#')
        IfKeywordInDirectiveTrivia ('if')
        WhitespaceTrivia (' ')
        IdentifierTokenInDirectiveTrivia ('X')
        EndOfLineTrivia ('\n')
        DisabledTextTrivia ('a\n')
        HashTokenInDirectiveTrivia ('#')
        RegionKeywordInDirectiveTrivia ('region')
        WhitespaceTrivia (' ')
        PreprocessingMessageTrivia ('R')
        EndOfLineTrivia ('\n')
        DisabledTextTrivia ('b')
        """,
    )

    fun testMisplacedDirectivesContinueTrailingTrivia() {
        // `#define Y` after a token is misplaced; so is the next line's `#if Y` (Roslyn's trailing trivia goes on).
        assertEquals(listOf("x", "a"), active("x; #define Y\n#if Y\na"))
        // An empty line ends the trailing trivia: the #if is placed, and Y is not defined.
        assertEquals(listOf("x", "b"), active("x; #define Y\n\n#if Y\na\n#else\nb\n#endif"))
        // After a delimited comment on the line (Roslyn's `onlyWhitespaceOnLine`).
        assertEquals(listOf("a"), active("/* c */ #if X\na"))
        // A delimited doc comment leaves `onlyWhitespaceOnLine` alone: the directive is placed.
        assertEquals(emptyList<String>(), active("/** d */ #if X\na\n#endif"))
        // A single-line doc comment after a comment takes the new line in Roslyn: the next line is still "dirty".
        assertEquals(listOf("a"), active("/* c */ /// d\n#if X\na"))
    }

    fun testPragmaWarningAndMessages() = assertTokens(
        "#pragma warning disable CS0168, x\n#region a // b\n#error  e ",
        """
        HashTokenInDirectiveTrivia ('#')
        PragmaKeywordInDirectiveTrivia ('pragma')
        WhitespaceTrivia (' ')
        WarningKeywordInDirectiveTrivia ('warning')
        WhitespaceTrivia (' ')
        DisableKeywordInDirectiveTrivia ('disable')
        WhitespaceTrivia (' ')
        IdentifierTokenInDirectiveTrivia ('CS0168')
        CommaTokenInDirectiveTrivia (',')
        WhitespaceTrivia (' ')
        IdentifierTokenInDirectiveTrivia ('x')
        EndOfLineTrivia ('\n')
        HashTokenInDirectiveTrivia ('#')
        RegionKeywordInDirectiveTrivia ('region')
        WhitespaceTrivia (' ')
        PreprocessingMessageTrivia ('a // b')
        EndOfLineTrivia ('\n')
        HashTokenInDirectiveTrivia ('#')
        ErrorKeywordInDirectiveTrivia ('error')
        WhitespaceTrivia ('  ')
        PreprocessingMessageTrivia ('e ')
        """,
    )

    fun testDirectiveTokensAreTriviaForTheParser() {
        val text = "#if A && (B || !C)\n#pragma warning disable CS1, x\n#line (1,1)-(2,2) 3 \"f\"\n#nullable enable warnings\n" +
            "#region r\n#error e\n#endregion\n#endif\n#:x\n#! y\n#q 'z' 1.5 \"\"\"r\"\"\"\nx; #if"
        for (t in lex(text, 0)) {
            if (t.type == SyntaxKind.IdentifierToken || t.type == SyntaxKind.SemicolonToken) continue // `x;`
            assertTrue("$t", CSharpTokenTypes.COMMENTS.contains(t.type) || CSharpTokenTypes.WHITESPACES.contains(t.type))
        }
    }

    fun testStateIsZeroOnlyWithAnEmptyContext() {
        val text = "#region R\na\n#endregion\n#if X\nb\n#else\nc\n#endif\nd\n#define Z\ne"
        val tokens = lex(text, 0)
        fun stateOf(name: String) =
            tokens.single { it.type == SyntaxKind.IdentifierToken && text.substring(it.start, it.end) == name }.state
        assertEquals("a plain #region does not count", 0, stateOf("a"))
        assertTrue("inside #else", stateOf("c") != 0)
        assertEquals(0, stateOf("d"))
        assertTrue("after #define", stateOf("e") != 0)
        assertRestartable(text)
    }

    /** Restarting at every state-0 token of the directive goldens with symbols gives the same tokens. */
    fun testRestartsWithSymbols() {
        val dir = java.nio.file.Paths.get(io.github.dotnetsupport.csharp.CSharpTestUtil.testDataPath("lexer"))
        for (name in listOf("Directives", "DirectivesEvaluation", "DirectivesForms", "DirectivesMisplaced", "DirectivesRegionQuirk")) {
            val text = com.intellij.openapi.util.text.StringUtil.convertLineSeparators(java.nio.file.Files.readString(dir.resolve("$name.cs")))
            symbols = setOf("DEBUG", "A", "X", "Q", "Y")
            try {
                assertRestartable(text)
            } finally {
                symbols = emptySet()
            }
        }
    }
}
