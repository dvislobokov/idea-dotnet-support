// CS0026: Keyword 'this' is not valid in a static property, static method, or static field initializer. Lines marked `// ERROR CSxxxx` must
// show that error in the editor (and in `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks
// both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0026;

public class StaticThis
{
    private int _count = 1;
    public int Count => this._count;

    public static int Read() => this._count; // ERROR CS0026

    public static int Shared => this.Count; // ERROR CS0026

    public static readonly int Initial = this.Count; // ERROR CS0026

    public static StaticThis operator +(StaticThis a, StaticThis b) => this; // ERROR CS0026

    static StaticThis()
    {
        Console.WriteLine(this); // ERROR CS0026
    }

    public static Func<int> Later() => () => this._count; // ERROR CS0026

    public int Instance() => this._count + Count;

    public Func<int> InstanceLater() => () => this._count;

    public static int Fine(StaticThis other) => other._count;
}
