// docs: cs0445.md #1; codes: CS0445 CS0455
// CS0445.CS  
class UnboxingTest  
{  
    public static void Main()  
    {  
        Point p;  
        p.x = 1;  
        p.y = 2;  
        object obj = p;  
        // The following line generates CS0445, because the result  
        // of unboxing obj is a temporary variable.  
        ((Point)obj).x = 2;  
  
        // The following lines resolve the error.  
  
        // Store the result of the unboxing conversion in p2.  
        Point p2;
        p2 = (Point)obj;  
        // Then you can modify the unboxed value.  
        p2.x = 2;  
    }  
}  
  
struct Point  
{  
    public int x, y;  
}  
