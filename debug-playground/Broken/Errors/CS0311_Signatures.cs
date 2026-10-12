// CS0311: The type 'X' cannot be used as type parameter 'T' in the generic type or method 'G<T>'. There is no implicit reference conversion from 'X' to 'C'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
// Signatures of members: the compiler reports them on the name of the member or parameter and stops before method bodies (CS0311.cs).
namespace DebugPlayground.Broken.Errors.CS0311_Signatures;

public interface IEntity { int Id { get; } }

public class Order : IEntity { public int Id => 1; }

public class Customer { public int Id => 2; }

public class Repository<T> where T : IEntity { }

public class Service
{
    private Repository<Order>? _orders;
    private Repository<Customer>? _customers; // ERROR CS0311
    public Repository<Order>? Orders => _orders;
    public Repository<Customer>? Customers { get; set; } // ERROR CS0311
    public void Use(Repository<Order> orders) { }
    public void Misuse(Repository<Customer> customers) { } // ERROR CS0311
    public Repository<Order>? this[int id] => null;
    public Repository<Customer>? this[string name] => null; // ERROR CS0311
}

public class OrderBox<T> where T : Repository<Order> { }

public class CustomerBox<T> where T : Repository<Customer> { } // ERROR CS0311

public class OrderService : List<Repository<Order>> { }

public class CustomerService : List<Repository<Customer>> { } // ERROR CS0311

public record Snapshot(Repository<Customer> Source); // ERROR CS0311
