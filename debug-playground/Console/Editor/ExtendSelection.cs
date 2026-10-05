namespace Playground.Editor;

/// <summary>
/// Live check of Extend Selection (Ctrl+W, Shrink: Ctrl+Shift+W) on the plugin's own tree (0.1.48). Settings | Tools | .NET | Language Server →
/// Source of Features → «Typing assistance» = Built-in, then the markers below: put the caret where a <c>// TYPE:name</c> comment says and
/// press Ctrl+W again and again. With «Language server» (the default for now) the tokens answer: the differences are named in EXPECT
/// ("tokens: …"). Nothing is typed; the file only has to compile.
/// </summary>
public class ExtendSelection
{
    /// <summary>Saves the order.</summary>
    [Obsolete("only for the playground")]
    public int Save(int count, string name)
    {
        // TYPE:extend-selection-call — caret inside `name` of `name.Trim()` below, Ctrl+W 16 times. EXPECT: `name` → `name.Trim` →
        // `name.Trim()` → `count, name.Trim()` → `(count, name.Trim())` → `Compute(count, name.Trim())` → `Compute(…) + 1` → `= Compute(…) + 1`
        // → `total = …` → `var total = …` → `var total = …;` → the same with this comment above it → the contents of the body (from the first `// TYPE:` comment to `return total;`, no line
        // breaks around) → the body with its braces → the method from `[Obsolete…]` → the method with its doc comment. Tokens: `name` → `count, name.Trim()` →
        // `(count, name.Trim())` → the body without braces (with its line breaks) → the body; no `name.Trim()`, no statement, no method
        var total = Compute(count, name.Trim()) + 1; // tail
        if (total > 0)
        {
            // TYPE:extend-selection-string — caret inside `plain` below, Ctrl+W 3 times. EXPECT: `plain` → `plain text here` (no quotes) →
            // `"plain text here"`. Tokens: `plain` → `"plain text here"`. Then caret inside the first `total` of the interpolated string:
            // `total` → `total ` → `total {total:N2} of {name}` (no `$"`, `"`) → the whole string; tokens skip the third
            Log($"total {total:N2} of {name}", "plain text here");
        }

        // TYPE:extend-selection-condition — caret on `>` below, Ctrl+W 4 times. EXPECT: `>` → `total > 1` → `(total > 1)` → the whole `if`
        // statement with its block. Tokens: the same here (robot, 2026-10-04)
        if (total > 1)
        {
            total--;
        }

        return total;
    }

    private static int Compute(int count, string name) => count + name.Length;

    private static void Log(string message, string detail) => System.Console.WriteLine(message + detail);
}
