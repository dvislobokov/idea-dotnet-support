// CS1593: Delegate 'D' does not take N arguments. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1593;

public delegate int Combine(int left, int right);

public class Handlers
{
    public event Action<int>? Changed;
    public Action<int>? Handler;
    public Func<int, int> Rule { get; set; } = (a, b) => a; // ERROR CS1593
    public Func<int, int> Same { get; set; } = a => a;

    public void OnValue(Func<int, int> handler) { }
    public void OnSelect(Func<int, int> handler) { }
    public void OnSelect(Func<int, int, int> handler) { }
    public void Pick(Func<int, int, int> handler) { }
    public void Pick(Func<int, int> handler) { }
    public void Step(Func<int, int> handler, string name) { }
    public void Step(Func<int, int, int> handler, int count) { }
    public void Seed<T>(T start, Func<T, int> next) { }

    public Func<int, int> Make() { return (a, b) => a; } // ERROR CS1593
    public Func<int, int> Build() => (a, b) => a; // ERROR CS1593
    public Func<int, int> Fine() => a => a;

    public void Use(List<int> numbers)
    {
        Func<int, int> twice = (a, b) => a * 2; // ERROR CS1593
        Func<int, int> ok = a => a * 2;
        Func<int> constant = x => 1; // ERROR CS1593
        Action done = x => { }; // ERROR CS1593
        Action fine = () => { };
        Combine sum = a => a; // ERROR CS1593
        Combine add = (a, b) => a + b;
        OnValue((a, b) => a); // ERROR CS1593
        OnValue(a => a);
        OnSelect((a, b) => a);
        Pick((a, b, c) => a); // ERROR CS1593
        Pick(a => a);
        Step((a, b, c) => a, "name"); // ERROR CS1593
        Step((a, b, c) => a, 1.5); // ERROR CS1593 CS1503
        Step(a => a, "name");
        Seed(1, (a, b) => 1); // ERROR CS1593
        Seed(1, a => a);
        Changed += (a, b) => { }; // ERROR CS1593
        Changed += a => { };
        Handler += (a, b) => { }; // ERROR CS1593
        Handler -= a => { };
        var filtered = numbers.Where((a, b, c) => true); // ERROR CS1593
        var kept = numbers.Where((a, i) => i > 0);
        Func<int, int> anonymous = delegate () { return 1; }; // ERROR CS1593
        Func<int, int> any = delegate { return 1; };
        Console.WriteLine(twice(1) + ok(1) + constant() + sum(1, 2) + add(1, 2) + anonymous(1) + any(1) + filtered.Count() + kept.Count());
        done();
        fine();
        Changed?.Invoke(1);
    }
}
