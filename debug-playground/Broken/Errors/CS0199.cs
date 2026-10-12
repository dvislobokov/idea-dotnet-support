// CS0199: A static readonly field cannot be used as a ref or out value (except in a static constructor). Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0199;

public class Counter
{
    public static readonly int Total;
    public static int Live;

    static Counter()
    {
        Bump(ref Total);             // in the static constructor of its type
        Read(out Counter.Total);
    }

    public Counter()
    {
        Bump(ref Live);
        Bump(ref Total); // ERROR CS0199
    }

    public void Reset()
    {
        Peek(in Total);              // `in` only reads it
        Read(out Total); // ERROR CS0199
    }

    public static void Bump(ref int value) => value++;
    public static void Read(out int value) => value = 0;
    public static void Peek(in int value) => Console.WriteLine(value);
}

public class Dashboard
{
    public void Refresh()
    {
        int copy = Counter.Total;
        Counter.Bump(ref copy);
        Counter.Bump(ref Counter.Live);
        Counter.Bump(ref Counter.Total); // ERROR CS0199
        Counter.Read(out Counter.Total); // ERROR CS0199
    }
}
