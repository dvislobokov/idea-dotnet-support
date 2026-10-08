// docs: cs0051.md #1; codes: CS0051
// CS0051.cs
public class A
{
    // B is implicitly private here.
    class B
    {
    }

    public static void F(B b)  // CS0051
    {
    }

    public static void Main()
    {
    }
}
