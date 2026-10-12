// docs: cs0029.md #5; codes: CS0029
// CS0029.cs
public class MyInt
{
    private int x = 0;

    // Uncomment this conversion routine to resolve CS0029.
    /*
    public static implicit operator int(MyInt i)
    {
        return i.x;
    }
    */

    public static void Main()
    {
        var myInt = new MyInt();
        int i = myInt; // CS0029
    }
}
