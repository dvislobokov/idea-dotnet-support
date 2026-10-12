// CS0128: A local variable or function named 'x' is already defined in this scope. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0128;

public class DuplicateLocals
{
    public int Plain()
    {
        var total = 1;
        var total = 2; // ERROR CS0128
        return total;
    }

    public void OutVariables(string text)
    {
        if (int.TryParse(text, out var parsed)) Console.WriteLine(parsed);
        var parsed = 0; // ERROR CS0128
        Console.WriteLine(int.TryParse(text, out var count) ? count : 0);
        Console.WriteLine(int.TryParse(text, out var count) ? 1 : 0); // ERROR CS0128
    }

    public void Patterns(object first, object second)
    {
        if (first is int number && second is int number) Console.WriteLine(); // ERROR CS0128
        if (first is string name) Console.WriteLine(name);
        while (second is string label) { Console.WriteLine(label); break; }
        while (second is string label) { Console.WriteLine(label); break; }
    }

    public void Deconstruction()
    {
        var (left, left) = (1, 2); // ERROR CS0128
        var (a, b) = (1, 2);
        Console.WriteLine(a + b);
    }

    public void Switch(int code)
    {
        switch (code)
        {
            case 1:
                var message = "one";
                Console.WriteLine(message);
                break;
            case 2:
                var message = "two"; // ERROR CS0128
                break;
        }
        switch ((object)code)
        {
            case int value when value > 0: Console.WriteLine(value); break;
            case long value: Console.WriteLine(value); break;
        }
    }

    public void LocalFunctions()
    {
        void Log() => Console.WriteLine("a");
        void Log() => Console.WriteLine("b"); // ERROR CS0128
        Log();
    }

    public void LocalsAndFunctions(int code)
    {
        int retry = 1;
        void retry() { } // ERROR CS0128
        int retry = 2; // ERROR CS0128
        void cleanup() { } // ERROR CS0136
        int cleanup = 3;
        void cleanup() { } // ERROR CS0128
        switch (code)
        {
            case 1:
                int handler = 1;
                Console.WriteLine(handler);
                break;
            case 2:
                void handler() { } // ERROR CS0128
                break;
        }
    }

    public void Queries(string[] names)
    {
        var parsed = from name in names where int.TryParse(name, out var n) && int.TryParse(name, out var n) select name; // ERROR CS0128
        var lengths = from name in names where int.TryParse(name, out var n) select name.Length;
        var others = from name in names select int.TryParse(name, out var n) ? n : 0;
        Console.WriteLine(parsed.Count() + lengths.Count() + others.Count());
    }

    public void Discards(string text)
    {
        var _ = 1;
        var _ = 2; // ERROR CS0128
        Console.WriteLine(int.TryParse(text, out var _));
    }

    public void Loops()
    {
        for (var i = 0; i < 2; i++) Console.WriteLine(i);
        for (var i = 0; i < 2; i++) Console.WriteLine(i);
        foreach (var item in new[] { 1 }) Console.WriteLine(item);
        foreach (var item in new[] { 2 }) Console.WriteLine(item);
        try { Console.WriteLine(); } catch (InvalidOperationException e) { Console.WriteLine(e); } catch (Exception e) { Console.WriteLine(e); }
        { var scoped = 1; Console.WriteLine(scoped); }
        { var scoped = 2; Console.WriteLine(scoped); }
        Func<int, int> twice = n => n * 2;
        Func<int, int> thrice = n => n * 3;
        Console.WriteLine(twice(1) + thrice(1));
    }
}
