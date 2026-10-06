// CS0308: The non-generic type 'X' cannot be used with type arguments. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0308;

public class Order { public static int Count; }

public class Box<T> { }

public class Outer { public class Inner { } }

public static class Tools
{
    public static int Size() => 0;
    public static T Echo<T>(T value) => value;
}

public class Uses
{
    public void Run()
    {
        Order plain = new();
        Box<Order> boxed = new();
        Order<int>? wrong = null; // ERROR CS0308
        var random = new Random<char>(); // ERROR CS0308
        Outer.Inner<int>? inner = null; // ERROR CS0308
        var order = new Order<string>(); // ERROR CS0308
        var count = Tools.Size();
        var counted = Tools.Size<int>(); // ERROR CS0308
        var echo = Tools.Echo<int>(1);
        var orders = Order<int>.Count; // ERROR CS0308
        var all = Order.Count;
        var text = string.Join<int>(",", new[] { 1 });
        Console.WriteLine($"{plain}{boxed}{wrong}{random}{inner}{order}{count}{counted}{echo}{orders}{all}{text}");
    }
}
