// docs: cs1058.md #1; codes: CS1058
// CS1058.cs  
// CS1058 expected  
using System.Runtime.CompilerServices;  
  
// the following attribute is set to true by default in the C# compiler  
// set to false in your source code to resolve CS1058  
[assembly: RuntimeCompatibilityAttribute(WrapNonExceptionThrows = true)]  
  
class TestClass
{  
   static void Main()
   {  
      try {}  
  
      catch (System.Exception e) {
         System. Console.WriteLine("Caught exception {0}", e);  
      }  
  
      catch {}   // CS1058. This line will never be reached.  
   }  
}  
