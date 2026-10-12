#nullable enable
using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;

class Node
{
    public Node? Next;
    public string? Name;
    public string Text = "";
    public Node? Find(string key) => null;
    public int Length => 0;
}

class Flow
{
    string? field;
    string notNull = "";

    void Deref(string? s, Node? n)
    {
        Console.WriteLine(s.Length);
        Console.WriteLine(s.Length);
        Console.WriteLine(n.Next.Name);
    }

    void Tests(string? s, Node? n)
    {
        if (s != null) Console.WriteLine(s.Length);
        if (s == null) return;
        Console.WriteLine(s.Length);
        if (n is null) throw new ArgumentNullException();
        Console.WriteLine(n.Length);
        Console.WriteLine(n.Next.Length);
        if (n.Next is not null) Console.WriteLine(n.Next.Length);
        if (n.Next is { } next) Console.WriteLine(next.Length);
        if (n.Name is string name) Console.WriteLine(name.Length);
    }

    void Logic(string? a, string? b)
    {
        if (a != null && a.Length > 0) { }
        if (a == null || a.Length > 0) { }
        if (a == null && b.Length > 0) { }
        if (!(b is null)) Console.WriteLine(b.Length);
        while (a != null) { Console.WriteLine(a.Length); a = null; }
    }

    void Operators(string? s, Node? n)
    {
        string t = s ?? "";
        Console.WriteLine(t.Length);
        int? len = s?.Length;
        string u = s!;
        Console.WriteLine(n?.Name.Length);
        string v = n?.Name;
        s ??= "x";
        Console.WriteLine(s.Length);
    }

    void Assign(string? s)
    {
        string a = s;
        notNull = s;
        notNull = null;
        field = s;
        string? b = null;
        b = "x";
        Console.WriteLine(b.Length);
        string c = (string)s;
        Take(s);
        Take(null);
        TakeMaybe(s);
    }

    void Take(string value) { }
    void TakeMaybe(string? value) { }

    string Return(string? s, bool flag)
    {
        if (flag) return s;
        if (s == null) return "";
        return s;
    }

    string Arrow(string? s) => s;

    void Loop(List<string?> items)
    {
        string? last = null;
        foreach (var item in items) last = item ?? last;
        for (int i = 0; i < 3; i++) { if (last == null) last = ""; }
        Console.WriteLine(last.Length);
    }

    void Attributes(string? s, Dictionary<string, string> map)
    {
        if (!string.IsNullOrEmpty(s)) Console.WriteLine(s.Length);
        if (map.TryGetValue("k", out var value)) Console.WriteLine(value.Length);
        if (IsSet(s)) Console.WriteLine(s.Length);
        Ensure(s);
        Console.WriteLine(s.Length);
    }

    static bool IsSet([NotNullWhen(true)] string? s) => s != null;
    static void Ensure([NotNull] string? s) { if (s == null) throw new Exception(); }

    void Unknown(string? s)
    {
        Mystery(s);
        Console.WriteLine(s.Length);
    }

    void Mystery(string? s) { }

    void Lambda(string? s)
    {
        Action a = () => Console.WriteLine(s.Length);
        Func<string?, int> f = x => x.Length;
    }

    void Switch(object? o)
    {
        switch (o)
        {
            case string text: Console.WriteLine(text.Length); break;
            case null: break;
            default: Console.WriteLine(o.ToString()); break;
        }
        var r = o switch { null => 0, _ => o.GetHashCode() };
    }

    void Try(string? s)
    {
        try { s = "x"; Console.WriteLine(s.Length); }
        catch (Exception) { Console.WriteLine(s.Length); }
    }

    void Throws(string? s)
    {
        var t = s ?? throw new ArgumentNullException(nameof(s));
        Console.WriteLine(t.Length);
        Console.WriteLine(s.Length);
    }
}

class Ctor
{
    public string Name;
    public string Other { get; set; }
    public string? Optional;
    public string Init = "";
    public required string Req { get; set; }

    public Ctor(bool flag)
    {
        if (flag) Name = "a";
        Other = "b";
    }

    public Ctor() : this(true) { }

    public Ctor(string name)
    {
        Name = name;
        Setup();
    }

    [MemberNotNull(nameof(Other))]
    void Setup() => Other = "";
}

partial class Part
{
    public string A;
    public Part() { }
}

partial class Part
{
    public string B;
}

class Members
{
    string? name;

    void Use()
    {
        if (name != null) Console.WriteLine(name.Length);
        if (this.name != null) Console.WriteLine(name.Length);
        Console.WriteLine(name.Length);
    }
}
