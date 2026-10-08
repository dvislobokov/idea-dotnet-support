// docs: -; codes: CS0121 (the explicit implementation probe of 0.1.153: an explicit implementation is no member of the type by its simple name)
using System;
using System.Collections;
using System.Collections.Generic;

class ExplicitBag : IEnumerable<int>, IDisposable
{
    private readonly List<int> items = new List<int>();
    public void Add(int item) => items.Add(item);
    public IEnumerator<int> GetEnumerator() => items.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    void IDisposable.Dispose() => items.Clear();
    public void Dispose(bool all) => items.Clear();
    public int Count => items.Count;

    static int Sum(ExplicitBag bag)
    {
        var sum = 0;
        foreach (var item in bag) sum += item;
        IEnumerator e = ((IEnumerable)bag).GetEnumerator();
        ((IDisposable)bag).Dispose();
        bag.Dispose(true);
        using var enumerator = bag.GetEnumerator();
        return sum + bag.Count + (e.MoveNext() ? 1 : 0);
    }
}

class Ambiguous
{
    static void M(int a, long b) { }
    static void M(long a, int b) { }
    static void Call() => M(1, 1);
}
