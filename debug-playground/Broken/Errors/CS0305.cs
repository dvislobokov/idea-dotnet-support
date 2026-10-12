// CS0305: Using the generic type 'G<T>' requires 1 type arguments. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0305;

public class Box<T> { public static int Count; }

public class Pair<TFirst, TSecond> { }

public class Pair<TFirst> { }

public static class Make
{
    public static T Default<T>() => default!;
    public static void Store<T>(T value) { }
}

public class Uses
{
    public void Run()
    {
        Box<int> number = new();
        var single = new Pair<int>();
        var both = new Pair<int, string>();
        Box<int, string>? wrong = null; // ERROR CS0305
        List<int, int>? items = null; // ERROR CS0305
        var lookup = new Dictionary<string>(); // ERROR CS0305
        List? plain = null; // ERROR CS0305
        var count = Box<int>.Count;
        var value = Make.Default<int>();
        var twice = Make.Default<int, int>(); // ERROR CS0305
        Make.Store(1);
        Make.Store<int, int>(1); // ERROR CS0305
        var total = Box<int, int>.Count; // ERROR CS0305
        var bare = Box.Count; // ERROR CS0305
        Console.WriteLine($"{number}{single}{both}{wrong}{items}{lookup}{plain}{count}{value}{twice}{total}{bare}");
    }
}
