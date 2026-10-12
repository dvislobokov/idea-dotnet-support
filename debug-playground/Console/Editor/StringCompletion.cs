using System;
using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Playground.Editor;

/// <summary>
/// Live check of completion inside strings (0.1.90): names and members in the holes of interpolated strings, format specifiers by the type
/// of the value, regular expressions injected into the patterns of <see cref="Regex"/>. Server off (Settings | .NET | Language Server), the
/// assemblies indexed. Type on the empty line under a marker (Ctrl+Space where the marker says so) and undo with Ctrl+Z. Compiles as it is.
/// </summary>
public static partial class StringCompletion
{
    public enum Shade { Light, Dark }

    public sealed record Ticket(int Id, string Title, decimal Price, DateTime Opened, TimeSpan Spent, Guid Key, Shade Shade);

    public static void Run()
    {
        var ticket = new Ticket(1, "Printer", 12.5m, DateTime.Now, TimeSpan.FromMinutes(90), Guid.NewGuid(), Shade.Dark);
        var count = 3;
        var builder = new StringBuilder();

        // TYPE:string-hole — type `var a = $"{tic` → the list opens by itself with `ticket` first; Enter, then `.` → the list of `Title`, `Price`,
        // `Opened`… opens by itself; `Ti` + Enter → `$"{ticket.Title`. EXPECT: no list while typing the text of the string (`$"abc `).

        // TYPE:string-hole-kinds — type `var b = $@"{ticket.` and `var c = $$"""{{ticket.` (close the string after) → the same members in both.
        // EXPECT: inside `"plain text ` (no hole) Ctrl+Space shows nothing.

        // TYPE:format-number — type `var d = $"{ticket.Price:` and press Ctrl+Space. EXPECT (as Rider): `0000 - custom 0123`, `C - currency ¤1,234.45`,
        // `C0`, `E`, `e2`, `E2`, `F`, `F1`, `G`, `g2`, `N`, `N0`, `N1`, `N2`, `P`, `P1` with examples on the right; `N2` + Enter → `{ticket.Price:N2`.
        // With `{count:` there are `D`, `X` too; with `{ticket.Title:` nothing (a string has no formats).

        // TYPE:format-date — type `var e = $"{ticket.Opened:yyyy-` + Ctrl+Space. EXPECT: `yyyy-MM-dd - custom 2009-06-15`, `yyyy-MM-dd HH:mm:ss`;
        // with an empty format: `d - short date`, `D`, `t`, `T`, `o`, `s`, `u`, `HH:mm:ss`, no `N2`.

        // TYPE:format-calls — Ctrl+Space after `{1:` in `Console.WriteLine("{0} {1:", count, ticket.Opened);`, inside `ticket.Spent.ToString("")`,
        // and after `{0:` of `builder.AppendFormat("{0:", ticket.Key)`. EXPECT: dates; `c`, `g`, `hh\:mm\:ss` (inserted as `hh\\:mm\\:ss`);
        // `N`, `D`, `B`, `P` of a Guid. `{{0:` (escaped braces) — nothing.

        // TYPE:format-enum — type `var f = $"{ticket.Shade:` + Ctrl+Space. EXPECT: `G - name`, `F - flags`, `D - decimal`, `X - hexadecimal`.

        var stamp = $"{ticket.Opened:yyyy-MM-dd} {ticket.Price:N2} {count:D3}";
        builder.AppendFormat(CultureInfo.InvariantCulture, "{0:N2}", ticket.Price);
        Console.WriteLine(stamp + builder);
    }

    // TYPE:regex-colors — look at the patterns below. EXPECT: the regular expressions are colored as RegExp (classes `\d`, groups, quantifiers,
    // a light background of an injected fragment); `(?<year>` and `(?'month'` are no errors; `"plain(text"` of Console.WriteLine is a plain
    // string. Alt+Enter on a pattern offers "Check RegExp"; the colors of `\t` escapes and of the string around stay as before.
    public static bool Patterns(string input)
    {
        var date = new Regex(@"(?<year>\d{4})-(?'month'\d{2})-(?<day>\d{2})");
        var escaped = new Regex("\\w+\\s*=\\s*\\d+");
        var raw = new Regex("""
            ^(?<key>[a-z]+)
            \s*:\s*(?<value>.+)$
            """, RegexOptions.IgnorePatternWhitespace);
        Console.WriteLine("plain(text");
        return date.IsMatch(input) || escaped.IsMatch(input) || raw.IsMatch(input) || Regex.IsMatch(input, "^[A-Z]{3}$") || Words().IsMatch(input) || Check(@"x+y", input);
    }

    // TYPE:regex-completion — put the caret after `\` in the pattern of `Typed` (or type `\` there) and press Ctrl+Space. EXPECT: the list of
    // RegExp — `\d`, `\w`, `\s`, `\b`, `\p{…}`… with descriptions; after `(?` — `(?<name>`, `(?:`, lookarounds.
    public static Regex Typed() => new(@"[a-z]+");

    // TYPE:regex-generated — EXPECT: the pattern of [GeneratedRegex], the string after `// lang=regex` and the argument of `Check` (its parameter is
    // [StringSyntax(StringSyntaxAttribute.Regex)]) are colored as RegExp; `Plain` is not.
    [GeneratedRegex("^[a-z]+(?:-[a-z]+)*$", RegexOptions.IgnoreCase)]
    private static partial Regex Words();

    // lang=regex
    private const string Marked = @"\b\w+@\w+\.com\b";
    private const string Plain = "not (a regex";

    private static bool Check([StringSyntax(StringSyntaxAttribute.Regex)] string pattern, string input) => Regex.IsMatch(input, pattern + Marked + Plain.Length);
}
