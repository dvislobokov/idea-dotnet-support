// docs: cs8156.md #2; codes: CS8156
class Test
{
    delegate int D1();

    void Test1()
    {
        D1 d1 = () => 2 + 2;
    }
}
