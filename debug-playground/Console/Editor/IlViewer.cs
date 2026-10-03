namespace Playground.Editor;

/// <summary>
/// Live check of the IL Viewer (menu .NET → IL Viewer, the tool window on the right). Build the solution first (Debug, the framework of the
/// toolbar), open the window, then put the caret where a comment <c>// IL:name</c> says and compare with EXPECT. Nothing here is called;
/// the file only has to compile. Edits asked for below are undone with Ctrl+Z.
/// </summary>
public class IlViewer
{
    // IL:il-field — caret on `_count` below. EXPECT: the IL of the field (`.field private int32 _count`), one item in the list at the top
    // (kind `type` or `method`, as the helper names it), no highlighted lines; no error, no banner
    private int _count;

    // IL:il-simple — caret on the line `var sum = a + b;`. EXPECT: the list at the top shows `...IlViewer::Add` (method); in the IL the lines
    // `ldarg.1 / ldarg.2 / add / stloc.0` are highlighted and scrolled into view; a click on `ldarg.2` highlights `var sum = a + b;` in this
    // editor and the caret here does not move; a click on `.maxstack` highlights nothing
    public int Add(int a, int b)
    {
        var sum = a + b;
        _count++;
        return sum;
    }

    // IL:il-overloads — caret in the body of the second `Scale` (two parameters). EXPECT: the IL of `Scale(int32, int32)` with `mul`, not of
    // `Scale(int32)`; move to the first `Scale` → after ~0.3 s the IL changes to the single-parameter one (`ldc.i4.2`)
    public int Scale(int value) => value * 2;

    public int Scale(int value, int factor) => value * factor;

    // IL:il-async — caret on the line `await Task.Delay(10);`. EXPECT: the list has the state machine `<LoadAsync>d__…::MoveNext` (state machine)
    // first and the stub `LoadAsync` (method) too; the highlighted lines are in `MoveNext` (a `call ... Task::Delay`); choosing `LoadAsync`
    // in the list shows the stub with `AsyncTaskMethodBuilder` and the choice stays while the caret moves inside this method
    public async Task<int> LoadAsync()
    {
        await Task.Delay(10);
        return _count;
    }

    // IL:il-iterator — caret on the line `yield return i * i;`. EXPECT: the `MoveNext` of `<Squares>d__…` (state machine) at the top of the
    // list, highlighted `mul`; the method `Squares` itself (it only creates the iterator) also in the list
    public IEnumerable<int> Squares(int count)
    {
        for (var i = 0; i < count; i++)
            yield return i * i;
    }

    // IL:il-lambda — caret inside the lambda `x => x > limit` (on `x > limit`). EXPECT: the lambda `<>c__DisplayClass…::<Filter>b__…` (lambda)
    // first, `Filter` (method) next; the highlighted lines are `ldarg.1 / ldarg.0 / ldfld limit / cgt`; caret on `return` of `Filter` → the
    // method first, with `newobj ... <>c__DisplayClass` and `ldftn`
    public int[] Filter(int[] values, int limit)
    {
        return values.Where(x => x > limit).ToArray();
    }

    // IL:il-local-function — caret on the line `return n <= 1 ? 1 : n * Factorial(n - 1);`. EXPECT: `<Compute>g__Factorial|…` (local function)
    // first, with a recursive `call` to itself; `Compute` (method) in the list too
    public int Compute(int n)
    {
        return Factorial(n);

        static int Factorial(int n)
        {
            return n <= 1 ? 1 : n * Factorial(n - 1);
        }
    }

    // IL:il-stale — caret in `Add`, then type a space after `a + b` (do not save). EXPECT: the yellow banner «Source changed after the last build»
    // with Build, the IL of the old build still under it; Ctrl+Z, Ctrl+S → the banner stays (the saved file is newer than the assembly);
    // Build in the banner → the Build window runs `Build Console.csproj`, after it the banner is gone and the IL is refreshed by itself
}

// IL:il-type-header — caret on the name `IlViewerHeader` below (the header, not inside a member). EXPECT: the IL of the class: `.class public
// auto ansi beforefieldinit Playground.Editor.IlViewerHeader extends [System.Runtime]System.Object`, colored: `.class` as a directive,
// `public auto ansi` as keywords, the base type as a type name; no highlighted lines
public class IlViewerHeader
{
    public override string ToString() => nameof(IlViewerHeader);
}

// IL:il-states — (1) open `README.md` of the playground: «Open a C# file to see the IL of the code at the caret»; (2) Clean Solution and
// come back here: «Build the project to see its IL» with a Build link, the link builds Console and the IL appears; (3) open a .cs file outside
// any project (File | New | Scratch File, C#): «The file is not a part of a .NET project»
