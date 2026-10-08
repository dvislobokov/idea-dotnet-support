// docs: cs0052.md #1; codes: CS0052
// CS0052.cs
public class MyClass2
{
    // The following line causes an error because the field, M, is declared
    // as public, but the type, MyClass, is private. Therefore the type is
    // less accessible than the field.
    public MyClass M;   // CS0052

    private class MyClass
    {
    }
    // One way to resolve the error is to change the accessibility of the type
    // to public.
    //public class MyClass
    // Another solution is to change the accessibility of the field to private.
    // private MyClass M;
}

public class MainClass
{
    public static void Main()
    {
    }
}
