// docs: cs1941.md #1; codes: CS1941
// cs1941.cs  
using System.Collections;  
using System.Linq;  
class Test  
{  
    static int Main()  
    {  
        var nums = new[] { 1, 2, 3, 4, 5, 6 };  
        var words = new string[] { "lake", "mountain", "sky" };  
        IEnumerable e = from n in nums  
                        join w in words on n equals w // CS1941  
                        select w;  
        return 0;  
    }  
}  
