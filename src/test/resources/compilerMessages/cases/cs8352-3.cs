// docs: cs8352.md #3; codes: CS8352
class Program
{
    static public Span<int> CreateSpanWithValue(int size, int value)
    {
        Span<int> localSpan = new int[size];
        localSpan[1] = value;
        return localSpan;
    }

    static public void Main()
    {
        var span = CreateSpanWithValue(5, 10);
        span[2] = 14;
        foreach(var item in span)
        {
            Console.WriteLine(item.ToString());
        }
    }
    // Output:
    //0
    //10
    //14
    //0
    //0
}
