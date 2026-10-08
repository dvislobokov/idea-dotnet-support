// docs: array-declaration-errors.md #1; codes: CS0022 CS0178 CS0248 CS0251 CS0270 CS0611 CS0623 CS0650 CS0719 CS0747 CS0820 CS0826 CS0846 CS1062 CS1063 CS1064 CS1552 CS1586 CS1920 CS1921 CS1922 CS1925 CS1950 CS1954 CS3007 CS3016 CS8346 CS8353 CS8381 CS9174 CS9176 CS9185 CS9186 CS9187 CS9188 CS9203 CS9208 CS9209 CS9210 CS9212 CS9213 CS9214 CS9215 CS9221 CS9222 CS9332 CS9354 CS9355 CS9356 CS9357 CS9358 CS9359
public class Test
{
    public static void Main()
    {
        var invalid = new TestClass { 1, "hello" }; // CS1922
        var valid = new TestClass { MemberA = 1, MemberB = "hello" };
    }
}

public class TestClass
{
    public int MemberA { get; set; }
    public string MemberB { get; set; } = "";
}
