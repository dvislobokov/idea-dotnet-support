// CS0305: Using the generic type 'G<T>' requires 1 type arguments. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
// Signatures of members: the compiler reports them on the type and stops before method bodies (CS0305.cs).
namespace DebugPlayground.Broken.Errors.CS0305_Signatures;

public class Box<T> { }

public class Pair<TFirst, TSecond> { }

public class Shelf
{
    public Box<int>? Single;
    public Box<int, int>? Double; // ERROR CS0305
    public Pair<int, string>? Both;
    public Pair<int>? Half; // ERROR CS0305
    public List<Box<string>> Items() => new();
    public List<Box> Bare() => new(); // ERROR CS0305
    public void Put(Dictionary<string, Box<int>> items) { }
    public void Drop(Dictionary<string> items) { } // ERROR CS0305
}

public class Crate : Box<int> { }

public class Bin : Box { } // ERROR CS0305
