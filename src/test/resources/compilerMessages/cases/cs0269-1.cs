// docs: cs0269.md #1; codes: CS0269
// CS0269.cs  
class C  
{  
    public static void F(out int i)  
    // One way to resolve the error is to use a ref parameter instead  
    // of an out parameter.  
    //public static void F(ref int i)  
    {  
        // The following line causes a compiler error because no value  
        // has been assigned to i.  
        int k = i;  // CS0269  
        i = 1;  
        // The error does not occur if the order of the two previous
        // lines is reversed.  
    }  
  
    public static void Main()  
    {  
        int myInt = 1;  
        F(out myInt);  
        // If the declaration of method F is changed to require a ref  
        // parameter, ref must be specified in the call as well.  
        //F(ref myInt);  
    }  
}  
