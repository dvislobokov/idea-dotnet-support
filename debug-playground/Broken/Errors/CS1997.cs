// CS1997: Since 'C.M()' is an async method that returns 'Task', a return keyword must not be followed by an object expression. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1997;

public class AsyncReturns
{
    private readonly Dictionary<string, int> _cache = new();

    public async Task SaveAsync(string key, int value)
    {
        await Task.Delay(1);
        _cache[key] = value;
        return value; // ERROR CS1997
    }

    public async ValueTask FlushAsync()
    {
        await Task.Yield();
        return _cache.Count; // ERROR CS1997
    }

    public async Task<int> Run()
    {
        async Task Local()
        {
            await Task.Delay(1);
            return true; // ERROR CS1997
        }
        await Local();
        return 1;
    }

    // the legal look-alikes
    public async Task<int> CountAsync() { await Task.Delay(1); return _cache.Count; }
    public async ValueTask<string> NameAsync() { await Task.Delay(1); return "cache"; }
    public async Task StopAsync() { await Task.Delay(1); return; }
    public Task<int> CachedAsync() { return Task.FromResult(_cache.Count); }
    public Task DoneAsync() { return Task.CompletedTask; }
    public Func<Task<int>> Lazy() => async () => { await Task.Delay(1); return 2; };
}
