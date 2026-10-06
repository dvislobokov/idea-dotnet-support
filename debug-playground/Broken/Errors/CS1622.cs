// CS1622: Cannot return a value from an iterator. Use the yield return statement to return a value, or yield break to end the iteration. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1622;

public class Sequences
{
    private readonly List<int> _values = new() { 1, 2, 3 };

    public IEnumerable<int> Positive()
    {
        foreach (var v in _values)
            if (v > 0) yield return v;
        return _values; // ERROR CS1622
    }

    public IEnumerator<int> GetEnumerator()
    {
        if (_values.Count == 0) return; // ERROR CS1622
        foreach (var v in _values) yield return v;
    }

    public IEnumerable<string> Names
    {
        get
        {
            yield return "first";
            return new List<string>(); // ERROR CS1622
        }
    }

    public void Run()
    {
        IEnumerable<int> Twice()
        {
            yield return 2;
            return null; // ERROR CS1622
        }
        Console.WriteLine(Twice().Count());
    }

    // the legal look-alikes: yield break, a return in a lambda of an iterator, a method that only returns
    public IEnumerable<int> Ok()
    {
        Func<int> first = () => { return _values[0]; };
        yield return first();
        yield break;
    }

    public IEnumerable<int> All() { return _values; }
    public IEnumerable<int> Lazy() => _values.Where(v => v > 1);
}
