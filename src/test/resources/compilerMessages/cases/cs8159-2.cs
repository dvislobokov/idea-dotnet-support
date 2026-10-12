// docs: cs8159.md #2; codes: CS8159
using System.Linq;
class TestClass
{
    delegate char RefCharDelegate();
    void TestMethod()
    {
        var x = from c in "TestValue" select (RefCharDelegate)(() => c);
    }
}
