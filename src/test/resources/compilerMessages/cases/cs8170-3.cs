// docs: cs8170.md #3; codes: CS8170
public struct Program
{
    public int d;
}

public static class Extensions
{
    public static ref readonly int RefD(this in Program program)
    {
        return ref program.d;
    }
}

public class Other
{
    public void Method()
    {
        var p = new Program();
        var d = p.RefD();
    }
}
