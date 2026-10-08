// docs: cs0120.md #2; codes: CS0120
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
        var mc = new MyClass();
        mc.i = 10;
        mc.Method();
        int p = mc.Prop;
    }
}
