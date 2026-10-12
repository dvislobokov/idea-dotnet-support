// CS8852: Init-only property or indexer 'x' can only be assigned in an object initializer, or on 'this' or 'base' in an instance constructor or an 'init' accessor. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS8852;

public record Customer(string Name, int Age);

public readonly record struct Money(decimal Amount);

public record struct Cursor(int Line);

public class Profile
{
    public string Email { get; init; } = "";
    public string Display { get => Email; init => Email = value; }
    public int Level { get; protected init; }

    public Profile() { Email = "none"; }

    public Profile(string email, bool later)
    {
        Display = email;
        Action set = () => Email = email; // ERROR CS8852
        if (later) set();
    }

    public Profile(Profile other)
    {
        this.Email = other.Email;
        other.Email = "taken"; // ERROR CS8852
    }

    public void Change(string email) => Email = email; // ERROR CS8852
}

public class Admin : Profile
{
    public Admin() { Email = "root"; base.Email = "admin"; }
}

public class Registry
{
    public void Register(Customer customer, Profile profile, Money money, Cursor cursor)
    {
        var renamed = customer with { Name = "Ann" };
        var created = new Profile { Email = "a@b.c" };
        cursor.Line = 2;
        customer.Name = "Bob"; // ERROR CS8852
        profile.Email = "x@y.z"; // ERROR CS8852
        money.Amount = 1; // ERROR CS8852
        profile.Level = 3; // ERROR CS8852
        Console.WriteLine(renamed.Name + created.Email + cursor.Line);
    }
}
