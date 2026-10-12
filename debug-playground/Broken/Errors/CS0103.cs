// CS0103: The name 'x' does not exist in the current context. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0103;

public class Names
{
    private int _field;

    public int Use(int parameter)
    {
        var local = parameter + _field;
        return missing + local; // ERROR CS0103
    }

    public void Call()
    {
        Console.WriteLine(Use(1));
        Undefined(); // ERROR CS0103
    }
}
