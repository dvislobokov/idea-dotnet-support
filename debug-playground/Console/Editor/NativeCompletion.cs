using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;

namespace Playground.Editor;

/// <summary>
/// Live check of the completion list of the plugin's own tree (0.1.55). Settings | .NET | Language Server → Source of
/// Features → «Completion» = Built-in (the default is still «Language server»: then the list is the server's, as before). Type on the
/// empty line under a marker comment, press Ctrl+Space where it says, compare with EXPECT, then undo (Ctrl+Z) so the file keeps
/// compiling. With Built-in the items of the markers come at once, before «Roslyn: DebugPlayground.sln» is ready; once the server is
/// ready its items join (members after a dot, types of the framework) without doubles: one `total`, one `if`.
/// </summary>
public abstract class CompletionShape
{
    public abstract double Area();

    public virtual string Describe(int digits) => Area().ToString("F" + digits);

    protected virtual int Sides { get; set; }

    public void NotVirtual() { }
}

public partial class CompletionSquare : CompletionShape
{
    private readonly double _side = 2;
    private int _resized;

    public int Count { get; set; }

    public override double Area() => _side * _side;

    // TYPE:complete-keywords — on the empty line in Statements press Ctrl+Space. EXPECT: `total`, then `limit`, then `_side` /
    // `_resized` / `Count`, then the methods (`Resize`, `Area`...), the types (`CompletionShape`...), the keywords last; among the keywords
    // `if`, `foreach`, `return`, `var`, `await` — NOT `break`, `continue`, `case` (no loop or switch here), NOT `public`, `class`. Type
    // `whi` and Enter: `while (|)`. Inside the foreach below Ctrl+Space also offers `break` and `continue`
    public int Statements(int limit)
    {
        var total = limit;

        foreach (var step in Enumerable.Range(0, limit))
        {
            total += step;

        }
        return total;
    }

    // TYPE:complete-expected — on the empty line in Expected type `CompletionSquare copy = ` and Ctrl+Space. EXPECT: `other` (a
    // CompletionSquare) above `count` (an int); `copy` itself is not offered. Then type `Resize(` and Ctrl+Space: `amount` (named as the
    // parameter) first
    public void Expected(CompletionSquare other, int count)
    {
        var amount = count;

        Resize(amount);
    }

    public void Resize(int amount) => _resized += amount;

    // TYPE:complete-override — on the empty line below type `public override ` and Ctrl+Space. EXPECT: `Describe`, `Sides`, `Equals`,
    // `GetHashCode`, `ToString` — NOT `Area` (overridden above), NOT `NotVirtual`; no keywords. Choose `Describe`: the whole member is
    // written — `public override string Describe(int digits)` with `{ return base.Describe(digits); }` on its own lines, the caret after
    // the `;`. For an abstract member (make a second class: `class X : CompletionShape { override ` → `Area`) the body is
    // `throw new NotImplementedException();`


    // TYPE:complete-partial — on the empty line below type `partial ` and Ctrl+Space. EXPECT: `OnResized` (declared without a body in the
    // second part of CompletionSquare below); Enter writes `partial void OnResized(int amount)` with an empty body, the caret inside


    // TYPE:complete-names — on the empty line in Names type `StringBuilder ` and Ctrl+Space. EXPECT: `builder`, `stringBuilder`. Then
    // `List<CompletionShape> ` → `shapes`, `completionShapes`; `CancellationToken ` → `token`, `cancellationToken`. At the class level
    // `private readonly StringBuilder ` → `_builder`, `_stringBuilder`
    public void Names()
    {

    }

    // TYPE:complete-goto-query — on the empty line in Queries type `goto ` and Ctrl+Space: `again` (and nothing else). Undo, then after
    // `from x in items ` in the query below Ctrl+Space: `where`, `select`, `orderby`, `join`, `let`, `group`
    public int Queries(int[] items)
    {
    again:
        var query = from x in items
                    select x;

        return query.Count() > 100 ? 0 : items.Length == 0 ? Count : 0;
    }
}

public partial class CompletionSquare
{
    partial void OnResized(int amount);
}

public class CompletionAutoPopup
{
    private string _title = "";

    // TYPE:no-popup-after-brace — on the empty line below the method, type
    // `public string Title(CancellationToken token = default){` and wait a second. EXPECT: no completion list opens by itself after
    // `{`, nor after `(` (Rider opens none there); Ctrl+Space inside the new body still lists `token`, `_title`, `if`… Then type `this.` —
    // the list of members opens by itself. Not expected: the list `token, _title, Title, …` popping up after `{` (before 0.1.70).
    // Ctrl+Z to undo
    public string Name() => _title;

}
