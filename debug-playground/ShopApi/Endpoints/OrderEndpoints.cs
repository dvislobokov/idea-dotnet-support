using MediatR;
using Microsoft.AspNetCore.Http.HttpResults;
using Shop.Api.Domain;
using Shop.Api.Features.Orders;

namespace Shop.Api.Endpoints;


public class User
{
    public int Age { get; set; }
    public string Name { get; set; }
}


public class UserDto
{
    public int Agen { get; set; }
    public string Name { get; set; }
}


public static class OrderEndpoints
{
    public static IEndpointRouteBuilder MapOrderEndpoints(this IEndpointRouteBuilder app, UserDto userDto)
    {
        var user = new User{
            Age = userDto.Agen,
            Name = userDto.Name
        };
        var orders = app.MapGroup("/api/orders").WithTags("Orders");

        orders.MapGet("/", (OrderStatus? status, long? customerId, int? page, IMediator mediator, CancellationToken cancellationToken) =>
            mediator.Send(new ListOrders(status, customerId, page ?? 1), cancellationToken));

        orders.MapGet("/{id:long}", async Task<Results<Ok<OrderView>, NotFound>>(long id, IMediator mediator, CancellationToken cancellationToken) =>
            await mediator.Send(new GetOrder(id), cancellationToken) is { } order ? TypedResults.Ok(order) : TypedResults.NotFound());

        orders.MapPost("/", async (CreateOrder command, IMediator mediator, CancellationToken cancellationToken) =>
        {
            var id = await mediator.Send(command, cancellationToken);
            return TypedResults.Created($"/api/orders/{id}", new { id });
        });

        orders.MapPost("/{id:long}/cancel", async Task<Results<NoContent, Conflict>>(long id, string reason, IMediator mediator, CancellationToken cancellationToken) =>
            await mediator.Send(new CancelOrder(id, reason), cancellationToken) ? TypedResults.NoContent() : TypedResults.Conflict());

        orders.MapGet("/top-customers", (int? count, ISender sender, CancellationToken cancellationToken) =>
            sender.Send(new GetTopCustomers(count ?? 10), cancellationToken));

        return app;
    }
}
