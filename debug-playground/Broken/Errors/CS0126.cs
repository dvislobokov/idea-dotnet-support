// CS0126: An object of a type convertible to 'int' is required. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0126;

public class EmptyReturns
{
    private readonly List<string> _names = new();

    public int Count()
    {
        if (_names.Count == 0) return; // ERROR CS0126
        return _names.Count;
    }

    public string First()
    {
        return; // ERROR CS0126
    }

    public List<string> Names
    {
        get { return; } // ERROR CS0126
    }

    public async Task<int> LoadAsync()
    {
        await Task.Delay(1);
        return; // ERROR CS0126
    }

    public Task SaveAsync()
    {
        return; // ERROR CS0126
    }

    public int Local()
    {
        int Twice(int x)
        {
            if (x < 0) return; // ERROR CS0126
            return x * 2;
        }
        return Twice(2);
    }

    // the legal look-alikes: `return;` where nothing is returned
    public void Clear()
    {
        if (_names.Count == 0) return;
        _names.Clear();
    }

    public async Task ClearAsync()
    {
        await Task.Delay(1);
        return;
    }

    public IEnumerable<string> All()
    {
        foreach (var name in _names) yield return name;
        yield break;
    }

    public int Value
    {
        get => _names.Count;
        set { if (value < 0) return; }
    }

    public EmptyReturns() { return; }

    public Func<int> Counter() => () => _names.Count;
}

// a lambda: the delegate type written next to it says what `return` must carry; nullable annotations inside the type stay in the message
public class Lambdas
{
    public void Run()
    {
        Func<int> count = () => { return; }; // ERROR CS0126
        Func<Task<string>> load = async () => { await Task.Yield(); return; }; // ERROR CS0126
        Func<int?> maybe = () => { if (count() > 0) return 1; return; }; // ERROR CS0126
        Func<int> ok = () => { Action inner = () => { return; }; return 1; };
        Func<Task> okAsync = async () => { await Task.Yield(); return; };
    }

    public List<string?> Names() { return; } // ERROR CS0126
    public (string? Name, int Age) Person() { return; } // ERROR CS0126
}
