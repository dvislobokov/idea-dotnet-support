// CS0452: The type 'X' must be a reference type in order to use it as parameter 'T' in the generic type or method 'G<T>'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0452;

public struct Money { public decimal Amount; }

public class Account { }

public interface IAudit { }

public class Cache<T> where T : class { }

public static class Pool
{
    public static T? Take<T>() where T : class => null;
    public static T Keep<T>(T item) where T : class => item;
}

public class Registry<TKey>
{
    public class Slot<TValue> where TValue : class { }
    public static void Put<TValue>(TKey key, TValue value) where TValue : class { }
}

public class Uses
{
    public void Run()
    {
        var names = new Cache<string>();
        var accounts = new Cache<Account>();
        var arrays = new Cache<int[]>();
        var audits = new Cache<IAudit>();
        Cache<int>? numbers = null; // ERROR CS0452
        var amounts = new Cache<Money>(); // ERROR CS0452
        var weak = new WeakReference<Account>(new Account());
        var broken = new WeakReference<int>(1); // ERROR CS0452
        var account = Pool.Take<Account>();
        var money = Pool.Take<Money>(); // ERROR CS0452
        var nullable = Pool.Take<int?>(); // ERROR CS0452
        var dates = new Cache<DateTime>(); // ERROR CS0452
        var kept = Pool.Keep(new Account());
        var number = Pool.Keep(42); // ERROR CS0452
        var slots = new Registry<int>.Slot<Account>();
        var badSlots = new Registry<int>.Slot<Money>(); // ERROR CS0452
        Registry<string>.Put("key", new Account());
        Registry<string>.Put("key", 1.5); // ERROR CS0452
        Console.WriteLine($"{names}{accounts}{arrays}{audits}{numbers}{amounts}{weak}{broken}{account}{money}{nullable}{dates}{kept}{number}{slots}{badSlots}");
    }
}
