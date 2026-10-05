// Errors and warnings of task D2 of CSHARP_PSI_MIGRATION.md (0.1.78): the plugin's own pass, without the language server. Excluded from
// the compilation of Broken (Broken.csproj), like SemanticErrors.cs; the editor still sees the file. Every line under a "permanent" marker
// is as it is on purpose: compare the marks with EXPECT, and with the server (Settings | .NET | Language Server, «Errors and warnings» =
// Language server) — the same codes in the same places. The lines marked "silent" are correct: NO mark there.
#nullable enable
using System.Threading.Tasks;

namespace DebugPlayground.Broken;

public class SemanticErrors2
{
    private int _instance;
    private static int _counter;

    // TYPE:sem2-unreachable (permanent) — EXPECT: the lines after `return x;` gray (as Rider), the tooltip «CS0162: Unreachable code
    // detected» on `Console`; in Loop the line after `while (true) { }` gray. Silent: the line after `if (flag) return;` in Fine.
    public int Unreachable(int x)
    {
        return x;
        Console.WriteLine(x);
        x++;
    }

    public void Loop()
    {
        while (true) { }
        Console.WriteLine();
    }

    public void Fine(bool flag)
    {
        if (flag) return;
        Console.WriteLine();
    }

    // TYPE:sem2-unused (permanent) — EXPECT: `a` gray «CS0168: The variable 'a' is declared but never used», `b` and `c` gray «CS0219: The
    // variable 'b' is assigned but its value is never used». Alt+Enter on `a`: «Remove unused variable» removes the line (Ctrl+Z after).
    // Silent: `d` (a call may matter), `e` (read).
    public void Unused()
    {
        int a;
        int b = 5;
        string c = "x"; c = "y";
        int d = Compute();
        int e = 1; Console.WriteLine(e);
    }

    private static int Compute() => 1;

    // TYPE:sem2-static (permanent) — EXPECT: red «CS0120: An object reference is required for the non-static field, method, or property
    // 'DebugPlayground.Broken.SemanticErrors2._instance'» on `_instance`, the same for `Helper` ('...SemanticErrors2.Helper()').
    // Silent: `_counter` (static), `nameof(_instance)`.
    public static void StaticUse()
    {
        _instance = 1;
        Helper();
        _counter++;
        Console.WriteLine(nameof(_instance));
    }

    private void Helper() { }

    // TYPE:sem2-await (permanent) — EXPECT: yellow «CS4014: Because this call is not awaited, …» on `Work()` and on `Task.Delay(1)`;
    // Alt+Enter on `Work()`: «Add 'await'» makes it `await Work();` (Ctrl+Z after). Silent: `_ = Work();`, `await Work();`, NotAsync.
    public async Task NotAwaited()
    {
        Work();
        Task.Delay(1);
        _ = Work();
        await Work();
    }

    public void NotAsync() => Work();

    private Task Work() => Task.CompletedTask;

    // TYPE:sem2-nullable (permanent) — `#nullable enable` at the top. EXPECT: yellow «CS8600: Converting null literal or possible null value
    // to non-nullable type.» on `null` of `s`, «CS8625: Cannot convert null literal to non-nullable reference type.» on `null` of `Take(null)`
    // and of `_name = null`, «CS8603: Possible null reference return.» on `null` of Name. Silent: `string? ok = null`, `null!`.
    private string _name = "";

    public string Name() => null;

    public void Nullables()
    {
        string s = null;
        string? ok = null;
        string forgiven = null!;
        Take(null);
        _name = null;
        Console.WriteLine(s + ok + forgiven);
    }

    private void Take(string value) => Console.WriteLine(value);

    // TYPE:sem2-arguments (permanent) — EXPECT: red «CS1503: Argument 1: cannot convert from 'string' to 'int'» on `"x"` of One, «CS7036:
    // There is no argument given that corresponds to the required parameter 'a' of 'SemanticErrors2.Two(int, string)'» on `Two` (named
    // argument only), «CS1503» on `"y"` of Gen (its `int` parameter), «CS1503» on `"z"` of Many (`params int[]`). Silent: the last line.
    private static int One(int a) => a;
    private static int Two(int a, string b = "") => a;
    private static int Gen<T>(T a, int b) => b;
    private static int Many(params int[] xs) => xs.Length;

    public void Arguments()
    {
        One("x");
        Two(b: "x");
        Gen("x", "y");
        Many("z");
        One(1); Two(1, b: "x"); Gen("x", 1); Many(); Many(1, 2);
    }

    // TYPE:sem2-target-typed (permanent) — EXPECT: red «CS0029: Cannot implicitly convert type 'string' to 'int'» on the whole
    // `flag ? "a" : "b"`, and on each of `"x"` and `"y"` of the switch expression; «CS0266: Cannot implicitly convert type 'int' to 'byte'.
    // An explicit conversion exists (are you missing a cast?)» on `flag ? 1 : 2` of `small` (the natural type `int` decides, though each
    // constant would fit). Silent: `big`, `number` (each arm converts).
    public void TargetTyped(bool flag, string text)
    {
        int a = flag ? "a" : "b";
        int c = text switch { "a" => "x", _ => "y" };
        byte small = flag ? 1 : 2;
        long big = flag ? 1 : 2;
        byte number = text switch { "a" => 1, _ => 2 };
    }
}

// TYPE:sem2-uninitialized (permanent) — EXPECT: yellow «CS8618: Non-nullable field 'Name' must contain a non-null value when exiting
// constructor. …» on `Name`, «… property 'Title' …» on `Title`. Silent: `Note` (nullable), `Code` (initialized), `Id` (required), WithCtor.
public class NoConstructor
{
    public string Name;
    public string Title { get; set; }
    public string? Note;
    public string Code = "";
    public required string Id;
}

public class WithCtor
{
    public string Name;
    public WithCtor() { Name = ""; }
}
