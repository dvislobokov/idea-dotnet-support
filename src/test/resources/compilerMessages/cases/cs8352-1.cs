// docs: cs8352.md #1; codes: CS8352
class Program
{
    static public Span<int> CreateSpanWithValue(int size, int value)
    {
        Span<int> localSpan = stackalloc int[size];
        localSpan[0] = value;
        return localSpan;    // CS8352
    }                        // localSpan refers to stack memory that's been reclaimed.

    static public void Main()
    {
        Span<int> localSpan = CreateSpanWithValue(5, 10);
        localSpan[2] = 14;
        foreach(var item in localSpan)
        {
            Console.WriteLine(item.ToString());  // Reading from deleted object
        }
    }
    // Example of output:
    // 284945320
    // 149
    // 149
    // 1369466325
    // 284945320
}
