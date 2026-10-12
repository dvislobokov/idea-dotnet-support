// CS8031: Async lambda expression converted to a 'Task' returning delegate cannot return a value. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS8031;

public delegate Task Job();

public class Jobs
{
    public void Schedule()
    {
        Func<Task> save = async () => { await Task.Yield(); return 1; }; // ERROR CS8031
        Func<ValueTask> flush = async () => { await Task.Yield(); return 2; }; // ERROR CS8031
        Job job = async () => { await Task.Yield(); return 3; }; // ERROR CS8031
        Func<Task> old = async delegate { await Task.Yield(); return 4; }; // ERROR CS8031
        Run(save, flush, job, old);
    }

    // the legal look-alikes: a bare return, Task<T> with a value, a lambda that is not async
    public void Ok()
    {
        Func<Task> save = async () => { await Task.Yield(); return; };
        Func<Task<int>> count = async () => { await Task.Yield(); return 1; };
        Func<Task> plain = () => { return Task.CompletedTask; };
        Run(save, count, plain);
    }

    private static void Run(params object[] jobs) { }
}
