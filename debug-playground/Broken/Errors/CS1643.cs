// CS1643: Not all code paths return a value in lambda expression of type 'D'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1643;

public class Paths
{
    public Func<int, int> Rule { get; set; } = x => { if (x > 0) return 1; }; // ERROR CS1643
    public Func<int, int> Full { get; set; } = x => { return x; };

    public void Run(Func<int, int> step) { }
    public Func<int, int> Sign() { return x => { if (x > 0) return 1; }; } // ERROR CS1643
    public Func<int, int> Same() => x => { return x; };

    public void Use(List<int> numbers)
    {
        Func<int, int> sign = x => { if (x > 0) return 1; }; // ERROR CS1643
        Func<int, int> full = x => { if (x > 0) return 1; else return -1; };
        Func<int, string> name = x => // ERROR CS1643
        {
            switch (x)
            {
                case 1: return "one";
            }
        };
        Func<int, string> named = x =>
        {
            switch (x)
            {
                case 1: return "one";
                default: return "many";
            }
        };
        Func<int> nothing = () => { }; // ERROR CS1643
        Func<int> forever = () => { while (true) { } };
        Func<int> fails = () => { throw new InvalidOperationException(); };
        Run(x => { if (x > 0) return 1; }); // ERROR CS1643
        Run(x => { return x; });
        Func<Task<int>> later = async () => { await Task.Yield(); }; // ERROR CS1643
        Func<Task> work = async () => { await Task.Yield(); };
        Func<int, int> anonymous = delegate (int x) { if (x > 0) return 1; }; // ERROR CS1643
        Action<int> act = x => { if (x > 0) return; };
        Console.WriteLine(sign(1) + full(1) + name(1) + named(1) + nothing() + forever() + fails() + numbers.Count);
        Console.WriteLine(later().Result + anonymous(1));
        work();
        act(1);
    }
}
