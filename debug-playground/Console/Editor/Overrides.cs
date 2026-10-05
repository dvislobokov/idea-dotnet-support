using Microsoft.Extensions.Hosting;

namespace Playground.Editor;

/// <summary>
/// Live check of writing an override (0.1.85): `override ` completion over bases of the solution, of assemblies and of files the build
/// generates (see also `Grpc/Greeter.cs`, TYPE:grpc-override), Ctrl+O / Ctrl+I, Alt+Enter «Implement missing members» / «Override
/// members...», the red CS0534 / CS0535, `base.` and Ctrl+P in `: base(`. The server off (Settings | .NET | Language Server). Type on
/// the empty line under a marker, check EXPECT, then Ctrl+Z until the file is as it was (it builds as it is).
/// </summary>
public abstract class OvShape
{
    public abstract double Area();
    public virtual string Describe(int digits) => Area().ToString("F" + digits);
    protected virtual int Sides { get; set; }
    public sealed override string ToString() => Describe(2);
    public void NotVirtual() { }
}

public class OvSquare : OvShape
{
    private readonly double _side;

    public OvSquare(double side) => _side = side;

    public override double Area() => _side * _side;

    // TYPE:override-popup — type `public override ` (with the space). EXPECT: the list opens by itself after the space (no Ctrl+Space):
    // Describe(int digits), Sides, Equals(object? obj), GetHashCode() — bold, each with its type and the base in parentheses. NOT: Area
    // (overridden here), ToString (sealed in OvShape), NotVirtual, keywords. Choose Describe. EXPECT: `public override string
    // Describe(int digits)` with `{ return base.Describe(digits); }` on separate lines, the caret at the end of the `return` line.

    // TYPE:override-access — type `override Si` and Enter. EXPECT: `protected override int Sides` (the base's accessibility put before
    // `override`) with `get => base.Sides;` and `set => base.Sides = value;`. Then type `protected override ` — the typed access stays.
}

/// <summary>A base of an assembly (Microsoft.Extensions.Hosting): its abstract and virtual members are offered as well.</summary>
public class OvWorker : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) => Task.CompletedTask;

    // TYPE:override-library — type `public override ` and choose StartAsync. EXPECT: `public override Task StartAsync(CancellationToken
    // cancellationToken)` with `return base.StartAsync(cancellationToken);`; no `using` added (implicit usings have System.Threading.Tasks).
    // Also offered: StopAsync, Dispose, Equals, GetHashCode, ToString. NOT: ExecuteAsync (overridden above).
    // Then type `public async override ` and choose StopAsync. EXPECT: `await base.StopAsync(cancellationToken);` in the body.

    // TYPE:override-base-dot — inside ExecuteAsync above, replace `Task.CompletedTask` with `base.` EXPECT: the list after the dot has
    // StartAsync, StopAsync, ExecuteTask, Dispose, ToString (members of BackgroundService and object; before 0.1.85 it was empty).
}

/// <summary>A type with gaps: Alt+Enter and the red errors of missing members.</summary>
public class OvCircle : OvShape
{
    public override double Area() => Math.PI;

    // TYPE:override-ctrl-o — put the caret on this empty line and press Ctrl+O (Code | Override Methods). EXPECT: the dialog «Override
    // Members» grouped by OvShape and object: Describe, Sides, Equals, GetHashCode; OK writes them under this comment, formatted.
    // Ctrl+I (Implement Methods) here: «Nothing to generate» hint — nothing is missing.

    // TYPE:override-alt-enter — Alt+Enter on this empty line. EXPECT: «Override members...» (opens the same dialog). On the name `OvCircle`
    // of the class header: «Override members...» too (it has a base class); on `OvSquare`-like classes without gaps no «Implement
    // missing members».

    // TYPE:implement-missing — delete `public override double Area() => Math.PI;` above. EXPECT: `OvCircle` in the header gets red
    // «CS0534: 'Playground.Editor.OvCircle' does not implement inherited abstract member 'Playground.Editor.OvShape.Area()'» (a second
    // or two, no build). Alt+Enter on `OvCircle`: «Implement missing members» first in the list → the dialog with Area checked → OK:
    // `public override double Area() { throw new NotImplementedException(); }`, the red is gone. Then type `, IDisposable` after
    // `OvShape` in the header: red CS0535 on `IDisposable`; Alt+Enter on it, on the header or on an empty line of the body → Implement
    // missing members → `public void Dispose()`. Ctrl+Z all.
}

/// <summary>Ctrl+P (Parameter Info) in a constructor initializer.</summary>
public class OvFailure : Exception
{
    // TYPE:override-ctor-info — put the caret inside `base(...)` below and press Ctrl+P. EXPECT: the constructors of Exception: `<no
    // parameters>`, `string? message`, `string? message, Exception? innerException` — the second one bold. Before 0.1.85: nothing.
    public OvFailure(string message) : base(message) { }
}
