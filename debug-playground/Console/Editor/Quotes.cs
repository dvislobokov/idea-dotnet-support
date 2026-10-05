namespace Playground.Editor;

/// <summary>
/// Live check of the pairing of quotes (0.1.69): type what a <c>// TYPE:name</c> comment says on the empty line under it, compare with
/// EXPECT, undo with Ctrl+Z. The file only has to compile.
/// </summary>
public class Quotes
{
    public void Interpolated(int x)
    {
        // TYPE:quote-interpolated — type `Console.WriteLine($"` (the `)` comes by itself after `(`). EXPECT: `Console.WriteLine($"|")` with
        // the caret between the quotes; then type `{x}"` — the closing quote is stepped over: `Console.WriteLine($"{x}"|)`, not `""`.
        // Not expected: `Console.WriteLine($"|)` without the second quote (before 0.1.69)

    }

    public void Verbatim()
    {
        // TYPE:quote-verbatim — type `var path = @"`, then `$@"` on the next line. EXPECT: `@"|"` and `$@"|"`, the caret between the quotes

    }
}
