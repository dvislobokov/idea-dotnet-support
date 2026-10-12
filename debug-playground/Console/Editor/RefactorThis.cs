namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.170: Refactor This (Ctrl+Alt+Shift+T) lists Introduce Field / Introduce Parameter only on an expression of a body,
/// Rename on every identifier of the solution, and Introduce Variable (Alt+Enter or Refactor This) at the places of the Jellyfin comparison:
/// an `await` with no result, the receiver of a member, the type of a cast, a switch expression of collection expressions, the expression
/// body of a lambda. Put the caret where the marker says, invoke, compare with EXPECT, undo (Ctrl+Z). Nothing here is called.
/// </summary>
public class RefactorThis
{
    public class Folder
    {
        public int Size { get; set; }
    }

    private readonly List<int> _items = new();

    // TYPE:refactor-header — the caret on `RefactorThis` of the class header, Ctrl+Alt+Shift+T. EXPECT: Rename and the move rows; NO
    // "Introduce Field..." and NO "Introduce Parameter..." (they showed at every caret before)

    public int Count(int extra)
    {
        // TYPE:refactor-introduce — the caret on `Count` of `_items.Count` below, Ctrl+Alt+Shift+T. EXPECT: "Introduce Variable",
        // "Introduce Field...", "Introduce Parameter..." and Rename
        return _items.Count + extra;
    }

    public async Task Publish()
    {
        await Task.Delay(1);
    }

    public async Task<int> Places(object item, bool all)
    {
        // TYPE:refactor-await — the caret on `await` below, Alt+Enter → "Introduce variable". EXPECT: `var publish = Publish();` and
        // `await publish;` (the await has no value: what it awaits goes into the variable)
        await Publish();

        // TYPE:refactor-receiver — the caret on `_items` below, Alt+Enter → "Introduce variable". EXPECT: `var count = _items.Count;`
        // before the `if` and `if (count > 0)`; NOT nothing because `_items` alone is already a name
        if (_items.Count > 0) { }

        // TYPE:refactor-cast — the caret on `Folder` of the cast below, Alt+Enter → "Introduce variable". EXPECT: `var folder = (Folder)item;`
        // and `folder.Size`, the parentheses of the cast gone
        Console.WriteLine(((Folder)item).Size);

        // TYPE:refactor-switch — the caret on `switch` below, Alt+Enter → "Introduce variable". EXPECT: `var ints = all switch { … };` before
        // the assignment, typed by the place (`int[]`), and `values = ints;`
        int[] values;
        values = all switch { true => [1, 2], false => [3] };

        // TYPE:refactor-lambda — the caret on `Count` inside the lambda below, Alt+Enter → "Introduce variable". EXPECT: the lambda gets a
        // block: `{ var count = _items.Count; return count + x; }`, each statement on its own line
        Func<int, int> add = x => _items.Count + x;
        return values.Length + add(1);
    }
}
