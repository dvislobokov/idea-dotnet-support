// docs: preprocessor-errors.md #16; codes: CS1024 CS1025 CS1027 CS1028 CS1029 CS1030 CS1032 CS1038 CS1040 CS1517 CS1560 CS1576 CS1578 CS1633 CS1634 CS1635 CS1691 CS1692 CS1694 CS1695 CS1696 CS1709 CS2029 CS7009 CS7010 CS7011 CS8097 CS8098 CS8301 CS8938 CS8939 CS8996 CS9028 CS9297 CS9298 CS9299 CS9314 CS9378
// CS1635.cs
// compile with: /w:1 /nowarn:162

enum MyEnum {one=1,two=2,three=3};

class MyClass
{
    public static void Main()
    {
#pragma warning disable 162

    if (MyEnum.three == MyEnum.two)
        System.Console.WriteLine("Duplicate");

#pragma warning restore 162  // CS1635
    }
}
