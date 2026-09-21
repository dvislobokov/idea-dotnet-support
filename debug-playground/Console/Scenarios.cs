using System.Diagnostics;
using Playground.Lib;

namespace Playground;

/// <summary>
/// One method per thing to check in the debugger. Lines worth a breakpoint are marked `// BP:name`, the comment says what to look at.
/// </summary>
public static class Scenarios
{
    /// <summary>Run without arguments. The rest (evil, crash, wait) are asked for by name: they hang, crash or never end.</summary>
    public static readonly string[] Safe =
        ["variables", "collections", "strings", "expensive", "setvalue", "stepping", "library", "closures", "exceptions", "async", "threads", "environment", "output"];

    public static async Task Run(string name)
    {
        switch (name)
        {
            case "variables": Variables(); break;
            case "collections": Collections(); break;
            case "strings": Strings(); break;
            case "expensive": Expensive(); break;
            case "setvalue": SetValue(); break;
            case "stepping": Stepping(); break;
            case "library": Library(); break;
            case "closures": Closures(); break;
            case "exceptions": Exceptions(); break;
            case "async": await Async(); break;
            case "threads": Threads(); break;
            case "environment": EnvironmentAndArguments(); break;
            case "output": Output(); break;
            case "evil": Evil(); break;
            case "crash": Crash(); break;
            case "wait": Wait(); break;
            default: Console.WriteLine($"Unknown scenario '{name}'"); break;
        }
    }

    // ---------------------------------------------------------------- values

    private static void Variables()
    {
        int number = 42;
        long big = 9_000_000_000;
        double ratio = 0.1 + 0.2;
        decimal money = 1234.56m;
        bool flag = true;
        char letter = 'ж';
        string text = "Привет, \"мир\"\tand a tab";
        string? nothing = null;
        int? maybe = 7;
        var when = new DateTime(2026, 9, 21, 14, 5, 9);
        var color = Color.Green;
        var access = Access.Read | Access.Write;
        var point = new Point(3, 4);
        var tuple = (Id: 1, Name: "tuple");
        var line = new OrderLine("Tea", 4.5m, 2);
        var person = new Person("Ada", 36) { Friend = new Person("Grace", 85) };
        object boxed = number;
        Console.WriteLine($"{number} {big} {ratio} {money} {flag} {letter} {text} {nothing} {maybe} {when} {color} {access} {point} {tuple} {line} {person} {boxed}"); // BP:variables — locals of every kind; Evaluate: person.Friend.Name, number * 2, text.Length, access.HasFlag(Access.Write)
    }

    private static void Collections()
    {
        var small = new List<int> { 1, 2, 3 };
        var byName = new Dictionary<string, Person> { ["ada"] = new("Ada", 36), ["grace"] = new("Grace", 85) };
        var set = new HashSet<string> { "a", "b" };
        var matrix = new int[3, 4];
        var jagged = new[] { new[] { 1 }, new[] { 1, 2 } };
        var huge = Enumerable.Range(0, 100_000).ToList();
        var hugeArray = new int[5_000_000];
        IEnumerable<int> lazy = huge.Where(x => x % 2 == 0);
        Console.WriteLine($"{small.Count} {byName.Count} {set.Count} {matrix.Length} {jagged.Length} {huge.Count} {hugeArray.Length} {lazy.First()}"); // BP:collections — expand `huge` and `hugeArray`: pages, no freeze; Evaluate: huge[99999], byName["ada"].Age, lazy.Count()
    }

    private static void Strings()
    {
        var longText = new string('x', 5000) + "END";
        var multiline = "line 1\nline 2\r\nline 3";
        var json = """{ "name": "raw string", "value": 1 }""";
        Console.WriteLine($"{longText.Length} {multiline.Length} {json.Length}"); // BP:strings — `longText` is longer than 4096: an error text instead of a value is fine, a broken session is not
    }

    private static void Expensive()
    {
        var slow = new SlowToString();
        var throwing = new ThrowingProperty();
        Console.WriteLine($"{slow.GetHashCode()} {throwing.GetHashCode()}"); // BP:expensive — `slow` takes 2 s to describe, `throwing.Value` shows the exception; then switch off "Allow property evaluations..." and compare
    }

    private static void SetValue()
    {
        int counter = 0;
        string label = "before";
        var person = new Person("Ada", 36);
        for (int i = 0; i < 3; i++)
        {
            counter += 10; // BP:setvalue — F2 on `counter`, `label`, `person.Age` (stage 3); the output below must show the new values
        }
        Console.WriteLine($"counter={counter} label={label} age={person.Age}");
    }

    // ---------------------------------------------------------------- stepping

    private static void Stepping()
    {
        var person = new Person("Ada", 36);
        var greeting = Greet(person); // BP:stepping — F7 enters Greet (not the getter of Name: step filtering), Shift+F8 comes back here
        var length = Measure(greeting);
        Console.WriteLine($"{greeting} {length}"); // Run to Cursor here from the breakpoint above
    }

    private static string Greet(Person person)
    {
        var name = person.Name;
        return $"Hello, {name}!";
    }

    private static int Measure(string text) => text.Length;

    private static void Library()
    {
        var lines = new[] { new OrderLine("Tea", 4.5m, 2), new OrderLine("Cup", 12m, 1) };
        var total = Pricing.Total(lines, 10); // BP:library — F7 opens Pricing.cs of the Lib project; a breakpoint set there beforehand stops too
        Console.WriteLine($"total={total}");
    }

    private static void Closures()
    {
        int captured = 5;
        Func<int, int> add = x => x + captured; // BP:lambda — put it on this line: stops when the lambda runs, `captured` is visible inside
        int Local(int y) => y * captured;
        Console.WriteLine($"{add(1)} {Local(2)}");
        foreach (var item in Numbers()) Console.WriteLine($"iterator {item}");
    }

    private static IEnumerable<int> Numbers()
    {
        yield return 1; // BP:iterator
        yield return 2;
    }

    // ---------------------------------------------------------------- exceptions, async, threads

    private static void Exceptions()
    {
        try
        {
            throw new ShopException("Out of stock", 409); // BP:exception — stage 3: the `all` filter with a condition on the type stops here
        }
        catch (ShopException e)
        {
            Console.WriteLine($"caught {e.Code}: {e.Message}"); // Evaluate: e.Code, $exception
        }

        try
        {
            int.Parse("not a number"); // thrown in external code
        }
        catch (FormatException e)
        {
            Console.WriteLine($"caught {e.GetType().Name}");
        }
    }

    private static async Task Async()
    {
        var before = Environment.CurrentManagedThreadId;
        var value = await Compute(20); // BP:async — F8 continues after the await, maybe on another thread; F7 into an async method is a known gap of the adapter
        var after = Environment.CurrentManagedThreadId;
        Console.WriteLine($"value={value} thread {before} -> {after}");
    }

    private static async Task<int> Compute(int input)
    {
        await Task.Delay(50);
        return input * 2; // BP:async-inner — the frames show the async call stack
    }

    private static void Threads()
    {
        var worker = new Thread(() =>
        {
            Thread.CurrentThread.Name = "Playground worker";
            Thread.Sleep(100); // BP:worker — the Debug window must select "Playground worker", not Main Thread
        });
        worker.Start();
        worker.Join();

        Parallel.For(0, 4, i =>
        {
            var square = i * i; // BP:parallel — several threads hit it one after another; each time the selected thread is the one that stopped
            Console.WriteLine($"parallel {i} -> {square}");
        });
    }

    // ---------------------------------------------------------------- launch

    private static void EnvironmentAndArguments()
    {
        var arguments = Environment.GetCommandLineArgs();
        var fromProfile = Environment.GetEnvironmentVariable("PLAYGROUND_FROM_PROFILE");
        var fromConfiguration = Environment.GetEnvironmentVariable("PLAYGROUND_FROM_CONFIGURATION");
        var environment = Environment.GetEnvironmentVariable("DOTNET_ENVIRONMENT");
        var directory = Environment.CurrentDirectory;
        Console.WriteLine($"args: {string.Join(" | ", arguments.Skip(1))}"); // BP:environment
        Console.WriteLine($"PLAYGROUND_FROM_PROFILE={fromProfile} (launchSettings.json: expected 'yes')");
        Console.WriteLine($"PLAYGROUND_FROM_CONFIGURATION={fromConfiguration} (set it in the run configuration)");
        Console.WriteLine($"DOTNET_ENVIRONMENT={environment} cwd={directory}");
    }

    private static void Output()
    {
        Console.WriteLine("stdout: кириллица, 日本語, emoji ✓"); // non-ASCII output is a known problem of the adapter on Windows
        Console.Error.WriteLine("stderr: this line goes to the error stream");
        Debug.WriteLine("Debug.WriteLine: shown by a debugger only");
        Console.WriteLine(@"   at Playground.Scenarios.Output() in C:\fake\Scenarios.cs:line 1 (looks like a stack frame)");
    }

    // ---------------------------------------------------------------- by name only

    /// <summary>`evil`: a getter that never returns. Expanding the object must not lose the session; Stop has to work within seconds.</summary>
    private static void Evil()
    {
        var evil = new EndlessGetter();
        Console.WriteLine(evil.GetHashCode()); // BP:evil — expand `evil`, then press Stop while it is being evaluated
    }

    /// <summary>`crash`: an unhandled exception. Known gap of the adapter: it does not stop here and reports exit code 0.</summary>
    private static void Crash() => throw new InvalidOperationException("Unhandled on purpose");

    /// <summary>`wait`: runs until stopped — for Pause, Stop and (stage 5) Attach to Process.</summary>
    private static void Wait()
    {
        Console.WriteLine($"Process id {Environment.ProcessId}; press Pause, then Stop.");
        for (var tick = 0; ; tick++)
        {
            Thread.Sleep(500); // after Pause the frames end here
            if (tick % 10 == 0) Console.WriteLine($"tick {tick}");
        }
    }
}
