// CS4034: The 'await' operator can only be used within an async lambda expression. Consider marking this lambda expression with the 'async' modifier. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS4034;

public class Handlers
{
    private readonly List<int> _values = new() { 1, 2, 3 };

    public async Task RunAsync()
    {
        Func<Task<int>> first = () => { await Task.Delay(1); return Task.FromResult(1); }; // ERROR CS4034
        Action log = delegate { await Task.Yield(); }; // ERROR CS4034
        var doubled = _values.Select(v => await Task.FromResult(v * 2)); // ERROR CS4034
        Func<int, Task> each = v => { await Task.Delay(v); return Task.CompletedTask; }; // ERROR CS4034
        await first();
        log();
        _ = doubled;
        await each(1);
    }

    // the legal look-alikes
    public async Task OkAsync()
    {
        Func<Task<int>> first = async () => { await Task.Delay(1); return 1; };
        Action log = async delegate { await Task.Yield(); };
        var doubled = _values.Select(async v => await Task.FromResult(v * 2));
        Func<int, Task> each = v => Task.Delay(v);
        await first();
        log();
        await Task.WhenAll(doubled);
        await each(1);
    }
}
