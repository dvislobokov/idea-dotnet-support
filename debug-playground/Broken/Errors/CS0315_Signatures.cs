// CS0315: The type 'X' cannot be used as type parameter 'T' in the generic type or method 'G<T>'. There is no boxing conversion from 'X' to 'C'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
// Signatures of members: the compiler reports them on the name of the member or parameter and stops before method bodies (CS0315.cs).
namespace DebugPlayground.Broken.Errors.CS0315_Signatures;

public interface IShape { double Area(); }

public struct Square : IShape { public double Area() => 1; }

public struct Point { public int X; }

public class Shape { }

public class Canvas<T> where T : IShape { }

public class Holder<T> where T : Shape { }

public class Drawing
{
    public Canvas<Square>? Squares, Tiles;
    public Canvas<Point>? Points, Corners; // ERROR CS0315
    public Holder<Shape> Shapes() => new();
    public Holder<int> Numbers() => new(); // ERROR CS0315
    public void Paint(Canvas<Square> canvas) { }
    public void Plot(Canvas<Point> canvas) { } // ERROR CS0315
    public event Action<Holder<Shape>>? Drawn;
    public event Action<Holder<long>>? Erased; // ERROR CS0315
}

public record Layer(Canvas<Square> Canvas);

public record Grid(Canvas<Point> Canvas); // ERROR CS0315
