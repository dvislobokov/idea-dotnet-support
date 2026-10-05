using System.Text;
using static Playground.Editor.ColorRegistry;

namespace Playground.Editor;

/// <summary>
/// Live check of the colors of identifiers (Settings | Editor | Color Scheme | C#, the keys of Rider). Nothing is typed here: look at the names
/// a <c>// TYPE:colors-*</c> comment points at and compare with EXPECT (colors of Darcula / Islands Dark; the light scheme has darker ones). Two modes:
/// Settings | Tools | .NET | Language Server → Source of Features → «Colors of identifiers» = Built-in (the plugin's own tree and stubs) or
/// Language server (semantic tokens of the server). Since 0.1.56 Built-in also colors types and members of the referenced assemblies
/// (`Console`, `WriteLine`; more in LibraryNames.cs); a name it cannot resolve where only a type can stand gets the plain type color. The robot prints the keys:
/// <c>tools/ui-robot/scripts/highlight_keys.js</c>. The other part of the partial class and the base class are in SemanticColorsPart.cs.
/// </summary>
public partial class ColorOrder(string customer) : ColorBase, IColorShape
{
    // TYPE:colors-declarations — EXPECT: `ColorOrder` class color, `customer` (above, primary constructor parameter) plain like a parameter,
    // `ColorBase` class, `IColorShape` interface; `MaxLines` cyan BOLD (constant), `_created` and `_lines` cyan (static field / field),
    // `Title` and `Empty` cyan (property / static property), `Changed` pink (event), `Area` / `Describe` green (method declarations),
    // `Create` green (static method), `ColorKind` / `ColorPoint` / `ColorSize` / `ColorHandler` lighter violet (enum, record struct,
    // delegate) in Darcula, `ColorLine` violet (record); `Small` / `Large` cyan bold (enum members)
    private const int MaxLines = 10;
    private static int _created;
    private readonly List<ColorLine> _lines = [];

    public string Title { get; set; } = "";
    public static ColorOrder Empty { get; } = new("nobody");
    public event Action? Changed;

    public double Area() => _lines.Count;

    public static ColorOrder Create(string who) => new(who);

    public string Describe<TFormat>(TFormat format, int repeat)
    {
        // TYPE:colors-locals — EXPECT: `builder` plain (local), `total` UNDERLINED at every occurrence (written again: `+=`, `++`),
        // `repeat` and `format` plain (parameters), `TFormat` violet (type parameter), `Indent` green (local function, also above its
        // declaration), `line` plain (foreach variable), `again` (label) plain; Built-in = Language server here
        var builder = new StringBuilder();
        int total = 0;
        foreach (var line in _lines) total += line.Qty;
        total++;
        Indent(builder);
    again:
        if (total < 0) goto again;
        return builder.Append(format).Append(repeat).Append(total).ToString();

        void Indent(StringBuilder target) => target.Append(' ', repeat);
    }

    public void Touch()
    {
        // TYPE:colors-members — EXPECT: `_created` / `Tick` / `_ticks` (the last two from ColorBase in the other file), `Reset` and `Count`
        // (the other part of the partial class ColorRegistry, through `using static`), `Changed` pink, `Title` cyan, `ColorKind.Small`:
        // violet + cyan bold; `this.Title` cyan; `customer` plain (primary constructor parameter) — all colored also in Built-in, where
        // `Console` (class) and `WriteLine` (static method) colored too since 0.1.56 (the plugin indexes the referenced assemblies)
        _created++;
        Tick();
        _ticks += 1;
        Reset();
        Count = 0;
        Changed?.Invoke();
        this.Title = customer + ColorKind.Small;
        Console.WriteLine(Title);
    }

    public void Shadowing(int count)
    {
        // TYPE:colors-shadowing — EXPECT: the lambda's own `count` and the outer parameter `count` both plain (parameters), `Count` of
        // ColorRegistry cyan (static field), the local `title` plain and NOT the property `Title`
        Func<int, int> twice = count => count * 2;
        var title = Title + twice(count) + Count;
        Console.WriteLine(title);
    }
}

public enum ColorKind { Small, Large }
public record ColorLine(int Qty);
public record struct ColorPoint(int X, int Y);
public delegate void ColorHandler(object sender);
public interface IColorShape { double Area(); }
