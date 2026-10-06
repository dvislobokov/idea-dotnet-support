namespace Playground.Editor;

/// <summary>
/// Live check of the colored matching brackets (Settings | .NET, "Colorize matching brackets", on by default): pairs of (), [], {} and the
/// &lt;&gt; of type lists by depth, three colors cycling (Darcula: gold, orchid, sky blue; light schemes: blue, green, brown — the defaults of
/// VS Code). Nothing is typed under most markers; where something is, undo it with Ctrl+Z.
/// </summary>
public static class BracketColors
{
    // TYPE:brackets-nesting — look only. EXPECT: the `{` `}` of the class are level 1, of the method level 2; in the statement below the
    // `(` `)` of `Range(0, 3)`, of `Select(…)` and of `ToArray()` are level 3; inside Select the empty `[` `]` of `new[]` and the `{` `}`
    // of the initializer after them are both level 1 again (the same depth, one after the other), and `(i + 1)` inside the braces is
    // level 2: a pair always has one color at both ends and the colors go deeper with every nesting, three colors cycling. The daemon and
    // the opening colors paint the same (close the tab and open it again: colored at once, no later repaint).
    public static int[][] Nesting()
    {
        return Enumerable.Range(0, 3).Select(i => new[] { (i + 1) * 2 }).ToArray();
    }

    // TYPE:brackets-generics — look only. EXPECT: `<` `>` of `Dictionary<string, List<int>>` and of `Compute<T>` are colored as brackets
    // (`<` of Dictionary and the last `>` the same color, the inner pair the next), while `<` and `>` of `a < b`, `b > 1` and `=>` keep the
    // plain operator color.
    public static Dictionary<string, List<int>> Generics<T>(int a, int b)
    {
        var map = new Dictionary<string, List<int>>();
        if (a < b && b > 1) map["pair"] = [a, b];
        return map;
    }

    // TYPE:brackets-strings — look only. EXPECT: the brackets inside the strings, the char and the comments are plain string / comment
    // color; in the interpolated string the `(` `)` of the hole are colored (level 3), its `{` `}` are not.
    public static string Strings(int count)
    {
        const string text = "{[( not brackets )]}"; // ( [ { not brackets either
        /* ( [ { */
        var c = '(';
        return $"{(count + 1)} items of {text}{c}";
    }

    // TYPE:brackets-mismatch — on the empty line below type `(` only (nothing else). EXPECT: the typed `(` stays uncolored (plain color),
    // and the `{` `}` of this method and of the next one keep their colors — nothing after it shifts. Ctrl+Z.
    public static void Mismatch()
    {

    }

    // TYPE:brackets-inactive — look only. EXPECT: the brackets of the `#if NEVER` branch are gray with the branch, not colored; the ones of
    // the active branch are.
    public static int Inactive()
    {
#if NEVER
        return (((1)));
#else
        return ((2));
#endif
    }

    // TYPE:brackets-off — Settings | .NET, untick "Colorize matching brackets", Apply. EXPECT: all brackets of the open file in the plain
    // colors of the scheme at once, without closing the file; tick it back — colored again. In Settings | Editor | Color Scheme | C# the
    // three levels are under Braces and operators | Matching brackets, and the preview shows them on the `Enumerable.Range(…)` line.
    public static void Off() { }
}
