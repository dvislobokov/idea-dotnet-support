using System.Diagnostics;
using System.Text.RegularExpressions;
using MediatR;
using Microsoft.EntityFrameworkCore;
using Shop.Api.Data;
using Shop.Api.Domain;
using Shop.Api.Features.Orders;
using Shop.Api.Telemetry;

namespace Shop.Api.Playground;

// A tour of code completion on a real service: every marker says what to type on the empty line under it and what the list must show.
// Type, look, then undo with Ctrl+Z: the file compiles as is. The markers go in the order of the versions that brought the feature.

/// <summary>Overrides of a framework base class (0.1.85).</summary>
public sealed class ReportWorker(IServiceScopeFactory scopes, ILogger<ReportWorker> logger) : BackgroundService
{
    // TYPE:shop-override — type `override ` (with the space).
    // EXPECT: the list opens by itself: ExecuteAsync, StartAsync, StopAsync, Dispose, ToString…; Enter writes the whole method with base call.
    // EXPECT: Ctrl+O offers the same; Alt+Enter on the class name and on `BackgroundService` offers "Generate overrides".

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<ShopDbContext>();

        // TYPE:shop-dbset — type `db.`
        // EXPECT: Customers, Orders, OrderLines, Products, Outbox first (members of ShopDbContext), then DbContext's: SaveChangesAsync, Database, Set…
        // EXPECT: no CS1061 on `db.Orders` anywhere in this file.
        db.OrderLines.Add(entity: new OrderLine()
        {

        })
        // TYPE:shop-lambda — type `db.Orders.Where(`
        // EXPECT: the list opens by itself with `order => ` / `o => ` on top (a lambda place); a space after `o` does not replace it.

        // TYPE:shop-ef-async — type `await db.Products.ToLi`
        // EXPECT: ToListAsync (EF Core extension, its using is already here), ToList, ToLookup…; Enter writes `ToListAsync()`.

        // TYPE:shop-include — type `db.Customers.Include(c => c.`
        // EXPECT: Orders, Name, Email, Phone, Id, CreatedAt, UpdatedAt — members of Customer.

        var orders = await db.Orders.Where(order => order.Status == OrderStatus.Placed).ToListAsync(stoppingToken);

        // TYPE:shop-foreach-postfix — type `orders.foreach` and press Tab.
        // EXPECT: `foreach (var order in orders) { }`, the name is `order`, not `item`; `orders.for` gives `i < orders.Count`.

        // TYPE:shop-suggestion — type `foreach (var o` then a space.
        // EXPECT: the list (if any) is not focused: the space keeps `o`, Enter does not insert a type name.

        foreach (var order in orders)
        {
            // TYPE:shop-enum — type `if (order.Status == `
            // EXPECT: the list opens by itself, OrderStatus.Draft / Placed / Paid / Shipped / Cancelled first.

            // TYPE:shop-is-pattern — type `if (order is { `
            // EXPECT: members of Order: CustomerId, Customer, Status, Comment, Lines, Total, Id, CreatedAt, UpdatedAt.

            // TYPE:shop-var-postfix — type `order.Total.var` and press Tab.
            // EXPECT: `var total = order.Total;` (the name from the member).

            logger.LogInformation("Order {OrderId}: {Total}", order.Id, order.Total);
        }
    }
}

/// <summary>Commands, object initializers and expected types (0.1.86–0.1.88).</summary>
public sealed class CheckoutService(IMediator mediator, ShopDbContext db, ShopMetrics metrics)
{
    public async Task<long> Checkout(long customerId, CancellationToken cancellationToken)
    {
        // TYPE:shop-initializer — type `var draft = new Order { ` then Ctrl+Space.
        // EXPECT: only properties not yet assigned: CustomerId, Customer, Status, Comment, Lines, Id, CreatedAt, UpdatedAt; no Total (read-only).

        // TYPE:shop-named-args — type `await mediator.Send(new ListOrders(` then Ctrl+Space.
        // EXPECT: named arguments Status:, CustomerId:, Page:, Size:; Ctrl+P shows the constructor `(OrderStatus? Status, long? CustomerId, int Page = 1, int Size = 20)`.

        // TYPE:shop-await — type `var page = mediator.Send(new ListOrders(null, customerId)).aw` and press Tab.
        // EXPECT: postfix `.await` (only on tasks): `var page = await mediator.Send(...)`.

        // TYPE:shop-throw-new — type `throw new `
        // EXPECT: exceptions only: InvalidOperationException, ArgumentException, ValidationException (ours)…; no Order, no OrderView.

        var command = new CreateOrder(customerId, [new OrderLineInput("SKU-1", 2)], Comment: "tour");
        try
        {
            return await mediator.Send(command, cancellationToken);
        }
        // TYPE:shop-catch — type `catch (` on the line below this comment.
        // EXPECT: exceptions first: DbUpdateException, OperationCanceledException, ValidationException…
        catch (DbUpdateException)
        {
            metrics.OrderCancelled(OrderStatus.Draft);
            return 0;
        }
    }

    public async Task<OrderView?> Find(long id)
    {
        // TYPE:shop-import — type `JsonSeri` (no `using System.Text.Json` in this file).
        // EXPECT: JsonSerializer from System.Text.Json; Enter adds the using at the top.

        // TYPE:shop-smart — type `OrderView? view = ` then Ctrl+Shift+Space.
        // EXPECT: only what is an OrderView: `await Find(…)`, `null`, `new OrderView(…)`; Ctrl+Space shows everything.

        return await mediator.Send(new GetOrder(id));
    }

    public Task<int> CountPlaced() => db.Orders.CountAsync(order => order.Status == OrderStatus.Placed);
}

/// <summary>Strings, formats, regular expressions, directives and doc comments (0.1.90).</summary>
public static partial class Receipt
{
    private static readonly Regex SkuPattern = new(@"^(?<group>[A-Z]{3})-(?<number>\d{1,6})$", RegexOptions.Compiled);

    // TYPE:shop-regex — look at SkuPattern above and GeneratedSku below.
    // EXPECT: the pattern is colored as RegExp (groups, classes, quantifiers), the rest of the string keeps the string color; Alt+Enter → Check RegExp.
    [GeneratedRegex(@"^SKU-\d+$", RegexOptions.IgnoreCase)]
    private static partial Regex GeneratedSku();

    public static string Line(OrderLine line, DateTimeOffset placedAt)
    {
        // TYPE:shop-hole — type `var text = $"{line.`
        // EXPECT: members of OrderLine: Sku, Title, Price, Quantity, OrderId…; nothing in the plain text of the string.

        // TYPE:shop-format — type `var price = $"{line.Price:`
        // EXPECT: number formats with examples: C, N2, F2, P, 0.00…; `$"{placedAt:` gives date formats: d, D, yyyy-MM-dd, o…

        // TYPE:shop-tostring — type `placedAt.ToString("`
        // EXPECT: date formats, the same as above.

        var valid = SkuPattern.IsMatch(line.Sku) || GeneratedSku().IsMatch(line.Sku);
        return $"{line.Title,-30} {line.Quantity,3} x {line.Price,10:N2}{(valid ? "" : " (?)")}";
    }

    // TYPE:shop-directive — type `#` at the very start of the empty line below.
    // EXPECT: the list opens by itself: if, region, pragma, nullable…; after `#if ` — DEBUG, TRACE, NET9_0…; after `#pragma warning disable ` — CS codes.

    // TYPE:shop-doc — on the empty line below type `/// <`
    // EXPECT: summary, param, returns, see, inheritdoc…; `/// <param name="` offers only `order` and `culture`.

    public static string Total(Order order, IFormatProvider? culture = null) => order.Total.ToString("N2", culture);
}

/// <summary>Telemetry: ActivitySource, metrics and live templates (0.1.89).</summary>
public sealed class TourTelemetry
{
    public void Trace(Order order)
    {
        using var activity = ShopTelemetry.Source.StartActivity("tour");

        // TYPE:shop-activity — type `activity?.Set`
        // EXPECT: SetTag, SetStatus, SetBaggage, SetParentId, SetCustomProperty…

        // TYPE:shop-template — on an empty line inside this class (outside the method) type `ctorf` and press Tab.
        // EXPECT: a constructor from fields; `prop` + Tab gives a property, `propg` a get-only one.

        activity?.SetTag("order.id", order.Id);
        Debug.Assert(order.Lines.Count > 0);
    }
}
