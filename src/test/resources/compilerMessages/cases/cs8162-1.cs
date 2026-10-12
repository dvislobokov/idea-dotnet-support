// docs: cs8162.md #1; codes: CS8162
// CS8162.cs (12,14)
public class Test
{
    public struct S1
    {
        public char x;
    }

    public readonly S1 i2;

    ref char Test1()
    {
        return ref i2.x;
    }
}
