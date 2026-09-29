namespace Playground.Editor;

/// <summary>
/// Live check of the order of the completion list and of the suggestion statistics (ROADMAP: «Порядок списка completion по контексту»,
/// «Статистика подсказок»). Lines to type on are marked <c>// TYPE:name</c>: put the caret on the empty line under the marker, type what
/// the comment says, compare the list with EXPECT. Nothing here is called, the file only has to compile: undo what was typed
/// (Ctrl+Z) before going on to the next marker. Wait for the widget «Roslyn: DebugPlayground.sln» first.
/// </summary>
public class CompletionRanking
{
    private readonly List<RankedOrder> _orders = new();
    private readonly string _title = "ranking";

    public int Count => _orders.Count;
    public string Name { get; set; } = "";

    public decimal Total(RankedOrder order) => order.Price * order.Quantity;

    private void Save(RankedOrder order, CancellationToken cancellationToken) => _orders.Add(order);
    private void Send(string message) => Console.WriteLine(message);
    private void Run(CancellationToken token) => token.ThrowIfCancellationRequested();

    public async Task<RankedOrder> Handle(RankedOrder order, string customerName, CancellationToken cancellationToken)
    {
        int count = 0;
        var text = "x";
        await Task.Yield();
        Save(order, cancellationToken);

        int amount =

        // TYPE:expected-type — `int amount = `. EXPECT: gray `count;` at once, Tab takes it. With Ctrl+Space instead: `count` first, then `Count`;
        // `customerName`, `text`, `_title` and the keywords are below

        // TYPE:method-by-type — `decimal sum = ` and Ctrl+Space. EXPECT: the method `Total` above the variables `count`, `customerName`, `text`

        // TYPE:parameter-name — `Save(`. EXPECT: gray `order, cancellationToken` right after the parenthesis, before the solution is loaded as well
        // (`Save` is declared in this file). After `order, ` — gray `cancellationToken`

        // TYPE:parameter-type — `Send(` and Ctrl+Space. EXPECT: the strings `customerName`, `text` (locals), then `_title`, `Name` above `count` and `order`

        // TYPE:partial-name — `Run(`. EXPECT: `cancellationToken` first: the parameter is called `token`

        // TYPE:assignment — `Name = ` and Ctrl+Space. EXPECT: strings first (`customerName`, `text`, `_title`), `count` below them

        // TYPE:return — `return `. EXPECT: gray `order;`: the method is async and returns Task<RankedOrder>, and `order` is the one RankedOrder at hand

        // TYPE:value-silent — `string label = `. EXPECT: NO gray text: `customerName`, `text`, `_title`, `Name` are all strings and none is called so.
        // `decimal sum = ` — no gray text either: `Total` is a method, its arguments are not guessed

        // TYPE:after-dot — `int amount = order.`. EXPECT: `Amount` first (the name); `Quantity` is an int as well and is NOT moved up:
        // the types of members of other types are not known to the plugin, only their names

        // TYPE:declared-nearby — `var copy = ` and Ctrl+Space. EXPECT: `text` and `count` (declared a few lines above) over the fields `_orders`, `_title`

        // TYPE:chosen-before — type `_or`, Enter (`_orders`), Ctrl+Z; three times. Then type `_` on the empty line.
        // EXPECT: `_orders` above `_title`. .NET | Suggestion Statistics | Reset brings the order of the server back

        Console.WriteLine($"{count} {text} {customerName} {_title} {cancellationToken.IsCancellationRequested}");
        return order;
    }

    // TYPE:stats-ghost — type `public string Title` under this comment, wait for the gray ` { get; set; }`, press Tab.
    // EXPECT in .NET | Suggestion Statistics: the row `auto-property` has shown 1, taken 1, 100%. Typing the name letter by letter counts as ONE shown.

    // TYPE:stats-list — after the markers above open .NET | Suggestion Statistics.
    // EXPECT: «Completion list: N chosen» with most of them at `position first`, and the reasons `expected type`, `name`,
    // `declared nearby` in the last block. Copy puts the report on the clipboard, Reset clears the numbers.
}

public class RankedOrder
{
    public int Amount { get; set; }
    public int Quantity { get; set; }
    public decimal Price { get; set; }
    public string Customer { get; set; } = "";
}
