// CS0159: No such label 'x' within the scope of the goto statement. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0159;

public class Gotos
{
    public int Retry(int attempts)
    {
        if (attempts > 3) goto failed; // ERROR CS0159
        return attempts;
    }

    public void Step(int state)
    {
        switch (state)
        {
            case 1:
                if (state > 0) goto case 3; // ERROR CS0159
                break;
            case 2:
                if (state > 0) goto default; // ERROR CS0159
                break;
        }
    }

    public void Nested(int n)
    {
        Action run = () =>
        {
            if (n > 0) goto done; // ERROR CS0159
        };
        run();
        done:
        Console.WriteLine(n);
    }

    // the legal look-alikes: labels of the same function, cases and default that exist
    public int Ok(int n)
    {
        var i = 0;
    again:
        i++;
        if (i < n) goto again;
        switch (n)
        {
            case 1: goto case 2;
            case 2: goto default;
            default: break;
        }
        if (i > 10) goto end;
        i = 0;
    end:
        return i;
    }
}

// `goto case` of every governing type, by the values of the labels: strings, enum members, chars, constants
public enum Step { First, Second }

public class Cases
{
    const int Seven = 7;

    public void Run(string s, Step step, char c, int n)
    {
        switch (s)
        {
            case "a":
                if (n > 0) goto case "c"; // ERROR CS0159
                break;
            case "b":
                if (n > 0) goto case "a";
                break;
        }
        switch (step)
        {
            case Step.First:
                if (n > 0) goto case Step.Second; // ERROR CS0159
                break;
        }
        switch (c)
        {
            case 'x':
                if (n > 0) goto case 'y'; // ERROR CS0159
                break;
            case 'z':
                break;
        }
        switch (n)
        {
            case 1:
                if (n > 0) goto case Seven; // ERROR CS0159
                break;
            case 3 + 4 + 1:
                if (n > 0) goto case 2 * 4;
                break;
        }
    }
}
