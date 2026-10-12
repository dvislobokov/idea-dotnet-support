// docs: cs8170.md #4; codes: CS8170
using System.Diagnostics.CodeAnalysis;

struct Program
{
    public int d;

    [UnscopedRef]
    public ref int M()
    {
        return ref d;    // No error - ref is valid to escape the scope in this line of that method
    }
}

public class Other
{
    public void Method()
    {
        var p = new Program();
        ref int d = ref p.M();
    }
}
