using System;
using System.Collections.Generic;
using System.Linq;

namespace Playground.Editor;

/// <summary>
/// Live check of Reformat Code on the plugin's own tree (0.1.49): what <c>dotnet format whitespace</c> does, without a process.
/// Settings | .NET | Language Server → Source of Features → «Formatting» = Built-in; Settings | .NET | Toolset and Build →
/// formatter «Auto» or «dotnet format» (with CSharpier chosen or found, CSharpier formats in both modes). Then the markers below: the code
/// under a <c>// TYPE:name</c> comment is badly formatted on purpose; put the caret or the selection where the marker says, press
/// Ctrl+Alt+L, compare with EXPECT, undo with Ctrl+Z. With «Language server» (the default for now) the server (or <c>dotnet format</c>)
/// formats: the result must be the same, the differences are named in EXPECT ("server: …"). The file only has to compile.
/// </summary>
public class Formatting
{
    // TYPE:format-method — select the whole method `Sum` below (from `public` to its `}`), Ctrl+Alt+L. EXPECT: `public int Sum(int a, int b)`
    // at the indent of the class, its braces on lines of their own (Allman), the body indented by 4; `if (a > b) { return a - b; }` is
    // laid out in full as Rider does since 0.1.100 (`{`, `return a - b;`, `}` on lines of their own; with `dotnet format` it stays on one
    // line), `return b - a;` one level under `else`; the methods around are not touched.
    // Server: same text; `dotnet format` without the server formats whole files only (the whole file changes)
    public int Sum(int a, int b)
    {
        if (a > b) { return a - b; }
        else
            return b - a;
    }

    // TYPE:format-file — caret anywhere, Ctrl+Alt+L with no selection (or Code | Reformat File). EXPECT: every method of the file as
    // the markers say; the blank lines are kept; the string literals, the comments and the `#else` branch (disabled while `DEBUG` is
    // defined) are left exactly as they are. Server: same text
    public string Literals()
    {
        var verbatim = @"line one
    line two keeps   its spaces";
        string text = "a  b";   // a trailing comment keeps   its spaces
        var hole = $"{verbatim.Length + 1} and {string.Join(",", text)}";
#if DEBUG
        return verbatim + text + hole;
#else
            return   verbatim  +  text ;
#endif
    }

    // TYPE:format-switch — select from `switch` to its `}`, Ctrl+Alt+L. EXPECT: `case` labels indented inside the switch, their
    // statements one level deeper, `case 2:` with its block `{` under `case` and contents one level deeper still. Server: same text
    public static string Name(int code)
    {
        switch (code)
        {
            case 1:
                return "one";
            case 2:
                {
                    return "two";
                }
            default:
                return "many";
        }
    }

    // TYPE:format-initializers — select the method, Ctrl+Alt+L. EXPECT: object initializer members on lines of their own with
    // `Count = 3`; the multi-line collection initializer `{1,` … `3}` is left exactly as it is (Roslyn does not touch it either);
    // the query clauses line up under `from`; the lambda body is indented from the line of the lambda. Server: same text
    public List<int> Initializers()
    {
        var box = new Box { Count = 3, Name = "n" };
        var list = new List<int>
        {
            1,
            2,
            3
        };
        var query = from n in list
                    where n > box.Count
                    select n * 2;
        Action<string> print = s =>
        {
            Console.WriteLine(s);
        };
        print(box.Name);
        return query.ToList();
    }

    // TYPE:format-options — put an `.editorconfig` next to this file with `[*.cs]` and `csharp_new_line_before_open_brace = none`,
    // `indent_size = 2`, then Ctrl+Alt+L on the method `Sum`. EXPECT: `{` at the end of the line of `Sum(...)`, the body indented
    // by 2. Delete the `.editorconfig` afterwards. Server: same text

    // TYPE:format-choice — Toolset and Build → «Formatter». Built-in: select two badly spaced lines of `Sum`, Ctrl+Alt+L. EXPECT: only
    // they are fixed, at once; Code | Auto-Indent Lines works too. «dotnet format (on save)»: Ctrl+Alt+L. EXPECT: the whole file, about
    // a second without the language server; with «Reformat code» in Actions on Save, Ctrl+S formats the file. «Auto» without CSharpier:
    // as Built-in when Language Server → «Formatting» is Built-in, as dotnet format when it is Language server

    // TYPE:format-csharpier — Toolset and Build → formatter «CSharpier» (with CSharpier installed), Ctrl+Alt+L on the file. EXPECT: the
    // CSharpier style (as before 0.1.49), whatever «Formatting» says; «None»: Ctrl+Alt+L changes nothing

    private class Box
    {
        public int Count { get; set; }
        public string Name { get; set; } = "";
    }
}
