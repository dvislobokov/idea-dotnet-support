// docs: cs0507.md #3; codes: CS0507
// CS0507_c.cs  
// compile with: /reference:cs0507_b.dll  
public class cly : clx  
{  
   protected internal override void f() {}   // CS0507  
   // try the following line instead  
   // protected override void f() {}   // OK  
  
   public static void Main() {}  
}  
