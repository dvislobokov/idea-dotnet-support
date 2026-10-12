// docs: cs0201.md #2; codes: CS0201
// CS0201_b.cs  
// compile with: /target:library  
public class MyList<T>
{  
   public void Add(T x)  
   {  
      int i = 0;  
      if ( (object)x == null)  
      {  
         checked(i++);   // CS0201  
  
         // OK  
         checked {  
            i++;
         }  
      }  
   }  
}  
