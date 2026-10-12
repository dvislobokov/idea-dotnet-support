// CS0818: Implicitly-typed variables must be initialized. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0818;

public class UninitCases
{
    public int Run(bool flag)
    {
        var total; // ERROR CS0818
        var name; // ERROR CS0818
        int count;
        var ready = flag;
        if (ready) count = 1; else count = 2;
        foreach (var item in new[] { 1, 2 }) count += item;
        for (var i = 0; i < 2; i++) count++;
        var result; // ERROR CS0818
        string text;
        text = "x";
        var pair; // ERROR CS0818
        return count + text.Length;
    }
}
