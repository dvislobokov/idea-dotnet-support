# Compiler messages: the corpus of dotnet/docs against the plugin's diagnostics

The rule (the user, 2026-10-08): every error the C# compiler reports is an error in the editor with the plugin too. This folder measures it.

| What | Where |
|---|---|
| The examples of the pages | `src/test/resources/compilerMessages/cases/<page>-<n>.cs` — every ```` ```csharp ```` block (and `:::code` snippet) of `docs/csharp/language-reference/compiler-messages/*.md` of [dotnet/docs](https://github.com/dotnet/docs), verbatim, with a header `// docs: <page> #n; codes: ...`; plus `rider-comparison.cs`, a copy of `debug-playground/Broken/RiderComparison.cs` |
| What Roslyn says | `src/test/resources/compilerMessages/cases.roslyn.txt` — `file  start  end  code  severity`, one compilation per file (the examples repeat type names), made by `roslyndump semantics` (`tools/csharp-psi/roslyndump`, Microsoft.CodeAnalysis.CSharp 5.9): files mode — the newest `Microsoft.NETCore.App.Ref` pack, nullable enabled, langversion preview, no implicit usings |
| What the plugin says | `CompilerMessagesCoverageTest`: each example highlighted as in the editor (syntax + semantic annotators, the index fixtures of `src/test/resources/index` plus `System.ObjectModel`), errors matched with Roslyn's by code and line |
| The state | `src/test/resources/compilerMessages/coverage.txt` — a summary line and `file  CSxxxx@line  ok|missing|extra`; `extra` is an error the plugin reports and Roslyn does not (a false positive — the worse of the two) |
| The order of the work | `build/reports/compilerMessages/by-code.md` after a run of the test: the codes by the number of examples that lack them |

## Commands (from the root, Git Bash)

```sh
git clone --depth 1 --filter=blob:none --sparse https://github.com/dotnet/docs.git build/csdocs/docs
git -C build/csdocs/docs sparse-checkout set docs/csharp/language-reference/compiler-messages
uv run --no-project python tools/compiler-messages/extract.py build/csdocs/docs/docs/csharp/language-reference/compiler-messages
cp debug-playground/Broken/RiderComparison.cs src/test/resources/compilerMessages/cases/rider-comparison.cs   # keep its header line, see the file
dotnet build -c Release tools/csharp-psi/roslyndump                      # once
uv run --no-project python tools/compiler-messages/oracle.py --jobs=8    # ~1 minute
./gradlew.bat test --tests "io.github.dotnetsupport.CompilerMessagesCoverageTest"
```

The test fails on any change of the state, an improvement included: read `build/reports/compilerMessages/coverage.txt`, check that nothing
went from `ok` to `missing` and nothing new is `extra`, copy it over `src/test/resources/compilerMessages/coverage.txt`.

## What the corpus is not

- Examples that are fragments (a lone member, `...`) get Roslyn's errors of a fragment (CS8803, CS0106, CS0116); they count as `missing`
  until the plugin reports them too — Roslyn does, so the rule applies.
- Examples that need an assembly of their own (`cs0433`, `cs1701`, `cs1705`: `/reference:` on the page) report CS0234 / CS0430 for it; same.
- Warnings are in `cases.roslyn.txt` but not measured yet: the rule is about errors first.
