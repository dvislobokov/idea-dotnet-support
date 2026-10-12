namespace Playground.Editor;

// Navigation inside decompiled code (0.1.105): a decompiled type resolves its names against a project that refers to its assembly,
// so Ctrl+Click goes on from it into the next decompiled type. The file compiles as it is.
public static class DecompiledNavigation
{
    // TYPE:decompiled-ctrl-click — Ctrl+Click `StringBuilder` below: the decompiled `StringBuilder.cs` opens. In it Ctrl+Click a type
    //   it uses (`ArgumentOutOfRangeException`, `Span<T>`, `String`) and a call of another type (`string.Concat`, `Math.Max`).
    // EXPECT: each Ctrl+Click opens the next decompiled type at its declaration (the first time maybe the metadata view while it is
    //   decompiled behind it — Ctrl+Click again); names of the same type go to the member in the same tab.
    // EXPECT (not): nothing happening on Ctrl+Click in a decompiled tab, red errors in it.
    public static string Build() => new System.Text.StringBuilder().Append("a").ToString();
}
