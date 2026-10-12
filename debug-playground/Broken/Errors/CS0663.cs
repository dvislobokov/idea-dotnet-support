// CS0663: 'T' cannot define an overloaded method that differs only on parameter modifiers 'out' and 'ref'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0663;

public class Parser
{
    public bool TryRead(string text, ref int value) => int.TryParse(text, out value);
    public bool TryRead(string text, out int value) => int.TryParse(text, out value); // ERROR CS0663
    public bool TryRead(string text, int value) => value > 0 && text.Length > 0;
    public bool TryRead(string text, ref long value) => long.TryParse(text, out value);

    public void Swap(ref int left, ref int right) => (left, right) = (right, left);
    public void Swap(in int left, in int right) { } // ERROR CS0663
    public void Swap(ref long left, ref long right) => (left, right) = (right, left);

    public void Measure(in double size) { }
    public void Measure(ref readonly double size) { } // ERROR CS0663
    public void Measure(double size) { }

    public void Fill<T>(ref T target) { }
    public void Fill<U>(out U target) => target = default!; // ERROR CS0663
    public void Fill<T>(T[] targets) { }

    public void Pair(ref int a, out int b) => b = a;
    public void Pair(int a, ref int b) { }
    public void Pair(out int a, ref int b) => a = b; // ERROR CS0663
}

public class Buffer
{
    public Buffer(ref int size) { }
    public Buffer(in int capacity) { } // ERROR CS0663
    public Buffer(int count) { }
    public Buffer(ref long size) { }
}
