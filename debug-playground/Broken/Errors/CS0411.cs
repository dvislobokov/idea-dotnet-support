// CS0411: The type arguments for method 'M<T>()' cannot be inferred from the usage. Try specifying the type arguments explicitly. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0411;

public class Registry<TKey>
{
    public class Slot
    {
        public static T Make<T>() => default!;
    }

    public static T Lookup<T>(TKey key) => default!;
}

public static class Tools
{
    public static class Parse
    {
        public static T From<T>(string text) => default!;
    }
}

public class Factory
{
    public T Create<T>() where T : new() => new T();
    public void Register<T>() { }
    public void Store<T>(T value) { }
    public void Batch<T>(params T[] values) { }
    public void Pair<TKey, TValue>(TKey key, TValue value) { }
    public T Many<T>() => default!;
    public T Many<T>(T first, T second) => default!;
    public void Configure<T>(int retries = 3) { }
    public void Fill<T>(ref int count) { }
    public void Apply<T>(Func<T, int> rule) { }
    public void Produce<T>(Func<int, int, T> make) { }
    public void Combine<T>(T seed, Func<T, int> rule) { }
    public void Supply<T>(Func<T> source) { }
    static int Twice(int x) => x * 2;
    static int Now() => 42;

    public void Use(Factory other, List<int> numbers)
    {
        var made = Create(); // ERROR CS0411
        var typed = Create<List<int>>();
        Register(); // ERROR CS0411
        Register<string>();
        Store(null); // ERROR CS0411
        other.Store(default); // ERROR CS0411
        Store(42);
        Store<string?>(null);
        Batch(); // ERROR CS0411
        Batch(1, 2, 3);
        Pair("key", null); // ERROR CS0411
        Pair("key", 1);
        var empty = Array.Empty(); // ERROR CS0411
        var none = Array.Empty<int>();
        var many = Many(); // ERROR CS0411
        var pair = Many(null, null); // ERROR CS0411
        var two = Many(1, 2L);
        Configure(); // ERROR CS0411
        Configure<int>(5);
        int count = 0;
        Fill(ref count); // ERROR CS0411
        Apply(x => 1); // ERROR CS0411
        Apply((string s) => s.Length);
        Produce((a, b) => a + b);
        Combine(1, (string s) => 1); // ERROR CS0411
        Combine(1, x => x);
        Apply(Twice); // ERROR CS0411
        Supply(Now);
        var slot = Registry<int>.Slot.Make(); // ERROR CS0411
        var found = Registry<string>.Lookup("key"); // ERROR CS0411
        var parsed = Tools.Parse.From("1"); // ERROR CS0411
        var nothing = Enumerable.Empty(); // ERROR CS0411
        var numbersOnly = Enumerable.Empty<int>();
        Console.WriteLine(typed.Count + none.Length + (made == null ? 0 : 1) + (empty == null ? 0 : 1) + two + numbers.Count);
        Console.WriteLine($"{many}{pair}{slot}{found}{parsed}{nothing}{numbersOnly}");
    }
}
