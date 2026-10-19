// RAZOR_PLAN.md, R0: the C# of a Blazor project sees the classes the Razor generator of the SDK makes of the .razor files — the helper of
// the plugin runs it with the other source generators (Dependencies → Analyzers → Microsoft.CodeAnalysis.Razor.Compiler). Completion and
// navigation see the components. The errors of a project with Razor are reported once the generator ran (0.1.196, R0b).
using Playground.Blazor.Components;
using Playground.Blazor.Components.Pages;

namespace Playground.Blazor.Editor;

public static class RazorGeneratedTypes
{
    // TYPE:razor-component-types — open this file and wait a few seconds (or .NET → Code Analysis → Refresh Generated Files).
    // EXPECT: Ctrl+B on `Counter` opens Counter_razor.g.cs of the generator (read-only), on `Step` — the property there; Solution view:
    // Blazor → Dependencies → Analyzers → Microsoft.CodeAnalysis.Razor.Compiler lists App_razor.g.cs, Counter_razor.g.cs, Routes_razor.g.cs.
    // On the empty line below type `public static string Members = nameof(Counter.` EXPECT: completion offers `Step` and `CountChanged`.
    // Ctrl+Z.
    public static Type Component = typeof(Counter);

    // TYPE:razor-errors-after-generation — once the generator ran (Solution view: Blazor → Dependencies → .NET 9.0 → Analyzers →
    // Microsoft.CodeAnalysis.Razor.Compiler → RazorSourceGenerator lists the *_razor.g.cs; about 4 s after the file opens), on the empty
    // line below type `public static object Probe = Counter.Nope + UndefinedThing();` EXPECT, without saving: `Nope` red, CS0117 "'Counter'
    // does not contain a definition for 'Nope'", and `UndefinedThing` red, CS0103 — the same as the line gives in Lib/Pricing.cs. Before the
    // generator ran (right after opening the solution, nothing listed yet) neither is red: the project stays quiet until it knows its
    // generated types. The debug log of the analysis (Settings | .NET, on by default; .NET → Plugin Logs, category `semantic`) shows no line
    // for them — these are decisions, not refusals. Ctrl+Z.
    // (Checked by the UI robot in WSL on 0.1.196: the generator, the tree and Ctrl+B worked; the errors did not show because an unsaved
    // edit of a .cs made the project "stale" — fixed in 0.1.198 for generators that read no C#.)
    public static Type Root = typeof(App);
    public static string Parameter = nameof(Counter.Step);

    // TYPE:razor-edit-regenerates — in Components/Pages/Counter.razor add `[Parameter] public int Max { get; set; }` to @code, do not save.
    // Then on the empty line below type `public static string Max = nameof(Counter.` EXPECT: completion offers `Max` about a second after
    // the edit (0.1.288: the plugin writes the class itself after a pause in typing; before, only the helper did, after a save); Ctrl+B on
    // it opens the new property in Counter_razor.g.cs. An edit of the markup of Counter.razor alone does not write the class again (the
    // plugin's log, .NET → Plugin Logs: no "written again after an edit"). Ctrl+Z in both files.

    public static Task Click(Counter counter) => counter.CountChanged.InvokeAsync(1);
}
