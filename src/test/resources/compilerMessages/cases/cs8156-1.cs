// docs: cs8156.md #1; codes: CS8156
// CS8156.cs (7,27)

class Test
{
    delegate ref int D1();

    void Test1()
    {
        D1 d1 = () => ref 2 + 2;
    }
}
