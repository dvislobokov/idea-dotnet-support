// docs: cs0103.md #1; codes: CS0103
using System;

class MyClass1
{
    public static void Main()
    {
        try
        {
            // The following declaration is only available inside the try block.
            var conn = new MyClass1();
        }
        catch (Exception e)
        {
            // The following expression causes error CS0103, because variable
            // conn only exists in the try block.
            if (conn != null)
                Console.WriteLine("{0}", e);
        }
    }
}
