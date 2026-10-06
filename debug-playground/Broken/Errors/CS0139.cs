// CS0139: No enclosing loop out of which to break or continue. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0139;

public class Jumps
{
    public void Check(int value)
    {
        if (value < 0)
            break; // ERROR CS0139
        Console.WriteLine(value);
    }

    public void Skip(List<int> values)
    {
        if (values.Count == 0) continue; // ERROR CS0139
        values.Clear();
    }

    public void Kind(int kind)
    {
        while (kind > 0)
        {
            kind--;
        }
        continue; // ERROR CS0139
    }

    public void Cleanup()
    {
        try { Console.WriteLine("work"); }
        finally { break; } // ERROR CS0139
    }

    // the legal look-alikes: inside loops and switch sections
    public int Sum(List<int> values)
    {
        var sum = 0;
        foreach (var v in values)
        {
            if (v < 0) continue;
            if (v > 100) break;
            sum += v;
        }
        for (var i = 0; ; i++) { if (i > 3) break; }
        do { sum++; } while (sum < 10 && sum != 5);
        while (true)
        {
            switch (sum)
            {
                case 1: break;
                default: continue;
            }
            break;
        }
        return sum;
    }
}
