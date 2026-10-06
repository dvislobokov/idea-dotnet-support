// CS0815: Cannot assign <null> / void to an implicitly-typed variable. Lines marked `// ERROR CSxxxx` must show that error in the editor
// (and in `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0815;

public class VoidLog
{
    public void Write(string text) => Console.WriteLine(text);
    public string Read() => "line";
    public Task FlushAsync() => Task.CompletedTask;
    public Task<int> CountAsync() => Task.FromResult(1);
}

public class VoidCases
{
    public async Task Run(VoidLog log)
    {
        var nothing = null; // ERROR CS0815
        var written = log.Write("a"); // ERROR CS0815
        var printed = Console.WriteLine("b"); // ERROR CS0815
        var flushed = await log.FlushAsync(); // ERROR CS0815
        var line = log.Read();
        var count = await log.CountAsync();
        var pending = log.FlushAsync();
        string? empty = null;
        var maybe = (string?)null;
        Console.WriteLine($"{line} {count} {pending} {empty} {maybe}");
    }
}
