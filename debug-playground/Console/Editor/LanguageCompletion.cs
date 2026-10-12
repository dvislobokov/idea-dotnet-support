using System;
using System.Collections.Generic;

[assembly: System.Reflection.AssemblyMetadata("Playground", "LanguageCompletion")]

namespace Playground.Editor;

/// <summary>
/// Live check of the language places of the completion (0.1.94, COMPLETION_GAPS 3.2, 3.3, 3.5, 3.6): members of an interface implemented
/// explicitly, <c>[]</c> after the dot, names of tuple elements and of what a deconstruction takes apart, <c>partial class |</c>.
/// Settings | .NET | Language Server → «Completion» = Built-in (the default with the server off). Put the caret on the empty line under a
/// marker, type what it says, compare with EXPECT, undo with Ctrl+Z. The file compiles as it is.
/// </summary>
public interface ILcPriced
{
    decimal Price { get; }

    string Describe(int digits);

    void Reset();
}

public interface ILcAudited
{
    void Audit(string who);
}

public class LcItem : ILcPriced, ILcAudited, IDisposable
{
    public decimal Price => 1m;

    public string Describe(int digits) => Price.ToString("F" + digits);

    public void Reset() { }

    public void Audit(string who) { }

    public void Dispose() { }

    // TYPE:explicit-members — on the empty line below type `void ILcAudited.` and Ctrl+Space (the list also opens by itself after the dot).
    // EXPECT: `Audit` with its parameters and the tail `{ ... }` — and nothing else; Enter writes `void ILcAudited.Audit(string who)` with
    // the body `throw new NotImplementedException();`, the caret at the end of the `throw` line. NOT: `Price`, `Describe`, `Reset`
    // (members of another interface). Ctrl+Z. Then `int ILcPriced.` and Ctrl+Space: `Price`, `Describe`, `Reset`; choosing `Price`
    // replaces `int` with `decimal` and writes `get => throw new NotImplementedException();` in braces. Ctrl+Z.

    // TYPE:explicit-names — on the empty line below type `void ` and Ctrl+Space.
    // EXPECT: `ILcPriced`, `ILcAudited` and `IDisposable` among the items (tail «explicit implementation»); choosing one writes
    // `void ILcPriced.` and opens the list of its members at once. NOT: those names after `public void ` (an explicit implementation has
    // no access modifier). Ctrl+Z until the line is empty.

    // TYPE:explicit-alone — on the empty line below type `IDisposable.` and Ctrl+Space. EXPECT: `Dispose`; Enter writes
    // `void IDisposable.Dispose()` with a throwing body (`using System;` is there already). Ctrl+Z.
}

public class LcIndexers
{
    private readonly int[] _numbers = { 1, 2, 3 };
    private readonly List<string> _names = new() { "a", "b" };
    private readonly Dictionary<string, int> _counts = new();

    public int this[int index] => _numbers[index];

    public void Run(string text, LcIndexers self)
    {
        // TYPE:indexer-string — on the empty line below type `text.` and Ctrl+Space. EXPECT: an item `[]` with the tail `this[int index]`;
        // Enter turns `text.` into `text[|]` with the caret between the brackets (type `0`: `text[0]`). NOT: `[]` once a letter is
        // typed (`text.Le` has `Length`, no `[]`). Ctrl+Z.

        // TYPE:indexer-collections — `_numbers.`, `_names.`, `_counts.`, `self.`: `[]` in each list (`this[int index]`, `this[string key]`,
        // `this[int index]` of the type itself). `_names?.` gives `_names?[|]`. NOT: `[]` after `new object().` or `Console.`. Ctrl+Z.
    }
}

public class LcTuples
{
    public (string Title, int Count) Pair() => ("t", 1);

    public static (int Quantity, decimal Total) Totals() => (1, 2m);

    public void Run()
    {
        var pair = Pair();
        var named = (width: 3, height: 4);

        // TYPE:tuple-names — on the empty line below type `pair.` and Ctrl+Space; then `named.`. EXPECT: `Title` (string) and `Count` (int)
        // for the first, `width` and `height` (int) for the second, `Item1` and `Item2` among the members as before. Ctrl+Z.

        // TYPE:deconstruct-var — on the empty line below type `var () = pair;`, move the caret between the parentheses and Ctrl+Space.
        // EXPECT: `title`. Type `title, ` and Ctrl+Space again: `count`. The same with `Totals()` on the right: `quantity`, `total`.
        // Ctrl+Z.

        // TYPE:deconstruct-foreach — in the loop below put the caret after `a, ` of `foreach (var (a, b) in pairs)`, delete `b` and
        // Ctrl+Space. EXPECT: `count`. Ctrl+Z.
        var pairs = new List<(string Title, int Count)> { Pair() };
        foreach (var (a, b) in pairs)
        {
            Console.WriteLine(a + b);
        }

        // TYPE:deconstruct-record — type `var (x, ) = new LcPoint(1, 2);`, caret after `x, `, Ctrl+Space. EXPECT: `y` (the positional
        // parameter, in lower case). For `new LcMoney()` (its own `Deconstruct(out int units, out string currency)`): `units`, `currency`.
        // NOT: names for an `object`, or for a tuple without names (`(string, int)`). Ctrl+Z.
    }
}

public record LcPoint(int X, int Y);

public class LcMoney
{
    public void Deconstruct(out int units, out string currency)
    {
        units = 0;
        currency = "";
    }
}

// TYPE:partial-types — on the empty line below type `partial class ` and Ctrl+Space. EXPECT: `LcOrder`, `LcCart` and `LcBox<T>` (partial
// classes of Playground.Editor with a part in LanguageCompletionParts.cs; the tail shows the file); NOT: `LcMoney` (not partial),
// `LcTotals` (a struct), `LcOwn` (the only part is here), types of other namespaces. Choosing writes the name. `partial struct ` →
// `LcTotals`; `partial interface ` → `ILcShape`. Ctrl+Z.
public partial class LcOwn
{
}
