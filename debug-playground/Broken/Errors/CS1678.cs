// CS1678: Parameter N is declared as type 'A' but should be 'B'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1678;

public class Invoice { }

public class Callbacks
{
    public void Use()
    {
        Func<Invoice, string, int> total = (Invoice invoice,
            object note) => 1; // ERROR CS1678 CS1661
        Func<Invoice, string, int> count = (Invoice invoice,
            string note) => note.Length;
        Action<int> print = (long value // ERROR CS1678
            ) => Console.WriteLine(value); // ERROR CS1661
        Action<int> show = (int value) => Console.WriteLine(value);
        Console.WriteLine(total(new Invoice(), "a") + count(new Invoice(), "b"));
        print(1);
        show(2);
    }
}
