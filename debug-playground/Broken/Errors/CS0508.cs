// CS0508: 'D.M()': return type must be 'T' to match overridden member 'B.M()'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0508;

public class Animal { }
public class Cat : Animal { }
public sealed class Rock { }

public abstract class Shelter
{
    public abstract int Count();
    public abstract Animal Adopt();
    public virtual Task SaveAsync() => Task.CompletedTask;
    public virtual void Clean() { }
    public virtual string Describe() => "shelter";
    public virtual object Payload() => new object();
}

public class CatShelter : Shelter
{
    public override long Count() => 0; // ERROR CS0508
    public override Cat Adopt() => new Cat();
    public override Task SaveAsync() => Task.CompletedTask;
    public override int Clean() => 0; // ERROR CS0508
    public override string Describe() => "cats";
    public override string Payload() => "payload";
}

public class RockShelter : Shelter
{
    public override int Count() => 0;
    public override Rock Adopt() => new Rock(); // ERROR CS0508
    public override void SaveAsync() { } // ERROR CS0508
    public override object Describe() => "rocks"; // ERROR CS0508
}

public class Item
{
    public override string GetHashCode() => "item"; // ERROR CS0508
    public override bool Equals(object? obj) => false;
    public override string ToString() => "item";
}

public abstract class Store<T>
{
    public virtual T Make() => default!;
    public virtual U Get<U>() => default!;
    public virtual List<T> All() => new();
}

public class TextStore : Store<string>
{
    public override string Make() => "";
    public override W Get<W>() => default!;
    public override List<object> All() => new(); // ERROR CS0508
}

public class ObjectStore : Store<string>
{
    public override object Make() => ""; // ERROR CS0508
    public override object Get<W>() => 1; // ERROR CS0508
    public override List<string> All() => new();
}

public class OpenStore<X> : Store<X>
{
    public override X Make() => default!;
    public override X Get<W>() => default!; // ERROR CS0508
}
