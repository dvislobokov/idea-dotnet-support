// docs: preprocessor-errors.md #17; codes: CS1024 CS1025 CS1027 CS1028 CS1029 CS1030 CS1032 CS1038 CS1040 CS1517 CS1560 CS1576 CS1578 CS1633 CS1634 CS1635 CS1691 CS1692 CS1694 CS1695 CS1696 CS1709 CS2029 CS7009 CS7010 CS7011 CS8097 CS8098 CS8301 CS8938 CS8939 CS8996 CS9028 CS9297 CS9298 CS9299 CS9314 CS9378
// CS1691.cs
public class C
{
    int i = 1;
    public static void Main()
    {
        C myC = new C();
#pragma warning disable 151  // CS1691
// Try the following line instead:
// #pragma warning disable 1645
        myC.i++;
#pragma warning restore 151  // CS1691
// Try the following line instead:
// #pragma warning restore 1645
    }
}
