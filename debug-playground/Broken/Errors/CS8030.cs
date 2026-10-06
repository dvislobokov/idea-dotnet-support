// CS8030: Anonymous function converted to a void returning delegate cannot return a value. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS8030;

public delegate void Handler(int code);

public class Callbacks
{
    private Action? _saved;

    public void Register()
    {
        Action log = () => { return 1; }; // ERROR CS8030
        Action<int> check = x => { if (x > 0) return; return x; }; // ERROR CS8030
        Action old = delegate { return 2; }; // ERROR CS8030
        Action fire = async () => { await Task.Yield(); return 3; }; // ERROR CS8030
        Handler handler = code => { return code; }; // ERROR CS8030
        _saved = () => { return 4; }; // ERROR CS8030
        var cast = (Action)(() => { return 5; }); // ERROR CS8030
        Use(log, check, old, fire, handler, cast);
    }

    // the legal look-alikes: a bare return, a delegate that returns a value, a nested function with its own return type
    public void Ok()
    {
        Action stop = () => { return; };
        Func<int> one = () => { return 1; };
        Action outer = () => { Func<int> inner = () => { return 2; }; inner(); };
        Use(stop, outer);
        one();
    }

    private static void Use(params object[] items) { }
}
