using System;
using System.Collections.Generic;

namespace Playground.Editor;

/// <summary>
/// Live check of Rider's popups of the caret in a C# file (0.1.70): Refactor This (Ctrl+Alt+Shift+T), Navigate To (Ctrl+Shift+G) and
/// Generate (Alt+Insert), and of the menu of the editor (right click). Put the caret where a <c>// TYPE:name</c> marker says, press the keys,
/// compare with EXPECT, close the popup with Escape. Nothing is typed, so nothing is to undo; if a row was chosen, Ctrl+Z puts the file back.
/// The rows of the language server (Extract method, Change signature, Generate constructor...) are there only when the widget
/// «Roslyn: DebugPlayground.sln» is ready; the plugin's own rows (Rename, Introduce / Inline Variable, Declaration...) always are.
/// Compare the look with Rider's pictures: docs/rider-analysis/img/24-generate-alt-insert.png, 25-refactor-this.png, 26-navigate-to.png.
/// </summary>
public interface IPopupShape
{
    double Area();
}

public class PopupCircle : IPopupShape
{
    private readonly double _radius;
    private readonly string _name;

    // TYPE:generate-in-class — caret on `PopupCircle` of the class declaration above, Alt+Insert. EXPECT: one popup «Generate» at once (no
    // second «Generate...» row to click through): with the server ready, its generators in Rider's order — «Generate constructor
    // 'PopupCircle()'» first, then «Unit Test», «Extract interface...», «Add 'DebuggerDisplay' attribute»; after a separator the rest of
    // the Generate group (Insert New GUID). Rows that cannot be generated here are not listed (no greyed Rider rows).
    // On the empty line below the server offers nothing: «Unit Test» and Insert New GUID only (Rider would list all its generators).

    public double Area() => Math.PI * _radius * _radius;

    // TYPE:refactor-method — caret on `Describe` below, Ctrl+Alt+Shift+T. EXPECT: popup «Refactor This» with «Rename...» first (Shift+F6
    // shown at the right); with the server ready also its refactorings here: «Move 'PopupCircle' to PopupCircle.cs», «Extract base
    // class...»; then Move File... / Copy File...; no Java rows, no greyed rows. NOT expected: «Introduce Variable», «Inline Variable»
    // (the caret is on a declaration, not on an expression or a local). Change signature is not there: the server has no such action.
    public string Describe(int digits)
    {
        // TYPE:refactor-inline — caret on `area` of the declaration below, Ctrl+Alt+Shift+T. EXPECT: «Inline Variable» among the rows
        // (the server's «Inline temporary variable» while it is in charge of context actions; choosing it puts `Area()` into the
        // `return`; Ctrl+Z back), «Rename...» too, the server's «Introduce local», «Introduce parameter for 'Area()'».
        var area = Area();
        // TYPE:refactor-introduce — select `Math.Round(area, digits)` below (Ctrl+W three times on `Math`), Ctrl+Alt+Shift+T. EXPECT:
        // «Introduce Variable» (Built-in context actions; choosing it writes `var round = Math.Round(area, digits);` above with the name
        // in a template); with the server ready its rows of the selection: «Introduce local for 'Math.Round(area, digits)'», «Extract
        // method», «Extract local function». Ctrl+Z (twice) back.
        return _name + ": " + Math.Round(area, digits);
    }
}

public static class PopupUsages
{
    public static double Total(List<IPopupShape> shapes)
    {
        double total = 0;
        foreach (var shape in shapes)
        {
            // TYPE:navigate-call — caret on `Area` of `shape.Area()` below, Ctrl+Shift+G. EXPECT: popup «Navigate To» with Rider's rows:
            // «Declaration» (Ctrl+B), «Implementation» (Ctrl+Alt+B), then «Base Symbols», «Find Usages», «Related Files», «Type of Symbol»,
            // «Show Usages»; then «Type Hierarchy», «Call Hierarchy», «IL Code»; at the end the file manager row («Explorer» / «Files»).
            // «Declaration» goes to IPopupShape.Area. The same list from the main menu: Navigate → Navigate To....
            total += shape.Area();
        }
        return total;
    }

    // TYPE:editor-menu — right click on `Total` above. EXPECT, in this order as in Rider: Show Context Actions, Cut / Copy / Paste, Copy /
    // Paste Special, Column Selection Mode, Find in Files, Find Usages, «Find Usages Settings...» (Rider's Find Usages Advanced) right
    // after it, Go To ▸, Folding ▸, «Inspect ▸» (Call Hierarchy, Type Hierarchy, IL Code), Rename..., Refactor ▸ (its first row is
    // «Refactor This...»), «Generate Code...», the Run / Debug rows, Open In ▸, Local History ▸, «Quick Definition», Compare with Clipboard.
    // NOT expected in a .txt or .json file: Find Usages Settings from this plugin, Inspect, Quick Definition from this plugin.
}
