// Live check of the rare places of completion (0.1.95, COMPLETION_GAPS 3.14), "Exclude from completion" (3.11) and a format that starts
// with a digit. Lines to type on are marked `// TYPE:name`: the caret on the empty line under the marker, type what the comment says,
// compare with EXPECT, undo (Ctrl+Z). Nothing here is code that needs the typed line: everything compiles as it is.
// The csproj part (`$(`, `@(`, `%(`, paths) is tried in debug-playground/Console/Console.csproj, see the README of the playground.

// TYPE:rare-internals-visible-to — on the empty line right under this comment (above `namespace`, where an assembly attribute may stand)
// type `[assembly: InternalsVisibleTo("` and Ctrl+Space. EXPECT: the projects of the solution (`Lib`,
// `Tests`, `Web`, `ShopApi`… as the solution names them), type `Li` narrows to `Lib`; choosing one writes it into the string. NOT: keywords,
// types. Ctrl+Z.

namespace Playground.Editor;

public class RareCompletion
{
    // TYPE:rare-extern-alias — NOT here: `extern alias` stands above the usings, the line is typed on the first empty line of a scratch .cs file.
    // Console.csproj has no `Aliases`, so the list is empty. To see names: add `Aliases="LibAlias"` to the ProjectReference of Lib in
    // Console.csproj (then the code that uses Lib needs `extern alias LibAlias;`, so undo the change after the check), and in a scratch file
    // type `extern alias ` and Ctrl+Space. EXPECT: `LibAlias` (also comma lists: `Aliases="A,B"` gives both; `global` never).

    // TYPE:rare-calling-convention — on the empty line under this comment type `delegate* unmanaged[` and Ctrl+Space. EXPECT: `Cdecl`,
    // `Stdcall`, `Thiscall`, `Fastcall`, `SuppressGCTransition`. After `Cdecl, ` the same list. NOT: types, keywords. Esc, Ctrl+Z.
    // (the `unsafe` modifier is not needed for the list; the line does not have to compile)

    // TYPE:rare-file-package — in a new scratch file (not in the project) type `#:package seri` on the first line and Ctrl+Space.
    // EXPECT: package ids of the NuGet feeds of the solution (`Serilog`, `Serilog.AspNetCore`, …) as in `<PackageReference Include="`, the
    // version of the newest in gray; `#:package Serilog@` lists the versions, the newest first; `#:` lists `package`, `sdk`, `property`,
    // `project`. Offline: the list stays empty, nothing breaks. Delete the scratch file.

    public string FormatDigit(decimal total)
    {
        // TYPE:rare-format-digit — put the caret after the `0` in the string below (`{total:0}`) and press Ctrl+Space; a new `0` typed there does the same. EXPECT: the list of
        // format specifiers stays open for `0`: `0000 - custom`, `0.## - custom`. NOT: an empty
        // list, and no names of the method. Outside of a format a number still gets no list: `int x = 1` + Ctrl+Space shows nothing. Ctrl+Z.
        return $"{total:0}";
    }

    // TYPE:rare-exclude — Settings | .NET → "Exclude from completion:", add the line `System.Text.*`, Apply. On the empty line under this
    // comment type `StringBu`. EXPECT: no `StringBuilder (in System.Text)` row. Remove the line from the setting: the row is back. `System`
    // as a line removes every type of System and the namespaces inside it, `System.Text.StringBuilder` only that type. Ctrl+Z.
}
