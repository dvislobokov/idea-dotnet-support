// CS1715: 'C.P': type must be 'T' to match overridden member 'B.P'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1715;

public class Animal { }

public class Dog : Animal { }

public abstract class Shelter<T>
{
    public virtual int Size { get; set; }
    public virtual Animal Resident { get; } = new();
    public virtual Animal Guest { get; set; } = new();
    public virtual T? Keeper { get; set; }
    public virtual int this[int index] => index;
    public virtual string this[string name] { get => name; set { } }
}

public class DogShelter : Shelter<string>
{
    public override int Size { get; set; }
    public override Dog Resident { get; } = new();
    public override string? Keeper { get; set; }
    public override int this[int index] => index * 2;
}

public class WrongShelter : Shelter<string>
{
    public override long Size { get; set; } // ERROR CS1715
    public override Dog Guest { get; set; } = new(); // ERROR CS1715
    public override object? Keeper { get; set; } // ERROR CS1715
    public override long this[int index] => index; // ERROR CS1715
    public override object this[string name] { get => name; set { } } // ERROR CS1715
}

public class Failure : Exception
{
    public override string Message => "failure";
    public override string? StackTrace => null;
}

public class WrongFailure : Exception
{
    public override object Message => "failure"; // ERROR CS1715
}
