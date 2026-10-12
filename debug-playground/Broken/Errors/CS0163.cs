// CS0163: Control cannot fall through from one case label ('case 1:') to another. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0163;

public enum Shade { Light, Dark, System }

public class FallThrough
{
    public int Score(int level, bool bonus)
    {
        var score = 0;
        switch (level)
        {
            case 1: // ERROR CS0163
                score += 10;
            case 2:
                score += 20;
                break;
            case 3:
            case 4: // ERROR CS0163
                if (bonus) score *= 2;
            default:
                score = -1;
                break;
        }
        return score;
    }

    public string Theme(Shade shade)
    {
        var name = "system";
        switch (shade)
        {
            case Shade.Light: // ERROR CS0163
                Console.WriteLine("light");
            case Shade.Dark:
                name = "dark";
                break;
        }
        return name;
    }

    // the legal look-alikes: every section ends with a jump, empty sections share the next one's statements
    public int Ok(int level, bool bonus)
    {
        switch (level)
        {
            case 1:
            case 2:
                return 1;
            case 3:
                if (bonus) return 3;
                else break;
            case 4:
                throw new InvalidOperationException();
            case 5:
                while (true) { }
            case 6:
                { return 6; }
            default:
                break;
        }
        return 0;
    }
}

// a `continue` without a loop and a `goto` without its label do not jump: control goes on into the next section
public class BrokenJumps
{
    public int Next(int level, string name)
    {
        switch (level)
        {
            case 1: // ERROR CS0163
                continue; // ERROR CS0139
            case 2: // ERROR CS0163
                goto case 7; // ERROR CS0159
            case 3:
                goto case 1;
            default:
                break;
        }
        switch (name)
        {
            case "a": // ERROR CS0163
                goto case "b"; // ERROR CS0159
            case "c":
                goto case "a";
            default:
                break;
        }
        while (level > 0)
        {
            switch (level--) { case 1: continue; default: break; }
        }
        return level;
    }
}
