// docs: cs0173.md #3; codes: CS0173 CS8957
public class C {}

public class A
{
    // Uncomment to add implicit conversion from C to A.
    //public static implicit operator A(C c)
    //{
    //    return new A();
    //}
}

public class MyClass
{
    public static void F(bool b)
    {
        A a = new A();
        C c = new C();

        // CS0173: No implicit conversion between A and C.
        var result = b ? a : c;

        // Fix: Cast to common base type.
        object result2 = b ? (object)a : (object)c;

        // Or in C# 9.0+, provide target type.
        object result3 = b ? a : c;  // OK in C# 9.0+.
    }
}
