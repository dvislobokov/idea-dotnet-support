// CS0272: The property or indexer 'x' cannot be used in this context because the set accessor is inaccessible. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0272;

public class Order
{
    public int Id { get; private set; }
    public string Status { get; protected set; } = "new";
    public DateTime Created { get; private init; }
    public int this[string key] { get => key.Length; private set { } }
    public int this[int index] { get => index; set { } }
    public int Batch { get; protected init; }

    public void Ship(Order other)
    {
        Id = 1;
        other.Id = 2;
        Status = "shipped";
        this["a"] = 1;
    }
}

public class Refund : Order
{
    public void Apply() => Status = "refunded";
}

public class Shop
{
    public void Edit(Order order)
    {
        var id = order.Id;
        order.Id = 5; // ERROR CS0272
        order.Status = "lost"; // ERROR CS0272
        order.Id++; // ERROR CS0272
        var copy = new Order { Created = DateTime.Now }; // ERROR CS0272
        order[1] = 3;                // the overload by int has a public setter
        order["x"] = 3; // ERROR CS0272
        var batch = new Order { Batch = 2 }; // ERROR CS0272
        Console.WriteLine(id + order.Status + copy.Created);
    }
}
