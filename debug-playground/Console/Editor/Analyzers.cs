namespace Playground.Editor;

/// <summary>
/// Live check of Roslyn analyzers without the language server (0.1.77, CSHARP_PSI_MIGRATION.md task D3): the helper of the plugin runs
/// the analyzers of the project — the package Microsoft.VisualStudio.Threading.Analyzers (VSTHRD*), the CA rules of the SDK and the IDE
/// rules of code style — with the severities of `Console/.editorconfig` (its section for this file turns a few of them into warnings).
/// They come in the background a second after a save (Settings | .NET | Analyzers and Generators → «Run analyzers in the background on
/// save»), and for the whole project from .NET → Code Analysis → Run Code Analysis (the list goes to the Build tool window). The server
/// off. Type on the empty line under a marker, save (Ctrl+S), check EXPECT, undo with Ctrl+Z and save.
/// </summary>
public class AnalyzerScenarios
{
    private int counter;

    // TYPE:an-on-save — no typing: open this file, wait a few seconds (the first run builds the helper: up to a minute once).
    // EXPECT: yellow warnings, each once, with the id first in the tooltip: «VSTHRD200: Use "Async" suffix in names of methods that
    // return an awaitable type» on `Load`, «CA1822: Member 'Twice' does not access instance data…» on `Twice`, «VSTHRD103: …» on
    // `File.ReadAllText` in Load. The tooltip ends with «Roslyn analyzer». NOT: the same warning twice (once from the last build).

    public async Task<string> LoadAsync(string path)
    {
        await Task.Yield();
        return await File.ReadAllTextAsync(path) + counter;
    }

    public static int Twice(int value) => value * 2;

    public int Count() => counter++;

    // TYPE:an-fix — Alt+Enter on `Load`.
    // EXPECT: «Rename to LoadAsync» (the fix of the package, applied by the helper): the method becomes `LoadAsync`; Alt+Enter on
    // `File.ReadAllText` → «Await ReadAllTextAsync instead» → `await File.ReadAllTextAsync(path)`; Alt+Enter on `Twice` → «Make static».
    // After each fix the warning is gone once the file is saved again.

    public void Unused()
    {
        counter--;
        // TYPE:an-ide — type `var value = 1; value = 2; Console.WriteLine(value);`, save.
        // EXPECT: «IDE0059: Unnecessary assignment of a value to 'value'» on the first `value = 1` (the code style rule of the SDK, a
        // warning by the .editorconfig section of this file); Alt+Enter → «Remove unnecessary value assignment».
    }

    // TYPE:an-project — no typing: .NET → Code Analysis → Run Code Analysis with this file open (or on Console in the Solution view).
    // EXPECT (0.1.82): the Build tool window «Code Analysis Console» says «0 errors, 3 warnings, N suggestions» (N about 170); under it
    // two nodes — «Warnings (3)» open with the 3 warnings of this file, «Suggestions (N)» collapsed (CA1822, IDE0060, IDE0028… of all
    // files of Console), whatever «Show suggestions» says; each row opens its place on double click.

    // TYPE:an-quiet (0.1.82) — no typing: open Editor/Overloads.cs and Editor/ContextActions.cs, save each once (Ctrl+S), wait a few seconds.
    // EXPECT: no green or gray waves from analyzers there (before 0.1.82: 18 and 19 weak warnings — IDE0060, CA1822…); only this file
    // shows its 3 warnings. Caret on the name of a method there that uses no instance data (CA1822, an Info): Alt+Enter still offers
    // «Make static». Settings | .NET | Analyzers and Generators → «Show suggestions» on → the Info ones come back as weak warnings
}
