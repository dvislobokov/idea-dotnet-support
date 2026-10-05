using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace Playground.Editor;

/// <summary>
/// Live check of completion by the expected type (0.1.88, ROADMAP «Completion по ожидаемому типу»): initializers and property patterns,
/// enum members, `await` rows, `new` with a target type, filtered type places, smart completion (Ctrl+Shift+Space). Built-in completion
/// (Settings | .NET | Language Server → Source of Features → «Completion» = Built-in, the default since 0.1.76). Type on the empty line
/// under a marker comment, check EXPECT, then undo (Ctrl+Z) so the file keeps compiling.
/// </summary>
public class ExpectedTypeCompletion
{
    private int _created = 1;
    private string _mutable = "";

    public int Created => _created;

    private Task<string> Highlights() => Task.FromResult("text");
    private Task<int> Counted() => Task.FromResult(1);
    private void Take(ExpectedStatus status) { }

    public async Task Initializers(ExpectedOrder order, ExpectedPoint point)
    {
        await Task.Yield();
        // TYPE:expected-initializer — type `var a = new ExpectedOrder { ` and press Ctrl+Space. EXPECT: only `Buyer`, `Customer`, `Id`,
        // `Lines`, `Status` (bold, type at the right). NOT: `Total` (private set), `Code` (readonly), `Summary` (no setter), locals, keywords

        // TYPE:expected-initializer-rest — type `var b = new ExpectedOrder { Id = 1, Status = ExpectedStatus.New, ` and Ctrl+Space.
        // EXPECT: `Buyer`, `Customer`, `Lines` only — what is assigned already is gone

        // TYPE:expected-with — type `var c = point with { X = 1, ` and Ctrl+Space. EXPECT: `Y` only

        // TYPE:expected-property-pattern — type `if (order is { ` and Ctrl+Space. EXPECT: `Buyer`, `Code`, `Customer`, `Id`, `Lines`,
        // `Status`, `Summary`, `Total`; after `{ Id: 1, ` — the same without `Id`. NOT: locals, `if`

        // TYPE:expected-nested-pattern — type `if (order is { Buyer: { ` and Ctrl+Space. EXPECT: `Age`, `Name` (the members of ExpectedBuyer);
        // then `if (order is { Status: ` → the four `ExpectedStatus.*` rows first

    }

    public void Enums(ExpectedStatus status, ExpectedOrder order)
    {
        // TYPE:expected-enum-equals — type `if (status == ` (the space too). EXPECT: the list opens by itself, its first rows
        // `ExpectedStatus.Cancelled : 7`, `ExpectedStatus.New : 0`, `ExpectedStatus.Paid : 5`, `ExpectedStatus.Shipped : 6`; typing `Pa` and
        // Enter gives `status == ExpectedStatus.Paid`. NOT: after `if (_created == ` the list does not open by itself

        // TYPE:expected-enum-case — type `switch (status) { case ` (the list opens by itself), `var label = status switch { ` (Ctrl+Space),
        // `if (order.Status is ` (Ctrl+Space). EXPECT: each time the four `ExpectedStatus.*` rows first

        // TYPE:expected-enum-argument — type `Take(` then Ctrl+Space, and `status = ` then Ctrl+Space. EXPECT: `ExpectedStatus.*` first,
        // `status` right under them

    }

    // TYPE:expected-await — on the empty line in Plain type `string s = ` and Ctrl+Space. EXPECT: a row `await Highlights : Task<string>`;
    // Enter gives `string s = await Highlights();` and the header becomes `private async Task<string> Plain()`. NOT: `await Counted` (an int)
    private string Plain()
    {

        return _mutable;
    }

    public void Creation()
    {
        // TYPE:expected-new — type `ExpectedShape s = new ` and Ctrl+Space. EXPECT: `ExpectedCircle()` and `ExpectedSquare()` high in the
        // list (the shape itself is abstract: not first). `ExpectedOrder o = new ` → `ExpectedOrder()` first, Enter gives `new ExpectedOrder()`

        // TYPE:expected-throw-new — type `throw new ` and Ctrl+Space. EXPECT: only exceptions: `ExpectedException()`, `ArgumentException()`,
        // `InvalidOperationException()`… NOT: `ExpectedOrder`, `int`, `string`

        // TYPE:expected-catch — type `try { } catch (` and Ctrl+Space. EXPECT: exceptions first (`ExpectedException`, `ArgumentException`…),
        // the other types below

    }

    // TYPE:expected-base-list — after `ExpectedCircleTwo : ` below delete `ExpectedShape`, Ctrl+Space (Ctrl+Z after). EXPECT: classes and
    // interfaces (`ExpectedShape`, `IExpectedShape`); NOT: `ExpectedSquare` (sealed), `ExpectedStatus` (enum), `ExpectedChanged`
    // (delegate), `int`. In `ExpectedValue : ` (a struct) — interfaces only
    public class ExpectedCircleTwo : ExpectedShape { }

    public struct ExpectedValue : IExpectedShape { }

    // TYPE:expected-event — on the empty line type `public event ` and Ctrl+Space. EXPECT: delegates only: `ExpectedChanged`, `EventHandler`,
    // `Action`…; NOT: classes, `int`, member keywords

    public void Smart(int number, string name, bool flag)
    {
        // TYPE:expected-smart — type `int n = ` and press Ctrl+Shift+Space (smart completion). EXPECT: only ints: `number`, `_created`,
        // `await Counted`, `default`, `Int32.MaxValue`…; NOT: `name`, `flag`, `_mutable`, keywords but `default`. `string s = ` + Ctrl+Shift+Space →
        // `name`, `_mutable`, `String.Empty`, `null`; `Take(` + Ctrl+Shift+Space → `ExpectedStatus.*` and nothing else of other types;
        // `ExpectedShape s = new ` + Ctrl+Shift+Space → `ExpectedCircle()`, `ExpectedSquare()` only

    }
}

public enum ExpectedStatus { New, Paid = 5, Shipped, Cancelled }

public class ExpectedBuyer
{
    public string Name { get; set; } = "";
    public int Age { get; set; }
}

public class ExpectedOrder
{
    public int Id { get; set; }
    public string Customer { get; set; } = "";
    public ExpectedStatus Status { get; init; }
    public decimal Total { get; private set; }
    public List<string> Lines { get; } = new();
    public readonly int Code;
    public string Summary => Customer + Total + Code;
    public ExpectedBuyer Buyer { get; set; } = new();
}

public record ExpectedPoint(int X, int Y);

public abstract class ExpectedShape { }

public class ExpectedCircle : ExpectedShape { }

public sealed class ExpectedSquare : ExpectedShape { }

public interface IExpectedShape { }

public class ExpectedException : Exception { }

public delegate void ExpectedChanged(int value);
