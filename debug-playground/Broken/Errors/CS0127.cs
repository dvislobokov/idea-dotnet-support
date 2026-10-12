// CS0127: Since 'C.M()' returns void, a return keyword must not be followed by an object expression. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0127;

public class VoidReturns
{
    private int _total;

    public void Add(int value)
    {
        _total += value;
        return _total; // ERROR CS0127
    }

    public void Reset(List<int> values, ref int count)
    {
        values.Clear();
        count = 0;
        return true; // ERROR CS0127
    }

    public int Total
    {
        get { return _total; }
        set { return value; } // ERROR CS0127
    }

    public VoidReturns()
    {
        return 0; // ERROR CS0127
    }

    public async void Fire()
    {
        await Task.Delay(1);
        return 1; // ERROR CS0127
    }

    public int Run()
    {
        void Log(string text)
        {
            Console.WriteLine(text);
            return text; // ERROR CS0127
        }
        Log("run");
        return _total;
    }

    // the legal look-alikes
    public int Get() { return _total; }
    public void Stop() { return; }
    public Func<int> Getter() { return () => _total; }
    public Action Printer() { return () => { Console.WriteLine(_total); return; }; }
    public static void Generic<T>(T item) { Func<T> f = () => { return item; }; f(); }
}

// nullable annotations of the parameters are part of the method's name in the message
public class Annotated
{
    public void Log(string? message, List<string?> tags) { return 1; } // ERROR CS0127
    public void Ok(string? message) { if (message == null) return; }
}
