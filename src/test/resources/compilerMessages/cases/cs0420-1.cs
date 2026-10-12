// docs: cs0420.md #1; codes: CS0420
// CS0420.cs  
// compile with: /W:1  
using System;  
  
class TestClass  
{  
   private volatile int i;  
  
   public void TestVolatile(ref int ii)  
   {  
   }  
  
   public static void Main()  
   {  
      TestClass x = new TestClass();  
      x.TestVolatile(ref x.i);   // CS0420
   }  
}  
