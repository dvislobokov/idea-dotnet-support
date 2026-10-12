// CS0712: Cannot create an instance of the static class 'T'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0712;

public static class Helpers
{
    public static int Twice(int value) => value * 2;
}

public class Tools
{
    public static int Half(int value) => value / 2;
}

public class Usage
{
    public void Run()
    {
        object helpers = new Helpers(); // ERROR CS0712
        object tools = new Tools();
        object math = new Math(); // ERROR CS0712
        object console = new Console(); // ERROR CS0712
        object random = new Random();
        Console.WriteLine($"{helpers}{tools}{math}{console}{random}{Helpers.Twice(2)}{Tools.Half(4)}");
    }
}
