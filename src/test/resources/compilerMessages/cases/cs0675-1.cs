// docs: cs0675.md #1; codes: CS0675
// CS0675.cs  
// compile with: /W:3  
using System;  
  
public class sign  
{  
   public static void Main()  
   {  
      int hi = 1;  
      int lo = -1;  
      long value = (((long)hi) << 32) | lo;              // CS0675, value contains -1 (0xffffffff_ffffffff)
      // try the following line instead  
      // long value = (((long)hi) << 32) | ((uint)lo);   // correct, value contains 8589934591 (0x00000001_ffffffff)
   }  
}  
