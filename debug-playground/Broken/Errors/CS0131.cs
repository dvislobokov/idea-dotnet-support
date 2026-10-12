// CS0131: The left-hand side of an assignment must be a variable, property or indexer. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0131;

public enum Level { Low, High }

public class Counter
{
    public const int Max = 10;
    private int _value;
    private int[] _items = new int[4];

    public int Next() => _value + 1;
    public ref int Slot() => ref _value;

    public void Update(int x)
    {
        const int step = 2;
        _value = step;
        Slot() = 5;                  // a method that returns by reference is a variable
        _items[0] = 1;
        (_value) = 3;
        Next() = 4; // ERROR CS0131
        Max = 11; // ERROR CS0131
        step = 3; // ERROR CS0131
        x + 1 = 2; // ERROR CS0131
        Level.Low = Level.High; // ERROR CS0131
        Math.PI = 3; // ERROR CS0131
        Next() += 1; // ERROR CS0131
        (int)x = 1; // ERROR CS0131
    }
}
