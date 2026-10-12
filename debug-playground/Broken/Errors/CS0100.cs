// CS0100: The parameter name 'x' is a duplicate. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0100;

public delegate void Handler(object sender, object sender); // ERROR CS0100

public class DuplicateParameters(string name, int name) // ERROR CS0100
{
    public void Move(int x, int y, int x) { } // ERROR CS0100

    public DuplicateParameters(int id, string id, bool flag) : this("", 0) { } // ERROR CS0100

    public int this[int row, int row] => 0; // ERROR CS0100

    public void Discards(int _, int _) { } // ERROR CS0100

    public static DuplicateParameters operator +(DuplicateParameters left, DuplicateParameters left) => left; // ERROR CS0100

    public void Fine(int x, int y)
    {
        Func<int, int, int> first = (_, _) => 0;
        Func<int, int, int> second = (a, b) => a + b;
        Action<int, int> third = delegate (int _, int _) { };
        Console.WriteLine(x + y + first(1, 2) + second(1, 2));
        third(1, 2);
    }

    public void Overload(int x) { }
    public void Overload(int x, string y) { }
}
