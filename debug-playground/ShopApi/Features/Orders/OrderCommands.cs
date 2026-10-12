using System.Text.Json;
using MediatR;
using Microsoft.EntityFrameworkCore;
using Shop.Api.Behaviors;
using Shop.Api.Data;
using Shop.Api.Domain;
using Shop.Api.Telemetry;

namespace Shop.Api.Features.Orders;

public sealed record OrderLineInput(string Sku, int Quantity);

public sealed record CreateOrder(long CustomerId, IReadOnlyList<OrderLineInput> Lines, string? Comment = null) : IRequest<long>, IValidatable
{
    public IEnumerable<string> Validate()
    {
        if (CustomerId <= 0) yield return "CustomerId must be positive";
        if (Lines.Count == 0) yield return "An order needs at least one line";
        foreach (var line in Lines.Where(line => line.Quantity <= 0)) yield return $"Quantity of {line.Sku} must be positive";
    }
}

public sealed class CreateOrderHandler(ShopDbContext db, ShopMetrics metrics, ILogger<CreateOrderHandler> logger) : IRequestHandler<CreateOrder, long>
{
    public async Task<long> Handle(CreateOrder request, CancellationToken cancellationToken)
    {
        var skus = request.Lines.Select(line => line.Sku).ToList();
        var products = await db.Products
            .Where(product => skus.Contains(product.Sku))
            .ToDictionaryAsync(product => product.Sku, cancellationToken);

        var missing = skus.Except(products.Keys).ToList();
        if (missing.Count > 0) throw new ValidationException(missing.Select(sku => $"Unknown product {sku}").ToList());

        var order = new Order
        {
            CustomerId = request.CustomerId,
            Comment = request.Comment,
            Lines = request.Lines.Select(line => new OrderLine
            {
                Sku = line.Sku,
                Title = products[line.Sku].Title,
                Price = products[line.Sku].Price,
                Quantity = line.Quantity,
            }).ToList(),
        };
        order.Place();

        db.Orders.Add(order);
        db.Outbox.Add(new OutboxMessage { Type = "OrderPlaced", Payload = JsonSerializer.Serialize(new { order.Id, order.CustomerId, order.Total }) });
        await db.SaveChangesAsync(cancellationToken);

        metrics.OrderPlaced(order);
        logger.LogInformation("Order {OrderId} placed for {CustomerId}: {Total:N2}", order.Id, order.CustomerId, order.Total);
        return order.Id;
    }
}

public sealed record CancelOrder(long OrderId, string Reason) : IRequest<bool>;

public sealed class CancelOrderHandler(ShopDbContext db, ShopMetrics metrics) : IRequestHandler<CancelOrder, bool>
{
    public async Task<bool> Handle(CancelOrder request, CancellationToken cancellationToken)
    {
        var order = await db.Orders.AsTracking().FirstOrDefaultAsync(order => order.Id == request.OrderId, cancellationToken);
        if (order is null || order.Status is OrderStatus.Shipped or OrderStatus.Cancelled) return false;

        var from = order.Status;
        order.Cancel(request.Reason);
        await db.SaveChangesAsync(cancellationToken);
        metrics.OrderCancelled(from);
        return true;
    }
}
