using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace Playground.Editor;

/// <summary>
/// Live check of completion after a dot, quick documentation and parameter info on the plugin's own semantics (0.1.66,
/// CSHARP_PSI_MIGRATION.md task C3). Completion needs Settings | .NET | Language Server → Source of Features → «Completion» =
/// Built-in; quick documentation and parameter info need «Documentation and parameter info» = Built-in (Language server by default).
/// Best with the server switched off, after the assemblies are indexed (restore). Type on the empty line under a marker comment, check
/// EXPECT, then undo (Ctrl+Z) so the file keeps compiling.
/// </summary>
public class MemberCompletion
{
    private readonly List<MemberOrder> _orders = new();

    // TYPE:dot-instance — on the empty line type `text.` (Ctrl+Space if the list does not open). EXPECT: Length, Substring, ToUpper,
    // Split… and the extension methods of LINQ (Where, Select, First) further down, with their parameters `(Func<char, bool> predicate)`
    // without the receiver. NOT: IsNullOrEmpty, Join (static), MemberwiseClone (protected), keywords. Choose Substring: `text.Substring(|)`.
    public void Instance(string text)
    {

    }

    // TYPE:dot-generic — type `_orders.`. EXPECT: Add `(MemberOrder item)`, Count `: int`, Where / Select / OrderBy (System.Linq is
    // imported), one item per name (the server's twins are dropped once it is ready). Then `_orders[0].` → Total, Ship, Lines; NOT _secret.
    public void Generic()
    {

    }

    // TYPE:dot-static — type `Console.` → WriteLine `(string value) (+ 17)`, ReadLine, Out; NOT instance members. `string.` → Join, Empty,
    // IsNullOrEmpty; NOT Length. `MemberOrder.` → Empty, State; NOT Total. `MemberOrder.State.` → New, Shipped.
    public void Static()
    {

    }

    // TYPE:dot-namespace — type `System.Collections.Generic.` → namespaces and types: List<>, Dictionary<,>, HashSet<>; `System.Te` → Text.
    public void Namespaces()
    {

    }

    // TYPE:dot-this — in a class derived from a library type: `this.` → Add, Count (of List<int>) and own `_extra`.
    private sealed class Numbers : List<int>
    {
        private int _extra;

        public void Fill()
        {

            _extra++;
        }
    }

    // TYPE:quick-doc — put the caret on WriteLine / Substring / Total / Add / limit below and press Ctrl+Q (or hover).
    // EXPECT: `void Console.WriteLine(string value) (+ N overloads)` and the text of the documentation of System.Console with the
    // parameter; `string string.Substring(int startIndex, int length) (+ 1 overload)`; `decimal MemberOrder.Total { get; set; }` with
    // «The sum of the lines.»; `void MemberOrder.Add(string item, int count = 1) (+ 1 overload)` with Params; `(parameter) int limit` with
    // «How many to show.»; on `Documented` itself — «Shows the first orders.». NOT: the server's hover beside it.
    /// <summary>Shows the first orders.</summary>
    /// <param name="limit">How many to show.</param>
    public void Documented(int limit)
    {
        Console.WriteLine("orders".Substring(0, 3));
        var first = _orders.First();
        first.Add("book", limit);
        Console.WriteLine(first.Total);
    }

    // TYPE:parameter-info — put the caret inside the parentheses of `first.Add("book", limit)` above and press Ctrl+P. EXPECT: two rows,
    // `string item` and `string item, int count = 1`, the second marked, `int count = 1` highlighted. In `Console.WriteLine(|)`: a row per
    // overload. In `new StringBuilder(|)` below: the constructors of StringBuilder.
    public string Build() => new StringBuilder(16).ToString();
}

/// <summary>An order of the member completion scenario.</summary>
public class MemberOrder
{
    private int _secret;

    /// <summary>The sum of the lines.</summary>
    public decimal Total { get; set; }

    public List<string> Lines { get; } = new();

    public static MemberOrder Empty => new();

    public void Ship() => _secret++;

    /// <summary>Adds a line.</summary>
    /// <param name="item">What is added.</param>
    /// <param name="count">How many times.</param>
    public void Add(string item, int count = 1)
    {
        for (var i = 0; i < count; i++) Lines.Add(item);
    }

    public void Add(string item) => Add(item, 1);

    public enum State { New, Shipped }
}
