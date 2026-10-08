// docs: cs0570.md #1; codes: CS0570
// CS0570.cs  
// compile with: /reference:CPP0570.dll  
using System;  
using CS0570_Server;  
  
public class C {  
   public static int Main() {  
      CS0570Class r = new CS0570Class();  
      r.sc1_field = null;   // CS0570  
      object o = r.sc1_prop;   // CS0570  
      r.sc1_method();   // CS0570  
   }  
}  
