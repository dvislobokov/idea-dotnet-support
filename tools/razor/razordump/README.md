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
| `codegen <file or dir> [--langversion V] [--root-namespace NS] [--assembly-name A] [--references P;Q\|@file] [--csharp P;Q\|@file] [--no-tag-helpers] [--metadata-checksums] [--absolute-paths] [--newline lf\|crlf] [--crlf \| --keep-newlines] [--include P;Q\|@file] [--out file]` | The C# the source generator makes of every `.razor` / `.cshtml` (walked as `tree` walks), its source mappings and its diagnostics, in the formats of the compiler's `.codegen.cs`, `.mappings.txt`, `.diagnostics.txt` baselines |
| `ir <file or dir> [same options as codegen]` | The intermediate representation of every file after all the passes, in the format of the `.ir.txt` baselines |
| `cases-codegen <TestFiles dir> [--langversion V] [--check] [--tag-helpers-out dir] [--out file]` | The compiler's code generation integration tests: every `<Name>.cshtml` / `.razor` with a `<Name>.codegen.cs` beside it (recursively), processed as its test does; `--check` compares with the baselines |
| `tag-helpers [<dir>] [--langversion V] [--root-namespace NS] [--assembly-name A] [--references P;Q\|@file] [--csharp P;Q\|@file] [--out file]` | The tag helper descriptors `codegen` discovers, as JSON |
| `symbols [<dir>] [same options as tag-helpers]` | The symbols of that compilation the compiler's producers read (types, properties, attributes with their arguments), as JSON: the input of razor-psi's port of the discovery |

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

## Code generation: `codegen`, `ir`

The oracle of the virtual C# document of a Razor file (RAZOR_PLAN.md, R3b): the compiler's runtime code generation, which is what the
SDK's source generator compiles and what the tooling maps (the SDK's compiler has no design-time writer any more). The engine is the
source generator's (`GetGenerationProjectEngine` of `RazorSourceGenerator.Helpers.cs`, options of `RazorSourceGenerator.RazorProviders.cs`):
consolidated MVC views, `AddComponentParameter` available, no `RazorSourceChecksumAttribute`, the Roslyn tokenizer, C# `preview`.
Every `.razor` / `.cshtml` under the directory is in its file system with the Razor path `/<relative path>`, so a file gets the
`_Imports.razor` / `_ViewImports.cshtml` of its folder and the folders above — within the directory: run it on the project directory
(`--include` picks the files to print), not on a subfolder, or the parent imports and the namespace of the folder are lost.

The tag helpers are discovered as the source generator does: the declarations of the directory's components (`ProcessDeclarationOnly`)
and its `.cs` files (`bin`, `obj` skipped) are compiled against the reference assemblies of the installed .NET and ASP.NET Core
(`packs/Microsoft.NETCore.App.Ref`, `packs/Microsoft.AspNetCore.App.Ref` of the runtime's major version) and searched with the compiler's
producers — components, `@bind`, event handlers, `@ref`, `@key`, splats, render modes, form names, MVC tag helpers, view components.
Without them a component is a markup element and `@bind` an attribute. Options:

- `--root-namespace`: MSBuild `RootNamespace` (the generator's fallback is `ASP`); `--assembly-name`: the assembly of the directory's
  own tag helpers (`@addTagHelper *, Name`), default the root namespace;
- `--references`: more assemblies for the discovery (package references; e.g. every dll of the project's `bin` but its own);
- `--csharp`: more C# for the discovery compilation, files or directories — at least `obj/<cfg>/<tfm>/<Project>.GlobalUsings.g.cs`
  when the project has `ImplicitUsings` (without it a parameter of type `List<T>` is not resolved and the generated `TypeCheck<…>`
  differs), and the output of other source generators the components use;
- `--no-tag-helpers`: no discovery;
- `--packs P;Q`: the reference packs of the discovery (default `Microsoft.NETCore.App.Ref;Microsoft.AspNetCore.App.Ref`; a Razor class
  library without ASP.NET Core's framework has .NET's alone, its packages through `--references`);
- `--define A;B`: the preprocessor symbols of the discovery compilation (a project's `DefineConstants`: `#if NET9_0_OR_GREATER` members);
- `--metadata-checksums`: the `RazorSourceChecksumAttribute`s of the file and its imports (`GenerateRazorMetadataSourceChecksumAttributes`,
  which MVC's runtime compilation package turns on);
- `--absolute-paths`: the physical paths are absolute with the OS separators, as a build has them; by default they are the relative paths
  with `/`, so the dump does not depend on the machine. They are in `#pragma checksum`, `#line` and — through the position in the
  generated text — in the ids of tag helper call sites (`checksum + offset`);
- line breaks: by default the files are read with LF and the generated document is LF; `--crlf` makes both CRLF; `--keep-newlines`
  reads the files as they are on disk; `--newline lf|crlf` sets the generated document's alone (a build on Windows writes CRLF,
  `Environment.NewLine`, whatever the input's are).

The dump then equals the generator's output byte for byte (checked 2026-10-17 against `EmitCompilerGeneratedFiles`: MudBlazor's
library 165/165 with `--csharp …GlobalUsings.g.cs`, nopCommerce's `Nop.Web` 779/779 with `--references @<bin dlls> --csharp
…GlobalUsings.g.cs --metadata-checksums --absolute-paths --newline crlf --keep-newlines`, `debug-playground/Blazor` 3/3 with `--langversion 9.0` of its net9.0), but for the
absolute paths unless `--absolute-paths`.

`codegen`, one block per file:

    ### <relative path>
    <the generated C#: codeDocument.GetCSharpDocument().Text>
    ### mappings
    Source Location: (24:1,1 [40] Editor/RazorCSharp.razor)
    |using Playground.Blazor.Components.Pages|
    Generated Location: (496:13,0 [40] )
    |using Playground.Blazor.Components.Pages|

    ...
    ### diagnostics
    Editor/RazorHighlighting.razor(24,1): Warning RZ10012: Found markup element with unexpected name 'Counter'. ...

The mappings are the compiler tests' `SourceMappingsSerializer` (`.mappings.txt`), the diagnostics their `RazorDiagnosticSerializer`
(`.diagnostics.txt`, one per line); `ir` prints `### <relative path>` and the tests' `IntermediateNodeSerializer` (`.ir.txt`).
The serializers are copied from the compiler's test code (`src/Razor/src/Shared/Microsoft.AspNetCore.Razor.Test.Common/Language/
IntegrationTests/`) into `Baselines/`, their output unchanged (the members internal in the SDK's dll are reached by reflection).
The dump's line breaks are LF; the offsets and spans are those of the texts the compiler saw (CRLF counts two with `--crlf`).
A mapping whose source and generated texts differ (the tests assert they are equal ignoring case) is reported on stderr.
The summary goes to stderr: `# files= filesWithDiagnostics= millis=`.

## The integration tests: `cases-codegen`

`cases-codegen <TestFiles dir>` takes every `<Name>.cshtml` / `<Name>.razor` that has a `<Name>.codegen.cs` beside it and processes it
as `IntegrationTestBase` does (the test class is read from `test/IntegrationTests/<Class>.cs` next to `test/TestFiles`):

- the project item of `CreateProjectItemFromFile`: the path `TestFiles/IntegrationTests/<Class>/<Name>.cshtml` (so `#pragma checksum`
  and the `RazorCompiledItemMetadataAttribute` read as in the baselines), the text with CRLF line breaks (the tests ran on Windows) and a
  BOM (the checksum is of these bytes), the file kind `Component` when the test method passes `fileKind: RazorFileKind.Component`;
- the engine of `CreateProjectEngine`: CRLF, `SuppressUniqueIds = "__UniqueIdSuppressedForTesting__"`, `RegisterExtensions()` of the
  configuration's name, the class's configuration (`new(RazorLanguageVersion.X, "MVC-3.0", …)` of the MVC tests, else
  `RazorConfiguration.Default` with `RazorExtensions.Register` of `RunTest`), its C# version, the Roslyn tokenizer;
- the imports as its `NormalizedDefaultImportFeature` reads them (trimmed, CRLF — the MVC default imports start with a line break), with
  the `_ViewImports.cshtml` a test method adds (`AddProjectItemFromText`);
- the tag helpers its `CompilationTagHelperFeature` discovers: the C# the test method adds (`AddCSharpSyntaxTree`) compiled as the
  class's assembly (`TestAssembly`, `AppCode`) against the installed reference packs — always, since MVC's own tag helpers
  (`UrlResolutionTagHelper`, `BodyTagHelper`, `HeadTagHelper`) come from the references;
- a test method that runs with tag helper descriptors of its own (`RunTagHelpersTest`, `RunDiscoveredTagHelpersTest`) is printed as
  `### <path>` and `# skipped: tag helpers`; a case the compiler throws on as `# failed: <exception>`.

Each case is printed as `codegen` prints a file, the header the input's path relative to the directory. `--check` compares the C#
with `<Name>.codegen.cs`, the IR with `.ir.txt`, the mappings with `.mappings.txt` (when there is one) and the diagnostics with
`.diagnostics.txt` (none when there is no file), line breaks and the BOM aside, and prints to stderr

    # cases=N same=M ir-same=K mappings-same=J skipped=S diagnostics-same=D failed=F ir-baselines=I mappings-baselines=P
    # differs <path>: codegen ir mappings diagnostics | failed (<message>)

(`same` counts equal `.codegen.cs`). `--langversion` replaces the language version of the class's configuration (`latest` in both
classes). 2026-10-17, SDK 10.0.401 against the baselines of the corpus (dotnet/roslyn main):
`Microsoft.AspNetCore.Razor.Language/test/TestFiles/IntegrationTests/CodeGenerationIntegrationTest` — 87 cases, 33 skipped, 54 of 54
equal in all four; `Microsoft.AspNetCore.Mvc.Razor.Extensions/…/CodeGenerationIntegrationTest` — 25 cases, 23 equal (IR too, 4 of 4
mappings, 24 of 24 diagnostics), `BasicComponent` differs by the markup split of main (its `@functions` members go to the declaration
document there; the SDK's compiler has no split), `RazorComment_AdjacentCommentsInCodeBlock` (the test of dotnet/razor#7421) makes the SDK's
compiler throw (`ArgumentException`: `Expected 'SyntaxKind.RazorCommentStar', but it was Whitespace`) where main passes.

`--tag-helpers-out <dir>` writes the descriptors each processed case ran with (discovered from the references and the test's C#; every
case has MVC's three) as `<dir>/<Project>/<Class>/<Name>.taghelpers.json`, `<Project>` the test project's folder without
`Microsoft.AspNetCore.` — the fixtures of razor-psi's code generation gate (`razor-psi-core/testData/codegen-tagHelpers`, tools/razor/README.md):

    # for both projects, Microsoft.AspNetCore.Razor.Language and Microsoft.AspNetCore.Mvc.Razor.Extensions:
    dotnet tools/razor/razordump/bin/Release/net10.0/RazorDump.dll cases-codegen .corpus/razor/razor-compiler/src/Razor/src/Compiler/<Project>/test/TestFiles \
        --check --tag-helpers-out razor-psi-core/testData/codegen-tagHelpers --out <file>

## Tag helper descriptors: `tag-helpers`

`tag-helpers [<dir>]` runs the discovery of `codegen` (all the producers; the directory's components and `.cs` files when one is given,
`--csharp` and `--references` with or without it, against the installed reference packs) and prints the descriptors as one JSON array
(`TagHelperJson.cs`): each descriptor with `kind`, `runtimeKind`, names, `typeName` / `typeNamespace` / `typeNameIdentifier`, documentation,
the flags as booleans, `tagMatchingRules` (with the required `attributes`), `boundAttributes` (with `parameters` and `metadata`),
`allowedChildTags`, `metadata` (`kind` and the properties of its record — `PropertyMetadata`, `BindMetadata`, `ComponentMetadata`,
`ViewComponentMetadata`, … — as dotnet/roslyn main declares them; a property the installed compiler lacks is written with the record's
default) and `diagnostics` (`id`, `severity`, `warningLevel`, `message`, `span`) on every level. Keys in a fixed order, two spaces of indentation,
`[]` / `{}` when empty, only `"`, `\` and the control characters escaped: razor-psi's `TagHelperDescriptorJson` reads it and writes the same text
back (and the plugin's index will hand descriptors to the engine in it, RAZOR_PLAN.md R5). The count goes to stderr: `# tagHelpers= millis=`,
then `absent=` — the metadata properties the installed compiler lacks that the dump wrote as defaults (10.0.401:
`Component.AcceptsUnmatchedAttributes`, `Property.AcceptsAssetPath`, `Property.AcceptsStringLiteral`); the discovery gate sets ours so too.

## Symbols for the discovery: `symbols`

`symbols [<dir>]` writes the compilation `tag-helpers` searches as the compiler's producers see it — the input of razor-psi's discovery
(`razor-psi-core`, package `razor.lang.discovery`: the producers ported over a symbol model without Roslyn), whose output the gate
`TagHelperDiscoveryCorpusTest` diffs against `tag-helpers` of the same options (`Symbols.cs`). One line of JSON:

- `assembly` — the compilation's name; `assemblies` — in the order the discovery visits them (the compilation's, then the references):
  `name`, `references` (the names of the assemblies it references), `types` — the keys of its types as `TagHelperDiscoverer` walks them
  (namespaces depth first, the nested types of public types), only for an assembly that might hold tag helpers (its name or a reference
  starts with `Microsoft.AspNetCore.`);
- `byMetadataName` / `wellKnown` — `GetTypeByMetadataName` / `GetTypesByMetadataName` of the names the producers' factories look up;
- `types` — the definitions by key `assembly|metadata name` (nested types with `+`): name, namespace, containing type, kind, accessibility,
  `abstract`, `static`, `special` (`SpecialType`), `arity`; the walked ones, the base classes and the attribute classes in full (`full`:
  `typeParameters` with constraints and attributes, `base`, `interfaces` — `AllInterfaces` —, `attributes`, `doc` — the XML documentation —,
  `properties`, `methods` — only `Invoke`, `InvokeAsync`, `AddNamedEvent`, `AddComponentParameter`, the ones the producers ask for), the rest
  as stubs. A property has its type, accessibility, `static`, `override` with `overridden` (the declaring type and name), `parameters`
  (an indexer's count), the accessibility of `getter` / `setter`, `initOnly`, attributes, `doc`, and the compilation's answers the producers
  ask about it: `stringLiteral` (`CanConvertStringLiteral`: an implicit union conversion from `string`), `dictionaryAssignable`
  (`Dictionary<string, object>` converts by identity or an implicit reference conversion), `awaitable` (a delegate whose `Invoke` returns an
  awaitable), `dictionary` (the type arguments of the `IDictionary<,>` it implements);
- an attribute: `class` (a key), `arguments` and `named` as typed constants (`kind`, `type`, `value`: a string, a number — an enum as its
  value —, a boolean, a type reference, a list of constants);
- a type reference: `{"t":"n","d":key,"a":[arguments],"o":containing type,"tn":tuple element names}`, `{"t":"p","n"}` (a type
  parameter, `"m"` of a method), `{"t":"a","e","r"}` (an array), `ptr`, `dyn`, `{"t":"err","n","ns","a"}` (a type the compilation could
  not bind), `"q"` when annotated nullable; where the producers print the type, `"s"` — its three spellings: `ToDisplayString()`, the full
  name and the globally qualified name of `WellKnownSymbolDisplayFormats` (the gate checks razor-psi's `RazorTypeDisplay` against them).

Reference packs alone: 8 MB, 2 s.
