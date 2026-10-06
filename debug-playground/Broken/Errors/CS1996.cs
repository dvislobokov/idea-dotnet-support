// CS1996: Cannot await in the body of a lock statement. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1996;

public class Cache
{
    private readonly object _gate = new();
    private readonly Dictionary<string, string> _items = new();

    public async Task PutAsync(string key)
    {
        lock (_gate)
        {
            _items[key] = await Task.FromResult(key); // ERROR CS1996
        }
    }

    public async Task<int> CountAsync()
    {
        lock (_gate)
        {
            if (_items.Count == 0)
            {
                await Task.Delay(1); // ERROR CS1996
            }
            return _items.Count;
        }
    }

    public async Task ClearAsync()
    {
        lock (_gate) await Task.Yield(); // ERROR CS1996
    }

    // the legal look-alikes: await before or after the lock, in a lambda inside it, in the expression of a lock
    public async Task SafeAsync(string key)
    {
        var value = await Task.FromResult(key);
        lock (_gate) { _items[key] = value; }
        await Task.Delay(1);
        lock (_gate) { Func<Task> later = async () => await Task.Delay(1); _ = later; }
        lock (await Task.FromResult(_gate)) { _items.Remove(key); }
    }
}
