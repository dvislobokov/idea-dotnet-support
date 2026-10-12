using Playground.Lib;

namespace Playground.Editor;

/// <summary>
/// Live check of usages, rename and the hierarchy of types and members across the solution without the language server (0.1.73, task C4b
/// of CSHARP_PSI_MIGRATION.md). Settings | .NET | Language Server → Source of Features → «Navigation» and «Rename» = Built-in (the
/// default); stop the server (or open the file before it is ready) to be sure the plugin answers. The other half is in
/// <c>Lib/SolutionShapes.cs</c> (another project). Put the caret where a <c>// TYPE:name</c> comment says, press the keys, compare with
/// EXPECT; after a rename Ctrl+Z (one step brings back every file). With «Language server» and the server ready the same keys go to the
/// server: the results must be the same. The file only has to compile.
/// </summary>
public class SolutionTile : SolutionSquare
{
    public SolutionTile() : base(1) { }

    // TYPE:solution-goto-super — caret on `Area` below, Ctrl+U. EXPECT: SolutionSquare.Area in Lib/SolutionShapes.cs opens (a single
    // target, no list). The gutter of this line has the «Overrides member» icon (up); the one of SolutionSquare.Area — «Implements member» and
    // «Is overridden»; the one of ISolutionFigure.Area — «Has implementations»
    public override double Area() => base.Area() / 2;
}

public static class SolutionUsages
{
    // TYPE:solution-find-usages — caret on `Side` in `square.Side = 3` below, Alt+F7. EXPECT: 7 usages in 2 projects — Lib (5): the
    // constructor (write), `Side * Side` (2 reads), `nameof(Side)` (Usage in nameof), the `cref` of the doc comment of ISolutionFigure;
    // Console (2): `square.Side = 3` (write) and `square.Side` in the return (read). Group by Usage Type shows Read / Write / nameof as with the
    // server. Ctrl+Alt+F7 (Show Usages): the same rows in a popup. No «Side» of the comments or strings of this file
    public static double Measure(ISolutionFigure figure)
    {
        var square = new SolutionSquare(2);
        square.Side = 3;
        // "Side" in a string and Side in this comment are not usages
        return figure.Area() + square.Area() + square.Side + 1;
    }

    // TYPE:solution-highlight — caret on `square` or on `Side` in Measure above and wait: every usage of the member in this file is
    // highlighted (writes in the write color); Ctrl+Shift+F7 does the same

    // TYPE:solution-goto-implementation — caret on `Area` in `figure.Area()` above, Ctrl+Alt+B. EXPECT: a list «Choose Implementation» with
    // SolutionSquare.Area (Lib) and SolutionTile.Area (this file); on `ISolutionFigure` in the parameter: SolutionSquare and SolutionTile

    // TYPE:solution-hierarchy — caret on `ISolutionFigure` above, Ctrl+H. EXPECT: the Hierarchy tool window, ISolutionFigure → SolutionSquare →
    // SolutionTile; the toolbar's Supertypes / Subtypes views. Caret on `Measure`, Ctrl+Alt+H: the callers (Run below); Callee view:
    // ISolutionFigure.Area, SolutionSquare (the `new`), SolutionSquare.Area

    // TYPE:solution-rename — caret on `Side` in Measure, Shift+F6, type `Edge`, Enter. EXPECT: the property in Lib/SolutionShapes.cs, its
    // constructor assignment, `Edge * Edge`, `nameof(Edge)`, the `cref` «SolutionSquare.Edge» and both uses here. Then Ctrl+Z
    // Caret on `Area` in SolutionTile, Shift+F6 → `Surface`: a dialog asks whether to rename the hierarchy (ISolutionFigure.Area,
    // SolutionSquare.Area): «Rename All» renames all three and `figure.Area()` / `square.Area()` here, «Only This» only SolutionTile.Area.
    // Caret on `SolutionTile`, Shift+F6 → `SolutionPlate`: the class, its constructor; the file keeps its name (it is not named after the type).
    // Caret on `Side`, Shift+F6 → `Label`: a conflict dialog («already has a member named 'Label'»)
    public static double Run() => Measure(new SolutionTile());
}
