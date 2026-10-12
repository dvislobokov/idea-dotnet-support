// docs: cs1729.md #1; codes: CS1729
// cs1729.cs  
class Test  
{  
    static int Main()  
    {  
        // Class Test has only a parameterless constructor, which takes no arguments.  
        Test test1 = new Test(2); // CS1729  
        // The following line resolves the error.  
        Test test2 = new Test();  
  
        // Class Parent has only one constructor, which takes two int parameters.  
        Parent exampleParent1 = new Parent(10); // CS1729  
        // The following line resolves the error.  
        Parent exampleParent2 = new Parent(10, 1);  
  
        return 1;  
    }  
}  
  
public class Parent  
{  
    // The only constructor for this class has two parameters.  
    public Parent(int i, int j) { }  
}  
  
// The following declaration causes a compiler error because class Parent  
// does not have a constructor that takes no arguments. The declaration of  
// class Child2 shows how to resolve this error.  
public class Child : Parent { } // CS1729  
  
public class Child2 : Parent  
{  
    // The constructor for Child2 has only one parameter. To access the
    // constructor in Parent, and prevent this compiler error, you must provide
    // a value for the second parameter of Parent. The following example provides 0.  
    public Child2(int k)  
        : base(k, 0)  
    {  
        // Add the body of the constructor here.  
    }  
}  
