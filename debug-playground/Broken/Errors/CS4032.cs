// CS4032: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task<int>'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS4032;

public class Loader
{
    private readonly List<string> _items = new();

    public int Load()
    {
        await Task.Delay(10); // ERROR CS4032
        return _items.Count;
    }

    public string Read() => await Task.FromResult("x"); // ERROR CS4032

    public List<string> Items
    {
        get
        {
            await Task.Yield(); // ERROR CS4032
            return _items;
        }
    }

    public int Run()
    {
        int Fetch()
        {
            var value = await Task.FromResult(1); // ERROR CS4032
            return value;
        }
        return Fetch();
    }

    // the legal look-alikes
    public async Task<int> LoadAsync() { await Task.Delay(10); return _items.Count; }
    public Task<int> CountAsync() => Task.FromResult(_items.Count);
    public async Task<string> ReadAsync() => await Task.FromResult("x");
    public Func<Task<int>> Later() => async () => await Task.FromResult(3);
    public int Sync() { var task = Task.FromResult(1); return task.Result; }
}

// the suggested return type keeps the annotations inside it and drops the one of the type itself
public class Annotated
{
    public string? Find() { await Task.Yield(); return null; } // ERROR CS4032
    public List<string?> All() { await Task.Yield(); return new(); } // ERROR CS4032
    public Task<string?> Later() { await Task.Yield(); return Task.FromResult<string?>(null); } // ERROR CS4032
    public async Task<string?> Ok() { await Task.Yield(); return null; }
}
