# Поддержка .NET Framework: что сделано и что дальше

Действующий план по .NET Framework (net4x SDK-стиля и проекты старого формата без `Sdk`). Сводный чек-лист — раздел «До уровня Rider
и .NET Framework» в `ROADMAP.md`; здесь — порядок работ, подробности и факты, найденные по ходу (2026-10-04). Площадка для живой
проверки — `debug-playground/NetFramework/NetFramework.sln` (чек-лист — `debug-playground/README.md`, раздел «Сборка проектов старого формата
MSBuild'ом Visual Studio»).

## Сделано

| Версия | Что | Где |
|---|---|---|
| 0.1.24 | `packages.config`: пакеты в Dependencies и окне NuGet, Install / Update / Remove выключены | `msbuild/PackagesConfig` |
| 0.1.27 | restore `packages.config` помощником (NuGet.Protocol) | `nuget/NuGetHelper` |
| 0.1.28 | MsBuildHost: вычисление legacy-проектов, дерево по вычисленным items | `msbuild/MsBuildEvaluation` |
| 0.1.41 | Build / Rebuild / Clean legacy-проектов и решений с ними — `MSBuild.exe` из VS / Build Tools (`vswhere`); «MSBuild version» в Toolset and Build; `VSToolsPath` для MsBuildHost | `build/VisualStudioToolset` |
| 0.1.42 | Run legacy-проекта — собранный exe напрямую; Stop у `WinExe` — жёсткий; вывод в OEM-кодировке; `SolutionDir` при сборке одного проекта | `run/ExecutableLaunch` |
| 0.1.69 | Отладка net4x (адаптер `dotnet-debugger` 0.2.0): Debug legacy-проекта — `program` = `TargetPath`, папка вывода как рабочая; отказ 32-битной программе и процессу до адаптера; attach к net4x без ключа реестра; одна сборка перед Debug вместо двух | `debugger/DotNetDebugRunner`, `run/DebugBitness`, `run/DotNetDebugLaunch` |

## Дальше — по порядку

### 1. Тесты legacy-проектов (2–3 дня)
`dotnet test` на проекте без `Sdk` не работает: цель `VSTest` импортирует только SDK (ожидается MSB4057 — **проверить на площадке**).
- Добавить в площадку `NetFramework/LegacyTests` (MSTest или NUnit на `packages.config`, `v4.8.1`) с парой тестов, один падает.
- Режим запуска: сборка через `DotNetBuildService` (уже идёт в `MSBuild.exe`), затем `vstest.console.exe` из той же установки VS
  (`<VS>\Common7\IDE\Extensions\TestPlatform\vstest.console.exe` — в Build Tools 2022 на машине разработки есть) с `TargetPath` сборки тестов.
  Запасной вариант без VS — `dotnet vstest <dll>` (умеет net4x-сборки? **проверить**).
- Логгер TRX и живой логгер плагина (`testlogger/`, `/logger:` и `/TestAdapterPath:`) — те же, что у `dotnet test`; фильтр — `/TestCaseFilter:`.
  Сверить, что `testing/TestingPlatform` и разбор TRX не зависят от `dotnet test`.
- Debug тестов — после отладчика (п. 4), `VSTEST_HOST_DEBUG` → `testhost.net4x.exe`.
- Покрытие (`coverage`) — отдельно: coverlet collector под vstest.console.

### 2. Reference assemblies net4x и `HintPath` в индексаторе и Dependencies (2 дня)
Индексатор (`indexer/`) берёт сборки из `project.assets.json`; у net4x стандартные сборки — в пакете
`Microsoft.NETFramework.ReferenceAssemblies.net4x` (`build/.NETFramework/v4.x/`) у SDK-проектов и в
`C:\Program Files (x86)\Reference Assemblies\Microsoft\Framework\.NETFramework\v4.x\` у legacy. У legacy `project.assets.json` нет вовсе:
список сборок брать у MsBuildHost (items `Reference` с `HintPath`, `ReferencePath` после `ResolveAssemblyReferences` — это уже не
вычисление, а выполнение цели; оценить, хватит ли `HintPath` + каталога reference assemblies по `TargetFrameworkVersion`).
Формат индекса меняется — поднять `Program.FormatVersion` и `AssemblyIndex.FORMAT_VERSION` вместе, пересоздать фикстуры.

### 3. Roslyn LS и legacy-проекты (1 день)
В журнале песочницы сервер грузит оба проекта площадки, но пишет «has unresolved dependencies». Выяснить, чего ему не хватает (restore
`packages.config` он не делает — пробует `dotnet restore` решения), и нужно ли предупреждение без VS / Build Tools.

### 4. Отладка .NET Framework — сделано в 0.1.69 (адаптер `dotnet-debugger` 0.2.0), проверено UI-роботом
- Debug legacy-проекта: «Build .NET Project» (`MSBuild.exe`) → `launch` с `program` = `TargetPath` и `cwd` = папка вывода, `console:
  integratedTerminal` как у .NET (адаптер 0.2.0 сам создаёт процесс с консолью терминала). Остановка на `BP:legacy-console`, кадры, переменные,
  Evaluate (в т. ч. `Newtonsoft.Json.JsonConvert.SerializeObject(args)`), Step Over / Into, Resume до конца, вывод с кириллицей. WPF: Debug,
  клик по кнопке (UI Automation) → `BP:legacy-wpf-click`, `clicks` = 1, Stop закрывает окно.
- Разрядность: адаптер отлаживает только 64-битные процессы, поэтому выбирать адаптер не из чего — плагин отказывает 32-битной программе
  (PE: PE32 + `32BITREQUIRED`, с `32BITPREFERRED` — это «Prefer 32-bit»; PE32 без CLI-заголовка или не IL-only — x86) уведомлением с тем, что
  поменять в проекте, ещё до запуска адаптера; attach к WOW64-процессу (`IsWow64Process`) — так же. В площадке `Prefer32Bit` = false.
- Attach к net4x: ключ `dotnet.debugger.attach.netFramework` снят, процессы предлагаются на Windows; проверено роботом (attach, клик, стоп,
  detach — процесс жив).
- Тесты SDK-проекта `net481` (VSTest): Debug теста останавливается на точке в тесте (attach к `testhost`, он 64-битный). Тесты legacy-проектов —
  п. 1 (их запуска ещё нет вовсе).
- Не сделано: run configuration «.NET Executable» для чужого exe; кнопка «выключить Prefer 32-bit» в уведомлении (правка `.csproj`).

### 5. Крупное (этап 4 ROADMAP)
- Legacy-модель проекта: новые файлы → `<Compile Include>` в `.csproj`, удаление / переименование с правкой проекта (4–5 дней).
- NuGet Install / Update / Remove для `packages.config`: правка `packages.config` + `<Reference HintPath>` + restore.
- IIS Express и ASP.NET на `System.Web` (3–5 дней).
- По желанию: binding redirects в `app.config` / `web.config`, трансформации `Web.Release.config`.

## Что поставить для проверок
- Web-workload Build Tools 2022 («ASP.NET and web development build tools») — проверить цели `WebApplications` через `VSToolsPath`.
- .NET Framework 4.8 Developer Pack — сейчас только targeting pack 4.8.1, проект на `v4.8` падает с MSB3644.

## Факты, найденные по ходу (2026-10-04)
- `dotnet build` legacy WPF не компилирует XAML и падает с CS5001 (нет `Main`); `MSBuild.exe` Build Tools собирает чисто. Простой консольный
  legacy-проект SDK собирает.
- `MSBuild.exe` 17.14 пишет вывод в UTF-8 и при запуске из JVM без окна; формат строк ошибок при `-m -v:m` тот же, что у `dotnet build`.
  По умолчанию его подробность — normal (у `dotnet build` minimal), параллельность и restore — только с `-m` / `-restore`.
- Одиночный проект с `packages.config` и `-restore` без `SolutionDir` падает: «Решение не найдено… /p:SolutionDir». Завершающий `\` в
  `-p:SolutionDir=…\` командная строка платформы удваивает перед кавычкой (`CommandLineUtil`), MSBuild получает путь как есть.
- `vswhere -all -prerelease -products * -requires Microsoft.Component.MSBuild -format json -utf8`; у Build Tools на машине разработки
  `isComplete: false`, но MSBuild рабочий. Папка `MSBuild\Microsoft\VisualStudio\v18.0` есть без VS 18 (остаток Python Tools) — `VSToolsPath`
  берётся по major-версии установки, не по самой новой папке.
- Run: то, что «Build .NET Project» кладёт в `ExecutionEnvironment`, до Run-state не доходит (2026.1) — путь exe берётся у MsBuildHost заново.
  У Debug так же: **двойная сборка подтверждена** роботом 2026-10-05 (каждый Debug, не только legacy: задача собирает, `DotNetDebugRunner`
  не видит её user data и собирает снова). `executionId` окружения у задачи и у runner'а один и тот же — по нему передаётся путь
  (`BuiltBeforeLaunch`, 0.1.69); после этого в журнале одна сборка.
- Консоль программы .NET Framework пишет в OEM-кодировке (`GetOEMCP`; на машине разработки 437 при русской локали) — кириллица теряется
  в самой программе. Вариант с `chcp 65001` через `cmd /c` отвергнут: экранирование аргументов через `cmd` и дерево процессов.
- Мягкий Stop платформы — Ctrl+C; окну (`WinExe`, подсистема PE = 2) он не доходит, процесс остаётся жить, а вкладка пишет «Process finished».
- Запуск из Services не виден `RunContentManager` и `ExecutionManager.getRunningProcesses()` (скрипты робота `run_consoles.js`, `stop_all.js`);
  Stop там — кнопка `//div[@myaction='Stop (Stop the process)' and contains(@myaction.key,'RunDashboard')]`.
- Кириллица в JS-скриптах робота до IDE не доходит (аргументы конфигурации сохранились пустыми) — в скриптах латиница.

## Факты, найденные по ходу (2026-10-05, отладка)
- `dotnet-debugger` 0.2.0 на площадке (прогон DAP без IDE, `netfx-probe`): `launch` до остановки на точке — 0,4–0,6 с, `attach` — 0,15 с, шаги —
  10–15 мс, Evaluate — 2–130 мс. Windows-PDB (`DebugType` full) читаются. Вывод кириллицы под отладчиком цел (адаптер переключает консоль).
- MSBuild по умолчанию делает AnyCPU-программу net4.5+ «Prefer 32-bit» (`corflags` 0x20003) даже без `Prefer32Bit` в проекте — так собрана
  площадка до 0.1.69, адаптер отказывал ей: «'LegacyConsole.exe' runs as a 32-bit process, which dotnet-debugger cannot debug…». SDK-проект
  `net481` (`OutputType` Exe) — AnyCPU без предпочтения (0x1), 64-битный.
- `ProcessInfo.executableCannonicalPath` платформы на Windows пустой — путь берётся у `ProcessHandle.info().command()`.
- Адаптер: в Evaluate не видны `using` файла для типов из сборок пакетов (`JsonConvert` → «The name 'JsonConvert' does not exist», полное имя
  работает; `Encoding` из `using System.Text` — работает) — и на .NET 10 тоже, не только net4x. Инициализатор коллекции (`new List<int> { 1, 2 }`)
  не поддержан («Only member initializers are supported»). У `process` при attach `name` — PID строкой, а не имя exe.
