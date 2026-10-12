using System.Diagnostics.CodeAnalysis;

namespace Playground.Editor;

/// <summary>
/// Live check of the nullable flow analysis on the plugin's own semantics (0.1.80; the project has <c>&lt;Nullable&gt;enable</c>): CS8602
/// (dereference of a maybe-null value), CS8600 / CS8601 / CS8625 (a maybe-null value or <c>null</c> where it may not be), CS8604 (as an
/// argument), CS8603 (returned), CS8618 (a constructor leaves a field null), through null tests, <c>??</c>, <c>?.</c>, <c>!</c>, early exits,
/// loops and the attributes of <c>System.Diagnostics.CodeAnalysis</c> of the source and of the assemblies. Turn the server off (Settings | .NET |
/// Language Server), let the assemblies be indexed. A yellow warning is what Roslyn reports in the same place (`dotnet build` lists them);
/// type on the empty line under a marker and undo with Ctrl+Z. The file compiles as it is (its warnings are part of the check).
/// </summary>
public class NullableFlow
{
    private string? _cache;
    private string _title = "";

    public void Remember(string? value) => _cache = value;

    public sealed class FlowNode
    {
        public FlowNode? Next;
        public string? Name;
        public string Text = "";
    }

    // TYPE:nullable-deref — EXPECT: yellow CS8602 «Dereference of a possibly null reference» on `name` of the first `name.Length` only (after it
    // `name` is known not null, as in Roslyn); none inside `if (name != null)`, none after `if (name is null) return`. Type
    // `Console.WriteLine(node.Next.Name);` on the empty line → CS8602 on `node.Next` (a field `FlowNode?`), not on `node`.
    public int Deref(string? name, FlowNode node)
    {
        var length = name.Length + name.Length;
        if (name != null) length += name.Length;

        if (name is null) return 0;
        return length + name.Length;
    }

    // TYPE:nullable-tests — EXPECT: no warning anywhere in this method: `is not null`, `is { } next`, `&&`, `??`, `?.` and `!` all tell the
    // analysis the value is not null. Type `Console.WriteLine(node.Next.Text);` after the `if` block → CS8602 on `node.Next` (the test is
    // inside the if, not after it).
    public string Tests(FlowNode node, string? text)
    {
        if (node.Next is not null && node.Next.Text.Length > 0) Console.WriteLine(node.Next.Text);
        if (node.Next is { } next) Console.WriteLine(next.Text);
        string sure = text ?? "";
        int? maybeLength = text?.Length;
        string trusted = text!;

        return sure + maybeLength + trusted;
    }

    // TYPE:nullable-assign — EXPECT: CS8600 on `_cache` in `string local = _cache;` (a maybe-null field to a non-nullable local), CS8625 on
    // `null` of `_title = null;`, CS8601 on `_cache` of `_title = _cache;`. Type `Take(_cache);` → CS8604 «Possible null reference argument for
    // parameter 'value' in 'void NullableFlow.Take(string value)'»; `Take(null);` → CS8625.
    public void Assign()
    {
        string local = _cache;
        _title = null;
        _title = _cache;

        Console.WriteLine(local + _title);
    }

    private static void Take(string value) => Console.WriteLine(value);

    // TYPE:nullable-return — EXPECT: CS8603 «Possible null reference return» on `_cache` of the first return only; the second is after a null
    // test, the third after `?? ""`. Type `return null;` under the marker inside `Fallback` → CS8603 on `null` too.
    public string Return(bool first)
    {
        if (first) return _cache;
        if (_cache != null) return _cache;
        return _cache ?? "";
    }

    public string Fallback() => _title;

    // TYPE:nullable-attributes — EXPECT: no warning: `string.IsNullOrEmpty` is `[NotNullWhen(false)]`, `Dictionary.TryGetValue` is
    // `[MaybeNullWhen(false)] out`, `ArgumentNullException.ThrowIfNull` is `[NotNull]` (all from the index of the assemblies), `IsSet` and
    // `Ensure` are this file's own `[NotNullWhen(true)]` / `[NotNull]`. Type `Console.WriteLine(found.Length);` in the `else` of TryGetValue →
    // CS8602 on `found`.
    public void Attributes(string? text, Dictionary<string, string> map, object? value)
    {
        if (!string.IsNullOrEmpty(text)) Console.WriteLine(text.Length);
        if (map.TryGetValue("key", out var found)) Console.WriteLine(found.Length);
        else Console.WriteLine("none");

        if (IsSet(text)) Console.WriteLine(text.Length);
        ArgumentNullException.ThrowIfNull(value);
        Console.WriteLine(value.ToString());
        Ensure(text);
        Console.WriteLine(text.Length);
    }

    private static bool IsSet([NotNullWhen(true)] string? text) => text != null;

    private static void Ensure([NotNull] string? text)
    {
        if (text == null) throw new ArgumentNullException(nameof(text));
    }

    // TYPE:nullable-loops — EXPECT: CS8602 on `last` of `last.Length` after the loop (it may never have run), none inside the `while`.
    public void Loops(List<string?> items)
    {
        string? last = null;
        foreach (var item in items) last = item ?? last;
        while (last != null && last.Length > 10) last = last.Substring(1);

        Console.WriteLine(last.Length);
    }

    // TYPE:nullable-ctor — EXPECT: CS8618 «Non-nullable field 'Name' must contain a non-null value when exiting constructor» on
    // `FlowOwner` of the first constructor (set on one path only); none on the second (`Init` is `[MemberNotNull]`), none on the third (it
    // calls `this(...)`). `Optional` is `string?`, `Label` has an initializer, `Required` is `required`: never warned.
    public sealed class FlowOwner
    {
        public string Name;
        public string? Optional;
        public string Label = "";
        public required string Required { get; init; }

        public FlowOwner(bool named)
        {
            if (named) Name = "named";
        }

        [SetsRequiredMembers]
        public FlowOwner(string name)
        {
            Required = name;
            Init();
        }

        public FlowOwner() : this(true) { }

        [MemberNotNull(nameof(Name))]
        private void Init() => Name = "init";
    }

    // TYPE:nullable-unknown — EXPECT: no warning: a lambda that captures `text` and a call the analysis does not know (a delegate) are not
    // guessed at — precision first. Roslyn warns on `text.Length` inside the lambda; the plugin knowingly does not (yet).
    public void Unknown(string? text, Action<string?> callback)
    {
        callback(text);
        Func<int> length = () => text?.Length ?? 0;

        Console.WriteLine(length());
    }
}
