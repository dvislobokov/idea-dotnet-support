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
- [ ] Переход между partial-частями, Related file (`.xaml` ↔ `.xaml.cs`, `.razor` ↔ `.razor.cs`, тест ↔ класс)
- [ ] Reformat Code через `dotnet format` (+ при сохранении)
- [x] Ошибки и предупреждения последней сборки в редакторе (`BuildProblems` + ExternalAnnotator): подчёркнуто слово по колонке компилятора, сообщение с кодом; диагностика следует за своей строкой при правках выше и исчезает, когда строку исправили; следующая сборка заменяет всё
- [x] Live templates для C# (33: `ctor` с именем типа, `prop*`, `cw`, циклы, `try`, `using`, `svm`, типы, `fact` / `theory` / `test` / `testm`, `region`…), Enter внутри `///` продолжает комментарий, третий `/` над объявлением даёт `<summary>` с `<param>` и `<returns>`
- [x] Отступы при наборе (Enter, набранные `{` `}` `)` `]`) — `LineIndentProvider` на движке правил из JSON (`resources/csharpIndent/rules.json`): упорядоченный список «условия → якорь + добавка», первое подошедшее выигрывает; 29 правил с примерами внутри (тест прогоняет все примеры и реальный файл построчно). Покрыто: блоки и K&R / Allman, аргументы и их перенос, цепочки `.`/операторы и возврат к началу оператора после `;`, тело без скобок у `if` / `for` / `else`…, `else` / `catch` / `finally`, `switch` (метки, секции, блок секции), инициализаторы / enum / switch-выражения, атрибуты, `#region` и `#if`, `/* */`, verbatim / raw-строки не трогаются. Размеры — из Code Style → C# (и `indent_size` / `indent_style` из `.editorconfig`), опции `csharp_indent_braces`, `csharp_indent_switch_labels`, `csharp_indent_case_contents`, `csharp_indent_case_contents_when_block` — из `.editorconfig`. Enter после `{`: если ниже есть `}` на «своём» отступе, вторая не вставляется, даже когда скобки файла не сходятся из-за недописанного кода (платформа считает скобки, а не раскладку). Форматирование файла целиком остаётся за CSharpier / `dotnet format`
- [ ] Отступы: Auto-Indent Lines и вставка фрагмента по тем же правилам, метки `goto` (`csharp_indent_labels`), продолжение `//` по Enter (как в VS Code)
- [ ] Enter в `/* */`
- [ ] TODO-индекс, WordsScanner (текстовый Find Usages), spellchecker
- [ ] Неактивные ветки `#if` по `DefineConstants`
- [ ] Переименование файла вместе с типом

## Заход 3 — NuGet и тесты
- [x] Dependencies как в Rider: Imports (Sdk.props / Sdk.targets, Directory.Build.*, явные `<Import>`), узел на каждый TFM, Packages с разрешёнными версиями и транзитивными зависимостями, Projects, Assemblies, Analyzers, Frameworks со сборками — из `obj/project.assets.json`
- [ ] Dependencies: вложенные импорты внутри Sdk.props / Sdk.targets, анализаторы самого SDK, транзитивные проекты
- [x] Окно NuGet: пакеты проекта с доступными обновлениями, поиск по фидам (API v3, источники из `dotnet nuget list source`), установка / обновление / откат / удаление через `dotnet add|remove package`, prerelease
- [x] Окно NuGet как в Rider: область «Solution / проект», единый список Installed + Available с иконками пакетов и действиями в строке, карточка пакета (версии, таблица проектов с Install / Update / Downgrade / Remove, зависимости по фреймворкам из `.nuspec`, лицензия, ссылки, теги)
- [x] Окно NuGet: вкладка Sources (фиды всех уровней `nuget.config`, добавить / удалить / включить / выключить, ссылки на файлы конфигурации) и вкладка Log (команды `dotnet` и их вывод)
- [x] Потоковый вывод команд `dotnet`: NuGet — построчно во вкладку Log; создание проектов, `dotnet sln`, EF, шаблоны, конвертация — задачами в Build tool window (окно открывается само только при ошибке), отмена прогресса убивает процесс
- [x] Карточка пакета по образцу Rider (шапка, Version с кнопками «во все проекты», сворачиваемые Info и Dependencies со сводкой, список проектов с кнопками-иконками), однотонная иконка окна для обеих тем и нового UI
- [x] Диалог фида как в Rider (New / Edit): Name, URL, User, Password, Enabled, Allow insecure connections, Disable TLS certificate validation; учётные данные — в `nuget.config` через CLI и в хранилище паролей IDE для поиска по приватным фидам; пароли замаскированы в логах и прогрессе
- [ ] Окно NuGet: README пакета, правка версий в `Directory.Packages.props` при CPM, выбор файла конфигурации для нового фида (сейчас — куда пишет CLI, т.е. пользовательский)
- [ ] Устаревшие и уязвимые пакеты (`dotnet list package --outdated --vulnerable --format json`)
- [x] csproj / `Directory.Packages.props`: сетевой completion пакетов — id в `Include` / `Update` у PackageReference / PackageVersion / GlobalPackageReference / PackageDownload (поиск по фидам solution, порядок фида, версия, загрузки, ✓ verified; от 2 символов, перезапрос при наборе), версии в `Version` / `VersionOverride` (атрибутом и тегом; новые сверху, prerelease — по настройке или когда набирается `-`); выбор пакета дописывает `Version="<последняя>"`, кроме CPM и когда версия уже есть; запрос в пуле с отменой при следующем символе, кеш на 5 минут
- [ ] csproj: inlay «доступна новая версия», quick-fix обновления
- [x] Авто-`dotnet restore` при изменении csproj (настройка на странице NuGet, см. «Заход 4»)
- [x] Тесты: дерево результатов из TRX (SMTRunner), переход к исходнику, перезапуск упавших, ▶ у тестовых методов и классов (xUnit / NUnit / MSTest) с `--filter`
- [ ] Тесты: результаты по мере выполнения, а не после завершения (свой VSTest-логгер или протокол Microsoft.Testing.Platform)

## Заход 4 — проект и окружение
- [x] Настройки инструментов на той же странице: пути к `dotnet-counters`, `dotnet-stack`, `dotnet-gcdump`, `upgrade-assistant` (пусто — PATH и `~/.dotnet/tools`), кнопка Install / Update у каждого
- [x] Страницы настроек по образцу Rider, дочерние к Settings | Tools | .NET, опция в опцию; то, за чем у плагина пока ничего нет, показано выключенным с замком и причиной в подсказке (`settings/RiderSettingsUi.kt`):
  - **Toolset and Build** (на проект, workspace): MSBuild global properties (`-p:` для build / rebuild / clean / restore, `--property:` для run), Run build after solution is loaded, Restore NuGet packages before build (`--no-restore`), число процессов (`-m:N`), verbosity вывода, лог MSBuild в файл (`-fl -flp:`, папка, verbosity). Замок: Mono, версия MSBuild, авто-загрузка SDK, ReSharper Build, targets пропущенных проектов, design-time build
  - **NuGet** (на машину): Include prerelease (начальное состояние чекбокса окна и Upgrade Packages), автоматический restore после изменения `*.csproj` / `Directory.Packages.props` / `nuget.config` (в Log окна NuGet), Smart Restore on Build (`--no-restore`, пока `project.assets.json` новее всего, что решает состав пакетов), `--no-cache`, `--interactive`. Замок: unlisted, blob-фиды, dependency behavior, file conflict, uninstall-опции, restore engine, формат пакетов, credential providers
  - **Coverage**: что делать с новым покрытием (спросить / не применять / заменить / добавить к показанному — попадания суммируются), Activate Coverage View, проценты покрытия у файлов и папок в Project / Solution view
  - **Debugger**: Enable external source debug (= не Just My Code) и Allow property evaluations and other implicit function calls
  - **Language Server**: параметры `roslyn-language-server` — запуск (лог, авто-загрузка проектов, генераторы, доп. аргументы) и настройки,
    которые сервер запрашивает через `workspace/configuration` (анализ, проекты, completion, навигация, code lens, inlay hints, правки,
    генерация кода; прочее — строками `секция = значение`). Клиента LSP ещё нет: настройки хранятся для него
  - Выключенные опции-заглушки «как в Rider, под замком» убраны со всех страниц (2026-09-21): на страницах только работающее
  - **Editor | Code Style | C#**: Tabs and Indents настоящие (ими отступает редактор, EditorConfig IDE их переопределяет), остальное с первой вкладки Rider и прочие вкладки — под замком (нужен форматтер внутри IDE)
- [x] Окно NuGet: вертикальный тулбар как в Rider — Restore (solution или проект из «Packages for»), Upgrade Packages in Solution, показать / скрыть карточку пакета, Settings, Help
- [x] Страница настроек (Settings | Tools | .NET): путь к `dotnet` с проверкой, список установленных SDK, статус `global.json` проекта, переключатели поведения (автосоздание run configurations, окно Build при каждой сборке, автопереключение на Solution view)
- [x] Уведомление при открытии solution: `dotnet` не найден, или `global.json` требует неустановленный SDK (политики `rollForward` сверены с настоящим CLI)
- [ ] New Project в IntelliJ IDEA (`GeneratorNewProjectWizard`)
- [ ] Project Properties (TargetFramework, OutputType, Nullable, LangVersion, RootNamespace)
- [ ] Честное содержимое проекта: `Compile Remove`, linked files, `DependentUpon`-вложение
- [ ] Переименование / перемещение проекта, unload / reload, solution filters (`.slnf`), drag-and-drop в дереве
- [ ] Publish, `dotnet tool restore`, user secrets
- [ ] Редактирование шаблонов генераторов пользователем (сейчас зашиты в плагин)

## Заход 5 — файлы проекта и конфигурации
- [x] MSBuild-файлы (`.csproj`, `.props`, `.targets`, …): собственная составная схема вместо XSD — JSON-фрагменты в `resources/msbuildSchema` (ядро SDK, NuGet / CPM, упаковка, publish / trimming / AOT, анализ кода, ASP.NET / OpenAPI, SDK-контейнеры, тесты и coverlet, gRPC / Protobuf, EF Core, MinVer / GitVersion / SourceLink, WPF / WinForms / MAUI / Avalonia): ~290 свойств, ~50 item-ов с метаданными. Схема открытая: неизвестный тег — не ошибка. По ней: completion тегов по месту (свойства в PropertyGroup, item-ы в ItemGroup, метаданные в item-е, задачи в Target, структура в Project), атрибутов (Include / Remove / Update, метаданные атрибутами, Condition, атрибуты Target / Import / задач) и значений (в т.ч. элемента списка `a;b`); фрагменты инструментов, на которые проект ссылается (пакет или SDK, в т.ч. через `PackageVersion`), идут первыми, остальные — серым с «needs <пакет>»; вставка тега в готовом виде (`<Nullable>|</Nullable>`, `<PackageReference Include="|" />`); Ctrl+Q в файле и в списке completion (описание, значения, пакет, ссылка на документацию); предупреждение о значении вне закрытого перечисления (не для `$(…)`); подсветка `$(Property)`, `@(Item)`, `%(Metadata)`
- [ ] MSBuild: навигация по `Import` / `ProjectReference` / `$(Property)`, битые пути, completion `$(…)` по свойствам файла и `Directory.Build.props`, страница цветов для ссылок
- [ ] JSON Schema для `appsettings.json`, `launchSettings.json`, `global.json`
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
- [ ] Endpoints: маршруты из констант и `nameof`, группы, объявленные в другом файле (extension-методы `MapXxxEndpoints`), Razor Pages и `MapHub`, поиск маршрута через Search Everywhere
- [ ] Hot Reload для `dotnet watch`: кнопка Restart, индикатор «изменения применены / нужен перезапуск»
- [x] Окружение в run configuration: список из `appsettings.<Name>.json` + Development / Staging / Production; задаёт `ASPNETCORE_ENVIRONMENT` и `DOTNET_ENVIRONMENT`, перебивает launch-профиль через `dotnet run -e` (SDK 9.0.200+, с учётом `global.json`; на старых SDK — только переменные)
- [ ] Compound-конфигурация: запуск нескольких проектов solution разом

### Диагностика без отладчика
- [x] ★ Окно «.NET Monitor» (справа, графики столбиком, как Monitoring в Rider): CPU и память процесса приложения средствами ОС (без внешних инструментов; `dotnet run` / `watch` — лаунчер, меряется его дочернее приложение) и счётчики рантайма через `dotnet-counters collect` (GC heap, скорость аллокаций, время в GC, сборки/с, активные запросы сервера и HttpClient, p95 длительности запроса, исключения и lock contention, очередь thread pool); процессы из IDE подхватываются сами, остальные .NET-процессы машины — из списка; имена счётчиков .NET 9+ и старых рантаймов; предложение установить tool
- [x] Monitor: в списке только процессы, запущенные из IDE; остальные .NET-процессы машины — по галочке «All .NET processes» (запоминается). Программа под отладчиком тоже попадает в список: её pid берётся из события `process` адаптера (**вживую не проверено**)
- [ ] Monitor: свои `Meter` приложения по имени, запросы/с, EF Core и Kestrel, пауза и масштаб времени, строка состояния в Services
- [x] ★ Thread Dump в .NET Monitor (`dotnet-stack report`): потоки с кодом приложения наверху, одинаковые стеки свёрнуты, кадры проекта кликабельны (тип ищется по имени файла, метод — в файле; async, лямбды, конструкторы разворачиваются в исходные имена)
- [x] ★ Heap Snapshot в .NET Monitor (`dotnet-gcdump report`): куча по типам (объекты, байты), фильтр, сравнение с любым более ранним снимком того же процесса — Δ объектов и Δ байт, поиск утечек
- [ ] `dotnet-trace`: запись трассы, просмотр flame graph (speedscope во встроенном браузере)
- [ ] `dotnet-dump`: снятие полного дампа, таблицы `dumpheap -stat`, `clrstack`, `gcroot`; сохранение `.gcdump` в файл; пути удержания объекта

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
- [ ] `.editorconfig` для C#: completion и документация опций `csharp_*` / `dotnet_*`, выбор severity правил

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
- [ ] Services / Run Dashboard (как Spring Boot в IDEA Ultimate): наши конфигурации в окне Services — статус, адрес «listening on» ссылкой, перезапуск, несколько сервисов списком
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
- [ ] Шаблоны: поиск по настроенным приватным фидам, установка конкретной версии, параметры шаблона (`dotnet new <t> --help`) в диалоге New Project
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
- [x] Content-модуль плагина `io.github.dotnetsupport.dap` с зависимостью на `intellij.platform.dap`: всё, что опирается на платформенный DAP, живёт в нём, без DAP выключается только он. Пока в нём заготовка `DotNetDapLaunchArgumentsProvider` (ни к чему не применима) — по ней диагностика видит, что модуль загрузился и классы DAP ему видны
- [ ] Проверить наличие `intellij.platform.dap` и LSP API в GoLand / PyCharm / WebStorm / Rider 2026.1+ (в IDEA Ultimate есть) — диагностикой выше

## Отладка через DAP (план; начат слой 1)
**Действующий план — `PLATFORM_DAP_PLAN.md`**: платформенный DAP-клиент (2026.1+) вместо своего, этапы 0–6. Слои ниже — запасной путь со своим клиентом.
Отладчик `dotnet-debugger` (dotnet tool `dotnet-debugger-dap`) + свой DAP-клиент + платформенный XDebugger. Подробности,
соответствие API и ограничения адаптера — в `DAP_PLAN.md`; результаты проверки адаптера — в `dap-probe/FINDINGS.md`.
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
- [ ] Платформенный DAP, этап 6 (по желанию), остальное: значения в редакторе, async-стек, `runInTerminal` (ввод в консольную программу),
  Set Next Statement, второй адаптер
- [ ] Слой 1: DAP-клиент — фрейминг, корреляция ответов, события, обратные запросы; тесты на фейковом адаптере
- [ ] Слой 2: MVP — Debug у run configurations, точки останова на строках, кадры, переменные (постранично), шаги, evaluate, консоль
- [ ] Слой 3: условия / hit count / logpoints, исключения, Set Value, watches, Run to Cursor, attach, restart
- [ ] Слой 4: отладка тестов (`VSTEST_HOST_DEBUG=1` + attach)
- [ ] Слой 5: значения в редакторе, async-стек, `runInTerminal`, netcoredbg как второй адаптер

## Собственный LSP-клиент (план, не начато)
Подробности — оценка, решения по устройству, риски, способ проверки — в `LSP_PLAN.md`.

Семантика языка через language server без LSP4IJ и без платформенного LSP API (в бесплатных IDE он только с 2026.2):
свой JSON-RPC поверх stdio + интеграция с обычными точками расширения платформы. Первый сервер — `roslyn-language-server`
(MIT, у пользователя стоит 5.12), клиент при этом не привязан к нему: сервер описывается определением (команда, типы файлов,
особенности протокола). Эвристики плагина (раскраска, тесты, endpoints) остаются запасным вариантом, когда сервера нет.
Оценки — в днях работы разработчика; узкое место — проверка редакторного UX в живой IDE, headless-тесты его не покрывают.

### Фаза 0 — разведка (0,5–1 д), решение «идём / не идём»
- [ ] Запустить сервер скриптом, снять реальный трафик: `initialize`, открытие solution (`solution/open` / `--autoLoadProjects`), `workspace/projectInitializationComplete`, pull-диагностика, completion + resolve, динамическая регистрация
- [ ] Замерить время загрузки solution, память процесса, требования к runtime; записать нестандартные методы Roslyn (`_vs_onAutoInsert`, `_roslyn_restore`, вложенные code actions)

### Фаза 1 — транспорт и жизненный цикл (2–3 д)
- [ ] JSON-RPC 2.0: фрейминг `Content-Length`, запросы / ответы / уведомления, `$/cancelRequest`, `$/progress`, таймауты; свои DTO на Gson только под используемые методы (без lsp4j)
- [ ] Процесс сервера на проект: старт по требованию, перезапуск с backoff, stderr, `window/logMessage` / `showMessage`, `workspace/configuration`, `client/registerCapability`
- [ ] URI ↔ путь (Windows: `file:///c%3A/`, регистр диска), позиции UTF-16 ↔ offset
- [ ] Настройки на странице .NET: путь к серверу (как у остальных tools), аргументы, уровень логов, выключатель; виджет статуса (loading / ready / crashed), действие Restart, окно с логом и трассой JSON
- [ ] Тесты: сценарный фейковый сервер на in-memory pipe; тот же транспорт потом годится для DAP (netcoredbg): фрейминг одинаковый

### Фаза 2 — синхронизация документов и диагностика (2–3 д) — первая видимая польза
- [ ] `didOpen` / инкрементальный `didChange` / `didSave` / `didClose`, версии документов; `workspace/didChangeWatchedFiles` из VFS (файлы, созданные нашими же действиями New)
- [ ] Pull-диагностика (`textDocument/diagnostic`) → `ExternalAnnotator`: severity, `unnecessary` серым, `deprecated` зачёркнутым, код правила со ссылкой; отбрасывание ответов для устаревшей версии
- [ ] Состояние «solution загружается»: без ложных ошибок до `projectInitializationComplete`

### Фаза 3 — навигация и документация (2–3 д)
- [ ] Definition / type definition / implementation через `GotoDeclarationHandler`, включая metadata-as-source (декомпилированные исходники фреймворка — «dotPeek бесплатно»)
- [ ] Hover → документация (markdown → HTML), подсветка вхождений (`documentHighlight`)
- [ ] Find Usages через `customUsageSearcher`, Go to Symbol / Class через `workspace/symbol`

### Фаза 4 — completion и signature help (3–4 д) — самое чувствительное к UX
- [ ] `CompletionContributor`: отмена при наборе, `isIncomplete`, `filterText` / `sortText` / `preselect`, commit characters, `itemDefaults`
- [ ] `completionItem/resolve`: `textEdit` + `additionalTextEdits` (авто-`using`), сниппеты LSP → live template
- [ ] Signature help на `(` и `,`

### Фаза 5 — правки кода (3–4 д)
- [ ] Применение `WorkspaceEdit`: `documentChanges`, создание / переименование / удаление файлов, проверка версий, одна undo-команда; `workspace/applyEdit`
- [ ] Rename (`prepareRename` + `rename`), форматирование файла / диапазона (`AsyncDocumentFormattingService`), on-type, `///` через `_vs_onAutoInsert`
- [ ] Code actions: quick fix у диагностики и intentions под кареткой (предзагрузка, потому что intentions синхронные), вложенные действия и Fix All Roslyn

### Фаза 6 — семантическая полировка (2–3 д)
- [ ] Semantic tokens → наша палитра Rider вместо эвристического классификатора (он остаётся фолбэком)
- [ ] Inlay hints, Structure view и breadcrumbs из `documentSymbol`, folding, code lens со ссылками; call / type hierarchy — по желанию

### Фаза 7 — надёжность (2–3 д, частично по ходу)
- [ ] Большие solution, несколько solution в проекте, штормы перезапусков, батчинг `didChange`, dumb mode
- [ ] Предупреждение, если тот же сервер уже подключён через LSP4IJ (двойной запуск)
- [ ] Второй сервер как проверка общности: `csharp-ls`; определения серверов в настройках

### Итог
- MVP (фазы 0–3): 7–10 д — ошибки компилятора в редакторе, переходы, документация, поиск использований
- «Ощущается как IDE» (+ фаза 4): 10–14 д
- Полный объём (фазы 0–7): 17–25 д. «Весь LSP 3.17» не цель: реализуется то, что отдаёт Roslyn (~35 методов), без notebook / moniker / linked editing
- Риски: синхронные API платформы против асинхронного сервера (intentions, structure view, parameter info — решается кэшем последнего ответа); недокументированные расширения Roslyn в prerelease-версиях (фиксировать версию, хранить снятый трафик как фикстуры); память и время старта сервера; сопровождение (LSP4IJ делали годами, но у нас один язык и один-два сервера)

## Вне рамок (нужна семантика языка)
Полный парсер выражений, разрешение ссылок, типизация, инспекции, completion по типам, рефакторинги, собственный форматтер,
inlay-подсказки имён параметров. Парсер уровня объявлений (namespace → типы → члены, тела пропускаются) — в рамках, это заход 2.

Варианты, если понадобится разбирать тела методов: ANTLR4 `grammars-v4/csharp` + `antlr4-intellij-adaptor` (устарела до C# 6–7,
дописывать самим), tree-sitter-c-sharp (лучшая грамматика, но нативная и без PSI), consulo-csharp (Apache 2.0, ручной парсер и PSI —
образец и источник кусков, API разошёлся с IntelliJ).
