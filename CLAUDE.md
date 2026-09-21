# idea-dotnet-support

Плагин «C# Project Support» (`io.github.dotnetsupport`) для IDE на платформе IntelliJ (GoLand, PyCharm, WebStorm, IDEA...):
Rider-подобная работа с .NET **без LSP, Roslyn и (пока) отладчика**. Всё держится на `dotnet` CLI, файлах проектов
(`.sln`/`.slnx`, MSBuild, `project.assets.json`) и лексере C#. Парсера C# нет — «понимание» кода делается эвристиками по токенам.

Планы и статус: `ROADMAP.md` (чек-лист фич, ведётся по-русски), `DAP_PLAN.md` (отладка через DAP, начат слой 1),
`LSP_PLAN.md`. `dap-probe/` — питоновские эксперименты с отладчиком, к сборке плагина не относятся.

## Сборка и проверка

Системных JDK и Gradle нет. Wrapper запускать с JBR GoLand (Git Bash):

```sh
export JAVA_HOME="C:\Program Files\JetBrains\GoLand 2025.1.3\jbr"
./gradlew.bat test buildPlugin -q      # основная проверка перед тем, как сказать «готово»
./gradlew.bat compileKotlin -q         # быстрая проверка компиляции
./gradlew.bat test --tests "io.github.dotnetsupport.NuGetTest" -q
```

- Целевая платформа — локальный GoLand 2025.1.3 (`localIdePath` в `gradle.properties`), ничего не скачивается. `sinceBuild = 251`.
- Gradle 9.7.1, Kotlin 2.3.21, но `apiVersion`/`languageVersion` = **2.1** (stdlib берётся из платформы) — не использовать API Kotlin новее 2.1.
- Упавшие тесты: `build/test-results/test/TEST-*.xml` (grep по `<failure`), отчёт — `build/reports/tests/test/index.html`.
- `runIde` и любой GUI агент проверить не может: после изменений в UI просить пользователя посмотреть вживую и прямо говорить, что не проверено.
- Если `compileKotlin` висит минутами и падает с `OutOfMemoryError` — это почти наверняка конструкция в новом коде,
  на которой зацикливается вывод типов (так было с рекурсивной локальной функцией `fun run(): Unit = x.run(...) { run() }`), а не нехватка памяти. Переписать проще, `./gradlew.bat --stop`, повторить.

## Устройство

`src/main/kotlin/io/github/dotnetsupport/`, пакет = область:

| Пакет | Что там |
|---|---|
| `solution` | модель и парсеры `.sln`/`.slnx`, `SolutionService` (solutions, `msBuildProject(file)`, assets, central package versions) |
| `msbuild` | типы файлов MSBuild/XML, `DotNetProjects.findOwningProject`, target frameworks |
| `view` | панель Solution (узлы, ключи `SolutionKey`/`ProjectKey`/…, Dependencies, nesting, Show All Files) |
| `actions` | действия над solution; `SolutionContext` — что выбрано в дереве (`fromSelection`, `buildTarget`) |
| `cli` | `DotNetCli` (поиск `dotnet`, `commandLine`, `execute`, `runInBackground`, нотификации), `DotNetTool` — глобальные tools |
| `build` | Build/Rebuild/Clean/Restore → Build tool window, разбор вывода MSBuild, конфигурация Debug/Release + TFM |
| `run` | run configuration «.NET Project», producer, автогенерация по `launchSettings.json`, Run/Debug проекта, консольные фильтры |
| `testing`, `coverage` | `dotnet test`, Unit Tests explorer, покрытие |
| `nuget` | окно NuGet (Packages / Sources / Folders / Log), `NuGetService`, клиент V3-фидов, действия меню NuGet |
| `format` | CSharpier / `dotnet format` за Reformat Code |
| `monitor`, `endpoints`, `upgrade`, `sdk`, `settings`, `templates`, `newproject`, `lang` | по названию |

Регистрация всего — `src/main/resources/META-INF/plugin.xml`. Действия:
- `DotNet.MainMenu` — меню **.NET** в главной строке меню (после Tools), в нём подменю `DotNet.NuGet`, `DotNet.EfCore`;
- `DotNet.SolutionViewPopup` — ПКМ в панели Solution; действия объявляются здесь, в главное меню попадают через `<reference>`
  (группа с объявлением должна идти в файле раньше ссылки);
- настройки — Settings | Tools | .NET (`DotNetSettingsConfigurable`) и дочерние страницы по образцу Rider (Toolset and Build, NuGet, Coverage,
  Debugger), плюс Editor | Code Style | C#. Страницы повторяют Rider опция в опцию: опцию, за которой у плагина пока ничего нет,
  не выбрасывать, а показывать выключенной с замком и причиной (`Unavailable.*` в `settings/RiderSettingsUi.kt`); когда появляется
  реализация — заменить на настоящую привязку. Есть тест на состав страниц (`SettingsPagesTest`).

## Соглашения кода

- Kotlin, строки до ~180 символов, плотный стиль; однострочные функции-выражения — норма.
- Комментарии и KDoc — **по-английски**, короткие, объясняют «почему» (часто с отсылкой «as in Rider»). `ROADMAP.md` и общение с пользователем — по-русски.
- Тексты UI — английские, в Title Case для действий, формулировки сверять с Rider.
- Действия: `AnAction(), DumbAware`, `getActionUpdateThread() = BGT`; в `update` обычно `isEnabledAndVisible`.
  В контекстном меню неподходящее скрывать, в главном — оставлять видимым, но выключенным (`e.isFromContextMenu`).
- Команды `dotnet`: строить через `DotNetCli.commandLinesOrNotify { DotNetCli.commandLine(dir, ...) }`, запускать `DotNetCli.runInBackground`
  (вывод — в Build tool window или свой `CommandOutput`, напр. `NuGetService.log`). Блокирующие `DotNetCli.execute` — только не на EDT;
  результат на EDT через `invokeLater(..., ModalityState.any())` с проверкой `project.isDisposed`.
- Парсинг вывода CLI и файлов — чистыми функциями (`NuGetResponses`, `MsBuildOutputParser`, …), чтобы тестировать без процесса.
- Схема MSBuild-файлов — наша, составная: `resources/msbuildSchema/<фрагмент>.json` + список в `MsBuildSchema.FRAGMENT_FILES` (порядок = приоритет при
  совпадении имён). Фрагмент на инструмент сообщества: `packages` / `sdks` — когда он считается активным для проекта. `values: "bool"` или список;
  `open: true` — значения только подсказка, без предупреждения (ставить всегда, когда перечисление может пополниться). Структура файла
  (Project / Target / задачи / их атрибуты) — в коде `MsBuildSchema`, она не растёт вместе с экосистемой.
- Новую фичу отмечать в `ROADMAP.md`; при переносе пунктов меню править упоминания путей («меню .NET → …») там же и в KDoc.

## Тесты

`src/test/kotlin/io/github/dotnetsupport/*Test.kt`, JUnit 3-стиль на `BasePlatformTestCase` (`fun testXxx()`), файлы через `myFixture.addFileToProject`.
- Light-проект **общий для тестов класса и между классами**: run configurations, файлы с тем же путём и т.п. переживают тест.
  Брать уникальные имена проектов и убирать за собой то, что регистрируется (`RunManager`, listeners).
- Реальный `dotnet` в тестах не запускать; HTTP подменяется (`NuGetClient(fetch = ...)`).
- Tool window в тестах — `ToolWindowHeadlessManagerImpl.MockToolWindow`, disposable освобождать вручную.
- Есть тесты на состав UI (вкладки окна NuGet, состав меню) — при изменении состава обновлять их.

## Git

Коммитить только по просьбе. В рабочем дереве обычно лежит незакоммиченная работа пользователя по нескольким фичам сразу — чужие изменения не трогать и не откатывать.
