using System.Collections;
using System.Runtime.CompilerServices;

namespace Playground.Editor;

/// <summary>
/// Live check of the usages the code does not write by name (0.1.82): the implicit calls Roslyn's Find References counts — `Deconstruct`
/// of a deconstruction or a positional pattern, the constructor of a target-typed `new(…)` wherever its type comes from the place,
/// `GetEnumerator` of `foreach`, `Dispose` of `using`, `GetAwaiter` of `await`, `Add` of a collection initializer. The server off (or the
/// «Navigation» feature Built-in, the default). Put the caret on the name a `// TYPE:` comment says, Alt+F7, compare with EXPECT; with the
/// server on, the same keys must give the same places. The file only has to compile.
/// </summary>
public sealed class ImplicitPoint(int x, int y)
{
    public int X { get; } = x;
    public int Y { get; } = y;

    // TYPE:implicit-deconstruct — caret on `Deconstruct`, Alt+F7. EXPECT: 3 usages in ImplicitUsageScenarios below: `var (a, b)` and
    // `(var c, var d)` of the two deconstructions, `var (px, py)` of the foreach (the row starts at the left side / the variable, as with
    // the server). NOT: the positional patterns `(0, _)` and `(var sx, _)` (the server does not count them either), this comment.
    // Shift+F6 on `Deconstruct` renames only the declaration: no `var` is touched
    public void Deconstruct(out int x, out int y) => (x, y) = (X, Y);
}

public sealed class ImplicitBag : IEnumerable<int>, IDisposable
{
    private readonly List<int> items = [];

    // TYPE:implicit-add — caret on `Add`, Alt+F7. EXPECT: the 2 elements of the collection initializer `{ 1, 2 }` in Run below, and the
    // explicit `bag.Add(3)`
    public void Add(int item) => items.Add(item);

    // TYPE:implicit-enumerator — caret on `GetEnumerator` (the first), Alt+F7. EXPECT: the `foreach` over `bag` in Run (the row is
    // `foreach`) and the call in the explicit IEnumerable.GetEnumerator below. The server lists more: every `foreach` of the solution over
    // any IEnumerable<T> (it cascades to the interface member of the library); the plugin only the loops that bind to this method
    public IEnumerator<int> GetEnumerator() => items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    // TYPE:implicit-dispose — caret on `Dispose`, Alt+F7. EXPECT: the `using` of Run (the row is `using`). The server also lists the
    // `using` of other IDisposable types (Usings.cs), the plugin does not
    public void Dispose() => items.Clear();
}

public readonly struct ImplicitDelay
{
    // TYPE:implicit-awaiter — caret on `GetAwaiter`, Alt+F7. EXPECT: the `await` of RunAsync (the row is `await`)
    public TaskAwaiter GetAwaiter() => Task.CompletedTask.GetAwaiter();
}

public static class ImplicitUsageScenarios
{
    // TYPE:implicit-new — caret on `ImplicitPoint` of the class above, Alt+F7. EXPECT, among the 7 usages by name: 7 rows on `new` —
    // every `new(…)` below: the field, the property initializer, the return of Make, the dictionary initializer `["a"] = new(1, 2)`, the
    // collection expression element, the collection initializer element, the argument of Sum (14 usages in all, as with the server)
    private static readonly ImplicitPoint Origin = new(0, 0);

    public static ImplicitPoint Corner { get; } = new(9, 9);

    public static ImplicitPoint Make() => new(3, 4);

    private static int Sum(ImplicitPoint point) => point.X + point.Y;

    public static int Run()
    {
        var byName = new Dictionary<string, ImplicitPoint> { ["a"] = new(1, 2) };
        List<ImplicitPoint> listed = [new(5, 6)];
        var initialized = new List<ImplicitPoint> { new(7, 8) };
        var (a, b) = Make();
        (var c, var d) = Origin;
        var total = a + b + c + d + Sum(new(1, 1));
        foreach (var (px, py) in listed) total += px * py;
        if (Corner is (0, _)) total++;
        total += byName["a"] switch { (var sx, _) => sx };
        using (var bag = new ImplicitBag { 1, 2 })
        {
            bag.Add(3);
            foreach (var item in bag) total += item;
        }
        return total + initialized.Count;
    }

    public static async Task RunAsync() => await new ImplicitDelay();
}
