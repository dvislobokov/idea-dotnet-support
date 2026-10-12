using System.Globalization;
using System.Text;

namespace Playground.Editor;

/// <summary>
/// Live check of the colors inside strings (0.1.71), as in Rider: the code of a hole of an interpolated string in the colors of code, escapes
/// in two alternating colors, alignment and format of a hole and the items of <c>string.Format</c> in the color of a format item. Mostly
/// nothing is typed: look at what a <c>// TYPE:strings-*</c> comment points at and compare with EXPECT (colors of Darcula / Islands Dark:
/// string brown, escape pink-violet, the second escape cyan, format item violet; the light scheme has darker ones). Where a comment says
/// to type, type on the empty line under it and undo with Ctrl+Z. The robot prints the keys: <c>tools/ui-robot/scripts/highlight_keys.js</c>
/// (CSHARP_ESCAPE_CHARACTER_1 / _2, CSHARP_INVALID_ESCAPE_CHARACTER, CSHARP_FORMAT_STRING_ITEM / _2, CSHARP_BRACES of a hole).
/// </summary>
public class StringColors
{
    public string Holes(int x, string name, DateTime date)
    {
        // TYPE:strings-holes — EXPECT: inside the holes `+` and `>` / `?` / `:` in the operator color, `1` and `0` in the number color,
        // `null` a keyword, `"yes"` a string of its own, `{` `}` of each hole in the color of braces; `x`, `name`, `Length` in their colors
        // (parameter, property); ` of ` and `Order ` stay string-colored. Not expected: everything between the quotes brown (before 0.1.71)
        var order = $"Order {x + 1} of {name.Length} {(x > 0 ? "yes" : null)}";

        // TYPE:strings-format — EXPECT: `,5`, `:D`, `,-12:N2` and `:yyyy-MM-dd` in the format item color (violet), the names before them as code
        var row = $"{x,5:D} {name.Length,-12:N2} {date:yyyy-MM-dd}";
        return order + row;
    }

    public string Escapes()
    {
        // TYPE:strings-escapes — EXPECT: `\t` pink-violet; in `\n\r\n\\` the escapes side by side alternate (pink-violet, cyan, pink-violet, cyan),
        // `\u0041` after the space pink-violet again; in the verbatim string `""` is an escape and `\d` plain text; `'\n'` an escape in a char
        var tabs = "a\tb\n\r\n\\ \u0041";
        var path = @"C:\dir ""quoted"" \d";
        var newline = '\n';

        // TYPE:strings-invalid-escape — type `\q` between the two spaces of `"bad  escape"` below. EXPECT: `\q` in the invalid escape color
        // (red wavy underline in Darcula), the text around it still string-colored; `\x41` typed instead is a valid escape
        var invalid = "bad  escape";
        return tabs + invalid + path + newline;
    }

    public string BraceEscapes(int count)
    {
        // TYPE:strings-brace-escapes — EXPECT: `{{` and `}}` in the escape colors (not a hole), `count` as code
        var json = $"{{ \"count\": {count} }}";

        // TYPE:strings-raw — EXPECT: in the `$$` raw string `{{count}}` is a hole (braces in the brace color, `count` as code), the single
        // `{` and `}` around it are text; no escapes in raw strings: `\n` stays text
        var raw = $$"""{ "count": {{count}}, "text": "\n" }""";
        return json + raw;
    }

    public string FormatItems(double total, StringBuilder builder)
    {
        // TYPE:strings-format-items — EXPECT: `{0}`, `{1,8:N2}` and `{0:D}` in the format item color, the text between them brown;
        // `{{literal}}` is no item. Not expected: the color in `Console.WriteLine("{0}")` without arguments (printed as is)
        var formatted = string.Format("Total {0} = {1,8:N2} {{literal}}", "sum", total);
        Console.WriteLine("Count {0:D}", 42);
        builder.AppendFormat(CultureInfo.InvariantCulture, "{0}{1}", 1, 2);
        Console.WriteLine("{0}");

        // TYPE:strings-typing — type `var t = $"{` then `total`, `}`, `\t"` on the empty line below. EXPECT: `total` takes the code color
        // as soon as it is typed, `\t` the escape color; the closing quote typed last is stepped over (one `"` at the end, not two)

        return formatted;
    }
}
