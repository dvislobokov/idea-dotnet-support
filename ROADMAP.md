# Roadmap

Поддержка .NET в IDE на платформе IntelliJ **без LSP, Roslyn и отладчика**: всё держится на `dotnet` CLI,
файлах проекта и лексере. Отмечается по мере готовности.

## Готово
- [x] Панель Solution: `.sln`/`.slnx`, solution folders, solution items, проекты, Dependencies (пакеты, проекты, сборки, central package management)
- [x] C#: лексер, подсветка, комментирование, скобки, кавычки
- [x] Раскраска типов / методов / членов эвристикой по токенам, палитра Rider (светлая и тёмная), страница Color Settings
- [x] Типы файлов (MSBuild, slnx, xaml, resx, config → XML) и иконки .NET-файлов

## Заход 1 — «можно писать и запускать код» (код готов, покрыт тестами; UI в живой IDE ещё не проверен)
### Сборка и запуск
- [x] Run configuration «.NET Project»: `dotnet run` / `watch` / `test`, аргументы, окружение, профили `launchSettings.json`
- [x] Создание конфигурации из контекста (проект в дереве, файл в редакторе), gutter ▶ у `Main`
- [x] Build / Rebuild / Clean / Restore для solution и проекта, вывод в Build tool window с разбором ошибок MSBuild
- [x] Кликабельные `file(line,col)` в консоли запуска
- [x] Автосоздание run configurations для запускаемых проектов solution (по одной на профиль `launchSettings.json`, как в Rider)
### Создание проектов и файлов
- [x] New Project (DirectoryProjectGenerator — GoLand, PyCharm, WebStorm...): шаблоны из `dotnet new list`, язык, framework, solution
- [x] Add → New Project в существующий solution (в т.ч. в solution folder)
- [x] New → .NET ▸ генераторы по категориям (C#, ASP.NET, Razor/Blazor, Tests, EF Core, Configuration, Resources): подходящие проекту — сразу, остальные в «Other»; partial-часть, тест для класса, копия .resx для культуры, `dotnet ef migrations add` (остальной EF — в меню .NET → EF Core), любой item-шаблон SDK
- [x] New → C# Class / Interface / Record / Struct / Enum с вычисленным namespace (RootNamespace + путь, file-scoped по `.editorconfig`)
### Действия над solution
- [x] Add Existing Project, Remove from Solution
- [x] New Solution Folder (правка `.sln`/`.slnx`)
- [x] Add Project Reference (диалог с галочками)
- [x] Автовыбор панели Solution при первом открытии папки с solution

## Заход 2 — навигация и редактор без парсера
- [x] Сканер объявлений поверх лексера (`CSharpDeclarations`): namespace (в т.ч. file-scoped), типы, члены (поля, свойства, индексаторы, методы, конструкторы, операторы, события, делегаты, enum-члены) по заголовку до `{` / `;` / `=` / `=>` и балансу скобок; generic-методы, tuple-типы, явные реализации интерфейсов, атрибуты, top-level program; на недописанном коде — меньше объявлений, без исключений. Парсер строит по нему PSI-узел на объявление (`CSharpDeclaration`), внутри членов токены остаются плоскими
- [x] Structure view и File Structure (иконки вида и видимости, сигнатуры), breadcrumbs, folding: тела объявлений, блок `using`, `#region` (с именем), серии `///` и `//`, блочные комментарии; `using` и doc-комментарии сворачиваются по настройкам платформы
- [x] Go to Class / Go to Symbol: `FileBasedIndex` по именам типов и членов, элемент с контейнером и файлом
- [ ] Переход между partial-частями, Related file (`.xaml` ↔ `.xaml.cs`, тест ↔ класс)
- [ ] Reformat Code через `dotnet format` (+ при сохранении)
- [x] Ошибки и предупреждения последней сборки в редакторе (`BuildProblems` + ExternalAnnotator): подчёркнуто слово по колонке компилятора, сообщение с кодом; диагностика следует за своей строкой при правках выше и исчезает, когда строку исправили; следующая сборка заменяет всё
- [x] Live templates для C# (33: `ctor` с именем типа, `prop*`, `cw`, циклы, `try`, `using`, `svm`, типы, `fact` / `theory` / `test` / `testm`, `region`…), Enter внутри `///` продолжает комментарий, третий `/` над объявлением даёт `<summary>` с `<param>` и `<returns>`
- [x] Отступы при наборе (Enter, набранные `{` `}` `)` `]`) — `LineIndentProvider` на движке правил из JSON (`resources/csharpIndent/rules.json`): упорядоченный список «условия → якорь + добавка», первое подошедшее выигрывает; 29 правил с примерами внутри (тест прогоняет все примеры и реальный файл построчно). Покрыто: блоки и K&R / Allman, аргументы и их перенос, цепочки `.`/операторы и возврат к началу оператора после `;`, тело без скобок у `if` / `for` / `else`…, `else` / `catch` / `finally`, `switch` (метки, секции, блок секции), инициализаторы / enum / switch-выражения, атрибуты, `#region` и `#if`, `/* */`, verbatim / raw-строки не трогаются. Размеры — из Code Style → C# (и `indent_size` / `indent_style` из `.editorconfig`), опции `csharp_indent_braces`, `csharp_indent_switch_labels`, `csharp_indent_case_contents`, `csharp_indent_case_contents_when_block` — из `.editorconfig`. Enter после `{`: если ниже есть `}` на «своём» отступе, вторая не вставляется, даже когда скобки файла не сходятся из-за недописанного кода (платформа считает скобки, а не раскладку). Форматирование файла целиком остаётся за CSharpier / `dotnet format`
- [x] Postfix templates и Surround With (2026-09-29): `CSharpExpressions.before` находит выражение перед точкой по токенам (цепочка имён / вызовов /
  индексаторов / литералов, `?.`, префиксы `new` / `await` / `!`), `startsStatement` — где можно ставить statement-шаблон; 21 шаблон
  (`codeInsight.template.postfixTemplateProvider`), ключ с точкой, платформа сама убирает `.key` перед `expand`. Surround With
  (`lang.surroundDescriptor`): statement-обёртки целыми строками с отступом на единицу глубже, `#region` / `#if`, `(expr)` / `!(expr)`
- [ ] Отступы: Auto-Indent Lines и вставка фрагмента по тем же правилам, метки `goto` (`csharp_indent_labels`), продолжение `//` по Enter (как в VS Code)
- [ ] Enter в `/* */`
- [x] TODO-индекс (2026-09-22): TODO / FIXME и прочие шаблоны Settings | Editor | TODO в комментариях C# (`//`, `///`, `/* */`) — окно TODO,
  счётчики, подсветка; в строках и именах не считаются (`CSharpTodoIndexer` на лексере + `CSharpIndexPatternBuilder`)
- [ ] WordsScanner (текстовый Find Usages), spellchecker
- [ ] Неактивные ветки `#if` по `DefineConstants`
- [ ] Переименование файла вместе с типом
- [x] Move `.cs` в другую папку (2026-09-29, по сообщению пользователя «мув не проводит полный рефакторинг»): `CSharpMoveFileHandler` после
  переноса спрашивает и меняет namespace на namespace папки — рефакторингом Roslyn «Change namespace to '…'» через EP
  `io.github.dotnetsupport.namespaceAdjuster` (реализация в модуле `roslyn`, обновляет usages по solution; сервер видит перенос с задержкой —
  повторные запросы `codeAction`), без сервера — только объявление в файле + нотификация. Серверный путь вживую не проверен

## Заход 3 — NuGet и тесты
- [x] Dependencies как в Rider: Imports (Sdk.props / Sdk.targets, Directory.Build.*, явные `<Import>`), узел на каждый TFM, Packages с разрешёнными версиями и транзитивными зависимостями, Projects, Assemblies, Analyzers, Frameworks со сборками — из `obj/project.assets.json`
- [ ] Dependencies: вложенные импорты внутри Sdk.props / Sdk.targets, анализаторы самого SDK (транзитивные проекты — сделаны 2026-09-28)
- [x] Окно NuGet: пакеты проекта с доступными обновлениями, поиск по фидам (API v3, источники из `dotnet nuget list source`), установка / обновление / откат / удаление через `dotnet add|remove package`, prerelease
- [x] Окно NuGet как в Rider: область «Solution / проект», единый список Installed + Available с иконками пакетов и действиями в строке, карточка пакета (версии, таблица проектов с Install / Update / Downgrade / Remove, зависимости по фреймворкам из `.nuspec`, лицензия, ссылки, теги)
- [x] Окно NuGet: вкладка Sources (фиды всех уровней `nuget.config`, добавить / удалить / включить / выключить, ссылки на файлы конфигурации) и вкладка Log (команды `dotnet` и их вывод)
- [x] Потоковый вывод команд `dotnet`: NuGet — построчно во вкладку Log; создание проектов, `dotnet sln`, EF, шаблоны, конвертация — задачами в Build tool window (окно открывается само только при ошибке), отмена прогресса убивает процесс
- [x] Карточка пакета по образцу Rider (шапка, Version с кнопками «во все проекты», сворачиваемые Info и Dependencies со сводкой, список проектов с кнопками-иконками), однотонная иконка окна для обеих тем и нового UI
- [x] Кнопки-иконки окна NuGet срабатывают надёжно (2026-09-22, по сообщению пользователя «кнопки установки ничего не делают»): были на `mouseClicked`,
  а Swing не шлёт это событие, если между нажатием и отпусканием мышь сдвинулась хотя бы на пиксель или кнопку успела пересоздать перерисовка
  карточки (версии, nuspec, выбор версии). Теперь платформенный `ClickListener` (нажатие + отпускание с допуском), двойной клик не устанавливает
  дважды; «+» в строке списка при области Solution больше не молчит, а открывает карточку пакета со списком проектов. Проверено в живой IDE
  событиями «нажатие и отпускание со сдвигом, без клика»: пакет ставится. Установка / удаление / restore — фоновой задачей IDE (прогресс внизу),
  вывод — задачей окна Build (открывается само только при ошибке) и во вкладке Log окна NuGet; кнопки карточки на это время выключены.
  **Настоящая причина «ничего не происходит»**: перед командой сохраняются документы, а в 2026.1 это требует write-intent блокировки, которой
  у обработчика мыши Swing нет, — сохранение бросало исключение, команда не запускалась. Исправлено в общем запуске команд (`WriteIntentReadAction`),
  проверено кликом через очередь событий AWT. Установленные пакеты для карточки и списка читаются в фоне (было SlowOperations на EDT)
- [x] Логи плагина в одной папке `~/idea-dotnet-logs` (2026-09-22, по просьбе пользователя), меню .NET → Show Plugin Logs: `commands/commands-ДАТА.log` —
  каждая команда `dotnet` плагина (фоновая и короткая) со временем каждой строки вывода, кодом выхода и длительностью, у зависшей — отметка
  «still running …, nothing printed for …» раз в 30 с; там же `dotnet-debugger`, `roslyn-language-server`, `DotNetBuild`. Пароли в командах
  замаскированы, логи старше двух недель удаляются. У процессов `dotnet` закрыт stdin: команда, решившая спросить что-то (учётные данные
  фида), падает сразу, а не ждёт 10 минут — гипотеза о зависшей установке пакета, проверить по логу
- [x] Диалог фида как в Rider (New / Edit): Name, URL, User, Password, Enabled, Allow insecure connections, Disable TLS certificate validation; учётные данные — в `nuget.config` через CLI и в хранилище паролей IDE для поиска по приватным фидам; пароли замаскированы в логах и прогрессе
- [ ] Окно NuGet: README пакета, правка версий в `Directory.Packages.props` при CPM, выбор файла конфигурации для нового фида (сейчас — куда пишет CLI, т.е. пользовательский)
- [ ] Устаревшие и уязвимые пакеты (`dotnet list package --outdated --vulnerable --format json`)
- [x] csproj / `Directory.Packages.props`: сетевой completion пакетов — id в `Include` / `Update` у PackageReference / PackageVersion / GlobalPackageReference / PackageDownload (поиск по фидам solution, порядок фида, версия, загрузки, ✓ verified; от 2 символов, перезапрос при наборе), версии в `Version` / `VersionOverride` (атрибутом и тегом; новые сверху, prerelease — по настройке или когда набирается `-`); выбор пакета дописывает `Version="<последняя>"`, кроме CPM и когда версия уже есть; запрос в пуле с отменой при следующем символе, кеш на 5 минут
- [ ] csproj: inlay «доступна новая версия», quick-fix обновления
- [x] Авто-`dotnet restore` при изменении csproj (настройка на странице NuGet, см. «Заход 4»)
- [x] Тесты: дерево результатов из TRX (SMTRunner), переход к исходнику, перезапуск упавших, ▶ у тестовых методов и классов (xUnit / NUnit / MSTest) с `--filter`
- [ ] Тесты: результаты по мере выполнения, а не после завершения (свой VSTest-логгер или протокол Microsoft.Testing.Platform)
- [x] Microsoft.Testing.Platform (2026-09-29): распознавание (`EnableMSTestRunner` / `UseMicrosoftTestingPlatformRunner` / `TUnit` / `MSTest.Sdk` /
  пакеты платформы), три режима `dotnet test` (VSTest; MTP с `TestingPlatformDotnetTestSupport` — опции после `--`; раннер SDK 10 по `global.json`
  `test.runner` или `dotnet.config` — `--project` и опции напрямую), TRX `--report-trx` / `--report-xunit-trx` в наш каталог, фильтры по
  фреймворку (MSTest — VSTest-выражение, xunit.v3 — `--filter-class` / `--filter-method`, TUnit — `--treenode-filter`), покрытие `--coverage`.
  Отладки MTP-тестов нет (нет хоста, ждущего отладчик; `TESTINGPLATFORM_LAUNCH_ATTACH_DEBUGGER` — только JIT-отладчик Windows) — нотификация.
  Факты: `dotnet test --help` в обоих режимах SDK 10.0.401, имена опций из `Microsoft.Testing.Platform.dll` 1.5. Вживую не проверено

## Заход 4 — проект и окружение
- [x] Настройки инструментов на той же странице: пути к `dotnet-counters`, `dotnet-stack`, `dotnet-gcdump`, `dotnet-dump`, `upgrade-assistant` (пусто — PATH и `~/.dotnet/tools`), кнопка Install / Update у каждого
- [x] Страницы настроек по образцу Rider, дочерние к Settings | Tools | .NET, опция в опцию; то, за чем у плагина пока ничего нет, показано выключенным с замком и причиной в подсказке (`settings/RiderSettingsUi.kt`):
  - **Toolset and Build** (на проект, workspace): MSBuild global properties (`-p:` для build / rebuild / clean / restore, `--property:` для run), Run build after solution is loaded, Restore NuGet packages before build (`--no-restore`), число процессов (`-m:N`), verbosity вывода, лог MSBuild в файл (`-fl -flp:`, папка, verbosity). Замок: Mono, версия MSBuild, авто-загрузка SDK, ReSharper Build, targets пропущенных проектов, design-time build
  - **NuGet** (на машину): Include prerelease (начальное состояние чекбокса окна и Upgrade Packages), автоматический restore после изменения `*.csproj` / `Directory.Packages.props` / `nuget.config` (в Log окна NuGet), Smart Restore on Build (`--no-restore`, пока `project.assets.json` новее всего, что решает состав пакетов), `--no-cache`, `--interactive`. Замок: unlisted, blob-фиды, dependency behavior, file conflict, uninstall-опции, restore engine, формат пакетов, credential providers
  - **Coverage**: что делать с новым покрытием (спросить / не применять / заменить / добавить к показанному — попадания суммируются), Activate Coverage View, проценты покрытия у файлов и папок в Project / Solution view
  - **Debugger**: Enable external source debug (= не Just My Code) и Allow property evaluations and other implicit function calls
  - **Language Server**: параметры `roslyn-language-server` — запуск (лог, авто-загрузка проектов, генераторы, доп. аргументы) и настройки,
    которые сервер запрашивает через `workspace/configuration` (анализ, проекты, completion, навигация, code lens, inlay hints, правки,
    генерация кода; прочее — строками `секция = значение`), галочка «Use the language server for C#». Apply доходит до работающего сервера:
    изменилась командная строка — перезапуск, изменились опции — `workspace/didChangeConfiguration`
  - Выключенные опции-заглушки «как в Rider, под замком» убраны со всех страниц (2026-09-21): на страницах только работающее
  - **Editor | Code Style | C#**: Tabs and Indents настоящие (ими отступает редактор, EditorConfig IDE их переопределяет), остальное с первой вкладки Rider и прочие вкладки — под замком (нужен форматтер внутри IDE)
- [x] Окно NuGet: вертикальный тулбар как в Rider — Restore (solution или проект из «Packages for»), Upgrade Packages in Solution, показать / скрыть карточку пакета, Settings, Help
- [x] Страница настроек (Settings | Tools | .NET): путь к `dotnet` с проверкой, список установленных SDK, статус `global.json` проекта, переключатели поведения (автосоздание run configurations, окно Build при каждой сборке, автопереключение на Solution view)
- [x] Уведомление при открытии solution: `dotnet` не найден, или `global.json` требует неустановленный SDK (политики `rollForward` сверены с настоящим CLI)
- [x] New Project в IntelliJ IDEA (2026-09-28): `GeneratorNewProjectWizard` «.NET» (EP `newProjectWizard.generator`), шаги имя / папка / Git +
  панель шаблона; `isEnabled()` только в IDEA — в GoLand / PyCharm / WebStorm остаётся `DirectoryProjectGenerator`, иначе две записи. **Вживую не проверен**
- [x] Project Properties (2026-09-26): ПКМ проекта → Properties… — Application / Build / Package по образцу Rider (target frameworks, OutputType,
  Nullable, LangVersion, ImplicitUsings, warnings, анализаторы, метаданные пакета); правки в безусловную `PropertyGroup` через документ по
  смещениям PSI (форматирование файла сохраняется; `addSubTag` платформы переформатировал группу отступом IDE), пустое значение убирает тег.
  Условные группы не редактируются
- [x] Честное содержимое проекта (2026-09-26): `Remove` по типу item-а, под который файл попадает у SDK (`.cs` → Compile, `.resx` → EmbeddedResource,
  `wwwroot` в web-SDK → Content, прочее → None), `DefaultItemExcludes`; спрятанное возвращает Show All Files серым; `DependentUpon` вкладывает
  файл под родителя; linked-файлы (`Include` за пределами проекта, `Link` / `LinkBase` / `%(RecursiveDir)`) — по пути Link с бейджем.
  `TreeStructureProvider` только для Solution view (узлы с `settings is SolutionTreeStructure`). Осторожно с KDoc: `dir/**/*` в комментарии
  открывает вложенный комментарий Kotlin
- [x] Solution filters `.slnf` (2026-09-26): узел в панели Solution с «N of M projects», проекты по списку фильтра, пустые solution folders обрезаны;
  сборка / тесты / Add Project Reference работают, правки solution через `dotnet sln` под фильтром скрыты. Solution ищутся по всей открытой папке
  (`SolutionFinder`, мимо `bin` / `obj` / `node_modules` / `packages` / dot-папок, корневые первыми, кэш до изменения файлов) — панель и language server одним поиском
- [x] Rename Project (2026-09-28): файл, по галочке папка, `.sln` / `.slnx` (`SolutionEditor.renameProject`), `ProjectReference` остальных проектов
  (`MsBuildItemEditor.renameProjectReference`), run configurations — одной командой. Add → Assembly Reference… (`HintPath`). Транзитивные
  проекты в Dependencies → Projects (по `.csproj` ссылок, цикл не раскрывается второй раз)
- [ ] Перемещение проекта, unload / reload, drag-and-drop в дереве
- [ ] Publish, `dotnet tool restore`, user secrets
- [ ] Редактирование шаблонов генераторов пользователем (сейчас зашиты в плагин)

## Заход 5 — файлы проекта и конфигурации
- [x] MSBuild-файлы (`.csproj`, `.props`, `.targets`, …): собственная составная схема вместо XSD — JSON-фрагменты в `resources/msbuildSchema` (ядро SDK, NuGet / CPM, упаковка, publish / trimming / AOT, анализ кода, ASP.NET / OpenAPI, SDK-контейнеры, тесты и coverlet, gRPC / Protobuf, EF Core, MinVer / GitVersion / SourceLink, WPF / WinForms / MAUI / Avalonia): ~290 свойств, ~50 item-ов с метаданными. Схема открытая: неизвестный тег — не ошибка. По ней: completion тегов по месту (свойства в PropertyGroup, item-ы в ItemGroup, метаданные в item-е, задачи в Target, структура в Project), атрибутов (Include / Remove / Update, метаданные атрибутами, Condition, атрибуты Target / Import / задач) и значений (в т.ч. элемента списка `a;b`); фрагменты инструментов, на которые проект ссылается (пакет или SDK, в т.ч. через `PackageVersion`), идут первыми, остальные — серым с «needs <пакет>»; вставка тега в готовом виде (`<Nullable>|</Nullable>`, `<PackageReference Include="|" />`); Ctrl+Q в файле и в списке completion (описание, значения, пакет, ссылка на документацию); предупреждение о значении вне закрытого перечисления (не для `$(…)`); подсветка `$(Property)`, `@(Item)`, `%(Metadata)`
- [ ] MSBuild: навигация по `Import` / `ProjectReference` / `$(Property)`, битые пути, completion `$(…)` по свойствам файла и `Directory.Build.props`, страница цветов для ссылок
- [x] ~~JSON Schema для `appsettings.json`, `launchSettings.json`, `global.json`~~ — даёт каталог SchemaStore JSON-плагина платформы (проверено по каталогу 2026-09-29), своей схемы не нужно
- [ ] Редактор `.resx` (таблица, несколько культур)
- [ ] `.sln`: подсветка и сворачивание секций

## Инструменты
Обёртки над `dotnet` CLI, файлами проекта и HTTP; семантика языка не нужна. ★ — взять первыми.

### Запуск и наблюдение за приложением
- [x] ★ Кликабельные стектрейсы в консоли Run (`at Type.Method() in File.cs:line 42`, в т.ч. локализованные)
- [x] Сворачивание стектрейсов в консолях: кадры `System.*` / `Microsoft.*` и разделители «End of stack trace…» → «<N framework frames>» (в т.ч. локализованные и в Thread Dump)
- [x] ★ Автооткрытие браузера по «Now listening on: http://…» с `launchUrl` профиля; включается галочкой, для сгенерированных конфигураций — по `launchBrowser`
- [x] ★ Раскраска уровней логов в консоли: `Microsoft.Extensions.Logging` (`info:` / `warn:` / `fail:` / `crit:` / `dbug:` / `trce:`), Serilog (`[… INF]`), NLog / log4net (`|WARN|`, `[ERROR]`); цвета — Console Colors → Log console
- [x] Окно Endpoints: маршруты minimal API (`MapGet`…, `MapGroup` через переменные и цепочки, `MapMethods`, `MapHealthChecks`) и контроллеров (`[Route]` на классе, `[HttpGet("{id}")]`, `[controller]` / `[action]`, абсолютные шаблоны) по токенам; переход к коду, запрос в `<Project>.http` с переменной хоста из `launchSettings.json`, открыть в браузере, копировать URL; значок на полях у каждого маршрута
- [ ] Endpoints: маршруты из констант и `nameof`, группы, объявленные в другом файле (extension-методы `MapXxxEndpoints`), `MapHub`, поиск маршрута через Search Everywhere
- [ ] Hot Reload для `dotnet watch`: кнопка Restart, индикатор «изменения применены / нужен перезапуск»
- [x] Окружение в run configuration: список из `appsettings.<Name>.json` + Development / Staging / Production; задаёт `ASPNETCORE_ENVIRONMENT` и `DOTNET_ENVIRONMENT`, перебивает launch-профиль через `dotnet run -e` (SDK 9.0.200+, с учётом `global.json`; на старых SDK — только переменные)
- [ ] Compound-конфигурация: запуск нескольких проектов solution разом

### Диагностика без отладчика
- [x] ★ Окно «.NET Monitor» (справа, графики столбиком, как Monitoring в Rider): CPU и память процесса приложения средствами ОС (без внешних инструментов; `dotnet run` / `watch` — лаунчер, меряется его дочернее приложение) и счётчики рантайма через `dotnet-counters collect` (GC heap, скорость аллокаций, время в GC, сборки/с, активные запросы сервера и HttpClient, p95 длительности запроса, исключения и lock contention, очередь thread pool); процессы из IDE подхватываются сами, остальные .NET-процессы машины — из списка; имена счётчиков .NET 9+ и старых рантаймов; предложение установить tool
- [x] Monitor: в списке только процессы, запущенные из IDE; остальные .NET-процессы машины — по галочке «All .NET processes» (запоминается). Программа под отладчиком тоже попадает в список: её pid берётся из события `process` адаптера (**вживую не проверено**)
- [ ] Monitor: свои `Meter` приложения по имени, запросы/с, EF Core и Kestrel, пауза и масштаб времени, строка состояния в Services
- [x] ★ Thread Dump в .NET Monitor (`dotnet-stack report`): потоки с кодом приложения наверху, одинаковые стеки свёрнуты, кадры проекта кликабельны (тип ищется по имени файла, метод — в файле; async, лямбды, конструкторы разворачиваются в исходные имена)
- [x] ★ Heap Snapshot в .NET Monitor (`dotnet-gcdump report`): куча по типам (объекты, байты), фильтр, сравнение с любым более ранним снимком того же процесса — Δ объектов и Δ байт, поиск утечек
- [x] Аллокации по строкам в редакторе (2026-09-29) — измеренные байты и объекты в секунду там, где Heap Allocations Viewer помечает «здесь возможна аллокация». Помощник `allocwatch/Program.cs` (EventPipe, `GCAllocationTick`, разбор на лету через `TraceLog.CreateFromEventPipeSession`, строки по PDB) плагин несёт исходником и собирает на машине пользователя общим сборщиком `cli/DotNetHelper` (проверено на SDK 10 и 9; в отличие от индексатора нужны два пакета NuGet). Раз в секунду — строка JSON со сводкой за окно 10 с. В редакторе (`allocations/`): числа в конце строки (`EditorLinePainter`), сумма у объявления метода с несколькими выделяющими строками, полоса слева с подсказкой (типы, всего с подключения). Переключатель — меню .NET → Show Allocations in Editor; подключение к программе, запущенной из IDE (Run и Debug), выбор процесса в дереве `dotnet run` — `AllocationTargets`. Правки после подключения числа не сбивают (маркеры документа). Прототип и замеры — `tools/alloc-probe/README.md`: привязка 95,1% / 4,5% / 0,3% / 0,2% при ожидаемых 95 / 4 / 0,4 / 0,2; цена около 2,4 мкс на событие. Ограничения — в `allocwatch/README.md`. Тест `AllocationsTest` (на настоящем выводе помощника); сценарий — `debug-playground/Console/Allocations.cs`. Первая проверка пользователем (2026-09-29): чисел не было. Причин две. Переключатель не был включён, и плагин об этом молчал — теперь включившему отвечает уведомление (программа не запущена / слушаю такую-то), любая неудача подключения — уведомление с причиной, весь путь — в `idea.log`. И ошибка в коде: на Windows Java не сообщает командную строку процесса, `dotnet run` не отличался от программы, и помощник подключился бы к запускающему процессу — командные строки берутся из списка процессов платформы (`OSProcessUtil.getProcessList()`; проверено на настоящем дереве `dotnet run` площадки). Пользователь не нашёл переключатель: он стоял внизу меню .NET среди служебных пунктов — перенесён под «Monitor .NET Process» и добавлен в контекстное меню редактора файла `.cs`. С исправлениями в IDE вживую не проверено
- [ ] `dotnet-trace`: запись трассы, просмотр flame graph (speedscope во встроенном браузере)
- [x] ★ Memory Dump в .NET Monitor (`dotnet-dump`, проверено UI-роботом 2026-09-22 на сценарии `leak` из `debug-playground`), аналог «кто держит объект» из dotMemory:
  дамп `--type Heap` (процесс не останавливается, секунды), один `dotnet-dump analyze` держится открытым на всё время диалога (команды — миллисекунды);
  типы кучи с фильтром → объекты типа (первая 1000) → для объекта пути удержания (`gcroot`: корень — handle / локальная кадра / очередь финализации,
  дальше объект за объектом), поля (`dumpobj`, ссылка открывается двойным кликом, Back) и сколько он удерживает (`objsize`); вкладка SOS Console —
  любая команда (`dumpasync`, `syncblk`, `clrstack -all`, `finalizequeue`…); Save Dump As… (открывается в VS / WinDbg / PerfView). Дамп лежит во
  временном каталоге IDE и удаляется при закрытии. Факты пробы: без терминала `analyze` завершает ответ строкой `<END_COMMAND_OUTPUT>` (ошибку —
  `<END_COMMAND_ERROR>`), приглашения нет; подпись `static variable: …` у шага `gcroot` — догадка SOS, бывает не про то поле, показывается как есть
- [ ] Memory Dump, дальше: сравнение двух дампов по типам (как у Heap Snapshot), тип поля вместо усечённого `...CoreLib]]`, группировка одинаковых путей
  удержания; сохранение `.gcdump` Heap Snapshot в файл; график поколений GC (gen0/1/2/LOH/POH); retained size и дерево доминаторов — только своим
  помощником на ClrMD (отдельное архитектурное решение)

### Качество кода силами компилятора
- [x] Покрытие тестов: `--collect:"XPlat Code Coverage"` (coverlet) → Cobertura → полосы на полях редактора (покрыто / частично / нет), сводка по файлам в окне «.NET Coverage»
- [ ] Покрытие: сводка по проектам и методам, покрытие для выбранного теста, хранение нескольких запусков
- [ ] Анализаторы Roslyn через сборку: включение `EnforceCodeStyleInBuild` / `AnalysisLevel`, вкладка Problems с группировкой по правилу, «подавить в `.editorconfig`»
- [x] Форматирование C#: Reformat Code / Actions on Save / перед коммитом через платформенный сервис форматирования. Настройка Formatter на странице .NET (Auto / CSharpier / dotnet format / None, хранится с проектом); Auto берёт CSharpier, только если репозиторий им пользуется (`.csharpierrc*`, запись в `dotnet-tools.json` или `.config/dotnet-tools.json`, пакет `CSharpier.MsBuild`)
- [x] CSharpier: обе линейки CLI (0.x `dotnet-csharpier`, 1.x `csharpier format`), приоритет у tool из манифеста репозитория, HTTP-сервер CSharpier на проект (7–12 мс на файл) с откатом на разовый запуск через stdin, несохранённый текст без записи на диск, кнопка `dotnet tool restore`, строка в .NET Tools
- [x] `dotnet format whitespace --folder` как интерактивный форматтер: копия файла в зеркале каталогов с цепочкой `.editorconfig`, без загрузки MSBuild (~1,3 с)
- [x] Format / Verify Formatting для проекта и solution: `csharpier format|check` или `dotnet format [--verify-no-changes]`, вывод в окно Build
- [ ] Форматирование: четвёртый вариант — сервером Roslyn (после LSP), форматирование `.csproj` / XML через CSharpier 1.x, позиция ошибки ссылкой в уведомлении
- [ ] Устаревшие (`--deprecated`) пакеты и лицензии пакетов по метаданным NuGet
- [ ] Неиспользуемые пакеты и ссылки: эвристика по `using`, предупреждение без автоудаления
- [x] `.editorconfig` для C# (2026-09-29): completion и документация опций `csharp_*` / `dotnet_*` / naming rules / severity — описания платформенного EditorConfig-плагина, включаются ключами реестра `editor.config.csharp.support` и `editor.config.resharper.support` (`EditorConfigDotNetSupport`). Вживую не проверено
- [x] Баннеры над файлом C# (2026-09-29, `CSharpEditorBanners`, `RoslynSolutionBanner`): нет сервера, файл вне проекта, исключён из проекта, проект не восстановлен, solution не выбран; «Don't Show Again» на каждый вид. Тест `EditorExtrasTest`. Вживую не проверено
- [x] Intentions уровня файла (2026-09-29, `CSharpIntentions`): Change namespace, Move type to file, Rename file to type, Add partial part, Create test; описания в `intentionDescriptions/`. Alt+Insert → Generate... (`RoslynGenerateAction`): code actions Roslyn «Generate / Implement …» списком. Вживую не проверено
- [x] Серый текст продолжения, первая партия (2026-09-29): `CSharpGhostText` + `CSharpGhostTextProvider` (основная часть, EP `inline.completion.provider`, по токенам) — авто-свойство `{ get; set; }`, `new();` после `= `, присваивание параметра в конструкторе; `ArgumentSuggestions` в `RoslynLambdaGhost` (модуль `roslyn`) — аргументы с именами параметров по `signatureHelp`. `isEnabled` провайдера токенов точный (true только когда есть что показать), чтобы не отнимать очередь у провайдера сервера: платформа берёт один провайдер на событие. Имена в области видимости — `CSharpScopeNames` (параметры члена, локальные выше курсора, поля и свойства типов). Тест `GhostTextTest`. Вживую не проверено. Отложено: `override`, ветки `switch` по enum, `catch (Exception e)`, `foreach`, `ThrowIfNull`
- [x] Серый текст продолжения, вторая партия (2026-09-29, всё по токенам, `CSharpGhostText`): namespace папки после `namespace ` (`CSharpNamespaces.forDirectory`, стиль — `isFileScopedPreferred`), имя типа по имени файла, `ILogger<Класс>` (поле или параметр), параметры конструктора по readonly-полям и get-only свойствам, `catch (Exception e)`. Строка, которую набирают, перед сканером объявлений заменяется пробелами (`withoutLine`): она ещё не объявление. Контекст (`Context`: namespace, стиль, `new()`) вычисляется по требованию — провайдер зовут на каждое нажатие. Исправлено чтение документа вне read action в `RoslynLambdaGhost` (исключение из лога пользователя). Вживую не проверено. Дальше (нужен индекс или сервер): пара для `AddScoped<IService, `, заполнение инициализатора объекта, `override`, ветки `switch`
- [x] Парная `>` для `<` у generic (2026-09-29, по замечанию пользователя: после `AddSingleton<` скобка не закрывалась): `CSharpAngleBrackets` + typed / backspace handlers в `lang/CSharpEditing.kt`. В brace matcher `<` не добавить — это и оператор; решает текст перед скобкой (имя с заглавной буквы вплотную), в том числе перед `()` вызова. Поставленная пара помнится (`RangeMarker` в user data редактора) и снимается, когда набранное внутри оказывается не типом: `Count<5`, `Count<=`, `Count<limit;` (`continuesTypeArguments`). Вживую не проверено
- [x] Страница о плагине в IDE (2026-09-29): `docs/demo.html` переписана короче, под главные возможности (редактор, отладчик, Solution, тесты) с макетами окон вместо заглушек-скриншотов, без внешних шрифтов и ссылок; сборка кладёт её в плагин как `welcome/index.html` (`processResources`). `WelcomePageActivity` открывает её вкладкой редактора (`HTMLEditorProvider`, JCEF) один раз на версию плагина — после установки и после обновления; без JCEF — в системном браузере. Тема страницы — по теме IDE, ссылки в репозиторий и сценарий демо в IDE скрыты (`data-host="ide"`). Повторно: меню .NET → Welcome to C# Project Support. Тест `WelcomePageTest`. В IDE вживую не проверено Обновлена 2026-09-29 под сделанное после неё: раздел «Аллокации видны прямо в коде» с макетом редактора, completion без импорта и порядок по контексту, значение серым текстом, Reload, значок сервера в статус-баре; строка в сравнении с Rider и в «Чего нет» (выборка). Переписана 2026-09-29 по замечанию пользователя («текст малопрофессиональный, сосредоточенность на мелочах; продать, а не выступать перед гиками»): тексты о пользе, а не об устройстве (по три пункта на раздел, блок из трёх утверждений под первым экраном), разделы Редактор / Отладчик / Память / Проект / Тесты. Макеты анимированы без библиотек: набор кода с серым текстом и Tab, `WriteLi` → список → `Console.WriteLine();` + `using`, шаг отладчика, значения аллокаций раз в секунду, прогон тестов. Разметка — последний кадр каждой анимации: без скриптов страница полная; вне экрана сцены стоят. Сцены проигрываются сразу и всегда (решение пользователя 2026-09-29: кнопки запуска нет), `prefers-reduced-motion` действует только на украшения — появление блоков и плавную прокрутку; набор идёт по часам, а не по числу таймеров, в подвале метка редакции страницы. Проверено в браузере, в IDE (JCEF) не проверено. Презентация `docs/presentation.md` (Marp, 19 слайдов, `presentation.pptx` пересобран) переписана 2026-09-29 в том же ключе: задача команды с несколькими языками → решение → кому нужно → пять возможностей → на чём построено → плагин и Rider → границы → план → три шага внедрения → сценарий демонстрации. Промпт, по которому такую же страницу делают для другого плагина, — `docs/demo-page-prompt.md`.
- [x] Документация плагина и настройки по-русски (2026-09-29, по просьбе пользователя). (1) `docs/guide.html` — вторая страница той же вкладки: начало работы, меню .NET и контекстное меню Solution, окна, редактор, запуск и отладка, расход памяти, **каждый параметр каждой страницы настроек** (название по-русски и по-английски, значение по умолчанию, что делает), где что хранится, что делать при неполадках. Генерируется `tools/guide/generate.py` из файлов строк и `RoslynOptions`, оформление берёт из `demo.html`. Открывается из меню .NET → Plugin Documentation, по ссылке со страницы о плагине и со страницы настроек .NET (оттуда — в системном браузере: вкладку за модальным окном не видно). (2) Страницы открываются файлами: `WelcomePage.write` кладёт обе в `<кэш IDE>/dotnet-support/welcome/<тема>/`, вкладка открывается по адресу `file:///…`, ссылки между страницами обычные. (3) Страницы настроек на двух языках: `DotNetBundle` (не `DynamicBundle`: тот следует только языку IDE, а русского пакета у IDE нет) + параметр «Language of the settings pages» на странице .NET; около 130 текстов шести страниц и 33 опции сервера языка. Названия страниц в дереве Settings платформа берёт сама по языку IDE (`key` + `bundle` в `plugin.xml`), выбор плагина на них не действует. Меню, действия и окна — английские. Тест `DotNetBundleTest`. Вживую не проверено: ни русские страницы настроек, ни открытие страниц файлами во встроенном браузере IDE
- [x] `;` после void-метода из completion (2026-09-29, по замечанию пользователя: Rider вставляет `Console.WriteLine(|);`): `RoslynCompletionPolicy.call` — `();` когда метод возвращает `void` и дальше на строке пусто; у метода без параметров во всех перегрузках курсор встаёт после вызова. Тип и параметры — из документации resolved-элемента (`RoslynSignatureTail`); resolved-элемент платформа держит во внутреннем `LspCompletionObject`, он берётся по имени класса (тест проверяет, что класс и метод на месте). Исправлено в тот же день по замечанию пользователя (`;` вживую не появлялась): платформа оборачивает элемент плагина в свой `LspLookupElementDecorator` и resolved-элемент держит в обёртке, поэтому он берётся из `context.elements`, а не из элемента обработчика. Элемент, который платформа не успела разрешить, получает обычные `()`, плагин сам шлёт `completionItem/resolve` и дописывает вызов, если за это время ничего не набрано (`completeWhenResolved`). Набранная `;` перешагивает `;`, которой кончается строка (`CSharpSemicolonTypedHandler`). Вживую не проверено
- [x] `<>` у generic из completion (2026-09-29, по замечанию пользователя: Rider вставляет `AddSingleton<|>()`). Факты — зонд `tools/roslyn-lsp/capture_generics.py`: метка `Имя<>`, вставляется голое имя, сигнатура в документации resolved-элемента. Тип (class / struct / interface) с меткой `<>` получает `List<|>`, после `new` — `new List<|>()`. Метод получает `<|>()`, только когда аргументы типа не из чего вывести (`RoslynCompletionPolicy.needsTypeArguments`: аргумента нет ни в параметрах, ни в получателе extension-метода); `Select`, `Where` — как раньше `(|)`; void — `<|>();`. Набранная `(` после `>` перед пустыми `()` входит в вызов (`CSharpAngleBrackets.entersCall`). Попутно: строка списка у generic-метода теперь показывает сигнатуру и тип (метка `X<>` не находилась в сигнатуре), число перегрузок читается с неразрывными пробелами сервера. Тест `GenericCompletionTest`. Вживую не проверено. Дальше — после проверки вживую сузить эвристику набора `<`
- [x] Устаревший resolve не роняет корутину платформы (2026-09-29, по исключению из лога пользователя): на `codeLens/resolve`, `inlayHint/resolve`, `completionItem/resolve` для изменившегося документа Roslyn отвечает ошибкой «Resolve version … does not match current version …» (ContentModified), LSP-клиент платформы её не ловит — в логе за утро 60 необработанных исключений. `RoslynStaleResolve` в обёртке сервера превращает такой ответ в исходный, неразрешённый элемент. Тест в `RoslynPhase7Test`. Вживую не проверено
- [x] Значки в стиле нового интерфейса (2026-09-29, по просьбе пользователя «как в Rider»): 18 значков файлов и узлов дерева перерисованы генератором `tools/icons/generate.py` — сетка 16×16, линии в один пиксель, палитра значков платформы, светлый и тёмный вариант каждого (`name_dark.svg`; раньше значки были одни на обе темы, с заливкой). Проект — `C#` / `F#` / `VB` в рамке, файл — те же буквы без рамки, solution — окно с «кристаллом». Новые: `dependencies` (узел Dependencies вместо платформенного `PpLibFolder`) и `propertiesFolder` (папка `Properties` / `My Project` прямо в папке проекта, `DotNetDirectoryIconProvider`). Формы свои, из Rider ничего не взято. Значки tool windows не менялись. Тест `IconsTest`. В IDE вживую не проверено
- [x] Параметры шаблона в New Project прокручиваются (2026-09-29, по замечанию пользователя: у шаблона с большим числом параметров диалог не помещался на экран). Строки параметров лежат в области прокрутки высотой не больше 320 px и не больше 40% высоты экрана (`TemplateOptionsView`, `TemplateOptionsLayout`), горизонтальной прокрутки нет — длинные пояснения переносятся; шаг колеса 20 px. Окно диалога растёт под параметры, но не больше 90% экрана, возвращается на экран, если вышло за край, и не сжимается обратно при смене шаблона. Описание варианта в выпадающем списке показывается только в раскрытом списке — раньше самое длинное описание задавало ширину поля и диалога. Тест `TemplateOptionsScrollTest`. Вживую не проверено
- [x] Сервер C# в статус-баре (2026-09-29, по просьбе пользователя): свой виджет `RoslynStatusWidget` (`statusBarWidgetFactory`, id `DotNet.Roslyn.Status`) виден всё время, пока жив процесс сервера, — платформенный виджет language services показывает сервер только рядом с файлом его языка. Текст — `Roslyn: loading Shop.sln...` / `Roslyn: Shop.sln`. По клику — окно: состояние, CPU и память **дерева процессов** сервера (на Windows tool — это `.cmd`, сервер — его потомок, а сам он запускает MSBuild-хосты), PID, число процессов, время работы; обновляется раз в секунду, пока окно открыто, замер вне UI-потока (`RoslynServerUsage` на `monitor/ProcessSampler`). Действия: Restart, Select Solution..., Log, Timings, Settings.... Процесс берётся из `startServerProcess` описателя клиента. Строка сервера из платформенного виджета language services убрана по просьбе пользователя (один значок вместо двух): `createWidgetItems` интеграции отдаёт пустой список, `RoslynWidgetItem` и `RoslynWidgetUpdater` удалены. Тест `RoslynStatusWidgetTest`. Вживую не проверено
- [x] Порядок списка completion по контексту (2026-09-29, `RoslynCompletionRanking`): к приоритету по виду элемента добавляется надбавка — ожидаемый тип (+25), имя как у параметра или переменной слева (+30 точное, +12 частичное: `token` ~ `cancellationToken`), локальная переменная или параметр (+3, объявлена в пределах 5 строк +1), выбиралось раньше (до +5, логарифм числа выборов). Ожидаемое — по тексту (`int total = `, `count = `, `return ` с учётом `async Task<T>`, цепочка `order.` пропускается) и по `signatureHelp` для аргумента вызова (таймаут 400 мс, один запрос на место, `RoslynCompletionContext`). Тип кандидата сервер в списке не присылает, поэтому он берётся из текста файла (`lang/CSharpScopeTypes`: параметры, локальные с `var`-выводом по инициализатору, поля, свойства, методы) — после точки типы файла не применяются, работает только имя. Лямбда на месте делегата по-прежнему выше всего. Тест `CompletionRankingTest`. Сценарий для проверки вживую — `debug-playground/Console/Editor/CompletionRanking.cs` (маркеры `// TYPE:`). Вживую не проверено
- [x] Статистика подсказок (2026-09-29, пакет `suggest`): сколько раз серый текст показан и принят — по каждому правилу (показ считается один раз на место: файл и строка, а не на каждую набранную букву); какую позицию в списке занимал выбранный элемент (первая, 2–3, 4–10, ниже) и по какой причине плагин его поднял. Хранится локально в настройках IDE (`dotnetSuggestionStats.xml`, не синхронизируется), никуда не отправляется. Отчёт — меню .NET → Suggestion Statistics (Copy, Reset). Принятие серого текста — `insertHandler` провайдера, выбор в списке — `LookupManagerListener`. Тест `SuggestionStatsTest`. Сценарий для проверки вживую — `debug-playground/Console/Editor/CompletionRanking.cs` (маркеры `// TYPE:`). Вживую не проверено
- [x] Значение серым текстом без Ctrl+Space (2026-09-29, по замечанию пользователя «просто предлагать»): `CSharpValueGhost` — после `Тип имя = `, `имя = `, `return ` предлагается переменная, поле или свойство из файла, если один кандидат явно впереди остальных (тип, затем имя, затем локальность; отрыв от второго не меньше 3): `int amount = ` → `count;`. Методы не предлагаются, при равных кандидатах подсказки нет. Правило `value` в статистике. Аргументы вызова: `Save(` не предлагало `order` (запрос `signatureHelp` уходил раньше, чем сервер получал изменение документа) — для метода, объявленного в этом же файле, параметры берутся из текста (`CSharpLocalCalls`, работает и до загрузки solution), для остальных запрос повторяется через 150 и 350 мс. После вставки метода из списка completion (Tab / Enter) серый текст аргументов запрашивается самим плагином (`RoslynLambdaGhost.offer`, событие `ManualCall` своему провайдеру): вставка из списка — не набор, событие набора не приходит. Сценарий — `debug-playground/Console/Editor/CompletionRanking.cs`. Тест `GhostTextTest`. Вживую не проверено
- [x] Доработка по живой проверке (2026-09-29, три замечания пользователя). (1) `decimal sum = ` не предлагал `Total`: метод предлагается целым вызовом `Total(order);`, если найдены все обязательные аргументы (перегруженные методы не предлагаются). (2) `Run(` не предлагал ничего: аргумент подбирался только по точному имени; теперь `CSharpArguments.pick` — имя, затем единственная переменная типа параметра, среди нескольких — та, чьё имя оканчивается так же (`token` — `cancellationToken`). (3) `;` после метода из списка ставилась только для `void`: теперь и когда вызов завершает оператор — значение объявления, присваивания, `return`, expression body (`RoslynCompletionPolicy.endsStatement`); в фигурных скобках инициализатора объекта не ставится. При наборе `(` руками `;` не добавляется. Тесты `GhostTextTest`, `RoslynPhase7Test`. Вживую не проверено
- [x] Reload Solution / Reload Project (2026-09-29, по замечанию пользователя: созданные мимо IDE папка и файл не появились в дереве): меню .NET, ПКМ на узле solution / проекта, кнопка рядом с «глазом» в заголовке окна Project (только в Solution view). `SolutionReload`: сохранить документы → перечитать папку с диска (`VfsUtil.markDirty` + `RefreshQueue`) → забыть разобранное (`SolutionService.reload`: всё или один проект с его `project.assets.json` и props / targets) → run configurations → перерисовать дерево → топик `SolutionReloadListener`. Модуль `roslyn`: Reload Solution перезапускает сервер, Reload Project шлёт `workspace/didChangeWatchedFiles` по файлу проекта. Причина исходного замечания: IDE смотрит на диск, когда её окно получает фокус, а файлы появились, пока окно было активно. Проверка вживую — раздел «Solution view» в `debug-playground/README.md`. Тест `SolutionReloadTest`. Вживую не проверено
- [x] Свой индекс сборок для подсказок без импорта (2026-09-29): `WriteLi` → `Console.WriteLine(|);` + `using`. Индексатор `indexer/Program.cs` (C#, `System.Reflection.Metadata`, сборки не загружаются) плагин несёт **исходником** и собирает на машине пользователя под установленный SDK (`index/IndexerTool`: SDK 10 → `net10.0`, SDK 9 → `net9.0`, сеть не нужна; проверено на обоих, 2,2–2,5 с, индексы побайтно одинаковы). Формат `.dnix` — файл на сборку, имя — MVID, читается отображением в память (`index/AssemblyIndex`). Проект видит только свои сборки (`index/ProjectAssemblies` по `project.assets.json`: пакеты с транзитивными, эталонные пакеты фреймворков, собранные проекты solution). Индексация — в фоне при открытии проекта, после restore и по Reload; один запуск на все проекты solution. От повторной индексации защищают блокировка папки индексов между процессами (её держит индексатор), блокировка сборки индексатора и очередь внутри IDE. Кэш solution из 5 проектов — 2,0 МБ (317 сборок, 0,73 с). Completion — `index/ImportCompletion`: от трёх букв, не после точки и не на месте имени; вставка по общим правилам скобок и `;` (`lang/CSharpCalls`), `using` с учётом implicit и global usings (`CSharpUsings`). Parquet отклонён: нужен точечный поиск. Тесты `AssemblyIndexTest`, `ImportCompletionTest`; сценарий — `debug-playground/Console/Editor/ImportCompletion.cs`. Подробности и замеры — `indexer/README.md`. Вживую не проверено
- [x] Completion в C# не смотрит на регистр (2026-09-29, по проверке, которую просил пользователь): платформа сопоставляет префикс по настройке IDE (Editor | General | Code Completion | Match case, по умолчанию «первая буква»), и `writeli` не находил `WriteLine` — ни среди элементов сервера, ни среди элементов индекса. `lang/CSharpCaseInsensitiveCompletion` — первый в цепочке (остальные идут `after dotnetCaseInsensitive`), запускает остальных с `CamelHumpMatcher(prefix, false)`; совпавшее и по регистру по-прежнему выше. Настройка IDE для C# не действует. Серый текст остаётся чувствительным к регистру намеренно: он дописывает остаток слова и не может заменить уже набранные буквы. Тест в `ImportCompletionTest`. Вживую не проверено
- [x] Дедлок настроек при старте (2026-09-30, найден роботом: в песочнице не стартовали ни сервер Roslyn, ни проверка SDK — всё, что трогает
  `DotNetSettings`, висело в `getService`). Причина: `PluginLanguage.toString()` брал текст из `DotNetBundle`, бандл спрашивал язык у
  `DotNetSettings`, а XML-сериализатор вызывает `toString()` констант enum, пока `DotNetSettings` ещё загружается, — сервис ждал сам себя.
  Воспроизводилось у всех, кто хоть раз выбрал язык страниц настроек. У `PluginLanguage`, `MsBuildVerbosity`, `FormatterChoice` вместо
  `toString()` теперь `label`, combo box рисует его через `textListCellRenderer`; `DotNetBundleTest` сторожит `toString() == name`

### Проект и зависимости
- [ ] ★ «Почему этот пакет здесь»: цепочка до транзитивной зависимости по `project.assets.json` (аналог `dotnet nuget why`)
- [ ] ★ Диаграмма зависимостей проектов по `ProjectReference`, подсветка циклов
- [ ] Обновление target framework по всему solution одним действием, с диффом
- [ ] Перевод на Central Package Management: сбор версий в `Directory.Packages.props`, конфликты версий
- [x] Конвертация `.sln` → `.slnx` (`dotnet sln migrate`; обратного направления в CLI нет)
- [ ] Окно глобальных и локальных tools: `dotnet tool list`, установка, обновление, запуск
- [ ] Workloads: `dotnet workload list / install`, подсказка об отсутствующем workload (MAUI, wasm)

### Данные и API
- [x] EF Core, команды (меню .NET → EF Core и то же подменю у проекта с EF в панели Solution): Add Migration, Remove Last Migration, Update Database (в т.ч. откат к миграции и `0`), Generate SQL Script (from / to, idempotent; в файл или scratch), Drop Database. Общий диалог: migrations project, startup project (по умолчанию — приложение, ссылающееся на проект), `DbContext` и миграции из исходников без сборки, окружение (`appsettings.*.json`), конфигурация / TFM, `--no-build`, аргументы приложения, предпросмотр командной строки; выбор запоминается по проекту
- [x] EF Core, страховки: `dotnet-ef` из манифеста репозитория или глобальный (установка / обновление, предупреждение «tool старее runtime»), добавление `Microsoft.EntityFrameworkCore.Design` версии EF проекта, подтверждение отката, Drop только после `dbcontext info` и ввода имени базы, предупреждение о `DropTable` / `DropColumn` в новой миграции, разбор типовых ошибок (нет tool / Design, не создаётся `DbContext`, несколько контекстов, миграция уже применена → Revert and Remove), `--connection` маскируется в логах
- [x] EF Core, окно миграций (tool window «EF Core», меню .NET → EF Core → Show Migrations; кнопка на полосе — только если в solution есть EF): проект → `DbContext` → миграции, новые сверху, читаются из исходников без сборки и обновляются при изменении файлов. Статус applied / pending — по кнопке Refresh Status (`migrations list --json`, сборка + подключение к базе) и сам после Update / Drop / Remove; новая миграция сразу pending; «model has changes that are not in a migration» (`has-pending-model-changes`, EF 8+); у контекста видно, с каким startup-проектом и окружением спрашивали. На миграции: Update Database to Here, Generate SQL Script from / to Here, Remove (последняя), Open Migration / Designer, Copy Name — через общий диалог с предзаполнением
- [x] EF Core: Scaffold DbContext from Database (меню .NET → EF Core; строка подключения или ссылка `Name=ConnectionStrings:…` из `appsettings*.json` startup-проекта, провайдер — по пакету проекта, таблицы / схемы, папки, `--data-annotations`, `--no-onconfiguring`, `--force`; нет пакета провайдера → Add Package; строка подключения маскируется в логах) и Create Migration Bundle (self-contained, target runtime, Reveal после сборки)
- [x] EF Core: gutter-иконки у классов `: …DbContext` (Add Migration, Update Database, Script, Bundle, Drop, Show Migrations — с этим контекстом) и у миграций (Update Database to / Script to / from — с этой миграцией и контекстом из Designer-файла)
- [x] EF Core: Design-Time DbContext Factory (`IDesignTimeDbContextFactory<T>`) — New → .NET → EF Core, gutter-иконка `DbContext` (Create Design-Time Factory, рядом с контекстом) и кнопка в нотификации «DbContext не создаётся»; `Use…` — по провайдеру проекта, строка подключения: аргумент после `--` → `ConnectionStrings__<имя из appsettings>` → локальная база
- [ ] EF Core: `dbcontext optimize`
- [ ] EF Core: run configuration «EF Core Command» (Save as Run Configuration из диалога) и before-launch «Apply EF Migrations»
- [ ] Строки подключения из `appsettings*.json` и user-secrets → Data Source во встроенном Database tool
- [ ] User Secrets: открыть `secrets.json`, `init`, дифф ключей с `appsettings.json` (ожидаемые, но не заданные)
- [ ] OpenAPI: генерация клиента (`dotnet-openapi` / NSwag / Kiota) из `swagger.json`; скачивание спецификации с запущенного приложения
- [ ] gRPC: добавление `<Protobuf Include>` для `.proto` с выбором Client / Server

### Публикация и контейнеры
- [ ] Мастер Publish: конфигурация, RID, self-contained, single-file, trimmed / AOT; сохранение как run configuration и `.pubxml`
- [ ] Контейнер без Dockerfile: `dotnet publish /t:PublishContainer`, выбор образа и тега
- [ ] Размер публикации: таблица «что занимает место» (для trimmed / AOT)
- [ ] Aspire: запуск AppHost, ссылка на dashboard из вывода

### Мелочи редактора без парсера
- [ ] ★ Запуск одиночного `.cs` (`dotnet run file.cs`, .NET 10) и `.csx`: run configuration и ▶, scratch-файлы C#
- [ ] ★ Paste Special: JSON → record-ы / классы с `JsonPropertyName`; XML → классы
- [x] Insert New GUID (Generate, меню .NET; мультикурсор)
- [ ] Вставка `DateTime`-форматов, конвертация строки в verbatim / raw
- [ ] Инъекция RegExp в строки `[StringSyntax]` / `Regex(...)` (даёт встроенный Check RegExp)
- [ ] `.resx` ↔ CSV / JSON для переводчиков, проверка недостающих ключей между культурами

## Инструменты, часть 2 — как в Rider и IDEA Ultimate
То, что в Rider сделано панелями и переключателями, а в IDEA Ultimate есть для Java. По-прежнему без семантики языка:
`dotnet` CLI, файлы проекта, уже написанные парсеры. ★ — взять первыми, в скобках — оценка трудоёмкости.

### Панели и переключатели Rider
- [x] ★ File nesting: `appsettings.*.json` под `appsettings.json`, `Foo.razor.cs` / `Foo.razor.css` под `Foo.razor`, `*.Designer.cs` под `.resx`, `*.xaml.cs` под `.xaml` — через `ProjectViewNestingRulesProvider` (пара часов)
- [x] ★ Переключатель конфигурации решения в тулбаре: Debug / Release и target framework; подставляется в сборку, запуск и тесты (`-c`, `--framework`) (день)
- [x] ★ Analyze .NET Stack Trace: вставить стектрейс из лога или тикета → кликабельные кадры (фильтр уже есть; час-два)
- [x] Unit Tests explorer: окно со всеми тестами solution без запуска (токенное обнаружение уже есть), запуск выделенного, группировка проект / namespace / класс
- [x] Окно Unit Tests внизу, как в Rider: вкладка Explorer плюс сессии — результаты `dotnet test` идут в это окно, а не в Run (свой program runner, повторный запуск переиспользует вкладку); окна Build и .NET Coverage не исчезают с панели
- [ ] Continuous testing: `dotnet watch test` с тем же деревом результатов, рабочая кнопка «Toggle auto-test»
- [ ] Декомпиляция сборок из Dependencies через `ilspycmd` → C# read-only в редакторе
- [x] Сводка по скорости сборки: меню .NET → Measure Build Performance — `-clp:PerformanceSummary` → таблицы «что тормозит» по таргетам, задачам и проектам
- [ ] Сохранение binlog (`-bl`) для MSBuild Structured Log Viewer
- [x] Run MSBuild Target…: список таргетов проекта с поиском (`dotnet msbuild -targets`), свои из проекта и `Directory.Build.*` наверху, запуск с выводом в Build tool window
- [x] Show All Files в панели Solution: показать `bin`, `obj` и файл проекта (меню настроек Project view)
- [x] Edit 'App.csproj' / Edit 'App.slnx' в ПКМ узла проекта и solution; перетаскивание такого узла в редактор открывает его файл (как в Rider): узлы — `AbstractPsiBasedNode`, иначе drag source платформы не начинает перетаскивание
- [x] То, что добавляет Show All Files (`bin`, `obj` с содержимым, файл проекта), в Solution view окрашено цветом «ignored» схемы — как файлы из `.gitignore`, но без зависимости от git
- [x] Кнопка-«глаз» Show All Files в заголовке окна Project (группа `ProjectViewToolbar`), видна только в Solution view

### Веб-разработка на ASP.NET
- [ ] ★ HTTPS dev-сертификат: `dotnet dev-certs https --check` при открытии web-проекта, баннер «сертификат не доверен» с кнопкой Trust
- [ ] ★ `dotnet user-jwts`: диалог создания dev-токена (роли, scope, срок), вставка в `.http` как `Authorization: Bearer …`, список выданных токенов
- [ ] HTTP Client environments: `http-client.env.json` из `applicationUrl` профилей `launchSettings.json`
- [x] Services / Run Dashboard (2026-09-29): `runDashboardDefaultTypesProvider` ставит «.NET Project» в окно Services, `runDashboardCustomizer` дописывает
  ссылку на «Now listening on» (адрес хранится на process handler — `ListeningAddressRecorder`, и у Run, и у Debug). Вживую не проверено
- [x] F2 на узле проекта — Rename Project (`renameHandler`), Delete — Remove from Solution (DeleteProvider из `uiDataSnapshot` панели) (2026-09-29)
- [ ] Проверка AOT / trimming: «Check AOT compatibility» → `dotnet publish -r <rid>`, предупреждения `IL2xxx` / `IL3xxx` отдельным списком с переходом к коду
- [ ] User Secrets: проверка, что ключ из `appsettings.json` перекрыт секретом (дополнение к пункту из раздела «Данные и API»)

### Зависимости (аналог Dependency Analyzer и Package Checker)
- [ ] ★ Update All: обновить все пакеты проекта / solution одним действием с предпросмотром списка
- [ ] ★ Консолидация версий: один пакет с разными версиями в проектах solution → «привести к одной» (область Solution уже показывает `multiple`)
- [ ] Конфликты версий: `NU1605` / `NU1608` / `NU1107` и `project.assets.json` → дерево «кто какую версию требует и какая победила»
- [ ] Кэши NuGet: `dotnet nuget locals all --list`, размеры папок, очистка http-cache / global-packages / temp
- [ ] Pack & Push: run configuration `dotnet pack` + `dotnet nuget push`, API-ключ в хранилище паролей IDE, выбор фида из вкладки Sources
- [ ] Bump version: major / minor / patch для `Version` в `.csproj` или `Directory.Build.props`

### SDK и окружение
- [x] «.NET on This Machine» (меню .NET и ссылка со страницы настроек): сводка из `dotnet --info`, таблицы SDK и runtime со статусом поддержки из `dotnet sdk check` (актуален / есть патч или поддержка скоро кончится / снят с поддержки), какой SDK выбран для проекта с учётом `global.json`, полный `--info` с копированием
- [x] Шаблоны: ссылка «More templates...» в New Project и Add New Project → поиск пакетов шаблонов на nuget.org (`packageType=Template`, то же, что ищет `dotnet new search`), установка, список установленных, проверка обновлений, Update All, удаление; вывод команд в логе диалога
- [x] Параметры шаблона в New Project / Add New Project (2026-09-29): `TemplateOptions.parse` разбирает секцию «Template options» из `dotnet new <t> --help`
  (choice / bool / text, choices с описаниями, Default, Multiple values, Enabled if), панель перестраивается при смене шаблона и языка (кэш по паре),
  в `dotnet new` уходит только отличное от умолчания; Framework берётся из `--framework` шаблона. Фикстуры — `src/test/resources/dotnetNew` (SDK 10.0.401).
  Вживую не проверено
- [ ] Шаблоны: поиск по настроенным приватным фидам, установка конкретной версии
- [x] Upgrade Assistant: «Analyze Upgrade to Newer .NET...» для проекта или solution — `upgrade-assistant analyze` с выбором целевого framework, отчёт таблицей (severity, правило, что найдено, место) с переходом к коду и ссылкой на документацию; предложение установить tool, если его нет
- [ ] Upgrade Assistant: применение исправлений (`upgrade-assistant upgrade`), HTML-отчёт, фильтр по severity / проекту

### Производительность и эксперименты
- [ ] BenchmarkDotNet: ▶ у `[Benchmark]`, запуск в Release, результаты из `BenchmarkDotNet.Artifacts/results/*.csv` таблицей (Mean, Error, Allocated), сравнение с предыдущим прогоном
- [ ] C# REPL: `csharprepl` или `dotnet-script` в консольном tool window, «выполнить выделенное из редактора»

### Порядок
1. File nesting, переключатель Debug / Release + framework, Analyze Stack Trace.
2. Dev-certs и `user-jwts`; Update All и консолидация версий в окне NuGet.
3. Services / Run Dashboard, тест-эксплорер с continuous testing, конфликты версий.
4. Остальное — по запросу: декомпиляция и BenchmarkDotNet эффектны, но нужны реже.

## Платформа
- [x] Минимальная версия — 2026.1 (`sinceBuild = 261`), сборка и тесты на IntelliJ IDEA 2026.1.4, Kotlin API 2.3. Папки под узлом проекта в панели Solution получили короткие имена и в IDEA (Java-плагин называл их как пакеты). Убраны устаревшие `ReadAction.compute`, `DaemonCodeAnalyzer.restart()`, `isLenient`, `createSingleFileDescriptor`
- [ ] События окна Build: конструкторы `*BuildEventImpl` / `MessageEventImpl` устарели (не «к удалению»), замена — `BuildEvents` с builder-ами, пока `@Experimental`; перейти, когда стабилизируется
- [x] Диагностика «меню .NET → Probe Platform LSP / DAP API...»: есть ли в этой IDE (и с этой лицензией) модули LSP и DAP — точки расширения и кто в них зарегистрирован, ключи реестра, сервисы, program runner, классы lsp4j, и сверка всех классов API с эталоном IDEA 2026.1.4 (`resources/platformProbe/expected.json`, член = `имя/число параметров`); видны ли классы загрузчику плагина без зависимости на модуль. Таблица с фильтром «Problems only», **Copy as JSON** (без имени владельца лицензии). Анализ API — `docs/platform-lsp-dap.html`
- [x] ~~Content-модуль `io.github.dotnetsupport.dap` на `intellij.platform.dap`~~ — убран 2026-09-22 вместе с переходом отладчика на свой DAP-клиент (модуля DAP нет в IDEA Community и её форках)
- [ ] Проверить наличие LSP API в GoLand / PyCharm / WebStorm / Rider 2026.1+ (в IDEA Ultimate есть) — диагностикой выше; отладчику платформенный DAP больше не нужен

## Отладка через DAP (сделано, кроме второго адаптера)
Отладчик `dotnet-debugger` (dotnet tool `dotnet-debugger-dap`) + свой DAP-клиент (пакет `debugger`) + платформенный XDebugger — работает в любой IDE
на платформе. **Действующий план — `DAP_PLAN.md`** (соответствие API, ограничения адаптера); результаты проверки адаптера — `dap-probe/FINDINGS.md`.
Пункты «Платформенный DAP, этап N» ниже сделаны сначала на платформенном DAP-клиенте (`PLATFORM_DAP_PLAN.md`, теперь история), 2026-09-22 всё
перенесено на свой клиент и проверено заново; обходы платформы (`DotNetPresentationFactory`, переписывание `setBreakpoints`) при этом ушли.
- [x] Отладчик в списке .NET Tools на странице настроек: путь, Install / Update (id пакета и команда разные)
- [x] Платформенный DAP, этап 0: разведка API по байткоду — схема вызовов и поправки к этапам в «Журнале» `PLATFORM_DAP_PLAN.md`
- [x] Платформенный DAP, этап 1 (проверено вживую 2026-09-21): Debug у конфигурации «.NET Project» с командой `dotnet run` уходит в
  платформенный DAP-клиент — описание адаптера `dotnet-debugger`, аргументы `launch` (профиль `launchSettings.json`: переменные, `applicationUrl`,
  аргументы; имя окружения), точки останова на строках `.cs` (на строках с исполняемым кодом, включая top-level statements), before-run task
  «Build .NET Project» (наш Build, ошибка отменяет запуск; для Debug выясняет `TargetPath`; у Run / watch / test ничего не делает — они собирают сами).
  Конфигурация без задачи собирается перед `launch` самим дескриптором. Логи: лог адаптера на каждую сессию, меню .NET → Show Debugger Logs /
  Trace Debugger Protocol. Обход ошибки платформы с выбором остановившегося потока (`DotNetPresentationFactory`).
  Точки останова на исключениях — только заготовка типа (этап 3). В IDE без модуля DAP кнопка Debug, как и раньше, выключена
- [x] Платформенный DAP, этап 2 (проверено вживую пользователем и UI-роботом 2026-09-21; «Save all files on debugger launch» вернулась под
  замок — платформа сохраняет файлы сама): свой процесс адаптера с жёстким завершением (без ошибки
  `Cannot send Ctrl+C` на Stop, зависший адаптер убивается), `launchBrowser` при отладке, аргументы `launchSettingsProfile: ""` / `configuration` /
  `allowImplicitFuncEval`, на странице Debugger работают Save all files on debugger launch, Enable external source debug (= не Just My Code),
  Allow property evaluations and other implicit function calls
- [x] Платформенный DAP, этап 3 (проверено UI-роботом 2026-09-21; ограничение адаптера: `unhandled` не выключается): точки останова на исключениях как «Break when» в Rider — типы
  (`System.IO.*, !System.OperationCanceledException`) и когда: thrown / user-unhandled / unhandled; по умолчанию включена «Any exception
  (user-unhandled, unhandled)», «+» в диалоге Breakpoints добавляет точку под конкретные типы. Set Value (F2) через `setExpression`.
  Hover над переменной в редакторе (выражение под курсором — по токенам)
- [x] Платформенный DAP, этап 4 (проверено UI-роботом 2026-09-21): hit count (`5`, `>= 3`, `% 10`…) и logpoints (`total = {total}`) у точек останова
  `.cs` — панель в диалоге Breakpoints; поля дописываются в `setBreakpoints` на пути к адаптеру, синхронизация точек остаётся штатной. У точки
  появилось и поле Condition
- [x] Платформенный DAP, этап 5 (проверено UI-роботом 2026-09-21): Run | Attach to Process для .NET-процессов (Stop отсоединяется, процесс живёт),
  отладка тестов — Debug у ▶ в редакторе, у конфигурации `dotnet test` и «Debug Selected Tests» в окне Unit Tests (`VSTEST_HOST_DEBUG`, начальный
  `Debugger.Break()` хоста пропускается). Не проверено: проекты на Microsoft.Testing.Platform
- [x] Платформенный DAP, этап 6, completion в Evaluate / watches / условиях точек останова (проверено UI-роботом 2026-09-21): имена берутся у
  остановленной программы — локальные и члены `this`, после `значение.` — его члены; поля выражений стали фрагментами C# с подсветкой
- [x] Свой DAP-клиент вместо платформенного (проверено вживую 2026-09-22): `DapConnection` (фрейминг, корреляция по `request_seq`, события,
  закрытие; тесты `DapClientTest`), `DotNetDebugProcess` на XDebugger — всё из этапов 0–6 выше, кнопка Debug работает и в IDE без модуля DAP.
  Кадры порциями, переменные постранично (`start` / `count`), Run to Cursor, значения в редакторе
- [x] Слой 5, полировка (проверено UI-роботом 2026-09-22): async-стек в кадрах (разделитель «Async Call Stack», как в Rider); ввод в консольную
  программу из консоли отладки (`runInTerminal`: программу запускает плагин, кириллица в обе стороны); Set Next Statement в контекстном меню
  редактора (`gotoTargets` / `goto`). `restart` адаптера намеренно не используется — Rerun платформы пересобирает проект, как Restart в Rider
- [ ] Второй адаптер (netcoredbg) — отложен

## C# через roslyn-language-server (действующий план, фаза 1 сделана)
Подробности — решения, замеры сервера, устройство трёх слоёв (сервер, кэш ответов, свои индексы плагина), риски — в `LSP_PLAN.md`.
Решение 2026-09-21: платформенный LSP-клиент (`intellij.platform.lsp`); прежний план собственного JSON-RPC-клиента снят.
- [x] Research: зонд и замер скорости сервера (`tools/roslyn-lsp/probe.py`, `bench.py`), выводы про кэш и «< 1 мс»
- [x] Фаза 1 (2026-09-21): content-модуль `io.github.dotnetsupport.roslyn` на платформенном LSP-клиенте — сервер стартует с первым открытым `.cs`
  (нет tool — нотификация с Install), загрузка: `--autoLoadProjects` или `solution/open` / `project/open` из `SolutionService`, до
  `workspace/projectInitializationComplete` диагностика не спрашивается; настройки страницы → `workspace/configuration`, отступы из Code Style,
  `locale: en`. Проверено в живой IDE на `debug-playground` (UI-робот: `lsp_state.js`, `lsp_editor.js`): ошибки компилятора и подсказки анализаторов,
  code lens, completion после `person.`, Go to Declaration в другой проект. **Не проверено вживую:** signature help,
  папка без solution (`--autoLoadProjects` / `project/open`)
- [x] Что грузит сервер (2026-09-21, решение пользователя): solution всегда открывает плагин (`solution/open`, без `--autoLoadProjects`); solution ищутся
  по всей папке рекурсивно, несколько — список выбора (выбор запоминается в проекте, закрытый список оставляет нотификацию), смена — меню .NET →
  Select Solution for Language Server (перезапуск сервера). Проверено в живой IDE: список из двух solution, до выбора ничего не грузится и ошибок нет,
  выбор → загрузка за 1,7 с и ошибки компилятора, смена на вложенный `.slnx` → перезапуск без повторного вопроса
- [x] Фаза 1, хвосты (проверено роботом 2026-09-30): `.cs`, созданный после загрузки solution, сервер подхватывает сам, секунд через 15–20
  (`prepareTypeHierarchy` по новому файлу сначала пуст, потом отвечает; платформа LSP file watching не даёт, сервер следит за файлами сам)
- [x] Фаза 2, «Roslyn главный» (2026-09-21, решение пользователя): мост `RoslynServerStatus` — пока сервер проекта готов, уступают эвристическая
  раскраска идентификаторов, свой folding (платформа сворачивает любой язык по ответу сервера — вдвоём были бы дубли) и ошибки последней сборки в
  редакторе; semantic tokens сервера идут в палитру плагина (платформа по умолчанию просит их только для TEXT / TextMate — включено явно);
  Reformat Code: работу `dotnet format whitespace` делает сервер, CSharpier и None остаются выбором проекта; включён серверный onTypeFormatting
  (`;`, `}`, Enter). Проверено в живой IDE: цвета по ключам `CSHARP_TYPE / METHOD / MEMBER`, 52 региона folding без совпадающих диапазонов,
  Reformat Code и набор `;` выправляют строку, Quick Documentation отвечает сервером
- [x] Quick fixes и code actions (2026-09-21): Roslyn помечает диагностику тегами Visual Studio (`2147483642`…), lsp4j читает их как `null`, платформа
  возвращает `"tags":[null,…]` в `textDocument/codeAction`, и сервер отвергал **каждый** такой запрос. `RoslynServerWrapper` убирает `null` перед
  отправкой (хук платформы `addLsp4jServerWrapper` — `@Internal`, другого места нет). Вживую: «Remove unused variable», «Generate type», Fix All
- [x] Фаза 2, остаток (2026-09-21): строка сервера в виджете language services — «Roslyn: starting / select a solution to load / loading X.sln / X.sln»
  (перерисовку даёт пустой `itemsProvider` виджета), кнопки Select Solution и Show Log, меню .NET → Restart C# Language Server / Show Language Server
  Log; неожиданная остановка сервера → нотификация с причиной (нет runtime .NET 10 по `dotnet --list-runtimes` — ссылка на загрузку) и Restart / Show Log;
  `_roslyn_projectNeedsRestore` → нотификация с Restore (сервер 5.12 при включённом авто-restore восстанавливает сам и клиента не спрашивает — проверено зондом)
- [x] Клиентские команды Roslyn (2026-09-21, по сообщениям пользователя): сервер только описывает `roslyn.client.*`, исполнять их должен клиент, а
  платформа слала их обратно серверу — «N references» не нажимался, «Introduce constant» и все «Fix All» были мёртвыми пунктами. `RoslynClientCommands`:
  `peekReferences` → Show Usages, `nestedCodeAction` → список вариантов → `codeAction/resolve` → правка, `fixAllCodeAction` → выбор области →
  `codeAction/resolveFixAll`. Клик по code lens идёт мимо `commandsCustomizer` (через `codeLensClicked`) — переопределён и он. Проверено вживую все три
- [x] Rename (2026-09-21, по сообщению пользователя): Shift+F6 на объявлении упирался в заглушку «needs a language server» — стандартный rename брал мой
  PSI-узел раньше LSP-обработчика (тот `order="last"`), а сам LSP-rename по умолчанию выключен для языков кроме TEXT / TextMate. Вето на стандартный
  rename для объявлений + `shouldRunRename`; так же включена подсветка вхождений под кареткой (`documentHighlight`). Вживую: на объявлении единственный
  обработчик — `LspRenameHandler`; сам диалог rename роботом не открыть (нужен настоящий фокус) — **посмотреть руками**
- [x] Ctrl+наведение (2026-09-21, по сообщению пользователя): платформа отдаёт LSP-ссылку только во время действия Go to Declaration, поэтому при
  наведении не было ни подчёркивания, ни «руки». Свой `implicitReferenceProvider` (последним): идентификатор → `textDocument/definition` (300 мс
  максимум, у прогретого сервера 3–11 мс) → PSI-цель. Проверено программно (ссылка резолвится, на самом объявлении ссылки нет); **как это выглядит под
  мышью — посмотреть руками**
- [x] Снятый трафик сервера (2026-09-21, идея пользователя): `tools/roslyn-lsp/capture.py` делает по запросу каждого типа (47 записей) с настоящими
  capabilities платформы (`client-capabilities.json`, снимается `lsp_capabilities.js`), `--fixtures` кладёт обезличенную копию в
  `src/test/resources/roslyn/capture-5.12`. `RoslynCapturedTrafficTest` гоняет ответы через lsp4j туда-обратно и сверяет потери с `lsp4j-losses.txt`
  (там виден баг с `tags`), проверяет разбор клиентских команд на настоящих данных и факты, на которых стоит клиент. Найдено захватом:
  `completionItem/resolve` без `data` роняет сервер целиком (платформа `data` из `itemDefaults` подставляет — в IDE безопасно)
- [x] Completion при наборе нескольких слов подряд (2026-09-21, по сообщению пользователя): в `public const string` popup был только на `public`. Пробел —
  trigger-символ Roslyn, completion стартует с пустым префиксом, платформа такой popup не показывает и запоминает «здесь пусто» (фаза `EmptyAutoPopup`),
  после чего буквы следующего слова автопопап не запускают — хотя сервер на них отвечает (проверено зондом: 134 и 109 элементов). `RoslynCompletionRestart`:
  completion, начатый с пустого префикса, перезапускается первой буквой (только первой — дальше список фильтруется локально). Вживую посимвольным
  набором (`type_text.js`): popup на `public`, `const` и `string`, два прогона подряд
- [x] Alt+Enter без повторов (2026-09-22, проверено в песочнице): платформа спрашивает действия и для каждой диагностики под кареткой, и для
  самой каретки, а Roslyn каждый раз отвечает всем, что там применимо, — рефакторинги попадали и в исправления, и в контекстные действия, а
  исправления диагностики ещё и в контекстные. `RoslynCodeActionsSupport`: рефакторинги (`refactor*`) — только контекстными действиями,
  исправление, называющее свою диагностику, — только у неё. `int unused = 1;` — 10 строк вместо 13, у метода — 4 вместо 6. Extract method на
  одиночном идентификаторе больше не предлагается (на выделении — как прежде). Go to Class / Symbol без дублей — см. пункт ниже
- [x] Фаза 3 (2026-09-21): **учёт времени** каждого запроса в обёртке сервера (меню .NET → Language Server Timings, в буфер обмена);
  **прогрев** после загрузки — первый completion пользователя 42 мс вместо 153, code actions 86–149 вместо 523 (references не прогреваются: не помогает);
  **дисковый кэш semantic tokens** по содержимому файла — при открытии проекта файл раскрашен сервером из кэша до загрузки solution (вживую: на 2,8 с
  раньше, ответ за 0 мс), после загрузки платформа перезапрашивает настоящий. Спекулятивный completion не делался — по замеру ждать нечего (10–20 мс на
  первую букву, дальше список фильтруется локально); folding / symbols не кэшируются — до загрузки их дают эвристики. Подробности и цифры — `LSP_PLAN.md`,
  слой 2. **Посмотреть руками**: раскраска при открытии проекта не «мигает» при переходе с кэша на ответ сервера; таблица таймингов на большом solution
- [x] Completion и Parameter Info как в Rider (2026-09-22, по сообщениям пользователя): выбранный метод получает `()` с курсором внутри и сразу
  подсказку параметров (Roslyn присылает голое имя); Ctrl+P показывает **все перегрузки** списком — свой обработчик для C#
  (`RoslynParameterInfoHandler`), потому что платформенный для LSP рисует только активную сигнатуру и только её параметры (для
  `Console.WriteLine(|)` — пустой квадратик 44×28, «ничего не происходит»). Текущий параметр подсвечен, неподходящие перегрузки серые.
  Проверено: сервер отдаёт 19 перегрузок `WriteLine`, обработчик стоит для C# первым и рисует все строки; **глазами — у пользователя**
- [x] Completion, хвосты и мусор (2026-09-22): у методов и членов справа — параметры, число перегрузок и тип (`WriteLine  ()  +18 overloads  void`,
  `Title  string`), как в Rider. Roslyn их не присылает, берутся из сигнатуры в документации: платформа resolve'ит видимые строки в фоне и
  перерисовывает их, так что хвост появляется через мгновение. `await` больше не висит в списке при любом вводе: Roslyn даёт ему
  `textEditText`, равный набранному (`p`), а платформа делала его строкой поиска — теперь он ищется по названию. Список типов после `(` не открывается
- [x] Порядок completion как в Rider (2026-09-29, по сообщению пользователя «на `n` сначала `nameof`, потом моя `names`»): `PrioritizedLookupElement` по
  `CompletionItemKind` — локальные / параметры / члены → методы → типы → ключевые слова, `preselect` сервера сверху (`RoslynCompletionPolicy.priority`).
  Проверено роботом 2026-09-30 (`tools/ui-robot/scripts/complete_at_line.js`, сценарии `CompletionRanking.cs`): weigher сильнее `sortText` —
  на `n` первым `Name`, ключевые слова `nameof` / `new` / `null` на 71–75 позиции после типов; `int amount = ` → `count`, `Count`; `Send(` → строки первыми.
  Замечено: объявляемая на этой же строке переменная (`amount`, `copy`) стоит первой — её предлагает сервер, плагин не отсеивает
- [x] Parameter Info у extension-методов (2026-09-29, по скриншоту пользователя: строки вида «extension) IServiceCollection … AddSingleton(Type serviceType»):
  label Roslyn начинается с `(extension)`, а список параметров брался от первой скобки; теперь — последняя сбалансированная группа скобок
  (`RoslynSignatures.splitParameterList`)
- [x] Лямбда там, где ждут делегат (2026-09-29, идея пользователя): `LambdaSuggestions` разбирает тип параметра из label `signatureHelp`
  (`Func` / `Action` / `Predicate` / `Expression<Func>` / `EventHandler` / `Comparison` / `Converter`, имена из типов); два слоя —
  пункт списка completion первым (`RoslynLambdaCompletion`, приоритет 200, inline и блочный варианты) и серый inline-текст после `(` / `,`
  (`RoslynLambdaGhost`, EP `inline.completion.provider`, `InlineCompletionSingleSuggestion.build`). Проверено роботом 2026-09-30, сценарий
  `debug-playground/Console/Editor/LambdaSuggestions.cs` (`TYPE:lambda-*`): серый `lambdaOrder => ` / `serviceProvider => ` / `(i, s) => `,
  в списке лямбда первой, у `+=` и `Console.WriteLine(` ничего
- [x] `typeof` / `nameof` / `sizeof` / `checked` / `unchecked` из completion получают `()` с кареткой внутри, как в Rider (2026-09-29, по сообщению пользователя)
- [x] `new HttpClient` + Tab → `new HttpClient(|)` с Parameter Info (2026-09-29, по сообщению пользователя): тип (Class / Struct), выбранный сразу после `new`, получает скобки
- [x] Inlay hints включены по умолчанию (решение пользователя 2026-09-22): имена параметров у литералов, индексаторов и `new`, типы у `var` и параметров
  лямбд; «всё остальное», `new()` и collection expressions — выключены. Проверено в песочнице: `Scenarios.cs` — `year:`, `month:`, `DateTime`…
- [x] Go to Class / Symbol без дублей: пока сервер готов, свой индекс не отдаёт файлы загруженного solution (их отдаёт `workspace/symbol`);
  файлы вне solution и время загрузки — за своим индексом
- [x] `projects.dotnet_enable_file_based_programs` выключено по умолчанию (решение пользователя 2026-09-22): с ним новый `.cs`, открытый сразу после
  создания, оставался «отдельной программой» без ошибок и типов проекта до переоткрытия вкладки
- [x] Память ответов сервера на повторные вопросы (2026-09-22): `RoslynResponseMemo` в обёртке сервера — пока в workspace ничего не менялось
  (правка, сохранение, закрытие файла, файлы на диске, конфигурация, команда, загрузка проектов, перезапуск сервера), тот же запрос
  (`codeAction`, `codeAction/resolve`, `codeLens/resolve`, `documentHighlight`, `inlayHint`, `hover`, `signatureHelp`, переходы…) получает
  прежний ответ; любое изменение забывает всё. Ответ хранится JSON'ом и отдаётся копией, через адаптеры lsp4j самого метода
  (`@ResponseJsonAdapter` — иначе список «Command или CodeAction» не читается; тест гоняет настоящие ответы из захвата). Замер в песочнице:
  `codeAction/resolve` — 17 из 65 из памяти, `codeAction` — 5 из 12. Code lens / folding / inlay hints при переключении вкладок платформа и так
  не перезапрашивает; `didOpen` кэш не сбрасывает (текст открытого файла — тот же, что на диске). Дальше — диагностика с `previousResultId`
- [ ] Фаза 4: расширенный индекс исходников (namespace, `using`-и, базовые типы, сигнатуры, extension-методы)
- [ ] Фаза 5: метаданные сборок (dotnet-помощник на `System.Reflection.Metadata`, кэш на машину по пакет + версия + TFM) и XML-документация
- [ ] Фаза 6: потребители своих данных — Go to Class по пакетам, типы с авто-`using`, члены статических типов, раскраска, документация; слияние с ответом сервера
- [x] Фаза 7 (2026-09-21):
  - **Go to Implementation** (Ctrl+Alt+B): платформенное действие подменено под своим id (сочетание и тексты платформы); в C#-файле с загруженным
    сервером — `textDocument/implementation`, одна цель — переход, несколько — список «имя: тип in Контейнер (файл:строка)», ноль — подсказка; в
    остальных файлах — обработчик платформы. Вживую: на вызове метода интерфейса, на методе в интерфейсе и на имени интерфейса — по 2 реализации
  - **Декомпилированный код** (Ctrl+клик в тип фреймворка или пакета): сервер кладёт декомпилят во `%TEMP%/MetadataAsSource/…` и сам его понимает;
    вкладка `Console.cs [System.Console]`, правка запрещена, баннер «Decompiled from System.Console 9.0.0.0. Read-only» со ссылкой на dll. Вживую:
    переход в `Console`, оттуда в `TextWriter.cs [System.Runtime]`, ложных ошибок в декомпиляте нет
  - **Переименование файла вместе с типом**: сервер правит только текст, а платформа не умеет переименовывать файлы из правок LSP
    (`resourceOperations: ["create"]`) — файл `<Тип>.cs` переименовывает плагин, когда правка применена; только если правка приходится на имя
    объявленного в файле типа и файла с новым именем ещё нет. Вживую: `Scenarios` → `Playbook`, `Scenarios.cs` → `Playbook.cs`, ошибок нет
  - вложенные code actions и Fix All — сделаны раньше, в фазе 2
  - **Посмотреть руками**: выбор мышью в списке реализаций (робот теряет popup вместе с фокусом окна); настоящий Shift+F6 на классе — переименуется
    ли файл после inline-rename платформы (робот проверил путь «ответ сервера → правка → файл», но не сам шаблон платформы); Ctrl+Z после такого
    переименования — откатываются текст и файл двумя шагами, не одним
- [x] Type / Call Hierarchy и Go to Base (2026-09-29): окно Hierarchy платформы (`typeHierarchyProvider` / `callHierarchyProvider` для C#) на
  `prepareTypeHierarchy` + `supertypes` / `subtypes` и `prepareCallHierarchy` + `incomingCalls` / `outgoingCalls`; элементы — `FakePsiElement`
  с файлом и позицией; Ctrl+U (`codeInsight.gotoSuper`) на типе — базовые типы. Проверено роботом 2026-09-30 (`tools/ui-robot/scripts/hierarchy.js`):
  окно Hierarchy — база жирным, интерфейс и наследники; Callers of `Total` → `Handle`; Ctrl+U на `ProbeCircle` → `ProbeShape`. Ограничение
  сервера: `supertypes` отдаёт только типы из исходников — у `ShopException : Exception` пусто (в снимке трафика тоже нет `object`)
- [x] Сервер стартует с проектом, а не с первым открытым `.cs` (2026-09-29, по сообщению пользователя): `RoslynStartupActivity` →
  `LspClientManager.ensureClientStarted` для папки с solution / проектами; строка сервера в виджете language services показывается и для
  не-C#-файлов (`RoslynWidgetUpdater.createWidgetItems`) — раньше при закрытии всех `.cs` строка пропадала, хотя сервер жил (в логе
  «Stopping LSP server normally» появляется только от Restart / настроек / закрытия проекта). Документы `roslyn-source-generated://` в
  `workspace/diagnostic` пропускаются; в лог пишется «workspace/diagnostic: N documents, M changed, K problems shown»
- [x] Problems: открытые файлы тоже (2026-09-29, по скриншоту пользователя — вкладка Project Errors пуста, а в открытом Program.cs ошибки):
  Roslyn не включает открытые документы в `workspace/diagnostic` (лог: только Types.cs / PricingTests.cs / *.cshtml.cs с Information / Hint;
  у зонда, где ничего не открыто, — все файлы), их даёт `textDocument/diagnostic`, который платформа спрашивает для редактора. Обёртка
  сервера перехватывает эти ответы и `didClose` и кладёт в те же Project Errors под тем же uri. `resultId` теперь запоминается и для
  пропущенных generated-документов — иначе сервер отвечал ими каждые 1,5 с
- [x] Problems по всему solution (2026-09-29): `workspace/diagnostic` → `ProblemsCollector` (вкладка Project Errors). Зонд `scratchpad/wsdiag.py`
  на сервере 5.12: отвечает только при `dotnet_compiler_diagnostics_scope = fullSolution`; повторный запрос висит до изменения в workspace —
  держим один запрос постоянно (таймаут 10 мин, перезапуск). Коллектор Problems — не слушатель топика, а его источник: проблемы отдаются ему
  напрямую и по тем же объектам снимаются. Ошибки и предупреждения, без hints. Проверено вживую 2026-09-30
- [ ] Фаза 8: надёжность — большие и несколько solution, перезапуски, dumb mode (LSP4IJ не учитываем — решение 2026-09-21)

## Вне рамок (нужна семантика языка)
Полный парсер выражений, разрешение ссылок, типизация, инспекции, completion по типам, рефакторинги, собственный форматтер,
inlay-подсказки имён параметров. Парсер уровня объявлений (namespace → типы → члены, тела пропускаются) — в рамках, это заход 2.

**Razor / Blazor — вне плана** (решение пользователя 2026-09-30): ни тип файла `.razor` / `.cshtml`, ни Razor через `roslyn-language-server`
не делаем; из сделанного остаются только шаблоны New → .NET, иконки и nesting `Foo.razor.cs` под `Foo.razor`. Снятые факты о сервере
(раздел «Razor» в `tools/roslyn-lsp/README.md`, `capture_razor.py`, фикстуры `capture-5.12-razor`) сохранены на случай возврата к теме.

Варианты, если понадобится разбирать тела методов: ANTLR4 `grammars-v4/csharp` + `antlr4-intellij-adaptor` (устарела до C# 6–7,
дописывать самим), tree-sitter-c-sharp (лучшая грамматика, но нативная и без PSI), consulo-csharp (Apache 2.0, ручной парсер и PSI —
образец и источник кусков, API разошёлся с IntelliJ).
