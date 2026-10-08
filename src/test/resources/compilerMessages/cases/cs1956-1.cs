// docs: cs1956.md #1; codes: CS1956
// cs1956.cs  
class Base<T, S>  
{  
    // This is the method that should be called.  
    public virtual int Test(out T x) // CS1956  
    {  
        x = default(T);  
        return 0;  
    }  
  
    // This is the "last" method and is the one that ends up being called  
    public virtual int Test(ref S x)  
    {  
        return 1;  
    }  
}  
  
interface IFace  
{  
    int Test(out int x);  
}  
  
class Derived : Base<int, int>, IFace  
{  
    static int Main()  
    {  
        IFace x = new Derived();  
        int y;  
        return x.Test(out y);  
    }  
}  
