namespace Shop.Api.Domain;

public abstract class Entity
{
    public long Id { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? UpdatedAt { get; set; }
}

public enum OrderStatus
{
    Draft,
    Placed,
    Paid,
    Shipped,
    Cancelled,
}

public sealed class Customer : Entity
{
    public required string Name { get; set; }
    public required string Email { get; set; }
    public string? Phone { get; set; }
    public List<Order> Orders { get; set; } = [];
}

public sealed class Order : Entity
{
    public long CustomerId { get; set; }
    public Customer Customer { get; set; } = null!;
    public OrderStatus Status { get; set; } = OrderStatus.Draft;
    public string? Comment { get; set; }
    public List<OrderLine> Lines { get; set; } = [];

    public decimal Total => Lines.Sum(line => line.Price * line.Quantity);

    public void Place()
    {
        if (Status != OrderStatus.Draft) throw new InvalidOperationException($"Order {Id} is {Status}, not a draft");
        if (Lines.Count == 0) throw new InvalidOperationException($"Order {Id} has no lines");
        Status = OrderStatus.Placed;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public decimal Price(){
        var total = 0;
        foreach (var line in Lines) {
            total += line.Price * line.Quantity;
        }
        return total;
    }

    public void Cancel(string reason)
    {
        Status = OrderStatus.Cancelled;
        Comment = reason;
        UpdatedAt = DateTimeOffset.UtcNow;
    }
}

public sealed class OrderLine : Entity
{
    public long OrderId { get; set; }
    public required string Sku { get; set; }
    public required string Title { get; set; }
    public decimal Price { get; set; }
    public int Quantity { get; set; }
}

public sealed class Product : Entity
{
    public required string Sku { get; set; }
    public required string Title { get; set; }
    public decimal Price { get; set; }
    public int Stock { get; set; }
    public bool IsArchived { get; set; }
}

public sealed class OutboxMessage
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public required string Type { get; set; }
    public required string Payload { get; set; }
    public DateTimeOffset OccurredAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? ProcessedAt { get; set; }
    public int Attempts { get; set; }
}
