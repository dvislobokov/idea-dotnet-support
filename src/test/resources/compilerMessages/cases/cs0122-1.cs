// docs: cs0122.md #1; codes: CS0122
// CS0122.cs
public class MyClass
{
    private int data;

    void PrivateMethod() {}
    public void PublicMethod() {}
}

public static class MyClassExtensions
{
    public static int GetData(this MyClass myClass)
    {
        return myClass.data;   // CS0122
    }
}

public class Program
{
    public static void Main()
    {
        MyClass a = new MyClass();
        a.PrivateMethod();   // CS0122
        a.PublicMethod();   // OK
    }
}
