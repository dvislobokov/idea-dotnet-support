// CS0206: A non ref-returning property or indexer may not be used as an out or ref value. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0206;

public record Point(int X, int Y);

public class Sensor
{
    private int _raw;
    public int Value { get; set; }
    public int[] Samples = new int[3];
    public ref int Raw => ref _raw;
    public int this[int index] { get => index; set { } }

    public void Measure(Sensor other, Point point, List<int> list)
    {
        Bump(ref Samples[0]);        // an element of an array is a variable
        Bump(ref Raw);               // a property that returns by reference
        Peek(in _raw);
        Bump(ref Value); // ERROR CS0206
        Read(out other.Value); // ERROR CS0206
        Bump(ref this[1]); // ERROR CS0206
        Bump(ref list[0]); // ERROR CS0206
        Read(out point.X); // ERROR CS0206
    }

    public static void Bump(ref int value) => value++;
    public static void Read(out int value) => value = 0;
    public static void Peek(in int value) => Console.WriteLine(value);
}
