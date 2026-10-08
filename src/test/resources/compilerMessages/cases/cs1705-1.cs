// docs: cs1705.md #1; codes: CS1705
// CS1705a.cs

// Compile by using the following command:
//      csc /target:library /out:C:\\CS1705.dll /keyfile:mykey.snk CS1705a.cs

// The DLL is created in the C:\ directory.

// The AssemblyVersion attribute specifies version 1.0 for this DLL.

[assembly:System.Reflection.AssemblyVersion("1.0")]
public class Class1
{
   public void Method1() {}
}
