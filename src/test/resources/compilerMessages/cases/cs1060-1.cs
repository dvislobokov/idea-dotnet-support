// docs: cs1060.md #1; codes: CS1060
// cs1060.cs  
namespace CS1060  
{
    public class U  
    {  
        public int i;  
    }  
  
    public struct S  
    {  
        public U u;  
        // Add constructor to correct the error.  
        //public S(int val)  
        //{  
        //    u = new U() { i = val };  
        //}  
    }  
    public class Test  
    {  
        static void Main()  
        {  
            S s;  
            s.u.i = 5;  // CS1060  
  
            //Try these lines instead, and uncomment the constructor in S  
            // S s = new S(0);  
            // s.u.i = 5;  
  
        }  
    }
}  
