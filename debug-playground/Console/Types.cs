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
