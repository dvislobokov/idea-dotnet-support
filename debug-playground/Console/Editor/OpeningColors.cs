using System.Text;

namespace Playground.Editor;

/// <summary>
/// Live check of the colors as a C# file opens (0.1.84). Nothing is typed here: close this tab (and Scenarios.cs, the biggest file of the
/// playground), wait a little, open them again from the Solution view or with Ctrl+Shift+N, and watch the first moment the text is there.
/// The robot measures it: <c>tools/ui-robot/scripts/color_timing.js</c> (times from the open to the first identifier color, pictures of the
/// editor at the first moment, at the first color and at the end).
/// </summary>
public sealed class OpeningInvoice(string customer)
{
    // TYPE:colors-on-open — close the tab, open it again. EXPECT: `OpeningInvoice` / `OpeningLine` / `OpeningTotals` (class / record / struct
    // colors), `customer` (primary constructor parameter), `Lines`, `Customer`, `Total` (properties), `Add`, `Describe`, `Totals` (methods),
    // `line`, `sum`, `builder` (locals), `{0}` / `{1,8:N2}` (format items) and the gray inactive `#if` text below come with the keywords and
    // strings, in the same frame — no moment with plain white names, and nothing changes color half a second later. The same on Scenarios.cs.
    // NOT EXPECTED: names white for a moment and then colored; colors that blink when the "Analyzing..." of the editor ends.
    private readonly List<OpeningLine> _lines = [];
    private const int MaxLines = 100;

    public string Customer => customer;
    public IReadOnlyList<OpeningLine> Lines => _lines;
    public decimal Total => Totals().Net + Totals().Tax;

    public void Add(string product, int quantity, decimal price)
    {
        if (_lines.Count >= MaxLines) throw new InvalidOperationException("too many lines");
        var line = new OpeningLine(product, quantity, price);
        _lines.Add(line);
    }

    public OpeningTotals Totals()
    {
        decimal sum = 0;
        foreach (var line in _lines) sum += line.Quantity * line.Price;
        return new OpeningTotals(sum, Math.Round(sum * 0.2m, 2));
    }

    public string Describe()
    {
        var builder = new StringBuilder();
        builder.AppendFormat("{0}: {1,8:N2}", Customer, Total).AppendLine();
        foreach (var line in _lines) builder.AppendLine(Format(line));
#if NEVER_DEFINED_IN_THE_PLAYGROUND
        builder.AppendLine("this text is inactive: gray from the first frame");
#endif
        return builder.ToString();

        static string Format(OpeningLine line) => string.Format("{0} x{1}", line.Product, line.Quantity);
    }
}

public sealed record OpeningLine(string Product, int Quantity, decimal Price);

public readonly record struct OpeningTotals(decimal Net, decimal Tax);
