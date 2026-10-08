// docs: cs8161.md #1; codes: CS8161
// CS8161.cs (12,14)
public class Test
{
    public struct S1
    {
        public char x;
    }

    public static readonly char s1;

    ref char Test2()
    {
        return ref s1;
    }
}
