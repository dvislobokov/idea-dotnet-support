// CS0152: The switch statement contains multiple cases with the label value '1'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0152;

public class Labels
{
    public string Name(int code)
    {
        switch (code)
        {
            case 1: return "one";
            case 2: return "two";
            case 1: return "uno"; // ERROR CS0152
            case -1: return "minus";
            case 0x2: return "hex two"; // ERROR CS0152
            default: return "many";
        }
    }

    public int Parse(string text)
    {
        switch (text)
        {
            case "a":
            case "b":
                return 1;
            case "a": // ERROR CS0152
                return 2;
            case null:
                return 0;
            default:
                return -1;
        }
    }

    public bool IsVowel(char c, long size)
    {
        switch (size) { case 1: case 1L: return false; } // ERROR CS0152
        switch (c)
        {
            case 'a': case 'e': case 'a': return true; // ERROR CS0152
            case 'A': return true;
            default: return false;
        }
    }

    // the legal look-alikes: different values, a `when` clause, a switch expression
    public string Size(int n)
    {
        switch (n)
        {
            case 1: return "s";
            case 10: return "m";
            case int x when x > 100: return "l";
            case int x when x > 1000: return "xl";
            default: return "?";
        }
    }

    public string Short(int n) => n switch { 1 => "one", 2 => "two", _ => "many" };
}

// the values of constant expressions: enum members (an implicit one is the one before plus one), constants, folded operators, concatenation
public enum Level { Low, Mid = 5, High, Top = High + 1, Default = Low }

public class Constants
{
    const int Ten = 10;
    const string Hello = "hel" + "lo";
    const char Yes = 'y';

    public string Name(Level level)
    {
        switch (level)
        {
            case Level.Low: return "low";
            case Level.Default: return "default"; // ERROR CS0152
            case Level.High: return "high";
            case Level.Top - 1: return "top"; // ERROR CS0152
            case Level.Mid: return "mid";
            default: return "?";
        }
    }

    public int Code(int n, string s, char c)
    {
        const int local = 3;
        switch (n)
        {
            case Ten: return 1;
            case 5 * 2: return 2; // ERROR CS0152
            case local: return 3;
            case 1 + 2: return 4; // ERROR CS0152
            case 1 << 4: return 5;
            case 0x10: return 6; // ERROR CS0152
        }
        switch (s)
        {
            case Hello: return 1;
            case "hello": return 2; // ERROR CS0152
        }
        switch (c)
        {
            case Yes: return 1;
            case 'y': return 2; // ERROR CS0152
            case 'n': return 3;
        }
        return 0;
    }

    // the legal look-alikes: values that differ once folded, a char next to its neighbour code
    public int Ok(Level level, int n)
    {
        switch (level) { case Level.Mid: return 1; case Level.High: return 2; case Level.Top: return 3; }
        switch (n) { case Ten + 1: return 1; case Ten - 1: return 2; case Ten: return 3; case 'a': return 4; case 98: return 5; }
        return 0;
    }
}
