// docs: cs0103.md #2; codes: CS0103
using System;

class MyClass2
{
    public static void Main()
    {
        // To resolve the error in the example, the first step is to
        // move the declaration of conn out of the try block. The following
        // declaration is available throughout the Main method.
        MyClass2 conn = null;
        try
        {
            // Inside the try block, use the conn variable that you declared
            // previously.
            conn = new MyClass2();
        }
        catch (Exception e)
        {
            // The following expression no longer causes an error, because
            // the declaration of conn is in scope.
            if (conn != null)
                Console.WriteLine("{0}", e);
        }
    }
}
