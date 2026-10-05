namespace Playground.Editor;

/// <summary>
/// Live check of «Import type» and the semantic errors of the built-in diagnostics (0.1.74, CSHARP_PSI_MIGRATION.md task C4c): Settings |
/// .NET | Language Server → Source of Features → «Errors and warnings» = Built-in (the default). Type on the empty line under a marker, check
/// EXPECT, undo with Ctrl+Z until the file is as it was (it compiles as it is). The errors come from the plugin's resolver and the index of
/// assemblies, not from a build or the server: they are there before «Roslyn: DebugPlayground.sln» appears. The project has ImplicitUsings
/// (`System`, `System.IO`, `System.Linq`, `System.Collections.Generic`, `System.Threading.Tasks` need no `using`).
/// </summary>
public class ImportType
{
    public void Statements()
    {
        // TYPE:import-type-hint — type `var sw = Stopwatch.StartNew();`.
        // EXPECT: «CS0103: The name 'Stopwatch' does not exist in the current context» under `Stopwatch` at once, and a blue hint
        // «System.Diagnostics.Stopwatch? Alt+Enter» over it (the platform's auto-import hint, as in Rider); Alt+Enter adds
        // `using System.Diagnostics;` at the top of the file (before `namespace`) and the red goes away. NOT: a second red mark from the
        // server or from the last build for the same error.

        // TYPE:import-type-declaration — type `StringBuilder builder = new StringBuilder();`.
        // EXPECT: «CS0246: The type or namespace name 'StringBuilder' could not be found (…)» on the first `StringBuilder` and CS0246 on the
        // second; Alt+Enter on either → «Import 'System.Text.StringBuilder'» → `using System.Text;` added, both marks gone.

        // TYPE:import-type-choice — type `Canvas canvas = null!;` (two classes `Canvas` in ImportTypeTargets.cs).
        // EXPECT: CS0246 on `Canvas`, no blue hint (there is a choice); Alt+Enter → «Import type 'Canvas'…» opens a list:
        // Playground.ImportTargets.Drawing, Playground.ImportTargets.Printing; the chosen one is added as `using`. Escape adds nothing.
        // NOT an error: `Timer timer = null!;` — System.Threading is an implicit using of the project.

        // TYPE:import-type-extension — type `new List<int>().AsReadOnly2();`.
        // EXPECT: CS1061 «'List<int>' does not contain a definition for 'AsReadOnly2' and no accessible extension method …» — no import is
        // offered (there is no such method anywhere).

        // TYPE:import-type-silent — type `dynamic d = 1; d.Anything();` and `var t = (a: 1, b: "x"); var s = t.b;`.
        // EXPECT: NO red: members of `dynamic` and the named elements of a tuple are never reported.

        // TYPE:import-type-member — type `Console.WriteLin("x");`.
        // EXPECT: «CS0117: 'Console' does not contain a definition for 'WriteLin'» on `WriteLin`; Ctrl+Space after `WriteLin` still
        // completes `WriteLine`.

        // TYPE:import-type-arguments — type `Math.Max(1);`.
        // EXPECT: «CS1501: No overload for method 'Max' takes 1 arguments» on `Max`; `Math.Max(1, 2);` — no red.

        // TYPE:import-type-conversion — type `int count = "three";`.
        // EXPECT: «CS0029: Cannot implicitly convert type 'string' to 'int'» on `"three"`; `int fromDouble = 1.5;` — «CS0266 … An explicit
        // conversion exists (are you missing a cast?)».

    }

    // TYPE:import-type-unused — put the caret at the very start of the file (before `namespace`), type `using System.Text;` and Enter.
    // EXPECT: the directive turns gray at once, the tooltip «CS8019: Unnecessary using directive.»; Alt+Enter → «Remove unused directives
    // in file» removes it. Type `using System.IO;` instead: gray too, «CS8933: The using directive for 'System.IO' appeared previously as
    // global using» (an implicit using of the project). Ctrl+Z.
}
