using System.Diagnostics.CodeAnalysis;
using Shop.Api.Domain;

namespace Shop.Api.Playground;

// Object initializers and C# 11 `required` members (0.1.98), as in Rider. OrderLine and Product have `required string Sku` / `Title`.
// Type on the empty line under a marker, press what it says, compare with EXPECT, undo with Ctrl+Z. The file compiles as it is.

/// <summary>A constructor that sets the required members: `new Money(1m)` needs no initializer.</summary>
public sealed class Money
{
    public required decimal Amount { get; init; }
    public string Currency { get; init; } = "EUR";

    public Money() { }

    [SetsRequiredMembers]
    public Money(decimal amount) => Amount = amount;
}

/// <summary>Only a constructor with parameters: completion keeps `new Shipment(|)`, the initializer comes from the quick fix.</summary>
public sealed class Shipment(string carrier)
{
    public string Carrier { get; } = carrier;
    public required string TrackingNumber { get; set; }
}

public static class RequiredMembersTour
{
    public static List<OrderLine> Lines()
    {
        var lines = new List<OrderLine>();

        // TYPE:required-new — type `lines.Add(new OrderL` and choose `OrderLine` with Enter.
        // EXPECT: `new OrderLine` followed by `{` on its own line, `Sku = ,` and `Title = ` one a line, `}` under `{`, the caret after `Sku = `;
        //   no `()`. Type `"A-1"`, move to `Title = `, type `"Widget"`, then `);` — no red anywhere.
        // EXPECT (not): `new OrderLine(|)` with the caret in the parentheses.

        // TYPE:required-expected — type `OrderLine line = new ` and look at the first row, then Enter.
        // EXPECT: the first row reads `OrderLine { Sku, Title }`; Enter writes the same initializer as above.

        // TYPE:required-error — type `var bad = new OrderLine();`
        // EXPECT: `OrderLine` underlined red twice: "CS9035: Required member 'OrderLine.Sku' must be set in the object initializer or attribute
        //   constructor." and the same for 'OrderLine.Title'. Alt+Enter on `OrderLine` → "Add initializer for required members" writes
        //   `new OrderLine { Sku = |, Title = }` laid out one member a line (no `()`); the red goes away once both values are typed.

        // TYPE:required-partial — type `var half = new OrderLine { Sku = "A-1" };`
        // EXPECT: one red CS9035 for 'OrderLine.Title' only; Alt+Enter on `OrderLine` → the fix adds `Title = ` after `Sku = "A-1",`.

        // TYPE:required-fill — type `var filled = new OrderLine { ` (the `}` closes by itself), then Ctrl+Space inside the braces.
        // EXPECT: rows "Fill required members  Sku, Title" and "Fill all members  Sku, Title, Id, CreatedAt, UpdatedAt, OrderId, Price, Quantity"
        //   on top, then the members Sku, Title, Price… one by one. Enter on "Fill required members" writes `Sku = ,` / `Title = ` on their lines.

        // TYPE:required-member — type `var one = new Product { Ti` and choose `Title` with Enter.
        // EXPECT: `Title = ` with the caret after `= ` (the space and the equals sign are written for you); Ctrl+Space again lists no `Title`.

        // TYPE:required-intention — type `var more = new OrderLine { Sku = "A", Title = "B" };`, put the caret between `"B"` and `}`, Alt+Enter.
        // EXPECT: "Initialize members" writes `Id = , CreatedAt = , UpdatedAt = , OrderId = , Price = , Quantity = ` one a line after `Title = "B",`.
        //   With `{ }` empty and the caret inside, Alt+Enter also offers "Initialize required members" (only Sku and Title).

        // TYPE:required-sets — type `var price = new Money(1m);` and then `var zero = new Money();`
        // EXPECT: no red on `new Money(1m)` (its constructor is [SetsRequiredMembers]); CS9035 'Money.Amount' on `new Money()` only.
        //   `var m = new Mon` + Enter writes `new Money { Amount =  }` on one line (a single member stays on the line).

        // TYPE:required-ctor — type `var parcel = new Shipm` and Enter.
        // EXPECT: `new Shipment(|)`, the caret in the parentheses (the constructor needs a carrier); after `"DHL")` and `;` CS9035 for
        //   'Shipment.TrackingNumber', Alt+Enter → `new Shipment("DHL") { TrackingNumber =  }`.

        lines.Add(new OrderLine { Sku = "A-1", Title = "Widget", Price = 9.99m, Quantity = 1 });
        return lines;
    }

    public static Money Price() => new Money(1m);

    public static Shipment Parcel() => new Shipment("DHL") { TrackingNumber = "1Z" };
}
