# Plan: native C# PSI

Goal: C# lexer, parser, PSI, stubs and later semantics for the IntelliJ Platform without a language server, checked
against Roslyn on real code. The move into idea-dotnet-support and the switch-over feature by feature are planned
there (`CSHARP_PSI_MIGRATION.md`); step numbers below are the same as in that plan.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Where | Standalone repository, moved into the plugin when ready | As go-psi: fast iterations, own gates; the plugin's build is heavy (decided 2026-10-04) |
| Parser | Hand port of Roslyn's `LanguageParser` to `PsiBuilder` | C# is ambiguous everywhere (generics, lambdas vs parentheses, patterns, queries, contextual keywords); go-psi's Grammar-Kit + "two external rules" does not fit. Porting keeps recovery identical to the compiler |
| PSI | Element types, interfaces and implementations generated from Roslyn's `Syntax.xml` | ~250 node classes, ~1000 kinds; generated, kinds map to Roslyn one to one, so the tree diff is almost trivial |
| Lexer | JFlex (as go-psi), producing what the parser expects: contextual keywords as identifiers, single `>`, interpolated and raw strings as separate tokens, directives as tokens, disabled text by the active symbols | IntelliJ needs real tokens for highlighting and typing, Roslyn re-lexes strings in the parser |
| Bodies | Lazy reparseable blocks of methods, accessors, lambdas; the parse context (`async`, accessor kind, iterator) restored from the owner | Instead of Roslyn's blender (incrementality) |
| `#if` | Symbols of the configuration and the first TFM (`DEBUG`, `TRACE`, `NET`, `NETx_0_OR_GREATER`, ...) until the project model gives `DefineConstants` | All branches active breaks the parse (`#if X class A : B { #else class A { #endif`) |
| Oracle | `tools/csharp-psi/roslyndump`, the package built from `roslynCommit` | The same code that is ported |

## Roslyn pin

`roslynCommit` in `gradle.properties` = 35d9211b841e (the commit of `Microsoft.CodeAnalysis.CSharp` 5.9.0). Sizes there:
`LanguageParser.cs` 14,765 lines, `_Patterns` 680, `_InterpolatedString` 619, `DirectiveParser` 929,
`DocumentationCommentParser` 1,680, `Lexer.cs` 4,861 (not ported as is: the lexer is ours); `Syntax.xml` — 252 node
classes, 1,018 kinds; 72 test files in `Test/Syntax/Parsing`.

## Steps

**0. Bootstrap and probe** (in progress)
- [x] Repository, Gradle modules, sandbox plugin, test descriptor, `test buildPlugin` green (0.0.1).
- [x] `tools/csharp-psi/roslyndump` (`tokens`, `tree`, directory walk), `tools/csharp-psi/fetch-roslyn.sh`. Roslyn's `src` (252 files,
  1.6M nodes) dumps in ~4 s.
- [ ] Baseline numbers of the current path (roslyn-language-server + heuristics) in idea-dotnet-support: first
  highlighting, server ready, memory of the server and the IDE, completion latency — on `debug-playground` and a
  sample of `dotnet/aspnetcore`. Recorded in `CSHARP_PSI_MIGRATION.md`, "Исходные замеры". (Measured by the UI robot,
  `tools/ui-robot/baseline.py` of idea-dotnet-support, 3 runs each; open until the user confirms the numbers live.)
- [x] Corpus beyond Roslyn's own sources: `tools/csharp-psi/fetch-corpus.sh`, tags `runtimeTag`/`aspnetcoreTag` = `v10.0.12`
  (`gradle.properties`). `runtime` (`src/libraries` with tests, `src/coreclr/System.Private.CoreLib`): 20,466 files,
  261 MB of `.cs`, 30.0M nodes, `roslyndump tree` in ~40 s; `aspnetcore` (`src`): 10,170 files, 62 MB, 7.6M nodes,
  ~13 s. Files with Roslyn errors (no `--define`): 17 and 16 — mostly `#error` in the active branch of
  `#if`/`#else` (target frameworks), template directives in `ProjectTemplates`, two genuinely invalid test files.
- [x] Vertical slice (0.0.2): expressions, names and types, patterns, lambdas and the statement minimum ported onto
  `PsiBuilder` — ≈6,800 Roslyn lines → 5,015 Kotlin lines in ≈1 h of agent time, three gate iterations to 0.
  `ExpressionSliceDiffTest` and `ParsingTestsSliceCorpusTest` (714 valid + 368 invalid Roslyn parser inputs): 0
  mismatches, 0 spurious errors, 0 exceptions; review fixes ≈40 min more. Every hard spot of `docs/csharp-psi/PORTING_MAP.md` §7 has a PsiBuilder solution
  (`docs/csharp-psi/GRAMMAR.md`, "Parser: the step-0 slice"); the only gap — where a missing token sits — is a rule of the
  test mapping. Verdict: direct port onto PsiBuilder, no intermediate tree.
- [x] Beyond the plan (0.0.2): the lexer of step 4 except `#if` (token gate 0 mismatches on 42.5M tokens of Roslyn,
  runtime, aspnetcore), `roslyndump gen-kinds` (`SyntaxKind.kt`, `SyntaxFacts.kt`), test infrastructure and gates.

Recalibrated estimates (2026-10-04, from the slice): step 3 — 1 session (kinds are generated already; PSI interfaces
and classes from `Syntax.xml` remain); step 4 — 0.5–1 session (`#if` by symbols, directive tokens); step 5 — 3–4
sessions (≈9,300 remaining lines of `LanguageParser*`, `DirectiveParser` and doc comments ≈2,600, lazy bodies);
step 6 — 1 session (the gates exist; tree gate on the full corpus once the file parser is wired, fuzz, benchmarks).

**3. Generator from `Syntax.xml`.** Element types, PSI interfaces with accessors, implementations; the kind mapping table
and the normalisation rules for the tree diff (lists are not nodes in Roslyn; missing tokens ↔ error elements; skipped
tokens trivia ↔ error elements; trivia diffed separately as tokens). (Done: `roslyndump gen-psi` generates interfaces,
implementations, the visitor and kind sets — 41 abstract and 250 concrete classes, 950 accessors — deterministically;
accessor gate 0 mismatches on all corpora including doc-comment structure. Left: structured trivia of directives and
skipped tokens, which our tree does not have as nodes yet.)

**4. Lexer.** Done (0.0.2 without `#if`; directives and `#if` after it). JFlex; token diff against `roslyndump tokens` on the corpus (merging `>` pairs and splitting strings by
the rules in `docs/csharp-psi/GRAMMAR.md`); 0 bad characters, 0 mismatches. Directives are lexed as Roslyn's `DirectiveParser`
sees them and `#if` is evaluated with the file's symbols (empty for the gates, `CSharpPreprocessorSymbols.IDE_DEFAULT`
in the IDE); directive tokens and disabled text are compared too, nothing is skipped (runtime: 68,086 directive
tokens, 4,855 disabled texts). Left: the XML of doc comments; symbols from the project model.

**5. Parser.** The port, file by file, with lazy bodies and their context. (0.0.3: all of `LanguageParser*`
— statements, queries, declarations, local functions — ported and wired into `CSharpParserDefinition`; ≈1 session of
agent time. Left: lazy reparseable bodies with their context (`async`, accessor kind, iterator, `field`/`value`;
a body containing a directive or starting at a non-zero lexer state is not reparsed alone), the XML of doc comments
(`DocumentationCommentParser`), structured directive nodes.)

**6. Gates.** Tree diff against `roslyndump tree`: 0 mismatches on valid code (Roslyn sources, runtime, aspnetcore,
playground); on invalid code (parsing tests) 0 exceptions and a match ratio that only grows; mutation fuzz (0
exceptions, `localityViolations` metric); benchmarks (parse ms/MB, incremental body reparse) with thresholds.
(0.0.3: tree gates 0 mismatches and 0 failures on Roslyn `src`, runtime, aspnetcore, playground — ~38.5M Roslyn nodes;
parsing tests 0 mismatches except `ParseBigExpression` (depth limit) and `MissingNodeWithSkippedTokens1` (misplaced
`#`); fuzz 0 failures; benchmark thresholds set. Left: `localityViolations` (73 of 1,789) to go down with lazy bodies,
body reparse benchmark to become a real incremental one.)

**8. Stubs and indices.** Types, members, extension methods, test attributes; no stubs inside bodies. (Done 2026-10-04
in idea-dotnet-support, docs/csharp-psi/GRAMMAR.md "Stubs": the platform's stub registry over the `SyntaxKind` element types,
`gen-psi` makes the declaration classes stub-based (`GenPsi.Stubbed`), four string stub indexes; `CSharpStubTest`, host
`CSharpStubNavigationTest` with AST loading forbidden. Stub building on 3,000 files of `runtime/src/libraries` (26 MB):
≈ 130 ms/MB including the parse, which is almost all of it (parse alone ≈ 105–115 ms/MB), ≈ 3,500 stubs and ≈ 110 KB of
serialized stubs per MB of source; the whole `dotnet/runtime` corpus (261 MB) ≈ 35 s on one thread. Left: lazy bodies
for the stub path, only if indexing asks for them.)

Semantics (step 10–11 of the migration plan) start only after milestone S in idea-dotnet-support.
