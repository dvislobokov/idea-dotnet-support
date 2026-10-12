package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.SystemInfo

/**
 * The order in which csc gets the files of a project's default `Compile` glob (every `.cs` under the project): what decides which of two duplicate
 * declarations Roslyn reports (CS0101 on every later one). MSBuild sorts the items of a glob by their whole path relative to the project,
 * ordinal and case-insensitive (.NET's `OrdinalIgnoreCase`: each UTF-16 unit upper-cased), with the separator of the OS in the path; seen with
 * `dotnet build` / `dotnet msbuild -getItem:Compile` (SDK 10) on Windows and Linux: `0dir\n.cs`, `1.cs`, `a-b.cs`, `a.b.cs`, `a.cs`,
 * `aa\t.cs`, `ab.cs`, `a\k.cs`, `a_b.cs`, `B.cs`, `Sub2\x.cs`, `sub\A.cs`, ..., `[x].cs`, `_dir\m.cs`, `~t.cs` on Windows, where `\` sorts
 * after the letters; on Linux `/` sorts before them (`a.cs`, `a/k.cs`, `aa/t.cs`, `sub/...`, `Sub2/x.cs`). A directory is no unit of its own:
 * `sub\A.cs`, `sub\deep\q.cs`, `Sub\m.cs`, `sub\z.cs` interleave. Items written in the project (`<Compile Include>` after a `Remove`, files
 * from outside) come after the glob's in the order written, and `EnableDefaultCompileItems=false` leaves only those: then the evaluated items
 * say it, not this rule. The common directory of both paths does not matter, so full paths compare as well as relative ones.
 */
internal object CSharpCompileOrder {
    /** < 0 when the file at [a] comes before the one at [b] (`/` separated paths), as the default glob of the OS orders them. */
    fun compare(a: String, b: String, separator: Char = if (SystemInfo.isWindows) '\\' else '/'): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val x = unit(a[i], separator)
            val y = unit(b[i], separator)
            if (x != y) return x - y
        }
        return a.length - b.length
    }

    private fun unit(c: Char, separator: Char): Int = (if (c == '/' || c == '\\') separator else c).uppercaseChar().code
}
