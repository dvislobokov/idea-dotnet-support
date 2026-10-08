// docs: cs1658.md #1; codes: CS1037 CS1584 CS1658
// CS1658.cs  
// compile with: /doc:x.xml  
// CS1584 expected  
/// <summary>  
/// </summary>  
public class C  
{  
    /// <see cref="C.F(params object[])"/>  // CS1658  
    public static void M()  
    {  
    }  
  
    /// <summary>  
    /// </summary>  
    public void F(params object[] o)  
    {  
    }  
  
    static void Main()  
    {  
    }  
}  
