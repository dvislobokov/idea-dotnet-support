// CS1676: Parameter N must be declared with the 'K' keyword. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1676;

public delegate void Increment(ref int value);
public delegate bool TryRead(string text, out int value);
public delegate int Peek(in int value);

public class Counters
{
    public void Visit(Increment step) { }

    public void Use()
    {
        Increment bump = value => { }; // ERROR CS1676
        Increment add = (ref int value) => { value++; };
        Increment typed = (int value) => { }; // ERROR CS1676
        TryRead read = (string text, int value) => true; // ERROR CS1676
        TryRead parse = (string text, out int value) => int.TryParse(text, out value);
        TryRead wrong = (string text, ref int value) => true; // ERROR CS1676
        Peek peek = (int value) => value; // ERROR CS1676
        Peek look = (in int value) => value;
        Peek refPeek = (ref int value) => value; // ERROR CS1676
        Visit(value => { }); // ERROR CS1676
        Visit((ref int value) => { value--; });
        int number = 0;
        bump(ref number);
        add(ref number);
        typed(ref number);
        Console.WriteLine(read("1", out number) && parse("2", out number) && wrong("3", out number));
        Console.WriteLine(peek(number) + look(number) + refPeek(number));
    }
}
