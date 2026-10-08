// docs: cs8158.md #1; codes: CS8158
// CS8158.cs (11,14)

public class Test
{
    public struct S1
    {
        public char x;
    }

    ref char Test1(char arg1, S1 arg2)
    {
        ref S1 r = ref arg2;
        return ref r.x;
    }
}
