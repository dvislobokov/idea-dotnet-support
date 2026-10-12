// CS0165: Use of unassigned local variable 'x'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0165;

public enum Level { Low, High }

public class Unassigned
{
    private static bool Ready() => DateTime.Now.Ticks > 0;
    private static void Show(object? value) => Console.WriteLine(value);

    public void OneBranch(int count)
    {
        string label;
        if (count > 0)
            label = "some";
        Show(label); // ERROR CS0165

        string both;
        if (count > 0) both = "some";
        else both = "none";
        Show(both);
    }

    public void SwitchWithoutDefault(Level level)
    {
        int weight;
        switch (level)
        {
            case Level.Low: weight = 1; break;
            case Level.High: weight = 2; break;
        }
        Show(weight); // ERROR CS0165

        int score;
        switch (level)
        {
            case Level.Low: score = 1; break;
            default: score = 2; break;
        }
        Show(score);
    }

    public void TryCatch(string text)
    {
        int parsed;
        try { parsed = int.Parse(text); }
        catch (FormatException) { Show("bad"); }
        Show(parsed); // ERROR CS0165

        int rethrown;
        try { rethrown = int.Parse(text); }
        catch (FormatException) { throw; }
        Show(rethrown);
    }

    public void Patterns(object value, bool flag)
    {
        if (value is string text || flag)
            Show(text); // ERROR CS0165
        if (!(value is string name)) return;
        Show(name);
        if (flag && int.TryParse(name, out int number)) Show(number);
        else Show(number); // ERROR CS0165
        if (int.TryParse(name, out var other)) Show(other);
    }

    public void Loops(List<int> items)
    {
        int last;
        foreach (var item in items) last = item;
        Show(last); // ERROR CS0165

        int found;
        while (true)
        {
            if (Ready()) { found = 1; break; }
        }
        Show(found);

        int counter;
        counter++; // ERROR CS0165
    }

    public void Captured()
    {
        int later;
        Action print = () => Show(later); // ERROR CS0165
        later = 1;
        print();
        int early = 2;
        Action ok = () => Show(early);
        ok();
        Show(nameof(later));
    }
}
