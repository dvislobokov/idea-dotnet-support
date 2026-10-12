// docs: cs0229.md #1; codes: CS0229
// CS0229.cs  
  
interface IList  
{  
    int Count  
    {  
        get;  
        set;  
    }  
  
    void Counter();  
}  
  
interface ICounter  
{  
    double Count  
    {  
        get;  
        set;  
    }  
}  
  
interface IListCounter : IList, ICounter {}  
  
class MyClass  
{  
    void Test(IListCounter x)  
    {  
        x.Count = 1;  // CS0229  
        // Try one of the following lines instead:  
        // ((IList)x).Count = 1;  
        // or  
        // ((ICounter)x).Count = 1;  
    }  
  
    public static void Main() {}  
}  
