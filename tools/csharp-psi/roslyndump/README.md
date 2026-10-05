# roslyndump

Roslyn's view of C# files: the oracle the corpus tests of csharp-psi diff against (the role `tools/astdump` plays in
go-psi). Roslyn is `Microsoft.CodeAnalysis.CSharp` 5.9.0, built from `roslynCommit` in `gradle.properties`.

    dotnet build tools/csharp-psi/roslyndump -c Release
    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll <command> ...

| Command | What it does |
|---|---|
| `tree <file or dir> [options] [--out file]` | Syntax trees of `*.cs` files (compilation units) |
| `tokens <file or dir> [options] [--out file]` | Tokens and trivia of `*.cs` files |
| `expr <file or dir> [--lines] [options] [--out file]` | Trees of expressions (`SyntaxFactory.ParseExpression`) |
| `stmt <file or dir> [--lines] [options] [--out file]` | Trees of statements (`SyntaxFactory.ParseStatement`) |
| `member <file or dir> [--lines] [options] [--out file]` | Trees of member declarations (`SyntaxFactory.ParseMemberDeclaration`) |
| `doc <file or dir> [options] [--out file]` | `tree` over `*.doc` files: the doc comment snippets of `extract-tests` |
| `semantics <project or file or dir> [options] [--out file]` | Roslyn's semantics of the files of one compilation: symbols of identifiers, types of expressions, diagnostics (section "semantics") |
| `gen-kinds --out <dir>` | Generates `SyntaxKind.kt`, `SyntaxFacts.kt` and `doc/XmlCharType.kt` of csharp-psi-core |
| `gen-psi <Roslyn dir> --out <csharp-psi-core dir>` | Generates the PSI of csharp-psi-core from Roslyn's `Syntax.xml` |
| `extract-tests <ParsingTests dir> --out <dir>` | Extracts the snippets Roslyn's parsing tests feed to the parser |
| `errors [--out file]` | Every `ErrorCode` of the compiler with its message: `CS1002 ERR_SemicolonExpected ; expected` |

A directory is walked recursively (`bin`, `obj`, `.git` skipped) in ordinal order of relative paths: `*.cs` for
`tree`/`tokens`, `*.expr`, `*.stmt`, `*.member`, `*.doc` for the snippet commands. Everything is parsed with
`SourceCodeKind.Regular`, `DocumentationMode.Parse` and the options:

- `--langversion <v>`: the language version, a `LangVersion` string (`LanguageVersionFacts.TryParse`: `7.3`, `14`,
  `latest`, `default`, `latestMajor`, `preview`, `iso-1` ...); `preview` when absent;
- `--define A;B`: preprocessor symbols;
- `--include P;Q` or `--include @<file>` (one per line): only the files equal to or under these paths relative to
  the directory (relative paths in the output are unchanged);
- `--fields` (`tree`, `expr`, `stmt`, `member`): every child of a node ends with ` f=<Field>`, the `Syntax.xml` field
  of the parent it belongs to (format below).

**Before parsing, CRLF and CR are
replaced with LF** and the BOM is dropped: offsets are those of the IntelliJ document. A summary
(`# files= units= unitsWithErrors= nodes= errors= millis=`) goes to stderr; `units` are parsed snippets or files,
`unitsWithErrors` those with at least one `D` record.

## Snippets: `expr`, `stmt`, `member`

Each file is one snippet (an `.expr` file is one expression, possibly over several lines). With `--lines` every
non-empty line of the file is a separate snippet and its record is `file <path>:<lineNo>` (1-based); offsets are then
relative to the start of the line. Parsing uses `consumeFullText: true`, as Roslyn's tests do: text after the snippet
becomes `SkippedTokensTrivia` with an error. The output is the `tree` format (nodes, trivia section, errors) with the
snippet's node at depth 0 instead of a `CompilationUnit`; a snippet node has no `EndOfFileToken`. `member` writes an
empty unit when `ParseMemberDeclaration` returns null.

## `gen-kinds`

    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll gen-kinds --out csharp-psi-core/src/main/kotlin/io/github/dotnetsupport/csharp/lang

By reflection over the package (deterministic: enum value order):
- `SyntaxKind.kt`: `object SyntaxKind` with one `@JvmField val <RoslynName>: IElementType` per kind, the debug name
  equal to Roslyn's name. Tokens (`SyntaxFacts.IsAnyToken`) and trivia (`IsTrivia`, plus `SkippedTokensTrivia` and
  `PreprocessingMessageTrivia`, which `IsTrivia` leaves out) are `CSharpTokenType`, other kinds `CSharpElementType`
  (both in the hand-written `CSharpElementTypes.kt`), except `SingleLineDocumentationCommentTrivia` and
  `MultiLineDocumentationCommentTrivia`: the hand-written, lazily parsed `CSharpDocCommentElementType`. `None` and
  `List` are skipped; `byName` maps names to types.
- `SyntaxFacts.kt`: `object CSharpSyntaxFacts` — keyword text maps (reserved, contextual, preprocessor), fixed texts
  (`GetText`), the `GetXKinds()` enumerations as `TokenSet`s, every public `bool IsX(SyntaxKind)` as a `TokenSet` `IsX`
  plus `isX(kind)`, every public `SyntaxKind GetX(SyntaxKind)` as `getX(kind)` (null for `None`).
- `doc/XmlCharType.kt`: Roslyn's internal `XmlCharType.IsStartNCNameCharXml4e` / `IsNCNameCharXml4e` (the XML name
  classes of the doc comment lexer), tabulated over all UTF-16 units as ranges.

stderr lists the tabulated methods and the internal `SyntaxFacts` methods taking a `SyntaxKind` (not generated; the
parser port carries them by hand). The header of the files names the package version and `roslynCommit`; a warning
is printed when the package's source commit differs from `gradle.properties`.

## `gen-psi`

    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll gen-psi .corpus/roslyn --out csharp-psi-core

Reads `<Roslyn dir>/src/Compilers/CSharp/Portable/Syntax/Syntax.xml` (the sources `tools/csharp-psi/fetch-roslyn.sh` checks out
at `roslynCommit`) and writes, deterministically (document order of `Syntax.xml`), into `csharp-psi-core`:
- `src/main/kotlin/.../lang/psi/CSharpPsi.kt`: one interface per class (abstract ones included), `CSharpVisitor.kt`;
- `src/main/kotlin/.../lang/psi/impl/CSharpPsiImpl.kt`: one implementation per concrete class with its field shape,
  and the factory table by kind; the classes of `GenPsi.Stubbed` (declarations and the nodes between them) extend
  `CSharpStubElementImpl` with a second constructor from a stub, listed in `stubConstructors` (GRAMMAR.md, "Stubs");
  `impl/CSharpSyntaxKindSets.kt`: the node kinds of each class used as a field type;
- `src/test/kotlin/.../lang/psi/CSharpPsiFieldTable.kt`: kinds to classes and fields to accessors, for the gate.

Naming, field mapping and the cases it decides: csharp-psi's docs/csharp-psi/GRAMMAR.md, section "PSI". stderr lists the
adjacent fields that accept the same kinds (each needs a resolution in `GenPsi.Resolved`, otherwise the command fails)
and the `bool` fields that are not generated. Exit code 1 on an inconsistency of `Syntax.xml` with the package (an
unknown kind, a concrete class missing a field of its base that is not a known hand-written override); then nothing is
written. Exit code 2 when `<Roslyn dir>` is not a git checkout at `roslynCommit` of `gradle.properties` (the header of
the generated files names that commit, so it must be the one the sources come from).

## `errors`

    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll errors --out build/errors.txt

Prints `CS<number> <ErrorCode name> <message>` for every value of Roslyn's internal `ErrorCode` (reflection), the message
from `CSharpResources` in the invariant culture. The source of `CSharpErrorCode` of csharp-psi-core: the codes the files of
`Parser/` use, with their texts (`{0}` stays a placeholder). Regenerate the enum when the Roslyn of the corpus moves.

## `extract-tests`

    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll extract-tests .corpus/roslyn/src/Compilers/CSharp/Test/Syntax/Parsing --out .corpus/parsing-tests

Parses Roslyn's parsing tests with Roslyn (syntax only) and takes the first argument of calls of the parser entry
points when it is a compile-time constant string: literals of every form (regular, verbatim, raw, interpolated without
non-constant holes), `+`, `string.Empty`, a local declared once with an initializer and never assigned, a `const` or
`static readonly` field of the test class or its bases. Entry points and snippet kinds:

| Kind | Calls |
|---|---|
| `expr` | `UsingExpression`, `ParseExpression` (helper or `SyntaxFactory`) |
| `stmt` | `UsingStatement`, `ParseStatement` (helper or `SyntaxFactory`) |
| `member` | `UsingDeclaration`, `ParseDeclaration`, `SyntaxFactory.ParseMemberDeclaration` |
| `cs` | `UsingTree`, `ParseTree`, `ParseFile`, `ParseAndValidate[First]`, `ParseWithRoundTripCheck`, `ParseAndRoundTripping`, `ParseAndCheckTerminalSpans`, `UsingLineDirective`, `SyntaxFactory.ParseSyntaxTree`/`ParseCompilationUnit`, `CSharpSyntaxTree.ParseText` |
| by class | `UsingNode`/`ParseNode` with a string: the test class's `ParseNode` override (`ParseExpression` → `expr`, none → `cs`) |
| `doc` | `UsingNode`/`ParseNode` of a class whose override builds a doc comment with `ParseLeadingTrivia(string.Format(<constant>, text))` (`CrefParsingTests`, `VerbatimCrefParsingTests`: `/// <see cref="{0}"/>`; `NameAttributeValueParsingTests`: `/// <param name="{0}"/>`): the snippet is the formatted comment, parsed back by `roslyndump doc` |

Compilation tests (`CreateCompilation`) are not parsing entry points. `ParseName`, `ParseTypeName`, `ParseToken`,
`ParseLeadingTrivia` and the like are counted as skipped. Output: `<out>/<TestFile>/<Method>[_n].<kind>` (`_n` when a
method has several snippets, in source order), LF line ends, and `index.tsv` with the columns `file method kind entry
options classDefault path`: `options` is the options argument as written (`TestOptions.Regular9`, `options`, ...),
`classDefault` the options of the class's `ParseTree`/`ParseNode` override when the entry point goes through it. The
options are recorded for the consumer, which runs the dumps with the matching `--langversion` (csharp-psi's
`ParsingTestsVersionGate`). A previous extraction (a directory
with `index.tsv`) is replaced; another non-empty directory is refused. stderr: counts per kind and entry point, and
skipped calls per reason.

## Format

One record per line, fields separated by single spaces; offsets are UTF-16 code units, `start end` is a half-open span
without trivia (Roslyn's `Span`, not `FullSpan`).

| Record | Meaning |
|---|---|
| `file <path>[:<line>]` | Starts a file (or a snippet); the path is relative to the walked directory, with `/`; `:<line>` with `--lines` |
| `N <depth> <Kind> <start> <end> [f=<Field>]` | Syntax node (`tree`, `expr`, `stmt`, `member`); `f` with `--fields` |
| `T [<depth>] <Kind> <start> <end> [missing] [ck=<Kind>] [f=<Field>]` | Token; `missing` — inserted by error recovery (zero width); `ck` — contextual kind (`var`, `async`, `record`, …); `f` with `--fields` |
| `V <Kind> <start> <end>` | Trivia. In `tree` a flat section after the nodes, because Roslyn hangs trivia on tokens and a directive would otherwise sit inside a node whose span does not cover it: only structured trivia (directives, `SkippedTokensTrivia`, doc comments; its structure follows as `N`/`T` from depth 1) and `DisabledTextTrivia`. After a doc comment's structure, the `SkippedTokensTrivia` inside it (trivia of its XML tokens) follow as `V` records of their own, without structure, within the comment's span. In `tokens` all trivia that has no structure, in text order |
| `D <Id> <start> <end> <message>` | Parser error (`tree` and the snippet commands), after the nodes and the trivia section |

`f=<Field>` (`--fields`, `SlotFields.cs`) is on every child of a node, structure of trivia included, never on a
root: the name of the field of the parent's class whose value holds the child (the elements and separators of a
separated list all carry the list's field). The names come from Roslyn itself, not from `Syntax.xml`: the parameters
of the internal (green) constructor of the node's class, in slot order, `bool` parameters left out; each field is read
through the public property of that name and the flattened values are checked to be exactly `ChildNodesAndTokens()`.

`tokens` lists the tokens of the **parsed** tree, flattened into structured trivia; missing zero-width tokens are left
out. These are tokens after the parser's work: `>>` of a shift is one `GreaterThanGreaterThanToken` while the Roslyn
lexer (and ours) emits two `>`; interpolated strings are already split into their parts. The lexer gate merges and
splits by the rules in `docs/csharp-psi/GRAMMAR.md` before comparing.

## `semantics`

The oracle of csharp-psi's semantic layers (CSHARP_PSI_MIGRATION.md, step 11, task C0; consumer: `CSharpSemanticGate`,
`./gradlew semanticGate`). One compilation per run, built the way the plugin is meant to see the code:

    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll semantics debug-playground/Console/Console.csproj --root debug-playground --out console.txt
    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll semantics .corpus/runtime/src/libraries/System.Linq/src --assembly System.Linq --root .corpus/runtime/src/libraries --define "NET;NET10_0_OR_GREATER" --out linq.txt

- **Project mode** (a `*.csproj`): the compiler command line of a design-time `Compile` of the project
  (`dotnet msbuild -restore -t:Compile -p:SkipCompilerExecution=true -p:ProvideCommandLineArgs=true -p:DesignTimeBuild=true
  -p:BuildProjectReferences=false -getItem:CscCommandLineArgs`, about a second, nothing is compiled or built), parsed by
  Roslyn's `CSharpCommandLineParser`: the same sources (generated `GlobalUsings.g.cs` and `AssemblyInfo.cs` of `obj`
  included), references, `DefineConstants`, `LangVersion`, `Nullable`, `/nowarn` as `csc`. A `ProjectReference` is compiled
  from its sources too (recursively), not taken as a dll: its symbols are declared in source, as the plugin sees a solution.
  Source generators of every project run (`--no-generators` turns them off); their files are listed, not dumped.
  `--configuration` (Debug), `--framework` (default: `TargetFramework`, or the first of `TargetFrameworks`). The project's
  own files (not under `bin`/`obj`) are dumped. A project that does not compile is still dumped: the items come anyway.
- **Files mode** (a file or a directory, `*.cs` walked as in `tree`): one library compilation of those files with
  `--define`, `--langversion` (`preview`), nullable enabled, unsafe allowed, `--usings A;B` as global usings, references
  `--refs dir;x.dll|@file` (default: every assembly of the newest `Microsoft.NETCore.App.Ref` pack of the running dotnet);
  `--assembly Name` names the compilation and leaves the reference assembly of that name out (the sources are that library).

`--root` (default: the project's directory or the input directory) is what paths are relative to; `--include P;Q|@file`
restricts the dumped files. Text is read as in `tree`: CRLF and CR become LF, the BOM is dropped. stderr:
`# semantics files= names= bound= candidates= expressions= typed= diagnostics= millis=`.

Format: the first line `# roslyndump semantics <version>` (1; `SemanticDump.FORMAT_VERSION` of the reader), then records of
tab-separated fields:

| Record | Meaning |
|---|---|
| `S input <path>`, `S assembly <name>`, `S langversion <v>`, `S define <A;B>`, `S nullable <option>` | The dumped compilation |
| `S reference <file name> <full path>` or `S reference project:<assembly>` | Its references, sorted; the path is what the semantic gate indexes (`indexer/`) to give the resolver the same assemblies |
| `S options <n> <langversion> <A;B>` | Parse options, numbered; `S src <path> <n>` is every source file of the compilation and of the projects it references, with its options (the files a resolver needs besides the dumped ones) |
| `S generated <path>` | A file made by a source generator (its path is the generator's) |
| `F <path>` | Starts a dumped file |
| `N <offset> <text> <role> <kind> <id> <declarations> <flags>` | Every identifier token (not in trivia: inactive code and doc comments are out) |
| `X <start> <end> <SyntaxKind> <type> <converted type>` | Every expression node (type syntax included), in tree order: `TypeInfo.Type` and `ConvertedType`; `-` none (a namespace, a method group, a lambda's natural type), `=` converted is the same, `?Name` an error type |
| `D <start> <end> <id> <error or warning>` | The compiler's diagnostics of the file (`SemanticModel.GetDiagnostics`: syntax, declarations, bodies; errors and warnings that are not suppressed) |

`N` fields:
- `role`: `decl` — the token is the name of a declaration (`GetDeclaredSymbol` of its parent: types, members, locals,
  parameters, type parameters, labels, range variables, `using X =` aliases, anonymous type members); `ref` — a simple name,
  bound by `GetSymbolInfo`.
- `kind`: `SymbolKind`, refined for types (`NamedType.Class`, `NamedType.Struct`...) and methods (`Method.Ordinary`,
  `Method.LocalFunction`...), `Local.Const`; `-` when Roslyn binds nothing.
- `id`: the documentation comment id of the definition (`T:System.Collections.Generic.List`1`, `M:N.C.M(System.Int32)`) for
  namespaces, types and members; `<Kind>:<name>` for local-like symbols (`Local:sum`, `Parameter:x`, `Label:done`).
- `declarations`: where the definition is declared, `,`-separated: `path:offset` of its name in a source (every part of
  a partial type), `gen:<path>:offset` in a generated file, `asm:<assembly>` from metadata, `ns` for a namespace (declared
  everywhere), `-` none (an implicit symbol: `args` of top-level statements). `,`, `|`, `%` and tab in paths are escaped as
  `%2C`, `%7C`, `%25`, `%09`.
- Symbols are normalised to their definition: `OriginalDefinition` (`List<int>.Count` is `List<T>.Count`), the extension
  method a reduced call was made from, the type of an attribute name (Roslyn binds it to the constructor: flag `ctor`).
- Several candidates (Roslyn bound none) are `|`-separated in `kind`, `id` and `declarations`, with the flag `cand=<CandidateReason>`.
- `flags` (`,`-separated, `-` none): `acc` — the name after `.`, `?.`, `::` or of a qualified name; `own` — a member of a
  type around the name (outer types included), `inh` — of one of their base classes (both only without `acc`); `alias` —
  bound through a `using` alias; `kw` — a contextual keyword or implicit name (`var`, `nameof`, `dynamic`, `nint`, `nuint`,
  `unmanaged`, `notnull`, `_`); `cand=...`; `ctor`.
