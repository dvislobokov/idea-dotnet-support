// docs: cs0173.md #1; codes: CS0173 CS8957
class Program
{
    static void Main()
    {
        // CS0173: Type of conditional expression can't be determined
        // because there is no implicit conversion between 'int' and 'string'.
        var result = true ? 100 : "ABC";
    }
}
