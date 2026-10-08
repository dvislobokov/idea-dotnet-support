// docs: cs0108.md #1; codes: CS0108
// CS0108.cs
// compile with: /W:2
using System;

namespace MyNamespace;

public class BaseClass
{
    public int i = 1;
}

public class DerivedClass : BaseClass
{
    public static int i = 2;   // CS0108
    // Use the following line instead:
    // public static new int i = 2;

    public static void Main()
    {
        Console.WriteLine(i);
    }
}
