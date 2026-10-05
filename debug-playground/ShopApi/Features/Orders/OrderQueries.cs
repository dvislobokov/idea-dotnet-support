using MediatR;
using Microsoft.EntityFrameworkCore;
using Shop.Api.Data;
using Shop.Api.Domain;

namespace Shop.Api.Features.Orders;

public sealed record OrderLineView(string Sku, string Title, decimal Price, int Quantity);

public sealed record OrderView(long Id, string Customer, OrderStatus Status, decimal Total, DateTimeOffset CreatedAt, IReadOnlyList<OrderLineView> Lines);

public sealed record GetOrder(long OrderId) : IRequest<OrderView?>;

public sealed class GetOrderHandler(ShopDbContext db) : IRequestHandler<GetOrder, OrderView?>
{
    public Task<OrderView?> Handle(GetOrder request, CancellationToken cancellationToken) =>
        db.Orders
            .Where(order => order.Id == request.OrderId)
            .Select(order => new OrderView(
                order.Id,
                order.Customer.Name,
                order.Status,
                order.Lines.Sum(line => line.Price * line.Quantity),
                order.CreatedAt,
                order.Lines.Select(line => new OrderLineView(line.Sku, line.Title, line.Price, line.Quantity)).ToList()))
            .FirstOrDefaultAsync(cancellationToken);
}

public sealed record OrderPage(IReadOnlyList<OrderView> Items, int Total, int Page, int Size);

public sealed record ListOrders(OrderStatus? Status, long? CustomerId, int Page = 1, int Size = 20) : IRequest<OrderPage>;

public sealed class ListOrdersHandler(ShopDbContext db) : IRequestHandler<ListOrders, OrderPage>
{
    public async Task<OrderPage> Handle(ListOrders request, CancellationToken cancellationToken)
    {
        IQueryable<Order> orders = db.Orders;
        if (request.Status is { } status) orders = orders.Where(order => order.Status == status);
        if (request.CustomerId is { } customerId) orders = orders.Where(order => order.CustomerId == customerId);

        var total = await orders.CountAsync(cancellationToken);
        var items = await orders
            .OrderByDescending(order => order.CreatedAt)
            .Skip((request.Page - 1) * request.Size)
            .Take(request.Size)
            .Select(order => new OrderView(
                order.Id,
                order.Customer.Name,
                order.Status,
                order.Lines.Sum(line => line.Price * line.Quantity),
                order.CreatedAt,
                Array.Empty<OrderLineView>()))
            .ToListAsync(cancellationToken);
        return new OrderPage(items, total, request.Page, request.Size);
    }
}

public sealed record TopCustomer(long CustomerId, string Name, int Orders, decimal Spent);

public sealed record GetTopCustomers(int Count = 10) : IRequest<IReadOnlyList<TopCustomer>>;

/// <summary>The query syntax on purpose: range variables, <c>join</c>, <c>group … into</c> and <c>let</c> over an <see cref="IQueryable{T}"/>.</summary>
public sealed class GetTopCustomersHandler(ShopDbContext db) : IRequestHandler<GetTopCustomers, IReadOnlyList<TopCustomer>>
{
    public async Task<IReadOnlyList<TopCustomer>> Handle(GetTopCustomers request, CancellationToken cancellationToken)
    {
        var query =
            from order in db.Orders
            join customer in db.Customers on order.CustomerId equals customer.Id
            where order.Status != OrderStatus.Cancelled
            group order by new { customer.Id, customer.Name } into perCustomer
            let spent = perCustomer.Sum(order => order.Lines.Sum(line => line.Price * line.Quantity))
            orderby spent descending
            select new TopCustomer(perCustomer.Key.Id, perCustomer.Key.Name, perCustomer.Count(), spent);
        return await query.Take(request.Count).ToListAsync(cancellationToken);
    }
}
