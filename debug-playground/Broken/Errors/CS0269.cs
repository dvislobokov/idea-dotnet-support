// CS0269: Use of unassigned out parameter 'x'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0269;

public class OutReads
{
    public void Increment(out int count)
    {
        count++; // ERROR CS0269
    }

    public void Accumulate(out int total)
    {
        total = 0;
        total += 5;
    }

    public void Log(out string message)
    {
        Console.WriteLine(message); // ERROR CS0269
        message = "done";
    }

    public void LogAfter(out string message)
    {
        message = "done";
        Console.WriteLine(message);
    }

    public bool Check(bool flag, out int code)
    {
        if (flag) code = 1;
        if (code > 0) return true; // ERROR CS0269 CS0177
        code = 0;
        return false;
    }

    public void Copy(int[] source, out int[] target)
    {
        target.CopyTo(source, 0); // ERROR CS0269
        target = source;
    }

    public void Forward(out int value)
    {
        Fill(out value);
        Console.WriteLine(value);
    }

    private static void Fill(out int value) => value = 42;
}
