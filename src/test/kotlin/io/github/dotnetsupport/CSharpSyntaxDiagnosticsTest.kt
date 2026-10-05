package io.github.dotnetsupport

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.csharp.lang.diagnostics.CSharpSyntaxDiagnostics
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpDiagnostics
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * `DIAGNOSTICS` NATIVE, the syntactic part (CSHARP_PSI_MIGRATION.md, task A3): Roslyn's syntax errors from csharp-psi's tree. The cases are an
 * oracle: the expected `code start end` are what `roslyndump tree` prints for the same text (`D` lines, errors only; Roslyn 5.x of
 * tools/csharp-psi/roslyndump). Diagnostics the port only counts (`addError` on a built node) are left out, named at their case.
 */
class CSharpSyntaxDiagnosticsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private class Case(val name: String, val text: String, vararg val expected: String)

    private val cases = listOf(
        Case("badchar", "class C\n{\n    int x = 1 ` 2;\n}\n", "CS1056 24 24"),
        Case("call", "class C\n{\n    void M()\n    {\n        Console.WriteLine(\"a\"\n    }\n}\n", "CS1002 58 58", "CS1026 58 58"),
        Case("chars", "class C\n{\n    char a = '';\n    char b = 'ab';\n}\n", "CS1011 23 23", "CS1012 40 40"),
        Case("comment", "class C\n{\n    /* unterminated\n}\n", "CS1513 9 9", "CS1035 14 14"),
        Case("directive", "#if\nclass C { }\n#endif\n#region\n#foo\n#endregion\n#endif\n", "CS1517 3 3", "CS1024 32 35", "CS1028 47 53"),
        Case("directive2", "#if DEBUG\nclass C { }\n", "CS1027 22 22"),
        Case("directive3", "class C { }\n#define X\n#error Stop here\n#warning   Look // twice\n#region R\n#endif\n#pragma warning disable CS0168 junk\n#pragma warning foo\n#pragma bogus\n#nullable maybe\n#if (A\n#endif extra\n#elif B\n#else\n", "CS1032 13 19", "CS1029 29 38", "CS1038 74 80", "CS8637 161 166", "CS1026 173 173", "CS1025 181 186", "CS1038 187 194", "CS1038 195 200", "CS1038 201 201"),
        Case("dot", "class C\n{\n    void M()\n    {\n        x.\n    }\n}\n", "CS1001 39 39", "CS1002 39 39"),
        Case("eof", "class C { }\n}\n", "CS1022 12 13"),
        Case("escape", "class C\n{\n    string s = \"a\\qb\";\n}\n", "CS1009 27 29"),
        Case("exprterm", "class C\n{\n    void M()\n    {\n        int x = 1 + ;\n    }\n}\n", "CS1525 49 50"),
        Case("ident", "class C\n{\n    int ;\n    void M(int) { }\n}\n", "CS1519 18 19", "CS1001 34 35"),
        Case("identkw", "class C\n{\n    int class;\n}\n", "CS1001 23 24"),  // Roslyn also: CS1519 18 23 (counted only)
        Case("ifparen", "class C\n{\n    void M()\n    {\n        if (x\n        {\n        }\n    }\n}\n", "CS1026 42 42"),
        Case("interp", "class C\n{\n    string s = \$\"a{1 +}b\";\n}\n", "CS1733 32 32"),
        Case("lbrace", "class C\n    void M() { }\n}\n", "CS1513 7 7", "CS1514 7 7", "CS1022 25 26"),  // Roslyn also: CS8803 12 24 (counted only)
        Case("lexer2", "class C\n{\n    int a = 1; #if X\n    string b = \"\"\"abc\"\"\"\"\n    ;\n    long c = 1__;\n    float d = 1e99999f;\n    char e = '\\x';\n    string f = @@\"x\";\n    int @@g;\n}\n#endregion\n", "CS1040 25 26", "CS8998 55 56", "CS1013 76 79", "CS0594 95 95", "CS1009 119 121", "CS9008 139 141", "CS9008 154 156", "CS1028 161 171"),
        Case("membertok", "class C\n{\n    int x;\n    +\n}\n", "CS1519 25 26"),
        Case("misc", "class C\n{\n    void M()\n    {\n        foreach (x in y) { }\n        yield;\n        switch x { }\n    }\n}\n", ),  // Roslyn also: CS0230 48 50, CS8515 88 89 (counted only)
        Case("misc2", "class C\n{\n    void M()\n    {\n        for (int i = 0; i < 1; i++;) { }\n        int[] a = new int[1,];\n        a..b;\n        x = ;\n    }\n}\n", "CS1003 63 64", "CS1525 64 65", "CS0443 98 98", "CS1525 127 128"),
        Case("nsmember", "namespace N\n{\n    int x;\n    void M() { }\n}\n", ),
        Case("number", "class C\n{\n    int a = 0x;\n    int b = 99999999999999999999;\n    double c = 1e;\n}\n", "CS1013 22 22", "CS1021 38 38", "CS0595 75 75"),
        Case("playground", "// Syntax errors for the built-in diagnostics (CSharpFeature.DIAGNOSTICS, \u00abErrors and warnings\u00bb, 0.1.54): Roslyn's syntax errors from the\n// plugin's own tree, with Roslyn's codes, messages and places. Excluded from the compilation of Broken (Broken.csproj) so that its Debug\n// scenario keeps its two errors; the editor and the language server still see the file.\n// Compare the two sources: Settings | Tools | .NET | Language Server | Source of Features | \u00abErrors and warnings\u00bb = Built-in / Language server.\n// Built-in: every error below is shown once, with the code first in the text and the tooltip; the server still adds its semantic errors.\n// The lines under \"permanent\" markers are broken as they are; under the others type on the empty line and undo with Ctrl+Z.\nnamespace DebugPlayground.Broken;\n\nclass SyntaxErrors\n{\n    void Typing()\n    {\n        // TYPE:diag-semicolon \u2014 type `int x = 1` (no `;`).\n        // EXPECT: one red mark AFTER the end of that line (not on the next line), tooltip \u00abCS1002: ; expected\u00bb.\n        // EXPECT: not two marks for the same error (the server's CS1002 gives way to the built-in one).\n\n        // TYPE:diag-paren \u2014 type `Typing(1;`.\n        // EXPECT: \u00abCS1026: ) expected\u00bb on the `;`.\n\n        // TYPE:diag-expression \u2014 type `int y = 1 + ;`.\n        // EXPECT: \u00abCS1525: Invalid expression term ';'\u00bb on the `;`.\n\n        // TYPE:diag-brace \u2014 type `if (true) {` and wait.\n        // EXPECT: \u00abCS1513: } expected\u00bb at the end of the file (after the last `}`), nothing in between; Ctrl+Z removes it.\n\n        // TYPE:diag-edit \u2014 in the permanent line under diag-literals, delete the second `'` of `''` and type it back.\n        // EXPECT: the marks follow the edit at once (no stale mark at the old place).\n    }\n\n    // TYPE:diag-literals (permanent) \u2014 EXPECT, line by line: \u00abCS1011: Empty character literal\u00bb at the first `'`;\n    // \u00abCS1009: Unrecognized escape sequence\u00bb on `\\q`; \u00abCS1021: Integral constant is too large\u00bb at the start of the number;\n    // \u00abCS0595: Invalid real literal.\u00bb at the start of `1e`.\n    char empty = '';\n    string escape = \"a\\qb\";\n    long big = 99999999999999999999;\n    double real = 1e;\n\n    // TYPE:diag-member (permanent) \u2014 EXPECT: \u00abCS1519: Invalid token ';' in a member declaration\u00bb on the `;` of `int ;`,\n    // \u00abCS1001: Identifier expected\u00bb on the `)` of `Member(int)`.\n    int ;\n    void Member(int) { }\n\n    void Misplaced()\n    {\n        // TYPE:diag-misplaced (permanent) \u2014 EXPECT: \u00abCS1040: Preprocessor directives must appear as the first non-whitespace character\n        // on a line\u00bb on the `#`, nothing else on that line.\n        int a = 1; #if X\n    }\n\n    void Counted()\n    {\n        // TYPE:diag-server-keeps (permanent) \u2014 errors the built-in tree does not report itself stay the server's, also with Built-in:\n        // EXPECT: \u00abCS0230: Type and identifier are both required in a foreach statement\u00bb on `x` (from the server, after it loads);\n        // EXPECT: \u00abCS0029: Cannot implicitly convert type 'string' to 'int'\u00bb on \"three\" (semantic, the server's).\n        foreach (x in new int[0]) { }\n        int count = \"three\";\n    }\n}\n\n// TYPE:diag-directives (permanent) \u2014 EXPECT: \u00abCS1024: Preprocessor directive expected\u00bb on `foo`; a yellow \u00abCS1030: #warning: 'Look here'\u00bb\n// on `Look here`; a yellow \u00abCS1634: Expected 'disable' or 'restore'\u00bb on `foo` of the pragma; NOTHING inside the `#if NEVER` block\n// (the excluded text is not parsed: no error for `int broken = ;`).\n#foo\n#warning Look here\n#pragma warning foo\n#if NEVER\nclass Excluded { int broken = ; }\n#endif\n\n// TYPE:diag-end (permanent, keep last) \u2014 EXPECT: \u00abCS1035: End-of-file found, '*/' expected\u00bb at the `/*` below, and nothing after it.\n/* this comment never ends\n", "CS1011 2070 2070", "CS1009 2096 2098", "CS1021 2117 2117", "CS0595 2157 2157", "CS1519 2357 2358", "CS1001 2378 2379", "CS1040 2628 2629", "CS1024 3464 3467", "CS1035 3693 3693"),  // Roslyn also: CS0230 3067 3069 (counted only)
        Case("raw", "class C\n{\n    string s = \"\"\"abc\n}\n", "CS1002 31 31", "CS8997 31 31"),
        Case("rbrace", "class C\n{\n    void M()\n    {\n        if (true)\n        {\n    }\n", "CS1513 62 62"),
        Case("rparen", "class C\n{\n    void M()\n    {\n        M(1;\n    }\n}\n", "CS1026 40 41"),
        Case("semi", "class C\n{\n    void M()\n    {\n        int x = 1\n        x++;\n    }\n}\n", "CS1002 46 46"),
        Case("semi_inline", "class C { void M() { int x = 1 x++; } }\n", "CS1002 31 32"),
        Case("string", "class C\n{\n    string s = \"abc\n    ;\n}\n", "CS1010 25 25"),
        Case("syntax", "class C\n{\n    void M()\n    {\n        var a = new int[] { 1, 2 ;\n    }\n}\n", "CS1513 62 63"),
        Case("top", "int x = 1\nConsole.WriteLine(x);\n", "CS1003 9 9"),
        Case("usingafter", "class C { }\nusing System;\n", "CS1529 12 25"),
        Case("verbatim", "class C\n{\n    string s = @\"abc\n", "CS1039 25 25", "CS1002 31 31", "CS1513 31 31"),
    )

    private fun diagnostics(name: String, text: String): List<String> {
        val file = myFixture.addFileToProject("syntaxDiagnostics/$name.cs", text) as CSharpFile
        return CSharpSyntaxDiagnostics.of(file.node).filter { !it.isWarning }.map { "${it.id} ${it.start} ${it.end}" }.distinct()
    }

    /** Every case gives Roslyn's errors: codes and spans (the order of the text). */
    fun testRoslynOracle() {
        val failures = cases.mapNotNull { case ->
            val actual = diagnostics(case.name, case.text).sortedWith(compareBy({ it.split(' ')[1].toInt() }, { it.split(' ')[2].toInt() }, { it }))
            val expected = case.expected.sortedWith(compareBy({ it.split(' ')[1].toInt() }, { it.split(' ')[2].toInt() }, { it }))
            if (actual == expected) null else "${case.name}: expected $expected, got $actual"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    /** The messages are Roslyn's, with their arguments. */
    fun testMessages() {
        val file = myFixture.addFileToProject("syntaxDiagnostics/Messages.cs", "class C\n{\n    int x = 1 + ;\n    char c = 'ab';\n}\n#foo\n") as CSharpFile
        assertEquals(
            listOf("CS1525: Invalid expression term ';'", "CS1012: Too many characters in character literal", "CS1024: Preprocessor directive expected"),
            CSharpSyntaxDiagnostics.of(file.node).map { it.text },
        )
    }

    /**
     * Warnings, which `roslyndump` does not print: `#warning` with its text (from the first non-whitespace after the keyword), the
     * `#pragma` ones on the token they are about, and none from a directive in excluded text (`isActive` false).
     */
    fun testDirectiveWarnings() {
        val text = "class C { }\n#warning   Look // twice\n#pragma warning disable CS0168 junk\n#pragma warning foo\n#pragma bogus\n#if NEVER\n#warning hidden\n#pragma bogus\n#endif\n"
        val file = myFixture.addFileToProject("syntaxDiagnostics/Warnings.cs", text) as CSharpFile
        val warnings = CSharpSyntaxDiagnostics.of(file.node).filter { it.isWarning }.map { "${it.id} ${text.substring(it.start, it.end)} ${it.message}" }
        assertEquals(
            listOf(
                "CS1030 Look // twice #warning: 'Look // twice'",
                "CS1696 junk Single-line comment or end-of-line expected",
                "CS1634 foo Expected 'disable' or 'restore'",
                "CS1633 bogus Unrecognized #pragma directive",
            ),
            warnings,
        )
    }

    /** `#if` is evaluated with the file's symbols: an error in the branch that is not taken is not reported, the directive's own are. */
    fun testExcludedTextHasNoErrors() {
        val text = "#if NEVER\nclass C { int x = 1 }\n#error never\n#endif\nclass D { }\n"
        val file = myFixture.addFileToProject("syntaxDiagnostics/Excluded.cs", text) as CSharpFile
        assertEquals(emptyList<String>(), CSharpSyntaxDiagnostics.of(file.node).map { it.text })
    }

    /** NATIVE: the annotator shows Roslyn's text at Roslyn's place (a zero-width one after the end of its line); the error elements stay hidden. */
    fun testAnnotatorWithNative() {
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
        myFixture.configureByText("DiagNative.cs", "class DiagNative\n{\n    void M()\n    {\n        int x = 1\n        x++;\n    }\n}\n")
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertEquals(listOf("CS1002: ; expected"), errors.map { it.description })
        val error = errors.single()
        assertEquals("zero-width after `1`", 55, error.startOffset)
        assertEquals(55, error.endOffset)
        assertTrue("shown after the end of the line", error.isAfterEndOfLine)
        assertEquals("CS1002: ; expected", error.toolTip?.let { com.intellij.openapi.util.text.StringUtil.removeHtmlTags(it) })
    }

    /** ROSLYN (the default): the server reports, the tree shows nothing; the switch takes effect on the next pass. */
    fun testAnnotatorWithRoslyn() {
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.ROSLYN)
        myFixture.configureByText("DiagRoslyn.cs", "class DiagRoslyn { void M() { int x = 1 x++; } }\n")
        assertEquals(emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
        DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file)
        assertEquals(listOf("CS1002: ; expected"), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    /** With NATIVE the server's syntax errors that the tree reports give way; its semantic ones, and syntax ones the tree misses, do not. */
    fun testServerGivesWayOnlyToWhatTheTreeShows() {
        val file = myFixture.configureByText("DiagServer.cs", "class DiagServer\n{\n    void M()\n    {\n        int x = 1\n        x++;\n    }\n}\n")
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.ROSLYN)
        val line4 = file.text.indexOf("int x = 1")
        val line5 = file.text.indexOf("x++")
        fun repeats(message: String?, offset: Int) = NativeCSharpDiagnostics.repeatsNative(file, message, offset)
        assertFalse("ROSLYN: the server's", repeats("; expected", line4))
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
        assertTrue(repeats("; expected", line4))
        assertFalse("another line", repeats("; expected", line5))
        assertFalse("semantic", repeats("The name 'y' does not exist in the current context", line4))
        assertFalse("a syntax error the tree does not report here", repeats(") expected", line4))
        assertFalse(repeats(null, line4))
    }

    /** A zero-width diagnostic inside a line takes the character after it, as the platform shows an empty error element. */
    fun testZeroWidthRanges() {
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
        myFixture.configureByText("DiagRange.cs", "class DiagRange\n{\n    char a = '';\n}\n")
        val error = myFixture.doHighlighting(HighlightSeverity.ERROR).single()
        assertEquals("CS1011: Empty character literal", error.description)
        assertEquals(31, error.startOffset)
        assertEquals("the opening quote", 32, error.endOffset)
        assertFalse(error.isAfterEndOfLine)
    }
}
