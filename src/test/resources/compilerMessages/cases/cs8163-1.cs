// docs: cs8163.md #1; codes: CS8163
// CS8163.cs (12,14)
public class Test
{
    public struct S1
    {
        public char x;
    }

    public static readonly S1 s2;

    char Test2()
    {
        return s2.x;
    }
}
