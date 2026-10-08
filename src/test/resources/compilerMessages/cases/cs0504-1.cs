// docs: cs0504.md #1; codes: CS0504
// CS0504.cs  
namespace x  
{  
   abstract public class clx  
   {  
      static const int i = 0;   // CS0504, cannot be both static and const  
      abstract public void f();  
   }  
}  
