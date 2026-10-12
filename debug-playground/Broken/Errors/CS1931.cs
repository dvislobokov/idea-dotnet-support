// CS1931: The range variable 'x' conflicts with a previous declaration of 'x'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1931;

public class Reports
{
    private int item;

    public void Parameters(int[] items, int item)
    {
        var doubled = from item in items select item * 2; // ERROR CS1931
        Console.WriteLine(doubled.Count() + item);
    }

    public void Locals(int[] items)
    {
        var total = items.Sum();
        var sums = from total in items select total; // ERROR CS1931
        var later = from size in items let count = size select count; // ERROR CS1931
        var count = 0;
        Console.WriteLine(sums.Count() + later.Count() + total + count);
    }

    public void Lambdas(int[] items)
    {
        Func<int, int> counter = limit => (from limit in items select limit).Count(); // ERROR CS1931
        Func<int, int> fine = limit => (from value in items where value < limit select value).Count();
        Console.WriteLine(counter(1) + fine(2));
    }

    public void Nested(int[] items)
    {
        var outer = from row in items select (from row in items select row).Count(); // ERROR CS1931
        var shadowed = from row in items let size = row select (from row in items select row).Count();
        Console.WriteLine(outer.Count() + shadowed.Count());
    }

    public void Fine(int[] items)
    {
        var query = from value in items select value;
        { var value = 1; Console.WriteLine(value); }
        var lambdas = from value in items where items.Any(value => value > 0) select value;
        var field = from item in items select item + this.item;
        Console.WriteLine(query.Count() + lambdas.Count() + field.Count());
    }
}
