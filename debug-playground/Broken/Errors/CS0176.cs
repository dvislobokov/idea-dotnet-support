// CS0176: Member 'M' cannot be accessed with an instance reference; qualify it with a type name instead. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0176;

public class Calculator
{
    public static int Twice(int value) => value * 2;
    public int Add(int a, int b) => a + b;
    public static int Created;
    public const int Max = 100;
    public static string Version { get; } = "1.0";
    public string Name { get; set; } = "calc";
}

public enum Color { Red, Green }

public class Palette
{
    public Color Color { get; set; }
    public Color Pick() => Color.Red;
}

public class Usage
{
    private static int counter;

    public void Run(Calculator calc, string text, Palette palette)
    {
        int a = calc.Twice(2); // ERROR CS0176
        int b = Calculator.Twice(2);
        int c = calc.Add(1, 2);
        int d = calc.Created; // ERROR CS0176
        int e = calc.Max; // ERROR CS0176
        string f = calc.Version; // ERROR CS0176
        string g = calc.Name;
        string h = text.Empty; // ERROR CS0176
        string i = string.Empty;
        int j = this.counter; // ERROR CS0176
        int k = counter;
        Color l = palette.Pick();
        Console.WriteLine($"{a}{b}{c}{d}{e}{f}{g}{h}{i}{j}{k}{l}");
    }
}
