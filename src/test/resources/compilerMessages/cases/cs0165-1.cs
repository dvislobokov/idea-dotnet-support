// docs: cs0165.md #1; codes: CS0165
// CS0165.cs  
using System;  
  
class MyClass  
{  
    public int i;  
}  
  
class MyClass2  
{  
    public static void Main(string[] args)  
    {  
        // i and j are not initialized.  
        int i, j;  
  
        // You can provide a value for args[0] in the 'Command line arguments'  
        // text box on the Debug tab of the project Properties window.  
        if (args[0] == "test")  
        {  
            i = 0;  
        }  
        // If the following else clause is absent, i might not be  
        // initialized.  
        //else  
        //{  
        //    i = 1;  
        //}  
  
        // Because i might not have been initialized, the following
        // line causes CS0165.  
        j = i;  
  
        // To resolve the error, uncomment the else clause of the previous  
        // if statement, or initialize i when you declare it.  
  
        // The following example causes CS0165 because myInstance is  
        // declared but not instantiated.  
        MyClass myInstance;  
        // The following line causes the error.  
        myInstance.i = 0;
  
        // To resolve the error, replace the previous declaration with  
        // the following line.  
        //MyClass myInstance = new MyClass();  
    }  
}  
