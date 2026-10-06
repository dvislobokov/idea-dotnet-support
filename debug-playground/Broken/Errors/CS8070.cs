// CS8070: Control cannot fall out of switch from final case label ('default:'). Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS8070;

public class FallOut
{
    private int _count;

    public void Count(int kind)
    {
        switch (kind)
        {
            case 1:
                _count++;
                break;
            default: // ERROR CS8070
                _count--;
        }
    }

    public void Log(string level)
    {
        switch (level)
        {
            case "info":
                Console.WriteLine("info");
                break;
            case "warn":
            case "error": // ERROR CS8070
                Console.WriteLine(level);
        }
    }

    public void Range(int n)
    {
        switch (n)
        {
            case 0:
                return;
            case int x when x > 0: // ERROR CS8070
                _count += x;
        }
    }

    // the legal look-alikes: the last section ends with break, return or throw
    public int Ok(int n)
    {
        switch (n)
        {
            case 0: return 0;
            default: _count++; break;
        }
        switch (n)
        {
            case 1: _count++; break;
            case 2: throw new ArgumentException(nameof(n));
        }
        return _count;
    }
}

// a `goto` without its label in the last section does not jump either
public class BrokenLastJump
{
    public void Run(int n, string s)
    {
        switch (n)
        {
            case 1:
                break;
            default: // ERROR CS8070
                goto case 5; // ERROR CS0159
        }
        switch (s)
        {
            case "a":
                goto case "b";
            case "b":
                return;
        }
    }
}
