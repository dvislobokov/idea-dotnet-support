// docs: cs1705.md #3; codes: CS1705
// CS1705c.cs

// Compile by using the following command. AssemblyA refers to the DLL created by
// CS1705a.cs (version 1.0). AssemblyB refers to the DLL created by CS1705b.cs
// (version 2.0).
//    csc /target:library /r:AssemblyA=C:\\CS1705.dll /r:AssemblyB=CS1705.dll CS1705c.cs

extern alias AssemblyA;
extern alias AssemblyB;

// Class1A is an alias for type Class1 from VS1705a.cs, which is in version 1.0
// of the assembly. Class1B is an alias for type Class1 from CS1705b.cs, which
// is in version 2.0 of the assembly.

using Class1A = AssemblyA::Class1;
using Class1B = AssemblyB::Class1;

// Method Return1A in ClassC returns an object of type Class1A, which is
// Class1 from version 1.0 of the DLL. Method Return1B returns an object
// of type Class1B, which is Class1 from version 2.0 of the DLL.

public class ClassC
{
    // The following line creates a dependency on version 1.0 of CS1705.dll.
    // This is not the source of the problem when ClassC is accessed from
    // CS1705d.cs because CS1705d.cs references version 1.0 of the DLL.
    // Therefore, type Class1A and the assembly have the same version.
    public static Class1A Return1A() { return new Class1A(); }

    // The following line creates a dependency on version 2.0 of CS1705.dll.
    // This causes compiler error CS1705 when ClassC is accessed from
    // CS1705d.cs, because CS1705d.cs does not reference version 2.0 of the
    // DLL. Class1B is the alias for Class1 in version 2.0, and CS1705d.cs
    // references version 1.0.
    public static Class1B Return1B() { return new Class1B(); }
}
