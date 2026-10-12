// docs: cs0429.md #1; codes: CS0429
// CS0429.cs  
public class cs0429
{  
    public static void Main()
    {  
        if (false && myTest())  // CS0429  
        // Try the following line instead:  
        // if (true && myTest())  
        {  
        }  
        else  
        {  
            int i = 0;  
            i++;  
        }  
    }  
  
    static bool myTest() { return true; }  
}  
