// docs: cs0165.md #2; codes: CS0165
class Program  
{  
    delegate void Del();  
    static void Main(string[] args)  
    {  
        // The following line causes CS0165 because variable d is used
        // as an argument before it has been initialized.  
        Del d = delegate() { System.Console.WriteLine(d); };
  
        //// To resolve the error, initialize d in a separate statement.  
        //Del d = null;  
        //// After d is initialized, you can use it as an argument.  
        //d = delegate() { System.Console.WriteLine(d); };  
        //d();  
    }  
}  
