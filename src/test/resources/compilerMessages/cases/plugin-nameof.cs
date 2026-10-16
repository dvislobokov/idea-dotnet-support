// docs: -; codes: CS0103, CS0117, CS0234, CS1061 (a missing name inside nameof(...): the gap the UI robot found on 2026-10-16; the valid forms around it must stay silent)
using System;
using System.Collections.Generic;
using System.Linq;

public class Counter
{
    public int Count { get; set; }
    public static int Total;
    public void Increment() { }
    public void Overloaded(int x) { }
    public void Overloaded(string s) { }
    public class Nested { public int Inner; }
}

public class NameofCases<T>
{
    private int field;
    public static string A = nameof(Counter.Missing);
    public static string B = nameof(Counter.Count);
    public static string C = nameof(Counter.Increment);
    public static string D = nameof(Counter.Overloaded);
    public static string E = nameof(Counter.Nested.Inner);
    public static string F = nameof(UndefinedThing);
    public static string G = nameof(List<int>.Count);
    public static string H = nameof(T);
    public static string I = nameof(System.Console);
    public static string J = nameof(System.Nothing);
    public static string K = nameof(Enumerable.Where);
    public static string L = nameof(Counter.Total);

    public string M(string text, Counter counter)
    {
        var local = 1;
        return nameof(text) + nameof(local) + nameof(field) + nameof(text.Length) + nameof(counter.Count)
            + nameof(text.Nope)
            + nameof(counter.Increment) + nameof(text.Select)
            + nameof(missingLocal) + local;
    }
}
