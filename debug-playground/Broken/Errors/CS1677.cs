// CS1677: Parameter N should not be declared with the 'K' keyword. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1677;

public delegate void Notify(int code);
public delegate int Combine(int left, int right);

public class Callbacks
{
    public void Listen(Action<string> listener) { }

    public void Use()
    {
        Notify byRef = (ref int code) => { }; // ERROR CS1677
        Notify byValue = (int code) => { };
        Notify byOut = (out int code) => { code = 1; }; // ERROR CS1677
        Notify byIn = (in int code) => { }; // ERROR CS1677
        Combine second = (int left, ref int right) => left; // ERROR CS1677
        Combine both = (int left, int right) => left + right;
        Action<int> act = (ref int x) => { }; // ERROR CS1677
        Action<int> plain = x => { };
        Listen((ref string text) => { }); // ERROR CS1677
        Listen(text => Console.WriteLine(text));
        byValue(1);
        Console.WriteLine(both(1, 2));
        plain(3);
    }
}
