// docs: cs1001.md #3; codes: CS1001
interface IMyTest
{
    void TestFunc1(int, int);  // CS1001
    // Use the following line instead:
    // void TestFunc1(int a, int b);
}

class CMyTest : IMyTest
{
    void IMyTest.TestFunc1(int a, int b)
    {
    }
}
