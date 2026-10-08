// docs: using-statement-declaration-errors.md #1; codes: CS0245 CS0728 CS1674 CS8410 CS8417 CS8418 CS8647 CS8648 CS8649 CS9229
using System;

class Program
{
    static void Main()
    {
        // error CS9229: Modifiers cannot be placed on using declarations.
        public using var resource = new Resource();

        // error CS9229: Modifiers cannot be placed on using declarations.
        static using var anotherResource = new Resource();
    }
}

class Resource : IDisposable
{
    public void Dispose() { }
}
