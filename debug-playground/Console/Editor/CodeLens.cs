using System;
using System.Collections.Generic;

namespace Playground.Editor;

/// <summary>
/// Live check of Code Vision without the language server: "N usages" and "N implementations" at types and members
/// (NativeCSharpCodeLens). Settings | .NET | Language Server → Source of Features → «Code Vision» = Built-in (the default), Code Lens →
/// «References» on. Nothing is typed unless a marker says so: each marker comment stands on its own line ABOVE its declaration, so the
/// lens of the declaration is visible in any Code Vision position (Settings | Editor | Inlay Hints | Code vision: Top or Right — at Right
/// it stands right after the code); compare it with EXPECT. A click on "N usages" opens the Show Usages popup with exactly N rows. With
/// «Language server» and the server on, the server's "N references" lenses show instead, never both.
/// </summary>
// TYPE:lens-interface — EXPECT: "5 usages | 3 implementations" (two base lists, two parameter types, List<>); click "3 implementations" → LensCircle, LensSquare, LensRing (all the way down)
public interface ILensShape
{
    // TYPE:lens-interface-member — EXPECT: "3 usages | 2 implementations" (the two calls in Total, `circle.Area()` on the implementation: Find Usages cascades)
    double Area();
}

// TYPE:lens-class — EXPECT: "3 usages | 1 inheritor" (two `new LensCircle`, the base list of LensRing; `var circle` is no usage, `base(radius)` of LensRing is the constructor's, not the type's)
public class LensCircle : ILensShape
{
    // TYPE:lens-constructor — EXPECT: "3 usages": the two `new LensCircle(...)` below and `base(radius)` of LensRing
    public LensCircle(double radius) { Radius = radius; }

    // TYPE:lens-property — EXPECT: "5 usages": the setter above, twice in Area, once in Describe, `circle.Radius`
    public double Radius { get; set; }

    // TYPE:lens-override — EXPECT: "3 usages", the same count as the interface member: Find Usages cascades over the hierarchy
    public double Area() => 3.14 * Radius * Radius;

    // TYPE:lens-virtual — EXPECT: "1 usage | 1 override"; the override is in LensRing
    public virtual string Describe() => $"circle {Radius}";

    // TYPE:lens-no-usages — EXPECT: "no usages"; a click shows an empty Show Usages
    private int _unused;

    // TYPE:lens-two-fields — EXPECT: one lens line, two entries "1 usage | no usages" (_a is read in Sum)
    private int _a, _b;

    // TYPE:lens-typing — on the empty line below Sum type `public void Extra() { Sum(); }`. EXPECT: the lens of Sum becomes "1 usage" at once, the others of the file stay; Ctrl+Z
    public int Sum() => _a;

}

public class LensSquare : ILensShape
{
    // EXPECT: "2 usages" (Area below), also after typing anywhere above
    public double Side { get; set; }
    public double Area() => Side * Side;
}

public class LensRing : LensCircle
{
    public LensRing(double radius) : base(radius) { }
    // TYPE:lens-override-only — EXPECT: "1 usage", the same `circle.Describe()` as the virtual's: Find Usages cascades over the hierarchy

    public override str
}

// TYPE:lens-enum — EXPECT: one lens line with three entries "1 usage | 1 usage | no usages": LensKind, Round, Square in the order of the line; a click on each shows its own usages
public enum LensKind { Round, Square }

public static class LensProgram
{
    // TYPE:lens-overloads — EXPECT: "1 usage" here (`Total(new List<ILensShape>())`) and "2 usages" at the overload below (`Total(circle)`, `Total(new LensCircle(1))`): the native Find Usages resolves each call to its overload by the argument, the lens says what a click lists
    public static double Total(IEnumerable<ILensShape> shapes)
    {
        double total = 0;
        foreach (var shape in shapes) total += shape.Area();
        return total;
    }

    public static double Total(ILensShape shape) => shape.Area();

    // TYPE:lens-click — click "no usages"/"N usages" of any member above. EXPECT: the Show Usages popup of the plugin (kinds «Invocation», «Read» …), not the server's
    public static void Describe()
    {
        var circle = new LensCircle(2);
        Console.WriteLine(circle.Radius + circle.Area() + Total(circle) + Total(new LensCircle(1)) + Total(new List<ILensShape>()));
        Console.WriteLine(circle.Describe());
        var kind = LensKind.Round;
        Console.WriteLine(kind);
    }
}
