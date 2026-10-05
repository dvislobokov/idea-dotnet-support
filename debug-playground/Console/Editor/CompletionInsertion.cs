using System.Collections.Immutable;

namespace Playground.Editor;

/// <summary>
/// Live check of the leftovers of completion 0.1.85–0.1.91 (0.1.92): parentheses after a method chosen by `.` / `;` or inserted by itself,
/// the types of extension methods with the receiver's type arguments, a lambda place found by the resolved delegate type, exceptions first
/// in `catch (`. Lines to type on are marked <c>// TYPE:name</c>: put the caret on the empty line under the marker, type what the comment
/// says, compare with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to compile. Completion = Built-in (the default).
/// </summary>
public class CompletionInsertion
{
    public delegate bool InsertionRule(InsertionOrder order);

    public enum Ex { None }

    public class Exc { }

    public class InsertionException : Exception { }

    private readonly List<InsertionOrder> _orders = new();

    private decimal Total() => _orders.Sum(o => o.Price);
    private void Recalculate() { }
    private void Register(int id) => _ = id;
    private bool Check(InsertionRule rule) => _orders.All(o => rule(o));

    public void Use()
    {
        var total = Total().ToString();
        Recalculate();
        Register(1);
        _ = Check(order => order.Price > 0);
        var array = _orders.ToImmutableArray();
        Console.WriteLine($"{total} {array.Length}");

        // TYPE:insertion-dot — `var text = Tot` (the list opens by itself), then `.`. EXPECT: `var text = Total().` with the caret after the dot
        // and the members of decimal in a list that opens by itself (`ToString`, `CompareTo`…); NOT `Total.`

        // TYPE:insertion-semicolon — `Recalcul` then `;`. EXPECT: `Recalculate();` with the caret after `;`. Then `Regist` and `;`. EXPECT:
        // `Register(|);` — the caret between the parentheses (the method takes an argument) and the parameter info `int id` opens by itself

        // TYPE:insertion-auto — `Recalcula` + Ctrl+Space. EXPECT: no list, the only item is inserted by itself as `Recalculate();` (as Enter
        // would); `var w = new Widgetr` + Ctrl+Space. EXPECT: `new Widgetry(|)`, the caret in the parentheses

        // TYPE:insertion-extension-types — `_orders.ToImm`. EXPECT: `ToImmutableArray` with the type `ImmutableArray<InsertionOrder>` on the
        // right (NOT `ImmutableArray<TSource>`); `_orders.Fir` → `First` : `InsertionOrder`; `_orders.Where(` + Ctrl+P → `Func<InsertionOrder,
        // bool> predicate` (NOT `Func<TSource, bool>`)

        // TYPE:insertion-delegate-lambda — `Check(`. EXPECT: the list opens by itself with nothing selected (InsertionRule is a delegate of its
        // own name, not Func / Action / …Handler), `order => ` at the top; typing `o` and a space leaves `o ` (a name is being written)
    }

    public void Catch()
    {
        try
        {
            Recalculate();
        }
        catch (InsertionException)
        {
        }

        // TYPE:insertion-catch — `try { } catch (` + Ctrl+Space. EXPECT: the exceptions first (`InsertionException`, `ArgumentException`,
        // `Exception`…); `Ex` (an enum) and `Exc` (a class) NOT among them — below all exceptions, with the other types
    }
}

public class InsertionOrder
{
    public decimal Price { get; set; }
}

public class Widgetry { }
