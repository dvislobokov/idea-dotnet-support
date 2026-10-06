// CS0019: Operator 'op' cannot be applied to operands of type 'A' and 'B'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0019;

public enum OpStatus { Draft, Paid }
public enum OpSize { Small, Large }

public struct OpPoint { public int X; }

public class OpOrder { public decimal Total; }
public class OpSpecialOrder : OpOrder { }

public readonly struct OpMoney(decimal amount)
{
    public decimal Amount { get; } = amount;
    public static OpMoney operator +(OpMoney a, OpMoney b) => new(a.Amount + b.Amount);
}

public class OperatorCases
{
    public void Numbers(int count, long big, decimal price, double rate, float ratio, char letter, byte small, int? maybe, uint unsigned)
    {
        var total = price * rate; // ERROR CS0019
        var fine = price * count + big;
        var scaled = rate * ratio + letter - small;
        var lifted = maybe + count;
        var shifted = count << 2;
        var wide = unsigned + count;
        Console.WriteLine($"{total} {fine} {scaled} {lifted} {shifted} {wide}");
    }

    public void Flags(bool done, bool? maybe, int count, string name, OpStatus status, OpSize size)
    {
        var sum = done + count; // ERROR CS0019
        var both = done && maybe; // ERROR CS0019
        var ok = done && count > 0 || maybe == true;
        var bits = done & done ^ (maybe ?? false);
        var compared = name == count; // ERROR CS0019
        var text = name + count + done + status + maybe;
        var minus = name - 1; // ERROR CS0019
        var sameEnum = status == OpStatus.Paid && status != OpStatus.Draft;
        var otherEnum = status == size; // ERROR CS0019
        var zero = status == 0;
        var gap = status - status;
        var times = status * 2; // ERROR CS0019
        Console.WriteLine($"{sum} {both} {ok} {bits} {compared} {text} {minus} {sameEnum} {otherEnum} {zero} {gap} {times}");
    }

    public void Objects(OpPoint a, OpPoint b, OpOrder order, OpSpecialOrder special, OpMoney cash, object any, string name)
    {
        var points = a == b; // ERROR CS0019
        var orders = order == special;
        var cost = order + 1; // ERROR CS0019
        var money = cash + cash;
        var described = name + a + order;
        var boxed = any == order;
        Console.WriteLine($"{points} {orders} {cost} {money} {described} {boxed}");
    }

    public void Compound(int count, bool done, string name)
    {
        count += done; // ERROR CS0019
        name += count;
        count += 'a';
        Console.WriteLine($"{count} {done} {name}");
    }
}
