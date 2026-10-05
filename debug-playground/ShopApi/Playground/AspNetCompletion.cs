using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using Shop.Api.Domain;

namespace Shop.Api.Playground;

// Strings of ASP.NET Core (0.1.93): message templates of logging, route templates, JSON, configuration keys and service registrations.
// Type on the empty line under a marker, look, then undo with Ctrl+Z: the file compiles as is.

/// <summary>Message templates of ILogger, Serilog and [LoggerMessage] (task 3.8).</summary>
public sealed partial class PlaygroundLogging(ILogger<PlaygroundLogging> logger)
{
    public void Placed(Order order, decimal total)
    {
        // EXPECT: in the line below `{OrderId}` and `{Total}` have the color of format items (as `{0}` of string.Format), the rest is string.
        logger.LogInformation("Order {OrderId} placed: {Total}", order.Id, total);

        // TYPE:shop-log-placeholder — type `logger.LogInformation("Order {` (the closing quote comes by itself).
        // EXPECT: the list opens by itself after `{`; nothing yet (no arguments); now finish the line as
        // EXPECT: `logger.LogInformation("Order {", order.Id, order.Customer);`, put the caret after `{` and press Ctrl+Space:
        // EXPECT: OrderId, Id first (from `order.Id`), then Customer / OrderCustomer; Enter writes `OrderId}`.

        // TYPE:shop-log-free — type `logger.LogWarning("Order {OrderId} by ", order.Id, order.Customer);`, caret after `by `, Ctrl+Space.
        // EXPECT: `{OrderCustomer}` (the argument no placeholder takes yet); Enter writes it into the template.

        // TYPE:shop-log-count — type `logger.LogError("Order {OrderId} failed: {Reason}", order.Id);`
        // EXPECT: a warning (yellow) on `{Reason}`: "No argument for the placeholder 'Reason' of the message template";
        // EXPECT: add `, total` — the warning goes; add one more argument — "The argument is not used in the message template" on it.

        // TYPE:shop-log-serilog — type `Serilog.Log.Information("User {Name} logged in", order.Customer);`
        // EXPECT: `{Name}` colored as a format item; with no argument after the template — a warning on `{Name}`.
    }

    // TYPE:shop-log-message — in the attribute below, put the caret after `{` of `{orderId}` and delete `orderId`, press Ctrl+Space.
    // EXPECT: orderId and reason (the parameters of the method, without the logger); `{orderId}` and `{reason}` colored;
    // EXPECT: rename `{reason}` to `{why}` — a warning "No method parameter for the placeholder 'why' of the message template".
    [LoggerMessage(EventId = 7001, Level = LogLevel.Warning, Message = "Order {orderId} cancelled: {reason}")]
    private static partial void Cancelled(ILogger logger, long orderId, string reason);

    public void Cancel(long orderId) => Cancelled(logger, orderId, "by the customer");
}

/// <summary>Route templates of attributes (task 3.7).</summary>
[ApiController]
[Route("api/playground/[controller]")]
public sealed class PlaygroundRoutesController : ControllerBase
{
    // EXPECT: in `{id:long}` the braces are format items, `id` has the color of a parameter, `long` of a method; `[controller]` above too.
    [HttpGet("{id:long}")]
    public IActionResult Get(long id) => Ok(id);

    // TYPE:shop-route-param — inside `""` of the attribute below type `{` .
    // EXPECT: the list opens by itself: code, page (parameters of the action; not `cancellationToken`, not `[FromServices]`), Enter writes `code}`.
    // TYPE:shop-route-constraint — then type `:` before `}`.
    // EXPECT: the list opens by itself: int, long, guid, bool, datetime, …, alpha, minlength(n), range(min,max), regex(expression);
    // EXPECT: `minlength` writes `minlength()` with the caret inside the parentheses.
    [HttpGet("by-code/")]
    public IActionResult ByCode(string code, int page, [FromServices] ILogger<PlaygroundRoutesController> log, CancellationToken cancellationToken) => Ok(code + page);

    // TYPE:shop-route-token — in `[Route("api/playground/[controller]")]` above, delete `controller]` and press Ctrl+Space after `[`.
    // EXPECT: action, area, controller.
}

/// <summary>Route templates of the minimal API and JSON in strings (task 3.7).</summary>
public static class PlaygroundEndpoints
{
    public static IEndpointRouteBuilder MapPlaygroundEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/playground");

        // TYPE:shop-map-param — between the quotes of the pattern below, after `/products/`, type `{`.
        // EXPECT: sku, version (the lambda's parameters; not `cancellationToken`); after `{sku:` the constraints.
        group.MapGet("/products/", (string sku, int version, CancellationToken cancellationToken) => Results.Ok(new { sku, version }));

        // EXPECT: `{id:guid}` colored: braces, `id` as a parameter, `guid` as a method.
        group.MapDelete("/drafts/{id:guid}", (Guid id) => Results.NoContent());

        return app;
    }

    public static string Sample()
    {
        // TYPE:shop-json — put the caret in the string below and press Alt+Enter.
        // EXPECT: the JSON of the string has the colors of JSON (keys, numbers, `true`); "Edit JSON Fragment" opens it in an editor;
        // EXPECT: remove a `}` — the JSON shows an error inside the string.
        // lang=json
        var json = """{ "sku": "A-1", "price": 12.5, "active": true }""";

        // EXPECT: JsonDocument.Parse takes JSON: `[1, 2, 3]` colored as JSON too.
        using var document = JsonDocument.Parse("[1, 2, 3]");
        return json + document.RootElement.GetArrayLength();
    }
}

/// <summary>Configuration keys and service registrations (task 3.9).</summary>
public static class PlaygroundConfiguration
{
    public static string? Read(IConfiguration configuration)
    {
        // TYPE:shop-config-key — type `var level = configuration["` .
        // EXPECT: the list opens by itself with the keys of appsettings.json: AllowedHosts, ConnectionStrings, Serilog, OTEL_EXPORTER_OTLP_ENDPOINT,
        // EXPECT: then the nested ConnectionStrings:Shop, Serilog:MinimumLevel:Default…, each with its value on the right.

        // TYPE:shop-config-connection — type `var cs = configuration.GetConnectionString("` .
        // EXPECT: Shop only (the names under ConnectionStrings).

        // TYPE:shop-config-section — type `var level = configuration.GetSection("Serilog")["` .
        // EXPECT: MinimumLevel, MinimumLevel:Default, MinimumLevel:Override… — relative to the section; no AllowedHosts.
        return configuration.GetValue<string>("AllowedHosts");
    }

    public static IServiceCollection AddPlaygroundServices(this IServiceCollection services)
    {
        // TYPE:shop-di-impl — type `services.AddScoped<IPriceCalculator, ` .
        // EXPECT: the list opens by itself: DiscountPriceCalculator, StandardPriceCalculator on top (implementations from the solution),
        // EXPECT: not the abstract PriceCalculatorBase; then the other types. Enter writes the name.
        services.AddSingleton<IPriceCalculator, StandardPriceCalculator>();
        return services;
    }
}

public interface IPriceCalculator
{
    decimal Price(Order order);
}

public abstract class PriceCalculatorBase : IPriceCalculator
{
    public abstract decimal Price(Order order);
}

public sealed class StandardPriceCalculator : PriceCalculatorBase
{
    public override decimal Price(Order order) => order.Total;
}

public sealed class DiscountPriceCalculator : IPriceCalculator
{
    public decimal Price(Order order) => order.Total * 0.9m;
}
