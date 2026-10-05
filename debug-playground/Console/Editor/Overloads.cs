using System;
using System.Collections.Generic;
using System.Linq;

namespace Playground.Editor;

/// <summary>
/// Live check of overload resolution of the plugin's own semantics (0.1.78, CSHARP_PSI_MIGRATION.md task D1, C# §12.6.4): which overload
/// a call stands for decides Ctrl+Click (Go to Declaration), the quick documentation (Ctrl+Q), the colors and the type of `var`. Put the
/// caret on the name of the call a marker names, press Ctrl+B (or Ctrl+Q): the overload of EXPECT and no list of candidates. Nothing to
/// type here; the file compiles as it is.
/// </summary>
public static class Overloads
{
    public static string Pick(long value) => "long";
    public static string Pick(double value) => "double";
    public static string Pick(object value) => "object";

    public static string Text(string value) => "string";
    public static string Text(object value) => "object";

    public static string Rest(int first) => "one";
    public static string Rest(int first, params int[] rest) => "params";

    public static string Optional(int a) => "one";
    public static string Optional(int a, int b = 0, string c = "") => "optional";

    public static string Generic(int value) => "int";
    public static string Generic<T>(T value) => "generic";

    public static string Run(Func<int> producer) => "func";
    public static string Run(Action action) => "action";

    public static string Parse(string text) => text;
    public static string Convert(Func<string, string> converter) => "func of string";
    public static string Convert(Func<int> producer) => "func of int";

    public static void Calls(int number, short small, string text, List<int> numbers)
    {
        // TYPE:overloads-numeric — Ctrl+B on `Pick` of each line. EXPECT: `Pick(long)` for `number` and for `small` (better than double:
        // long converts to double, not back), `Pick(double)` for `1.5`, `Pick(object)` for `text`.
        Console.WriteLine(Pick(number));
        Console.WriteLine(Pick(small));
        Console.WriteLine(Pick(1.5));
        Console.WriteLine(Pick(text));

        // TYPE:overloads-reference — EXPECT: `Text(string)` for `text` and for `null!` (string is more specific than object), `Text(object)`
        // for `numbers`.
        Console.WriteLine(Text(text));
        Console.WriteLine(Text(null!));
        Console.WriteLine(Text(numbers));

        // TYPE:overloads-params-optional — EXPECT: `Rest(int)` for `Rest(1)` (the normal form wins), `Rest(int, params int[])` for
        // `Rest(1, 2, 3)`; `Optional(int)` for `Optional(1)` (no default filled in wins), `Optional(int, int, string)` for `c: "x"`.
        Console.WriteLine(Rest(1));
        Console.WriteLine(Rest(1, 2, 3));
        Console.WriteLine(Optional(1));
        Console.WriteLine(Optional(1, c: "x"));

        // TYPE:overloads-generic — EXPECT: `Generic(int)` for `number` (not generic wins), `Generic<T>(T)` for `text` (T is string).
        Console.WriteLine(Generic(number));
        Console.WriteLine(Generic(text));

        // TYPE:overloads-lambdas — EXPECT: `Run(Func<int>)` for `() => 1`, `Run(Action)` for `() => Console.WriteLine()`, `Convert(Func<string,
        // string>)` for the method group `Parse`; `var sum` is `int` (hover over `var`, or Ctrl+Q): `Sum(Func<int, int>)` of LINQ.
        Console.WriteLine(Run(() => 1));
        Console.WriteLine(Run(() => Console.WriteLine()));
        Console.WriteLine(Convert(Parse));
        var sum = numbers.Sum(x => x * 2);
        Console.WriteLine(sum);

        // TYPE:overloads-argument-name — Alt+Enter on `640`: «Add argument name». EXPECT: `Resize(width: 640, height: 480)`; Ctrl+Z after.
        // On `480` only `height:` is added. Not offered on an argument that already has a name.
        Resize(640, 480);
    }

    private static void Resize(int width, int height) => Console.WriteLine($"{width}x{height}");
}
