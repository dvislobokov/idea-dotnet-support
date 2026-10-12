// CS8864: Records may only inherit from object or another record. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS8864;

public class Entity
{
    public int Id { get; init; }
}

public abstract class Shape { }

public interface INamed
{
    string Name { get; }
}

public record Person(string Name) : INamed;
public record Employee(string Name, string Company) : Person(Name);
public record Customer : Entity; // ERROR CS8864
public record Circle(double Radius) : Shape; // ERROR CS8864
public record Failure(string Reason) : Exception; // ERROR CS8864
public record Tag : object;
public record Label(string Name) : INamed, IComparable<Label>
{
    public int CompareTo(Label? other) => 0;
}
public class Order : Entity { }
