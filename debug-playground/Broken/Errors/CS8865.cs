// CS8865: Only records may inherit from records. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS8865;

public record Person(string Name);

public abstract record Shape;

public class Entity { }

public record Employee(string Name, string Company) : Person(Name);
public record Circle(double Radius) : Shape;
public class Customer : Person // ERROR CS8865
{
    public Customer() : base("customer") { }
}
public class Square : Shape { } // ERROR CS8865
public class Order : Entity { }
public class Supplier : Entity, IComparable<Supplier>
{
    public int CompareTo(Supplier? other) => 0;
}
