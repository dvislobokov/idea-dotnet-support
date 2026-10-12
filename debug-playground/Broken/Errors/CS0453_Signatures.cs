// CS0453: The type 'X' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'G<T>'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
// Signatures of members: the compiler reports them on the name of the member or parameter and stops before method bodies (CS0453.cs).
namespace DebugPlayground.Broken.Errors.CS0453_Signatures;

public struct Money { public decimal Amount; }

public class Account { }

public class Range<T> where T : struct { }

public class Ledger
{
    public Range<Money>? Amounts, Limits;
    public Range<string>? Names, Codes; // ERROR CS0453
    public Range<int> Count() => new();
    public Range<Account> Owners() => new(); // ERROR CS0453
    public void Set(Range<long> values) { }
    public void Reset(Range<int?> values) { } // ERROR CS0453
    public event Action<Range<Money>>? Moved;
    public event Action<Range<object>>? Lost; // ERROR CS0453
    public static Ledger operator +(Ledger ledger, Range<Money> amounts) => ledger;
    public static Ledger operator -(Ledger ledger, Range<string> names) => ledger; // ERROR CS0453
}

public record Balance(Range<Money> Amounts);

public record Audit(Range<Account> Owners); // ERROR CS0453
