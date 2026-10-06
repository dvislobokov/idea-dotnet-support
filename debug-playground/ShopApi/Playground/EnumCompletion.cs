using System.Net;
using Shop.Api.Domain;

namespace Shop.Api.Playground;

// Enum completion (0.1.88, 0.1.106): where an enum is expected the list opens by itself with its members first (`OrderStatus.Paid : 2`),
// and a member chosen at the end of a line closes the statement (`;`, `)`). Type on the empty line under a marker, undo with Ctrl+Z.
// The file compiles as it is.
public static class EnumCompletion
{
    public static void Take(OrderStatus status) { }

    public static bool Run(Order order, OrderStatus status, DayOfWeek day)
    {
        // TYPE:enum-assign-library — type `Console.BackgroundColor = ` (with the space).
        // EXPECT: the list opens by itself, `ConsoleColor.Black : 0`, `ConsoleColor.Blue : 9`… first; Enter on `ConsoleColor.Black`
        //   writes `Console.BackgroundColor = ConsoleColor.Black;`, the caret after `;`.

        order.Status= OrderStatus.Cancelled;
        day.AddEndpointFilter((endpointFilterInvocationContext, endpointFilterDelegate) => {});
        // TYPE:enum-assign-no-equals — type `Console.ForegroundColor ` (with the space, no `=`) (0.1.107).
        // EXPECT: the list opens by itself with `= ConsoleColor.Black : 0`, `= ConsoleColor.Blue : 9`…; Enter writes
        //   `Console.ForegroundColor = ConsoleColor.Black;`. `order.Status ` gives `= OrderStatus.Draft`…; `order.Comment ` gives the
        //   strings at hand (`= ...`). EXPECT (not): `foregroundColor` as a name to declare, the list after `Console.WriteLine `.

        // TYPE:enum-assign-property — type `order.Status = ` (with the space).
        // EXPECT: the list opens by itself with `OrderStatus.Draft : 0`… `OrderStatus.Cancelled : 4`; Enter on `OrderStatus.Paid` writes
        //   `order.Status = OrderStatus.Paid;`.

        // TYPE:enum-declaration — type `OrderStatus next = ` (with the space).
        // EXPECT: the list opens by itself; `Shi` + Enter writes `OrderStatus next = OrderStatus.Shipped;`.

        // TYPE:enum-equality — type `if (status == ` (with the space).
        // EXPECT: the list opens by itself; Enter on `OrderStatus.Paid` writes `if (status == OrderStatus.Paid)` — `)` closed, no `;`.

        // TYPE:enum-argument — type `Take(` (the `)` comes by itself) and press Ctrl+Space.
        // EXPECT: `OrderStatus.*` first; Enter on `OrderStatus.Placed` writes `Take(OrderStatus.Placed);` — over the `)`, the `;` after it.

        // TYPE:enum-after-dot — type `var comparison = StringComparison.` and pick `OrdinalIgnoreCase`.
        // EXPECT: `var comparison = StringComparison.OrdinalIgnoreCase;` with the `;`.

        // TYPE:enum-switch — type `var label = day switch { ` and press Ctrl+Space.
        // EXPECT: `DayOfWeek.Friday : 5`, `DayOfWeek.Monday : 1`… first. EXPECT (not): a `;` after the chosen member (an arm goes on with ` =>`).

        // TYPE:enum-return — on the empty line in `Code` below type `return ` (with the space).
        // EXPECT: the list opens by itself with `HttpStatusCode.*`; `NotF` + Enter writes `return HttpStatusCode.NotFound;`.

        // TYPE:enum-no-foreign-extension — type `day.` (0.1.107).
        // EXPECT: the members of DayOfWeek and the extension methods for any value; EXPECT (not): `AddEndpointFilter`, `WithName`, `RequireAuthorization`
        //   (extension methods of `TBuilder where TBuilder : IEndpointConventionBuilder`). `day.AddEndpointFilter(null!);` is red: CS1061.

        // TYPE:enum-initializer — type `var draft = new Order { Status = ` (the `}` comes by itself) and press Enter on `OrderStatus.Draft`.
        // EXPECT (not): a `;` — the initializer goes on with `,` or `}`.

        return status == OrderStatus.Paid;
    }

    public static HttpStatusCode Code(bool found)
    {

        return found ? HttpStatusCode.OK : HttpStatusCode.NotFound;
    }
}
