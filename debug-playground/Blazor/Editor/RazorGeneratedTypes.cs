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

    // TYPE:razor-errors-after-generation — once the generator ran (the list of *_razor.g.cs above is there), on the empty line below type
    // `public static string Missing = nameof(Counter.Nope);` EXPECT: `Nope` is red, CS0117 "'Counter' does not contain a definition for
    // 'Nope'" — the same as in Console. Then type `UndefinedThing();` on the next line EXPECT: red CS0103. Before the generator ran (right
    // after opening the solution, no *_razor.g.cs listed yet) neither is red: the project stays quiet until it knows its generated types.
    // The debug log of the analysis (Settings | .NET, on by default; .NET → Plugin Logs, category `semantic`) shows no line for them —
    // these are decisions, not refusals. Ctrl+Z twice.
    public static Type Root = typeof(App);
    public static string Parameter = nameof(Counter.Step);

    // TYPE:razor-edit-regenerates — in Components/Pages/Counter.razor add `[Parameter] public int Max { get; set; }` to @code and save
    // (Ctrl+S). Then on the empty line below type `public static string Max = nameof(Counter.` EXPECT: completion offers `Max` a few seconds
    // after the save; Ctrl+B on it opens the new property in Counter_razor.g.cs. Ctrl+Z in both files, save.

    public static Task Click(Counter counter) => counter.CountChanged.InvokeAsync(1);
}
