// docs: cs1943.md #1; codes: CS1943
// cs1943.cs  
using System.Linq;  
class Test  
{  
    class TestClass  
    { }  
    static void Main()  
    {  
        int[] nums = { 0, 1, 2, 3, 4, 5 };  
        TestClass tc = new TestClass();  
  
        var x = from n in nums  
                from s in tc // CS1943  
                select n + s;  
    }  
}  
