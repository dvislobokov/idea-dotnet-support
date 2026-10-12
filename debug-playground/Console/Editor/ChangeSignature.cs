namespace Playground.Editor;

/// <summary>
/// Live check of Change Signature (0.1.174, Rider's "Change signature…"): Alt+Enter on the header of a method → "Change signature…", Refactor
/// This (Ctrl+Alt+Shift+T) → "Change Signature", or Ctrl+F6. The dialog lists the parameters (modifier, type, name, default value, value at
/// call sites) with add / remove / move up / down and shows the new signature. Undo (Ctrl+Z) takes the whole change back at once.
/// </summary>
public interface ISignatureShape
{
    double Scale(double factor, bool round);
}

public class SignatureCircle : ISignatureShape
{
    // TYPE:sig-hierarchy — Ctrl+F6 on `Scale` of ISignatureShape above, move `round` up, OK. EXPECT: the interface, this method and the override in
    // SignatureRing get `(bool round, double factor)`; the calls in SignatureUse.Run become `Scale(true, 2)` and `Scale(false, 3)`.
    public virtual double Scale(double factor, bool round) => round ? 1 : factor;
}

public class SignatureRing : SignatureCircle
{
    public override double Scale(double factor, bool round) => factor;
}

public class SignatureUse
{
    // TYPE:sig-add — Alt+Enter on `Sum` → "Change signature…", add a parameter `int b` with the value at call sites `10`, OK.
    // EXPECT: `Sum(int a, int b)`; the calls `Sum(1, 10)` and `Sum(a: 2, b: 10)`.
    public int Sum(int a) => a;

    // TYPE:sig-remove-rename — Change Signature on `Area`: remove `unused`, rename `height` to `depth`. EXPECT: `Area(int width, int depth)`
    // with `width * depth` in the body; the call `Area(2, depth: 3)`. Removing `width` instead: a conflict "Parameter 'width' is used…".
    public int Area(int width, int height, int unused) => width * height;

    // TYPE:sig-method-group — Change Signature on `Pick`, swap the parameters. EXPECT: a conflict about the method group in `Func<…> pick =
    // Pick`; `nameof(Pick)` is left alone.
    public int Pick(int a, int b) => b;

    public void Run(ISignatureShape shape, SignatureRing ring)
    {
        shape.Scale(2, true);
        ring.Scale(3, false);
        var x = Sum(1) + Sum(a: 2);
        var y = Area(2, height: 3, unused: 4);
        System.Func<int, int, int> pick = Pick;
        var name = nameof(Pick);
        System.Console.WriteLine($"{x} {y} {pick(1, 2)} {name}");
    }
}
