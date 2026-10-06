// CS0200: Property or indexer 'x' cannot be assigned to -- it is read only. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0200;

public class Invoice
{
    public int Number { get; }
    public decimal Total => 10m;
    public string Note { get; set; } = "";
    public static int Count { get; }
    public int this[int line] => line;
    public string this[string code] { get => code; set { } }

    static Invoice() { Count = 0; }

    public Invoice(int number, Invoice other)
    {
        Number = number;             // a get-only auto-property is set in the constructor
        this.Number = number + 1;
        other.Number = 2; // ERROR CS0200
    }

    public void Renumber(int number)
    {
        Number = number; // ERROR CS0200
        Note = "renumbered";
    }
}

public class Clerk
{
    public void Edit(Invoice invoice, List<int> lines, string text)
    {
        invoice.Note = "ok";
        invoice.Total = 1; // ERROR CS0200
        invoice.Number++; // ERROR CS0200
        invoice["code"] = "A-1";     // the overload by string has a setter
        invoice[0] = 1; // ERROR CS0200
        invoice['c'] = 2; // ERROR CS0200
        lines.Capacity = 10;
        lines.Count = 0; // ERROR CS0200
        var length = text.Length;
        var copy = new Invoice(1, invoice) { Note = "copy" };
    }
}
