namespace Playground.Editor;

/// <summary>
/// Live check of what an argument list offers (ROADMAP: «Лямбда там, где ждут делегат», «Именованные аргументы»): the lambda first in the
/// completion list and as gray text after `(` / `,`, the lambda and «Create method» at `Changed += `, the named arguments `name:`, the
/// parameter info after a method chosen in the list. Lines to type on are marked <c>// TYPE:name</c>: put the caret on the empty line under
/// the marker, type what the comment says, compare with EXPECT, undo (Ctrl+Z). Nothing here is called, the file only has to compile.
/// Since 0.1.86 the plugin's own semantics answer (Settings | .NET | Language Server: Completion and Documentation = Built-in, the default);
/// the server need not run.
/// </summary>
public class LambdaSuggestions
{
    private readonly List<LambdaOrder> _orders = new();

    public event EventHandler? Changed;

    private void Each(Action<LambdaOrder> action) => _orders.ForEach(action);
    private void Sort(Comparison<LambdaOrder> comparison) => _orders.Sort(comparison);
    private void Register(Func<IServiceProvider, object> implementationFactory) => _ = implementationFactory;
    private void Retry(int attempts, Func<int, string, bool> shouldRetry) => _ = (attempts, shouldRetry);
    private void Place(int quantity, string name, bool urgent = false) => _ = (quantity, name, urgent);
    private void Place(int quantity, string name, bool urgent, DateTime when) => _ = (quantity, name, urgent, when);

    // TYPE:named-attribute — `[Obsolete(Di` on the empty line below. EXPECT: `DiagnosticId =` in the list (a property of ObsoleteAttribute);
    // `[Obsolete(` + Ctrl+Space: `message:` and `error:` (parameters of the constructors), `DiagnosticId =`, `UrlFormat =`

    private void Marked() { }

    public void Use()
    {
        Each(order => order.Price++);
        Sort((x, y) => x.Price.CompareTo(y.Price));
        Register(serviceProvider => serviceProvider);
        Retry(3, (i, s) => i > 0 && s.Length > 0);
        Place(1, "acme");
        Changed += (sender, e) => { };
        var first = _orders.FirstOrDefault(order => order.Price > 0);
        var prices = _orders.Select(order => order.Price).Where(d => d > 0).ToList();
        Console.WriteLine($"{first?.Price} {prices.Count}");

        // TYPE:lambda-action — `Each(`. EXPECT: gray `lambdaOrder => ` right after the parenthesis (the name comes from the type, as Rider names it);
        // Ctrl+Space: `lambdaOrder => ` first, the block form `lambdaOrder => { ... }` second, then the variables and members (`first`, `_orders`)

        // TYPE:lambda-func — `Register(`. EXPECT: gray `serviceProvider => `: the name comes from the type IServiceProvider

        // TYPE:lambda-two — `Retry(3, `. EXPECT: gray `(i, s) => `: the second parameter is Func<int, string, bool>, well-known types get one-letter names

        // TYPE:lambda-linq — `_orders.Where(`. EXPECT: gray `lambdaOrder => `; Ctrl+Space: `lambdaOrder => ` first, then `(lambdaOrder, i) => ` of the
        // second overload (Func<LambdaOrder, int, bool>), both above the variables and members

        // TYPE:lambda-event — `Changed += ` + Ctrl+Space. EXPECT: first `(sender, e) => {}` (Enter: `(sender, e) => { | };`, the caret in the
        // braces), second `Create method OnChanged(object?, EventArgs)` (Enter: `Changed += OnChanged;` and below `Use` a new
        // `private void OnChanged(object? sender, EventArgs e) { }`); no gray text here (not an argument)

        // TYPE:lambda-silent — `Console.WriteLine(`. EXPECT: NO lambda: no overload of WriteLine takes a delegate

        // TYPE:named-prefix — `Place(qu` + Ctrl+Space. EXPECT: `quantity:` first (Enter: `Place(quantity: |`); as in Rider's list

        // TYPE:named-next — `Place(3, ` + Ctrl+Space. EXPECT: `name:`, `urgent:`, `when:` in the list (low, after the values); NO `quantity:`
        // (taken by position); `Place(3, urgent: true, ` — no `urgent:` any more

        // TYPE:parameter-info — `Ret`, choose `Retry` in the list with Enter. EXPECT: `Retry(|);` and the parameter info opens by itself
        // (`int attempts, Func<int, string, bool> shouldRetry`) without Ctrl+P; then `3, ` gives the gray `(i, s) => `
    }
}

public class LambdaOrder
{
    public decimal Price { get; set; }
}
