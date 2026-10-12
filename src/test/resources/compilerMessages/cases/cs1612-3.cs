// docs: cs1612.md #3; codes: CS1612
// CS1612.cs  
using System;  
  
public struct MyStruct  
{  
    public int Width;  
}  
  
public class ListView  
{  
    public MyStruct Size { get; set; }
}  
  
public class MyClass  
{  
    public MyClass()  
    {  
        ListView lvi;  
        lvi = new ListView();  
        lvi.Size.Width = 5; // CS1612  
  
        // You can use the following lines instead.  
        // MyStruct ms;  
        // ms.Width = 5;  
        // lvi.Size = ms;
    }  
  
    public static void Main()
    {  
        MyClass mc = new MyClass();  
        // Keep the console open in debug mode.  
        Console.WriteLine("Press any key to exit.");  
        Console.ReadKey();
    }  
}  
