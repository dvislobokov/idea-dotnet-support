// CS0428: Cannot convert method group 'M' to non-delegate type 'T'. Did you intend to invoke the method? Lines marked `// ERROR CSxxxx`
// must show that error in the editor (and in `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide
// checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0428;

public class GroupStock
{
    public int Count() => 3;
    public string Describe() => "stock";
    public GroupStock Copy() => new();
}

public class GroupCases
{
    private int _total;

    public void Run(GroupStock stock)
    {
        int count = stock.Count; // ERROR CS0428
        string text = stock.Describe; // ERROR CS0428
        GroupStock copy = stock.Copy; // ERROR CS0428
        _total = stock.Count; // ERROR CS0428
        string name = ToString; // ERROR CS0428
        int fine = stock.Count();
        Func<int> later = stock.Count;
        var inferred = stock.Describe;
        object boxed = stock.Count();
        Console.WriteLine($"{count} {text} {copy} {name} {fine} {later()} {inferred()} {boxed} {_total}");
    }
}
