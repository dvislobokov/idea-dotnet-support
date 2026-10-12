// CS0102: The type 'T' already contains a definition for 'x'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0102;

public interface IShape
{
    double Area();
    string Name { get; }
}

public class Order : IShape
{
    private int _id;
    private int _id; // ERROR CS0102
    public string Status { get; set; } = "";
    public string Status = ""; // ERROR CS0102
    public void Ship() { }
    public bool Ship; // ERROR CS0102
    public void Ship(int days) { } // ERROR CS0102
    public event Action? Changed;
    public void Changed() { } // ERROR CS0102
    public class Line { }
    public class Line { } // ERROR CS0102
    public int Total, Total; // ERROR CS0102

    public void Pay() { }
    public void Pay(int amount) { }
    public class Item { }
    public class Item<T> { }
    double IShape.Area() => 0;
    public double Area() => 1;
    string IShape.Name => "";
    public string Name => "order";
}

public enum Color { Red, Green, Red } // ERROR CS0102

public partial class Customer
{
    public string Email { get; set; } = "";
    partial void OnCreated();
}

public partial class Customer
{
    public string Email = ""; // ERROR CS0102
    partial void OnCreated() { }
}

public record Point(int X, int Y)
{
    public int X { get; init; } = X;
    public int Z;
    public int Z; // ERROR CS0102
}

public class Generic<T>
{
    public T? Value;
    public T? Value; // ERROR CS0102
}

public class Grid
{
    public int Item;
    public int this[int row] => row; // ERROR CS0102
    public int this[string key] => 0; // ERROR CS0102
}

public class Table
{
    public int this[int row] => row; // ERROR CS0102
    public string Item() => "";
}

public class Matrix
{
    [System.Runtime.CompilerServices.IndexerName("Cell")]
    public int this[int row, int column] => row + column; // ERROR CS0102
    public int Item;
    public int Cell;
}

public interface IVersioned
{
    int Version { get; }
    event Action Saved;
}

public class Document : IVersioned // ERROR CS8646
{
    int IVersioned.Version => 1;
    int IVersioned.Version => 2; // ERROR CS0102
    event Action IVersioned.Saved { add { } remove { } }
    event Action IVersioned.Saved { add { } remove { } } // ERROR CS0102
    public int Version => 3;
}

public record Money(decimal Amount, string Currency) // ERROR CS0102
{
    public void Amount() { } // ERROR CS0102
    public class Currency { }
}

public record Temperature(double Celsius, double Kelvin)
{
    public event Action? Kelvin; // ERROR CS0102
    public double Celsius { get; init; } = Celsius;
}

public partial class Workflow
{
    public bool Started;
    partial void Started(int step); // ERROR CS0102
    partial void Started(int step) { }
    partial void Finished(int step) { }
    partial void Finished(int step);
    private int Finished; // ERROR CS0102
}
