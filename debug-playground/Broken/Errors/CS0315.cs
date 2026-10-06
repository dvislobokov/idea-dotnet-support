// CS0315: The type 'X' cannot be used as type parameter 'T' in the generic type or method 'G<T>'. There is no boxing conversion from 'X' to 'C'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0315;

public interface IShape { double Area(); }

public struct Square : IShape { public double Area() => 1; }

public struct Point { public int X; }

public class Shape { }

public class Canvas<T> where T : IShape { }

public class Holder<T> where T : Shape { }

public enum Color { Red, Green }

public static class Draw
{
    public static void Paint<T>(T shape) where T : IShape { }
    public static TEnum[] Values<TEnum>() where TEnum : struct, Enum => Enum.GetValues<TEnum>();
}

public class Uses
{
    public void Run()
    {
        var squares = new Canvas<Square>();
        var points = new Canvas<Point>(); // ERROR CS0315
        Holder<int>? numbers = null; // ERROR CS0315
        Holder<Shape> shapes = new();
        Draw.Paint<Square>(new Square());
        Draw.Paint(new Square());
        Draw.Paint(new Point()); // ERROR CS0315
        Draw.Paint(3); // ERROR CS0315
        var colors = Enum.GetValues<Color>();
        var values = Enum.GetValues<int>(); // ERROR CS0315
        var mine = Draw.Values<Color>();
        var wrong = Draw.Values<long>(); // ERROR CS0315
        var comparer = Comparer<int>.Default;
        Console.WriteLine($"{squares}{points}{numbers}{shapes}{colors}{values}{mine}{wrong}{comparer}");
    }
}
