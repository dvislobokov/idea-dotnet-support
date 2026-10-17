# razordump

The Razor compiler's view of `.razor` / `.cshtml` files: the oracle the gates of razor-psi diff against (the role
`tools/csharp-psi/roslyndump` plays for csharp-psi). The compiler is the one of the installed SDK —
`Microsoft.CodeAnalysis.Razor.Compiler.dll` of the Razor SDK's source generator (`sdk/<version>/Sdks/Microsoft.NET.Sdk.Razor/source-generators/`),
the same code that compiles the user's project; its internals are reached by reflection (the dll is ReadyToRun, so
`IgnoresAccessChecksToGenerator` cannot rewrite it). The Roslyn package must be the version that compiler was built against
(SDK 10.0.401: `Microsoft.CodeAnalysis.CSharp` 5.9.0).

    dotnet build tools/razor/razordump -c Release
    dotnet tools/razor/razordump/bin/Release/net10.0/RazorDump.dll <command> ...

| Command | What it does |
|---|---|
| `tree <file or dir> [--native-tokenizer] [--no-edit-handlers] [--langversion V] [--crlf] [--include P;Q\|@file] [--out file]` | The syntax tree and the diagnostics of every `.razor` / `.cshtml` (`bin`, `obj`, `node_modules`, `.git` skipped), ordinal order of relative paths |
| `cases <ParserTests dir> [same options] [--out file]` | The trees of the compiler's own parser tests: the input of a case is the first line of its `.stree.txt` (`LF` back to CRLF, as the tests wrote them) |

The engine is configured as the SDK's source generator configures it (`RazorSourceGenerator.Helpers.cs`): the compiler's features,
the MVC extension (`inject`, `model`, `page`, `section` for `.cshtml`), the file kind by the file name (`_Imports.razor`, `.razor`,
else legacy), C# `preview`. Options:

- `--native-tokenizer`: the compiler's own C# tokenizer instead of Roslyn's (`use-roslyn-tokenizer`, the SDK's default since .NET 10;
  the compiler's parser test baselines under `legacyTest` were written with the native one, those under `test` with Roslyn's);
- `--no-edit-handlers`: no `SpanEditHandler;Accepts:…` after the spans (the tooling's view; on by default, as in the baselines);
- `--langversion`: a Razor language version as `RazorLangVersion` takes it (`3.0`, `8.0`, `10.0` — the SDK's for net10.0 —, `latest`,
  `experimental`); default `latest`;
- `--crlf`: `tree` reads the files with CRLF line breaks instead of LF (by default CRLF and CR become LF before parsing, so offsets
  are those of the IntelliJ document).

## Format

One block per file:

    ### <relative path>
    RazorDocument - [0..N) - Width: N - [<the whole text>]
        MarkupBlock - [0..N) - Width: N
            MarkupTextLiteral - [0..0) - Width: 0 - [] - Gen<Markup> - SpanEditHandler;Accepts:Any
                Marker;[];
            ...
        EndOfFile;[];
    # diagnostic RZ1006(10:0,10 [1] path) The code block is missing a closing "}" character. ...

The tree lines are the compiler's `SyntaxSerializer` (the `.stree.txt` baselines of its tests, where `[0..N)::N` is written
` - Width: N` here): nodes with their span, the content of literal nodes, the chunk generator (`Gen<…>`), the edit handler, the directive
(`Directive:{name;Kind;Usage} [diagnostics]`); tokens as `Kind;[content];diagnostics`. A line break inside content is written `LF`
(the serializer writes its own line break so; with `--crlf` or `cases` the content's CRLF is one `LF`, by default a lone `\n` is too).
The `# diagnostic` lines hold `tree.Diagnostics` (the parser's and the nodes', by position) with the message.

The summary goes to stderr: `# files= filesWithDiagnostics= nodes= millis=` (`nodes` is the number of tree lines).
