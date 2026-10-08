// docs: -; codes: - (debug-playground/Broken/RiderComparison.cs, the live comparison of 2026-10-08)
// Side-by-side comparison with JetBrains Rider (0.1.141): open this file in Rider and in the plugin and compare, case by case, the
// diagnostics each one shows — codes, the token each mark sits on, and which names are painted red (unresolved). The rule under test in
// the plugin: a syntax error silences the semantic checks ONLY inside its own statement; every other statement of the member keeps its
// semantic errors (CS0103 / CS1061 / CS0246 / CS1503 / CS0029 ...), and an unresolved name is painted red, as Rider paints it.
//
// Every `// CASE:<name> — EXPECT (Roslyn/Rider): ...` comment lists what Roslyn reports for the code under it. Roslyn places a MISSING
// token (`)`, `;`, a missing operand) at the END of the previous line, width 0, when that line ends with a newline; when the unexpected
// token is on the same line the mark sits on that token. "verify in Rider" marks the details that were not checked against a real compiler.
//
// Unlike SyntaxErrors.cs / SemanticErrors.cs this file is NOT excluded from the compilation of Broken: Rider analyses excluded files only
// partially (no resolve), and the comparison needs full analysis. So `dotnet build Broken` reports these errors too; the Debug scenario of
// Broken (two errors from Program.cs) is not reliable while this file is in the project.
//
// The last case (CASE:unterminated-comment) opens a `/*` that never closes: it MUST stay the last thing in the file.
using Nope.Missing;
using System.Nothing;

namespace DebugPlayground.Broken;

// CASE:unknown-using — EXPECT (Roslyn/Rider): the two directives above. «CS0246: The type or namespace name 'Nope' could not be found (are
// you missing a using directive or an assembly reference?)» on `Nope` (the leftmost part of an unknown dotted name); «CS0234: The type or
// namespace name 'Nothing' does not exist in the namespace 'System' (are you missing an assembly reference?)» on `Nothing`. Both names red.
// Neither directive is gray (CS8019 is not reported for a directive that failed to resolve). The methods below keep all their own errors.

public class RcStore
{
    public List<int> Items { get; } = new();
}

// CASE:primary-ctor-this — EXPECT (Roslyn/Rider): «CS1061: 'RcHandler' does not contain a definition for 'db' and no accessible extension
// method 'db' accepting a first argument of type 'RcHandler' could be found (are you missing a using directive or an assembly reference?)»
// on `db` after `this.` (a primary-constructor parameter is not a member); `db` red. The second line is clean: the parameter is captured.
public class RcHandler(RcStore db)
{
    public void Save(int id)
    {
        this.db.Items.Add(id);
        db.Items.Add(id);
    }
}

public class RiderComparison
{
    private readonly List<int> items = new();

    private void Log(string message, int id) { }

    // CASE:unterminated-string — the live case. EXPECT (Roslyn/Rider): line 1 — «CS1010: Newline in constant» on the string literal that
    // starts at the `"` after `id` and runs to the end of the line (verify the exact span in Rider); «CS1003: Syntax error, ',' expected»
    // at that same `"` (the parser recovers the tail string as a third argument); «CS1026: ) expected» and «CS1002: ; expected» at the END
    // of line 1 (width 0). Roslyn still binds the recovered call `Log(…, id, "…")`, so expect also «CS1501: No overload for method 'Log'
    // takes 3 arguments» on `Log` — verify in Rider, the plugin silences semantics in that statement on purpose. Line 2 — «CS1061:
    // 'RiderComparison' does not contain a definition for 'nope' and no accessible extension method 'nope' accepting a first argument of
    // type 'RiderComparison' could be found (…)» on `nope`; `nope` red. Nothing else on line 2.
    public void UnterminatedString(int id)
    {
        Log("Customer {CustomerId} not found", id");
        this.nope.Add(id);
    }

    // CASE:missing-paren — EXPECT (Roslyn/Rider): «CS1026: ) expected» on the `;` of line 1 (the unexpected token is on the same line, so
    // the mark is on it, width 1); no semantic error on line 1 (`Log("x", id)` binds fine). Line 2: «CS0103: The name 'missingName' does
    // not exist in the current context» on `missingName`; `missingName` red.
    public void MissingParen(int id)
    {
        Log("x", id;
        Console.WriteLine(missingName);
    }

    // CASE:missing-semicolon — EXPECT (Roslyn/Rider): «CS1002: ; expected» at the END of line 1 (after `1`, width 0 — not on `var` of
    // line 2). Line 2 binds as usual: «CS0103 … 'unknownValue'» on `unknownValue`, red. `a` resolves (its declaration is complete).
    public void MissingSemicolon()
    {
        var a = 1
        var b = a + unknownValue;
    }

    // CASE:await-non-awaitable — EXPECT (Roslyn/Rider): «CS1061: 'int' does not contain a definition for 'GetAwaiter' and no accessible
    // extension method 'GetAwaiter' accepting a first argument of type 'int' could be found (…)» — the mark covers the whole `await id`
    // expression (verify the span in Rider: whole expression vs `id` alone); nothing red (`id` resolves). Line 2: «CS0103 …
    // 'unknownNumber'» on `unknownNumber`, red; the `await Task.FromResult(id)` part is clean.
    public async Task AwaitNonAwaitable(int id)
    {
        await id;
        var n = await Task.FromResult(id) + unknownNumber;
    }

    // CASE:await-outside-async — EXPECT (Roslyn/Rider): «CS4033: The 'await' operator can only be used within an async method. Consider
    // marking this method with the 'async' modifier and changing its return type to 'Task'.» on `await` (verify the span: the keyword
    // or the whole `await Task.Delay(1)`); nothing red on that line. Line 2: CS1061 on `nope` (message as in unterminated-string), red.
    public void AwaitOutsideAsync()
    {
        await Task.Delay(1);
        this.nope.Add(1);
    }

    // CASE:unknown-type-and-method — EXPECT (Roslyn/Rider): «CS0246: The type or namespace name 'Widget' could not be found (are you
    // missing a using directive or an assembly reference?)» TWICE — on the declared type `Widget` and on the `Widget` after `new` (both
    // are type contexts, hence CS0246 and not CS0103); «CS0103: The name 'Render' does not exist in the current context» on `Render`.
    // Red: both `Widget`, `Render`. No error on `w` (its type is the error type, cascading errors are suppressed).
    public void UnknownTypeAndMethod()
    {
        Widget w = new Widget();
        Render(w);
    }

    // CASE:lambda-semantic — EXPECT (Roslyn/Rider): «CS1061: 'int' does not contain a definition for 'Nope' …» on `Nope` inside the
    // lambda; `Nope` red. No CS0411 / no error on `Select` or on `names` (the error type is inferred silently — verify in Rider).
    // Line 2 is clean. Line 3: «CS0103 … 'missingAfterLambda'» on `missingAfterLambda`, red.
    public void LambdaSemantic()
    {
        var names = items.Select(x => x.Nope);
        Console.WriteLine(names.Count());
        Console.WriteLine(missingAfterLambda);
    }

    // CASE:lambda-syntax — EXPECT (Roslyn/Rider): «CS1525: Invalid expression term ')'» on the `)` after `x >` (same line, so on the
    // token). No semantic error on line 1 (the missing operand binds silently). Line 2: «CS0103 … 'missingAfterBrokenLambda'» on
    // `missingAfterBrokenLambda`, red.
    public void LambdaSyntax()
    {
        var big = items.Where(x => x > );
        Console.WriteLine(missingAfterBrokenLambda);
    }

    // CASE:unterminated-char — EXPECT (Roslyn/Rider): «CS1012: Too many characters in character literal» on `'a` (Roslyn's lexer takes
    // `'a` as the whole literal when no closing `'` follows the character; verify in Rider that it is CS1012 and not CS1010 «Newline in
    // constant»). The `;` is then parsed normally, so NO `;`-expected error and no semantic error on line 1 (`char c = 'a'` is fine).
    // Line 2: CS1061 on `nope`, red.
    public void UnterminatedChar(int id)
    {
        char c = 'a;
        this.nope.Add(id);
    }

    // CASE:broken-if-header — EXPECT (Roslyn/Rider): «CS1525: Invalid expression term '{'» and «CS1026: ) expected», BOTH at the END of
    // the `if (id >` line (width 0: the `{` sits on the next line). The block is parsed as the body of the `if`, and Roslyn binds that
    // body: «CS1061 … 'nope'» on `nope` INSIDE the body, `nope` red. The plugin treats the whole `if` as ONE statement and silences its
    // semantics, so here Rider shows CS1061 on `nope` and the plugin does not — this difference is expected, note it when comparing.
    // After the `if`: «CS0103 … 'afterIf'» on `afterIf`, red, in both.
    public void BrokenIfHeader(int id)
    {
        if (id >
        {
            this.nope.Add(id);
        }
        Console.WriteLine(afterIf);
    }

    // CASE:broken-signature — EXPECT (Roslyn/Rider): «CS1001: Identifier expected» and «CS1026: ) expected», both on the `{` of the
    // signature line (same line as `int`, so on the token — verify in Rider whether it shows one or both). The body is still parsed and
    // bound: «CS1061 … 'nope'» on `nope`, red. The body deliberately uses `1`, not the nameless parameter, so there is no CS0103 on `id`.
    public void BrokenSignature(int {
        this.nope.Add(1);
    }

    // CASE:broken-expression-body — EXPECT (Roslyn/Rider): «CS1525: Invalid expression term ';'» on the `;` of the first member (same
    // line); no semantic error there (`id * <missing>` binds silently), no CS0161. The next member is bound as usual: «CS0103 …
    // 'unknownTerm'» on `unknownTerm`, red.
    public int BrokenExpressionBody(int id) => id * ;
    public int AfterBrokenExpressionBody(int id) => id + unknownTerm;

    // CASE:nested-foreach — EXPECT (Roslyn/Rider): line 1 «CS0103 … 'beforeLoop'» on `beforeLoop`, red. Inside the loop: `Log("x", item;`
    // gets «CS1026: ) expected» on its `;` and nothing semantic; the next line keeps «CS1061 … 'nope'» on `nope`, red (the syntax error
    // belongs to the `Log` statement, not to the `foreach` — in the plugin too: only the `Log` line is silenced, the `foreach` as a whole
    // is not). After the loop: «CS0103 … 'afterLoop'» on `afterLoop`, red.
    public void NestedForeach()
    {
        Console.WriteLine(beforeLoop);
        foreach (var item in items)
        {
            Console.WriteLine(item);
            Log("x", item;
            this.nope.Add(item);
        }
        Console.WriteLine(afterLoop);
    }

    // CASE:two-statements-one-line — EXPECT (Roslyn/Rider): «CS1026: ) expected» on the first `;` of the line; «CS1061 … 'nope'» on
    // `nope` in the SECOND statement of the same line, `nope` red. The plugin silences by statement, not by line — same result expected.
    public void TwoStatementsOneLine(int id)
    {
        Log("x", id; this.nope.Add(id);
    }

    // CASE:wrong-arg-type-after-syntax — EXPECT (Roslyn/Rider): line 1 «CS1026: ) expected» on its `;`, nothing semantic. Line 2:
    // «CS1503: Argument 1: cannot convert from 'int' to 'string'» on `id` (the first argument of `Log(id, 1)`); NOTHING red — every name
    // on the line resolves, CS1503 is a conversion error, not an unresolved name.
    public void WrongArgTypeAfterSyntax(int id)
    {
        Log("x", id;
        Log(id, 1);
    }

    // CASE:wrong-return-type-after-string — EXPECT (Roslyn/Rider): line 1 exactly as in CASE:unterminated-string (CS1010, CS1003, CS1026,
    // CS1002, plus Roslyn's CS1501 on `Log` — verify in Rider). Line 2: «CS0029: Cannot implicitly convert type 'string' to 'int'» on
    // `"many"`; nothing red. No CS0161 on `WrongReturnTypeAfterString` (the method does return).
    public int WrongReturnTypeAfterString(int id)
    {
        Log("Count for {Id}", id");
        return "many";
    }

    // CASE:unterminated-comment — EXPECT (Roslyn/Rider): line 1 «CS1061 … 'nope'» on `nope`, red (the body is bound although its `}` is
    // gone). «CS1035: End-of-file found, '*/' expected» on the `/*` (verify the span in Rider: the two characters or the whole rest of the
    // file). The comment swallows the closing braces of the method and of the class: «CS1513: } expected» at the end of the file (verify
    // in Rider whether it is shown once or twice — one per missing brace). The method body before the comment keeps its errors.
    // This case MUST stay the last thing in the file.
    public void UnterminatedComment(int id)
    {
        this.nope.Add(id);
        /* this comment never ends: everything below is swallowed, including the `}` of the method and of the class
    }
}
