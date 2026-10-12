// TYPE:using-directive — on the empty line under this comment type `using System.Coll` and Ctrl+Space (Completion = Built-in).
// EXPECT: `Collections` (a namespace, no types); `using static System.` lists namespaces and the types `Console`, `Math`;
// `using J = System.Text.` lists `Json`, `Encoding`, `StringBuilder`; at the very top `global using` is an item. NOT: types after a plain
// `using System.`, `static` after `using static `. Ctrl+Z.

// TYPE:using-sort — Alt+Enter on any of the three directives below: "Sort 'using' directives". EXPECT: the directives of System, System.IO,
// System.Text in that order. NOT offered once they are sorted. Ctrl+Z.
// TYPE:using-global — Alt+Enter on the directive of System.Text: "Convert to 'global using'". EXPECT: the line leaves this file and
// `Console/GlobalUsings.cs` appears with the `global using` of System.Text (the project has no file of global usings yet). Ctrl+Z; delete
// GlobalUsings.cs if it stays.
using System.Text;
using System.IO;
using System;

namespace Playground.Editor;

/// <summary>
/// Live check of `using` on the plugin's own tree (0.1.61, CSHARP_PSI_MIGRATION.md task A8). Completion items need Settings | .NET
/// | Language Server → Source of Features → «Completion» = Built-in; the Alt+Enter actions work with any source (with the server ready and
/// «Typing assistance» = Language server, "Convert to 'using' declaration" is the server's "Use simple 'using' statement" instead). Type
/// on the empty line under a marker comment, check EXPECT, then undo (Ctrl+Z) so the file keeps compiling.
/// </summary>
public class Usings
{
    // TYPE:using-var — on the empty line in Var type `usi` and Ctrl+Space. EXPECT: `using var` and `await using var` among the items
    // (`using` too); `using var` gives `using var |`. In the getter of Name: `using var` but no `await using var`.
    public void Var()
    {

    }

    public string Name
    {
        get
        {

            return "name";
        }
    }

    // TYPE:using-await — on the empty line in Run type `aw` and choose `await using var`. EXPECT: `await using var ` and the header
    // `public async Task Run()`. Instead, type `await using var s = new MemoryStream();` by hand and Alt+Enter on `await`: "Make method
    // async" (one row). Ctrl+Z until the file is back.
    public void Run()
    {

    }

    // TYPE:using-postfix — on the empty line in Postfix type `new StringReader("x").using` and Enter. EXPECT: `using var reader = new
    // StringReader("x");` with `reader` in a box (Tab, 0.1.89). `new MemoryStream().awaitusing` gives `await using var stream = new MemoryStream();` and the
    // method becomes `public async Task Postfix()`. Ctrl+Z.
    public void Postfix()
    {

    }

    // TYPE:using-to-declaration — Alt+Enter on `using` in ToDeclaration: "Convert to 'using' declaration". EXPECT: `using var reader = …;`
    // and the WriteLine under it one level less indented, the braces gone. NOT offered when the statement is not the last one of its block.
    public void ToDeclaration()
    {
        Console.WriteLine("before");
        using (var reader = new StringReader("declaration"))
        {
            Console.WriteLine(reader.ReadLine());
        }
    }

    // TYPE:using-to-statement — Alt+Enter on `using` in ToStatement: "Convert to 'using' statement". EXPECT: `using (var writer = …)` and
    // the two statements after it inside new braces, one level deeper. Ctrl+Z.
    public void ToStatement()
    {
        using var writer = new StringWriter();
        writer.Write("statement");
        Console.WriteLine(writer.ToString());
    }

    // TYPE:using-wrap — Alt+Enter on `wrapped` in Wrap: "Wrap in 'using' statement". EXPECT: `using (var wrapped = …) { … }` around the rest
    // of the method. NOT offered on `var text = wrapped.ReadLine();` (a call: whether it is disposable needs the types). Ctrl+Z.
    public void Wrap()
    {
        var wrapped = new StringReader("wrap");
        var text = wrapped.ReadLine();
        Console.WriteLine(text);
    }

    // TYPE:using-cs1674 — on the empty line in Errors type `using (var n = 5) { }` (0.1.65; «Errors and warnings» = Built-in, after
    // indexing). EXPECT: `var n = 5` underlined red, tooltip `CS1674: 'int': type used in a using statement must implement
    // 'System.IDisposable'.` — once (not a second time from the server). `using var b = new StringBuilder();` → CS1674 with
    // 'System.Text.StringBuilder'. `await using var t = new CancellationTokenSource();` → CS8417 "… Did you mean 'using' rather than
    // 'await using'?". NOT underlined: `using var s = new MemoryStream();`, `await using var m = new MemoryStream();`,
    // `using (var x = Unknown()) { }` (an unknown type: the error is the server's CS0103 only). Ctrl+Z.
    public async System.Threading.Tasks.Task Errors()
    {

        await System.Threading.Tasks.Task.CompletedTask;
    }

    // TYPE:using-list — on the empty line in List type `using (` and Ctrl+Space (Completion = Built-in). EXPECT: `reader` and `stream`
    // among the items, NOT `count` nor `title` (an int and a string are not disposable; neither the server's rows of them). `using var x =
    // ` + Ctrl+Space: the same. `await using (` + Ctrl+Space: `stream` (a Stream is IAsyncDisposable), NOT `reader` (a TextReader is
    // IDisposable only). Ctrl+Z.
    public void List(int count, string title)
    {
        var reader = new StringReader(title);
        var stream = new MemoryStream();

        Console.WriteLine(count + reader.Peek() + stream.Length);
    }

    public Encoding Encoding => new StringBuilder().Length > 0 ? Encoding.UTF8 : Encoding.ASCII;
}
