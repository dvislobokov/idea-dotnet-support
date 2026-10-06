// CS0709: 'C': cannot derive from static class 'S'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0709;

public static class Tools
{
    public static int Twice(int value) => value * 2;
}

public class Helpers
{
    public static int Twice(int value) => value * 2;
}

public abstract class Component { }

public class MoreTools : Tools { } // ERROR CS0709
public class MoreHelpers : Helpers { }
public class Output : Console { } // ERROR CS0709
public class Calculator : Math { } // ERROR CS0709
public class Button : Component { }
public class Widget : Component, IDisposable
{
    public void Dispose() { }
}
