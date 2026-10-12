// CS1660: Cannot convert lambda expression to type 'T' because it is not a delegate type. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1660;

public class Order { }
public enum Status { Open, Closed }

public class Lambdas
{
    public int Size { get; set; } = () => 1; // ERROR CS1660
    public Func<int> Sizer { get; set; } = () => 1;

    public void Consume(int count) { }
    public void Run(Func<int> action) { }
    public void Number(int value) { }
    public void Number(string value) { }
    public void Rule<T>(Func<T, int> rule) { }
    public void Rule(int value) { }
    public void Take(int count) { }

    public int Count() { return () => 1; } // ERROR CS1660
    public Func<int> Counter() { return () => 1; }

    public void Use(List<int> numbers)
    {
        int count = () => 1; // ERROR CS1660
        Func<int> counter = () => 1;
        string text = x => "a"; // ERROR CS1660
        object boxed = () => 1;
        Delegate any = (int x) => x;
        Order order = () => new Order(); // ERROR CS1660
        Status status = () => Status.Open; // ERROR CS1660
        int? maybe = () => 1; // ERROR CS1660
        var natural = () => 1;
        Consume(() => 1); // ERROR CS1660
        Run(() => 1);
        Number(() => 1); // ERROR CS1660
        Number(1);
        Rule(x => 1); // ERROR CS1660
        Rule((string s) => s.Length);
        Take(() => 1); // ERROR CS1660
        Take(1);
        var first = numbers.Take(() => 1); // ERROR CS1660
        var some = numbers.Take(2);
        int value = delegate { return 1; }; // ERROR CS1660
        Console.WriteLine(count + counter() + text + boxed + any + order + status + maybe + natural() + value + first.Count() + some.Count());
    }
}
