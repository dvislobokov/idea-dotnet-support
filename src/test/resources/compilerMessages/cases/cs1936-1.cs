// docs: cs1936.md #1; codes: CS1936
// cs1936.cs  
using System.Collections;  
using System.Linq;  
class Test  
{  
    static int Main()  
    {  
        object obj;  
        IEnumerable e = from x in obj // CS1936  
                        select x;  
        return 0;  
    }  
}  
