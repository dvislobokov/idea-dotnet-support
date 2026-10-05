using System;
using System.Collections.Generic;
using System.Linq;
using Playground.Lib;

namespace Playground.Editor;

/// <summary>
/// Live check of Go to Declaration (Ctrl+B, Ctrl+click), Ctrl+hover and the highlighting of usages under the caret on the plugin's own
/// tree (0.1.50). Settings | .NET | Language Server → Source of Features → «Navigation and usages» = Built-in. Nothing is typed:
/// put the caret (or the mouse with Ctrl held) where a <c>// TYPE:name</c> comment says and compare with EXPECT. With «Language server»
/// (the default for now) the server answers once «Roslyn: DebugPlayground.sln» is ready: the targets must be the same. With Built-in the
/// targets of the markers below come without the server (stop it or open the file before it is ready to see that); what the tree cannot
/// resolve (a member after a dot, a type of the framework) still goes to the server. The file only has to compile.
/// </summary>
public partial class Navigation(int seed)
{
    private int _count;

    public int Total { get; set; }

    // TYPE:nav-locals — Ctrl+click each name in the `return` line of Locals below. EXPECT: `total` → `var total`; `parsed` → `out var parsed`;
    // `text` → `is string text`; `item` (in the foreach body) → `foreach (var item`; `index` (in the for) → `for (int index`; `first` →
    // `var (first, second)`; `value` / `input` → the parameters of Locals. Every jump goes straight to the name, no list. Ctrl+hover over
    // any of them: underlined, the hint names the declaration; over a declaration itself (`var total`) nothing is underlined
    public int Locals(object value, string input, List<int> items)
    {
        var total = 0;
        if (int.TryParse(input, out var parsed)) total += parsed;
        if (value is string text && text.Length > 0) total += text.Length;
        foreach (var item in items) total += item;
        for (int index = 0; index < 2; index++) total += index;
        var (first, second) = (1, 2);
        return total + parsed + first + second + (value is null ? 0 : 1) + input.Length;
    }

    // TYPE:nav-lambdas — Ctrl+click: `x` in `x * 2` → the `x` of `x => x * 2` (not the local `x` above it); `p` → `(int p, int q)`;
    // `Twice` in `Twice(3)` → the local function below it (declared after the call); `n` inside Twice → its parameter `int n`; `seed` →
    // the primary constructor parameter `Navigation(int seed)` at the top of the class
    public int Lambdas()
    {
        var x = 10;
        Func<int, int> doubled = x => x * 2;
        Func<int, int, int> sum = (int p, int q) => p + q;
        var result = doubled(x) + sum(1, 2) + Twice(3) + seed;
        return result;

        int Twice(int n) => n * 2;
    }

    // TYPE:nav-labels-queries — Ctrl+click: the name after `goto` → the label `retry:`; in the query the `o` that is doubled → `from o in`,
    // `doubled` of the `where` → `let doubled`, `g` of the last `select` → `into g`; `T` in `new List<T>` → the `T` of `Pick<T>`
    public List<T> Pick<T>(IEnumerable<T> source, int[] numbers)
    {
        var attempts = 0;
        retry:
        attempts++;
        if (attempts < 3) goto retry;
        var query = from o in numbers
                    let doubled = o * 2
                    where doubled > 2
                    group o by o % 2 into g
                    select g.Key;
        return query.Any() ? source.ToList() : new List<T>();
    }

    // TYPE:nav-members — Ctrl+click: `_count` → the field; `Total` → the property; `this._count` (after `this.`) → the field too;
    // `Add` called with 1 → a list of the two overloads `Add(int)` and `Add(string)` (no overload resolution: both are offered);
    // `Reset` → `Reset()` in the second `partial class Navigation` part at the bottom of this file; `Entry` → the nested class `Entry`
    public void Members()
    {
        _count = Total;
        this._count++;
        Add(1);
        Reset();
        Entry entry = new Entry();
        entry.Touch();
    }

    public void Add(int amount) => _count += amount;

    public void Add(string amount) => _count += amount.Length;

    // TYPE:nav-types — Ctrl+click: `UsageSample` → the class in FindUsages.cs (same namespace); `UsageLog` → the class in Lib/UsageLog.cs
    // (namespace Playground.Lib, brought in by `using Playground.Lib;` at the top). Then the fallback: `Record` (after the dot) and `Count`
    // of `items.Count` are not resolved by the tree — with the server ready they go where the server says (the method Record of UsageLog, the decompiled
    // List<T>.Count), without it nothing happens (no wrong jump)
    public int Types(List<int> items)
    {
        UsageSample sample = new UsageSample();
        UsageLog.Record("navigation");
        return sample.Read() + items.Count;
    }

    // TYPE:nav-highlight — put the caret on `total` in `total += step` (no click). EXPECT: every `total` of Highlight is highlighted, the
    // declaration and the writes (`+=`, `++`, `out total`) in the write color, the reads in the read color; the `total` of Locals above is
    // not. Caret on `step`: its declaration and the read in `+= step` only. Caret on `_count` (a field, which the tree leaves to the server):
    // the occurrences as before — from the server when it is ready, else every `_count` of the file by text
    public int Highlight(int step)
    {
        var total = 0;
        total += step;
        total++;
        Out(out total);
        return total;
    }

    private static void Out(out int value) => value = 1;

    private sealed class Entry
    {
        public void Touch() { }
    }
}

public partial class Navigation
{
    public void Reset() => _count = 0;
}
