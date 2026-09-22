using System.Diagnostics;

namespace Playground;

public enum Color { Red, Green, Blue }

[Flags]
public enum Access { None = 0, Read = 1, Write = 2, Execute = 4 }

public readonly record struct Point(int X, int Y);

[DebuggerDisplay("{Name} ({Age})")]
public class Person(string name, int age)
{
    private readonly List<string> _notes = ["created"];

    public string Name { get; } = name;
    public int Age { get; set; } = age;
    public Person? Friend { get; set; }
    public bool IsAdult => Age >= 18; // BP:property — stepping skips it (step filtering), a breakpoint here still stops
    public IReadOnlyList<string> Notes => _notes;

    public override string ToString() => $"{Name}, {Age}";
}

/// <summary>Describing the value costs two seconds: the variables view must stay usable.</summary>
public class SlowToString
{
    public override string ToString()
    {
        Thread.Sleep(2000);
        return "slow but here";
    }
}

public class ThrowingProperty
{
    public int Value => throw new InvalidOperationException("getter failed");
}

// ---------------------------------------------------------------- the `leak` scenario: who holds these objects is what Memory Dump answers

public record CachedOrder(int Id, string Name, byte[] Payload);

/// <summary>A cache without eviction: a static dictionary is a GC root, everything in it lives forever.</summary>
public static class LeakyCache
{
    private static readonly Dictionary<int, CachedOrder> Orders = new();

    public static void Remember(CachedOrder order) => Orders[order.Id] = order;
    public static int Count => Orders.Count;
}

public static class PriceFeed
{
    public static event EventHandler<decimal>? Changed;
    public static int Subscribers => Changed?.GetInvocationList().Length ?? 0;
}

/// <summary>Subscribes in the constructor and never unsubscribes: the static event keeps every watcher alive.</summary>
public class PriceWatcher
{
    private readonly string _name;
    private readonly List<decimal> _history = new();

    public PriceWatcher(string name)
    {
        _name = name;
        PriceFeed.Changed += OnChanged;
    }

    private void OnChanged(object? sender, decimal price) => _history.Add(price);

    public override string ToString() => _name;
}

public class EndlessGetter
{
    public int Forever
    {
        get
        {
            while (true) Thread.Sleep(10);
        }
    }
}
