// CS0027: Keyword 'this' is not available in the current context. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0027;

public class ContextThis
{
    private int _size = 2;

    public int Doubled = this._size * 2; // ERROR CS0027

    public int Tripled { get; } = this._size * 3; // ERROR CS0027

    public Func<int> Later = () => this._size; // ERROR CS0027

    public ContextThis() : this(this._size) // ERROR CS0027
    {
    }

    public ContextThis(int size) => _size = size;

    public int Size => this._size;

    public int Quadrupled { get; } = 4;

    public ContextThis(string text) : this(text.Length) => Console.WriteLine(this.Size);
}
