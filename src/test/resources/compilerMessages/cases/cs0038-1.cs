// docs: cs0038.md #1; codes: CS0038
// CS0038.cs
class OuterClass
{
    public int count;
    // Try the following line instead.
    // public static int count;

    class InnerClass
    {
        void Func()
        {
            // or, create an instance
            // OuterClass class_inst = new OuterClass();
            // int count2 = class_inst.count;
            int count2 = count;   // CS0038
        }
    }

    public static void Main()
    {
    }
}
