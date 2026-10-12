using Lib;

namespace DebugTarget;

static class Stepping
{
    static int Prop { get; set; } = 5;
    static int Computed => Prop * 2;

    public static void Run()
    {
        var calc = new Calculator();
        var sum = calc.Add(1, 2); // BP:step-start
        var viaProp = Computed + Prop; // BP:step-prop
        var echoed = Calculator.Echo("text");
        var squares = new[] { 1, 2, 3 }.Select(n => n * n).ToList(); // BP:step-lambda
        foreach (var item in Numbers()) // BP:step-iterator
        {
            sum += item;
        }
        try
        {
            Thrower(); // BP:step-try
        }
        catch (InvalidOperationException e)
        {
            Console.WriteLine("caught " + e.Message); // BP:step-catch
        }
        finally
        {
            Console.WriteLine("finally");
        }
        Console.WriteLine(sum + viaProp + echoed.Length + squares.Count); // BP:step-end
    }

    static IEnumerable<int> Numbers()
    {
        yield return 1; // BP:iterator-body
        yield return 2;
    }

    static void Thrower() => throw new InvalidOperationException("from thrower");
}
