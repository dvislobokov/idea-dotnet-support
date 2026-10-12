using Shop.Api.Domain;

namespace Shop.Api.Playground;

// Double completion (0.1.96): the second Ctrl+Space widens the list, the second Ctrl+Shift+Space adds chains of the expected type.
// Type on the empty line under a marker, press what it says, compare with EXPECT, undo with Ctrl+Z. The file compiles as it is.
// The types of packages the project does not reference are not visible here (ShopApi is one project): see
// `Console/Editor/DoubleCompletion.cs` of the main solution, marker `TYPE:double-package`.

/// <summary>A type with members the playground does not see from outside.</summary>
public sealed class Ledger
{
    private readonly List<Order> _entries = [];
    private int _version;

    internal string Owner { get; set; } = "";

    public int Count => _entries.Count;

    public Order? Last => _entries.Count == 0 ? null : _entries[^1];

    public Customer? LastCustomer => Last?.Customer;

    public void Record(Order order)
    {
        _entries.Add(order);
        _version++;
    }

    private static Ledger Open() => new();
}

public sealed class LedgerReport(Ledger ledger)
{
    private readonly Customer _fallback = new() { Name = "walk-in", Email = "walk-in@example.com" };

    public string Describe(Order order)
    {
        // TYPE:shop-double-members — type `ledger.` then Ctrl+Space once, look, then Ctrl+Space again.
        // EXPECT: first press: Count, Last, LastCustomer, Record, Owner (internal, same assembly), object's members; the advertisement line says
        //   "Press Ctrl+Space again to show members that are not accessible here".
        // EXPECT: second press: the same plus `_entries (not accessible)`, `_version (not accessible)` grayed at the bottom; no `Open` (static).
        //   Enter on `_version` writes `ledger._version` as it is (the compiler's CS0122 is yours, as in Rider).

        // TYPE:shop-double-protected — type `order.` then Ctrl+Space twice.
        // EXPECT: second press adds `MemberwiseClone() (not accessible)` grayed (protected member of object); the first press has none.

        // TYPE:shop-double-chain — type `Customer customer = ` then Ctrl+Shift+Space once, then again.
        // EXPECT: first press: `_fallback`, `null`, `default` (what is a Customer); the advertisement says "Press Ctrl+Shift+Space again to
        //   show members of values of the expected type".
        // EXPECT: second press adds the chains: `order.Customer`, `ledger.LastCustomer` with the type `Customer` at the right; Enter writes the chain.

        // TYPE:shop-double-chain-method — type `int n = ` then Ctrl+Shift+Space twice.
        // EXPECT: second press adds `ledger.Count : int` and `order.GetHashCode() : int`; not `order.CustomerId` (a long), not
        //   `_fallback.Orders.Count` (two accesses deep); Enter on `ledger.Count` writes `int n = ledger.Count`.

        return $"{ledger.Count} orders, last for {ledger.LastCustomer?.Name ?? _fallback.Name}, this one {order.Id}";
    }
}
