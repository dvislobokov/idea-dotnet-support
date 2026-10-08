// docs: cs0269.md #2; codes: CS0269
// CS0269b.cs  
class C  
{  
    public static void F(out int i)  
    {  
        try  
        {  
            // Assignment occurs, but compiler can't verify it  
            i = 1;  
        }  
        catch  
        {  
        }  
  
        int k = i;  // CS0269  
        i = 1;  
    }  
  
    public static void Main()  
    {  
        int myInt;  
        F(out myInt);  
    }  
}  
