using System.Text;

namespace Playground.Editor;

/// <summary>
/// Live check of the types of expressions of layer 11b (0.1.58): the plugin knows the type of any expression — a call with inferred type
/// arguments, `await`, an element access, an operator, a tuple, a query — and resolves the member after a dot on it without the language
/// server. Settings | .NET | Language Server → Source of Features → «Colors of identifiers» = Built-in and «Navigation and usages» =
/// Built-in; best with the language server turned off (Settings | .NET → Language Server off) to be sure the answer is the plugin's.
/// Nothing is typed: look at the names a <c>// TYPE:types-*</c> comment points at, or Ctrl+click them, and compare with EXPECT.
/// </summary>
public class ExpressionTypes
{
    private readonly List<TypesOrder> _orders = [new() { Name = "a", Total = 1 }];

    public async Task<int> Members()
    {
        // TYPE:types-after-call — Ctrl+click: EXPECT `Total` (after `FirstOrDefault()`, `First(...)`, `[0]`, `ElementAt(0)`) → the property
        // in TypesOrder below; `Name` after `Select(o => o).Last()` → its property; the same names are cyan properties, not plain text
        var first = _orders.FirstOrDefault()?.Total ?? 0;
        var second = _orders.First(o => o.Total > 0).Total + _orders[0].Total + _orders.ElementAt(0).Total;
        var name = _orders.Select(o => o).Last().Name;
        // TYPE:types-await — Ctrl+click: EXPECT `Total` after `(await LoadAsync())` and after `await Task.Run(() => _orders[0])` → TypesOrder.Total
        var loaded = (await LoadAsync()).Total + (await Task.Run(() => _orders[0])).Total;
        return (int)(first + second + loaded) + name.Length;
    }

    public decimal Expressions()
    {
        // TYPE:types-tuples — Ctrl+click: EXPECT `Order` / `Count` after `pair.` and `Total` after `pair.Order.` → TypesOrder.Total;
        // `Item1` (a library field of ValueTuple) → nowhere without the server, never a wrong place
        var pair = (Order: _orders[0], Count: _orders.Count);
        var fromTuple = pair.Order.Total * pair.Count + pair.Item1.Total;
        // TYPE:types-deconstruction — Ctrl+click: EXPECT `Total` after `order.` (a deconstructed tuple element) and after `o.` (a foreach of a
        // dictionary's values) → TypesOrder.Total
        var (order, count) = pair;
        var sum = order.Total * count;
        foreach (var o in Index().Values) sum += o.Total;
        // TYPE:types-query — Ctrl+click: EXPECT `Total` after `big.` and `g.Key.` → TypesOrder.Total; `Length` after `n.` stays a property
        var totals = from big in _orders where big.Total > 0 group big by big into g select g.Key.Total;
        var lengths = from n in _orders let s = n.Name select s.Length;
        // TYPE:types-operators — Ctrl+click: EXPECT `Total` after `(_orders[0] ?? new TypesOrder())` and after `Pick(true).` → TypesOrder.Total
        var fallback = (_orders[0] ?? new TypesOrder()).Total + Pick(true).Total;
        var text = new StringBuilder().Append(fallback).ToString().Length;
        return fromTuple + sum + totals.Sum() + lengths.Sum() + text;
    }

    private Dictionary<string, TypesOrder> Index() => _orders.ToDictionary(o => o.Name);

    private TypesOrder Pick(bool any) => any ? _orders[0] : new TypesOrder();

    private Task<TypesOrder> LoadAsync() => Task.FromResult(_orders[0]);
}

public class TypesOrder
{
    public string Name { get; set; } = "";

    public decimal Total { get; set; }
}
