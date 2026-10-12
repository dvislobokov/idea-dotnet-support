// docs: cs1705.md #4; codes: CS1705
// CS1705d.cs

// Compile by using the following command:
//     csc /reference:C:\\CS1705.dll /reference:CS1705c.dll CS1705d.cs

// C:\\CS1705.dll is version 1.0 of the assembly.

class Tester
{
    static void Main()
    {
        // Return1A returns a type defined in version 1.0.
        ClassC.Return1A().Method1();
        // Return1B returns a type defined in version 2.0.
        ClassC.Return1B().Method1();
    }
}
