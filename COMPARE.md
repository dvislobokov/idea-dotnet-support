# C# Project Support vs JetBrains Rider — полное сравнение

Дата: 2026-09-26. Плагин `io.github.dotnetsupport` (C# Project Support), версия из `gradle.properties`, целевая платформа IntelliJ 2026.1
(`sinceBuild = 261`). Rider — 2026.1 (актуальный на дату). Источник фактов — код плагина (172 файла Kotlin, ~23 100 строк, 43 тестовых класса),
`plugin.xml`, `ROADMAP.md`, `DAP_PLAN.md`, `LSP_PLAN.md`; всё в документе сверено с реализацией, а не только с чек-листами.

Целевая аудитория сравнения — разработчики на C# всех профилей, **кроме Unity и Unreal**: backend / ASP.NET Core, микросервисы и контейнеры,
desktop (WPF / WinForms / Avalonia / MAUI), библиотеки и NuGet-авторы, data / EF Core, QA / тестирование, DevOps / CI, legacy .NET Framework.

## Содержание
1. [Как читать](#1-как-читать)
2. [Сводка](#2-сводка)
3. [Архитектура: чем плагин принципиально отличается от Rider](#3-архитектура)
4. [Сравнение по областям](#4-сравнение-по-областям)
   - 4.1 Solution, проекты, панель Solution
   - 4.2 Редактор C#: слой без сервера (эвристики)
   - 4.3 Редактор C#: слой Roslyn language server
   - 4.4 Рефакторинги, генерация кода, инспекции
   - 4.5 Другие языки и файлы (Razor, XAML, F#, MSBuild, JSON, .resx, .http, .editorconfig)
   - 4.6 Сборка
   - 4.7 Запуск и run configurations
   - 4.8 Отладчик
   - 4.9 Тесты и покрытие
   - 4.10 NuGet и зависимости
   - 4.11 Профилирование и диагностика
   - 4.12 EF Core и данные
   - 4.13 ASP.NET Core и web
   - 4.14 Публикация, контейнеры, облако
   - 4.15 SDK, инструменты, окружение
   - 4.16 Настройки
   - 4.17 Что даёт хост-IDE бесплатно
5. [Итог: что готово](#5-что-готово)
6. [Итог: что не готово — по приоритетам и по профилям разработчиков](#6-что-не-готово)
7. [Шпаргалка на каждый день](#7-шпаргалка-на-каждый-день)
8. [Ограничения, оговорки, «не проверено вживую»](#8-ограничения-и-оговорки)
9. [Приложение: карта пакетов, окон, действий, тестов](#9-приложение)

---

## 1. Как читать

| Знак | Значение |
|---|---|
| ✅ | есть, работает, сверено с кодом (и, где отмечено, проверено в живой IDE) |
| 🟡 | есть частично / с оговоркой (описана в колонке «Комментарий») |
| ❌ | нет |
| ➖ | не нужно плагину: даёт сама хост-IDE (IDEA / GoLand / PyCharm / WebStorm) |
| 🚫 | сознательно вне рамок (нужна семантика языка внутри IDE, либо решение пользователя) |
| 🔬 | код есть, но вживую не проверено (см. раздел 8) |

Колонка **Rider** описывает, что есть в Rider; колонка **Плагин** — что есть здесь. Там, где Rider делает что-то внутри своей семантической модели
(ReSharper), а плагин — через внешний процесс (`roslyn-language-server`, `dotnet` CLI, `dotnet-*` tools), это отмечено.

---

## 2. Сводка

**Что это.** Плагин делает IDE на платформе IntelliJ (IDEA, GoLand, PyCharm, WebStorm) похожей на Rider для .NET-проектов **без собственного
парсера C# и без ReSharper**: панель Solution, сборка, запуск, отладчик (свой DAP-клиент к `dotnet-debugger`), тесты, NuGet, EF Core,
мониторинг, а семантику C# (ошибки, completion, навигация, рефакторинги) даёт `roslyn-language-server` через платформенный LSP-клиент.
Когда сервера нет или он грузится, работают эвристики по токенам (раскраска, Structure view, folding, отступы, Go to Class).

**Цифры.**
- 24 пакета Kotlin, самые крупные: `lang` (2 420 строк), `roslyn` (2 090), `monitor` (1 970), `ef` (1 907), `nuget` (1 853), `debugger` (1 732).
- 7 tool windows (Solution view, NuGet, Unit Tests, .NET Monitor, EF Core, Endpoints, .NET Coverage), 1 run configuration, меню **.NET** с 40+ действиями,
  6 страниц настроек, 33 live templates, 30 генераторов New → .NET, схема MSBuild ~283 свойства / ~44 item-а из 12 фрагментов, 29 правил отступов.
- 43 тестовых класса; реальные `dotnet` / сервер / адаптер в тестах не запускаются (HTTP и процессы подменяются).

**Главный вывод.** По ежедневным операциям «открыть solution → писать код → собрать → запустить → отладить → прогнать тесты → поставить пакет»
плагин закрывает **основной сценарий** для backend / консольных / тестовых / библиотечных проектов, причём отладчик и NuGet — на уровне,
близком к Rider. Разрыв с Rider — в трёх местах:
1. **Глубина работы с кодом**: у Rider своя семантическая модель (2 500+ инспекций, 60+ рефакторингов, форматтер, Code Cleanup, Solution-Wide Analysis,
   Postfix templates, генерация Alt+Insert, иерархии типов и вызовов). У плагина — то, что умеет Roslyn LS (компилятор + анализаторы + code actions Roslyn +
   rename) и платформа LSP-клиента (нет call/type hierarchy, нет Structural Search, нет своих инспекций).
2. **Языки помимо C#**: Razor / Blazor / XAML / F# / VB — только иконки, шаблоны и XML; ни одного языкового сервера для них.
3. **Публикация и инфраструктура**: Publish, Docker / контейнеры из IDE, Aspire, Azure, remote debug, Hot Reload — нет.

---

## 3. Архитектура

| Аспект | Rider | Плагин |
|---|---|---|
| Модель кода | ReSharper (собственный парсер и семантика C#, VB, F#, Razor, XAML, MSBuild…), бэкенд-процесс на .NET | **Парсера нет.** Лексер C# + сканер объявлений по токенам (`lang/CSharpDeclarations`) → PSI-узлы на объявления; семантика — `roslyn-language-server` (внешний dotnet tool, .NET 10) через `intellij.platform.lsp` |
| Где лежит «правда» о проекте | MSBuild-движок внутри бэкенда (evaluation, conditions, imports) | Статичное чтение XML `.csproj` (без условий, без `$(…)`, без imports) + `obj/project.assets.json` после restore. Точные значения (TargetPath, targets) — через `dotnet msbuild -getProperty` / `-targets` |
| Сборка | ReSharper Build (инкрементальная эвристика) или MSBuild in-process | `dotnet build` как процесс, вывод в Build tool window |
| Отладчик | Собственный (ICorDebug, .NET Framework, Mono, native, Blazor WASM, remote) | Свой DAP-клиент (`debugger/DapConnection`) + `dotnet-debugger-dap` (MIT, ICorDebug, только .NET Core / 5+) на платформенном XDebugger API |
| Форматтер | Внутри IDE, все опции Code Style | CSharpier / `dotnet format` / onTypeFormatting сервера; страница Code Style — только отступы |
| Тесты | Собственный раннер с live-протоколом | `dotnet test` + TRX → результаты после завершения процесса |
| Профилирование | dotTrace / dotMemory / DPA внутри | `dotnet-counters`, `dotnet-stack`, `dotnet-gcdump`, `dotnet-dump` как внешние tools |
| Где работает | Только Rider | Любая IDE на платформе 2026.1+, включая IDEA Community (отладчик не зависит от платформенного DAP); Roslyn-клиент — content-модуль, включается там, где есть `intellij.platform.lsp.impl` |
| Логи | Rider log, backend log | `~/idea-dotnet-logs`: `commands/` (каждая команда `dotnet`), `dotnet-debugger/`, `roslyn-language-server/`, `DotNetBuild/` |

Следствие: всё, что в Rider «мгновенно и в процессе», здесь — «через процесс и после restore/сборки». Всё, что в Rider требует семантики,
здесь — либо Roslyn LS, либо эвристика с явным правилом «эвристика только предлагает, никогда не источник ошибок».

---

## 4. Сравнение по областям

### 4.1 Solution, проекты, панель Solution

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Открыть `.sln` | ✅ | ✅ | Regex-парсер: `Project(...)`, solution folders (GUID `2150E333…`), `SolutionItems`, `NestedProjects`, `SolutionConfigurationPlatforms` (конфигурации, платформы отброшены) |
| Открыть `.slnx` | ✅ | ✅ | XML: `Project`, `File`, `Folder` (вложенность по `/`), `Configurations/BuildType` |
| Solution filters `.slnf` | ✅ | ✅ | 2026-09-26: `.slnf` — узел в панели («AppOnly (1 of 4 projects)»), фильтр по `projects` с обрезкой пустых solution folders; Build / Run / Test / Add Project Reference работают, действия `dotnet sln` (Add / Remove / New Folder / New Project) под фильтром скрыты. Тест `SolutionDiscoveryTest` |
| Несколько solution в папке | ✅ (один открыт) | ✅ | 2026-09-26: `SolutionFinder` ищет `.sln` / `.slnx` / `.slnf` по всей папке (мимо `bin`, `obj`, `node_modules`, `packages`, `wwwroot`, dot-папок), корневые первыми; результат кэшируется до появления / исчезновения файлов; language server ходит тем же поиском |
| Папка без solution | ✅ (Open Folder) | 🟡 | дерево обычное, LS — `--autoLoadProjects` / `project/open` 🔬 |
| Дерево: solution → folders → проекты → файлы | ✅ | ✅ | `SolutionViewPane` (id `DotNetSolutionView`), проект с TFM в подписи, «not found» для отсутствующего |
| Dependencies узел | ✅ | ✅ | по каждому TFM: Imports (Sdk.props/targets, `Directory.Build.*`, `Directory.Packages.props`, явные `<Import>`), Packages (разрешённые версии + транзитивные, циклы обрезаны), Projects, Assemblies (`<Reference>`), Analyzers (dll из `analyzers/`), Frameworks с reference-сборками |
| Dependencies: транзитивные проекты | ✅ | ✅ | 2026-09-28: узел проекта в Projects раскрывается в его собственные `ProjectReference` (по `.csproj`, без restore), цикл не раскрывается второй раз. Тест `ProjectActionsTest` |
| Dependencies: Source Generators отдельным узлом, вложенные imports внутри Sdk.props / Sdk.targets, анализаторы самого SDK | ✅ | ❌ | генераторы неотличимы от анализаторов без загрузки сборок |
| Показ `bin`/`obj`/файла проекта | Show All Files | ✅ | «глаз» в заголовке окна Project и пункт в настройках view; окрашены цветом «ignored» |
| File nesting | ✅ | ✅ | `appsettings.*.json`, `.config` → `.Debug/.Release.config`, `.razor` → `.razor.cs/.css/.js/.scss`, `.cshtml` → `.cs/.css/.js`, `.xaml/.axaml` → `.cs`, `.resx/.settings` → `.Designer.cs`, `.cs` → `.g.cs/.generated.cs/.Designer.cs` |
| Честное содержимое проекта (`Compile Remove`, linked files, `DependentUpon`) | ✅ | ✅ | 2026-09-26: `ProjectContent` поверх файлов на диске — скрыты файлы и папки, вынутые из глобов SDK по умолчанию (`<Compile Remove>` для `.cs`, `<EmbeddedResource Remove>` для `.resx`, `<Content Remove>` для `wwwroot` в web-SDK, `<None Remove>` для остального, `DefaultItemExcludes`); Show All Files возвращает их серым; `<DependentUpon>` вкладывает файл под родителя в той же папке (`Schema.xsd` → `Schema.Designer.cs`, `Login.resx` → `Login.ru.resx`); файлы из-за пределов папки проекта (`Include="..\Shared\**\*.cs" LinkBase="Shared"`, `Link="Ext\One.cs"`, `%(RecursiveDir)`) показаны по пути Link с бейджем ссылки и подписью «откуда». Условия и `$(…)` не вычисляются; виртуальная Link-папка с тем же именем, что реальная, показывается отдельным узлом. Тест `ProjectContentTest` |
| Add → New Project (в solution / solution folder) | ✅ | ✅ | диалог «New .NET Project»: Name, Template (из `dotnet new list --type project`, fallback 11 встроенных), «More templates…», Language, Framework (из `--list-sdks`), Location |
| Add → Existing Project | ✅ | ✅ | `dotnet sln add [--solution-folder]` |
| Add → New Solution Folder | ✅ | ✅ | правка текста `.sln`/`.slnx` (у CLI нет команды) |
| Add → Project Reference | ✅ | ✅ | диалог с галочками, `dotnet add/remove reference` |
| Add → Assembly Reference (dll по пути) | ✅ | ✅ | 2026-09-28: Add → Assembly Reference… — выбор `.dll`, `<Reference Include><HintPath>` относительным путём в новой `ItemGroup`, форматирование файла сохраняется. Тест `ProjectActionsTest` |
| Add → COM / Framework (GAC) reference, Browse по GAC | ✅ (Windows) | ❌ | |
| Remove from Solution | ✅ | ✅ | `dotnet sln remove`, файлы остаются |
| Rename проекта | ✅ | ✅ | 2026-09-28: ПКМ проекта → Rename Project… (2026-09-29: и **F2 / Shift+F6** на узле — `renameHandler`) — файл проекта, по галочке папка, запись в `.sln` / `.slnx`, `ProjectReference` остальных проектов, run configurations; одна отменяемая команда. **Delete** на узле проекта = Remove from Solution с вопросом (файлы не трогаются). AssemblyName / RootNamespace не меняются. Тесты `ProjectActionsTest`, `ServicesAndNodeActionsTest` |
| Move проекта в другой solution folder / на диске, Unload / Reload | ✅ | ❌ / 🚫 | move — через Remove + Add Existing (или правка `.sln`); unload не имеет смысла без per-project модели: код грузит только language server целым solution |
| Drag-and-drop в дереве | ✅ | 🟡 | только перетаскивание узла solution/проекта в редактор (открывает файл); перестановок/переносов нет |
| Edit '`App.csproj`' / '`App.slnx`' | ✅ | ✅ | ПКМ узла |
| Project Properties (диалог) | ✅ | ✅ | 2026-09-26: ПКМ проекта → Properties… — вкладки Application (target frameworks, AssemblyName, RootNamespace, OutputType, LangVersion, Nullable, ImplicitUsings, StartupObject), Build (TreatWarningsAsErrors, WarningsAsErrors, NoWarn, GenerateDocumentationFile, AllowUnsafeBlocks, EnableNETAnalyzers, AnalysisLevel, EnforceCodeStyleInBuild, InvariantGlobalization, SatelliteResourceLanguages), Package (PackageId, Version, Authors, Company, Description, License, URLs, Tags, README, GeneratePackageOnBuild, IsPackable). Пишет в безусловную `PropertyGroup` правками документа — отступы и остальной файл не трогаются; пустое значение = убрать свойство (умолчание SDK). Условных групп (Debug / Release, платформы) не редактирует. Тест `ProjectPropertiesTest` |
| Reload Project / Reload Solution | ✅ (и Unload Project) | ✅ 🔬 | 2026-09-29: меню .NET, ПКМ на узле, кнопка в заголовке Solution view — перечитывает папку с диска, сбрасывает кэши файлов проектов, перерисовывает дерево; сервер перезапускается (solution) или получает уведомление об изменении файла проекта (project). Unload Project нет. Вживую не проверено |
| Конвертация `.sln` → `.slnx` | ✅ | ✅ | `dotnet sln migrate`, предложение удалить старый |
| Convert `.slnx` → `.sln` | ✅ | ❌ | в CLI нет |
| Автопереключение на Solution view при первом открытии | ✅ (единственный view) | ✅ | один раз, настройка |
| New Solution (мастер IDE) | ✅ | ✅ | GoLand / PyCharm / WebStorm — `DirectoryProjectGenerator` «.NET»; IntelliJ IDEA — 2026-09-28 `GeneratorNewProjectWizard` «.NET» (имя, папка, Git + Template / Language / Framework / «same directory»), включён только в IDEA, чтобы не дублировать запись в других IDE. Плюс меню .NET → New .NET Project. Тест `NewProjectWizardTest`; **вживую в IDEA не проверен** |
| Solution-wide Configuration Manager | ✅ | ❌ | только переключатель Debug/Release + TFM в тулбаре |
| Иконки файлов .NET | ✅ | ✅ | cs/csx, fs/fsx/fsi, vb, sln/slnx/slnf, csproj/fsproj/vbproj/props/targets, razor/cshtml, xaml/axaml, resx, config/runsettings/ruleset, nupkg/nuspec, dll/exe/pdb, `nuget.config`, `packages.lock.json`, `Directory.Packages.props`, `global.json`, `launchSettings.json`, `appsettings*.json`; новая тема (expui) для иконок окон |

### 4.2 Редактор C#: слой без сервера (эвристики)

Работает всегда: в файлах вне solution, пока сервер грузится, в IDE без LSP-модуля.

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Лексер, подсветка синтаксиса | ✅ | ✅ | ручной `LexerBase`: verbatim, интерполяция с вложенными строками, raw `"""` (интерполяция в raw не разбирается на holes), `@identifier`, `///` vs `////`, hex/`_`/экспонента; препроцессор — одна лексема на строку |
| Раскраска типов / методов / членов | семантическая | 🟡 | эвристика по токенам (`после new/is/as`, `Foo bar`, `List<T>`, `Console.`, атрибуты, базовый список); локальные / параметры не красятся; уступает semantic tokens сервера |
| Палитра Rider (светлая / тёмная), страница Color Settings | ✅ | ✅ | 18 ключей: keyword, type, method, member, string, number, 3 вида комментариев, preprocessor, скобки/операторы/точка/запятая/точка с запятой |
| Комментирование, скобки, кавычки | ✅ | ✅ | `//`, `/* */`, brace matcher, quote handler |
| Structure view / File Structure | ✅ | ✅ | по сканеру объявлений: namespace (в т.ч. file-scoped), class/struct/interface/enum/record/delegate, конструкторы, методы, операторы (в т.ч. conversion, деструктор), свойства, индексаторы, поля (первый декларатор), события, enum-члены; иконки + видимость; сортировка только A-Z |
| Structure view: фильтры (inherited, visibility), сортировка по видимости | ✅ | ❌ | |
| Breadcrumbs, sticky lines | ✅ | ✅ | |
| Folding | ✅ | ✅ | тела объявлений, блок `using`, `#region` с именем, серии `///` и `//`, `/* */`; по настройкам платформы; при готовом сервере — уступает LSP folding |
| Go to Class / Symbol | ✅ | ✅ | `FileBasedIndex` по именам типов и членов; без namespace-квалификации; при готовом сервере файлы solution отдаёт `workspace/symbol`, индекс — остальное |
| Live templates | ✅ (шаблоны ReSharper) | ✅ | 33: `ctor` (имя типа макросом), `prop/propg/propi/propr/propfull`, `cw`, `for/forr/foreach/while/do`, `if/else/switch`, `try/tryf/using/lock/thr`, `svm/sam`, `class/interface/record/struct/enum`, `fact/theory/test/testm`, `region`, `nn` |
| Postfix templates (`.if`, `.foreach`, `.var`, `.return`…) | ✅ | ✅ | 2026-09-29: 21 шаблон по токенам (выражение перед точкой — `CSharpExpressions`: цепочки имён, вызовы, индексаторы, литералы, `new`, `await`, `!`, `?.`): statement-шаблоны `if` `else` `null` `notnull` `while` `lock` `switch` `foreach` `using` `var` `return` `throw` `yield` `cw` — только в начале statement; expression-шаблоны `not` `par` `await` `nameof` `typeof` `new` `str` — везде. Тип выражения не известен — предлагаются все. Тест `PostfixAndSurroundTest` (в т.ч. раскрытие по Tab) |
| Surround with (Ctrl+Alt+T) | ✅ | ✅ | 2026-09-29: `if`, `if/else`, `while`, `for`, `foreach`, `try/catch`, `try/finally`, `try/catch/finally`, `using`, `lock`, `{ }`, `#region`, `#if` — целыми строками с отступом на единицу глубже; для выделения в строке — `(expr)`, `!(expr)`. Тест `PostfixAndSurroundTest` |
| Doc-комментарии: `///` → `<summary>` + `<param>` + `<returns>`, Enter продолжает `///` | ✅ | ✅ | |
| Enter в `/* */` | ✅ | ❌ | |
| Отступы при наборе (Enter, `{ } ) ]`) | форматтер | ✅ | движок на 29 JSON-правилах: K&R / Allman, аргументы и переносы, цепочки `.`, тела без скобок, `else/catch/finally`, `switch` (метки/секции/блоки), инициализаторы, атрибуты, `#region/#if`, `/* */`, verbatim/raw не трогаются; `csharp_indent_*` из `.editorconfig`; уступает onTypeFormatting сервера |
| Auto-Indent Lines, отступ вставленного фрагмента, `goto`-метки | ✅ | ❌ | |
| Code Style → C# | десятки вкладок | 🟡 | только Tabs and Indents (используются реально); Spaces / Wrapping / Blank Lines / Naming — нет (сознательно: «обещали бы форматтер») |
| TODO-индекс | ✅ | ✅ | только в комментариях (`//`, `///`, `/* */`), шаблоны из Settings \| Editor \| TODO |
| Spellchecker в идентификаторах / комментариях / строках | ✅ | ❌ | |
| Find Usages текстовый (WordsScanner) | ✅ | ❌ | usages — только через сервер (4.3) |
| Неактивные ветки `#if` серым | ✅ | ❌ | сканер считает все ветки активными |
| Переход между partial-частями, Related files (`.xaml`↔`.cs`, тест↔класс) | ✅ | ❌ | есть только генератор «Partial Part of Selected Type…» |
| Move `.cs` в другую папку → namespace по папке с обновлением ссылок | ✅ | ✅ | 2026-09-29 (по замечанию пользователя): `MoveFileHandler` для C# — после переноса вопрос «Change / Keep», затем рефакторинг Roslyn «Change namespace to '…'» (объявление + все usages в solution; сервер подхватывает файл с задержкой — до 6 попыток); без загруженного сервера меняется только объявление в файле и приходит нотификация. Тест `MoveFileTest` (fallback-путь); **серверный путь вживую не проверен** |
| Paste Special: JSON / XML → классы | ✅ | ❌ | |
| Инъекция RegExp / JSON в строки | ✅ | 🟡 | своей инъекции нет; сервер подсвечивает «related parts» regex / JSON строк (опция в настройках), Check RegExp платформы не работает |
| Insert New GUID | ✅ | ✅ | Generate-меню и меню .NET, мультикурсор, регистр по контексту `.sln` |
| Файлы New → C# Class / Interface / Record / Struct / Enum | ✅ | ✅ | namespace = RootNamespace + путь; file-scoped по `csharp_style_namespace_declarations` из `.editorconfig`, иначе по TFM (≥ .NET 6 — file-scoped) |
| New → .NET ▸ генераторы | Rider: свои шаблоны файлов | ✅ | 30 генераторов: C# (Exception, Attribute, Extensions, Delegate, Top-level Program, Partial Part), ASP.NET (API/MVC Controller, Minimal API Endpoints, Middleware, Action Filter, Background Service, Health Check, Options+Registration), Razor/Blazor (Component, Component+code-behind, Razor Page, View, Layout, `_Imports.razor`, `_ViewImports.cshtml`), Tests (xUnit/NUnit/MSTest class, Fixture, Test for Selected Class), EF Core (DbContext, Entity Type Configuration, Design-Time Factory, Migration…), Configuration (`appsettings.{Env}.json`, `launchSettings.json`, `global.json`, `nuget.config`, `Directory.Build.props/targets`, `Directory.Packages.props`, `.editorconfig`, `.gitignore`, `dotnet-tools.json`, Dockerfile, GitHub Actions workflow), Resources (`.resx`, копия `.resx` для культуры, XAML UserControl/Window), «From SDK Template…» (любой item-шаблон `dotnet new`) |
| Редактирование шаблонов пользователем | ✅ | ❌ | зашиты в плагин |
| Баннеры над файлом C# | ✅ (solution не загружен, файл вне проекта…) | ✅ 🔬 | 2026-09-29: нет `roslyn-language-server` (Install / Settings…), файл не входит ни в один проект, файл исключён из проекта (`Compile Remove`), проект не восстановлен (Restore), несколько solution — не выбран (Select Solution...); у каждого «Don't Show Again». Вживую не проверено |
| Alt+Enter без сервера: действия уровня файла | ✅ | ✅ 🔬 | 2026-09-29: Change namespace to match folder (с usages, когда сервер загружен), Move type to its own file, Rename file to match type, Add partial part, Create test — по сканеру объявлений, работают и пока сервер грузится. Вживую не проверено |

### 4.3 Редактор C#: слой Roslyn language server

Требует установленного `roslyn-language-server` (dotnet tool, .NET 10 runtime) и IDE с модулем `intellij.platform.lsp.impl` (в 2026.1 — есть во всех
IDE на платформе по данным `docs/platform-lsp-dap.html`; GoLand/PyCharm/WebStorm проверить — пункт ROADMAP). Сервер стартует с первым открытым `.cs`.

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Ошибки компилятора в редакторе | ✅ мгновенно | ✅ | pull-диагностика, только после `projectInitializationComplete` (нет ложных ошибок при загрузке); область — open files / full solution / none (настройка) |
| Диагностика анализаторов (Roslyn analyzers, `.editorconfig` severity) | ✅ + свои 2 500 инспекций | 🟡 | только анализаторы Roslyn проекта и IDE-анализаторы сервера; инспекций ReSharper нет |
| Ошибки в закрытых файлах / Solution-Wide Analysis / окно Problems по solution | ✅ | ✅ | 2026-09-29: Problems → **Project Errors** — ошибки и предупреждения всего solution из `workspace/diagnostic` (закрытые файлы включительно; hints анализаторов не попадают). Работает **только при «Compiler diagnostics for: fullSolution»** (Settings → .NET → Language Server; иначе одна нотификация с кнопкой Open Settings) — зонд 2026-09-29: сервер 5.12 иначе отвечает пустым списком. Модель как в VS Code: один вечный запрос, ответ приходит при изменениях (playground — 3,9 с, 33 файла); открытые документы Roslyn из него исключает — их ошибки берутся из `textDocument/diagnostic` редактора (обёртка сервера), так что вкладка полная. Тест `SolutionProblemsTest`; **вживую не проверено** |
| Completion | ✅ (smart, import, statement) | ✅ | 2026-09-29: порядок как в Rider — область видимости → методы → типы → ключевые слова (`PrioritizedLookupElement` по `CompletionItemKind`, `preselect` сверху).  Roslyn: члены, типы из неимпортированных namespace (авто-`using`, опция), имена для новых членов, regex, аргументы; после пробела перезапуск первой буквой (`RoslynCompletionRestart`); хвосты «`()  +18 overloads  void`» из resolve; `()` после метода + Parameter Info; `await` не залипает; `(` не открывает список |
| Parameter Info (Ctrl+P) | все перегрузки | ✅ | свой обработчик: все перегрузки списком, текущий параметр подсвечен, неподходящие серые; 2026-09-29 — у extension-методов список параметров берётся из последней группы скобок label (`(extension) …` больше не попадает в строки) |
| Лямбда там, где ждут делегат | ✅ (по типу параметра) | ✅ | 2026-09-29: по `signatureHelp` сервера — если параметр в позиции каретки `Func<…>` / `Action` / `Predicate<T>` / `Expression<Func<…>>` / `EventHandler` / `Comparison` / `Converter`, первым в списке completion идёт `serviceProvider => ` (и вариант с блоком), а после `(` / `, ` тот же текст показывается серым inline-текстом — Tab вставляет (`inline.completion.provider`). Имена параметров — из типов (`IServiceProvider` → `serviceProvider`, `TService` → `service`, `int` → `i`); активная перегрузка первой. Кастомные `delegate`-типы не распознаются. Тест `LambdaSuggestionsTest`; **вживую не проверено** |
| Серый текст продолжения (принимается Tab) | ✅ (часть — через completion и Full Line) | 🟡 🔬 | 2026-09-29, девять случаев, только когда продолжение одно. По токенам, без сервера (`CSharpGhostText`): `{ get; set; }` после `public string Name` (не для методов: глагол в имени, `Task`, `async`); `new();` после `= ` у объявления с известным типом (коллекции, `StringBuilder`, `object`, …; для `IList<T>` — `new List<T>();`; в проектах до .NET 5 — `new List<int>();`); `_name = name;` на пустой строке конструктора; `namespace ` → namespace папки (`;` или блок — по `.editorconfig`); `public class ` в файле без типов → имя файла (interface — только для `IName.cs`); `private readonly ILogger<` → `OrderService> _logger;`, в списке параметров — `OrderService> logger`; `public Order(` → параметры по readonly-полям и get-only свойствам без инициализатора; `catch` → `(Exception e)` (`ex`, если так принято в файле). По `signatureHelp` сервера: аргументы вызова — переменные с именами параметров (`Save(` → `order, cancellationToken`). При открытом списке completion подсказки по токенам не показываются. Тест `GhostTextTest`. Вживую не проверено |
| Парная `>` для `<` у generic | ✅ (по семантике) | ✅ 🔬 | 2026-09-29: `AddSingleton<` → `<|>`, набранная `>` перешагивает парную, Backspace на `<` убирает обе (`CSharpAngleBracketTypedHandler`). Generic от сравнения отличается по тексту: имя с заглавной буквы вплотную к `<`; `i < n`, `count<5`, строки и комментарии — без пары. Сравнение `Count<5` без пробелов сначала получает пару, и она снимается первым же символом, которого не бывает в списке типов (цифра, `=`, пробел не после запятой, `;`, оператор). Следует настройке Insert paired brackets. Тест `AngleBracketsTest`. Вживую не проверено |
| Скобки и `;` после метода из completion | ✅ | ✅ 🔬 | `WriteLine` → `WriteLine(|)` с Parameter Info; 2026-09-29: void-метод → `WriteLine(|);`, метод без параметров → курсор после вызова, набранная `;` перешагивает стоящую в конце строки. Тип метода известен, только если платформа успела запросить у сервера детали элемента (строка списка уже показывает сигнатуру); иначе — обычные `()`. Вживую не проверено |
| `<>` у generic из completion | ✅ | ✅ 🔬 | 2026-09-29: `List<>` → `List<|>`, после `new` — `new List<|>()`; `AddSingleton<>` → `AddSingleton<|>()`, но `Select<>` → `Select(|)`: скобки типа ставятся, только когда аргументы не выводятся из параметров. Решение — по сигнатуре первой перегрузки; элемент, детали которого ещё не запрошены, получает обычные `()`. Вживую не проверено |
| Аллокации в редакторе | 🟡 Heap Allocations Viewer: статические пометки «здесь аллокация» (упаковка, замыкание, `params`) без величины | 🟡 🔬 | 2026-09-29: **измеренные** байты и объекты в секунду, типы и доля — в конце строки, у объявления метода и полосой слева, пока программа запущена из IDE (меню .NET → Show Allocations in Editor). Это выборка событий среды, мелкое за короткое время не видно; статической причины (почему здесь аллокация) плагин не называет. Вживую не проверено |
| Регистр при completion | ✅ не учитывается | ✅ 🔬 | 2026-09-29: `writeli` находит `WriteLine` для всех элементов списка; настройка IDE «Match case» для C# не действует |
| Completion того, что не импортировано | ✅ (типы, extension-методы, статические члены; пакеты, которых нет в проекте) | 🟡 🔬 | 2026-09-29: статические члены типов проекта по голому имени из своего индекса сборок (`WriteLi` → `Console.WriteLine(|);` + `using`), работает без сервера и до загрузки solution. Типы и extension-методы из неподключённых namespace даёт сам сервер. Пакеты, которых нет в проекте, не предлагаются. Вживую не проверено |
| Порядок списка completion по контексту | ✅ (по семантике) | 🟡 🔬 | 2026-09-29: наверх идёт то, что подходит месту, — ожидаемый тип, имя как у параметра, объявленное рядом, выбиравшееся раньше. Типы кандидатов — только из текста текущего файла: для членов других типов (после точки) и для символов из других файлов тип неизвестен, работает совпадение имени. Счётчик принятия подсказок — меню .NET → Suggestion Statistics. Вживую не проверено |
| Quick Documentation (Ctrl+Q), hover | ✅ | ✅ | сервер (с remarks — опция) |
| Go to Declaration (Ctrl+B, Ctrl+клик) | ✅ | ✅ | в т.ч. в другой проект |
| Ctrl+наведение (подчёркивание, «рука») | ✅ | ✅ 🔬 | свой `implicitReferenceProvider`, 300 мс таймаут; «как выглядит под мышью — посмотреть руками» |
| Go to Type Declaration | ✅ | 🟡 | `typeDefinition` есть у сервера и кэшируется; отдельное действие не проверялось |
| Go to Implementation (Ctrl+Alt+B) | ✅ | ✅ | своё действие поверх платформенного: 0 / 1 / список |
| Type Hierarchy (Ctrl+H) | ✅ | ✅ | 2026-09-29: окно Hierarchy платформы на `prepareTypeHierarchy` / `supertypes` / `subtypes` сервера — три вида (supertypes, subtypes, полная), уровень за уровнем, циклы не раскрываются; строка — имя + контейнер, иконка по `SymbolKind`. Тест `RoslynHierarchyTest` (на захваченном трафике 42–44); **вживую не проверено** |
| Call Hierarchy (Ctrl+Alt+H) | ✅ | ✅ | 2026-09-29: callers / callees через `prepareCallHierarchy` / `incomingCalls` / `outgoingCalls`; рекурсия показывается один раз. Тест на фикстурах 39–41; **вживую не проверено** |
| Go to Base (Ctrl+U) / Derived | ✅ | 🟡 | 2026-09-29: Ctrl+U на **типе** — базовые типы из `typeHierarchy/supertypes` (один — переход, несколько — список); на члене сервер «базового члена» не даёт — подсказка. Derived = Go to Implementation (Ctrl+Alt+B) |
| Find Usages / Show Usages | ✅ (группировка, фильтры) | 🟡 | `references` через сервер; code lens «N references» → Show Usages; без группировки по типу использования, без «read/write» |
| Подсветка вхождений под кареткой | ✅ | ✅ | `documentHighlight` включён явно |
| Rename (Shift+F6) | ✅ (файлы, строки, комментарии, перегрузки) | ✅ 🔬 | LSP rename + переименование файла `<Тип>.cs` плагином; диалог не проверен роботом; Ctrl+Z — два шага |
| Code actions / quick fixes (Alt+Enter) | ✅ (ReSharper + Roslyn) | ✅ | Roslyn: quick fixes, рефакторинги, вложенные варианты (`nestedCodeAction`), **Fix All** в Document / Project / Solution / Containing member (`resolveFixAll`); дедупликация (рефакторинги только как context actions, fix только у своей диагностики) |
| Code lens | ✅ (usages, VCS, tests) | ✅ | «N references», «Run and debug tests» (опции); клик по references работает |
| Inlay hints (имена параметров, типы `var`, лямбды) | ✅ | ✅ | включены по умолчанию как в Rider: параметры у литералов/индексаторов/`new`, типы у `var` и лямбд; «всё остальное», `new()`, collection expressions — выключены |
| Semantic highlighting | ✅ | ✅ | semantic tokens → палитра плагина; дисковый кэш по содержимому файла — файл раскрашен сервером **до** загрузки solution |
| Folding, Structure (documentSymbol) | ✅ | ✅ | при готовом сервере — сервер; до загрузки — эвристики |
| Форматирование (Reformat Code) | ✅ (свой форматтер, все опции) | ✅ | `dotnet format`-режим → сервером (когда загружен); CSharpier — своим путём; None; onTypeFormatting на `;` `}` Enter |
| Organize usings при форматировании | ✅ | 🟡 | опция сервера, по умолчанию выключена |
| Декомпилированные исходники (Ctrl+клик в тип фреймворка) | ✅ (dotPeek внутри, Assembly Explorer, IL Viewer) | ✅ | MetadataAsSource сервера: read-only, вкладка `Console.cs [System.Console]`, баннер с dll; Source Link / embedded sources — опция |
| Assembly Explorer, IL Viewer, декомпиляция dll из Dependencies | ✅ | ❌ | в ROADMAP — `ilspycmd`, не начато |
| Source generators: просмотр сгенерированного кода | ✅ | 🟡 | сервер выполняет генераторы (Automatic/Balanced), файлов не показать |
| Статус сервера, перезапуск, лог | скрыто | ✅ 🔬 | свой виджет в статус-баре («Roslyn: loading X.sln / X.sln»), виден, пока жив процесс сервера, и без открытых файлов; по клику — состояние, CPU и память дерева процессов сервера, Restart / Log / Timings / Settings (2026-09-29, вживую не проверено; строки в платформенном виджете language services больше нет); меню .NET → Restart / Show Log / Timings / Select Solution. 2026-09-29: сервер стартует **при открытии проекта** (папка с solution / проектами), а не с первого `.cs` |
| Нет runtime .NET 10 / tool не установлен / нужен restore | — | ✅ | нотификации с Install / Download / Restore |
| Кэши и прогрев | — | ✅ | memo ответов (`codeAction`, `hover`, `inlayHint`, `definition`…, сброс на любое изменение), прогрев после загрузки (первый completion 42 мс вместо 153), тайминги |
| Хвосты фазы 1 | — | 🔬 | новый `.cs`, созданный после загрузки solution, — сервер следит за файлами сам, не проверено; file-based programs выключены по умолчанию |

### 4.4 Рефакторинги, генерация кода, инспекции

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Rename, Extract Method / Interface / Class / Base class, Inline, Move type to file / namespace, Change Signature, Introduce Variable / Field / Parameter, Safe Delete, Convert (property ↔ method, anonymous → named, …), Pull Up / Push Down | 60+ | 🟡 | только рефакторинги, которые Roslyn отдаёт как code actions: Rename, Extract method / interface / class, Introduce variable / constant / parameter / field, Inline, Move type to file / namespace, Change signature, Convert (foreach ↔ LINQ, switch ↔ expression, …), Generate, Pull up (базовые); вызываются через Alt+Enter, а не Refactor-меню; Ctrl+Alt+M / Ctrl+Alt+V и т.п. не привязаны |
| Alt+Insert: конструктор, свойства, Equals/GetHashCode, overrides, implement missing members, delegating members | ✅ | 🟡 🔬 | 2026-09-29: пункт «Generate...» первым в Alt+Insert (`RoslynGenerateAction`): code actions Roslyn в позиции курсора, отобранные по названию (Generate constructor / Equals / GetHashCode / overrides, Implement interface / abstract class, Add DebuggerDisplay, Extract interface), списком; то же остаётся в Alt+Enter. Своего диалога выбора членов нет — показывает сервер не всё (нет delegating members, нет выбора полей галочками). Вживую не проверено |
| Code Cleanup (профили) | ✅ | 🟡 | Fix All по solution для конкретного правила; `dotnet format` / CSharpier для всего проекта (меню .NET → Format) |
| Inspection severity, подавление, `.editorconfig` из UI | ✅ | 🟡 | из UI нет; в `.editorconfig` — руками, с completion и документацией опций (см. 4.5); подавление — code action Roslyn «Suppress or configure issues» |
| Structural Search & Replace для C# | ✅ | ❌ | |
| Naming rules, code style enforcement | ✅ | 🟡 | только что даёт сервер из `.editorconfig` |
| Unused code dimming, «unused using» | ✅ | ✅ | серые `using` и диагностики IDE0xxx от сервера |
| Обновление target framework по solution | ✅ (Upgrade Assistant внутри) | 🟡 | «Analyze Upgrade to Newer .NET…» — только анализ (4.15) |

### 4.5 Другие языки и файлы

| Файл / язык | Rider | Плагин | Комментарий |
|---|---|---|---|
| Razor / Blazor (`.razor`, `.cshtml`) | полный язык: completion, навигация, диагностика, tag helpers | ❌ (сервер умеет) | иконки, шаблоны файлов, nesting; редактор — plain text / HTML платформы. 2026-09-29 снят трафик (`tools/roslyn-lsp/capture_razor.py`): сервер 5.12 через Razor cohost отвечает для `.razor` / `.cshtml` на диагностику, completion, hover, signature help, definition / references, rename, code actions, semantic tokens, code lens, inlay hints, call hierarchy — C#-половина целиком. HTML-половину (теги, атрибуты, folding, форматирование) сервер спрашивает у клиента. Клиент плагина пока отдаёт серверу только `.cs` |
| XAML (WPF, Avalonia, MAUI) | полный язык, preview (Avalonia через плагин) | 🟡 | как XML; nesting `.xaml.cs`; шаблоны UserControl / Window WPF; фрагмент схемы MSBuild `desktop` |
| WinForms designer | ✅ (Windows) | ❌ | |
| F#, VB.NET | ✅ | ❌ | иконки, типы проектов в дереве |
| MSBuild (`.csproj`, `.props`, `.targets`) | ✅ (schema, навигация по `$(…)`, imports) | ✅ | своя составная JSON-схема (12 фрагментов: core, nuget, packaging, publish, analysis, aspnet, containers, testing, grpc, efcore, versioning, desktop; ~283 свойства, ~44 item-а): completion тегов по месту (в т.ч. серым «needs <пакет>»), атрибутов, значений (bool / перечисления / списки `a;b`), готовые вставки (`<Nullable>|</Nullable>`), Quick Doc с ссылкой на документацию, предупреждение о значении вне закрытого перечисления, подсветка `$()`, `@()`, `%()` |
| MSBuild: навигация по `Import` / `ProjectReference` / `$(Property)`, completion `$(…)`, битые пути | ✅ | ❌ | |
| NuGet completion в `PackageReference` / `PackageVersion` / `GlobalPackageReference` / `PackageDownload` | ✅ | ✅ | id по фидам solution (от 2 символов, загрузки, ✓ verified), версии (новые сверху, prerelease по настройке или после `-`), авто-`Version="…"` кроме CPM; кэш 5 мин |
| csproj: inlay «есть новая версия», quick-fix обновления | ✅ | ❌ | |
| `Directory.Build.props` / `Directory.Packages.props` | ✅ | 🟡 | completion по схеме, показ в Imports, чтение версий CPM; правки CPM из окна NuGet — нет |
| `appsettings.json`, `launchSettings.json`, `global.json`, `dotnet-tools.json` — JSON Schema | ✅ | ➖ | даёт JSON-плагин платформы: каталог SchemaStore (включён по умолчанию, Settings → Languages → JSON Schema Mappings → Remote catalog) сопоставляет `appsettings.json` / `appsettings.*.json`, `launchsettings.json`, `global.json`, `dotnet-tools.json` — проверено по каталогу 2026-09-29. В IDEA Community JSON-плагин есть; своей схемы плагину не нужно |
| `.resx` редактор (таблица, культуры, Localization Manager) | ✅ | ❌ | как XML; генератор копии для культуры |
| `.editorconfig` для C# (completion `csharp_*`, severity) | ✅ | ✅ 🔬 | 2026-09-29: описания опций .NET (`csharp_*`, `dotnet_*`, naming rules, severity правил) и ReSharper лежат в платформенном EditorConfig-плагине, но выключены ключами реестра `editor.config.csharp.support` / `editor.config.resharper.support` (их включает Rider) — плагин включает их при открытии проекта (`EditorConfigDotNetSupport`): completion и документация опций. Вживую не проверено |
| `.http` (HTTP Client) | ✅ | ➖/🟡 | генерация запросов из Endpoints в `<Project>.http`; исполнение — HTTP Client платформы (есть в IDEA Ultimate / GoLand / PyCharm Pro / WebStorm, нет в Community) |
| `.sln`: подсветка, сворачивание секций | ✅ | ❌ | plain text |
| `.proto`, gRPC | ✅ | 🟡 | только фрагмент схемы MSBuild (`Protobuf` item) |
| T4 (`.tt`) | ✅ | ❌ | |
| `.csx`, скрипты, C# REPL | ✅ (C# Interactive) | ❌ | `.csx` — только тип файла |
| `.slnf`, `.pubxml`, `.runsettings` | ✅ | 🟡 | иконки; `.runsettings` как XML |

### 4.6 Сборка

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Build / Rebuild / Clean solution и проекта | ✅ | ✅ | `dotnet build [--no-incremental] / clean -nologo -clp:NoSummary`; ПКМ в панели и меню .NET; из редактора — по владельцу файла |
| Restore | ✅ | ✅ | `dotnet restore`, Force Restore (`--force --no-cache`) |
| Build tool window с деревом ошибок, переход к коду | ✅ | ✅ | `MsBuildOutputParser`: `file(line,col[,endLine,endCol]): error CODE: text [project]`, дедупликация по мульти-TFM, Rerun, Stop |
| Ошибки/предупреждения последней сборки в редакторе | ✅ (SWEA) | ✅ | ExternalAnnotator, диагностика следует за строкой (±200 строк по тексту), исчезает при правке строки; **выключено при готовом сервере** |
| Переключатель конфигурации Debug / Release + TFM в тулбаре | ✅ | ✅ | список из `.sln`/`.slnx` (по умолчанию Debug/Release); `--framework` только для мульти-TFM проекта |
| Custom Configurations / Platforms (x64, AnyCPU) | ✅ | 🟡 | конфигурации из solution — да; платформы — нет |
| MSBuild global properties, `-m:N`, verbosity, файловый лог | ✅ | ✅ | страница Toolset and Build: `-p:` (`--property:` для run), Run build after solution is loaded, Restore before build (`--no-restore`), Use up to N processes, verbosity, `-fl -flp:` в `~/idea-dotnet-logs/DotNetBuild` |
| Smart Restore on Build | ✅ (NuGet heuristics) | ✅ | `--no-restore`, пока `project.assets.json` новее csproj / `packages.lock.json` / `Directory.*` / `nuget.config` / `global.json` |
| ReSharper Build (инкрементальный), heuristics | ✅ | ❌ | |
| Build after solution loaded | ✅ | ✅ | |
| Run MSBuild Target… | ❌ (нет в Rider) | ✅ | список из `dotnet msbuild -targets`, свои сверху, `_`-внутренние серым |
| Measure Build Performance | ❌ (нет; binlog) | ✅ | `-clp:PerformanceSummary` → таблицы Targets / Tasks / Projects |
| Сохранение binlog (`-bl`), Structured Log Viewer | ✅ | ❌ | |
| Warnings as errors, Treat warnings, Analysis level из UI | ✅ | ❌ | через `.csproj` (completion есть) |
| Build при Run/Debug | ✅ | ✅ | before-run task «Build .NET Project» только для Debug + `dotnet run`; run / watch / test собирают сами; ошибка отменяет запуск |
| Терминал вывода MSBuild в UTF-8, локализация | ✅ | ✅ | `DOTNET_CLI_UI_LANGUAGE=en` там, где парсится вывод |

### 4.7 Запуск и run configurations

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Run configuration «.NET Project» | ✅ (.NET Project, .NET Executable, .NET Static Method, Launch Settings Profile, Compound, Docker, IIS Express, Publish…) | 🟡 | один тип «.NET Project» с командами `dotnet run` / `dotnet watch` / `dotnet test`; поля: Project, Command, Launch profile, Environment (Development/Staging/Production + `appsettings.<Name>.json`), Arguments, Working directory, Env vars (+ parent env), Test filter, Collect coverage, Open browser |
| `.NET Executable` (запуск готовой dll / exe без проекта) | ✅ | ❌ | |
| `.NET Static Method` | ✅ | ❌ | |
| Compound (несколько проектов) | ✅ | ❌ | можно запускать по одному |
| Автогенерация конфигураций по `launchSettings.json` | ✅ | ✅ | по одной на профиль `commandName: Project` (`<project>: <profile>`), `launchBrowser` из профиля; удалённая пользователем не восстанавливается; настройка |
| Профили IIS Express / Executable в `launchSettings` | ✅ (IIS Express на Windows) | ❌ | пропускаются |
| Создание из контекста: узел проекта, файл, gutter ▶ у `Main` | ✅ | ✅ | `Main` — по токенам (`static … Main(`); top-level statements — через узел проекта |
| `dotnet run -e` (окружение перебивает профиль) | ✅ | ✅ | SDK ≥ 9.0.200 с учётом `global.json`; на старых — только переменные |
| `dotnet watch` | ✅ (.NET Watch + Hot Reload UI) | 🟡 | как команда конфигурации; без индикатора Hot Reload / Restart |
| Hot Reload (Apply changes при запуске / отладке) | ✅ | ❌ | |
| Консоль: кликабельные стектрейсы `at X in File.cs:line N` (в т.ч. локализованные) | ✅ | ✅ | во всех консолях IDE |
| Кликабельные `file(line,col): error` в консоли | ✅ | ✅ | |
| Сворачивание кадров `System.*` / `Microsoft.*` | ✅ | ✅ | «<N framework frames>» |
| Раскраска уровней логов (MEL `info:`/`fail:`, Serilog `[INF]`, NLog/log4net `|WARN|`) | ✅ | ✅ | цвета Console Colors → Log console |
| Автооткрытие браузера по «Now listening on» + `launchUrl` | ✅ | ✅ | галочка; `0.0.0.0`/`[::]` → localhost |
| Analyze .NET Stack Trace (вставить из буфера) | ✅ | ✅ | из буфера, если похоже на стектрейс |
| Services / Run Dashboard (статус, адрес, перезапуск нескольких) | ✅ | ✅ | 2026-09-29: конфигурации «.NET Project» — в окне **Services** по умолчанию (`runDashboardDefaultTypesProvider`): консоли, статус, перезапуск списком; у запущенного веб-приложения рядом с именем — ссылка на адрес из «Now listening on» (`runDashboardCustomizer`), и при Run, и при Debug. Тест `ServicesAndNodeActionsTest`; **вживую не проверено** |
| Ввод в консоль запущенной программы | ✅ | ✅ | Run — платформа; Debug — через `runInTerminal` (4.8) |
| Запуск одиночного `.cs` (`dotnet run file.cs`, .NET 10) / `.csx` | ✅ (10 / scripts) | ❌ | file-based programs в LS выключены; run configuration нет |
| Remote run (SSH, WSL, Docker) | ✅ | ❌ | |

### 4.8 Отладчик

Адаптер — `dotnet-debugger-dap` (dotnet tool, MIT, ICorDebug). Все слои 1–5 `DAP_PLAN.md` сделаны и проверены в живой IDE / UI-роботом 2026-09-22.

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Debug у конфигурации `.NET Project` (`dotnet run`) | ✅ | ✅ | сборка своим Build → `TargetPath` через `msbuild -getProperty` → `launch` с `program`; аргументы, `cwd`, env (профиль + таблица + окружение), `configuration`, `justMyCode`, `allowImplicitFuncEval`, `console: integratedTerminal` |
| Debug в IDE без платформенного DAP (IDEA Community) | — | ✅ | свой `DapConnection` (фрейминг, `request_seq`, события, `DapException` с текстом адаптера, `DapClosedException`) |
| Line breakpoints | ✅ | ✅ | только на строках с кодом (`CSharpBreakpointLines`: пропускает `using`, комментарии, заголовки; expression-bodied, top-level statements); verified / invalid из ответа и событий `breakpoint` |
| Condition, hit count, logpoint | ✅ | ✅ | Condition; Hit count `5` / `>= 3` / `% 10`; Log message `total = {total}` (вычисляется `repl`, не останавливает) |
| Temporary / dependent breakpoints, breakpoint groups | ✅ | 🟡 | группы — платформа; dependent — нет |
| Exception breakpoints («Break when»: thrown / user-unhandled / unhandled, типы с `*` и `!`) | ✅ | ✅ | по умолчанию включена «Any exception (user-unhandled, unhandled)»; ограничение адаптера: `unhandled` не выключается; `$exception` в Evaluate |
| Data breakpoints, Method breakpoints | ✅ (data — Windows) | ❌ | у адаптера нет |
| Step Over / Into / Out, Resume, Pause, Run to Cursor | ✅ | ✅ | Run to Cursor — временная точка + continue |
| Smart Step Into (выбор вызова в строке) | ✅ | ❌ | |
| Step into external code / decompiled | ✅ (external sources, Source Link, symbol servers) | 🟡 | «Enable external source debug» (= не Just My Code) — шаг в код фреймворка без исходников; декомпиляции на лету и symbol servers нет |
| `stepIn` в async-метод | ✅ | ❌ | ограничение адаптера (Run to Cursor как обход) |
| Set Next Statement | ✅ | ✅ | своё действие в контекстном меню редактора (после Run to Cursor), без клавиши |
| Edit & Continue / Hot Reload при отладке | ✅ | ❌ | |
| Frames: потоки, кадры порциями, external code серым | ✅ | ✅ | остановившийся поток первым; кадры по 50, до 2000 |
| Async call stack | ✅ | ✅ | разделитель «Async Call Stack» над первым ожидающим кадром |
| Threads window, Parallel Stacks, Modules, Memory view | ✅ | ❌ | потоки — только список в Frames |
| Variables: locals / this / statics, раскрытие постранично, «Show more» | ✅ | ✅ | `variables` всегда с `start/count=100` (иначе адаптер умирает на больших коллекциях) |
| Set Value (F2) в Variables / Watches / элементе коллекции | ✅ | ✅ | `setExpression` |
| Evaluate, Watches, hover над переменной | ✅ (лямбды, LINQ) | ✅ | hover — по токенам, только цепочки имён без вызовов; лямбды — как умеет адаптер |
| Immediate window / REPL в Debug Console | ✅ | 🟡 | Evaluate Expression платформы; отдельного REPL нет |
| Completion в Evaluate / Watches / условиях | ✅ | ✅ | у адаптера нет `completions` — имена вычисляются из кадра и членов `this`, после `x.` — дети значения; только цепочки без вызовов |
| Значения в редакторе (inline values) | ✅ | ✅ | |
| Return values, `DebuggerDisplay`, `DebuggerTypeProxy` | ✅ | 🟡 | `DebuggerDisplay` — адаптер (при implicit func eval); return values — нет |
| Pin to top, Auto-refresh watches, exception popups | ✅ | 🟡 | платформа даёт часть; при `exception` детали печатаются в консоль (`exceptionInfo`) |
| Attach to Process (локальный .NET) | ✅ | ✅ | группа «.NET» в Run \| Attach to Process: `dotnet` / `testhost` / apphost с `runtimeconfig.json` / `deps.json`; повторный attach к отлаживаемому не предлагается; Stop — detach |
| Attach: remote / SSH / Docker / WSL | ✅ | ❌ | |
| Отладка тестов (gutter Debug, конфигурация `dotnet test`, Unit Tests → Debug Selected) | ✅ | ✅ | `VSTEST_HOST_DEBUG=1` → attach к testhost, стартовый `Debugger.Break()` пропускается; Microsoft.Testing.Platform — не проверено |
| Ввод в программу из Debug Console | ✅ | ✅ | `runInTerminal`: плагин сам запускает программу с pipes, UTF-8 на Windows через `--console-utf8` |
| Stop / kill | ✅ | ✅ | `terminate` → `disconnect` → убийство дерева процессов адаптера |
| Restart (Rerun) | ✅ (Restart пересобирает) | ✅ | Rerun платформы: остановка, Build, новая сессия; `restart` адаптера не используется намеренно |
| Отладка .NET Framework, Mono, native/C++ mixed | ✅ | ❌ | адаптер — только CoreCLR |
| Blazor WebAssembly, JS в браузере | ✅ | ❌ | |
| Отладка дампа (open .dmp как сессия) | ✅ | 🟡 | не как сессия отладки; есть анализ дампа в .NET Monitor (4.11) |
| Predictive debugger, Collect memory snapshot из сессии | ✅ | ❌ | |
| Логи адаптера и трасса протокола | скрыто | ✅ | `~/idea-dotnet-logs/dotnet-debugger/adapter-*.log` (20 последних), Trace Debugger Protocol (toggle), registry `dotnet.debugger.adapter.log` / `.protocol.trace` |
| Настройки Debugger | 5 страниц | 🟡 | «Enable external source debug», «Allow property evaluations and other implicit function calls»; остальное — Build, Execution, Deployment \| Debugger платформы |
| Второй адаптер (netcoredbg) | — | ❌ | отложен по решению пользователя |

### 4.9 Тесты и покрытие

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Обнаружение тестов | по семантике: xUnit, NUnit, MSTest, TUnit, MTP, наследование, кастомные атрибуты | 🟡 | по токенам: `[Fact]`, `[Theory]`, `[Test]`, `[TestCase]`, `[TestCaseSource]`, `[TestMethod]`, `[DataTestMethod]` (с/без `Attribute`, с квалификатором); унаследованные тесты и свои атрибуты-наследники невидимы |
| Unit Tests explorer (все тесты solution без запуска) | ✅ (сессии, группировки, фильтры, категории) | ✅ | окно Unit Tests внизу: вкладка Explorer — Project → Class → Method, счётчики; Run / Debug / Run with Coverage Selected, Refresh, Expand/Collapse; двойной клик — к коду |
| Сессии результатов в окне Unit Tests | ✅ | ✅ | свой program runner: `dotnet test` идёт в окно Unit Tests, вкладка переиспользуется |
| Результаты по мере выполнения | ✅ | ❌ | дерево из TRX после завершения процесса; консоль — live |
| Дерево результатов: длительность, вывод, сообщение, стек, переход к исходнику | ✅ | ✅ | TRX → SMTRunner; параметризованные кейсы `Divides(a: 4, …)`; переход — повторным сканом `.cs` |
| ▶ у методов и классов, `--filter FullyQualifiedName~` | ✅ | ✅ | класс — с точкой в конце, чтобы не ловить `OrderTestsBase` |
| Rerun Failed | ✅ | ✅ | |
| Debug тестов | ✅ | ✅ | см. 4.8 |
| Run tests with coverage | ✅ (dotCover, statement-level) | ✅ | coverlet `--collect:"XPlat Code Coverage"` → Cobertura: полосы в gutter (покрыто / частично по веткам / нет), окно «.NET Coverage» (File, Lines, Covered/Total, Branches, худшие сверху), проценты у файлов и папок в Project / Solution view, политика при новом покрытии (Ask / Replace / Add / Do not apply) |
| Coverage: по проектам / методам / классам, несколько прогонов с историей, экспорт | ✅ | ❌ | только по файлам; «Add» суммирует хиты |
| Continuous testing (`dotnet watch test`) | ✅ | ❌ | |
| Microsoft.Testing.Platform (MSTest runner, xunit.v3 runner, TUnit, `MSTest.Sdk`) | ✅ | 🟡 | 2026-09-29: проект распознаётся (`EnableMSTestRunner`, `UseMicrosoftTestingPlatformRunner`, пакеты `TUnit` / `Microsoft.Testing.Platform*`, SDK `MSTest.Sdk`); три режима `dotnet test`: VSTest (и MTP через bridge), MTP с `TestingPlatformDotnetTestSupport` (опции после `--`), MTP-раннер SDK 10 (`global.json` `test.runner` / `dotnet.config` → `--project`, опции напрямую); TRX через `--report-trx` (xunit.v3 — `--report-xunit-trx`) в наш каталог результатов → то же дерево; фильтры ▶ / explorer конвертируются: MSTest — VSTest-выражение, xunit.v3 — `--filter-class` / `--filter-method`, TUnit — `--treenode-filter` (несколько классов — шире, чем выбрано); покрытие — `--coverage … cobertura` (нужен `Microsoft.Testing.Extensions.CodeCoverage`). **Нет**: отладка MTP-тестов (у платформы нет хоста, ждущего отладчик — нотификация; путь — отладка exe как .NET Project или Attach), live-результаты. Тест `TestingPlatformTest`; **вживую не проверено** |
| Test categories / traits, фильтр по ним, `.runsettings` | ✅ | 🟡 | `--filter` можно набрать в поле «Test filter» руками; `.runsettings` — через аргументы |
| Параллельный запуск, retry, тайм-ауты из UI | ✅ | ❌ | |
| Генерация теста для класса | ✅ | ✅ | «Test for Selected Class» (ищет/создаёт тест-проект, выбирает xUnit/NUnit/MSTest по пакетам) |
| BenchmarkDotNet | 🟡 (плагин) | ❌ | |

### 4.10 NuGet и зависимости

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Окно NuGet: Packages | ✅ | ✅ | область «Solution / проект», единый список Installed + Available с иконками, поиск (400 мс debounce), Prerelease, стрелка «→ latest» и `multiple` при разных версиях; кнопки +/− в строке |
| Карточка пакета | ✅ | ✅ | шапка + verified, Version с кнопками «во все проекты», Info (описание, авторы, загрузки, лицензия, теги, ссылки Project / License / nuget.org), Dependencies по TFM из `.nuspec`, таблица проектов с Install / Update / Downgrade / Remove |
| README пакета в карточке | ✅ | ❌ | |
| Install / Update / Downgrade / Remove | ✅ | ✅ | `dotnet add|remove package`, фоновая задача, вывод в Build и в Log; после — сам restore |
| Upgrade Packages in Solution (все до последних stable) | ✅ (Update All) | ✅ | подтверждение со списком; без выбора отдельных пакетов («preview» — нет) |
| Консолидация версий между проектами | ✅ | ❌ | видно `multiple`, действия нет |
| Central Package Management (`Directory.Packages.props`) | ✅ (правки из окна) | 🟡 | версии читаются и показываются; установка / правка версий из окна не учитывает CPM (пишет `Version` в csproj через CLI) |
| Устаревшие / уязвимые / deprecated пакеты (`dotnet list package --outdated --vulnerable`) | ✅ (Vulnerable packages inspection) | ❌ | «есть новее» — сравнение с фидом; уязвимости — нет |
| Конфликты версий (NU1605/NU1608), «почему пакет здесь» (`dotnet nuget why`) | ✅ (Dependency Diagram / transitive) | ❌ | транзитивное дерево в Dependencies есть, обратной цепочки нет |
| Sources: фиды всех уровней `nuget.config`, New / Edit / Remove / Enable / Disable | ✅ | ✅ | диалог как в Rider: Name, URL, User, Password, Enabled, Allow insecure, Disable TLS validation; учётные данные в `nuget.config` (CLI) + PasswordSafe IDE для своих HTTP-запросов; пароли маскируются |
| Выбор файла `nuget.config` для нового фида | ✅ | ❌ | куда пишет CLI |
| Folders (кэши `dotnet nuget locals`), Clear | ✅ | ✅ | Open in File Manager, Clear с подтверждением |
| Log команд | ✅ | ✅ | вкладка Log, потоково |
| Тулбар: Restore, Upgrade, карточка, Settings, Help; NuGet Quick List (Alt+Shift+N) | ✅ | ✅ | |
| Auto-restore при изменении csproj / `Directory.*` / `nuget.config` / `packages.lock.json` / `global.json` | ✅ | ✅ | 2 с debounce, Smart Restore не гоняет лишний раз |
| Credential providers (Azure Artifacts) | ✅ | 🟡 | только ссылка на установку; `--interactive` опцией |
| Приватные фиды: поиск с учётными данными | ✅ | ✅ | Basic auth из PasswordSafe для V3 search / flat container |
| Протокол | V2 + V3 + local folders | 🟡 | только V3 (search, flat container, nuspec); локальные папки-фиды в поиске не участвуют |
| Pack & Push, API key, bump version | ✅ (частично) | ❌ | |
| Глобальные / локальные tools (`dotnet tool list/install`), workloads | ✅ | 🟡 | только tools самого плагина на странице настроек; общего окна и workloads нет |
| NuGet Settings, открыть `nuget.config`, папка `packages` | ✅ | ✅ | |

### 4.11 Профилирование и диагностика

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Monitoring tool window (counters во время запуска) | ✅ | ✅ | «.NET Monitor» справа: CPU (ОС), Memory (working set ОС + GC heap), Allocation rate, Time in GC, GC collections/s, Active requests (server / HttpClient), Request duration p95, Exceptions + lock contentions, Thread pool queue; `dotnet-counters collect --format csv`, имена .NET 9+ и legacy; процессы из IDE (run и debug) сами, «All .NET processes» по галочке |
| Свои `Meter`, EF Core / Kestrel counters, пауза, масштаб | ✅ (частично) | ❌ | |
| dotTrace (CPU profiling, flame graph, timeline) | ✅ | ❌ | `dotnet-trace` — в ROADMAP, не начато |
| dotMemory (снимки, retained size, доминаторы) | ✅ (Windows/Linux частично) | 🟡 | Heap Snapshot (`dotnet-gcdump report`): типы, объекты, байты, фильтр, Δ к любому прошлому снимку (до 8) |
| «Кто держит объект» | ✅ dotMemory | ✅ | Memory Dump (`dotnet-dump collect --type Heap` + один живой `analyze`): типы → объекты (1000) → Who Holds It (`gcroot`), Fields (`dumpobj`, переход по ссылке, Back), `objsize`; вкладка SOS Console (любая команда); Save Dump As… |
| Сравнение дампов, дерево доминаторов, поколения GC | ✅ | ❌ | |
| Thread Dump | ✅ | ✅ | `dotnet-stack report`: одинаковые стеки свёрнуты, код приложения сверху, кадры кликабельны (async / лямбды / `<Main>$` раскодированы) |
| Dynamic Program Analysis (DPA: аллокации, блокировки на ходу) | ✅ | ❌ | |
| Открыть `.dmp` / `.nettrace` / `.gcdump` файл | ✅ | ❌ | только снятые самим окном |

### 4.12 EF Core и данные

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Add / Remove Migration, Update Database (в т.ч. откат), Generate SQL Script, Drop Database | ✅ (EF Core plugin) | ✅ | общий диалог: migrations project, startup project (авто — приложение со ссылкой и Design-пакетом), DbContext и миграции из исходников без сборки, окружение, конфигурация / TFM, `--no-build`, verbose, аргументы, предпросмотр командной строки; выбор запоминается |
| Scaffold DbContext from Database | ✅ | ✅ | строка или `Name=ConnectionStrings:X` из `appsettings*.json`, провайдер по пакету (SqlServer, Npgsql, Sqlite, Pomelo / MySql, Oracle), таблицы / схемы / папки, `--data-annotations`, `--use-database-names`, `--no-pluralize`, `--no-onconfiguring`, `--force` |
| Create Migration Bundle | ✅ | ✅ | self-contained, RID, Reveal |
| Окно миграций со статусом applied / pending | ✅ | ✅ | tool window «EF Core»: проект → DbContext → миграции (новые сверху), статус по кнопке Refresh (`migrations list --json`), «model has changes not in a migration» (EF 8+), контекстные действия «Update Database to Here», «Script from/to Here», Remove, Open, Copy Name |
| Gutter у `DbContext` и миграций | ✅ | ✅ | |
| Страховки | 🟡 | ✅ | `dotnet-ef` из манифеста или глобальный + «tool старее runtime», авто-добавление `Microsoft.EntityFrameworkCore.Design` нужной версии, Drop только после `dbcontext info` и ввода имени базы, предупреждение о `DropTable/DropColumn` в новой миграции, разбор ошибок (нет tool / Design / провайдера, не создаётся DbContext → Create Design-Time Factory, несколько контекстов, миграция уже применена → Revert and Remove), маскирование `--connection` |
| Design-Time DbContext Factory генератор | ❌ | ✅ | по провайдеру и имени базы |
| `dbcontext optimize`, run configuration «EF Core Command», before-launch «Apply migrations» | 🟡 | ❌ | |
| Database tool window (подключения, SQL, данные) | ✅ | ➖ | есть в IDEA Ultimate / GoLand / PyCharm Pro / WebStorm(частично), нет в Community; интеграции «строка подключения из appsettings → Data Source» нет |
| User Secrets (`secrets.json`, init, дифф с appsettings) | ✅ | ❌ | |
| LINQ / SQL-инъекция в строки, EF query preview | ✅ | ❌ | |

### 4.13 ASP.NET Core и web

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Окно Endpoints | ✅ | ✅ | minimal API (`MapGet/Post/Put/Delete/Patch/Methods/HealthChecks`, `MapGroup` через переменные и цепочки) и контроллеры (`[Route]`, `[HttpX("…")]`, `[controller]`/`[action]`, абсолютные `/`/`~/`), параметры маршрута; по проектам с base URL из `launchSettings`; Generate HTTP Request, Open in Browser, Copy URL; globe в gutter; автопересканирование |
| Endpoints: маршруты из констант / `nameof` / других файлов, Razor Pages, `MapHub`, Search Everywhere | ✅ | ❌ | нет разрешения символов |
| HTTP Client + генерация запросов | ✅ | 🟡 | генерация в `<Project>.http` с `@<Project>_HostAddress`; исполнение — платформа |
| HTTP Client environments из `launchSettings` (`http-client.env.json`) | ✅ | ❌ | |
| Razor / Blazor редактирование | ✅ | ❌ | 4.5: сервер умеет, клиент не подключён |
| HTTPS dev-сертификат (`dev-certs --check`, Trust) | ✅ | ❌ | |
| `user-jwts` | ✅ | ❌ | |
| OpenAPI / Swagger: генерация клиента, просмотр спецификации | ✅ | ❌ | |
| SignalR, gRPC инструменты | 🟡 | ❌ | |
| JS / TS / CSS / HTML | ✅ (WebStorm внутри) | ➖ | даёт хост-IDE (WebStorm / IDEA Ultimate / плагин JS) |
| Docker / docker-compose запуск, fast mode | ✅ | ➖/❌ | Docker-плагин платформы для Dockerfile / compose; интеграции с `dotnet` (fast mode, debug в контейнере) нет |
| Aspire (AppHost, dashboard) | ✅ | ❌ | только SDK в схеме |

### 4.14 Публикация, контейнеры, облако

| Функция | Rider | Плагин |
|---|---|---|
| Publish to folder / IIS / Azure / Docker (`.pubxml`) | ✅ | ❌ |
| `dotnet publish /t:PublishContainer` | 🟡 | ❌ (только схема MSBuild `containers`) |
| AOT / trimming: проверка совместимости, размер | 🟡 | ❌ (только схема `publish`) |
| Azure Toolkit, AWS Toolkit, Kubernetes | ✅ | ➖ (плагины платформы, без .NET-специфики) |
| Remote development (Gateway), Code With Me | ✅ | ➖ |

### 4.15 SDK, инструменты, окружение

| Функция | Rider | Плагин | Комментарий |
|---|---|---|---|
| Проверка `dotnet` при открытии, `global.json` vs установленные SDK | ✅ | ✅ | политики `rollForward` (disable / patch / feature / minor / major / latest*), `allowPrerelease`; нотификации Download SDK / Open global.json |
| «.NET on This Machine» | 🟡 (Toolset page) | ✅ | `dotnet --info`, таблицы SDK / runtime со статусом поддержки из `dotnet sdk check`, какой SDK выбран, Copy, Download |
| Управление tools плагина (путь, Install / Update) | — | ✅ | dotnet-counters, dotnet-stack, dotnet-gcdump, dotnet-dump, upgrade-assistant, dotnet-debugger-dap, roslyn-language-server, dotnet-ef, csharpier; поиск: настройки → PATH → `~/.dotnet/tools`; манифест репозитория приоритетнее (ef, csharpier) |
| Шаблоны `dotnet new`: поиск на nuget.org, установка, обновление, удаление | ✅ | ✅ | «More templates…»; приватные фиды — нет |
| Параметры шаблона в New Project (`--test-runner`, `--use-program-main`, `--auth`, …) | ✅ | ✅ | 2026-09-29: под строкой Framework — опции выбранного шаблона из `dotnet new <t> --help` (choice → список с описаниями, bool → галочка, text → поле, multiple → значения через `;`, «Applies when» из `Enabled if`); в `dotnet new` уходит только отличное от умолчания; список Framework — из `--framework` шаблона. Так создаётся и MTP-проект: `mstest` → Test runner = Microsoft.Testing.Platform / MSTest, `--sdk`, Coverage tool. Работает и для шаблонов сообщества (TUnit, xunit3). `--framework` и `--no-restore` не показываются. Тест `TemplateOptionsTest` (фикстуры `src/test/resources/dotnetNew`); **вживую не проверено** |
| Upgrade Assistant | ✅ (внутри) | 🟡 | только `analyze`: выбор TFM, таблица Severity / Issue / Found / Location с переходом и документацией; `upgrade` — нет |
| Форматирование проекта / solution, Verify Formatting (как CI) | ✅ (Code Cleanup) | ✅ | CSharpier (0.x и 1.x, HTTP-сервер на проект 7–12 мс, откат на stdin, `dotnet tool restore`) или `dotnet format [--verify-no-changes]` |
| Логи плагина | — | ✅ | `~/idea-dotnet-logs`, меню .NET → Show Plugin Logs; пароли маскированы, старше 14 дней удаляются; stdin команд закрыт (интерактивный запрос падает сразу) |
| Диагностика платформы (есть ли LSP / DAP API в этой IDE) | — | ✅ | Probe Platform LSP / DAP API…, Copy as JSON |
| C# Interactive / REPL | ✅ | ❌ | |

### 4.16 Настройки

| Страница | Rider | Плагин |
|---|---|---|
| Tools \| .NET | Toolset and Build (аналог) | ✅ путь `dotnet` + Check, Installed SDKs, `global.json`, ссылка на «.NET on This Machine»; .NET Tools (9 строк с Install/Update); Formatter (Auto / CSharpier / dotnet format / None, Auto = CSharpier, если репозиторий им пользуется); Behavior: автосоздание run configurations, окно Build при каждой сборке, автопереключение на Solution view |
| Toolset and Build | ✅ | ✅ MSBuild global properties, Run build after solution is loaded, Restore before build, Use up to N processes, verbosity, файловый лог (папка, verbosity, «Open the log folder») |
| NuGet | ✅ | ✅ Include prerelease; Automatically restore, Smart Restore on Build, `--no-cache`, `--interactive`; ссылка на Credential Provider |
| Coverage | dotCover | ✅ When new coverage is gathered (Ask / Do not apply / Replace / Add), Activate Coverage View, Show coverage in the project view |
| Debugger | 5 страниц | ✅ Enable external source debug, Allow property evaluations and other implicit function calls (+ ссылка на платформенную) |
| Language Server | — (нет) | ✅ Use the language server for C#; Server (executable, log level, log folder, auto-load projects + лимит, source generators, extra args); Analysis, Projects, Completion, Navigation and Documentation, Code Lens, Inlay Hints (13), Editing, Code Generation; «Other Settings of the Server» (`секция = значение`); Apply → рестарт или `didChangeConfiguration` |
| Editor \| Code Style \| C# | десятки вкладок | 🟡 только Tabs and Indents |
| Editor \| Color Scheme \| C# | ✅ | ✅ 18 ключей |
| Editor \| Inspections \| C# | 2 500+ | ❌ |
| Editor \| Live / File / Postfix templates | ✅ | 🟡 live — есть (33), postfix — 21 (страница Postfix Completion платформы: можно выключать), file templates — только 5 внутренних |
| Принцип «на странице только то, что работает» | — | ✅ решение пользователя 2026-09-21, тест `SettingsPagesTest` |

### 4.17 Что даёт хост-IDE бесплатно

Плагин не дублирует: VCS (Git, GitHub, review), терминал, Search Everywhere, Local History, Bookmarks, TODO-окно, Diff, Database tools (Ultimate /
GoLand / PyCharm Pro), HTTP Client (те же), Docker / Kubernetes плагины, JS/TS/HTML/CSS (WebStorm / Ultimate / плагин), Markdown, YAML, JSON, XML,
`.editorconfig`-базу, Code With Me, Remote Development, AI Assistant, настройки тем и keymap. В **IDEA Community** нет Database tools и HTTP Client —
это нужно учитывать при оценке «плагин + Community» против Rider.

---

## 5. Что готово

Сгруппировано по тому, что в живой IDE проверено (по журналу ROADMAP / планов) и что покрыто тестами.

**Проверено в живой IDE (пользователем или UI-роботом, 2026-09-21…22):**
- Отладчик целиком: запуск, точки останова (условия, hit count, logpoints), исключения, шаги, Run to Cursor, Set Next Statement, async-стек,
  переменные постранично, Set Value, Evaluate / Watches / hover, completion в выражениях, attach, отладка тестов, ввод в консоль, Stop без зависаний, логи.
- Roslyn LS: ошибки и анализаторы, code lens (клик по references), completion (в т.ч. после пробела, `()` + Parameter Info, хвосты), Ctrl+P со всеми
  перегрузками, Go to Declaration / Implementation, декомпилят read-only, rename с переименованием файла (путь «ответ → правка → файл»), quick fixes /
  вложенные / Fix All, Alt+Enter без дублей, semantic tokens из кэша до загрузки solution, выбор solution из нескольких, статус-виджет, inlay hints.
- NuGet: кнопки установки (`ClickListener` + `WriteIntentReadAction`), фоновая установка с выводом в Build и Log.
- Memory Dump на сценарии `leak` (UI-робот).

**Готово и покрыто тестами (UI в живой IDE не обязательно проверен):**
- Панель Solution (`.sln`/`.slnx`/`.slnf` по всей папке, folders, items, Dependencies по TFM с транзитивными проектами, nesting и `DependentUpon`,
  скрытые по `Remove` / `DefaultItemExcludes` файлы, linked-файлы, Show All Files, окраска ignored, Edit project file, drag в редактор).
- Действия над solution и проектом: New / Existing Project, Solution Folder, Project Reference, Assembly Reference, Rename Project, Properties…,
  Remove, Convert to `.slnx`.
- New Project generator (GoLand / PyCharm / WebStorm), «More templates…», 30 генераторов New → .NET, New → C# Class/…
- Лексер, раскраска, палитра, Structure / breadcrumbs / folding, Go to Class / Symbol, TODO, 33 live templates, doc-комментарии, отступы (29 правил с примерами), Code Style Indents.
- Сборка: Build / Rebuild / Clean / Restore, разбор вывода, Build tool window, ошибки сборки в редакторе, конфигурация Debug/Release + TFM, Toolset and Build, Smart Restore, Measure Build Performance, Run MSBuild Target.
- Run configuration «.NET Project» (run / watch / test), автогенерация по `launchSettings.json`, `-e`, консольные фильтры (стектрейсы, MSBuild, уровни логов, folding), автооткрытие браузера, Analyze Stack Trace.
- Тесты: обнаружение, ▶, `--filter`, TRX → дерево, Rerun Failed, окно Unit Tests с Explorer и сессиями, покрытие coverlet → gutter + окно + проценты в дереве.
- NuGet: окно (4 вкладки), карточка, поиск V3, install / update / downgrade / remove, Upgrade in Solution, Sources с диалогом и учётными данными, Folders, Log, auto-restore, Quick List, completion пакетов в csproj.
- MSBuild-схема: completion / документация / предупреждения, подсветка `$()`.
- EF Core: 7 команд с диалогами, окно миграций, gutter, Design-Time Factory, разбор ошибок.
- .NET Monitor: графики (ОС + counters), Thread Dump, Heap Snapshot с диффом, Memory Dump с SOS.
- Endpoints: minimal API + контроллеры, `.http`, gutter.
- SDK: проверки при открытии, «.NET on This Machine», Upgrade Assistant analyze, форматирование CSharpier / dotnet format, логи, Probe API.
- 6 страниц настроек только с работающими опциями.

## 6. Что не готово

### 6.1 По приоритету (что закрыло бы самые частые запросы)

**P0 — мешает ежедневно, если этим пользуешься:**
1. Razor / Blazor / `.cshtml` — языка в редакторе нет (для web-разработчиков на Blazor / MVC это блокер). Сервер 5.12 его умеет (снимок трафика 2026-09-29), осталось подключить клиент: тип файла, C#-половина, затем HTML-половина.
2. Результаты тестов только после завершения `dotnet test` (свой VSTest-логгер или Microsoft.Testing.Platform).
3. Hot Reload (`dotnet watch` без индикации; при отладке — нет).
4. ~~Type / Call hierarchy, Go to Base~~ — сделаны 2026-09-29; остались Find Usages с группировкой и Go to Base для членов.
5. ~~Проблемы по всему solution~~ — сделано 2026-09-29 (Problems → Project Errors при scope fullSolution).
6. Publish (папка / контейнер / `.pubxml`).
7. Compound run configuration (Services-окно — сделано 2026-09-29).

**P1 — заметный разрыв с Rider:**
8. Уязвимые / устаревшие / deprecated пакеты, консолидация версий, конфликты NU1xxx, «почему пакет здесь».
9. CPM: правки версий из окна NuGet в `Directory.Packages.props`; README пакета.
10. ~~Project Properties, rename проекта, `.slnf`, `Compile Remove` / linked files~~ — сделаны 2026-09-26…28; остались move проекта, unload / reload, drag-and-drop в дереве, Configuration Manager.
11. Code Style / Inspections для C# из UI (severity в `.editorconfig` с completion `csharp_*` / `dotnet_*`).
12. Spellchecker, Find Usages текстовый, неактивный `#if`, partial-навигация, Related files.
13. ~~Postfix templates, Surround with, Generate (Alt+Insert)~~ — сделаны 2026-09-29; у Generate нет своего диалога выбора членов.
14. `.NET Executable` / Static Method конфигурации, запуск одиночного `.cs` (.NET 10) и `.csx`.
15. `.resx` редактор; JSON Schema для `appsettings` / `launchSettings` / `global.json`; `.sln` подсветка.
16. XAML: только XML (для WPF / Avalonia / MAUI — есть шаблоны и nesting, нет completion / preview).
17. Coverage по проектам / методам / история; continuous testing.
18. Смарт-шаги отладчика: Smart Step Into, `stepIn` в async (адаптер), return values, Threads / Parallel Stacks / Modules, Edit & Continue.
19. `dotnet-trace` (CPU, flame graph); сравнение дампов, доминаторы, поколения GC (ClrMD-помощник).
20. Dev-certs, `user-jwts`, HTTP Client environments из `launchSettings`, User Secrets.

**P2 — полезно, но реже:**
21. Декомпиляция dll из Dependencies (`ilspycmd`), Assembly Explorer, IL.
22. Диаграмма зависимостей проектов, обновление TFM по solution, перевод на CPM.
23. `dotnet tool` окно, workloads, Pack & Push, bump version.
24. MSBuild-навигация по `Import` / `$(…)`, binlog.
25. OpenAPI-клиент, gRPC `.proto`, SignalR, Aspire.
26. BenchmarkDotNet, C# REPL, Paste JSON as classes, T4.
27. Remote / SSH / Docker / WSL отладка и запуск; .NET Framework / Mono / native (за пределами адаптера).
28. Фазы 4–6 `LSP_PLAN.md` (свои индексы исходников и метаданных сборок — Go to Class по пакетам и completion без сервера), фаза 8 (надёжность на больших solution).

### 6.2 По профилям разработчиков

| Профиль | Закрыто | Главные пробелы |
|---|---|---|
| **Backend / ASP.NET Core Web API, Worker, микросервисы** | сборка, запуск с профилями и окружениями, отладка, Endpoints + `.http`, логи в консоли, Monitor (requests p95, GC), EF Core, NuGet, тесты | Compound / Services для нескольких сервисов, Hot Reload, Publish / контейнеры, dev-certs / user-jwts / User Secrets, OpenAPI-клиент, Aspire |
| **Blazor / MVC / Razor Pages** | шаблоны файлов, nesting, всё серверное | **редактор Razor отсутствует** — критично |
| **Desktop: WPF / WinForms / Avalonia / MAUI** | сборка / запуск / отладка CoreCLR, XAML как XML, nesting, шаблоны WPF, схема MSBuild `desktop` | XAML completion / preview, WinForms designer, workloads (MAUI), Hot Reload |
| **Библиотеки и NuGet-авторы** | multi-TFM в дереве и переключателе, схема `packaging` / `versioning` / SourceLink, Verify Formatting, тесты + покрытие | Pack & Push, bump version, `.NET Executable` для проверки сборки, анализ API-совместимости, README в карточке |
| **Data / EF Core** | всё EF Core (миграции, окно, scaffold, bundle), строки подключения из appsettings | User Secrets, Database tools ↔ appsettings, `dbcontext optimize`, LINQ-инструменты |
| **QA / автотесты** | explorer, `--filter`, Rerun Failed, debug, coverage, генерация тестов | live-результаты, traits / категории в UI, `.runsettings`, MTP / TUnit в explorer, continuous testing, история покрытия |
| **DevOps / CI** | Verify Formatting, Measure Build Performance, MSBuild targets, `global.json` контроль, GitHub Actions шаблон, Dockerfile шаблон | Publish, binlog, контейнеры из IDE, workloads, `dotnet tool` окно |
| **Legacy .NET Framework (4.x)** | дерево, `TargetFrameworkVersion` → `net48`, block-scoped namespaces по TFM, NuGet | отладчик (только CoreCLR), `packages.config`, IIS Express, `App.config` трансформации; roslyn-language-server .NET Framework-проекты грузит через MSBuild — не проверено |
| **Performance / диагностика** | Monitor, Thread Dump, Heap Snapshot Δ, Memory Dump с gcroot и SOS | dotnet-trace / flame graph, DPA, сравнение дампов, доминаторы, открытие чужих `.dmp` |

---

## 7. Шпаргалка на каждый день

### 7.1 Первое открытие проекта
1. Открыть **папку** с `.sln`/`.slnx` (не файл). Панель Project сама переключится на **Solution** (один раз; настройка «Switch the Project tool window…»).
2. Если `dotnet` не найден или `global.json` требует другой SDK — придёт нотификация (Configure… / Download SDK / Open global.json).
3. Открыть любой `.cs` — стартует `roslyn-language-server` (если не установлен — нотификация с **Install**; нужен runtime .NET 10). Несколько solution в папке —
   диалог «Select Solution for Language Server». Статус — значок C# в статус-баре: «Roslyn: loading X.sln» → «Roslyn: X.sln»; клик показывает CPU и память сервера.
4. Run configurations для запускаемых проектов появятся сами (по одной на профиль `launchSettings.json`).
5. Полезно сразу поставить tools: **Settings | Tools | .NET → .NET Tools → Install** у `dotnet-debugger-dap` (Debug), `roslyn-language-server`, `csharpier` (если репозиторий им пользуется),
   `dotnet-ef`, `dotnet-counters` / `dotnet-stack` / `dotnet-gcdump` / `dotnet-dump` (Monitor).

### 7.2 Где что лежит
| Хочу | Где |
|---|---|
| Дерево solution, Dependencies, Show All Files («глаз») | окно **Project → Solution**; `.slnf` — отдельный узел «N of M projects» |
| Свойства проекта (TFM, OutputType, Nullable, LangVersion, warnings, пакет) | ПКМ проекта → **Properties…** |
| Переименовать проект / сослаться на dll | ПКМ проекта → **Rename Project…**; Add → **Assembly Reference…** |
| Собрать / пересобрать / очистить / restore | меню **.NET** (solution) или ПКМ по узлу (проект / выделение); из редактора — Build собирает проект текущего файла |
| Debug / Release и TFM | переключатель в правой части главного тулбара («Debug \| .NET 9.0») |
| Запустить / отладить проект под курсором | ПКМ узла → Run / Debug Project; gutter ▶ у `Main`; меню .NET → Run / Debug Project |
| Несколько сервисов разом, адреса, перезапуск | окно **Services**: все конфигурации «.NET Project», у веб-приложения ссылка на адрес |
| Пакеты | окно **NuGet** внизу (Packages / Sources / Folders / Log) или **Alt+Shift+N** (Quick List); ПКМ проекта → Manage NuGet Packages… |
| Тесты | окно **Unit Tests** внизу (Explorer + сессии); ▶ у метода / класса; ПКМ проекта → Run Tests with Coverage |
| Покрытие | окно **.NET Coverage** справа; полосы в gutter; проценты в дереве (Settings → Coverage) |
| Мониторинг процесса, thread dump, heap, memory dump | окно **.NET Monitor** справа или меню .NET → Monitor .NET Process |
| Маршруты ASP.NET | окно **Endpoints** внизу; globe в gutter → `.http` |
| EF Core | меню .NET → EF Core (и ПКМ проекта с EF); окно **EF Core** внизу (Show Migrations); gutter у `DbContext` / миграции |
| Новый файл | New → C# Class / Interface / … (Alt+Insert в дереве) или New → **.NET ▸** (по категориям, релевантные проекту сверху, остальное в Other, «From SDK Template…») |
| Новый проект | меню .NET → New .NET Project… / ПКМ solution → Add → New Project…; в GoLand / PyCharm / WebStorm — и мастер New Project → «.NET» |
| Форматирование | Ctrl+Alt+L (CSharpier / `dotnet format` / сервер — по Settings → .NET → Formatter); проект целиком — меню .NET → Format / Verify Formatting |
| Кто-то прислал стектрейс | меню .NET → Analyze .NET Stack Trace… (берёт из буфера) |
| Информация об SDK | меню .NET → .NET on This Machine… |
| Проверка апгрейда на новый .NET | ПКМ проекта / меню .NET → Analyze Upgrade to Newer .NET… |
| Логи | меню .NET → Show Plugin Logs (`~/idea-dotnet-logs`), Show Debugger Logs, Show Language Server Log, Show NuGet Log |
| Сервер C# капризничает | меню .NET → Restart C# Language Server; Language Server Timings (что тормозит); Select Solution for Language Server |
| GUID | Generate (Alt+Insert в редакторе) → Insert New GUID |

### 7.3 Редактор C# — что ожидать
- **Alt+Enter** — quick fixes и рефакторинги Roslyn, включая «Fix All in Document / Project / Solution» и вложенные варианты. Refactor-меню платформы с C# почти не работает — идти через Alt+Enter.
- **Shift+F6** — rename через сервер; файл `<Тип>.cs` переименуется вслед за типом. Ctrl+Z откатывает в два шага.
- **Ctrl+B / Ctrl+клик** — объявление (в т.ч. декомпилят фреймворка, read-only, баннер «Decompiled from…»). **Ctrl+Alt+B** — реализации. **Ctrl+U** на типе — базовые типы. **Ctrl+H** — Type Hierarchy, **Ctrl+Alt+H** — Call Hierarchy (окно Hierarchy). **Ctrl+Alt+F7** / клик по code lens «N references» — usages.
- Ошибки всего solution — окно **Problems → Project Errors**, если в Settings → .NET → Language Server стоит «Compiler diagnostics for: fullSolution» (по умолчанию openFiles — тогда там пусто и приходит подсказка).
- **Ctrl+P** — все перегрузки. **Ctrl+Q** — документация сервера. Ввод `(` открывает Parameter Info, не список.
- Inlay hints: имена параметров у литералов / `new` / индексаторов, типы у `var` / лямбд — выключаются в Settings → .NET → Language Server → Inlay Hints.
- `///` над членом → `<summary>` + `<param>` + `<returns>`. Live templates: `ctor`, `prop`, `propfull`, `cw`, `foreach`, `svm`, `fact`, `test`, `nn`, `region` … Postfix: `expr.if` / `.notnull` / `.foreach` / `.var` / `.return` / `.await` / `.not` / `.par` + Tab. Surround With: Ctrl+Alt+T на выделении (`if`, `try/catch`, `using`, `#region`…).
- Пока solution грузится: раскраска из дискового кэша сервера (мгновенно) или эвристика, Structure / folding / Go to Class — эвристика; ошибок не показывается.
- Ошибки в **закрытых** файлах в редакторе не появятся — смотреть окно Build после сборки.
- Форматирование: Auto = CSharpier, если в репозитории `.csharpierrc*` / `dotnet-tools.json` с csharpier / `CSharpier.MsBuild`, иначе `dotnet format` (сервером, когда загружен). Работает и в Actions on Save / перед коммитом.

### 7.4 Запуск и отладка
- Конфигурация «.NET Project»: **Command** run / watch / test; **Launch profile** из `launchSettings.json`; **Environment** (Development / Staging / Production / `appsettings.<X>.json`) перебивает профиль через `dotnet run -e` (SDK ≥ 9.0.200); **Open browser** — по «Now listening on» + `launchUrl`.
- Точки останова — только на строках с кодом; в диалоге Breakpoints у точки: Condition, Hit count (`5`, `>= 3`, `% 10`), Log message (`x = {x}`).
- Исключения: Run | View Breakpoints → «.NET Exception Breakpoints» — по умолчанию «Any exception (user-unhandled, unhandled)»; «+» — свои типы (`System.IO.*, !System.IO.FileNotFoundException`), Break when: thrown / user-unhandled / unhandled. `unhandled` выключить нельзя (адаптер).
- Большие коллекции раскрываются по 100 («Show more»). Медленные свойства — выключить «Allow property evaluations…» в Settings → .NET → Debugger.
- Шаг в код фреймворка — включить «Enable external source debug». `stepIn` в async-метод не работает — Run to Cursor.
- **Set Next Statement** — ПКМ в редакторе во время остановки (рядом с Run to Cursor).
- Ввод в консольную программу — прямо в Debug Console. Rerun пересобирает проект.
- Attach: Run | Attach to Process → группа «.NET». Повторно к уже отлаживаемому — не даст (убивает процесс на Windows).
- Тесты под отладчиком: Debug у ▶, у конфигурации `dotnet test`, «Debug Selected Tests» в Unit Tests.
- Если что-то не так — меню .NET → Trace Debugger Protocol (включает трассу для следующих сессий) → Show Debugger Logs.

### 7.5 Сборка
- Вывод — окно Build (открывается всегда или только при ошибке — Settings → .NET → Behavior). Ошибки кликабельны; в редакторе подчёркнуты до следующей сборки (если сервер не готов).
- Settings → .NET → Toolset and Build: `-p:` свойства, `-m:N`, verbosity, файловый лог MSBuild (`~/idea-dotnet-logs/DotNetBuild`).
- Медленная сборка — меню .NET → Measure Build Performance (таблицы по таргетам / задачам / проектам). Нужный таргет — ПКМ проекта → Run MSBuild Target… (`-targets`, свои сверху).
- Restore при сборке пропускается, пока `project.assets.json` свежее (Smart Restore, Settings → NuGet); Force Restore — меню .NET → NuGet.

### 7.6 NuGet
- Окно NuGet → «Packages for: Solution / проект» → поиск → «+» в строке или карточка → кнопки у проектов. `multiple` — версии в проектах разные (консолидировать пока руками).
- Приватный фид: Sources → New Feed… (User / Password хранятся в `nuget.config` через CLI и в хранилище паролей IDE для поиска). HTTP-фид — «Allow insecure connections».
- В `.csproj`: completion id пакета (от 2 символов) и версии в `PackageReference` / `PackageVersion`; `Version` дописывается сам, кроме CPM.
- Обновить всё — меню .NET → NuGet → Upgrade Packages in Solution (список на подтверждение).
- Изменил `.csproj` / `Directory.Packages.props` / `nuget.config` руками — restore запустится сам через 2 с (Settings → NuGet).

### 7.7 Тесты и покрытие
- Unit Tests → Explorer: выделить → Run / Debug / Run with Coverage. Дерево результатов появится **после завершения** `dotnet test` (консоль идёт live).
- Фильтр по traits / категориям — поле «Test filter» в конфигурации (`--filter` выражение VSTest).
- Покрытие: в тест-проекте нужен пакет `coverlet.collector`. Политика применения — Settings → .NET → Coverage.

### 7.8 EF Core
- Меню .NET → EF Core → Add Migration… — диалог сам подберёт startup project, DbContext, окружение; предпросмотр команды внизу. Если появится «may lose data» — в миграции есть `Drop*`.
- Окно EF Core → Refresh Status — соберёт startup-проект и спросит базу, какие миграции применены; «model has changes not in a migration» — пора делать миграцию.
- «Unable to create DbContext» → кнопка **Create Design-Time Factory** (генератор под провайдер).
- Drop Database попросит ввести имя базы после `dbcontext info`.

### 7.9 Диагностика приложения без отладчика
- .NET Monitor: выбрать процесс (запущенные из IDE — сразу; «All .NET processes» — любые) → графики (нужен `dotnet-counters`).
- Зависло — **Thread Dump** (кадры приложения кликабельны). Растёт память — **Heap Snapshot** дважды → Δ Objects / Δ Bytes; затем **Memory Dump** → тип → объект → «Who Holds It».
- В Memory Dump вкладка SOS Console: `dumpasync`, `syncblk`, `clrstack -all`, `finalizequeue`, `dumpheap -strings`. Save Dump As… — для VS / WinDbg / PerfView.

### 7.10 Настройки, которые стоит знать
| Настройка | Где | Зачем |
|---|---|---|
| Formatter: Auto / CSharpier / dotnet format / None | Tools \| .NET | что делает Ctrl+Alt+L |
| Create run configurations for the runnable projects | Tools \| .NET | отключить автогенерацию |
| Open the Build tool window on every build | Tools \| .NET | тишина при удачной сборке |
| MSBuild global properties; Use up to N processes; Write MSBuild log to file | Toolset and Build | воспроизводимость и диагностика сборки |
| Include prerelease; Automatically restore; Smart Restore on Build | NuGet | скорость и предсказуемость restore |
| Enable external source debug; Allow property evaluations… | Debugger | шаги во фреймворк; скорость раскрытия переменных |
| Compiler / Analyzer diagnostics for: openFiles / fullSolution | Language Server → Analysis | больше ошибок ценой нагрузки |
| Show items from namespaces that are not imported | Language Server → Completion | авто-`using` в completion |
| Inlay Hints (13 галочек) | Language Server → Inlay Hints | шум подсказок |
| Organize 'using' directives when formatting | Language Server → Editing | сортировка usings при Reformat |
| Other Settings of the Server (`секция = значение`) | Language Server | любая опция сервера без UI |
| When new coverage is gathered; Show coverage in the project view | Coverage | поведение покрытия |
| Tabs and Indents | Editor \| Code Style \| C# | единственные реально действующие опции стиля (плюс `.editorconfig`) |
| Registry `dotnet.debugger.protocol.trace` | Registry / меню .NET | трасса DAP |

---

## 8. Ограничения и оговорки

**Архитектурные (не «баги», а следствие подхода):**
- Нет семантики C# внутри IDE: любая функция, которой нужны типы, живёт в `roslyn-language-server`, и когда его нет / он грузится / упал — её нет. Эвристики «только предлагают».
- Свойства проекта читаются из XML без вычисления: `Condition`, `$(…)`, `Import` не учитываются (кроме отображения Imports). Мульти-TFM с условными группами показывается «как написано».
- `dotnet test` не даёт live-протокола — дерево результатов только по TRX в конце.
- Отладчик ограничен возможностями `dotnet-debugger-dap`: только CoreCLR, нет `completions`, `stepIn` в async не работает, `unhandled` не выключить, второй attach убивает процесс, строки > 4096 символов — ошибка, необработанное исключение на Windows иногда даёт код выхода 0.
- Платформенный LSP-клиент: нет `workspace/diagnostic` (ошибки закрытых файлов), не вызывает `implementation` / `typeHierarchy` / `callHierarchy` сам, шлёт только `semanticTokens/full` (большие файлы — 170–500 мс на запрос, лечится кэшем), `@Internal` хук `addLsp4jServerWrapper` — риск при смене платформы.
- Roslyn LS 5.12: URI должны быть с обычным двоеточием; теги диагностики через lsp4j теряются (обход в обёртке); `completionItem/resolve` без `data` роняет сервер; клиентские команды исполняет плагин.
- Content-модуль `roslyn` загружается только там, где есть `intellij.platform.lsp.impl`; без него плагин работает целиком, но без семантики.

**Не проверено вживую (🔬 в таблицах; из ROADMAP / планов):**
- Signature help вживую; папка без solution (`--autoLoadProjects` / `project/open`); `.cs`, созданный после загрузки solution.
- Диалог Rename руками (робот не открывает); Ctrl+наведение «глазами»; выбор мышью в списке реализаций; настоящий Shift+F6 → переименование файла после inline-rename.
- Раскраска при открытии проекта не «мигает» при переходе с кэша на ответ сервера; таблица таймингов на большом solution.
- Проекты на Microsoft.Testing.Platform под отладчиком; pid отлаживаемой программы в .NET Monitor.
- Наличие LSP API в GoLand / PyCharm / WebStorm 2026.1 (проверить меню .NET → Probe Platform LSP / DAP API…).
- Сделанное 2026-09-26…28 по разделу 4.1 (`.slnf`, рекурсивный поиск solution, Properties…, скрытые / вложенные / linked файлы, Rename Project,
  Assembly Reference, транзитивные проекты, мастер New Project в IDEA) покрыто тестами на light-проекте, но в живой IDE ещё не смотрелось: диалоги,
  бейдж linked-файлов, поведение Rename при открытых вкладках, мастер в IDEA.
- .NET Framework-проекты в Roslyn LS и в дереве Dependencies (MSBuild-загрузка на не-Windows).

**Платформа:**
- События окна Build на устаревших конструкторах `*BuildEventImpl` (замена `BuildEvents` пока `@Experimental`).
- `sinceBuild = 261`, Kotlin API 2.3; IDEA тянет Java-плагин, из-за чего папки в дереве именуются иначе, чем в GoLand (обработано, есть тесты).

---

## 9. Приложение

### 9.1 Пакеты → область → строк
| Пакет | Область | Строк |
|---|---|---|
| `lang` | лексер, объявления, PSI, Structure, folding, индексы, шаблоны, отступы, цвета, TODO, точки останова / hover / inline values по токенам | 2 420 |
| `roslyn` (content-модуль) | клиент `roslyn-language-server`: дескриптор, workspace, обёртка (теги, memo, tokens cache, тайминги), клиентские команды, code actions, completion, Parameter Info, Go to Implementation, декомпилят, rename файла, Ctrl+hover, прогрев | 2 090 |
| `monitor` | .NET Monitor: sampler ОС, dotnet-counters, thread dump, heap snapshot, memory dump + SOS | 1 970 |
| `ef` | EF Core: команды, диалог, окно миграций, gutter, design-time factory, разбор ошибок | 1 907 |
| `nuget` | окно NuGet, сервис, V3-клиент, sources, settings, auto-restore, меню | 1 853 |
| `debugger` | DAP-клиент, процесс адаптера, XDebugProcess, breakpoints, frames, values, evaluator, attach, terminal, Set Next Statement, логи | 1 732 |
| `run` | run configuration, producers, генератор по launchSettings, консольные фильтры, before-run, launch args, процессы .NET, Analyze Stack Trace | 1 164 |
| `build` | Build service, парсер MSBuild, BuildProblems, конфигурация/TFM, Toolset and Build, performance, targets | 1 055 |
| `msbuild` | типы файлов, MsBuildProject, схема, completion/annotator/doc, package completion, assets | 1 028 |
| `templates` | New → C# Class…, New → .NET генераторы, partial / test / resx, SDK templates | 891 |
| `testing` | discovery, TRX, run state, runner, Unit Tests окно, gutter | 857 |
| `view` | Solution view, узлы, Dependencies, nesting, Show All Files, activators, decorators | 839 |
| `newproject` | генератор проекта, панель шаблонов, `dotnet new list`, template packages | 610 |
| `endpoints` | сканер маршрутов, модель, окно, gutter, `.http` | 592 |
| `format` | CSharpier CLI/сервер, dotnet format, formatting service, actions | 554 |
| `cli` | DotNetCli, DotNetTool, логи, установка SDK | 473 |
| `sdk` | `.NET on This Machine`, global.json, проверки | 455 |
| `actions` | действия над solution, Insert GUID, Convert to slnx, SolutionContext | 437 |
| `coverage` | Cobertura, сервис, gutter, окно, настройки, декоратор | 437 |
| `lsp` | настройки Language Server (модель, страница, каталог опций), RoslynServerStatus / RoslynPolicy | 425 |
| `probe` | Probe Platform LSP / DAP API | 347 |
| `settings` | Tools \| .NET, Debugger | 335 |
| `solution` | модель и парсеры `.sln`/`.slnx`, SolutionService, SolutionEditor | 311 |
| `upgrade` | Upgrade Assistant analyze | 276 |

### 9.2 Tool windows
Solution (pane в Project), NuGet (низ), Unit Tests (низ), Endpoints (низ, id `DotNetEndpoints`), EF Core (низ, только при EF в solution), .NET Monitor (право), .NET Coverage (право, secondary), плюс платформенные Build / Run / Debug.

### 9.3 Меню .NET (главное меню, после Tools)
Run Project, Debug Project · Build / Rebuild / Clean Solution, Measure Build Performance · NuGet ▸ (Quick List Alt+Shift+N, Restore, Force Restore, Open 'packages' Folder, Manage for Solution, Upgrade Packages in Solution, Show Tool Window / Packages / Sources / Folders / Log, NuGet Settings) · EF Core ▸ (Add / Remove Migration, Update Database, Generate SQL Script, Drop Database, Create Migration Bundle, Scaffold DbContext, Show Migrations, Install or Update dotnet-ef) · New .NET Project…, Monitor .NET Process, .NET on This Machine…, Format, Verify Formatting, Analyze Upgrade to Newer .NET…, Insert New GUID, Analyze .NET Stack Trace… · Show Plugin Logs, Show Debugger Logs, Trace Debugger Protocol, Probe Platform LSP / DAP API… · Select Solution for Language Server…, Restart C# Language Server, Language Server Timings, Show Language Server Log (последние четыре — из модуля `roslyn`).

ПКМ в панели Solution: Add ▸ (New Project, Existing Project, New Solution Folder, Project Reference, Assembly Reference), Edit '<file>', Properties…, Rename Project…, Run / Debug Project, Build, Rebuild, Manage NuGet Packages…, EF Core ▸, Run Tests with Coverage, Run MSBuild Target…, Analyze Upgrade…, Format, Verify Formatting, Clean, Convert to .slnx…, Remove from Solution….

### 9.4 Тесты (43 класса) → области
| Область | Тесты |
|---|---|
| Язык / редактор | CSharpDeclarationsTest, CSharpEditorAssistTest, CSharpEnterAfterBraceTest, CSharpIndentRulesTest, CSharpStructureTest, CSharpTodoTest, IdentifierColorsTest, ItemTemplatesTest, FormattingTest, PostfixAndSurroundTest, TemplateOptionsTest |
| Roslyn LS | RoslynLanguageServerTest, RoslynLspClientTest, RoslynPolicyTest, RoslynCacheTest, RoslynResponseMemoTest, RoslynCapturedTrafficTest (фикстуры `src/test/resources/roslyn/capture-5.12`), RoslynPhase7Test, RoslynHierarchyTest, SolutionProblemsTest, MoveFileTest |
| Тесты .NET | TestingAndCoverageTest, TestingPlatformTest |
| Solution / MSBuild / NuGet | ParsersTest, DependenciesTreeTest, EditProjectFileTest, SolutionDiscoveryTest, ProjectPropertiesTest, ProjectContentTest, ProjectActionsTest, NewProjectWizardTest, MsBuildSchemaTest, MsBuildPackageCompletionTest, NuGetTest, NuGetSourcesTest, RiderPanelsTest, RiderPanelsMoreTest |
| Сборка / запуск / тесты / покрытие | CliFormatsTest, CommandStreamingTest, RunConsoleTest, TestingAndCoverageTest, DiagnosticsTest, DotNetLogsTest, ToolsTest, ServicesAndNodeActionsTest |
| Отладчик | DapClientTest, DebugLaunchTest |
| Monitor / EF / Endpoints / SDK | MonitorTest, MemoryDumpTest, EfCoreTest, EndpointsTest, SdkEnvironmentTest, SettingsAndSdkTest |
| Настройки / плагин | SettingsPagesTest, PluginTest, PlatformApiProbeTest |
| Без тестов | `upgrade` (разбор отчёта Upgrade Assistant) |

Живая проверка вне unit-тестов: `tools/ui-robot` (Remote Robot, `./gradlew.bat runIdeForUiTests`) на `debug-playground` (Console / Lib / Web / MultiTarget / Tests / Broken, маркеры `// BP:`); `tools/roslyn-lsp` (probe / capture / bench сервера); `dap-probe` (проверка адаптера).
