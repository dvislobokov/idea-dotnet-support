// CS0820: Cannot initialize an implicitly-typed variable with an array initializer. Lines marked `// ERROR CSxxxx` must show that error in the
// editor (and in `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0820;

public class ArrayInitCases
{
    public int Run()
    {
        var numbers = { 1, 2, 3 }; // ERROR CS0820
        var names = { "a", "b" }; // ERROR CS0820
        int[] typed = { 1, 2, 3 };
        var created = new[] { 1, 2, 3 };
        var explicitly = new int[] { 4, 5 };
        List<int> collected = [6, 7];
        var empty = { }; // ERROR CS0820
        string[] words = { "x" };
        var rates = { 1.5, 2.5 }; // ERROR CS0820
        return typed.Length + created.Length + explicitly.Length + collected.Count + words.Length;
    }
}
