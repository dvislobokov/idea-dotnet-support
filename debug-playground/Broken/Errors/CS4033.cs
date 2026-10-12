// CS4033: The 'await' operator can only be used within an async method. Consider marking this method with the 'async' modifier and changing its return type to 'Task'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS4033;

public class Worker
{
    private int _done;

    public void Run()
    {
        await Task.Delay(10); // ERROR CS4033
        _done++;
    }

    public Worker(int delay)
    {
        await Task.Delay(delay); // ERROR CS4033
    }

    public int Done
    {
        get => _done;
        set
        {
            await Task.Yield(); // ERROR CS4033
            _done = value;
        }
    }

    public void Process()
    {
        void Step()
        {
            await Task.Delay(1); // ERROR CS4033
        }
        Step();
    }

    // the legal look-alikes
    public async Task RunAsync() { await Task.Delay(10); _done++; }
    public async void OnClick(object sender, EventArgs e) { await Task.Delay(1); }
    public void Start() { _ = Task.Run(async () => await Task.Delay(1)); }
    public void Wait() { Task.Delay(1).Wait(); }
    public void Later() { async Task Step() => await Task.Delay(1); _ = Step(); }
}
