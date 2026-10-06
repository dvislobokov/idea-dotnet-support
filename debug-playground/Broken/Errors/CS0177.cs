// CS0177: The out parameter 'x' must be assigned to before control leaves the current method. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0177;

public class OutParameters
{
    private readonly Dictionary<string, int> _ages = new();

    public bool TryGetAge(string name, out int age)
    {
        if (_ages.ContainsKey(name))
        {
            age = _ages[name];
            return true;
        }
        return false; // ERROR CS0177
    }

    public bool TryGetAgeFixed(string name, out int age)
    {
        if (_ages.TryGetValue(name, out age)) return true;
        age = -1;
        return false;
    }

    public void Split(string text, out string head, out string tail) // ERROR CS0177
    {
        var index = text.IndexOf(' ');
        head = index < 0 ? text : text.Substring(0, index);
        if (index >= 0) tail = text.Substring(index + 1);
    }

    public void Parse(string text, out int value) // ERROR CS0177
    {
        try { value = int.Parse(text); }
        catch (FormatException) { Console.WriteLine("bad number"); }
    }

    public void ParseOrThrow(string text, out int value)
    {
        try { value = int.Parse(text); }
        catch (FormatException) { throw new ArgumentException(text); }
    }

    public void InFinally(out int value)
    {
        try { return; }
        finally { value = 0; }
    }

    public void ExpressionBodied(out int value) => Console.WriteLine("none"); // ERROR CS0177

    public OutParameters(out bool created) // ERROR CS0177
    {
    }

    public OutParameters(int seed, out int doubled) => doubled = seed * 2;

    public int Local()
    {
        return Twice(2, out var result) + result;

        static int Twice(int x, out int y)
        {
            if (x > 0) y = x * 2;
            return x; // ERROR CS0177
        }
    }
}
