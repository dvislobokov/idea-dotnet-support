// docs: cs3009.md #1; codes: CS3009
// CS3009.cs  
  
using System;  
  
[assembly:CLSCompliant(true)]  
[CLSCompliant(false)]  
public class B  
{  
}  
  
public class C : B   // CS3009  
{  
    public static void Main () {}  
}  
