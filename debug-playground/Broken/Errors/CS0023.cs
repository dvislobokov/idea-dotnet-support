// CS0023: Operator 'op' cannot be applied to operand of type 'A'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0023;

public enum UnState { Off, On }

public struct UnVector { public int X; }

public class UnCounter
{
    public int Value;
    public bool Enabled { get; set; }
}

public class UnaryCases
{
    public void Run(int count, bool done, string name, double rate, UnState state, UnCounter counter, UnVector vector, int? maybe, ulong huge, uint small)
    {
        var flipped = !count; // ERROR CS0023
        var negated = -done; // ERROR CS0023
        var notDone = !done;
        var minus = -count + -rate + +rate;
        var inverted = ~rate; // ERROR CS0023
        var mask = (~count & 3) + (int)~state;
        var text = name + -1;
        var noState = -state; // ERROR CS0023
        var noCounter = !counter; // ERROR CS0023
        var enabled = !counter.Enabled;
        var away = -vector.X;
        var maybeNot = !maybe; // ERROR CS0023
        var maybeMinus = -maybe;
        var unsigned = -huge; // ERROR CS0023
        var promoted = -small;
        done++; // ERROR CS0023
        count++;
        state++;
        --counter.Value;
        Console.WriteLine($"{flipped} {negated} {notDone} {minus} {inverted} {mask} {text} {noState} {noCounter} {enabled} {away} {maybeNot} {maybeMinus} {unsigned} {promoted}");
    }
}
