// CS0310: 'X' must be a non-abstract type with a public parameterless constructor in order to use it as parameter 'T' in the generic type or method 'G<T>'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
// Signatures of members: the compiler reports them on the name of the member or parameter and stops before method bodies (CS0310.cs).
namespace DebugPlayground.Broken.Errors.CS0310_Signatures;

public class Plain { }

public class NeedsName { public NeedsName(string name) { } }

public abstract class Shape { }

public class Factory<T> where T : new() { }

public class Workshop
{
    public Factory<Plain>? Plains, Spares;
    public Factory<NeedsName>? Named, Labeled; // ERROR CS0310
    public Factory<Plain> Make() => new();
    public Factory<Shape> MakeShape() => new(); // ERROR CS0310
    public void Take(Factory<int> numbers) { }
    public void TakeArrays(Factory<int[]> arrays) { } // ERROR CS0310
    public Factory<Plain>? this[int index] => null;
    public Factory<string>? this[string name] => null; // ERROR CS0310
}

public record Order(Factory<Plain> Source);

public record Batch(Factory<Shape> Source); // ERROR CS0310
