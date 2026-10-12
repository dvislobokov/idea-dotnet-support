namespace Playground.Editor;

/// <summary>
/// Live check of 0.1.173: Rider's actions on members and types — "Add exception documentation", "Import static members", "Inline Method"
/// at a call and at all calls, "Create derived type", "Copy type". Put the caret where a marker says, Alt+Enter, compare with EXPECT,
/// undo (Ctrl+Z; "Copy type" makes a new file — delete it). Nothing here is called, the file only has to compile.
/// "Cover" on a test: `debug-playground/Tests/PricingTests.cs`, marker `TYPE:type-cover`.
/// </summary>
public class TypeActions
{
    /// <summary>Saves a name.</summary>
    public void Save(string name)
    {
        // TYPE:type-exception-doc — caret on `throw` below, Alt+Enter → "Add exception documentation". EXPECT: under the summary above
        // `/// <exception cref="ArgumentNullException"></exception>`, the caret between the tags; NOT offered again once it is there
        if (name == null) throw new ArgumentNullException(nameof(name));
    }

    // TYPE:type-import-static — caret on `Max` below, Alt+Enter → "Import static members". EXPECT: `using static System.Math;` at the top,
    // `Max(a, b) + Min(a, b)` here; `Convert.ToInt32` untouched
    public int Bounds(int a, int b) => Math.Max(a, b) + Math.Min(a, b) + Convert.ToInt32("1");

    // TYPE:type-inline-call — caret on `Multiply` below, Alt+Enter (or Refactor This) → "Inline Method". EXPECT: `((price + 1) * count) * 2`,
    // `Multiply` stays (other calls use it)
    public int Total(int price, int count) => Multiply(price + 1, count) * 2;

    public int Square(int x) => Multiply(x, x);

    private int Multiply(int a, int b) => a * b;

    public int Twice1(int x) => Twice(x);

    public int Twice2(int y) => Twice(y) + 1;

    // TYPE:type-inline-all — caret on the name `Twice` below, Alt+Enter → "Inline Method". EXPECT: `x * 2` and `(y * 2) + 1` above, the
    // method and its comment gone
    private int Twice(int v) => v * 2;
}

// TYPE:type-derived — caret on `ITypeGreeter` below, Alt+Enter → "Create derived type". EXPECT: `public class TypeGreeter : ITypeGreeter`
// after the interface with `public string Greet(string name)` throwing NotImplementedException, the caret on the new name
public interface ITypeGreeter
{
    string Greet(string name);
}

// TYPE:type-derived-class — caret on `TypeShape`, "Create derived type". EXPECT: `public class TypeShapeImpl : TypeShape` with
// `public override double Area()`; NOT offered on a sealed class or one whose constructors all take arguments
public abstract class TypeShape
{
    public abstract double Area();
}

// TYPE:type-copy — caret on `TypeOrder`, Alt+Enter → "Copy type". EXPECT: a new file `TypeOrder1.cs` beside this one, opened, with
// `namespace Playground.Editor;` and `public class TypeOrder1` (its constructor renamed too)
public class TypeOrder
{
    public TypeOrder() { }
}
