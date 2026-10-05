using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Shop.Api.Data;
using Shop.Api.Domain;

namespace Shop.Api.Controllers;

public sealed record CustomerInput(string Name, string Email, string? Phone);

[ApiController]
[Route("api/customers")]
public sealed class CustomersController(ShopDbContext db, ILogger<CustomersController> logger) : ControllerBase
{
    [HttpGet("{id:long}")]
    [ProducesResponseType<Customer>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<Customer>> Get(long id, CancellationToken cancellationToken)
    {
        var customer = await db.Customers.Include(customer => customer.Orders).FirstOrDefaultAsync(customer => customer.Id == id, cancellationToken);
        return customer is null ? NotFound() : Ok(customer);
    }

    [HttpPost]
    public async Task<IActionResult> Create(CustomerInput input, CancellationToken cancellationToken)
    {
        if (await db.Customers.AnyAsync(customer => customer.Email == input.Email, cancellationToken))
            return Conflict(new ProblemDetails { Title = "Email is taken", Detail = input.Email });

        var customer = new Customer { Name = input.Name, Email = input.Email, Phone = input.Phone };
        db.Customers.Add(customer);
        await db.SaveChangesAsync(cancellationToken);
        logger.LogInformation("Customer {CustomerId} created", customer.Id);
        return CreatedAtAction(nameof(Get), new { id = customer.Id }, customer);
    }
}
