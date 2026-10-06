// CS0144: Cannot create an instance of the abstract type or interface 'T'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0144;

public abstract class Shape
{
    public abstract double Area();
}

public class Square : Shape
{
    public double Side { get; init; }
    public override double Area() => Side * Side;
}

public interface IStore
{
    void Save(string item);
}

public abstract record Message(string Text);
public record Ping() : Message("ping");

public class Factory
{
    public void Create()
    {
        Shape shape = new Shape(); // ERROR CS0144
        Shape square = new Square { Side = 2 };
        IStore store = new IStore(); // ERROR CS0144
        Message message = new Message("hi"); // ERROR CS0144
        Message ping = new Ping();
        Stream stream = new Stream(); // ERROR CS0144
        Stream memory = new MemoryStream();
        var shapes = new Shape[3];
        var list = new List<Shape> { square };
        Shape implicitShape = new(); // ERROR CS0144
        Square implicitSquare = new();
        IStore? implicitStore = new(); // ERROR CS0144
        Stream output;
        output = new(); // ERROR CS0144
        MemoryStream buffer;
        buffer = new();
        Console.WriteLine($"{shape}{store}{message}{ping}{stream}{memory}{shapes}{list}{implicitShape}{implicitSquare}{implicitStore}{output}{buffer}");
    }

    public T Make<T>() where T : new() => new T();

    private IList<int> _items = new(); // ERROR CS0144
    private List<int> _list = new();
    public Shape Default { get; set; } = new(); // ERROR CS0144
}
