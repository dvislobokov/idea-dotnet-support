using System;
using System.Collections.Generic;
using System.Linq;

[assembly: System.Reflection.AssemblyMetadata("Playground", "CompletionBehaviour")]

namespace Playground.Editor;

/// <summary>
/// Live check of how the completion list behaves (0.1.91, COMPLETION_GAPS 2.8–2.14, 3.4, 3.10): statistics of choices, commit characters,
/// suggestion mode at names, the list opening by itself, Quick Doc of an item, type arguments, keywords of Roslyn's recommenders,
/// <c>nameof(</c> / <c>typeof(</c>, middle matching. Settings | .NET | Language Server → «Completion» = Built-in (the default with the server off).
/// Put the caret on the empty line under a marker, type what it says, compare with EXPECT, undo with Ctrl+Z. The file compiles as it is.
/// </summary>
public class CompletionBehaviour
{
    /// <summary>How many orders the basket holds.</summary>
    public int OrderCount { get; set; }

    /// <summary>The name printed on the receipt.</summary>
    public string CustomerName { get; set; } = "";

    private int _counter;
    private readonly List<int> _items = new();

    /// <summary>Writes the receipt line.</summary>
    public void WriteReceiptLine(string line) => Console.WriteLine(line);

    public int Status => _counter;

    public void Run(int[] numbers, object value)
    {
        int counter = _counter;

        // TYPE:behaviour-stats — type `_` then choose `_items` with Enter five times (undo each time), then type `_` again.
        // EXPECT: `_items` above `_counter` now (same kind, chosen before); locals still above fields, keywords still last.

        // TYPE:behaviour-commit — type `var copy = cou` and then `;`. EXPECT: `var copy = counter;` (the selected item is taken, `;` typed).
        // Also `Console.WriteLine(cou` + `)` → `Console.WriteLine(counter)`; `CustomerNa` + `.` → `CustomerName.` with the list of string.
        // EXPECT NOT: `var copy = unt` + `;` gives `unt;` (a middle match is not taken by a character).

        // TYPE:behaviour-suggestion — type `foreach (var num` and press space. EXPECT: the list shows no selection, the space is typed:
        // `foreach (var num ` (not replaced by `numbers`). Then `int.TryParse("1", out var res` + space: `res ` stays. Arrow Down selects an item.

        // TYPE:behaviour-lambda — type `var big = numbers.Where(`. EXPECT: the list opens by itself, without a selection; type `n` and
        // space: `Where(n ` stays (n is a lambda parameter, not `numbers`). Type `=> n > 0);` and nothing else is inserted.

        // TYPE:behaviour-autopopup — type `var list = new List<`. EXPECT: the list of types opens by itself (`int`, `string`, `CompletionBehaviour`).
        // `if (Status == ` → the list opens by itself; `switch (value) { case ` → opens by itself too.

        // TYPE:behaviour-quickdoc — type `this.Ord` and press Ctrl+Q while the list is open. EXPECT: the documentation of `OrderCount`:
        // `int CompletionBehaviour.OrderCount { get; set; }` and «How many orders the basket holds.». Arrow to another item: the popup follows.

        // TYPE:behaviour-generic — type `var map = new Dictionary<string, ` . EXPECT: types and predefined types (`int`, `string`, `CompletionBehaviour`),
        // no statements (`if`, `for`).

        // TYPE:behaviour-keywords — type each and Ctrl+Space: `var ok = value is int and > 0 ` → `and`, `or` only;
        // `var r = Status switch { 1 ` → `when`, `and`, `or`; `var q = value ` → `as`, `is`, `switch`, `with`.
        // EXPECT NOT: statement keywords (`if`, `foreach`) in those three lists.

        // TYPE:behaviour-nameof — type `var n = nameof(`. EXPECT: `counter`, `numbers`, `value`, members, types; no keywords (`int`, `out`).
        // `var t = typeof(` → types only; no `counter`, no `dynamic`.

        // TYPE:behaviour-middle — type `ReceiptLi`. EXPECT: `WriteReceiptLine` in the list (a middle match); `rec` → `WriteReceiptLine`
        // under the items that start with `rec`.

        _items.Add(counter);
    }

    // TYPE:behaviour-accessors — inside `{ }` of the property below type Ctrl+Space. EXPECT: `get`, `set`, `init`, `private`, `protected`, `internal`.
    public int Accessors { get; set; }

    // TYPE:behaviour-field — replace `get;` of `Backed` with `get => ` and Ctrl+Space. EXPECT: `field` among the items (C# 14).
    public int Backed { get; set; }
}

// TYPE:behaviour-extension — on the empty line inside the static class Ctrl+Space. EXPECT: `extension` among the modifiers (C# 14).
public static class CompletionBehaviourExtensions
{

}
