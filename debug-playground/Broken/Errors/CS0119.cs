// CS0119: 'X' is a type / method, which is not valid in the given context. Lines marked `// ERROR CSxxxx` must show that error in the editor
// (and in `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0119;

public class CtxOrder
{
    public class Line { }
    public int Id { get; set; }
}

public enum CtxColor { Red, Green }

public class CtxShop
{
    public CtxColor CtxColor { get; set; }
    public int Count() => 1;
    public string Name { get; set; } = "";

    public object Run(CtxOrder order)
    {
        Console.WriteLine(CtxOrder); // ERROR CS0119
        object kind = CtxOrder; // ERROR CS0119
        object line = CtxOrder.Line; // ERROR CS0119
        var length = Count.ToString(); // ERROR CS0119
        Console.WriteLine(order);
        Console.WriteLine(CtxColor);
        var red = CtxColor.Red;
        var type = typeof(CtxOrder);
        var name = nameof(CtxOrder);
        var counted = Count().ToString();
        Console.WriteLine($"{kind} {line} {length} {red} {type} {name} {counted}");
        return CtxOrder; // ERROR CS0119
    }
}
