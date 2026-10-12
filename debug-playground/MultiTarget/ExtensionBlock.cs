// Extension methods into a C# 14 `extension` block (0.1.173): the action needs the file's language version to be C# 14, the default of
// net10.0 — choose Debug | .NET 10.0 in the toolbar first (on .NET 9.0, C# 13, the actions are not offered).
namespace MultiTarget;

// TYPE:ext-block-all — caret on `Words` below (the name of the method), Alt+Enter. EXPECT: "Convert to extension block (3 methods)" and
// "Convert this method to extension block"; the first one writes `extension(string text) { … }` with Words, IsBlank and Left (no `static`,
// no `this string text` parameter), Twice stays as it is. Ctrl+Z. With .NET 9.0 in the toolbar: neither row.
// TYPE:ext-block-one — caret on `Twice`, Alt+Enter. EXPECT: only "Convert this method to extension block"; it writes
// `extension(int value) { public int Twice() => value * 2; }`. Ctrl+Z.
public static class ExtensionBlockSamples
{
    /// <summary>The number of words.</summary>
    public static int Words(this string text) => text.Split(' ').Length;

    public static bool IsBlank(this string text)
    {
        return text.Trim().Length == 0;
    }

    public static int Twice(this int value) => value * 2;

    public static string Left(this string text, int count) => text.Substring(0, count);
}
