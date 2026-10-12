// CS0239: 'D.M()': cannot override inherited member 'B.M()' because it is sealed. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0239;

public abstract class Animal
{
    public abstract string Sound();
    public virtual string Name => "animal";
    public virtual int Legs() => 4;
    public virtual void Feed(int grams) { }
}

public class Dog : Animal
{
    public sealed override string Sound() => "woof";
    public sealed override string Name => "dog";
    public override int Legs() => 4;
    public sealed override void Feed(int grams) { }
}

public class Puppy : Dog
{
    public override string Sound() => "yip"; // ERROR CS0239
    public override string Name => "puppy"; // ERROR CS0239
    public override int Legs() => 4;
    public override void Feed(int grams) { } // ERROR CS0239
    public new string Sound(int times) => string.Concat(Enumerable.Repeat("yip", times));
}

public class Wolf : Animal
{
    public override string Sound() => "howl";
    public override string Name => "wolf";
}

public class Cub : Wolf
{
    public override string Sound() => "squeak";
    public override string Name => "cub";
}
