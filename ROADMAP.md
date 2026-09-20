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
- [x] New → .NET ▸ генераторы по категориям (C#, ASP.NET, Razor/Blazor, Tests, EF Core, Configuration, Resources): подходящие проекту — сразу, остальные в «Other»; partial-часть, тест для класса, копия .resx для культуры, `dotnet ef`, любой item-шаблон SDK
- [x] New → C# Class / Interface / Record / Struct / Enum с вычисленным namespace (RootNamespace + путь, file-scoped по `.editorconfig`)
### Действия над solution
- [x] Add Existing Project, Remove from Solution
- [x] New Solution Folder (правка `.sln`/`.slnx`)
- [x] Add Project Reference (диалог с галочками)
- [x] Автовыбор панели Solution при первом открытии папки с solution

## Заход 2 — навигация и редактор без парсера
- [ ] Сканер объявлений поверх лексера (namespace / типы / члены по балансу скобок)
- [ ] Structure view, breadcrumbs, folding (блоки, `#region`, `using`, комментарии)
- [ ] Go to Class / Go to Symbol (индекс по файлам), переход между partial-частями, Related file (`.xaml` ↔ `.xaml.cs`, `.razor` ↔ `.razor.cs`, тест ↔ класс)
- [ ] Reformat Code через `dotnet format` (+ при сохранении)
- [ ] Ошибки последней сборки как аннотации в редакторе (ExternalAnnotator)
- [ ] Live templates (`ctor`, `prop`, `cw`, `foreach`, `svm`, `fact`...), Enter в `///` и `/* */`, заготовка `/// <summary>`
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
- [ ] csproj: completion имён и версий пакетов, inlay «доступна новая версия», quick-fix обновления
- [ ] Авто-`dotnet restore` при изменении csproj
- [x] Тесты: дерево результатов из TRX (SMTRunner), переход к исходнику, перезапуск упавших, ▶ у тестовых методов и классов (xUnit / NUnit / MSTest) с `--filter`
- [ ] Тесты: результаты по мере выполнения, а не после завершения (свой VSTest-логгер или протокол Microsoft.Testing.Platform)

## Заход 4 — проект и окружение
- [x] Страница настроек (Settings | Tools | .NET): путь к `dotnet` с проверкой, список установленных SDK, статус `global.json` проекта, переключатели поведения (автосоздание run configurations, окно Build при каждой сборке, автопереключение на Solution view)
- [x] Уведомление при открытии solution: `dotnet` не найден, или `global.json` требует неустановленный SDK (политики `rollForward` сверены с настоящим CLI)
- [ ] New Project в IntelliJ IDEA (`GeneratorNewProjectWizard`)
- [ ] Project Properties (TargetFramework, OutputType, Nullable, LangVersion, RootNamespace)
- [ ] Честное содержимое проекта: `Compile Remove`, linked files, `DependentUpon`-вложение
- [ ] Переименование / перемещение проекта, unload / reload, solution filters (`.slnf`), drag-and-drop в дереве
- [ ] Publish, `dotnet tool restore`, user secrets
- [ ] Редактирование шаблонов генераторов пользователем (сейчас зашиты в плагин)

## Заход 5 — файлы проекта и конфигурации
- [ ] MSBuild: XSD, completion свойств и значений, навигация по `Import` / `ProjectReference` / `$(Property)`, битые пути
- [ ] JSON Schema для `appsettings.json`, `launchSettings.json`, `global.json`
- [ ] Редактор `.resx` (таблица, несколько культур)
- [ ] `.sln`: подсветка и сворачивание секций

## Инструменты
Обёртки над `dotnet` CLI, файлами проекта и HTTP; семантика языка не нужна. ★ — взять первыми.

### Запуск и наблюдение за приложением
- [x] ★ Кликабельные стектрейсы в консоли Run (`at Type.Method() in File.cs:line 42`, в т.ч. локализованные)
- [ ] Сворачивание стектрейсов (кадры `System.*` / `Microsoft.*`)
- [x] ★ Автооткрытие браузера по «Now listening on: http://…» с `launchUrl` профиля; включается галочкой, для сгенерированных конфигураций — по `launchBrowser`
- [ ] ★ Раскраска уровней логов `Microsoft.Extensions.Logging` (`info` / `warn` / `fail`) в консоли
- [x] Окно Endpoints: маршруты minimal API (`MapGet`…, `MapGroup` через переменные и цепочки, `MapMethods`, `MapHealthChecks`) и контроллеров (`[Route]` на классе, `[HttpGet("{id}")]`, `[controller]` / `[action]`, абсолютные шаблоны) по токенам; переход к коду, запрос в `<Project>.http` с переменной хоста из `launchSettings.json`, открыть в браузере, копировать URL; значок на полях у каждого маршрута
- [ ] Endpoints: маршруты из констант и `nameof`, группы, объявленные в другом файле (extension-методы `MapXxxEndpoints`), Razor Pages и `MapHub`, поиск маршрута через Search Everywhere
- [ ] Hot Reload для `dotnet watch`: кнопка Restart, индикатор «изменения применены / нужен перезапуск»
- [ ] Переключатель окружения (`ASPNETCORE_ENVIRONMENT` / `DOTNET_ENVIRONMENT`) по имеющимся `appsettings.*.json`
- [ ] Compound-конфигурация: запуск нескольких проектов solution разом

### Диагностика без отладчика
- [ ] ★ `dotnet-counters`: живые графики CPU, GC, heap, requests/sec, исключений для запущенного процесса
- [ ] ★ `dotnet-stack`: снимок стеков всех потоков зависшего приложения
- [ ] `dotnet-trace`: запись трассы, просмотр flame graph (speedscope во встроенном браузере)
- [ ] `dotnet-dump` / `dotnet-gcdump`: снятие дампа, таблицы `dumpheap -stat`, `clrstack`, `threads`

### Качество кода силами компилятора
- [x] Покрытие тестов: `--collect:"XPlat Code Coverage"` (coverlet) → Cobertura → полосы на полях редактора (покрыто / частично / нет), сводка по файлам в окне «.NET Coverage»
- [ ] Покрытие: сводка по проектам и методам, покрытие для выбранного теста, хранение нескольких запусков
- [ ] Анализаторы Roslyn через сборку: включение `EnforceCodeStyleInBuild` / `AnalysisLevel`, вкладка Problems с группировкой по правилу, «подавить в `.editorconfig`»
- [ ] `dotnet format --verify-no-changes` как проверка перед коммитом
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
- [ ] EF Core: список миграций со статусом applied / pending (`migrations list --json`), SQL-скрипт между миграциями, `dbcontext scaffold` с диалогом, `dbcontext info`
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
- [x] Insert New GUID (Generate, Tools → .NET; мультикурсор)
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
- [ ] Continuous testing: `dotnet watch test` с тем же деревом результатов, рабочая кнопка «Toggle auto-test»
- [ ] Декомпиляция сборок из Dependencies через `ilspycmd` → C# read-only в редакторе
- [x] Сводка по скорости сборки: Tools → .NET → Measure Build Performance — `-clp:PerformanceSummary` → таблицы «что тормозит» по таргетам, задачам и проектам
- [ ] Сохранение binlog (`-bl`) для MSBuild Structured Log Viewer
- [x] Run MSBuild Target…: список таргетов проекта с поиском (`dotnet msbuild -targets`), свои из проекта и `Directory.Build.*` наверху, запуск с выводом в Build tool window
- [x] Show All Files в панели Solution: показать `bin`, `obj` и файл проекта (меню настроек Project view)

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
- [ ] Страница «.NET на этой машине»: `dotnet --info`, `dotnet sdk check` (устаревшие и снятые с поддержки SDK / runtime), вместе с проверкой `global.json`
- [ ] Шаблоны: `dotnet new search`, `dotnet new install / uninstall / update` из диалога New Project (сейчас видны только установленные)
- [ ] Upgrade Assistant: `upgrade-assistant analyze` перед сменой target framework, отчёт о несовместимостях

### Производительность и эксперименты
- [ ] BenchmarkDotNet: ▶ у `[Benchmark]`, запуск в Release, результаты из `BenchmarkDotNet.Artifacts/results/*.csv` таблицей (Mean, Error, Allocated), сравнение с предыдущим прогоном
- [ ] C# REPL: `csharprepl` или `dotnet-script` в консольном tool window, «выполнить выделенное из редактора»

### Порядок
1. File nesting, переключатель Debug / Release + framework, Analyze Stack Trace.
2. Dev-certs и `user-jwts`; Update All и консолидация версий в окне NuGet.
3. Services / Run Dashboard, тест-эксплорер с continuous testing, конфликты версий.
4. Остальное — по запросу: декомпиляция и BenchmarkDotNet эффектны, но нужны реже.

## Вне рамок (нужна семантика языка)
Полный парсер выражений, разрешение ссылок, типизация, инспекции, completion по типам, рефакторинги, собственный форматтер,
inlay-подсказки имён параметров. Парсер уровня объявлений (namespace → типы → члены, тела пропускаются) — в рамках, это заход 2.

Варианты, если понадобится разбирать тела методов: ANTLR4 `grammars-v4/csharp` + `antlr4-intellij-adaptor` (устарела до C# 6–7,
дописывать самим), tree-sitter-c-sharp (лучшая грамматика, но нативная и без PSI), consulo-csharp (Apache 2.0, ручной парсер и PSI —
образец и источник кусков, API разошёлся с IntelliJ).
