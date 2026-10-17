namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.162: the statement templates list the names in scope at their variable (`switch` + Tab → `switch (|)` with a list,
/// as Rider's completeSmart()), and the gray text writes the next `case` of a `switch` over an enum by a rule, no network. Lines to
/// type on are marked <c>// TYPE:name</c>: put the caret on the empty line under the marker, type what the comment says, compare with
/// EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to compile.
/// </summary>
public class SwitchCompletion
{
    public enum OperationType { Insert, Update, Delete, Read }

    public void Use(OperationType operation, int count)
    {
        // TYPE:switch-template — type `switch` and press Tab. EXPECT: `switch (|)` with the caret between the parentheses and a list
        // that opens by itself with `operation` and `count` in it; choose `operation`, Tab → the caret in the empty body `{ | }`, as
        // in Rider, and gray `case OperationType.Insert:` there at once (Tab takes it). NOT `switch (value)`, NOT a `case :` / `break;` /
        // `default:` skeleton (0.1.175).

        // TYPE:switch-next-case — on the empty line under `break;` below (the caret at the indent of `case`). EXPECT: gray
        // `case OperationType.Update:`; Tab takes it, Enter, and `break;` is offered gray on the next line as before
        switch (operation)
        {
            case OperationType.Insert:
                break;

        }

        // TYPE:switch-typed-case — in the block below type `case` (no space). EXPECT: gray ` OperationType.Delete:`; then a space →
        // the list of the members opens by itself and the gray text follows the selected row (`OperationType.Delete:`, move to `Read`
        // → `OperationType.Read:`), Tab takes the gray text. Choosing `case` from the list on an empty line (type `ca`, Enter) →
        // `case ` and the gray label right away, no extra key; choosing a member under `case ` → gray `:`
        switch (operation)
        {
            case OperationType.Insert:
            case OperationType.Update:
                break;

        }

        // TYPE:switch-default — on the empty line below (every member is covered). EXPECT: gray `default:`; with `def` typed, gray `ault:`
        switch (operation)
        {
            case OperationType.Insert:
            case OperationType.Update:
            case OperationType.Delete:
            case OperationType.Read:
                break;

        }

        // TYPE:switch-no-case — on the empty line under `Console.WriteLine` (inside the section) and in the `switch (count)`.
        // EXPECT: no `case` in gray in either place (`break;` is offered in the first as before)
        switch (operation)
        {
            case OperationType.Insert:
                Console.WriteLine(count);

                break;
        }
        switch (count)
        {
            case 1:
                break;

        }

        // TYPE:switch-expression-arm — on the empty line between the arms. EXPECT: gray `OperationType.Update => `; after all four
        // members, `_ => `
        var name = operation switch
        {
            OperationType.Insert => "insert",

            _ => "other",
        };
        Console.WriteLine(name);
    }
}
