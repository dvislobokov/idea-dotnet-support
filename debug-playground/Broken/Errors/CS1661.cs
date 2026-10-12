// CS1661: Cannot convert lambda expression to type 'D' because the parameter types do not match the delegate parameter types. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1661;

public delegate int Measure(string text, int start);
public delegate void Advance(int step, ref int position);
public class Customer { }

public class Converters
{
    public event Action<int>? Changed;
    public Func<int, int> Rule { get; set; } = (string s) => 1; // ERROR CS1661 CS1678

    public void Apply(Func<int, int> map) { }
    public void Parse(Func<string, int> parse) { }
    public void Parse(Func<int, int, int> parse) { }

    public Func<int, int> Reader() => (string s) => 1; // ERROR CS1661 CS1678
    public Func<int, int> Writer() => (int x) => x;

    public void Use()
    {
        Func<int, int> parse = (string s) => 1; // ERROR CS1661 CS1678
        Func<int, int> square = (int x) => x * x;
        Func<Customer, int> score = (object o) => 1; // ERROR CS1661 CS1678
        Func<Customer, int> rank = (Customer c) => 1;
        Measure measure = (string text, long start) => text.Length; // ERROR CS1661 CS1678
        Measure length = (string text, int start) => text.Length - start;
        Apply((long v) => 1); // ERROR CS1661 CS1678
        Apply((int v) => v);
        Parse((long s) => 1); // ERROR CS1661 CS1678
        Parse((string s) => s.Length);
        Advance move = (long step, ref long position) => { }; // ERROR CS1661 CS1678 CS1678
        Advance go = (int step, ref int position) => { position += step; };
        Changed += (string s) => { }; // ERROR CS1661 CS1678
        Changed += (int s) => { };
        Func<int, int> anonymous = delegate (string s) { return 1; }; // ERROR CS1661 CS1678
        Func<(int a, int b), int> tuple = ((int, int) t) => t.Item1;
        Func<object, int> dynamicArgument = (dynamic d) => 1;
        Console.WriteLine(parse(1) + square(2) + score(new Customer()) + rank(new Customer()) + measure("a", 1) + length("a", 0) + anonymous(1) + tuple((1, 2)) + dynamicArgument(1));
        int position = 0;
        move(1, ref position);
        go(1, ref position);
        Changed?.Invoke(position);
    }
}
