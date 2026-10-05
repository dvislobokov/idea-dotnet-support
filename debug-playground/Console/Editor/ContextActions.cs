using System;
using System.Collections.Generic;
using System.Linq;

namespace Playground.Editor;

/// <summary>
/// Live check of the Alt+Enter context actions on the plugin's own tree (0.1.64, CSHARP_PSI_MIGRATION.md task A7). Settings | .NET |
/// Language Server → Source of Features → «Context actions» = Built-in, the default since 0.1.72 (with Language server and the server ready, the server's own
/// actions answer instead; without the server the built-in ones answer either way). Put the caret where a marker says, press Alt+Enter,
/// check EXPECT, then undo (Ctrl+Z) so the file stays as it is. With Built-in there must be one row per action: the server's rows of the
/// same actions ("Convert to conditional expression", "Use expression body for method", "Use explicit type", "Use implicit type",
/// "Introduce local for …", "Inline temporary variable") are gone.
/// </summary>
public class ContextActions
{
    private int _total;

    // TYPE:ctx-if-to-conditional — Alt+Enter on `if` in Sign: "Convert to '?:' expression". EXPECT: `return value >= 0 ? "plus" : "minus";`
    // in place of the four lines. The same on `if` in Clamp (an `if` followed by `return`): `return value > 100 ? 100 : value;`, and in
    // Assign: `_total = add ? _total + value : _total - value;`. NOT offered on `if` in Mixed (the branches assign different fields).
    public string Sign(int value)
    {
        if (value >= 0)
            return "plus";
        else
            return "minus";
    }

    public int Clamp(int value)
    {
        if (value > 100) return 100;
        return value;
    }

    public void Assign(bool add, int value)
    {
        if (add)
        {
            _total = _total + value;
        }
        else
        {
            _total = _total - value;
        }
    }

    private int _other;

    public void Mixed(bool add)
    {
        if (add) _total = 1; else _other = 1;
    }

    // TYPE:ctx-conditional-to-if — Alt+Enter on `?` in Parity: "Convert '?:' to 'if' statement". EXPECT: `if (value % 2 == 0) { return
    // "even"; } else { return "odd"; }` with the braces on their own lines. In Check, on `?`: the `else` branch is `throw new
    // ArgumentException(...)`; as a statement. NOT offered on the `?:` in the argument of Console.WriteLine in Print.
    public string Parity(int value)
    {
        return value % 2 == 0 ? "even" : "odd";
    }

    public string Check(string? text)
    {
        return text != null ? text : throw new ArgumentException("no text");
    }

    public void Print(bool flag)
    {
        Console.WriteLine(flag ? "yes" : "no");
    }

    // TYPE:ctx-expression-body — Alt+Enter on the name Twice: "To expression body". EXPECT: `public int Twice(int value) => value * 2;`.
    // On Log: `public void Log(string text) => Console.WriteLine(text);`. On the name Name (a property with only `get`): `public string Name
    // => "context";`. On `get` of Counter: `get => _total;` (the `set` stays). NOT offered on Two (two statements) nor inside a body.
    public int Twice(int value)
    {
        return value * 2;
    }

    public void Log(string text)
    {
        Console.WriteLine(text);
    }

    public string Name
    {
        get { return "context"; }
    }

    public int Counter
    {
        get { return _total; }
        set { _total = value; }
    }

    public int Two(int value)
    {
        var doubled = value * 2;
        return doubled + 1;
    }

    // TYPE:ctx-block-body — Alt+Enter on the name Half: "To block body". EXPECT: a block of `return value / 2;` under the header, the
    // braces on their own lines. On Shout (void): a block of `Console.WriteLine(text.ToUpper());` without `return`. On the name Title:
    // `{ get { return "title"; } }` on three lines. On `set` of Level: `set { _total = value; }` on the same line.
    public int Half(int value) => value / 2;

    public void Shout(string text) => Console.WriteLine(text.ToUpper());

    public string Title => "title";

    public int Level { get => _total; set => _total = value; }

    // TYPE:ctx-var — Alt+Enter on `var` of names: "Use explicit type". EXPECT: `List<string> names = …` (no namespace: it is imported).
    // On `var` of count: `int count`; of query: `IEnumerable<string> query`; on `var` in the foreach: `string name`. On `int` of total
    // (Alt+Enter): "Use 'var'" → `var total = 0;`. NOT offered "Use 'var'" on `long big = 1;` (the value is an `int`) nor on
    // `IList<string> list = names;`.
    public void Types()
    {
        var names = new List<string> { "a", "b" };
        var count = names.Count;
        var query = names.Where(n => n.Length > 0);
        foreach (var name in query)
        {
            Console.WriteLine(name + count);
        }
        int total = 0;
        long big = 1;
        IList<string> list = names;
        Console.WriteLine(total + big + list.Count);
    }

    // TYPE:ctx-introduce — Alt+Enter on `Count` in Introduce: "Introduce variable". EXPECT: `var count = items.Count;` above the line and
    // `count * 2` in place, the name in a box: type another name and both change, Enter. Select `items.Count * 2` and Alt+Enter: `var value
    // = items.Count * 2;`. NOT offered on `Ready()` after `&&` (it runs only sometimes) nor on `items` alone.
    public void Introduce(List<int> items)
    {
        Console.WriteLine(items.Count * 2);
        if (items.Count > 0 && Ready())
        {
            Console.WriteLine("ready");
        }
    }

    private bool Ready() => true;

    // TYPE:ctx-inline — Alt+Enter on `sum` in Inline: "Inline variable". EXPECT: the declaration gone and `return (a + b) * 2 + Math.Abs(a +
    // b);` (in parentheses where needed). The same on `var` of sum (0.1.72). NOT offered on `changed` (written again).
    public int Inline(int a, int b)
    {
        var sum = a + b;
        var changed = a;
        changed++;
        return sum * 2 + Math.Abs(sum) + changed;
    }
}
