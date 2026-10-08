// docs: cs8352.md #2; codes: CS8352
class Program
{
    static public void FillSpanWithValue(Span<int> span, int value)
    {
        span[0] = value;
    }

    static public void Main()
    {
        Span<int> localSpan = stackalloc int[5];
        FillSpanWithValue(localSpan, 10);
        localSpan[2] = 14;
        foreach(var item in localSpan)
        {
            Console.WriteLine(item.ToString());
        }
    }
    // Output:
    // 10
    // 0
    // 14
    // 0
    // 0
}
