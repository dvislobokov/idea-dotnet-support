# Razor / Blazor corpus

`tools/razor/fetch-corpus.sh` clones the corpus into `.corpus/razor/` (git-ignored) at pinned commits (2026-10-15):

| Name | Repository | What it is | Files |
|---|---|---|---|
| `mudblazor` | MudBlazor/MudBlazor (MIT) | Blazor component library, `Microsoft.NET.Sdk.Razor`; generic components, cascading values, `RenderFragment`, bUnit tests, docs site | 2051 `.razor`, 1184 `.cs` |
| `nopcommerce` | nopSolutions/nopCommerce (own license, local use only) | MVC application, `Microsoft.NET.Sdk.Web`; `@model`, tag helpers, areas, layouts, sections, view components, plugins | 986 `.cshtml`, 3661 `.cs` |
| `eshop` | dotnet/eShop (MIT) | Blazor Web App (static SSR + interactive server), medium size: the first one to open in the IDE | 40 `.razor`, 18 `.cshtml` |
| `razor-compiler` | dotnet/roslyn, `src/Razor/src/Compiler` only | the oracle: the Razor compiler moved into dotnet/roslyn (dotnet/razor is archived) | see below |

All three solutions target `net10.0`; their `global.json` accept the SDK 10.0.401.

## Build and the generated C#

```sh
cd .corpus/razor
dotnet build mudblazor/src/MudBlazor.slnx      -p:EmitCompilerGeneratedFiles=true
dotnet build nopcommerce/src/NopCommerce.sln   -p:EmitCompilerGeneratedFiles=true
dotnet build eshop/eShop.Web.slnf              -p:EmitCompilerGeneratedFiles=true
```

`EmitCompilerGeneratedFiles` makes the build write what the Razor source generator produced — the C# of every `.razor` / `.cshtml`
with its `#line` mappings — under `obj/<Configuration>/<tfm>/generated/Microsoft.CodeAnalysis.Razor.Compiler/`: the reference for
the virtual C# document of a Razor file and its position mapping. The restore is also what `SolutionDiagnosticsHarnessTest`
(`SOLUTION_HARNESS=<folder of the .sln>`) needs.

## The baselines of the Razor compiler tests

Under `.corpus/razor/razor-compiler/src/Razor/src/Compiler/*/test/TestFiles/`, by suffix: `.stree.txt` (syntax tree, 2388),
`.cspans.txt` / `.tspans.txt` (classified / tag helper spans), `.ir.txt` (IR), `.codegen.cs` (generated C#, 828), `.decl.codegen.cs`
(the declaration pass), `.mappings.txt` (source mappings, 527), `.diag.txt` / `.diagnostics.txt` (diagnostics). The inputs are mostly
inline in the C# of the tests (`ComponentCodeGenerationTest`, parser tests); 182 `.cshtml` and 108 `.razor` are files of their own.

## The harness (RAZOR_PLAN.md, R0 / R0b)

```sh
SOLUTION_HARNESS="$PWD/.corpus/razor/mudblazor/src" SOLUTION_HARNESS_GENERATED=true \
  ./gradlew.bat :test --rerun --tests "io.github.dotnetsupport.SolutionDiagnosticsHarnessTest"
```

Our errors in every `.cs` of the restored solution go to `build/solution-harness.txt`; with `SOLUTION_HARNESS_GENERATED=true` the projects
also see `generated/` of the build (what CodeAnalysisHelper makes, byte for byte), files from `obj/` are under `<project>/~obj/` in the
report. `--rerun`: the variables are no inputs of the task. 2026-10-15: MudBlazor 241 errors in `.cs` (409 without the generators),
nopCommerce 88, eShop 15 — the list and what is a false error of the core, what an artifact of the harness, is in RAZOR_PLAN.md, R0b.
