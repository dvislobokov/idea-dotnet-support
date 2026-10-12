// CS0453: The type 'X' must be a non-nullable value type in order to use it as parameter 'T' in the generic type or method 'G<T>'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0453;

public struct Money { public decimal Amount; }

public class Account { }

public enum Status { Open, Closed }

public class Range<T> where T : struct { }

public static class Parse
{
    public static T? Maybe<T>(string text) where T : struct => null;
    public static string Show<T>(T value) where T : struct => value.ToString() ?? "";
}

public class Uses
{
    public void Run()
    {
        var numbers = new Range<int>();
        var amounts = new Range<Money>();
        Range<Status> states = new();
        Range<string>? names = null; // ERROR CS0453
        var optional = new Range<int?>(); // ERROR CS0453
        Nullable<int> value = 1;
        Nullable<Account> account = null; // ERROR CS0453
        var money = Parse.Maybe<Money>("1");
        var wrong = Parse.Maybe<Account>("1"); // ERROR CS0453
        var objects = new Range<object>(); // ERROR CS0453
        var shown = Parse.Show(Status.Open);
        var text = Parse.Show("open"); // ERROR CS0453
        var account2 = Parse.Show(new Account()); // ERROR CS0453
        Console.WriteLine($"{numbers}{amounts}{states}{names}{optional}{value}{money}{objects}{shown}{text}{account2}");
    }
}
