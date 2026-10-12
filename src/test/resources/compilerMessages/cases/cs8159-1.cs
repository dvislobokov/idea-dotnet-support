// docs: cs8159.md #1; codes: CS8159
// CS8159.cs (7,74)

using System.Linq;
class TestClass
{
    delegate ref char RefCharDelegate();
    void TestMethod()
    {
        var x = from c in "TestValue" select (RefCharDelegate)(() => ref c);
    }
}
