// docs: cs1701.md #3; codes: CS1701
// CS1701_c.cs  
// compile with: /target:library /reference:A2=c:\\cs1701_d.dll /reference:A1=cs1701_d.dll  
  
extern alias A1;  
extern alias A2;  
// using System;  
using a1 = A1::A;  
using a2 = A2::A;  
  
public class Ref {  
   public static a1 A1() { return new a1(); }  
   public static a2 A2() { return new a2(); }  
  
   public static A1::C1 M1() { return new A1::C1(); }  
   public static A2::C2 M2() { return new A2::C2(); }  
}  
