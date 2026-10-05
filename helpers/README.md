# Помощники на .NET, которые работают постоянно

Плагин несёт их **исходниками** и собирает на машине пользователя под установленный SDK (`cli/DotNetHelper`, как `indexer/`,
`allocwatch/`, `metrics/`), а запускает один раз и держит процесс, пока он нужен (`cli/HelperConnection`).

| Папка | Помощник | Что в нём |
|---|---|---|
| `protocol/` | — | `Protocol.cs`: общий цикл запросов, его берут все помощники |
| `msbuildhost/` | MsBuildHost | вычисление проектов MSBuild (`Microsoft.Build` + `MSBuildLocator`) |
| `dotnethelper/` | DotNetHelper | клиент NuGet; IL Viewer (`Il.cs`, `ICSharpCode.Decompiler` + portable PDB); дальше — тесты через TestPlatform, декомпилятор, метаданные сборок |
| `diagnostics/` | DiagnosticsHelper | ClrMD (какой CLR в процессе, дампы); дальше — TraceEvent, `allocwatch` |
| `codeanalysis/` | CodeAnalysisHelper | source generators и анализаторы Roslyn с code fixes без сервера (0.1.77, пакет `codeanalysis`): `generate`, `analyze`, `fix`, `invalidate`, `info`. Roslyn — не пакет, а сборки SDK из `DotnetTools/dotnet-format` (HintPath, первая сборка без сети), поэтому собирается под каждый SDK отдельно (`perSdk`); проект — design-time `dotnet msbuild -t:Compile -getItem:CscCommandLineArgs`, отсюда **SDK ≥ 8**. Один на solution, выход после простоя |

Почему три, а не один: `Microsoft.Build` грузится из SDK и тянет свои `NuGet.*` (конфликт с клиентом NuGet в том же процессе), для
проектов .NET Framework нужен процесс на net472 с MSBuild из Visual Studio, а подключение к чужим процессам падает чаще всего —
пусть падает отдельно. Свой restore у каждого: несобравшийся пакет одного не ломает остальные.

## Протокол

Одна строка — один JSON-объект (UTF-8), в обе стороны; подробности — в шапке `protocol/Protocol.cs`.

```
→ {"id":1,"method":"evaluate","params":{...}}        запрос; запросы выполняются параллельно
→ {"method":"cancel","params":{"id":1}}              отмена (клиент шлёт её после своего таймаута)
← {"id":1,"result":...}  /  {"id":1,"error":{"message":"..."}}
← {"method":"log","params":{"level":"info|warn|error","message":"..."}}   строка в журнал плагина
```

Конец stdin — конец помощника. stderr идёт в журнал плагина как предупреждения. `HelperException` на стороне C# — ошибка для
пользователя без стека; любое другое исключение уходит со стеком.

## Как устроен помощник

- `helpers/<name>/Program.cs` (и при необходимости другие `*.cs` в той же папке, без подпапок), `helpers/<name>/<Assembly>.csproj`.
- Запуск с `--serve` → `await HelperProtocol.ServeAsync((method, params, ct) => ...)`.
- `.csproj`: `TargetFramework` по умолчанию `net8.0`, перекрывается свойством `HelperFramework` (его ставит сборщик); `RollForward`
  `LatestMajor`, `UseAppHost=false`, `DebugType=none`, `InvariantGlobalization`. Протокол в репозитории подключается ссылкой, в кэше
  он лежит рядом сам:
  `<Compile Include="../protocol/Protocol.cs" Condition="!Exists('Protocol.cs')" Link="Protocol.cs" />`.
- Сборка плагина кладёт `*.cs` и `*.csproj` каждой папки и копию `Protocol.cs` в ресурсы `/<name>/` (`build.gradle.kts`).
- Плагин: `DotNetHelper("<name>", "<Assembly>", "HelperFramework", listOf("Program.cs", "Protocol.cs", ...))` и
  `HelperConnection.of(helper, "<категория журнала>")`; соединение — в сервисе проекта или приложения, освобождается вместе с ним.
- Ответы — чистые данные; их разбор на стороне плагина — чистыми функциями с тестами. Настоящий помощник в тестах не запускается:
  протокол проверяется на подделке (`HelperConnectionTest`), разбор — на сохранённых ответах.
