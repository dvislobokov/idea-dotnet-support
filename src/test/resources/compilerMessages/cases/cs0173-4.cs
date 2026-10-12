// docs: cs0173.md #4; codes: CS0173 CS8957
class Program
{
    static void Main()
    {
        // This example shows how different C# versions handle the same code.
        
        // In C# 8.0 and earlier: CS8957 (feature not available).
        // In C# 9.0+: Compiles successfully.
        object? result = (1 == 0) ? null : null;
    }
}
