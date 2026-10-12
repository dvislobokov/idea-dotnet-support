namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.173 (the Rider comparison on Jellyfin, № 54, 57, 63, 64): Introduce Variable replaces the occurrences of the whole
/// body, Move type to file writes a clean file, Refactor This (Ctrl+Alt+Shift+T) lists Rider's moves of a type and Extract Members to
/// Partial. Put the caret where the marker says, invoke, compare with EXPECT, then undo (Ctrl+Z; the moves create files — delete them, or
/// use Local History). Nothing here is called.
/// </summary>
public class RefactorMoves
{
    private readonly List<int> _items = new();

    public void Occurrences(bool flag)
    {
        // TYPE:moves-occurrences — the caret on `Count` of the second `_items.Count` below (inside the `if`), Alt+Enter → "Introduce
        // variable" → "Replace all 2 occurrences". EXPECT: `var count = _items.Count;` before the first line, both uses replaced; NOT
        // only the one in the `if` block
        Console.WriteLine(_items.Count);
        if (flag)
        {
            Console.WriteLine(_items.Count + 1);
        }
    }

    // TYPE:moves-extract-partial — the caret on `Persist` below, Ctrl+Alt+Shift+T → "Extract Members to Partial", name `Persistence`.
    // EXPECT: `RefactorMoves.Persistence.cs` next to this file with `public partial class RefactorMoves { … Persist … }`, this class
    // `public partial class RefactorMoves` without `Persist`; the new file has only the `using` directives it needs
    public void Persist()
    {
        Console.WriteLine(_items.Count);
    }
}

// TYPE:moves-type-rows — the caret on `MovedStamp` below, Ctrl+Alt+Shift+T. EXPECT: "Move to Another File", next to the moves of a
// type Rider lists. "Move to Another File" with `MovedStamp.cs` → the new file `namespace Playground.Editor;` + the class only, no blank
// line after it, nothing left here but the class above (Alt+Enter "Move 'MovedStamp' to MovedStamp.cs" writes the same)
public class MovedStamp
{
    public DateTime At { get; set; }
}
