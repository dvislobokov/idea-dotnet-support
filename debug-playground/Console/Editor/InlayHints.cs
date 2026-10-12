using System;
using System.Collections.Generic;
using System.Linq;

namespace Playground.Editor;

/// <summary>
/// Live check of inlay hints without the language server (0.1.116): the names of parameters before arguments and the types of
/// <c>var</c>, lambda parameters, <c>new()</c> and collection expressions, on the plugin's own semantics with Roslyn's rules. Settings |
/// .NET | Language Server → Source of Features → «Inlay hints» = Built-in, the server off; the thirteen «Inlay Hints» options of the same
/// page drive what is shown (their defaults: names at literals, indexers and <c>new</c>; types of <c>var</c> and lambda parameters).
/// Nothing is typed: look at the lines a <c>// TYPE:inlay-*</c> comment points at and compare with EXPECT. Also Settings | Editor |
/// Inlay Hints | C# must list «Parameter names» and «Types», both on.
/// </summary>
public class InlayHints
{
    private readonly List<Certificate> _certificates = [new("a", 10), new("b", 20)];

    public int Parameters()
    {
        // TYPE:inlay-literals — EXPECT `width:` before 10, `title:` before "t", `shape:` before `new Shape()`; no names on the next line
        // (`number`, `text`, `shape` are no literals: «Parameter names: for everything else» is off by default)
        Draw(10, "t", new Shape());
        int number = 1;
        string text = "x";
        Shape shape = new();
        Draw(number, text, shape);
        // TYPE:inlay-others — turn on «Parameter names: for everything else»: EXPECT `width:`, `title:`, `shape:` above too, but NOT before
        // `width` (the argument is named as the parameter) and NOT before `this.Width` / `Width`; `predicate:` before the lambda below
        int width = 2;
        Draw(width, text, shape);
        Draw(this.Width, text, shape);
        var first = _certificates.Where(certificate => certificate.Days > 0).First();
        // TYPE:inlay-indexer — EXPECT `key:` before "a" (an indexer of Dictionary); off with «Parameter names: for indexers»
        var map = new Dictionary<string, int> { ["a"] = 1 };
        var n = map["a"];
        // TYPE:inlay-named-params — EXPECT no hint at `width: 1` (named), `title:` before "t", `format:` before "x" and nothing at 1, 2 (params)
        Draw(width: 1, "t", shape: shape);
        Log("x", 1, 2);
        // TYPE:inlay-suffix — EXPECT no names at `Pair(1, 2)` (arg1 / arg2) and `Both(1, 2)` (valueA / valueB); `first:` `arg2:` at `Mixed`;
        // turn off «not when the names differ only by suffix»: `arg1:` `arg2:` and `valueA:` `valueB:` appear
        Pair(1, 2);
        Both(1, 2);
        Mixed(1, 2);
        // TYPE:inlay-intent — EXPECT no names at `SetColor("red")` and `EnableLogging(true)`; `width:` at `SetSize(1)`, `level:` at `DisableCache(1)`;
        // turn off «not when the name matches the intent of the method»: `color:` and `on:` appear
        SetColor("red");
        SetSize(1);
        EnableLogging(true);
        DisableCache(1);
        // TYPE:inlay-constructors — EXPECT `name:` `days:` at both `new` below (the arguments are literals: off with «Parameter names: for literals»,
        // not with «for 'new' expressions», which is about a `new …` passed as an argument, see inlay-literals)
        var made = new Certificate("c", 30);
        Certificate target = new("d", 40);
        return first.Days + n + made.Days + target.Days + width + number + text.Length + shape.Sides;
    }

    public int Types()
    {
        // TYPE:inlay-var — EXPECT `List<int>` after `var ` of `list`, `Certificate` at `one`, `int` at `item`, `KeyValuePair<string, int>` at `pair`,
        // `int` at `found`, `int` and `string` at `a` and `b`; nothing at `int plain`; off with «Types: of 'var' variables»
        var list = new List<int> { 1, 2 };
        var one = _certificates[0];
        foreach (var item in list) { }
        var map = new Dictionary<string, int>();
        foreach (var pair in map) { }
        if (map.TryGetValue("k", out var found)) { }
        var (a, b) = (1, "x");
        int plain = 1;
        // TYPE:inlay-linq — the report that started this: EXPECT `IOrderedEnumerable<Certificate>` at `resp`, `Certificate` before `certificate`
        // and before `c`; `int` before `x` and `y`; nothing before `(int x)`; off with «Types: of lambda parameters» (the lambdas) / «of 'var' variables»
        var resp = _certificates.Where(certificate => certificate.Days > 0).OrderBy(c => c.Name);
        Func<int, int, int> add = (x, y) => x + y;
        Func<int, int> typed = (int x) => x;
        // TYPE:inlay-new — turn on «Types: of 'new()' expressions»: EXPECT `List<int>` right after `new` of `xs` (off by default)
        List<int> xs = new();
        // TYPE:inlay-collection — turn on «Types: of collection expressions»: EXPECT `List<int>` before `[1, 2]` and before `[3]` (off by default)
        List<int> ys = [1, 2];
        Fill([3]);
        // TYPE:inlay-click — Ctrl+click (or click) on the `Certificate` hint at `one` above: EXPECT the caret in `class Certificate` below;
        // on `List<int>` at `list`: the metadata view of List<T> opens
        // TYPE:inlay-switch — «Inlay hints» = Language server (server on): EXPECT the server's hints only, never two of a kind at one place
        return list.Count + one.Days + found + a + b.Length + plain + resp.Count() + add(1, 2) + typed(3) + xs.Count + ys.Count + (a > 0 ? 0 : 1);
    }

    public int Width { get; set; }

    private static void Draw(int width, string title, Shape shape) { }
    private static void Fill(List<int> size) { }
    private static void Log(string format, params object[] args) { }
    private static void Pair(int arg1, int arg2) { }
    private static void Both(int valueA, int valueB) { }
    private static void Mixed(int first, int arg2) { }
    private static void SetColor(string color) { }
    private static void SetSize(int width) { }
    private static void EnableLogging(bool on) { }
    private static void DisableCache(int level) { }

    public class Shape
    {
        public int Sides { get; set; } = 3;
    }

    public class Certificate(string name, int days)
    {
        public string Name { get; } = name;
        public int Days { get; } = days;
    }
}
