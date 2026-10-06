// CS0452: The type 'X' must be a reference type in order to use it as parameter 'T' in the generic type or method 'G<T>'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
// Signatures of members: the compiler reports them on the name of the member or parameter and stops before method bodies (CS0452.cs).
namespace DebugPlayground.Broken.Errors.CS0452_Signatures;

public class Account { }

public class Cache<T> where T : class { }

public class TagAttribute<T> : Attribute where T : class { }

public class Store
{
    public Cache<Account>? Accounts, Archive;
    public Cache<int>? Numbers, Totals; // ERROR CS0452
    public Cache<Account>? Find(Cache<Account> key) => key;
    public Cache<int>? Load(int id) => null; // ERROR CS0452
    public void Save(Cache<long> values) { } // ERROR CS0452
    public Cache<Account> this[string name] => new();
    public Cache<int> this[int index] => new(); // ERROR CS0452
    public static Cache<Account>? operator +(Store store, Cache<Account> extra) => extra;
    public static Store operator -(Store store, Cache<int> extra) => store; // ERROR CS0452
    public static implicit operator Cache<double>(Store store) => new(); // ERROR CS0452
    public event Action<Cache<Account>>? Changed;
    public event Action<Cache<int>>? Moved; // ERROR CS0452
    [Tag<Account>] public void Marked() { }
    [Tag<int>] public void Wrong() { } // ERROR CS0452
}

public record Entry(Cache<Account> Owner, int Size);

public record Line(Cache<int> Values); // ERROR CS0452

public class Reader(Cache<Account> source) { public object Source => source; }

public class Writer(Cache<int> target) { public object Target => target; } // ERROR CS0452
