// docs: cs8070.md #1; codes: CS8070
// CS8070.cs
public class MyClass
{
    public static void Main()
    {
        int i = 2;
        int j = 0;

        switch (i)
        {
            case 1:
                i++;
                break;
            
            // Compiler error CS8070 is reported on the following line.
            case 2:
                i += 2;
            // To resolve the error, uncomment one of the following example statements.  
            // break;
            // return;
            // throw new Exception("Fin");
        }

        switch (j)
        {
            case 1:
                j++;
                break;
            
            case 2:
                j += 2;
                break;

            // Compiler error CS8070 is reported on the following line.
            default:
                Console.WriteLine("Default");
            // To resolve the error, uncomment the following line:
            // break;
        }
    }
}
