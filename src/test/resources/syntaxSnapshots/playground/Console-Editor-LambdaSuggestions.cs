namespace Playground.Editor;

/// <summary>
/// Live check of the lambda offered where a delegate is expected (ROADMAP: «Лямбда там, где ждут делегат»): the first item of the
/// completion list and the gray inline text after `(` / `,`. Lines to type on are marked <c>// TYPE:name</c>: put the caret on the empty
/// line under the marker, type what the comment says, compare with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to
/// compile. Wait for the widget «Roslyn: DebugPlayground.sln» first: the parameter types come from the signature help of the server.
/// </summary>
public class LambdaSuggestions
{
    private readonly List<LambdaOrder> _orders = new();

    public event EventHandler? Changed;

    private void Each(Action<LambdaOrder> action) => _orders.ForEach(action);
    private void Sort(Comparison<LambdaOrder> comparison) => _orders.Sort(comparison);
    private void Register(Func<IServiceProvider, object> implementationFactory) => _ = implementationFactory;
    private void Retry(int attempts, Func<int, string, bool> shouldRetry) => _ = (attempts, shouldRetry);

    public void Use()
    {
        Each(order => order.Price++);
        Sort((x, y) => x.Price.CompareTo(y.Price));
        Register(serviceProvider => serviceProvider);
        Retry(3, (i, s) => i > 0 && s.Length > 0);
        Changed += (sender, e) => { };
        var first = _orders.FirstOrDefault(order => order.Price > 0);
        var prices = _orders.Select(order => order.Price).Where(d => d > 0).ToList();
        Console.WriteLine($"{first?.Price} {prices.Count}");

        // TYPE:lambda-action — `Each(`. EXPECT: gray `lambdaOrder => ` right after the parenthesis (the name comes from the type, as Rider names it);
        // Ctrl+Space: `lambdaOrder => ` first, the block form `lambdaOrder => { }` second, then the members of the server (`action`, `first`)

        // TYPE:lambda-func — `Register(`. EXPECT: gray `serviceProvider => `: the name comes from the type IServiceProvider

        // TYPE:lambda-two — `Retry(3, `. EXPECT: gray `(i, s) => `: the second parameter is Func<int, string, bool>, well-known types get one-letter names

        // TYPE:lambda-linq — `_orders.Where(`. EXPECT: gray `lambdaOrder => `; Ctrl+Space: `lambdaOrder => ` first, then `(lambdaOrder, i) => ` of the
        // second overload (Func<LambdaOrder, int, bool>), both above the variables and members of the server

        // TYPE:lambda-event — `Changed += `. EXPECT: no lambda here (not an argument); after `Changed += (` neither: the server gives no signature help for `+=`

        // TYPE:lambda-silent — `Console.WriteLine(`. EXPECT: NO lambda: no overload of WriteLine takes a delegate
    }
}

public class LambdaOrder
{
    public decimal Price { get; set; }
}
