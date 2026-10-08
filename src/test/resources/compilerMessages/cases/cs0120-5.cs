// docs: cs0120.md #5; codes: CS0120
// CS0120_3.cs
using System;

public class MyClass
{
    public static void Main()
    {
        DoIt("Hello There");   // CS0120
    }

    private void DoIt(string sText)
    {
        Console.WriteLine(sText);
    }
}
