using System.Text;
using static System.Math;
using Json = System.Text.Json.JsonSerializer;

namespace Playground.Editor;

/// <summary>
/// Live check of the name resolution of layer 11a (0.1.57): types and members of the referenced assemblies, members of values whose type
/// is known, extension methods — without the language server. Settings | .NET | Language Server → Source of Features →
/// «Colors of identifiers» = Built-in and «Navigation and usages» = Built-in; best with the language server turned off (Settings | .NET
/// → Language Server off) to be sure the answer is the plugin's. Nothing is typed: look at the names a <c>// TYPE:library-*</c>
/// comment points at, or Ctrl+click them, and compare with EXPECT. The robot prints the keys: <c>tools/ui-robot/scripts/highlight_keys.js</c>.
/// </summary>
public class LibraryNames
{
    private readonly StringBuilder _log = new();
    private LibraryOrder Current { get; } = new();

    public void Colors(List<int> numbers, string? text)
    {
        // TYPE:library-colors — EXPECT in Built-in: `Console` / `StringBuilder` / `List` class color, `Math` static class color,
        // `DateTime` struct color, `WriteLine` / `Round` / `Max` green static method call, `Append` / `ToString` green method call,
        // `Count` / `Length` / `Now` / `Year` cyan property (`Now` static), `Pi` cyan bold constant, `Where` / `First` extension method
        // color, `Text` / `Json` / `System` namespace color in the usings above; nothing of this stays plain as before 0.1.56
        Console.WriteLine(numbers.Count + (text?.Length ?? 0));
        _log.Append(Round(PI, 2)).Append(Max(1, 2)).ToString();
        var year = DateTime.Now.Year;
        var first = numbers.Where(n => n > year).First();
        Console.WriteLine(first);
    }

    public void Navigation()
    {
        // TYPE:library-navigation — Ctrl+click: EXPECT `Total` → the property in LibraryOrder below (the type of `Current` is known), `Lines`
        // → its field, `Add` (on `Lines`, a List) and `WriteLine` → in Built-in with the server off (0.1.62) the metadata view of `List<T>` /
        // `Console` (tab `List.cs [System.Collections 10.0]`, read-only, banner «Metadata of …») at that member — `WriteLine` as a list of its
        // overloads; with the server ready its decompiled source as before — never a wrong place
        var total = Current.Total + Current.Lines.Count;
        Current.Lines.Add(total);
        Console.WriteLine(Json.Serialize(Current.Lines));
    }
}

public class LibraryOrder
{
    public List<int> Lines = [];

    public int Total => Lines.Sum();
}
