using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Playground.Editor;

/// <summary>
/// Live check of the options of Settings | .NET | Language Server on the plugin's own C# (the server off, every feature Built-in):
/// each option works for the built-in features as for the server. For every marker: toggle the named option (Apply), do what the
/// comment says on the empty line under it, check EXPECT, undo (Ctrl+Z) and put the option back.
/// </summary>
public class ServerOptions
{
    // TYPE:option-unimported — Completion → "Show items from namespaces that are not imported" OFF: type `StringBu` and Ctrl+Space.
    // EXPECT: no `StringBuilder (in System.Text)`; `Console` (System is imported) is still there. ON: `StringBuilder (in System.Text)` is back,
    // and after `list.ToImm` so is `ToImmutableList() (in System.Collections.Immutable)`. (Not `list.Sum()`: the project has
    // `<ImplicitUsings>`, so System.Linq is imported and `Sum` is in the list whatever the option says.)
    public void Unimported(List<int> list)
    {

    }

    // TYPE:option-names — Completion → "Suggest names for new members and variables" OFF: type `HttpClientHandler ` (with the space) and
    // Ctrl+Space. EXPECT: no `handler` / `httpClientHandler` rows. ON: both rows, `handler` first. Same for `foreach (var ` and `var (` of a tuple.
    public void Names()
    {

    }

    // TYPE:option-regex-completion — Completion → "Completion inside regular expressions" OFF: put the caret after `\` in the pattern below and
    // Ctrl+Space. EXPECT: no list of `\d`, `\w`…; the pattern keeps its colors. ON: the list.
    public void RegexCompletion()
    {
        var r = new Regex(@"\d+");
    }

    // TYPE:option-arguments — Completion → "Show completion in argument lists automatically" OFF: type `Run(` (with the parenthesis).
    // EXPECT: no list opens by itself (Ctrl+Space still works). ON: the list opens after `(` with `item` in it.
    public void Arguments()
    {

    }

    private int item;
    private void Run(Func<int, bool> predicate) { }

    // TYPE:option-decompiled — Navigation and Documentation → "Navigate to decompiled sources" OFF: Ctrl+Click on `StringBuilder` below.
    // EXPECT: the metadata view (signatures only, "from metadata" banner), never the decompiled code. ON: the decompiled code (the second
    // click, after the decompiler is done).
    public void Decompiled()
    {
        var builder = new System.Text.StringBuilder();
    }

    // TYPE:option-remarks — Navigation and Documentation → "Show remarks in quick documentation" OFF: Ctrl+Q (or hover) on `WithRemarks`.
    // EXPECT: the summary without a "Remarks:" section. ON: "Remarks:" with the text below.
    public void Remarks()
    {
        WithRemarks();
    }

    /// <summary>A member with remarks.</summary>
    /// <remarks>These remarks are shown only while the option is on.</remarks>
    private void WithRemarks() { }

    // TYPE:option-symbol-search — Navigation and Documentation → "Search symbols in reference assemblies" OFF: Ctrl+N, `StringBuilder`,
    // "Include non-project items" checked. EXPECT: nothing of the assemblies (only the types of the solution). ON: `StringBuilder (System.Text, …)`.

    // TYPE:option-auto-insert — Editing → "Insert documentation comments and closing braces automatically" OFF: on the empty line above
    // `AutoInsert` type `///`. EXPECT: just `///`, no `<summary>` skeleton; Enter after it does not continue with `/// `. ON: the skeleton
    // with `<param>` for `count`. The pair of `{` `}` is the platform's either way (Settings | Editor | General | Smart Keys).

    public void AutoInsert(int count)
    {
    }

    // TYPE:option-regex-highlight — Editing → "Highlight related parts of regular expressions" OFF: put the caret on `(` of the pattern below.
    // EXPECT: the pattern is one string color, no matching of the `(` `)`, no regex colors. ON: the regex colors and the matching parenthesis.
    // TYPE:option-json-highlight — Editing → "Highlight related parts of JSON strings" OFF: the caret on `[` of the JSON below. EXPECT: plain
    // string, no matching `]`. ON: JSON colors and the matching bracket.
    public void Highlighting()
    {
        var r = new Regex(@"(\d+)-(\w+)");
        var doc = JsonDocument.Parse("[1, 2, 3]");
    }

    // TYPE:option-organize — Editing → "Organize 'using' directives when formatting" ON: Reformat Code (Ctrl+Alt+L) on this file.
    // EXPECT: `using System.Text.Json;` and the others sorted (System first, the alphabet after), nothing unused left (add `using System.IO;`
    // at the top first: it goes away). OFF: Reformat leaves the directives as they are. Ctrl+Z.

    // TYPE:option-insertion — Code Generation → "Insert generated members: at_the_end": the caret on the line of `_id` below, Alt+Insert →
    // Constructor. EXPECT: the constructor after `Last()`, at the end of the type. "with_other_members_of_the_same_kind": after `_id`,
    // where the caret is. Ctrl+Z. Same with Alt+Enter → "Create property" on `generated.Count` (at the end, or after `Id`).
    private readonly int _id;

    public int Id { get; set; }

    public void Last() { }

    // TYPE:option-properties — Code Generation → "Generated properties: prefer_auto_properties": Alt+Enter on `IShape` of `Square` → Implement
    // missing members. EXPECT: `public string Name { get; set; }`. "prefer_throwing_properties" (the default): `get => throw new
    // NotImplementedException();` and `set => throw …`. Ctrl+Z.
    public class Square : IShape
    {
        public string Name { get; set; } = "";
    }

    public interface IShape
    {
        string Name { get; set; }
    }
}
