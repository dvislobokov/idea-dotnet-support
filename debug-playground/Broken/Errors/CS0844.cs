// CS0844: Cannot use local variable 'x' before it is declared. The declaration of the local variable hides the field 'T.x'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0844;

public class Counter
{
    private int total;
    private static int instances;
    private const int Limit = 10;
    public int Step { get; set; }

    public void Read()
    {
        Console.WriteLine(total); // ERROR CS0844
        var total = 1;
        Console.WriteLine(total + this.total);
    }

    public void Write()
    {
        instances = 2; // ERROR CS0844
        var instances = 3;
        Console.WriteLine(instances + Counter.instances);
    }

    public void Constant()
    {
        Func<int> limit = () => Limit; // ERROR CS0844
        int Limit = 5;
        Console.WriteLine(limit() + Limit);
    }

    public void Fine()
    {
        Console.WriteLine(this.total + Counter.instances + Step);
        var total = 4;
        var instances = 5;
        Console.WriteLine(total + instances);
    }
}

public class Base
{
    protected string name = "";
}

public class Derived : Base
{
    public void Rename()
    {
        Console.WriteLine(name); // ERROR CS0844
        var name = "new";
        Console.WriteLine(name + base.name);
    }
}

public class Box<T>
{
    private T? value;

    public void Show()
    {
        Console.WriteLine(value); // ERROR CS0844
        T? value = default;
        Console.WriteLine(value);
        Console.WriteLine(this.value);
    }
}
