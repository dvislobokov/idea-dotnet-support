namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.163, the analogy and value rules of the gray text (no network): the statement a `switch` repeats, the collection of a
/// `foreach`, the bound of a `for`, the parameter of `nameof(`, the string of `string.IsNullOrEmpty(`. Lines to type on are marked
/// <c>// TYPE:name</c>: put the caret on the empty line under the marker, type what the comment says, compare with EXPECT, undo (Ctrl+Z).
/// Nothing here is called, the file only has to compile. Completion = Built-in (the default).
/// </summary>
public class AnalogyGhost
{
    public enum OperationType { Insert, Update, Delete, Read }

    private static string Insert(int id) => $"insert {id}";
    private static string Update(int id) => $"update {id}";
    private static string Delete(int id) => $"delete {id}";

    public string Describe(OperationType operation, int id)
    {
        // TYPE:analogy-section — on the empty line under `case OperationType.Delete:` (at the indent of `return`). EXPECT: gray
        // `return "Delete";`, the two sections above repeated with this member's name; under `default:` nothing
        switch (operation)
        {
            case OperationType.Insert:
                return "Insert";
            case OperationType.Update:
                return "Update";
            case OperationType.Delete:

            default:
                break;
        }

        // TYPE:analogy-call — on the empty line under `case OperationType.Delete:` below. EXPECT: gray `result = Delete(id);` (the member
        // as a method name, the `id` kept); Tab, Enter → `break;` gray as before. Type `res` first → gray `ult = Delete(id);`
        var result = "";
        switch (operation)
        {
            case OperationType.Insert:
                result = Insert(id);
                break;
            case OperationType.Update:
                result = Update(id);
                break;
            case OperationType.Delete:

                break;
        }

        // TYPE:analogy-arm — delete `"Delete",` after `OperationType.Delete => ` (keep the space). EXPECT: gray `"Delete",`; type `"D` → gray `elete",`
        var name = operation switch
        {
            OperationType.Insert => "Insert",
            OperationType.Update => "Update",
            OperationType.Delete => "Delete",
            _ => result,
        };
        return name;
    }

    public int Sum(List<int> amounts, string title)
    {
        // TYPE:analogy-foreach — type `foreach (var amount in ` (the `)` may come by itself). EXPECT: gray `amounts)` (or `amounts` before
        // the `)`): the one collection in scope; with `foreach (var x in ` the same, there is one collection
        var total = 0;

        // TYPE:analogy-foreach-type — type `foreach (var item in `. EXPECT: no gray (two collections, none named so); with
        // `foreach (var amount in ` gray `amounts)` — the collection named as the plural, else the one whose element type names the element
        // (`foreach (var order in ` over `ICollection<Order> source` → `source)`; 0.1.169, Jellyfin № 6)

        // TYPE:analogy-for — type `for (var i = 0; i < `. EXPECT: gray `amounts.Count; i++)`; with an array parameter it would be `.Length`

        // TYPE:analogy-string — type `if (string.IsNullOrEmpty(`. EXPECT: gray `title)` (the one string in scope)

        // TYPE:analogy-nameof-one — type `var n = nameof(`. EXPECT: no gray text (two parameters, no `if` naming one);
        // then type `if (title == null) throw new ArgumentNullException(nameof(` → gray `title)`
        return total + amounts.Count + title.Length;
    }

    public void Check(string name)
    {
        // TYPE:analogy-nameof — type `if (name is null) throw new ArgumentNullException(nameof(`. EXPECT: gray `name)`: the one parameter
    }
}
