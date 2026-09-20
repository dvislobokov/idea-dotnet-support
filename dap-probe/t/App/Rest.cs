namespace DebugTarget;

static class AsyncCode
{
    public static async Task Run()
    {
        var first = await Compute(2); // BP:async-start
        var second = await Compute(first); // BP:async-second
        var all = await Task.WhenAll(Compute(1), Compute(2), Compute(3));
        Console.WriteLine(first + second + all.Sum()); // BP:async-end
    }

    static async Task<int> Compute(int x)
    {
        await Task.Delay(20);
        var doubled = x * 2; // BP:async-inner
        await Task.Yield();
        return doubled + 1;
    }
}

static class Threads
{
    static int _counter;

    public static void Run()
    {
        var threads = Enumerable.Range(1, 3).Select(n => new Thread(() => Worker(n)) { Name = "worker-" + n, IsBackground = true }).ToList();
        threads.ForEach(t => t.Start());
        Thread.Sleep(300);
        Console.WriteLine("main sees " + _counter); // BP:threads-main
        Thread.Sleep(300);
    }

    static void Worker(int id)
    {
        for (var i = 0; i < 1000; i++)
        {
            Interlocked.Increment(ref _counter);
            if (id == 2 && i == 3) Console.WriteLine("worker two at three"); // BP:threads-worker
            Thread.Sleep(20);
        }
    }
}

class ShopException(string message, int code) : Exception(message)
{
    public int Code { get; } = code;
}

static class Exceptions
{
    public static void Run()
    {
        try { throw new ShopException("custom", 17); } // BP:ex-custom
        catch (ShopException e) { Console.WriteLine("caught " + e.Code); }

        try { int.Parse("not a number"); }
        catch (FormatException e) { Console.WriteLine("caught " + e.GetType().Name); }

        try { Nested(); }
        catch (Exception e) { Console.WriteLine("caught outer: " + e.InnerException?.Message); }
        Console.WriteLine("exceptions done"); // BP:ex-done
    }

    static void Nested()
    {
        try { throw new ArgumentNullException("arg"); }
        catch (Exception inner) { throw new InvalidOperationException("wrapped", inner); }
    }

    public static void Unhandled()
    {
        Console.WriteLine("about to crash");
        throw new ShopException("nobody catches this", 99); // BP:unhandled-throw
    }

    public static async Task AsyncUnhandled()
    {
        await Task.Delay(10);
        throw new InvalidOperationException("async crash");
    }
}

class Box<T>(T value)
{
    public T Value { get; } = value;
    public TOut Map<TOut>(Func<T, TOut> f)
    {
        var mapped = f(Value); // BP:generic-method
        return mapped;
    }
}

static class Closures
{
    static readonly int StaticReadonly = Init();
    static int Init() => 11;

    public static void Run()
    {
        var factor = 3;
        var prefix = "n=";
        Func<int, string> format = n =>
        {
            var scaled = n * factor; // BP:lambda-body
            return prefix + scaled;
        };
        Console.WriteLine(format(5));

        int Local(int x)
        {
            var inner = x + factor; // BP:local-function
            return inner;
        }
        Console.WriteLine(Local(4));

        var box = new Box<int>(21);
        Console.WriteLine(box.Map(v => v * 2));
        Console.WriteLine(StaticReadonly); // BP:closures-end
    }
}

static class Output
{
    public static void Run()
    {
        Console.WriteLine("stdout line");
        Console.Error.WriteLine("stderr line");
        Console.WriteLine("Кириллица и emoji 🙂 €");
        Console.Write("no newline");
        Console.WriteLine();
        for (var i = 0; i < 200; i++) Console.WriteLine("bulk " + i);
        System.Diagnostics.Debug.WriteLine("debug trace line");
        System.Diagnostics.Debugger.Log(1, "cat", "debugger log line\n");
        Console.WriteLine("output done"); // BP:output-done
    }

    public static void Stdin()
    {
        Console.WriteLine("type something:");
        var line = Console.ReadLine();
        Console.WriteLine("read: " + (line ?? "<null>")); // BP:stdin-read
    }

    public static void Loop()
    {
        var n = 0L;
        while (true)
        {
            n++; // BP:loop-body
            if (n % 50_000_000 == 0) Console.WriteLine("tick " + n);
        }
    }

    public static void Sleeper()
    {
        for (var i = 0; i < 600; i++)
        {
            Console.WriteLine("sleeper " + i); // BP:sleeper
            Thread.Sleep(200);
        }
    }

    public static void Env(string[] args)
    {
        Console.WriteLine("args=" + string.Join("|", args));
        Console.WriteLine("cwd=" + Environment.CurrentDirectory);
        Console.WriteLine("MY_VAR=" + Environment.GetEnvironmentVariable("MY_VAR"));
        Console.WriteLine("PATH set=" + (Environment.GetEnvironmentVariable("PATH") != null));
        Console.WriteLine("debugger attached=" + System.Diagnostics.Debugger.IsAttached);
    }
}
