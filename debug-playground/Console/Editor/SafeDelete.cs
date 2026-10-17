namespace Playground.Editor;

/// <summary>
/// Live check of Safe Delete (0.1.174): Alt+Delete (or Refactor This → Safe Delete...) with the caret on a
/// name. Every marker <c>// TYPE:del-name</c> says where to put the caret, what to press and what must happen (EXPECT); undo with
/// Ctrl+Z after each. The file only has to compile.
/// </summary>
public class SafeDelete
{
    // TYPE:del-method — caret on `Unused` below, Alt+Delete. EXPECT: the method goes with its `///` line and the blank line before it;
    // `Kept` and `Run` stay as they are, one blank line between them
    public void Kept() { }

    /// <summary>Nobody calls it.</summary>
    public void Unused() { }

    // TYPE:del-used — caret on `Used`, Alt+Delete. EXPECT: a dialog of conflicts "Method 'Used' is used in SafeDelete.cs:<line>";
    // Cancel leaves the file as it is, Continue deletes the method (and `Run` no longer compiles)
    public void Used() { }

    public void Run()
    {
        Used();
        Save(1, "a", true);
        Save(2, name: "b", force: false);
    }

    // TYPE:del-parameter — caret on `name` of `Save`, Alt+Delete. EXPECT: `Save(int id, bool force)`, the `<param name="name">` line
    // gone, the calls in `Run` become `Save(1, true);` and `Save(2, force: false);`
    /// <summary>Saves.</summary>
    /// <param name="id">The id.</param>
    /// <param name="name">The name.</param>
    /// <param name="force">Whether to force.</param>
    public void Save(int id, string name, bool force) { _ = id; _ = force; }

    // TYPE:del-local — caret on `unused`, Alt+Delete. EXPECT: the line goes; on `saved` → the line becomes `Compute();` (the call stays)
    public void Locals()
    {
        var unused = 1;
        var saved = Compute();
    }

    private static int Compute() => 42;

    // TYPE:del-enum — caret on `Green`, Alt+Delete. EXPECT: `Red, Blue` — the member with its comma
    public enum Color { Red, Green, Blue }

    // TYPE:del-type-parameter — caret on `U` of `Pair<T, U>`, Alt+Delete. EXPECT: `Pair<T>`, its `where U : struct` gone, and the use
    // below becomes `Pair<string>`
    public class Pair<T, U> where U : struct
    {
        public T? First;
    }

    public Pair<string, int> MakePair() => new Pair<string, int>();
}
