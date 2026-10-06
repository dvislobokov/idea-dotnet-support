// CS0310: 'X' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'G<T>'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0310;

public class Plain { }

public class WithDefault { public WithDefault() { } }

public class Optional { public Optional(int size = 0) { } }

public class NeedsName { public NeedsName(string name) { } }

public class Hidden { private Hidden() { } }

public abstract class Shape { }

public struct Point { public int X; }

public record Person(string Name);

public interface IThing { }

public class Factory<T> where T : new() { public T Make() => new T(); }

public static class Make
{
    public static T One<T>() where T : new() => new T();
    public static T Like<T>(T sample) where T : new() => new T();
}

public class Uses
{
    public void Run()
    {
        var plains = new Factory<Plain>();
        var defaults = new Factory<WithDefault>();
        Factory<Point> points = new();
        var numbers = new Factory<int>();
        var lists = new Factory<List<int>>();
        Factory<NeedsName>? named = null; // ERROR CS0310
        var optionals = new Factory<Optional>(); // ERROR CS0310
        var shapes = new Factory<Shape>(); // ERROR CS0310
        var plain = Make.One<Plain>();
        var hidden = Make.One<Hidden>(); // ERROR CS0310
        var person = Make.One<Person>(); // ERROR CS0310
        var text = Make.One<string>(); // ERROR CS0310
        var things = new Factory<IThing>(); // ERROR CS0310
        var arrays = new Factory<int[]>(); // ERROR CS0310
        var copy = Make.Like(new Plain());
        var inferred = Make.Like("text"); // ERROR CS0310
        Console.WriteLine($"{plains}{defaults}{points}{numbers}{lists}{named}{optionals}{shapes}{plain}{hidden}{person}{text}{things}{arrays}{copy}{inferred}");
    }
}
