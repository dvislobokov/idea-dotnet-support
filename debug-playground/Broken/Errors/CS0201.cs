// CS0201: Only assignment, call, increment, decrement, await, and new object expressions can be used as a statement. Lines marked
// `// ERROR CSxxxx` must show that error in the editor (and in `dotnet build`), every other line no error at all:
// tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0201;

public class StmtCart
{
    public int Count { get; set; }
    public int Total() => Count * 2;
}

public class StatementCases
{
    private int _seen;

    public async Task Run(int count, StmtCart cart, int[] items, string name)
    {
        count + 1; // ERROR CS0201
        count == 2; // ERROR CS0201
        cart.Count; // ERROR CS0201
        cart.Total; // ERROR CS0201
        _seen; // ERROR CS0201
        "done"; // ERROR CS0201
        items[0]; // ERROR CS0201
        (cart.Total()); // ERROR CS0201
        count += 1;
        count++;
        cart.Total();
        new StmtCart();
        await Task.Delay(1);
        _seen = cart.Count;
        Console.WriteLine(name);
    }
}
