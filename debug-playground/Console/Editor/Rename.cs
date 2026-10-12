using System;
using System.Collections.Generic;
using System.Linq;

namespace Playground.Editor;

/// <summary>
/// Live check of Rename (Shift+F6) on the plugin's own tree (0.1.53). Settings | .NET | Language Server → Source of Features →
/// «Rename» = Built-in. Put the caret where a <c>// TYPE:name</c> comment says, press Shift+F6, type the new name, Enter; compare with
/// EXPECT, then Ctrl+Z (one Ctrl+Z must bring back the whole old text). With «Language server» (the default for now) the server renames
/// once «Roslyn: DebugPlayground.sln» is ready: the result must be the same. With Built-in the locals below are renamed without the server
/// (open the file before it is ready, or stop it, to see that); a member or a type still goes to the server, or gets a hint without it.
/// The file only has to compile.
/// </summary>
/// <param name="owner">Who orders.</param>
public class RenameScenarios(string owner)
{
    public decimal Limit => 100m;

    // TYPE:rename-local — caret on `subtotal` in `var subtotal = 0m`, Shift+F6, type `sum`, Enter. EXPECT: all three `subtotal` of the
    // method follow while typing (one frame around the name being typed), after Enter: `var sum = 0m;`, `sum += price;`,
    // `return sum * (1 - discount);`. Then on `price` → `cost`: the `foreach` and its body change, nothing else. Ctrl+Z: back, in one step
    // TYPE:rename-conflict — caret on `subtotal`, Shift+F6, type `prices`, Enter. EXPECT: the dialog «Problems Detected» says a parameter
    // named 'prices' is already declared; Cancel leaves `subtotal` everywhere, Continue renames anyway (then Ctrl+Z)
    // TYPE:rename-parameter — caret on `discount` in the parameter list, Shift+F6, `rate`, Enter. EXPECT: the parameter, `(1 - rate)` and
    // `<param name="rate">` of the doc comment; the `discount` of Limits below stays
    /// <summary>The sum of <paramref name="prices"/> with a discount.</summary>
    /// <param name="prices">The prices.</param>
    /// <param name="discount">The discount, 0..1.</param>
    public decimal Total(List<decimal> prices, decimal discount)
    {
        var subtotal = 0m;
        foreach (var price in prices) subtotal += price;
        return subtotal * (1 - discount);
    }

    public decimal Limits(decimal discount) => Limit * discount;

    // TYPE:rename-local-function — caret on `Scale` in `return Scale(2)`, Shift+F6, `Times`, Enter. EXPECT: both calls and the local
    // function below them (declared after its use) are `Times`; the named argument `factor:` stays. Then on `factor` in `int Scale(int
    // factor)` → `k`: the parameter, its use and the named argument `k: 3` change together
    public int Scaled()
    {
        return Scale(2) + Scale(factor: 3);

        int Scale(int factor) => factor * 10;
    }

    // TYPE:rename-label-query-lambda — Shift+F6 on: the name after `goto` → `retry` (the label `again:` too); `line` in `line => ...` →
    // `text` (both `line` of the lambda); `o` of `from o in` → `item` (the `where`, `group` and `by` follow; `g` after `into` stays)
    public int Labels(string[] lines, int[] numbers)
    {
        var attempts = 0;
    again:
        attempts++;
        if (attempts < 3) goto again;
        var trimmed = lines.Select(line => line.Trim()).Count();
        var query = from o in numbers where o > 1 group o by o % 2 into g select g.Key;
        return trimmed + query.Count();
    }

    // TYPE:rename-type-parameter-keyword — Shift+F6 on `TValue` in `First<TValue>` → `TItem`: the return type, `List<TItem>` and the
    // `<typeparam>` tag. Then on `kind` → `class`. EXPECT: `var @class = values.Count;` and `return @class > 0 ? ...` — the `@` is added
    /// <typeparam name="TValue">The item.</typeparam>
    public TValue First<TValue>(List<TValue> values)
    {
        var kind = values.Count;
        return kind > 0 ? values[0] : default!;
    }

    // TYPE:rename-primary-member — Shift+F6 on `owner` in `Describe` → `customer`: the primary constructor parameter at the top of the
    // class and `<param name="customer">` above it. Then Shift+F6 on `Limit` in `Limit * discount` (a property) → `Cap`. EXPECT (since
    // 0.1.73, with or without the server): the built-in rename renames the property, `Cap * discount` and `{Cap}` in Describe; members across
    // files — SolutionUsages.cs
    public string Describe() => $"{owner}: {Limit}";
}
