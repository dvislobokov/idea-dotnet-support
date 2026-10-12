// docs: cs0120.md #1; codes: CS0120
// CS0120_1.cs
public class MyClass
{
    // Non-static field.
    public int i;
    // Non-static method.
    public void Method() { }
    // Non-static property.
    public int Prop
    {
        get
        {
            return 1;
        }
    }

    public static void Main()
    {
        i = 10;   // CS0120
        Method();   // CS0120
        int p = Prop;   // CS0120
    }
}
