using System.Diagnostics;
using System.Dynamic;
using System.Linq.Expressions;
using System.Reflection;
using System.Reflection.Emit;

namespace DebugTarget;

[DebuggerDisplay("Order {Id} ({Total})")]
class Order
{
    public int Id = 7;
    public decimal Total = 99.5m;
    [DebuggerBrowsable(DebuggerBrowsableState.Never)] public string Hidden = "must not be listed";
    [DebuggerBrowsable(DebuggerBrowsableState.RootHidden)] public int[] Lines = { 1, 2, 3 };
}

[DebuggerTypeProxy(typeof(BagProxy))]
class Bag
{
    public string Raw = "raw-state";
}

class BagProxy(Bag bag)
{
    public string Pretty => "proxy of " + bag.Raw;
}

class ThrowingToString
{
    public int Value = 1;
    public override string ToString() => throw new InvalidOperationException("ToString failed");
}

class HangingToString
{
    public int Value = 2;
    public override string ToString() { while (true) { } }
}

class EvilProperties
{
    public static readonly object Gate = new();
    public int Fine => 1;
    public int Hangs { get { while (true) { } } }
    public int Deadlocks { get { lock (Gate) { return 3; } } }
    public int Slow { get { Thread.Sleep(3000); return 4; } }
}

class Node
{
    public string Name = "node";
    public Node? Next;
}

ref struct Window
{
    public Span<int> Data;
    public int Length => Data.Length;
}

record struct Money(decimal Amount, string Currency);

class Required
{
    public required string Name { get; init; }
    public int Level { get; init; }
}

interface IShape
{
    static abstract string Title { get; }
    double Area();
}

readonly struct Square(double side) : IShape
{
    public static string Title => "square";
    public double Area() => side * side;
}

class Outer<T>
{
    public class Inner<U>
    {
        public T? First;
        public U? Second;
        public string Describe() => typeof(T).Name + "/" + typeof(U).Name; // BP:nested-generic
    }
}

class GoodStatic
{
    public static readonly int Value;
    static GoodStatic()
    {
        Value = 5; // BP:static-ctor
    }
}

class BadStatic
{
    public static int Value = Fail();
    static int Fail() => throw new InvalidOperationException("static init failed");
}

class Finalizable
{
    ~Finalizable()
    {
        Console.WriteLine("finalizer runs"); // BP:finalizer
    }
}

static class Atypical
{
    public static unsafe void Spans()
    {
        Span<int> stack = stackalloc int[4] { 10, 20, 30, 40 };
        ReadOnlySpan<char> chars = "span text".AsSpan(5);
        ref int second = ref stack[1];
        second = 21;
        var window = new Window { Data = stack.Slice(1, 2) };
        int local = 5;
        int* pointer = &local;
        nint native = 123;
        Memory<byte> memory = new byte[] { 1, 2, 3 };
        Console.WriteLine(stack[1] + chars.Length + window.Length + *pointer + (int)native + memory.Length); // BP:spans
    }

    public static void DebugAttributes()
    {
        var order = new Order();
        var bag = new Bag();
        var first = HiddenMethod(1); // BP:attrs-step
        var second = StepThrough(2);
        var third = NonUser(3);
        Console.WriteLine(order.Id + bag.Raw + first + second + third); // BP:debugattrs
    }

    [DebuggerHidden] static int HiddenMethod(int x) { return Visible(x) + 1; }
    [DebuggerStepThrough] static int StepThrough(int x) { return Visible(x) + 2; }
    [DebuggerNonUserCode] static int NonUser(int x) { return Visible(x) + 3; }
    static int Visible(int x)
    {
        return x * 10; // BP:attrs-visible
    }

    public static void Break()
    {
        Console.WriteLine("before break");
        Debugger.Break();
        Console.WriteLine("after break"); // BP:after-break
    }

    public static int Deep(int depth)
    {
        if (depth == 0)
        {
            Console.WriteLine("bottom"); // BP:deep-bottom
            return 0;
        }
        return 1 + Deep(depth - 1);
    }

    public static void Huge()
    {
        var million = Enumerable.Range(0, 1_000_000).ToList();
        var bigDict = Enumerable.Range(0, 100_000).ToDictionary(n => "key" + n, n => n);
        var hugeString = new string('z', 10_000_000);
        var bytes = new byte[5_000_000];
        var cycle = new Node();
        cycle.Next = cycle;
        var chain = new Node { Name = "a", Next = new Node { Name = "b", Next = new Node { Name = "c" } } };
        Console.WriteLine(million.Count + bigDict.Count + hugeString.Length + bytes.Length + cycle.Name + chain.Name); // BP:huge
    }

    public static void Evil()
    {
        var throwing = new ThrowingToString();
        var hanging = new HangingToString();
        var evil = new EvilProperties();
        var holder = new Thread(() => { lock (EvilProperties.Gate) { Thread.Sleep(Timeout.Infinite); } }) { IsBackground = true, Name = "lock-holder" };
        holder.Start();
        Thread.Sleep(200);
        Console.WriteLine("evil ready " + throwing.Value + hanging.Value + evil.Fine); // BP:evil
        Console.WriteLine("evil survived"); // BP:evil-after
    }

    public static void Unicode()
    {
        var переменная = 1;
        var @class = "keyword";
        var 変数 = 2;
        var ünïcödé = "diacritics";
        Console.WriteLine(переменная + @class + 変数 + ünïcödé); // BP:unicode
    }

    public static void Patterns()
    {
        object[] inputs = { 5, "text", 2.5, new Money(10, "EUR"), new[] { 1, 2, 3 } };
        foreach (var input in inputs)
        {
            var described = input switch
            {
                int n when n > 3 => "big int " + n, // BP:pattern-arm
                string { Length: > 2 } s => "string " + s,
                Money { Currency: "EUR" } money => "euros " + money.Amount,
                int[] and [var head, .. var tail] => "array " + head + "+" + tail.Length,
                _ => "other",
            };
            Console.WriteLine(described);
        }
        var required = new Required { Name = "req", Level = 2 };
        var changed = new Money(1, "USD") with { Amount = 2 };
        Console.WriteLine(required.Name + changed); // BP:patterns-end
    }

    public static async Task AsyncIterator()
    {
        var total = 0;
        await foreach (var value in Produce())
        {
            total += value; // BP:asynciter-consume
        }
        var quick = await Quick();
        Console.WriteLine(total + quick); // BP:asynciter-end
    }

    static async IAsyncEnumerable<int> Produce()
    {
        for (var i = 1; i <= 3; i++)
        {
            await Task.Delay(5);
            yield return i; // BP:asynciter-produce
        }
    }

    static ValueTask<int> Quick() => new(7);

    public static void ParallelLoop()
    {
        var hits = 0;
        Parallel.For(0, 8, new ParallelOptions { MaxDegreeOfParallelism = 8 }, n =>
        {
            Interlocked.Increment(ref hits); // BP:parallel-body
            Thread.Sleep(50);
        });
        Console.WriteLine("parallel hits " + hits); // BP:parallel-end
    }

    public static void Deadlock()
    {
        var a = new object();
        var b = new object();
        var one = new Thread(() => { lock (a) { Thread.Sleep(100); lock (b) { } } }) { Name = "dead-one", IsBackground = true };
        var two = new Thread(() => { lock (b) { Thread.Sleep(100); lock (a) { } } }) { Name = "dead-two", IsBackground = true };
        one.Start();
        two.Start();
        Console.WriteLine("deadlock started");
        one.Join(60_000);
    }

    public static void StaticConstructors()
    {
        Console.WriteLine(GoodStatic.Value);
        try
        {
            Console.WriteLine(BadStatic.Value);
        }
        catch (TypeInitializationException e)
        {
            Console.WriteLine("type init: " + e.InnerException?.Message); // BP:staticctor-caught
        }
    }

    public static void Filters()
    {
        try
        {
            throw new ArgumentException("filtered");
        }
        catch (Exception e) when (Log(e))
        {
            Console.WriteLine("never here");
        }
        catch (ArgumentException)
        {
            Console.WriteLine("filter said no"); // BP:filters-catch
        }

        try
        {
            Task.WaitAll(Task.Run(() => throw new InvalidOperationException("one")), Task.Run(() => throw new NotSupportedException("two")));
        }
        catch (AggregateException e)
        {
            Console.WriteLine("aggregate of " + e.InnerExceptions.Count); // BP:filters-aggregate
        }
    }

    static bool Log(Exception e)
    {
        Console.WriteLine("filter saw " + e.Message); // BP:filter-body
        return false;
    }

    public static void ThreadCrash()
    {
        var thread = new Thread(() => throw new InvalidOperationException("worker crashed")) { Name = "crasher" };
        thread.Start();
        thread.Join();
        Console.WriteLine("unreachable");
    }

    public static int Overflow(int n) => Overflow(n + 1) + 1;

    public static void ExitFromThread()
    {
        var thread = new Thread(() => Environment.Exit(7)) { Name = "exiter" };
        thread.Start();
        Thread.Sleep(5000);
    }

    public static void DynamicCode()
    {
        dynamic expando = new ExpandoObject();
        expando.Name = "expando";
        expando.Count = 3;
        dynamic number = 5;
        var viaDynamic = number + expando.Count;

        var method = typeof(Atypical).GetMethod(nameof(Reflected), BindingFlags.NonPublic | BindingFlags.Static)!;
        var reflected = (int)method.Invoke(null, new object[] { 4 })!;

        Expression<Func<int, int>> tree = x => x * 3;
        var compiled = tree.Compile();
        var fromTree = compiled(5);

        var emitted = new DynamicMethod("Emitted", typeof(int), new[] { typeof(int) });
        var il = emitted.GetILGenerator();
        il.Emit(OpCodes.Ldarg_0);
        il.Emit(OpCodes.Ldc_I4, 100);
        il.Emit(OpCodes.Add);
        il.Emit(OpCodes.Ret);
        var fromIl = (int)emitted.Invoke(null, new object[] { 1 })!;
        Console.WriteLine(viaDynamic + reflected + fromTree + fromIl); // BP:dynamic
    }

    static int Reflected(int x)
    {
        return x + 1; // BP:reflected
    }

    public static void MultiLine()
    {
        var a = 1; var b = 2; var c = a + b; // BP:multi-statements
        var evens = new[] { 1, 2, 3, 4 }.Where(n => n % 2 == 0).Select(n => n * 10).ToArray(); // BP:multi-lambdas
        for (var i = 0; i < 3; i++) { a += i; } // BP:multi-for
        Console.WriteLine(a + b + c + evens.Length
                          + 1); // BP:multi-continuation
    }

    public static void Generics()
    {
        var inner = new Outer<int>.Inner<string> { First = 1, Second = "two" };
        Console.WriteLine(inner.Describe());
        Console.WriteLine(Describe(new Square(2)));
        Console.WriteLine(Identity(5));
        Console.WriteLine(Identity("five"));
    }

    static string Describe<T>(T shape) where T : IShape => T.Title + " " + shape.Area(); // BP:static-abstract

    static T Identity<T>(T value)
    {
        return value; // BP:generic-instantiations
    }

    public static void Finalizer()
    {
        Make();
        GC.Collect();
        GC.WaitForPendingFinalizers();
        Console.WriteLine("finalized"); // BP:finalizer-end
    }

    static void Make() => _ = new Finalizable();

    public static void ChildProcess()
    {
        using var child = Process.Start(new ProcessStartInfo("dotnet", "--version") { RedirectStandardOutput = true })!;
        var version = child.StandardOutput.ReadToEnd();
        child.WaitForExit();
        Console.WriteLine("child said " + version.Trim()); // BP:childproc
    }

    public static void RefParams()
    {
        var value = 1;
        var text = "in";
        Mutate(ref value, out var produced, in text);
        Console.WriteLine(value + produced + text); // BP:refparams-end
    }

    static void Mutate(ref int value, out int produced, in string text)
    {
        value += 10;
        produced = text.Length; // BP:refparams
    }
}
