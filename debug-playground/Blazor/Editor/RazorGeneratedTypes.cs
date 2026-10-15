// RAZOR_PLAN.md, R0: the C# of a Blazor project sees the classes the Razor generator of the SDK makes of the .razor files — the helper of
// the plugin runs it with the other source generators (Dependencies → Analyzers → Microsoft.CodeAnalysis.Razor.Compiler). Completion and
// navigation see the components. The errors of a project with Razor stay silent until R0b (false errors of the core first).
using Playground.Blazor.Components;
using Playground.Blazor.Components.Pages;

namespace Playground.Blazor.Editor;

public static class RazorGeneratedTypes
{
    // TYPE:razor-component-types — open this file and wait a few seconds (or .NET → Code Analysis → Refresh Generated Files).
    // EXPECT: Ctrl+B on `Counter` opens Counter_razor.g.cs of the generator (read-only), on `Step` — the property there; Solution view:
    // Blazor → Dependencies → Analyzers → Microsoft.CodeAnalysis.Razor.Compiler lists App_razor.g.cs, Counter_razor.g.cs, Routes_razor.g.cs.
    // On the empty line below type `public static string Members = nameof(Counter.` EXPECT: completion offers `Step` and `CountChanged`.
    // Not yet (R0b): `nameof(Counter.Nope)` is not red, the project with Razor stays silent. Ctrl+Z.
    public static Type Component = typeof(Counter);
    public static Type Root = typeof(App);
    public static string Parameter = nameof(Counter.Step);

    // TYPE:razor-edit-regenerates — in Components/Pages/Counter.razor add `[Parameter] public int Max { get; set; }` to @code and save
    // (Ctrl+S). Then on the empty line below type `public static string Max = nameof(Counter.` EXPECT: completion offers `Max` a few seconds
    // after the save; Ctrl+B on it opens the new property in Counter_razor.g.cs. Ctrl+Z in both files, save.

    public static Task Click(Counter counter) => counter.CountChanged.InvokeAsync(1);
}
