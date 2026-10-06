// CS0136: A local or parameter named 'x' cannot be declared in this scope because that name is used in an enclosing local scope to define a local or parameter. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0136;

public class ShadowedNames(int seed)
{
    private int _count;

    public int Seed => seed;

    public void Parameter(int id, List<int> items)
    {
        foreach (var item in items)
        {
            var id = item * 2; // ERROR CS0136
            Console.WriteLine(id);
        }
        foreach (var id in items) Console.WriteLine(id); // ERROR CS0136
    }

    public void LaterOuter(int[] values)
    {
        for (var i = 0; i < values.Length; i++)
        {
            var sum = values[i]; // ERROR CS0136
            Console.WriteLine(sum);
        }
        var sum = 0;
        Console.WriteLine(sum);
    }

    public void CatchAndUsing(string path)
    {
        var error = "";
        try { Console.WriteLine(path); } catch (Exception error) { Console.WriteLine(error); } // ERROR CS0136
        using (var stream = new MemoryStream()) { var stream = 1; Console.WriteLine(stream); } // ERROR CS0136
        Console.WriteLine(error);
    }

    public void Patterns(object value, object other)
    {
        if (value is int number) Console.WriteLine(number);
        else if (other is int number) Console.WriteLine(number); // ERROR CS0136
        while (other is string text) { Console.WriteLine(text); break; } // ERROR CS0136
        var text = "";
        Console.WriteLine(text);
    }

    public void Lambdas(int factor)
    {
        Func<int, int> scale = x => { var factor = 2; return x * factor; };
        Func<int, int> inner = y => { var y = 2; return y; }; // ERROR CS0136
        Func<int, int> twice = x => x * 2;
        Func<int, int> shadow = factor => factor + 1;
        Func<int, int> statics = static factor => factor * 3;
        var x = 1;
        int Local(int x) { var factor = x; return factor; }
        Console.WriteLine(scale(x) + inner(x) + twice(x) + shadow(x) + statics(x) + Local(x));
    }

    public void LocalFunctions()
    {
        void notify() { } // ERROR CS0136
        void notify() { } // ERROR CS0136
        int notify = 1;
        { void format() { } format(); } // ERROR CS0136
        var format = "";
        void log() => Console.WriteLine(format);
        { var log = 1; Console.WriteLine(log); } // ERROR CS0136
        log();
        Console.WriteLine(notify);
    }

    public void Queries(string[] names, int[] numbers)
    {
        var digits = from name in names where int.TryParse("1", out var name) select name; // ERROR CS0136
        var parsed = 0;
        var fine = from name in names where int.TryParse(name, out var parsed) select parsed;
        var twoRanges = from name in names from number in numbers where int.TryParse(name, out var number) select name;
        var ordered = from number in numbers orderby int.TryParse("", out var number) ? 1 : 0 select number; // ERROR CS0136
        Console.WriteLine(digits.Count() + parsed + fine.Count() + twoRanges.Count() + ordered.Count());
    }

    public void Fine()
    {
        var _count = 1;
        var seed = 2;
        { var block = 1; Console.WriteLine(block); }
        { var block = 2; Console.WriteLine(block); }
        Console.WriteLine(_count + seed + this._count);
    }

    public int this[int index]
    {
        get { var index = 0; return index; } // ERROR CS0136
    }

    public int Property
    {
        get => _count;
        set { var value = 1; _count = value; } // ERROR CS0136
    }
}
