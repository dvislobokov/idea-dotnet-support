# Testing

Everything is checked against Roslyn automatically: a layer is done when its corpus gate passes, not when samples look
right (CLAUDE.md). Test sources: `csharp-psi-core/src/test/kotlin/io/github/dotnetsupport/csharp/`.

## Layers

| Layer | Where | Task |
|---|---|---|
| Unit and golden tests | `*Test` | `test` (fast, every change) |
| Corpus gates: our trees and tokens against `roslyndump` over `.corpus/` | `*CorpusTest` | `:csharp-psi-core:corpusTest` |
| Benchmarks | `*Benchmark` | `:csharp-psi-core:benchmark` |

`corpusTest` and `benchmark` are `intellijPlatformTesting.testIde` tasks of `csharp-psi-core` (`csharp-psi-core/build.gradle.kts`, on
the IDE of `localIdePath` like the plugin); `test` excludes both patterns. Every `Test` task of the module gets
`csharppsi.testDataPath` (`csharp-psi-core/testData`: `testData/...` below means that directory), `csharppsi.repoRoot` (the
repository) and `csharppsi.corpus` (`<repo>/.corpus`), and every `csharppsi.*` property given to Gradle as `-D` or `-P` is passed
through (`-P` wins). The kotlinx-coroutines debug agent is dropped from test JVMs (`-Pcsharppsi.coroutinesAgent=true` keeps it).

## Commands

From the repository root, Git Bash, the JBR of the installed IDEA (no system JDK):

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
./gradlew.bat test buildPlugin -q            # the plugin's tests and :csharp-psi-core:test, the plugin ZIP
./gradlew.bat :csharp-psi-core:test --tests "io.github.dotnetsupport.csharp.lang.*" -q
tools/csharp-psi/fetch-roslyn.sh             # Roslyn sources at roslynCommit into .corpus/roslyn
tools/csharp-psi/fetch-corpus.sh             # dotnet/runtime, aspnetcore at runtimeTag/aspnetcoreTag into .corpus/
dotnet build tools/csharp-psi/roslyndump -c Release      # the oracle; format in tools/csharp-psi/roslyndump/README.md
dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll tree <file|dir> [--define A;B] [--out f]
tools/csharp-psi/gates.sh test corpus        # quiet gate output, full logs in build/gates/
./gradlew.bat :csharp-psi-core:corpusTest -Dcsharppsi.lexer.corpora=runtime,aspnetcore   # all corpus gates, ~2.5 min
```

`-Pcsharppsi.corpus=<dir>` points the gates at corpora elsewhere. Failed tests: `csharp-psi-core/build/test-results/<task>/TEST-*.xml`.

## Goldens

Parser goldens extend `CSharpParsingTestCase`: `testData/parser/<Name>.cs` and the PSI dump `<Name>.txt` (ranges,
whitespace included). A missing golden is written and the test fails, so that it is reviewed.
`-Dcsharppsi.updateGoldens=true` rewrites all goldens of the run and passes; review the diff, then run again without
the flag.

## Metrics and allowlists

`CorpusMetrics.check(testData/metrics/<gate>.json, ...)`: integer values that **may only improve** — lower is better,
except keys passed as `higherIsBetter`; `informational` keys (file and node counts) are recorded only. A regression
fails the gate; an improvement rewrites the file, which is committed in the same change.

Deliberate differences from Roslyn go to `testData/<area>/<gate>-allowlist.txt` (empty by default): `class <mismatch
class>` or `<file>:<offset>`, `#` starts a comment. Allowed mismatches are reported apart, stale entries are listed.
Every entry needs a note in `docs/csharp-psi/GRAMMAR.md`.

## The oracle

`tools/csharp-psi/roslyndump` (format: `tools/csharp-psi/roslyndump/README.md`). `lang/oracle/RoslynDump` builds it with
`dotnet build tools/csharp-psi/roslyndump -c Release` when `bin/Release/net10.0/RoslynDump.dll` is missing or older than a source
of the tool (`-Dcsharppsi.roslyndump=<dll>` uses a given build), runs a command over a file or a directory into a
temporary file and parses it into `DumpFile`s: a `DumpNode` tree (`kind start end`, tokens with `missing` and `ck`),
the trivia section and the diagnostics. `forEachFile` streams large dumps. `dotnet` must be on `PATH`;
`RoslynDumpRunTest` and `PsiToDumpTest` run it inside `test`. A run is killed and fails after a timeout
(Roslyn itself can hang, docs/csharp-psi/GRAMMAR.md "Doc comments"): 10 minutes over a directory, 2 over a file or snippet,
`-Pcsharppsi.roslyndump.timeoutMinutes=` and `-Pcsharppsi.roslyndump.fileTimeoutMinutes=` change them.

A tree gate is: `roslyndump tree` → `PsiToDump` of our PSI → `TreeDiff` → `DiffReport` → `CorpusMetrics`.

- `PsiToDump` maps PSI onto `DumpNode`s. Element type debug names are Roslyn `SyntaxKind` names; by default kinds ending
  in `Trivia` (and `WHITE_SPACE`) are dropped, other leaves and `*Token`/`*Keyword` composites are tokens, the remaining
  composites are nodes, the file element is unwrapped (`isNode`/`isTrivia` are pluggable). Error elements are unwrapped
  and counted. Spans run from the first to the last significant position (non-trivia leaf, or zero-width node or error
  element, the counterpart of a Roslyn missing token); `CompilationUnit` ends at the end of the text, as Roslyn's
  `EndOfFileToken` does.
- `TreeDiff` aligns siblings by start offset and compares kinds and spans of nodes and tokens (not `ck`). Classes:
  `A -> B kind`, `K range`, `K missing`, `K extra`; a missing or extra wrapper is reported once and its children are
  compared in place. Missing and other zero-width Roslyn tokens are skipped and counted, as are our tokens inside
  `SkippedTokensTrivia`.
- `DiffReport` prints the summary, classes by count and examples round-robin over classes; the full list goes to
  `<module>/build/roslyndump/<gate>-mismatches.txt`.

`PsiToDumpTest` runs the whole chain on a toy expression language, without the C# parser.

## Parser gates over Roslyn's parsing tests

`.corpus/parsing-tests` holds the inputs of Roslyn's `Test/Syntax/Parsing/*ParsingTests.cs` (`roslyndump
extract-tests`, with `index.tsv`), one snippet per file by the test helper used: `*.expr`, `*.stmt`, `*.member`,
`*.cs`. `SliceGate` diffs each against the matching `roslyndump` mode through `SliceParseHarness` (`Mode.Expr`, `Stmt`,
`Member` = `ParseMemberDeclaration`, `File` = the parser of `CSharpParserDefinition`, `CompilationUnit` included):

- `ParsingTestsSliceCorpusTest`: `.expr`, `.stmt` → `testData/metrics/parsing-tests-slice.json`;
- `ParsingTestsDeclarationsCorpusTest`: `.member` → `parsing-tests-members.json`, `.cs` → `parsing-tests-files.json`.
  Inputs with directives are diffed with the rest (the lexer evaluates `#if`).

Buckets of both tests: the inputs a test wrote for a script or an explicit language version
(`ParsingTestsIndex.isScriptOrOldVersion` on the `options` and `classDefault` columns of `index.tsv`) go to
`ParsingTestsVersionGate`; the rest are valid / invalid at `Preview`. The version gate resolves each input's version
(`ParsingTestsOptions.resolve`: `TestOptions.RegularN`, `WithLanguageVersion(LanguageVersion.X)`, `options ?? X`;
`cond ? A : B` gives both; a version in a variable gives every effective version) and diffs it at each of them, the
oracle with `--langversion` and `--include` (the inputs of that version only) and our parser at the same
`CSharpLanguageVersion`. Script inputs (`TestOptions.Script`; `IsScript` is not ported) are a bucket of their own,
diffed as regular code at their version. Metrics `oldLangVersion*` and `script*` in the same files: `Files`, `Diffs`,
`ValidDiffs`, `UnresolvedFiles` informational; `ValidMismatchedDiffs`, `SpuriousErrorDiffs`, `InvalidMismatchedDiffs`,
`Exceptions` only improve. `LanguageVersionDiffTest` (`test`) is the fast counterpart: one snippet per
version-dependent path, at its boundary version and the one before (docs/csharp-psi/GRAMMAR.md, "Language version").

Fast counterparts on hand-written snippets: `ExpressionSliceDiffTest` (`testData/parser/slice`) and
`DeclarationsDiffTest` (`testData/parser/decl`); both require 0 mismatches, valid and invalid. Every snippet is
diffed: the out-of-scope bucket of the step-0 slice (and its `outOfScopeFiles` / `scopeDisagreements` metrics) is gone
since the parser ports the whole grammar.
## Tree gates (step 6)

`lang/parser/TreeCorpusGate` with one test per corpus, all in `corpusTest`:

| Test | Corpus | Metrics |
|---|---|---|
| `RoslynSrcTreeCorpusTest` | `.corpus/roslyn/src` | `roslyn-src-tree.json` |
| `RuntimeTreeCorpusTest` | `.corpus/runtime/src` | `runtime-tree.json` |
| `AspnetcoreTreeCorpusTest` | `.corpus/aspnetcore/src` | `aspnetcore-tree.json` |
| `PlaygroundTreeCorpusTest` | `debug-playground` or `-Dcsharppsi.playground=<dir>`; skipped when missing | `playground-tree.json` |
| `RuntimeNetFrameworkCSharp73TreeCorpusTest` | `.corpus/runtime/src/libraries`, only `<Library>/src` of the libraries whose `src` project targets .NET Framework (90), both sides at C# 7.3 | `runtime-netfx-cs7.3-tree.json` |

- **Path.** Each file goes through the public path: `PsiFileFactory` over a `LightVirtualFile` (`ParsingTestCase.createFile`),
  so `CSharpParserDefinition`, the file element type and lazy elements run as in the IDE; then `PsiToDump` (our
  `CSharpElementType`s are nodes) and `TreeDiff` against the oracle. `PsiBuilderFactory` + `ParserDefinition` directly
  would skip the file element type and could not expand lazy bodies (they need a `PsiFile`); `CSharpParserBenchmark`
  reports the difference (`builderOnly`).
- **Oracle.** `roslyndump tree` without `--define` (the lexer's `#if` symbols must be empty for the gates:
  `CSharpParsingTestCase.createFile` puts an empty `CSharpPreprocessorSymbols.KEY` on every file) and at the gate's
  language version (`--langversion`, `preview` by default; `createFile` puts the same `CSharpLanguageLevel.KEY`,
  `TreeCorpusTestBase.langVersion` / `languageVersion`; `includes()` restricts the corpus with `--include`), streamed
  from stdout and never written to disk (the runtime dump is 2.2 GB); parsing runs while the oracle walks.
- **Parallelism.** `-Dcsharppsi.treeGate.threads` (default: processors - 1, at most 8). Files are independent and the
  light `ParsingTestCase` environment allows PSI creation off the EDT; at most 2 x threads files are in flight.
- **Guards** (`ParseGuard`, per file): exceptions, `StackOverflowError` (the parse thread has the JVM's default stack,
  as the IDE's pooled threads), timeouts (`-Dcsharppsi.treeGate.timeoutMillis`, default 30000: the progress indicator
  is cancelled; a parse that ignores cancellation is abandoned after twice the time and counted as a timeout), errors
  the platform logs instead of throwing (PsiBuilder's "Unbalanced tree" and the like), and coverage
  (`ParserGateSupport.checkTokenCoverage`: the leaves spell the text and only merge lexer tokens, never split them).
- **Buckets.** *Valid* files (no Roslyn diagnostic): `mismatchedFiles`, `mismatches`, and `spuriousErrorFiles` (an
  error element in our tree; also a mismatch of class `spurious error`). Files *with Roslyn errors*
  (`filesWithOracleErrors`, informational): `invalidMismatchedFiles`, `invalidMismatches` under the normalisation of
  invalid input (docs/csharp-psi/GRAMMAR.md, "What the gates compare"), with `invalidErrorElements` (ours) against
  `invalidOracleDiagnostics` (informational). Over all files: `exceptions`, `stackOverflows`, `timeouts`,
  `coverageFailures`, `loggedErrors`. `files` and `nodes` (Roslyn nodes and tokens) are informational.
- **Reports.** The summary prints classes by count (classes of files with Roslyn errors are prefixed `invalid:`) and
  examples; `build/tree-gate/<name>-mismatches.txt` lists up to 30 mismatches per file, `<name>-failures.txt` the
  failures with stack traces. Allowlist: `testData/parser/<name>-tree-allowlist.txt` (empty, absent by default).
- **Baselines.** Metrics are checked only on a full run of a parser that builds syntax nodes. While
  `CSharpParserDefinition` has the placeholder parser (a flat file of tokens) the gate prints `parser: builds no
  syntax nodes (placeholder)` and passes without writing anything. The first full run of the wired parser creates
  `testData/metrics/<name>-tree.json` (`CorpusMetrics` creates a missing file); review and commit it with the parser;
  from then on every value only improves. Baselines of the step-5 parser (2026-10-04): 0 mismatches, 0 failures on
  all four corpora. The playground changes with the user's experiments: after adding files to
  it, delete `playground-tree.json` deliberately and let the next run recreate it.
- **Quick runs** (metrics are not checked): `-Dcsharppsi.treeGate.limit=N` (first N files in the oracle's order),
  `-Dcsharppsi.treeGate.filter=<regex>` (relative paths, `find`), `-Dcsharppsi.treeGate.dir=<subdir>` (run the oracle
  on a subdirectory only, the fastest). `-Dcsharppsi.treeGate.metrics=false` skips metrics on a full run.
- **Strict mode.** `-Dcsharppsi.treeGate.strict=true` additionally fails on any mismatch, spurious error or failure
  in valid files: the end state of step 6 (PLAN.md: 0 mismatches on valid code).

## Mutation gate

`lang/parser/MutationOracleCorpusTest` (`corpusTest`, `tools/csharp-psi/gates.sh mutation`): the tree gates see almost only valid
code, Roslyn's parsing tests only the invalid code someone wrote a test for; this gate puts the oracle on invalid code
of every shape. A deterministic sample of 6,000 files of at most 60 KB of `.corpus/runtime/src/libraries` (seeded
shuffle of the sorted paths), 6 mutants each (36,000) with a seeded `Random` per file, cycling: delete 1-15
characters, insert a token (brackets, operators, `"`, `'`, `$"`, `/*`, `//`, keywords, literal starts), truncate. The
mutants are written to `build/mutation-gate/mutants` (`<n>_<k>_<kind>_<file>.cs`; `build/mutation-gate/mutants.txt`
maps each to its source and change) and go through one `TreeCorpusGate` run, so one `roslyndump` process walks the
directory (about 1 minute in all, half of it the oracle). The rules are the tree gate's: a mutant Roslyn parses
without diagnostics must match exactly, the others under the invalid-input normalisation; doc comments are compared
too. Metrics `testData/metrics/runtime-mutants.json`: `mismatchedMutants` (mutants with any mismatch, spurious error,
doc comment mismatch or failure; only improves), `exceptions`, `stackOverflows`, `timeouts`, `coverageFailures`,
`loggedErrors`; `mutants` informational. The summary lists the mismatch classes with examples,
`build/tree-gate/runtime-mutants-mismatches.txt` every mismatched mutant. A mismatch found here is minimised by hand
into a snippet `testData/parser/decl/invalid_mutant_*.cs` (checked by `DeclarationsDiffTest`) together with the fix.
Known remaining classes are listed in docs/csharp-psi/GRAMMAR.md ("Known differences on invalid code").

## Doc comments (step 5)

`lang/parser/DocCommentDiff` compares every doc comment of an oracle dump (a `V *DocumentationCommentTrivia` record
with its structure) with our doc comment element at that place, expanded. It runs inside the tree gates (metrics
`docComments`, informational; `docCommentMismatches` in valid files and `invalidDocCommentMismatches` in files with
Roslyn errors, both in `<name>-tree.json`; mismatch classes prefixed `doc:`) and inside `SliceGate` (its mismatches
count in the snippet's bucket; `docComments`, `docCommentMismatches` in the summary). Compared:
- nodes and non-empty tokens by kind and span; trivia (exterior, white space) is left out;
- zero-width tokens too, unlike the main tree: on both sides they become childless nodes `<Kind>!missing` (Roslyn's
  missing tokens, our `CSharpMissingTokenType`) and `<Kind>!zero` (`EndOfDocumentationCommentToken`,
  `OmittedArraySizeExpressionToken`), so the kind and the position of every recovery token are checked;
- the spans of `SkippedTokensTrivia` inside the comment: `roslyndump tree` writes them as `V` records after the
  comment's structure (`DumpTrivia.inner`), ours are `SkippedTokensTrivia` composites; the lists must be equal.

Normalisation: our `///` comment ends before the new line of its last line, Roslyn's after it (docs/csharp-psi/GRAMMAR.md, "Doc
comments"). The oracle's structure is cut at our end: non-empty tokens from there on are dropped (that new line),
zero-width ones beyond it move to it, nodes left empty are dropped, node spans are recomputed from their children.
A `///` comment that ends the file with an empty last line (`/// <summary>` + `///` + end of file) has no new line
token after that `///` in Roslyn, so Roslyn's missing tokens at the end sit before the line; ours go after the `///`
as when a new line follows (`DocCommentTreeBuilder.eodTriviaFirst`: the comment's text cannot tell the two apart), and
the zero-width tokens at the start of that line move to our end too (`DocCommentDiff.emptyLastLineAtEof`).
Roslyn reports no XML or cref diagnostic with `DocumentationMode.Parse` (the oracle's and the IDE's mode), so malformed
doc comments are in *valid* files and must match exactly; only an unterminated `/**` makes a file invalid.

| Test | Inputs | Metrics |
|---|---|---|
| `DocCommentDiffTest` (`test`) | `testData/parser/doc/*.cs`: every XML construct, every cref form, name attributes, malformed XML, `/** */` with and without `*`, unterminated | none, 0 mismatches asserted |
| `DocCommentParsingTest` (`test`) | the same files: PSI goldens `*.txt`; laziness, `PsiComment`, `isReparseable` | none |
| `ParsingTestsDocCommentsCorpusTest` | `.corpus/parsing-tests-doc/**/*.doc`: the inputs of `CrefParsingTests`, `VerbatimCrefParsingTests`, `NameAttributeValueParsingTests`, wrapped as those tests do (`extract-tests`, kind `doc`), against `roslyndump doc` | `parsing-tests-doc.json` |
| tree gates | every doc comment of roslyn-src, runtime, aspnetcore, playground | `<name>-tree.json` |

`.corpus/parsing-tests-doc` is an extraction of its own; `.corpus/parsing-tests` of the other gates is not replaced:

    dotnet tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll extract-tests .corpus/roslyn/src/Compilers/CSharp/Test/Syntax/Parsing --out .corpus/parsing-tests-doc

## PSI accessor gate (step 3)

The generated PSI (docs/csharp-psi/GRAMMAR.md, "PSI") against Roslyn's fields: `roslyndump tree --fields` names, for every child
of every node, the `Syntax.xml` field of its parent (tools/csharp-psi/roslyndump/README.md). `lang/psi/PsiAccessorCheck` pairs
Roslyn's nodes with ours (node children in order, error elements transparent) and checks, for every paired node and
every field of its class, that the public accessor (through the generated `CSharpPsiFieldTable`) returns what the field
holds: the paired node itself, a token of the same kind and range, lists element by element (separated lists with
their separators). Roslyn's missing tokens and other zero-width tokens (`EndOfFileToken`, omitted sizes and type
arguments) have no leaf on our side: expected null. Also every composite of our tree must be an instance of the
implementation generated for its kind, and every node kind of Roslyn's tree must have one. Doc comments are checked
too: every `V *DocumentationCommentTrivia` record of the dump (with `--fields` its structure names fields as well)
against our doc comment element containing its start, expanded, after the cut of the tree gates (Roslyn's structure
ends at our end, zero-width tokens beyond it move to it); the element must be a `CSharpDocCommentImpl`, and there
zero-width tokens other than missing ones (`EndOfDocumentationCommentToken`, `OmittedArraySizeExpressionToken`) must
be returned (empty composites). Not checked: directives and skipped-tokens trivia outside doc comments (our tree has no
nodes for them: directive tokens are leaves, skipped tokens error elements), and the tokens inside a doc comment's
`SkippedTokensTrivia` (trivia in Roslyn; the tree gates compare their spans).

| Test | Corpus | Metrics |
|---|---|---|
| `RoslynSrcPsiAccessorCorpusTest`, `RuntimePsiAccessorCorpusTest`, `AspnetcorePsiAccessorCorpusTest`, `PlaygroundPsiAccessorCorpusTest` | as the tree gates; files parsed the same way, run options `-Dcsharppsi.treeGate.*` | `<name>-psi.json` |
| `ParsingTestsPsiAccessorCorpusTest` | `.corpus/parsing-tests`, every snippet in its mode through `SliceParseHarness`, both sides at `Preview` | `parsing-tests-psi.json` |
| `ParsingTestsDocPsiAccessorCorpusTest` | `.corpus/parsing-tests-doc` (`roslyndump doc --fields`): Roslyn's cref and name attribute tests | `parsing-tests-doc-psi.json` |
| `PsiAccessorTest` (`test`) | `testData/parser/slice`, `testData/parser/decl` snippets and `testData/parser/doc`; factory, visitor, file root, doc comment element | asserted 0 |

Metrics: `accessorMismatches`, `nodesWithoutPsiClass`, `elementsWithWrongClass`, `alignmentMismatches`, failures (only
improve, and asserted 0 except the parsing tests' `alignmentMismatches`: 1, `ParseBigExpression.cs`, the depth
guard); `files`, `nodes` (paired Roslyn nodes, doc comment nodes included), `accessorChecks` (node × field), `docComments` informational. Mismatch class:
`<RoslynClass>.<Field>`; list in `build/psi-gate/<name>-mismatches.txt`. `tools/csharp-psi/gates.sh psi` runs the corpus tests.

## Fuzz (step 6)

`CSharpParserFuzzTestBase` (adapted from go-psi). Every source is mutated with a seeded `Random` (seed = base seed and
the source's name, reproducible from `name kind@offset`): delete, duplicate, insert (brackets, keywords, `"`, `/*`,
`#if X`, ...) or swap tokens, truncate, delete a line.

- **Hard rules** for the original and every mutant (the test fails): no exception, stack overflow, timeout or logged
  error (`ParseGuard`); lossless coverage of the text by leaves aligned with the lexer's tokens; parse under 5 s.
- **Incremental equals full** (hard rule, every mutant): the original file is reparsed incrementally to the mutant's
  text as a document commit does (`ChangedPsiRangeUtil.getChangedPsiRange` +
  `BlockSupportImpl.findReparseableNodeAndReparseIt`). When the platform reparses a body alone (docs/csharp-psi/GRAMMAR.md,
  "Reparseable bodies"), the original tree with that body replaced must equal the full parse of the mutant element by
  element, element types (body contexts) included (`BodyReparseSupport.firstMismatch`). `bodyReparses` counts the
  mutants handled by a body reparse (higher is better), `fullReparses` those that fell back (informational).
- **`localityViolations`** (informational since bodies are reparsed alone: it measures the full parse's recovery,
  while what the editor gets is decided by the rule above; kept to watch recovery). A mutation is *local* when it is
  a single-token kind, lies strictly inside the body `Block` of a member (`MethodDeclaration`, constructor, destructor, operator, conversion operator, accessor; the
  innermost one) and keeps that body lexically self-contained: in the mutant's tokens the body still starts with its
  `{`, ends with a `}` exactly at the shifted end, and its braces close only there (the condition for a lazy body to
  be reparsed alone; an unterminated `/*` running past the body is not local). For a local mutation the tree outside
  the member must not change: the preorder (depth, node/token, kind, span) list of the dump tree, the member's
  descendants excluded and offsets after the mutation shifted by the length delta, is equal for the original and the
  mutant, and the mutant has a node of the member's kind at the same depth and start. A violation is a mutation the
  full parse does not keep local (typically `public`/`struct` inserted into a body), which a body reparse rejects.
- `CSharpParserFuzzTest` (`test`): `testData/parser/**/*.cs`, `testData/lexer/*.cs` and the slice snippets wrapped into
  a method body, spread to 60 sources, 6 mutations each (360 mutants); hard rules asserted, locality reported.
- `CSharpParserFuzzCorpusTest` (`corpusTest`): 300 files of at most 100 KB of `.corpus/runtime/src` (seeded shuffle of
  the sorted paths), 20 mutations each; hard rules asserted; `testData/metrics/runtime-fuzz.json` (`hardFailures`,
  `bodyReparses` higher is better; `files`, `mutants`, `localityChecked`, `localityViolations`, `fullReparses`
  informational), created by the first run of a parser
  that builds nodes, not by the placeholder. `-Dcsharppsi.fuzz.files=N`, `-Dcsharppsi.fuzz.mutations=M` for other
  samples (metrics not checked).
- `GateSupportTest` (`test`) covers the guard, the coverage check and the locality rule on synthetic trees.

## Benchmarks (step 6)

`CSharpParserBenchmark` (`benchmark`), harness `benchmark/BenchmarkSupport` (adapted from go-psi): 3 warm-ups, the median
of 7 iterations. Samples: Roslyn's `Compilers/CSharp/Portable/Parser/*.cs` (`roslynParser`) and 150 files of
`runtime/src/libraries` (`runtimeSample`, seeded). Lines `BENCH <name>: median=<ms> <unit>=<value> (threshold ...)`:

- `lexer.<sample>`, `parse.<sample>` (ms/MB; parse = `PsiFileFactory` + a walk of the whole AST), `builderOnly.<sample>`
  (report only: `PsiBuilder` + `ParserDefinition` without a `PsiFile`);
- `bodyReparse`: one character typed into an identifier inside a method body of LanguageParser.cs and the document
  committed (the platform's `BlockSupport` reparse with `DiffLog`), toggling insert and delete; the method body is
  reparsed alone (a report line counts the body reparses among the commits and says whether a leaf far from the edit
  kept its PSI identity). Before reparseable bodies (0.0.3) it was a full reparse plus a tree diff (≈100 ms).

Thresholds: `testData/metrics/benchmark-parser.json`. A run fails when the median exceeds the stored median x 1.5 x
`-Dcsharppsi.benchmark.tolerance` (default 1). Entries are written only with `-Dcsharppsi.benchmark.update=true`,
when missing or faster (only improve). Parser entries of the placeholder parser are reported, never stored or compared;
establish the thresholds with `tools/csharp-psi/gates.sh bench -- -Pcsharppsi.benchmark.update=true` once the parser is wired, on
the machine that runs the gates.

## Semantic gate (step 11)

`./gradlew semanticGate` (`tools/csharp-psi/gates.sh semantic`), a task of the **root** project like `formatOracle`, never part of
`test`: `CSharpSemanticGate` (`src/test/kotlin/io/github/dotnetsupport`) compares a `CSharpSemanticModel` (the interface of
`csharp-psi-semantic`: `symbolAt`, `typeOf`, `diagnostics`) with `roslyndump semantics` (tools/csharp-psi/roslyndump/README.md,
section "semantics"). It lives in the root project because the resolver needs what the host gives a file: the parse options of
the project (`CSharpParseOptions.put`), the parser registration and the stub index of the light project, later the project model
(step 10). Inputs: `debug-playground` projects (`Console`, `Web`; project mode: the compilation MSBuild would make, project
references from source) and corpus libraries (`System.Linq`, `System.Threading.Channels`, `Microsoft.Extensions.Primitives` of
`.corpus/runtime`, files mode against the newest reference pack). Each input's sources go into the light project under their
dump paths with the dump's `#if` symbols and language version; every dumped file is compared (`SemanticComparison`):

- names (11a), per category: locals, parameters, local functions, labels, type parameters, members of the own type, inherited
  members, other members, types of the solution, types from assemblies, namespaces, aliases, member access (solution /
  assemblies), `var` and other contextual keywords, candidates, declarations. Correct: every place the answer gives is a
  declaration of Roslyn's symbol, or the same documentation comment id (a symbol of an assembly). Names Roslyn does not bind are
  not scored; answers for them are counted as `answered unbound`;
- types (11b): the natural type (`TypeInfo.Type`, fully qualified, keywords for special types) per expression category;
- diagnostics (11e): matched errors and warnings by id and span, spurious ones.

Assemblies: the dump lists the references of the compilation with their paths (`S reference <file> <path>`); the gate builds the
plugin's indexer (`indexer/`, into `build/semantic-gate/indexer`), indexes them into `build/semantic-gate/index` and gives the set
to the resolver through `CSharpSemanticEnvironment.setAssembliesForTests`, so types and members of assemblies resolve as in the IDE.
The model since task C1 is `ResolvingSemanticModel` (`SyntacticSemanticModel.kt`): the name resolution of layer 11a
(`lang/semantic/CSharpNameResolver`), with the syntactic answer where it has none; its candidates (no single answer) count as
unresolved, but as correct for the names Roslyn bound to candidates too. Since task C2 it also answers `typeOf` with the types of
expressions of layer 11b (`lang/semantic/CSharpExpressionTypes`, displayed as Roslyn does by `CSharpTypeDisplay`). `-PsemanticGate.model=syntactic` measures the syntactic
model alone (Go to Declaration of the native tree, which itself falls back to the resolver since 0.1.57).

Output: the table per input and over all, `build/semantic-gate/report.txt` with examples of wrong and unresolved answers,
the dumps `build/semantic-gate/<input>.txt`. Baseline: `src/test/resources/semanticGate/baseline.txt` (correct answers per input
and category; may only improve, an improvement rewrites it). Options `-PsemanticGate.<name>=`: `projects`, `libraries`
(`none`), `corpus`, `out`, `examples`, `reuse=true`, `roslyndump`, `dotnet`, `model` (`syntactic`). Unit tests of the reader and the comparison:
`SemanticOracleTest` on the committed dump `src/test/resources/semantic/sample.txt`.

## gates.sh

`tools/csharp-psi/gates.sh <gate>... [-- <gradle args>]` runs gates and prints only what matters (summaries, metrics, failures,
build result); full logs are in `build/gates/<gate>.log`. Gates: `test`, `build`, `corpus[:core]`, `bench[:core]`,
`tree` (all `*TreeCorpusTest`), `tree:roslyn|runtime|aspnetcore|playground`, `psi` (all `*PsiAccessorCorpusTest`), `fuzz` (both fuzz tests),
`mutation` (`MutationOracleCorpusTest`), `semantic` (`semanticGate` of the root project), `:module:task`.
On Windows it calls `gradlew.bat` with `JAVA_HOME` defaulting to the JBR of the installed IDEA.

```sh
tools/csharp-psi/gates.sh test corpus
tools/csharp-psi/gates.sh tree
tools/csharp-psi/gates.sh tree:runtime -- -Pcsharppsi.treeGate.dir=libraries/System.Linq
tools/csharp-psi/gates.sh fuzz bench
```
