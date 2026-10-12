// docs: cs0019.md #2; codes: CS0019
// CS0019_a.cs
// compile with: /target:library
using System.Diagnostics;

public class MyClass
{
   [ConditionalAttribute("DEBUG" || "TRACE")]   // CS0019
   public void TestMethod() {}

   // OK
   [ConditionalAttribute("DEBUG"), ConditionalAttribute("TRACE")]
   public void TestMethod2() {}
}
