// Syntax errors for the built-in diagnostics (CSharpFeature.DIAGNOSTICS, «Errors and warnings», 0.1.54): Roslyn's syntax errors from the
// plugin's own tree, with Roslyn's codes, messages and places. Excluded from the compilation of Broken (Broken.csproj) so that its Debug
// scenario keeps its two errors; the editor and the language server still see the file.
// Compare the two sources: Settings | Tools | .NET | Language Server | Source of Features | «Errors and warnings» = Built-in / Language server.
// Built-in: every error below is shown once, with the code first in the text and the tooltip; the server still adds its semantic errors.
// The lines under "permanent" markers are broken as they are; under the others type on the empty line and undo with Ctrl+Z.
namespace DebugPlayground.Broken;

class SyntaxErrors
{
    void Typing()
    {
        // TYPE:diag-semicolon — type `int x = 1` (no `;`).
        // EXPECT: one red mark AFTER the end of that line (not on the next line), tooltip «CS1002: ; expected».
        // EXPECT: not two marks for the same error (the server's CS1002 gives way to the built-in one).

        // TYPE:diag-paren — type `Typing(1;`.
        // EXPECT: «CS1026: ) expected» on the `;`.

        // TYPE:diag-expression — type `int y = 1 + ;`.
        // EXPECT: «CS1525: Invalid expression term ';'» on the `;`.

        // TYPE:diag-brace — type `if (true) {` and wait.
        // EXPECT: «CS1513: } expected» at the end of the file (after the last `}`), nothing in between; Ctrl+Z removes it.

        // TYPE:diag-edit — in the permanent line under diag-literals, delete the second `'` of `''` and type it back.
        // EXPECT: the marks follow the edit at once (no stale mark at the old place).
    }

    // TYPE:diag-literals (permanent) — EXPECT, line by line: «CS1011: Empty character literal» at the first `'`;
    // «CS1009: Unrecognized escape sequence» on `\q`; «CS1021: Integral constant is too large» at the start of the number;
    // «CS0595: Invalid real literal.» at the start of `1e`.
    char empty = '';
    string escape = "a\qb";
    long big = 99999999999999999999;
    double real = 1e;

    // TYPE:diag-member (permanent) — EXPECT: «CS1519: Invalid token ';' in a member declaration» on the `;` of `int ;`,
    // «CS1001: Identifier expected» on the `)` of `Member(int)`.
    int ;
    void Member(int) { }

    void Misplaced()
    {
        // TYPE:diag-misplaced (permanent) — EXPECT: «CS1040: Preprocessor directives must appear as the first non-whitespace character
        // on a line» on the `#`, nothing else on that line.
        int a = 1; #if X
    }

    void Counted()
    {
        // TYPE:diag-server-keeps (permanent) — errors the built-in tree does not report itself stay the server's, also with Built-in:
        // EXPECT: «CS0230: Type and identifier are both required in a foreach statement» on `x` (from the server, after it loads);
        // EXPECT: «CS0029: Cannot implicitly convert type 'string' to 'int'» on "three" (semantic, the server's).
        foreach (x in new int[0]) { }
        int count = "three";
    }
}

// TYPE:diag-directives (permanent) — EXPECT: «CS1024: Preprocessor directive expected» on `foo`; a yellow «CS1030: #warning: 'Look here'»
// on `Look here`; a yellow «CS1634: Expected 'disable' or 'restore'» on `foo` of the pragma; NOTHING inside the `#if NEVER` block
// (the excluded text is not parsed: no error for `int broken = ;`).
#foo
#warning Look here
#pragma warning foo
#if NEVER
class Excluded { int broken = ; }
#endif

// TYPE:diag-end (permanent, keep last) — EXPECT: «CS1035: End-of-file found, '*/' expected» at the `/*` below, and nothing after it.
/* this comment never ends
