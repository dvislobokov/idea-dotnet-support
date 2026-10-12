// CS0192: A readonly field cannot be used as a ref or out value (except in a constructor). Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0192;

public class Gauge
{
    public readonly int Level;
    public int Plain;

    public Gauge(int start)
    {
        Bump(ref Level);             // in a constructor of its type
        Read(out this.Level);
        Level += start;
    }

    public void Raise(Gauge other)
    {
        Bump(ref Plain);
        Peek(in Level);              // `in` only reads it
        Bump(ref Level); // ERROR CS0192
        Read(out Level); // ERROR CS0192
        Bump(ref other.Level); // ERROR CS0192
    }

    public static void Bump(ref int value) => value++;
    public static void Read(out int value) => value = 1;
    public static void Peek(in int value) => Console.WriteLine(value);
}

public class Panel
{
    public void Tune(Gauge gauge)
    {
        int local = gauge.Level;
        Gauge.Bump(ref local);
        Gauge.Bump(ref gauge.Plain);
        Gauge.Bump(ref gauge.Level); // ERROR CS0192
    }
}
