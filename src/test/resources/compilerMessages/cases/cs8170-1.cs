// docs: cs8170.md #1; codes: CS8170
// CS8170.cs (8,14)

struct Program
{
    public int d;

    public ref int M()
    {
        return ref d;
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
