// CS0021: Cannot apply indexing with [] to an expression of type 'T'. Lines marked `// ERROR CSxxxx` must show that error in the editor
// (and in `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0021;

public enum IdxKind { One, Two }

public class IdxBag
{
    public int Size;
}

public class IdxShelf
{
    private readonly int[] _items = [1, 2, 3];
    public int this[int index] => _items[index];
}

public class IdxBigShelf : IdxShelf { }

public class IndexCases
{
    public int Count() => 2;

    public void Run(int number, bool flag, IdxBag bag, IdxShelf shelf, IdxBigShelf big, IdxKind kind, string text, List<int> list, int[] array, Exception error)
    {
        var digit = number[0]; // ERROR CS0021
        var bit = flag[1]; // ERROR CS0021
        var item = bag[0]; // ERROR CS0021
        var kindAt = kind[0]; // ERROR CS0021
        var counted = Count[0]; // ERROR CS0021
        var failed = error[0]; // ERROR CS0021
        var fromShelf = shelf[0] + big[1];
        var letter = text[0];
        var last = list[^1] + array[0];
        var slice = array[1..];
        var chars = text[1..];
        Console.WriteLine($"{digit} {bit} {item} {kindAt} {counted} {failed} {fromShelf} {letter} {last} {slice.Length} {chars}");
    }
}
