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

### 4. Отладка .NET Framework (этап 3 ROADMAP, ждёт адаптера пользователя)
Debug exe напрямую (`program` = `TargetPath`), выбор адаптера по разрядности (`PlatformTarget`, `Prefer32Bit`, PE), attach к net4x
(снять ключ реестра `dotnet.debugger.attach.netFramework`), отладка тестов. Сценарии `// BP:legacy-console`, `// BP:legacy-wpf-click` уже в площадке.
Run configuration «.NET Executable» для чужого exe.

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
  У Debug так же? `DotNetDebugRunner` в этом случае собирает заново (`buildNow`) — **проверить, нет ли двойной сборки при Debug**.
- Консоль программы .NET Framework пишет в OEM-кодировке (`GetOEMCP`; на машине разработки 437 при русской локали) — кириллица теряется
  в самой программе. Вариант с `chcp 65001` через `cmd /c` отвергнут: экранирование аргументов через `cmd` и дерево процессов.
- Мягкий Stop платформы — Ctrl+C; окну (`WinExe`, подсистема PE = 2) он не доходит, процесс остаётся жить, а вкладка пишет «Process finished».
- Запуск из Services не виден `RunContentManager` и `ExecutionManager.getRunningProcesses()` (скрипты робота `run_consoles.js`, `stop_all.js`);
  Stop там — кнопка `//div[@myaction='Stop (Stop the process)' and contains(@myaction.key,'RunDashboard')]`.
- Кириллица в JS-скриптах робота до IDE не доходит (аргументы конфигурации сохранились пустыми) — в скриптах латиница.
