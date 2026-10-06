// CS0501: 'C.M()' must declare a body because it is not marked abstract, extern, or partial. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0501;

public class Service
{
    public void Start(); // ERROR CS0501
    public int Stop(bool force); // ERROR CS0501
    public static string Version(); // ERROR CS0501
    public void Run() { }
    public int Status => 0;
    public string Name { get; set; } = "";
    public static extern int GetTickCount();
}

public abstract class Worker
{
    public abstract void Work();
    public void Rest(); // ERROR CS0501
}

public partial class Job
{
    partial void OnStarted();
    public void Start() => OnStarted();
}

public struct Counter
{
    public void Increment(); // ERROR CS0501
    public int Value { get; set; }
}

public interface IService
{
    void Start();
    void Stop() { }
}

public partial class Task2
{
    public void Begin(); // ERROR CS0501
    partial void OnBegun();
    public void Run() => OnBegun();
}

public partial class Task2 { }
