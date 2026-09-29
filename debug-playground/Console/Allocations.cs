namespace Playground;

/// <summary>
/// `allocations`: memory is allocated at known places and at known rates, to check what the allocation probe
/// (tools/alloc-probe) says of which line. Lines that allocate are marked <c>// ALLOC:name</c> with what is expected of them.
/// Ten rounds a second; per round, by the sizes of the objects:
/// <code>
///   ALLOC:buffers   2000 × byte[1024]      ≈ 2.1 MB   the most by far
///   ALLOC:strings   2000 × "order-N-N"     ≈ 0.1 MB
///   ALLOC:list      one List of 1000 ints  ≈ 8 KB     (it grows by doubling: several arrays)
///   ALLOC:boxing    200 × boxed int        ≈ 5 KB
///   ALLOC:closure   one delegate and its closure, tens of bytes
/// </code>
/// Nothing is kept: the garbage collector takes it all back, the memory of the process does not grow.
/// </summary>
public static class AllocationScenario
{
    private static readonly object?[] Sink = new object?[64];

    /// <summary>
    /// `allocations-fast`: the same rounds with no pause between them, and the number of rounds done is printed every second. What a
    /// probe costs the program is seen in that number: before it is attached, while it listens, after it has left.
    /// </summary>
    public static void RunFast()
    {
        Console.WriteLine($"Process id {Environment.ProcessId}; allocating as fast as it goes.");
        var watch = System.Diagnostics.Stopwatch.StartNew();
        var second = 1;
        var done = 0;
        for (var round = 0; ; round++)
        {
            Round(round);
            done++;
            if (watch.ElapsedMilliseconds < second * 1000L) continue;
            Console.WriteLine($"second {second}: {done} rounds");
            second++;
            done = 0;
        }
    }

    public static void Run()
    {
        Console.WriteLine($"Process id {Environment.ProcessId}; allocating until stopped.");
        for (var round = 0; ; round++)
        {
            Round(round);
            Thread.Sleep(100);
            if (round % 100 == 0) Console.WriteLine($"round {round}");
        }
    }

    private static void Round(int round)
    {
        for (var i = 0; i < 2000; i++)
        {
            Sink[i % 64] = Buffer(); // calls the line that allocates: the probe should name that line, not this one
            Sink[(i + 1) % 64] = Name(round, i);
            if (i % 10 == 0) Sink[(i + 2) % 64] = Boxed(i);
        }
        Sink[0] = Numbers();
        Sink[1] = Counter(round); // the closure of Counter is charged here: it is made before the first line of Counter
    }

    private static byte[] Buffer()
    {
        return new byte[1024]; // ALLOC:buffers — about 95% of the bytes
    }

    private static string Name(int round, int index)
    {
        return $"order-{round}-{index}"; // ALLOC:strings — about 4% of the bytes, as many objects as the buffers
    }

    private static object Boxed(int value)
    {
        return value; // ALLOC:boxing — an int in a box: 24 bytes, 200 times a round
    }

    private static List<int> Numbers()
    {
        var numbers = new List<int>(); // ALLOC:list — the list itself
        for (var i = 0; i < 1000; i++) numbers.Add(i); // and its arrays as it grows: allocated inside List.Add, attributed to this line
        return numbers;
    }

    private static Func<int> Counter(int start)
    {
        return () => start + 1; // ALLOC:closure — the closure and the delegate
    }
}
