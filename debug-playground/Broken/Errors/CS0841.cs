// CS0841: Cannot use local variable 'x' before it is declared. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0841;

public class UseBeforeDeclaration
{
    private int total;

    public int Field()
    {
        total = 1;
        var result = total;
        return result;
    }

    public void Shadowing()
    {
        Console.WriteLine(count); // ERROR CS0841
        var count = 1;
        Console.WriteLine(count);
    }

    public void Assignment()
    {
        limit = 5; // ERROR CS0841
        var limit = 10;
        Console.WriteLine(limit);
    }

    public void InLambda()
    {
        Func<int> later = () => step; // ERROR CS0841
        var step = 2;
        Console.WriteLine(later() + step);
    }

    public void InLocalFunction()
    {
        Console.WriteLine();
        void Report() => Console.WriteLine(factor); // ERROR CS0841
        var factor = 3;
        Console.WriteLine(factor);
    }

    public void Patterns(object value)
    {
        if (number > 0 && value is int number) Console.WriteLine(number); // ERROR CS0841
        if (value is int other && other > 0) Console.WriteLine(other);
    }

    public void Declarators()
    {
        int first = second, second = 1; // ERROR CS0841
        Console.WriteLine(first + second);
    }

    public int Size { get; set; }
    public void Reset() { }

    public void HidesNoField()
    {
        Size = 1; // ERROR CS0841
        var Size = 2;
        Console.WriteLine(Reset); // ERROR CS0841
        var Reset = "";
        Console.WriteLine(Size + Reset + this.Size);
        this.Reset();
    }

    public void Fine()
    {
        Console.WriteLine();
        void Helper() => Console.WriteLine(this.total);
        var total = 1;
        Func<int, int> shadow = total => total;
        Console.WriteLine(this.total + total + shadow(1));
    }
}
