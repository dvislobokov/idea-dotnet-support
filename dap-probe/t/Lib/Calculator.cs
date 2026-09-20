namespace Lib;

public class Calculator
{
    public int Calls { get; private set; }

    public int Add(int a, int b)
    {
        Calls++;
        var result = a + b; // BP:lib-add
        return result;
    }

    public static T Echo<T>(T value)
    {
        return value; // BP:lib-generic
    }
}
