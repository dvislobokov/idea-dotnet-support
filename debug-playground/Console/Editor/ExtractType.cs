namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.174 (Jellyfin № 52): Refactor This (Ctrl+Alt+Shift+T) on a class lists "Extract Interface", "Extract Superclass",
/// "Extract Class", as Rider. Each opens a dialog: the name, "Put in a new file", the members (and "Make abstract" for the superclass).
/// Put the caret where the marker says, run the row, compare with EXPECT, undo (Ctrl+Z; delete the created file).
/// </summary>
// TYPE:extract-interface — caret on `ExtractedService` below, Refactor This → "Extract Interface", keep `Save`, `Name`, `Changed`.
// EXPECT: a new file IExtractedService.cs with `public interface IExtractedService { void Save(int id); string Name { get; } event EventHandler? Changed; }`
// (the doc comment of Save copied), `ExtractedService : IExtractedService`; `Helper` (private) is not in the list.
public class ExtractedService
{
    /// <summary>Saves the item.</summary>
    public void Save(int id) => Helper(id);

    public string Name { get; private set; } = "";

    public event EventHandler? Changed;

    private void Helper(int id) => Changed?.Invoke(this, EventArgs.Empty);
}

// TYPE:extract-superclass — caret on `ExtractedReport`, Refactor This → "Extract Superclass", choose `_count`, `Add`, `Title` with
// "Make abstract" on `Title`. EXPECT: ExtractedReportBase.cs with `public abstract class ExtractedReportBase` holding
// `protected int _count;`, `Add` and `public abstract string Title();`; `ExtractedReport : ExtractedReportBase` with
// `public override string Title()`, `Bump` made `protected` (the moved `Add` uses it), `Total` still compiling.
public class ExtractedReport
{
    private int _count;

    public void Add() => _count = Bump(_count);

    public string Title() => "report";

    private static int Bump(int x) => x + 1;

    public int Total() => _count;
}

// TYPE:extract-class — caret on `ExtractedCart`, Refactor This → "Extract Class", name `Totals`, choose `_count`, `_sum`, `Add`.
// EXPECT: Totals.cs with the two fields and `Add` made public; the cart gets `private readonly Totals _totals = new();` and `Buy`
// calls `_totals.Add(price)` and prints `_totals._sum`. Choosing `_count` and `Total` alone is refused (Total uses `_sum`).
public class ExtractedCart
{
    private int _count;
    private decimal _sum;

    private void Add(decimal price)
    {
        _count++;
        _sum += price;
    }

    public void Buy(decimal price)
    {
        Add(price);
        Console.WriteLine(this._sum + _count);
    }
}
