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
- [x] 0.1.30 — окно New Solution как в Rider (`newproject/NewSolutionDialog`, логика — `NewSolution.kt`): File | New, меню .NET и стартовый
  экран; категории шаблонов по тегам, Empty Solution, Custom Templates, framework + выбор SDK (`global.json`), язык, неподдерживаемые
  шаблоны под разделителем, Template description (Identity / Group ID из `templatecache.json`), Advanced Settings, Git. Тест
  `NewSolutionTest`. Вживую не проверено создание до конца, стартовый экран, SDK; нет секции Docker (это функция Rider, не `dotnet new`)
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
- [x] 0.1.13 — при открытии .NET-проекта без установленного SDK 10 — модальное окно: сервер C# (Roslyn) работает на .NET 10 и не стартует на
  .NET 8/9 (кнопки Download / Settings / Continue). Балон игнорировали, поэтому явное окно (`SdkCheckActivity.missingDotNet10`, проверка `--list-sdks`)
- [x] 0.1.128 — перехваченная ассоциация типов файлов (`DotNetFileTypeCheck`, по образцу Go-плагина): `*.cs` / `*.csx` / `*.sln` / `*.slnx` / `*.csproj`,
  открывающиеся не нашим типом (пользовательская карта `filetypes.xml` перебивает декларативные `extensions`), — модальный диалог при старте .NET-проекта
  и при открытии такого файла (один за раз, «Not Now» на сессию, «Don't ask again» навсегда) и баннер над файлом с Associate with C# (`DotNetFileTypeNotificationProvider`;
  платформенный тест `DotNetFileTypeCheckTest`). Проверено вживую 2026-10-07 в песочнице (баннер и Associate with C#)
- [x] 0.1.129 — проход `CSharpParseOptions.fill` по всем C#-файлам проекта (символы `#if` и версия языка на `VirtualFile`) больше не держит
  EDT: `checkCanceled` перед каждым файлом (write action прерывает non-blocking read action, иначе ждёт его вместе с UI — фризы 7–19 с
  на репозитории из 31 проекта и 530 файлов, дампы 2026-10-07), проход возобновляется с места остановки, путь нормализуется один раз
  (`CompilationOptions.compilesNormalized`), модель спрашивается один раз на файл. Тесты в `CSharpParseOptionsTest`, `CompilationOptionsTest`
- [x] 0.1.134 — mapping completion (`lang/NativeCSharpMappingCompletion.kt`): в инициализаторе объекта и в начале оператора под блоком присваиваний
  `dto.X = user.X;` — строка `Name = user.Name,` / `dto.Name = user.Name;` для каждого ещё не заданного члена цели, которому по имени (точно, без
  учёта регистра, общее начало/конец слов `UserId` ↔ `Id`, перекрытие camel-слов) и по типу (identity / implicit, nullable) подходит значение
  под рукой (локальные, параметры, поля и свойства типа и их публичные члены на два уровня), плюс строка «Map all remaining members from user»
  (все уверенные совпадения партнёра в порядке объявления, с отступом строки). Партнёр — объект, из которого копируют соседние присваивания;
  неоднозначный лучший кандидат — строки нет; `init` только в инициализаторах, required первыми. Первыми в списке, когда контекст явно mapping,
  иначе после обычных; серое ` map`. Настройка Settings | .NET → Behavior. Тесты `CSharpMappingCompletionTest`; сценарий `Console/Editor/Mapping.cs`.
  Вживую не проверено
- [x] 0.1.138 — порядок пространств имён по статистике корпуса (`ml/CSharpImportStats.kt`, артефакт движка e20 `ml-models/csharp/cs-imports-e20.cml`):
  в «Import type» (список исправления и то, что оно добавляет одним шагом) и среди ещё не подключённых типов списка дополнения (weigher `dotnetImportStats`
  после `priority`) первым идёт пространство, которое корпус чаще берёт для этого имени вместе с уже имеющимися using (own namespace и global usings тоже
  контекст). Только порядок найденного индексом; неизвестное имя — порядок индекса; using по `rankCoImports` не добавляются. Загрузка лениво на pooled
  thread из каталога моделей настроек / ресурсов ML-сборки / `ml-models/csharp`; работает и в обычной сборке. Настройка Settings | .NET → Behavior.
  Тесты `CSharpImportStatsTest`; сценарий `Console/Editor/ImportType.cs` (`import-stats-*`). Вживую не проверено
- [x] 0.1.135 — память выбора («учится у меня», `suggest/CSharpAcceptanceMemory.kt`, `ML_ACCEPTANCE.md`): счётчик выбранных элементов списка на
  проект по (вид места `ContextKind` движка: после точки / начало оператора / аргумент / тип / справа от `=` / прочее, lookup string) в workspace-файле,
  вдвое меньше каждый месяц, лимиты; `LookupListener` на каждом списке C# (вид места читается не на EDT). Без ранкера — weigher `dotnetAcceptedBefore`
  (после `priority`: внутри группы одного приоритета чаще выбранное выше); с ML-ранкером — `0.3 × ln(1 + count)` к оценке (вес на странице ML completion).
  Настройка on/off и кнопка «Forget the Choices» в Settings | .NET → Behavior. Тесты `CSharpAcceptanceMemoryTest`. Вживую не проверено
- [x] 0.1.130 — экспорт реальных списков completion для ML-ранкера (`ML_RANKER_EXPORT_TASK.md`, плагинная половина e18 движка
  `../idea-ml-completion`): признаки кандидата — `csharp-psi-ide` `ml/CSharpMlFeatures.kt` (`CSharpMlLanguage`, `CSharpMlCandidate`, 19 признаков
  языкового блока поверх 13 общих `ml-core`: вид one-hot, static, уровень области `CSharpMlScope` 0–5, нужен ли `using`, совпадение с ожидаемым
  типом, объявлен ли в файле и расстояние, ранг по правилам плагина, после точки); completion плагина вешает `CSharpMlCandidate` на каждый свой
  элемент (`lang/NativeCSharpMlInfo`: имена без точки, члены после точки, импорт, строки ожидаемого типа; чужие элементы — fallback OTHER
  с приоритетом). Экспорт — test-scope основной части (не `csharp-psi-ide`: completion живёт в основном модуле) `ml/CSharpMlDatasetExport.kt` +
  `CSharpMlExporter`, Gradle-задача `mlDataset` (`-Pml.repos -Pml.lm -Pml.data -Pml.out -Pml.perFile=10 -Pml.maxFiles=120 -Pml.cache=0.3
  -Pml.names -Pml.seed -Pml.heap=6g`): шард `.cmlx` на репозиторий, готовые пропускаются, сломанный репозиторий логируется и не роняет прогон,
  сводка `ml: TOTAL …` с recall. Тест `CSharpMlDatasetExportTest` (экспорт на фикстурном репозитории, языковой блок, инфо на элементах).
  Weigher `CSharpMlCompletionRanker` — после обучения ранкера в движке
- [x] Live templates для C# (33: `ctor` с именем типа, `prop*`, `cw`, циклы, `try`, `using`, `svm`, типы, `fact` / `theory` / `test` / `testm`, `region`…), Enter внутри `///` продолжает комментарий, третий `/` над объявлением даёт `<summary>` с `<param>` и `<returns>`
- [x] 0.1.17 — серый текст: `break;` первой (пустой) строкой секции `case`/`default` (`CSharpGhostText.breakInCase`, тест). Вживую не проверено
- [x] 0.1.16 — серый текст (Tab): `;` в конце незавершённого оператора (те же безопасные случаи, что у Complete Statement) и
  ` => throw new NotImplementedException();` после заголовка метода в class/struct/record (`CSharpGhostText.semicolon` / `notImplemented`, тесты). Вживую не проверено
- [x] 0.1.15 — Complete Statement (Ctrl+Shift+Enter): дописывает `;` только для явных операторов (присваивание, вызов, `return`/`throw`/`break`…),
  заголовки (`if (x)`, `void M()`) и несбалансированные скобки не трогает (`CSharpCompleteStatement.needsSemicolon`, покрыто тестом). Вживую не проверено
- [x] 0.1.14 — ещё postfix-шаблоны: `.for`, `.forr` (обратный), `.cast` (`((T)expr)` с выделенным плейсхолдером типа)
- [x] 0.1.10–0.1.12 — редакторные мелочи поверх лексера: Extend/Shrink Selection (Ctrl+W) структурно по скобкам и строкам/комментариям
  (`CSharpSelectioner`); Enter внутри `//` и `/* */` продолжает комментарий (`CSharpCommentEnterHandler`); подсветка вхождений идентификатора
  под кареткой, пока сервер не покрыл файл (`CSharpHighlightUsagesHandlerFactory`, уступает `documentHighlight` сервера). Вживую не проверено
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
- [x] Неактивные ветки `#if` по `DefineConstants` — серые с 0.1.67 (исключённый текст своего дерева, `NativeCSharpInactiveCode`;
  сценарий — `debug-playground/Broken/SyntaxErrors.cs`, `TYPE:diag-directives`)
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
- [x] 0.1.19 — Журнал плагина (2026-10-02, по просьбе пользователя: расследовать, почему не стартует `roslyn-language-server`, по idea.log было нельзя — плагин ничего не писал).
  `PluginLog.info/warn/error(категория, текст)`: строка `ЧЧ:ММ:СС.мс УРОВЕНЬ [категория] текст` в `~/idea-dotnet-logs/plugin/plugin-ДАТА.log`,
  коротко в idea.log, и в окно **.NET → Plugin Logs** (консоль, только события плагина; Clear, «Warnings and Errors Only», Open Logs Folder).
  Исключения внешних программ — одной строкой (сообщение; для неожиданных — класс и кадр плагина), без стек-трейсов (`PluginLog.describe`).
  Категории: `dotnet` (каждая команда: что запущено, где, код выхода, хвост ошибки; вывод по-прежнему в `commands/`), `tools`, `sdk`
  (dotnet, SDK и runtime при открытии решения), `roslyn` (команда запуска, DOTNET_ROOT, stderr сервера, инициализация, загрузка, остановка),
  `helpers`, `debugger`, `format`, `nuget`, `templates`, `monitor`, `run`, `ef`, `allocations`. Уведомления о том, что не стартовало: сервер
  Roslyn (разбор stderr хоста .NET — какой runtime нужен, где хост искал и что нашёл; `DOTNET_ROOT` сервера = папка `dotnet` плагина),
  процесс сервера не создался, адаптер отладчика пропал без `terminated`, индексатор сборок не собрался (раз за сессию); у каждого
  уведомления об ошибке — кнопка Plugin Logs. Меню .NET → Show Plugin Logs переименовано в Open Logs Folder. Вживую не проверено
- [x] 0.1.21 — Полный журнал фидов NuGet (2026-10-02, по просьбе пользователя: в корпоративной сети CLI ставит пакеты, а поиск окна пуст;
  причиной оказался `UnknownHostException` у JVM IDE при живом прокси у CLI, но журнал писал его раз за сессию и без класса исключения).
  `NuGetClient` пишет каждый GET: URL, маршрут IDE (`direct` / через какой прокси, по `JdkProxyProvider`), есть ли учётные данные, код
  ответа, размер и время, сервисы индекса фида, итог каждого поиска и списка версий по фиду; ошибка — класс и цепочка причин плюс
  подсказка по настройке IDE (`NuGetNetwork.describeFailure`: HTTP Proxy / Server Certificates / учётные данные источника). Подавления
  «раз на хост» нет. Первый запрос пишет сводку: настройки прокси IDE, свойства JVM, переменные `*_PROXY` окружения IDE. Те же строки — во
  вкладке Log окна NuGet; заголовок списка говорит «N of M feeds did not answer». Источники из `dotnet nuget list source` — в журнале как есть.
  Вживую не проверено
- [x] Диалог фида как в Rider (New / Edit): Name, URL, User, Password, Enabled, Allow insecure connections, Disable TLS certificate validation; учётные данные — в `nuget.config` через CLI и в хранилище паролей IDE для поиска по приватным фидам; пароли замаскированы в логах и прогрессе
- [ ] Окно NuGet: README пакета, правка версий в `Directory.Packages.props` при CPM, выбор файла конфигурации для нового фида (сейчас — куда пишет CLI, т.е. пользовательский)
- [x] 0.1.4 — Уязвимые и устаревшие (deprecated) пакеты в окне NuGet (`dotnet list package --vulnerable | --deprecated --format json`, два вызова —
  флаги взаимоисключающие): пометка в строке списка (severity для уязвимых, «Deprecated») и строка в карточке со ссылкой на advisory. Outdated уже
  показывался стрелкой «→ latest». Вживую не проверено
- [x] csproj / `Directory.Packages.props`: сетевой completion пакетов — id в `Include` / `Update` у PackageReference / PackageVersion / GlobalPackageReference / PackageDownload (поиск по фидам solution, порядок фида, версия, загрузки, ✓ verified; от 2 символов, перезапрос при наборе), версии в `Version` / `VersionOverride` (атрибутом и тегом; новые сверху, prerelease — по настройке или когда набирается `-`); выбор пакета дописывает `Version="<последняя>"`, кроме CPM и когда версия уже есть; запрос в пуле с отменой при следующем символе, кеш на 5 минут.
  0.1.20 (2026-10-02, по просьбе пользователя): строка как в Rider — `Имя • версия` серым, без загрузок и ✓, окно уже; следующие символы фильтруют список
  на месте (`FeedMatcher`, `restartCompletionWhenNothingMatches`), фид спрашивается снова только когда ничего не осталось — раньше список
  перестраивался на каждый символ и у коллег на Linux «моргала» версия; `.` и `-` в id продолжают набор (`PackageIdCharFilter`), а не вставляют
  выбранный пакет. В окне NuGet у строки списка больше нет удаления по клику у правого края — только в карточке (пакеты удалялись случайно).
  Строка списка окна NuGet — как в Rider: значок, `Имя • установленная версия • Фид` (имя фида из `nuget.config` золотым), справа синим последняя
  версия, уязвимый/устаревший — значком с подсказкой, описания и иконок действий в строке нет (установка, обновление, удаление — в карточке); заголовки «Installed Packages in Solution: N», «Available Packages: N».
  Список держит ширину окна (`getScrollableTracksViewportWidth`): раньше длинное описание растягивало строки, и версии у правого края «появлялись
  и исчезали» после Refresh
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
- [x] Страницы настроек по образцу Rider, дочерние к Settings | .NET, опция в опцию; то, за чем у плагина пока ничего нет, показано выключенным с замком и причиной в подсказке (`settings/RiderSettingsUi.kt`):
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
- [x] Страница настроек (Settings | .NET): путь к `dotnet` с проверкой, список установленных SDK, статус `global.json` проекта, переключатели поведения (автосоздание run configurations, окно Build при каждой сборке, автопереключение на Solution view)
- [x] 0.1.18 — Дополнительные папки для поиска `dotnet` (Settings | .NET, поверх PATH): сама папка и вложенные `dotnet*` на один
  уровень (`/usr/share` находит `/usr/share/dotnet-sdk-8.8.403`), новейшая версия по имени папки; тот же список — переменной среды
  `DOTNET_SUPPORT_SEARCH_PATHS` для раскатки политикой на корп-машины (`DotNetSearch`, поиск чистыми функциями). **Вживую не проверено**
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
- [x] Publish (0.1.35); [ ] `dotnet tool restore`, user secrets
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
- [x] 0.1.79 — Compound-конфигурация: запуск нескольких проектов solution разом. Запуски, стартовавшие вместе (Compound, Run / Debug N Projects, несколько подряд за ~0,3 с), собираются **одной** сборкой (`run/LaunchBuilds`): проекты одного solution — временным `.slnf` в окне Build, остальное (проект вне solution, выбранный TFM мультитаргет-проекта) — по очереди; затем `dotnet run --no-build`. Замер на площадке (Web + Worker → Lib): `.slnf` 1,9 / 2,1 / 1,9 с (чисто / без изменений / Lib изменён) против 3,6 / 3,2 / 4,2 по очереди и 3,3 / 1,9 / 3,1 двумя сборками разом. Падение сборки не запускает ни одного из набора (уведомление). «Save as Compound Configuration» (уведомление после Run N Projects, ПКМ Solution view, меню Run — из запущенных): платформенный Compound `Web + Worker` / «Multiple Projects», недостающие конфигурации .NET Project сохраняются и кладутся в папку с именем compound — Services группирует их, Stop / Rerun группы. «Wait for» в конфигурации .NET Project (started / listens / health URL, таймаут; в записи Compound места под опции нет): ожидание в шаге «Build .NET Project» с прогрессом и уведомлением по таймауту, проверка «ждёт сам себя» и цикла. Debug Compound в IDEA Community не запускался вовсе (раннера платформы для него нет) — теперь `DotNetCompoundDebugRunner`. Робот (Windows, 2026-10-05): одна сборка `Web+Worker.slnf` для Run и Debug compound, Run 2 Projects и Run группы из Services, `--no-build`, Wait for (1,7 с до `Now listening`) и таймаут 60 с, падение сборки — ничего не запущено, Save из уведомления, группа в Services, Stop группы. Тест `CompoundRunTest`; сценарий — `debug-playground/README.md` «Запуск нескольких проектов и Compound» (`Web` + новый `Worker`, `BP:worker-round`); строки E-150…E-154 в `docs/LIVE_CHECKS.md`

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
- [x] 0.1.4 — Устаревшие (`--deprecated`) пакеты в окне NuGet (см. выше). Лицензии пакетов по метаданным NuGet — ещё нет
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
- [x] 0.1.52 — индекс сборок для семантики (формат 2, шаг 10 миграции, B1–B2): все публичные и protected типы и члены с сигнатурами (generic-параметры с ограничениями, база, интерфейсы, атрибуты, nullable, кортежи, значения по умолчанию и констант), extension-методы по расширяемому типу, XML-документация отдельным файлом `.dnxd` (блоки deflate: 3,5 МБ на эталонный пакет .NET 10 вместо 31 МБ XML); читатель `index/AssemblyIndex`, `IndexedTypeRef`, `AssemblyDocs`, резолвер по сборкам проекта `AssemblyIndexSet` (тип по имени, базы и интерфейсы с подстановкой, унаследованные члены, extension-методы, доки). Индексатор параллельно: эталонный пакет — 0,83 с с документацией. Ссылки проекта (`ProjectAssemblies.references`): платформа из тулбара, пакеты, reference packs (и скачанные restore), .NET Framework (пакет `Microsoft.NETFramework.ReferenceAssemblies` или папка машины), `HintPath`, проекты старого формата, `ProjectReference` — проектами. Тесты `AssemblyIndexSemanticsTest` (фикстура `tools/index-fixture`), `ProjectReferencesTest`. В редакторе пока ничего не меняется — потребитель будет у семантики (шаг 11)
- [x] Completion в C# не смотрит на регистр (2026-09-29, по проверке, которую просил пользователь): платформа сопоставляет префикс по настройке IDE (Editor | General | Code Completion | Match case, по умолчанию «первая буква»), и `writeli` не находил `WriteLine` — ни среди элементов сервера, ни среди элементов индекса. `lang/CSharpCaseInsensitiveCompletion` — первый в цепочке (остальные идут `after dotnetCaseInsensitive`), запускает остальных с `CamelHumpMatcher(prefix, false)`; совпавшее и по регистру по-прежнему выше. Настройка IDE для C# не действует. Серый текст остаётся чувствительным к регистру намеренно: он дописывает остаток слова и не может заменить уже набранные буквы. Тест в `ImportCompletionTest`. Вживую не проверено
- [x] Инструменты на странице настроек — как в Go-плагине (2026-10-01, по просьбе пользователя): под каждым инструментом своя строка с
  найденным путём («путь из настроек» / найден плагином / «Не установлен: dotnet tool update --global …»), в поле — только короткий
  placeholder, путь в него не влезал; установка — `Task.Backgroundable` с прогрессом в статус-баре и последней строкой вывода в строке
  инструмента; `DotNetTool.find` пишет в лог, где нашёл или где искал (PATH IDE и `~/.dotnet/tools`, `DOTNET_CLI_HOME`), один раз на
  изменение, плюс PATH IDE целиком при первом «не найден»; инструменты ищутся заранее — в `SdkCheckActivity` при открытии solution.
  По снимку пользователя (поля вылезали за край): placeholder поля — снова найденный путь, строка под полем — откуда он (PATH / папка /
  настройки); ширину страницы распирали не строки инструментов, а combo box форматтера с длинными подписями (697 px), label состояния
  форматтера и длинный чекбокс — подписи combo box короткие (пояснения в комментарии), строки состояния — комментарии DSL (переносятся),
  все комментарии страницы — 56 символов в строке (`COMMENT_WIDTH`): страница просит 728 px вместо 840. Измерено роботом
  (`tools/ui-robot/scripts/settings_widths.js`), снимок на 900 px — без горизонтальной прокрутки при скрытом дереве настроек
- [x] Цвета статуса VCS у узлов проекта и solution (2026-09-30, по замечанию пользователя «нет подсветки файлов по цветам статуса git»).
  Проверено роботом: файлы красились и раньше (`PsiFileNode`), папки — при опции платформы Settings | Version Control | Confirmation →
  «Highlight directories that contain modified files» (по умолчанию выключена, `VcsConfiguration.SHOW_DIRTY_RECURSIVELY`); узлы проекта и
  solution стояли на `NOT_CHANGED`. Теперь `SolutionFileNode.getFileStatus` = `FileStatusManager.getRecursiveStatus` папки файла (та же
  опция), solution folder — по проектам под ним. Со включённой опцией проект и solution с изменениями синие, как в Rider; снимок робота
- [x] Дедлок настроек при старте (2026-09-30, найден роботом: в песочнице не стартовали ни сервер Roslyn, ни проверка SDK — всё, что трогает
  `DotNetSettings`, висело в `getService`). Причина: `PluginLanguage.toString()` брал текст из `DotNetBundle`, бандл спрашивал язык у
  `DotNetSettings`, а XML-сериализатор вызывает `toString()` констант enum, пока `DotNetSettings` ещё загружается, — сервис ждал сам себя.
  Воспроизводилось у всех, кто хоть раз выбрал язык страниц настроек. У `PluginLanguage`, `MsBuildVerbosity`, `FormatterChoice` вместо
  `toString()` теперь `label`, combo box рисует его через `textListCellRenderer`; `DotNetBundleTest` сторожит `toString() == name`

### Проект и зависимости
- [x] 0.1.5 — «Почему этот пакет здесь»: ПКМ по пакету в окне NuGet → «Why Is This Installed?» запускает `dotnet nuget why <scope> <id>` и
  показывает дерево зависимостей по каждому TFM в диалоге (нужен .NET SDK 8.0.400+). Вживую не проверено
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
- [~] 0.1.6 — Запуск одиночного `.cs` (`dotnet run file.cs`, .NET 10): действие «Run C# File with dotnet» в контекстном меню редактора для
  `.cs`, который не входит ни в один проект (file-based app); вывод в консоли с кнопкой Stop (`RunContentExecutor`). Полноценной run
  configuration с ▶ на тулбаре и `.csx` пока нет. Вживую не проверено
- [ ] ★ Paste Special: JSON → record-ы / классы с `JsonPropertyName`; XML → классы
- [x] Insert New GUID (Generate, меню .NET; мультикурсор)
- [ ] Вставка `DateTime`-форматов, конвертация строки в verbatim / raw
- [x] 0.1.90 — Инъекция RegExp в строки `[StringSyntax]` / `Regex(...)` / `[GeneratedRegex]` / `// lang=regex` (даёт встроенный Check RegExp)
- [ ] `.resx` ↔ CSV / JSON для переводчиков, проверка недостающих ключей между культурами

## Инструменты, часть 2 — как в Rider и IDEA Ultimate
То, что в Rider сделано панелями и переключателями, а в IDEA Ultimate есть для Java. По-прежнему без семантики языка:
`dotnet` CLI, файлы проекта, уже написанные парсеры. ★ — взять первыми, в скобках — оценка трудоёмкости.

### Панели и переключатели Rider
- [x] ★ File nesting: `appsettings.*.json` под `appsettings.json`, `Foo.razor.cs` / `Foo.razor.css` под `Foo.razor`, `*.Designer.cs` под `.resx`, `*.xaml.cs` под `.xaml` — через `ProjectViewNestingRulesProvider` (пара часов)
- [x] ★ Переключатель конфигурации решения в тулбаре: Debug / Release и target framework; подставляется в сборку, запуск и тесты (`-c`, `--framework`) (день).
  С 0.1.70 — в меню стрелки кнопки Build Solution (ниже), отдельного комбобокса нет, как в Rider
- [x] 0.1.70 — UI как в Rider (решения пользователя 2026-10-05; снимок Rider — `docs/rider-analysis`):
  - настройки плагина — свой узел **.NET** в корне Settings (Toolset and Build, NuGet, Coverage, Debugger, Language Server), не под Tools;
    Editor | Code Style | C# и Color Scheme | C# — на своих местах;
  - в тулбаре вместо комбобоксов Debug/Release и TFM — split-кнопка **Build Solution** (`build/BuildSolutionBar`, перед Run widget, как
    `BuildSolutionBar` Rider): молоток собирает solution, стрелка — Build / Rebuild / Clean / NuGet Restore / Cancel Build и выбор
    конфигурации и target framework;
  - в редакторе C#: **Refactor This** (Ctrl+Alt+Shift+T — сочетание Rider в раскладке по умолчанию; то же действие платформы
    `Refactorings.QuickListPopupAction`, что настраивает Rider), **Navigate To** (Ctrl+Shift+G, `DotNet.NavigateTo`, ещё в меню Navigate),
    **Generate** (Alt+Insert, действие платформы `Generate`): списки из того, что есть у каретки, — действия платформы под именами Rider
    (Rename, Declaration, Base Symbols…), свои intention-рефакторинги и генераторы (Introduce / Inline Variable, Move type to file,
    Unit Test), code actions сервера (`roslyn/RoslynPopupContributor`, точка расширения `csharpPopupContributor`); недоступное скрыто;
  - ПКМ редактора C#: Find Usages Advanced… после Find Usages, подменю Inspect (Call / Type Hierarchy, IL Code), Quick Definition,
    «Generate Code…» — как в Rider. Главное меню .NET не менялось.
  Нет своих генераторов Rider: Constructor, Read-only properties / Properties, Missing / Overriding / Delegating / Partial members,
  Deconstructor, Equality members / comparer, Relational members / comparer, Formatting members, Dispose pattern — пока только то, что
  отдаёт сервер. Сценарий — `Console/Editor/RiderPopups.cs`; `docs/LIVE_CHECKS.md` E-91…E-95
- [x] ★ Analyze .NET Stack Trace: вставить стектрейс из лога или тикета → кликабельные кадры (фильтр уже есть; час-два)
- [x] Unit Tests explorer: окно со всеми тестами solution без запуска (токенное обнаружение уже есть), запуск выделенного, группировка проект / namespace / класс
- [x] Окно Unit Tests внизу, как в Rider: вкладка Explorer плюс сессии — результаты `dotnet test` идут в это окно, а не в Run (свой program runner, повторный запуск переиспользует вкладку); окна Build и .NET Coverage не исчезают с панели
- [ ] Continuous testing: `dotnet watch test` с тем же деревом результатов, рабочая кнопка «Toggle auto-test»
- [x] 0.1.65 — хвосты A8, ждавшие C2 (`lang/NativeCSharpUsings`, `NativeCSharpUsingChecks`; типы — `lang/semantic/CSharpTypeFacts`):
  CS1674 / CS8418 на ресурсе `using`, не реализующем `IDisposable`, CS8410 / CS8417 — на `await using` без `IAsyncDisposable` (тексты и
  коды Roslyn, подчёркивание на объявлении или выражении; копия той же ошибки от сервера убирается); список `using (` / `using var x = `
  без локальных, параметров, полей и свойств, про которые известно, что они не disposable (их строки сервера — тоже). Только где тип
  известен целиком: неизвестный тип, нерешённая база, параметр типа, шаблон `DisposeAsync` / `Dispose` у `ref struct`, сборки без
  System.Runtime — ни ошибки, ни фильтра. Сценарий — `Usings.cs`, `TYPE:using-cs1674`, `TYPE:using-list`; `docs/LIVE_CHECKS.md` E-79.
  Вживую не проверено
- [x] 0.1.63 — Декомпиляция сборок из Dependencies → C# read-only в редакторе. Не `ilspycmd`: DotNetHelper, метод `decompile`
  (`helpers/dotnethelper/Decompile.cs`, ICSharpCode.Decompiler 11.1, тот же, что у IL Viewer) — C# типа целиком, XML-доки, смещения членов по
  XML doc id; reference assembly подменяется реализацией (`decompiler/ImplementationAssemblies`: `packs/*.Ref` → `shared/`, `ref/` → `lib/`
  пакета, .NET Framework → `%WINDIR%/Microsoft.NET/Framework64`), forwarded-тип берётся из сборки, где он определён. Плагин —
  пакет `decompiler`: свой VFS `dotnet-decompiled://` (а не голый LightVirtualFile — URL ведёт обратно к файлу: история навигации, Back,
  вкладки после перезапуска), файл C# только для чтения, баннер «Decompiled from … Read-only» со ссылкой на dll, заголовок вкладки
  `Console.cs [System.Console]`, кэш в памяти и на диске по (dll, время, тип). Вход — ПКМ по пакету / сборке в Dependencies → Decompile...
  (список типов из индекса сборок, иначе у помощника); точка входа для навигации из кода — `AssemblyDecompiler.open(...)`. Замеры
  (SDK 10, первый запрос к сборке / следующий тип той же): `System.Console` 0,3 с / 0,03 с, `System.String` через `System.Runtime`
  (CoreLib) 2,2 с, `JsonSerializer` 0,5 с, Newtonsoft `JsonConvert` 0,2 с. Тест `DecompilerTest` (ответы записаны `tools/decompiler/record.py`).
  Сценарий — `debug-playground/README.md`, «Декомпиляция». Вживую не проверено
- [x] Сводка по скорости сборки: меню .NET → Measure Build Performance — `-clp:PerformanceSummary` → таблицы «что тормозит» по таргетам, задачам и проектам
- [ ] Сохранение binlog (`-bl`) для MSBuild Structured Log Viewer
- [x] Run MSBuild Target…: список таргетов проекта с поиском (`dotnet msbuild -targets`), свои из проекта и `Directory.Build.*` наверху, запуск с выводом в Build tool window
- [x] Show All Files в панели Solution: показать `bin`, `obj` и файл проекта (меню настроек Project view)
- [x] Edit 'App.csproj' / Edit 'App.slnx' в ПКМ узла проекта и solution; перетаскивание такого узла в редактор открывает его файл (как в Rider): узлы — `AbstractPsiBasedNode`, иначе drag source платформы не начинает перетаскивание
- [x] То, что добавляет Show All Files (`bin`, `obj` с содержимым, файл проекта), в Solution view окрашено цветом «ignored» схемы — как файлы из `.gitignore`, но без зависимости от git
- [x] Кнопка-«глаз» Show All Files в заголовке окна Project (группа `ProjectViewToolbar`), видна только в Solution view

### Веб-разработка на ASP.NET
- [x] 0.1.7 — HTTPS dev-сертификат: при открытии solution с web-проектом (`isWebSdk`) проверка `dotnet dev-certs https --check --trust`, и если
  не доверен — уведомление с кнопками Trust (`dotnet dev-certs https --trust`) и «Don't ask again» (флаг на проект). Вживую не проверено
- [~] 0.1.9 — `dotnet user-jwts`: действие «Insert Development JWT» в контекстном меню `.http` создаёт dev-токен для ASP.NET Core проекта
  (`user-jwts create --output token`) и вставляет `Authorization: Bearer …` на строке каретки. Диалога с ролями/scope/сроком и списка
  выданных токенов пока нет. Вживую не проверено
- [ ] HTTP Client environments: `http-client.env.json` из `applicationUrl` профилей `launchSettings.json`
- [x] Services / Run Dashboard (2026-09-29): `runDashboardDefaultTypesProvider` ставит «.NET Project» в окно Services, `runDashboardCustomizer` дописывает
  ссылку на «Now listening on» (адрес хранится на process handler — `ListeningAddressRecorder`, и у Run, и у Debug). Вживую не проверено
- [x] F2 на узле проекта — Rename Project (`renameHandler`), Delete — Remove from Solution (DeleteProvider из `uiDataSnapshot` панели) (2026-09-29)
- [ ] Проверка AOT / trimming: «Check AOT compatibility» → `dotnet publish -r <rid>`, предупреждения `IL2xxx` / `IL3xxx` отдельным списком с переходом к коду
- [ ] User Secrets: проверка, что ключ из `appsettings.json` перекрыт секретом (дополнение к пункту из раздела «Данные и API»)

### Зависимости (аналог Dependency Analyzer и Package Checker)
- [x] Update All: «Upgrade Packages in Solution» (меню .NET → NuGet и боковой тулбар окна) обновляет все устаревшие пакеты solution до последней
  стабильной с предпросмотром списка (было в 0.1.0). Проектной области у действия пока нет
- [x] 0.1.8 — Консолидация версий: «Consolidate Package Versions» (меню .NET → NuGet и боковой тулбар окна) находит пакеты с разными версиями в
  проектах solution и приводит их к наибольшей используемой, с предпросмотром списка (`NuGetService.consolidations()`). Вживую не проверено
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
2. Dev-certs (0.1.7) и `user-jwts` (0.1.9); Update All и консолидация версий в окне NuGet сделаны (vulnerable/deprecated и «why» — 0.1.4/0.1.5, консолидация — 0.1.8).
3. Services / Run Dashboard, тест-эксплорер с continuous testing, конфликты версий.
4. Остальное — по запросу: декомпиляция и BenchmarkDotNet эффектны, но нужны реже.

## Из сравнения с Visual Studio Community (`COMPARE_VSCOM.md`, 2026-10-01)
Чего нет ни в плагине, ни в плане, но делается на `dotnet` CLI / XDebugger / ответах сервера без своего парсера. Версия у сделанного — та, в которой фича вышла (`CHANGELOG.md`).
- [x] 0.1.1 — просмотр строковых значений в отладчике как JSON / XML / HTML / JWT: литерал адаптера разбирается обратно в текст
  (`DotNetValue.unquote`), у длинных / многострочных / структурированных строк ссылка «View» (`XFullValueEvaluator`) и текст для hover
  (`XValueTextProvider`), вкладки рисуют визуализаторы платформы. Строка, которую адаптер не отдал (ошибка вместо литерала, `dap-probe/FINDINGS.md`
  #4), ссылки не получает; в 2026-10 строка на 5000 символов уже пришла целиком. Сценарий — `BP:view-text` в `debug-playground/Console/Scenarios.cs`.
  Проверено UI-роботом 2026-10-01 (`tools/ui-robot/scripts/view_text.js`): «View» у длинных строк, вкладки JSON / HTML+XML / JWT / Raw, у короткой
  строки ссылки нет; само всплывающее окно по клику роботом не открывалось
- [x] 0.1.1 — временные и зависимые точки останова (Remove once hit, Disable until hitting the following breakpoint): работают средствами XDebugger
  без изменений кода — обработчик честно регистрирует / снимает точки, остановка сообщается по `hitBreakpointIds`. Проверено UI-роботом 2026-10-01
  (`tools/ui-robot/scripts/dependent_bp.js`): ведомая молчит до мастера, мастер останавливает один раз и удаляется. Сценарий — `BP:dependent-master`
  / `BP:dependent-slave` в `debug-playground/Console/Scenarios.cs`
- [x] 0.1.2 — Code Metrics: maintainability index, cyclomatic complexity, class coupling, depth of inheritance, строки кода — по проекту / solution
  деревом с переходом к коду. Calculate Code Metrics (меню .NET и ПКМ в Solution) открывает окно «Code Metrics»: дерево namespace / тип / член с
  колонками как в Visual Studio, у индекса — цветная полоса; измеряет хелпер на Roslyn (`metrics/Program.cs`, собирается на машине), solution не
  собирается — ссылки берутся из последней сборки. Вживую не проверено
- [ ] Code Cleanup на правилах Roslyn: `dotnet format style` / `analyzers` с выбором диагностик и severity, профили в настройках
- [ ] docker-compose: run configuration `docker compose up` для сервисов, отладка процесса в контейнере (адаптер через `docker exec`); тот же транспорт — для WSL / remote
- [ ] T4: Transform для `.tt` через `dotnet-t4` (Mono.TextTemplating), вложение результата под `.tt`
- [x] 0.1.37 — Find Usages с группировкой: по проекту / файлу и по типу использования (чтение, запись, вызов, `new`, `typeof`/`nameof`) по соседним токенам

## До уровня Rider и .NET Framework (план 2026-10-03)
Из разбора «чего не хватает до Rider» и аудита поддержки .NET Framework (net4x SDK-стиля и legacy-проекты без SDK). Адаптер отладчика
(`dotnet-debugger`) под desktop CLR дорабатывает пользователь; здесь — сторона плагина. Порядок — от быстрого к сложному; оценки — рабочие дни.
### Этап 1 — быстрое
- [x] 0.1.22 — Run / Debug N Projects при выделении нескольких проектов в Solution view: сборки по очереди, затем запуск вместе (`dotnet run --no-build`); сборки перед Debug идут по очереди (`DotNetDebugBuild.build`), так что и платформенный Compound не собирает общие зависимости дважды разом. Compound + Run собирал параллельно (`dotnet run` каждый сам) — с 0.1.79 одна сборка на весь набор (`LaunchBuilds`). Тесты `ServicesAndNodeActionsTest`, `RiderPanelsTest`; чек-лист — `debug-playground/README.md` «Запуск нескольких проектов». Вживую не проверено
- [x] 0.1.24 — `packages.config` (`msbuild/PackagesConfig`): пакеты в Dependencies → Packages и как установленные в окне NuGet; Install / Update / Remove у такого проекта выключены с объяснением, сервис их пропускает (запись в Log). Тесты `DependenciesTreeTest`, `NuGetTest`. Вживую не проверено
- [x] 0.1.23 — Attach к процессам .NET Framework: управляемый `.exe` (CLI-заголовок PE, `PortableExecutable.isManaged`) без `runtimeconfig.json`; за ключом реестра `dotnet.debugger.attach.netFramework` до готовности адаптера (ключ снят в 0.1.69). Хосты, сами грузящие CLR (`w3wp.exe`, Office), так не находятся — нужен список модулей процесса. Тест `DebugLaunchTest`; чек-лист — `debug-playground/README.md`
- [x] 0.1.26 — Hot Reload в `dotnet watch` (`run/HotReload`): состояние по выводу `dotnet watch` (`DOTNET_CLI_UI_LANGUAGE=en`; тексты SDK 9 и 10 сняты с живого CLI, SDK 8 — по исходникам) в строке Services и цветом в консоли, Restart ссылкой и кнопкой — перезапуск конфигурации (клавиши `dotnet watch` из pipe не читает). Тест `HotReloadTest`; сценарий — `debug-playground/Web/HotReload.cs`. Вживую не проверено
- [x] 0.1.25 — Go to Base для членов (`roslyn/RoslynBaseMembers`): цепочка `typeHierarchy/supertypes`, член того же вида и имени в файле базового типа по сканеру объявлений (перегрузки — по числу параметров); ближайший базовый класс, затем интерфейсы. Тест `RoslynBaseMembersTest`; сценарий — `debug-playground/Console/Editor/GoToBase.cs`. Вживую не проверено
### Этап 2 — среднее
- [ ] Reference assemblies net4x в индексаторе и Dependencies (`Microsoft.NETFramework.ReferenceAssemblies`, `Reference Assemblies\...\.NETFramework\v4.x`), сборки по `HintPath` (2) — в индексаторе сделано в 0.1.52 (`ProjectAssemblies.references`), в дереве Dependencies — нет
- [x] 0.1.35 — Publish (`publish/`): диалог как в Rider (Configuration, TFM, RID, self-contained, single-file, ReadyToRun, trim, папка, превью команды), `.pubxml` читается (и профили VS с `PublishUrl` → `-o`) и пишется (Save as Profile), контейнер `-t:PublishContainer` (SDK ≥ 8), вывод в окне Build, уведомление с папкой, свой тип run configuration «.NET Publish». Тест `PublishTest`, профили и чек-лист — `debug-playground` «Publish». Вживую в IDE не проверено (команды прогнаны руками на площадке)
- [x] 0.1.41 — MSBuild из Visual Studio / Build Tools (`build/VisualStudioToolset`, `vswhere`): Build / Rebuild / Clean и свои цели проектов старого формата и решений с ними — через `MSBuild.exe` (amd64) с `-restore -p:RestorePackagesConfig=true -m -v:m` (у одиночного проекта — `SolutionDir` его решения, как у VS: без него restore `packages.config` падает) (.NET SDK пропускает XAML WPF — сборка падает с CS5001 — и цели VS); «MSBuild version» в Toolset and Build (Auto / .NET SDK / установка VS; выбранная установка собирает всё); без VS — уведомление со ссылкой на Build Tools. `$(VSToolsPath)` найденной VS передаётся MsBuildHost при вычислении проектов старого формата. Restore и Publish остаются на SDK. Тест `VisualStudioToolsetTest`; площадка — `debug-playground/NetFramework` (сборка решения проверена UI-роботом). `-getProperty` legacy — через MsBuildHost, как и было
- [ ] Roslyn LS и non-SDK проекты: предупреждение, если VS / Build Tools не найдены; проверить загрузку вживую (1)
- [x] 0.1.37 — Find Usages с группировкой (`lang/CSharpUsageKinds`, `CSharpUsageGrouping`): платформенный LSP-адаптер всегда отвечает «типа нет» и до `UsageTypeProvider` дело не доходит, поэтому — свои правила группировки на переключателях платформы (Usage Type / Module / File Structure). Вид — эвристикой по токенам, сверено с `_vs_kind` ответа Roslyn для Visual Studio (клиенту IntelliJ он недоступен) и `documentHighlight`. Тест `CSharpFindUsagesTest`, фикстура `roslyn/capture-5.12-references`, сценарий — `debug-playground/Console/Editor/FindUsages.cs`. Вживую окно Usages не проверено
### Этап 3 — отладка .NET Framework (под доработанный адаптер)
Договориться с адаптером: как он узнаёт desktop CLR (аргумент `launch` или по exe), x64 / x86 — два бинарника или один, какие `capabilities` объявляет.
- [x] 0.1.42 — Run проекта старого формата: `TargetPath` (MsBuildHost) запускается напрямую, `run/ExecutableLaunch` — папка вывода как рабочая, аргументы и окружение конфигурации, вывод консоли в OEM-кодировке Windows (`GetOEMCP`; чего в ней нет — `?` уже в программе, как в `cmd`), Stop у `WinExe` (подсистема PE = 2) — сразу жёсткий (мягкий — Ctrl+C, окну он не доходит). Путь из окружения Before launch до Run-state не доходит — берётся у MsBuildHost заново (~10 мс). SDK-проекты `net48` — по-прежнему `dotnet run`. Тест `ExecutableLaunchTest`; проверено UI-роботом на `debug-playground/NetFramework`
- [x] 0.1.69 — Debug проекта старого формата: адаптер `dotnet-debugger` 0.2.0 запускает `TargetPath` (`program`), рабочая папка — папка вывода (`DotNetLaunchArguments.startInOutputFolder`), как у Run; точки останова, шаги, переменные, Evaluate, консоль (кириллица под отладчиком цела — консоль переключает адаптер). Сборка перед Debug — одна: путь из «Build .NET Project» передаётся по `executionId` (`BuiltBeforeLaunch`; user data окружения до runner'а не доходит — каждый Debug, и не только legacy, собирал проект дважды). Тест `DebugLaunchTest`; проверено UI-роботом на `debug-playground/NetFramework` (`BP:legacy-console`, `BP:legacy-wpf-click`). «.NET Executable» для чужого exe — не сделано
- [x] 0.1.69 — 32 бита: адаптер отлаживает только 64-битные процессы, а MSBuild делает AnyCPU-программу net4.5+ «Prefer 32-bit» по умолчанию. Плагин читает PE-заголовок (`PortableExecutable.thirtyTwoBit`: PE32+, флаги CLI `32BITREQUIRED` / `32BITPREFERRED`, нативный PE32) и до запуска адаптера отказывает уведомлением «Cannot Debug a 32-bit Process» с тем, что поменять в проекте (`DebugBitness`); attach к WOW64-процессу — так же. Второго адаптера под x86 нет — выбирать не из чего. Площадка — `Prefer32Bit` = false в обоих проектах. Тест `DebugLaunchTest`; проверено UI-роботом
- [x] 0.1.69 — Attach к net4x без ключа реестра (на Windows); путь exe — из `ProcessHandle`, когда платформа его не дала (на Windows не даёт никогда: раньше net4x-программы находил только помощник диагностики, и не с первого открытия списка). Отладка тестов SDK-проекта `net481` (VSTest, `testhost` 64-битный) работает тем же attach — проверено UI-роботом. Тесты legacy-проектов — п. 1 `NET_FRAMEWORK_PLAN.md`
- [x] 0.1.69 — Площадка `debug-playground/NetFramework` со сценариями `// BP:` (проверено UI-роботом)
### Этап 4 — крупное
- [x] 0.1.36 — результаты тестов по ходу прогона (VSTest: xUnit, NUnit, MSTest): свой логгер + сборщик данных (`testlogger/`, netstandard2.0, ObjectModel 17.12 только для компиляции, собирается у пользователя один раз), события `*.jsonl` в каталоге из `DOTNET_SUPPORT_TEST_EVENTS`, плагин читает каждые 200 мс (`testing/LiveTestEvents.kt`), дерево по id, в конце сверка с TRX. Начало теста — сразу, результат — с задержкой до ~1 с (VSTest отдаёт логгерам пачками). Тест `LiveResultsTest`, сценарий — `debug-playground/Tests/LiveResultsTests.cs` (маркеры `LIVE:`). Вживую в IDE не проверено
- [ ] Живые результаты для Microsoft Testing Platform (MSTest runner, xunit.v3, TUnit): json-rpc `--server` / `--dotnet-test-pipe`, как у VS; сейчас — TRX в конце
- [ ] Legacy-модель проекта: явный `<Compile Include>` в дереве, «не в проекте» серым, новые файлы в `.csproj`, тест-проекты на `packages.config` (4–5)
- [ ] IIS Express: профили `IISExpress` в `launchSettings`, `iisexpress.exe` + `applicationhost.config`, attach отладчика (3–5)
- [ ] Hot Reload при отладке — после адаптера
- [ ] Razor / Blazor — ждёт решения: исключён 2026-09-30, в последних коммитах «работа в сторону razor»

### Помощники на .NET, работающие постоянно (план 2026-10-03)
Библиотеки, которых нет в JVM, через помощников, как `indexer`: исходником в плагине, сборка на машине пользователя, процесс держится
и отвечает на запросы. Соглашение и протокол — `helpers/README.md`. Три помощника, а не один: MSBuild конфликтует с клиентом NuGet,
для .NET Framework нужен процесс на net472, подключение к чужим процессам изолируется.
- [x] Основа: протокол `helpers/protocol/Protocol.cs` (JSON построчно, параллельные запросы, отмена, журнал), клиент `cli/HelperConnection`
  (ленивый старт, перезапуск, таймауты), `DotNetHelper` с несколькими исходниками, упаковка `helpers/*` в сборке. Тест `HelperConnectionTest`
- [x] 0.1.28 — MsBuildHost (`helpers/msbuildhost`, Microsoft.Build 17.11 из SDK через MSBuildLocator): `evaluate` / `invalidate` / `info`,
  вычисления переиспользуются до изменения проекта или импортов (12–48 мс против ~0,8 с у `dotnet msbuild -getProperty`). `TargetPath` для
  Debug — сначала помощник, затем CLI; legacy-проекты в Solution view — по вычисленным items (фоном, дерево обновляется по ответу).
  MSBuild из SDK вычисляет legacy-проекты, кроме импортов Visual Studio (`$(VSToolsPath)` → WebApplication targets): такой проект
  вычисляется с `IgnoreMissingImports` и предупреждением. Плагин — `msbuild/MsBuildEvaluation`, тест `MsBuildEvaluationTest`; чек-лист —
  `debug-playground/README.md` «MSBuild-вычисление проектов». Вживую в IDE не проверено, SDK 8 не проверен
- [x] 0.1.41 — Импорты Visual Studio в legacy-проектах (`VSToolsPath` из `vswhere`, см. этап 2; цели WebApplications вживую не проверены — у Build Tools на машине разработки нет web-workload) — без отдельного хоста на net472 (решение 2026-10-03: для .NET Framework всё равно нужен
  SDK ≥ 10 ради Roslyn, все помощники — dll на нём). Вычислению нужны только `.targets` VS, задачи не выполняются: найти VS / Build Tools
  через `vswhere` и передать `VSToolsPath=<VS>\MSBuild\Microsoft\VisualStudio\v17.0|v18.0` глобальным свойством; без VS — как сейчас,
  `IgnoreMissingImports` с предупреждением. Проверить цели WPF / WinForms (`Microsoft.WinFX.targets`)
- [x] 0.1.27 — DotNetHelper (`helpers/dotnethelper`, NuGet.Protocol / NuGet.Credentials 6.14): поиск и версии, когда HTTP-клиент IDE не
  доходит до фида (`NuGetNetwork.isRouteFailure`: прокси, сертификаты, неизвестный хост, 401 / 403 / 407; такой фид 10 минут идёт сразу
  через помощник), фиды V2 и локальные папки, учётные данные nuget.config и credential providers; restore `packages.config` в папку
  `packages` solution (`repositoryPath`). Плагин — `nuget/NuGetHelper`, тест `NuGetHelperTest` на ответах настоящего прогона. **За
  корпоративным прокси не проверено**; у пакетов, найденных только помощником, в карточке нет данных nuspec
- [ ] DotNetHelper, дальше: тесты через TestPlatform (живые результаты, поиск по метаданным, net4x), ~~декомпилятор (ICSharpCode.Decompiler)~~ (сделан в 0.1.63),
  CorFlags сборок
- [x] 0.1.29 — DiagnosticsHelper (`helpers/diagnostics`, ClrMD 3.1): `runtimes` — CLR процесса по модулям (Toolhelp-снимок с
  `TH32CS_SNAPMODULE32`, видит 32-битные процессы; 435 процессов за 319 мс), attach к хостам с desktop CLR под ключом реестра;
  `retained` / `dominators` / `close` — дерево доминаторов (Cooper–Harvey–Kennedy без рекурсии, ~100 байт на объект; дамп `leak` — 1 с,
  9 млн объектов — 29 с, 920 МБ), колонка Retained и вкладка Dominators в Memory Dump. Плагин — `cli/DiagnosticsHelper`, тест
  `DiagnosticsHelperTest`. Вживую в IDE не проверено
- [ ] DiagnosticsHelper, дальше: TraceEvent (flame graph из `.nettrace`), перенос `allocwatch`, сравнение двух дампов
- [ ] Перенос `indexer` / `allocwatch` в постоянные помощники — только если это даст выигрыш (сейчас они разовые и работают)

### Aspire (план 2026-10-03)

Сейчас есть только категория в New Solution и `Aspire.AppHost.Sdk` в подсказках `.csproj`; AppHost запускается как обычный проект.
- [x] 0.1.38 — шаг 1 (проверен вживую UI-роботом на Aspire 13.6: автоподключение к `Web`, остановка на точке, Stop чистит `dcp`; AppHost по `Sdk="Aspire.AppHost.Sdk/13.x"`, `<Sdk Name=…>` 9.x, `IsAspireHost` 8; DCP — не дочерний процесс AppHost, а `dcp start-apiserver --monitor <pid>`, сервисы под `dcp run-controllers`; пакет `aspire/`, тест `AspireTest`, площадка `debug-playground/AspireHost`). Было: AppHost распознаётся (`Aspire.AppHost.Sdk` / `IsAspireHost`): иконка, run configuration первой; ссылка
  `Login to the dashboard at …` в консоли кликабельна, «Open Dashboard» в строке Services; Debug AppHost автоматически подключает
  отладчик к .NET-процессам сервисов, которые запустил DCP (через существующий Attach)
- [ ] Шаг 2. Протокол IDE execution (спецификация в dotnet/aspire, сверить версию для Aspire 13): DCP просит IDE запустить проект, IDE
  запускает его сразу под отладчиком — точки останова с первой строки
- [ ] Шаг 3. Ресурсы AppHost в Services (resource service, gRPC — в C#-помощнике): состояние, адреса, логи ресурса, Start / Stop / Restart
- [ ] Дальше: метрики и трассировки OpenTelemetry, действия CLI `aspire` (`add`, `publish`, `deploy`)

### IL Viewer (план 2026-10-03)

- [x] 0.1.32 — окно IL Viewer, как в Rider: IL метода под курсором из последней сборки (DotNetHelper `il`, `helpers/dotnethelper/Il.cs`,
  `ICSharpCode.Decompiler` 11.1); тела выбираются по sequence points PDB (метод, `MoveNext` async/итератора, лямбды, локальные функции;
  portable и embedded PDB, путь `/_/` — по хвосту), без PDB и для полей / заголовков типов — по имени с warning; первый запрос к сборке
  0,3–0,7 с, дальше миллисекунды, сборка не блокируется. Подсветка C# ↔ IL в обе стороны, баннер о несвежей сборке, обновление после
  сборки (`DotNetBuildListener.TOPIC`). Контракт — `il/IlModel.kt`, тесты `IlHelperTest` / `IlViewerTest`, сценарий —
  `debug-playground/Console/Editor/IlViewer.cs` (маркеры `IL:`). Вживую в IDE не проверено; Windows PDB не читается
- [ ] Режим «IL + C#» (строки исходника комментариями перед своим IL)
- [ ] Декомпиляция типов библиотек в C# (тот же пакет)

### Схема конфигурации `appsettings*.json` из кода (план 2026-10-03)

Не только ASP.NET Core: любой проект, который читает конфигурацию через `Microsoft.Extensions.Configuration`, получает автодополнение и
проверку своих секций.
- [x] 0.1.33 — шаги 1–2 сделаны: content-модуль `io.github.dotnetsupport.jsonschema` (зависит от JSON-плагина), сервис
  `appsettings/AppSettingsSchemaService`, помощник `helpers/dotnethelper/AppSettings.cs` (Roslyn 5.9, только синтаксис; холодный ответ
  ~0,2 с, повторный 2–3 мс). Наш провайдер выключает SchemaStore для этих файлов (платформа применяет каталог только без провайдера),
  поэтому схема SchemaStore скачивается через HTTP-файловую систему IDE и встраивается в нашу; без сети — своя база
  `resources/appsettingsSchema/base.json`. Удалённые `$ref` внутри схемы в 2026.1 не разрешаются (ключ реестра
  `json.schema.object.v2.enable.nested.remote.schema.resolve`), в unit-тестах — разрешаются. Неизвестный ключ — своя инспекция
  `AppSettingsUnknownKey`. Тест `AppSettingsSchemaTest`, сценарий `debug-playground/Console/Editor/AppSettingsSchema.cs`. Пакеты с
  `ConfigurationSchema.json` вживую не проверены; типы из библиотек — открытый объект
- [x] Шаг 1. Провайдер JSON-схемы для `appsettings*.json` (и `appsettings.<Environment>.json`): база ASP.NET Core (Logging, Kestrel,
  ConnectionStrings, AllowedHosts; не спорить со схемой SchemaStore, если платформа её уже применяет) + `ConfigurationSchema.json` из
  NuGet-пакетов проекта (Aspire и др., пакеты — из `project.assets.json`)
- [x] Шаг 2. Секции из кода — метод DotNetHelper на синтаксисе Roslyn (без компиляции): `Configure<T>(GetSection("X"))`,
  `AddOptions<T>().Bind(...)` / `.BindConfiguration("X")`, `GetSection("X").Get<T>()` / `.Bind(obj)`, `GetValue<T>("A:B")`,
  `config["A:B"]`; имя секции из константы (`GetSection(PositionOptions.Position)`); пометка `// appsettings: Section:Sub` над классом
  для того, что по коду не найти. Типы: свойства с сеттером → ключи, примитивы, enum → значения, `TimeSpan`, коллекции, словари →
  `additionalProperties`, вложенные классы, инициализатор → `default`, XML-doc → описание. Пересчёт при правке `.cs`. Неизвестный ключ —
  слабое предупреждение (конфигурация приходит и из окружения), неверный тип — предупреждение
- [ ] Шаг 3. Навигация ключ JSON ↔ свойство C# (Ctrl+Click, значок у класса), DataAnnotations (`[Required]`, `[Range]`,
  `[RegularExpression]`) → ограничения, типы из библиотек — через индекс сборок, те же ключи в `secrets.json` и `Section__Key` в
  переменных окружения `launchSettings.json`

## Свой PSI C# вместо roslyn-language-server (план 2026-10-04)

Решение пользователя: уйти от сервера к своему PSI, как в `idea-golang-support`. Причины: ограничения LSP, скорость, требование
.NET 10, память. План по шагам, гейты и вехи — **`CSHARP_PSI_MIGRATION.md`**; статус шагов ведётся там, сюда — итоги по версиям.
- [x] 0.1.40 — шаги 1–2: модули `csharp-psi-core` / `-semantic` / `-ide` (пустые, `pluginComposedModule`), переключатели фич
  `ROSLYN | NATIVE` (`lang/CSharpFeatures`, чтение в модуле `roslyn`); на странице Language Server строки появятся с первой нативной фичей
- [x] Шаги 3–6 (в `../csharp-psi`, 2026-10-04): генератор PSI из `Syntax.xml`, лексер, перенос парсера Roslyn (MIT), корпусная сверка
  дерева с Roslyn (0 расхождений)
- [x] 0.1.45 — шаг 7: код csharp-psi перенесён в плагин целиком (`csharp-psi-core`, `tools/csharp-psi`, `docs/csharp-psi`); `.cs` разбирает
  парсер Roslyn-порта, Structure / folding / breadcrumbs / Go to Class / IL Viewer / ▶ тестов и остальные потребители `CSharpSyntaxModel` — на
  его PSI; символы `#if` и `LangVersion` файла — из `CompilationModel` (смена TFM в тулбаре перепарсивает). Переключатель «Structure, folding
  and breadcrumbs» на странице Language Server, **по умолчанию Built-in**; эвристики — по выбору Language server. Сценарий —
  `debug-playground/MultiTarget/ActiveBranch.cs` (`TYPE:active-branch`), проверка роботом — `tools/ui-robot/scripts/syntax_tree.js`
- [ ] Шаги 8–9: stub-индексы, синтаксические фичи на PSI
- [x] 0.1.47 — шаг 8: stub-индексы встроенного дерева (`csharp-psi-core` `psi/stubs`): Go to Class / Symbol без разбора файлов, индексы
  extension-методов и атрибутов тестов (`[Fact]`, `[Test]`) на будущее; `CSharpDeclarationIndex` — только для эвристического дерева.
  Замер: весь `dotnet/runtime` ≈ 35 с в один поток. Вживую не проверено (`docs/LIVE_CHECKS.md`)
- [x] 0.1.50 — шаг 9, `NAVIGATION` («Navigation and usages», встроенная по умолчанию с 0.1.60), синтаксическая часть: Go to
  Declaration, Ctrl+наведение и подсветка использований под кареткой по дереву (`lang/NativeCSharpNavigation`) — локальные, параметры
  (в т. ч. лямбд, локальных функций, первичных конструкторов), метки, переменные запросов, параметры типов; члены своего типа и его
  partial-частей (перегрузки — списком), типы solution по имени с учётом `using`. Чего дерево не знает (`a.B`, базовые члены, сборки) —
  по-прежнему сервер; Go to Super работает при любом переключателе. Сценарий — `debug-playground/Console/Editor/Navigation.cs`
  (`TYPE:nav-*`); робот — `goto_declaration.js`. Роботом и вживую не проверено
- [x] 0.1.49 — шаг 9, `FORMATTING` («Formatting», по умолчанию Built-in — робот 2026-10-04: метод, `switch`, инициализаторы и весь файл площадки совпали с сервером): встроенный форматтер — правила пробелов
  `dotnet format whitespace` (отступы, Allman, пробелы, `case`, пустые строки, `indent_*` / `csharp_*` из `.editorconfig`) на модели
  форматтера платформы (`lang/NativeCSharpFormatter`): Reformat Code / Selection, Auto-Indent Lines. Отвечает вместо сервера и `dotnet format`;
  CSharpier и None — как были. Оракул — `./gradlew formatOracle` (`tools/csharp-psi/format-oracle.sh`). Сценарий —
  `debug-playground/Console/Editor/Formatting.cs` (`TYPE:format-*`); робот — `reformat.js`. Роботом и вживую не проверено.
  Выбор в Toolset and Build → «Formatter» (по просьбе пользователя 2026-10-04): «Built-in» — на лету, «dotnet format (on save)» —
  только файлы целиком, по Reformat Code и при сохранении; «Auto» — по переключателю «Formatting» (`TYPE:format-choice`)
- [x] 0.1.68 — встроенный форматтер раскладывает инициализаторы и аргументы как Rider (просьба пользователя 2026-10-05; там, где
  `dotnet format` оставляет их как есть): многострочные инициализаторы объектов, коллекций, массивов, анонимных типов, `with` и
  `[...]` — `{` / `[` на своей строке (кроме начала аргумента: `Sum([`), элементы на отступ глубже, `}` на своей строке; переносы
  внутри сохраняются (`1, 2,` вместе), но если элемент сам многострочный — каждый элемент на своей строке; однострочные — `{ 1, 2 }`,
  `[1, 2]`. Аргументы вызовов / `new` / индексаторов / атрибутов и параметры — на отступ правее строки, где стоит вызов (Rider не
  выравнивает по первому аргументу), одинокая `)` — под ней; вложенные списки — от элементов внешнего списка, если тот разложен по
  строкам; в скобках `if` / `while` / `foreach` / `using` — от первого токена в скобках. Умолчания Rider сняты `jb cleanupcode`
  (ReSharper 2026.2) и записаны в KDoc `lang/NativeCSharpFormatter` (раздел «Rider's lists»); эталоны — `src/test/resources/formatting/rider`.
  Оракул (`format-oracle.sh`) сравнивает два стиля: `dotnet` (без списков Rider — ровно `dotnet format`) и `rider` (отличия списков
  Rider объявлены). Сценарий — `debug-playground/Console/Editor/Formatting.cs` (`TYPE:format-initializers`, `TYPE:format-arguments`),
  `docs/LIVE_CHECKS.md` E-85, E-86. Вживую не проверено
- [x] 0.1.72 — задача C4a миграции: «Documentation and parameter info» и «Context actions» по умолчанию Built-in (робот WSL на IC по
  E-76…E-84 в обоих режимах). Quick doc: `<inheritdoc/>` (база, интерфейс, `cref`, члены сборок), `cref` ссылками (`DocumentationLinkHandler`,
  F4 — исходник или metadata view), тип на `var`, `string?` из индекса; hover сервера при Built-in выключен. Parameter Info: конструктор по
  аргументам, именованные аргументы. Completion без `Void` / `Finalize` / статиков System.Enum. Inline variable на `var`. Сценарии —
  `debug-playground/Console/Editor/MemberCompletion.cs` (`TYPE:quick-doc-inherit`, `-cref`, `-var`, `TYPE:parameter-info-named`),
  `ContextActions.cs`; `docs/LIVE_CHECKS.md` E-99…E-102
- [x] 0.1.71 — подсветка внутри строк как в Rider (`docs/rider-analysis/README.md`, §6 п. 2): у редактора свой лексер
  (`lang/CSharpHighlightingLexer`, только для `CSharpSyntaxHighlighter`; `CSharpLexer` по-прежнему отдаёт литерал одним токеном) режет
  строки на текст, escape-последовательности (верные — двумя чередующимися цветами `CSHARP_ESCAPE_CHARACTER_1` / `_2`, неверные —
  `CSHARP_INVALID_ESCAPE_CHARACTER`; `""` verbatim-строк, `{{` / `}}` интерполированных, char-литералы), дырки интерполяции (скобки — цвет
  скобок, код внутри — лексером C#, вложенные строки так же; raw-строки `$$"""` с многоскобочными дырками) и выравнивание / формат
  (`,5`, `:N2` — `CSHARP_FORMAT_STRING_ITEM`). Элементы `{0,5:N2}` у `string.Format` / `Console.Write(Line)` / `AppendFormat` /
  `Trace.TraceInformation` — аннотатор по имени метода (`lang/CSharpFormatItems`, DumbAware), соседние — `_2`. Кавычки (`CSharpQuoteHandler`)
  понимают разрезанный литерал. Цвета Islands Dark, страница Color Scheme | C# — группа «String». Сценарий —
  `debug-playground/Console/Editor/StringColors.cs` (`TYPE:strings-*`), `docs/LIVE_CHECKS.md` E-96…E-98. Проверено UI-роботом (ключи совпали с дампом Rider);
  человеком вживую не проверено. Не сделано: подсветка пары «элемент формата ↔ аргумент» под кареткой (`MATCHED_FORMAT_STRING_ITEM`),
  ошибки формата (`{x}` в `string.Format`), шаблоны логгеров `{Name}`, regex-инъекция
- [x] 0.1.74 — семантические ошибки без сервера (задача C4c `CSHARP_PSI_MIGRATION.md`; «Errors and warnings» = Built-in): свой резолвер
  (`lang/semantic/CSharpSemanticChecks`, `CSharpReachability`, `CSharpUnusedUsings`, в редакторе — `lang/NativeCSharpSemanticDiagnostics`)
  выдаёт CS0103 / CS0246 / CS0234, CS1061 / CS0117, CS1501 / CS7036, CS0029 / CS0266, CS0161 с кодами, текстами и местами Roslyn, серые
  `using` (CS8019, CS8933) и fixes «Import type» (синяя подсказка как в Rider, выбор namespace; extension-методы) и «Remove unused
  directives in file». Молчит, когда чего-то не знает: сборки проиндексированы не все, проект с генераторами (Razor, WPF, gRPC…),
  синтаксическая ошибка в члене, partial-тип, generic-метод. Ошибки, которые показывает плагин, сервер и последняя сборка не повторяют.
  Гейт по оракулу (`semanticGate`, roslyndump теперь пишет и скрытые CS8019 / CS8933): ложных — 0 на playground, System.Linq,
  System.Threading.Channels, Microsoft.Extensions.Primitives; на пробе ошибок — 69 из 69. Сценарии — `debug-playground/Broken/SemanticErrors.cs`
  (`TYPE:sem-*`), `debug-playground/Console/Editor/ImportType.cs` (`TYPE:import-type-*`); `docs/LIVE_CHECKS.md` E-109…E-114. Сборки
  проектов вне solution (как `Broken`) индексируются, когда открыт их файл. Проверено UI-роботом (Windows, IC 2026.1.4): ошибки как у
  сервера, fixes работают; синяя подсказка и дубли со сборкой не проверены, человеком вживую не проверено
- [x] 0.1.126 — `override` как в Rider: начало типа возврата (`public override str`) оставляет члены этого типа (`string Describe()`,
  `string ToString()`), `public override string D` дописывает Describe поверх набранного типа, `override string ` — члены этого типа;
  после `override` нет `struct`, `class` и шаблонов. С `public ov` в списке строки `override string Describe`, которые пишут член целиком,
  и серый текст лучшего члена (Tab — то же, что выбор строки, с `using` и кареткой в теле). Сценарии `Overrides.cs`:
  `TYPE:override-by-type`, `TYPE:override-early`, `TYPE:override-gray`. Вживую не проверено
- [x] 0.1.125 — окно Project надёжно открывается на Solution при первом открытии папки с solution: переключение гонялось с созданием
  содержимого окна (`ProjectViewImpl.setupImpl` позже стартовой активности) и при проигрыше не повторялось, а флаг «уже переключали»
  ставился заранее; теперь ждём `ProjectViewListener.paneShown`, флаг — только после переключения, Solution — `isDefaultPane` для папки
  с `.sln/.slnx` в корне; после первого переключения выбор пользователя сохраняется. Решение — чистая функция, `SolutionViewSwitchTest`.
  Сценарии линз (`CodeLens.cs`, `CodeLensTests.cs`): маркеры над объявлениями, чтобы линза при позиции Right не уезжала за комментарий.
  Вживую не проверено
- [x] 0.1.124 — линз Code Vision нет над исходниками библиотек (Source Link), декомпилированным кодом и metadata view — только над файлами
  solution, как в Rider (inlay hints там остаются). Общие фреймворки, которые требуют пакеты (`Grpc.AspNetCore` → `Microsoft.AspNetCore.App`,
  `targets.<tfm>.<пакет>.frameworkReferences` в `project.assets.json`), теперь среди сборок проекта: `Host` в Grpc-проекте разрешается
  (completion, Go to Declaration, нет ложной CS0246). Узел Dependencies → Frameworks по-прежнему показывает только объявленные проектом.
  Тесты `CSharpCodeLensTest`, `ProjectReferencesTest`. Вживую не проверено
- [x] 0.1.123 — по второй проверке роботом (WSL, 2026-10-06): Source Link снова скачивает (запрет редиректов через `redirectLimit(0)` ронял
  каждую загрузку; теперь `followRedirects(false)`, тест с настоящим HTTP-клиентом на локальном сервере). Code Vision: у каждого объявления
  строки своя запись (`int _a, _b;`, однострочный enum и его члены) — провайдер теперь прямой `CodeVisionProvider` с
  `singleEntryPerLine = false`; счётчики членов ниже правки не сбрасываются; `base(...)` — использование конструктора, не типа; клик по
  «N implementations» и gutter переопределений без ошибки «Read access is allowed from inside read-action only». Прошло роботом:
  области анализа (97 файлов за 3,9 с, набор без задержек), исправления 0.1.120, «Run» над тестом. Исправления 0.1.123 проверены роботом (WSL): Source Link открывает исходник с GitHub и падает на декомпилят при выключенной опции; все линзы `CodeLens.cs` как в EXPECT, линзы обновляются после правки другого файла (~6 с), попап реализаций без ошибок. Не разрешается `Host` в Grpc-проекте (до Source Link не доходит); человеком вживую не проверено
- [x] 0.1.122 — области «Analysis» без сервера (Compiler / Analyzer diagnostics for: openFiles / fullSolution / none): `fullSolution` —
  фоновый проход по всем `.cs` solution в Problems → Project Errors (`lang/NativeCSharpSolutionProblems`, ~9 мс на файл, 196 файлов площадки
  за 1,7 с), перепроверка изменённого файла через 1 с и всего — после сборки; `none` — без диагностик компилятора вовсе, как у Roslyn;
  анализаторы при `fullSolution` — по проекту целиком в ту же вкладку, при `none` — только по Run Code Analysis. Вкладку заполняет один
  провайдер: сервер при «Errors and warnings» = Language server, иначе плагин. Правка файла перепроверяет только его (зависимые — после
  сборки). Теперь все 35 опций страницы Language Server работают и без сервера (`RoslynOptionsNativeTest`: PENDING пуст). Сценарий —
  `Console/Editor/SolutionProblems.cs` (`TYPE:problems-*`), тест `CSharpSolutionProblemsTest`. Вживую не проверено
- [x] 0.1.121 — переход в исходники библиотеки по Source Link и встроенным в PDB исходникам без сервера (опция «Navigate to Source Link and
  embedded sources»): помощник читает portable PDB (`helpers/dotnethelper/SourceLink.cs`, метод `sourceLocation`), плагин скачивает файл
  HTTP-клиентом IDE (только https, не локальная сеть и не сама машина, без редиректов, до 16 МБ), сверяет хеш из PDB, кэширует на диске и
  открывает read-only (`dotnet-source://`, пакет `sourcelink`); без PDB / Source Link / сети — декомпилированный код или метаданные. Работает
  для пакетов с PDB рядом с dll (Grpc.*, Microsoft.CodeAnalysis.*…); большинство пакетов кладут PDB только в `.snupkg` — символьные серверы не
  сделаны. Сценарий — `debug-playground/Grpc/SourceLink.cs` (`TYPE:sourcelink-*`), тест `SourceLinkTest`. Вживую не проверено
- [x] 0.1.120 — по проверке роботом 0.1.116–0.1.118 (WSL, 2026-10-06; скобки, inlay hints, в том числе на EF Core `IOrderedQueryable<Order>`,
  и 5 из 13 сценариев опций прошли): Apply страницы Language Server сразу перерисовывает inlay hints и Code Vision открытых файлов (раньше —
  только после правки); переключение «Inlay hints» / «Code Vision» на сервер работает без его перезапуска; нет ложной CS7036 у именованного
  аргумента на своей позиции перед позиционными (C# 7.2). Вживую исправления не проверены
- [x] 0.1.119 — Code Vision без сервера: «N usages» над типами и членами (счёт — встроенный Find Usages, клик — Show Usages),
  «N implementations / overrides / inheritors», «Run | Debug» над тестами и классами тестов (те же конфигурации, что ▶ в gutter); опции
  Code Lens «References» и «Run and debug tests» страницы Language Server действуют и для встроенной реализации; переключатель «Code Vision»
  (Built-in по умолчанию), с сервером — линзы сервера вместо встроенных. Перегрузки считаются вместе (встроенный Find Usages не различает
  их по аргументам). Сценарии — `Console/Editor/CodeLens.cs` (`TYPE:lens-*`), `Tests/CodeLensTests.cs`, тест `CSharpCodeLensTest`. Вживую не проверено
- [x] 0.1.118 — опции страницы Language Server действуют и для встроенных фич: completion (неимпортированные namespace, имена, regex,
  список в аргументах), навигация в декомпилированный код, remarks в документации, поиск символов в сборках, автовставка `///`, подсветка
  regex / JSON в строках, место вставки сгенерированных членов, бросающие или авто-свойства (по умолчанию, как у Roslyn, — бросающие).
  Новое: «Organize 'using' directives when formatting» у встроенного форматтера. У каждой опции — `NativeSupport` (READ / SERVER_ONLY /
  PENDING), guard-тест `RoslynOptionsNativeTest`; остаток PENDING — Code Lens, области анализа, Source Link. Сценарий —
  `debug-playground/Console/Editor/ServerOptions.cs` (`TYPE:option-*`), тест `NativeServerOptionsTest`. Вживую не проверено
- [x] 0.1.117 — inlay hints без сервера: имена параметров и типы `var` / параметров лямбд / `new()` / коллекций по собственной семантике, правила
  Roslyn, все 13 опций группы «Inlay Hints» страницы Language Server, переключатель «Inlay hints» (Built-in по умолчанию; при Language server
  встроенные молчат), клик по типу ведёт к объявлению или в metadata view. Причина: подсказки давал только сервер, с 0.1.76 выключенный по
  умолчанию (сообщение пользователя 2026-10-06). Сценарий — `debug-playground/Console/Editor/InlayHints.cs` (`TYPE:inlay-*`), тест
  `CSharpInlayHintsTest`. Вживую не проверено
- [x] 0.1.116 — цветные парные скобки в C# (`()`, `[]`, `{}`, `<>` списков типов) по глубине вложенности, как в VS Code / Rider; настройка
  «Colorize matching brackets» на Settings | .NET (включена по умолчанию), цвета — Editor | Color Scheme | C#. Сценарий —
  `debug-playground/Console/Editor/BracketColors.cs` (`TYPE:brackets-*`), тест `CSharpBracketColorsTest`. Вживую не проверено
- [x] 0.1.108–0.1.115 — ошибки компилятора без сервера, 94 новых кода по группам: обобщённые параметры (0.1.108), повторные объявления (0.1.109),
  неприсвоенные переменные (0.1.110), доступ и только для чтения, индекс формата 4 (0.1.111), операторы и приведения (0.1.112), перегрузки и лямбды
  (0.1.113), наследование (0.1.114), return / await / switch / переходы (0.1.115). Файл на каждый код — `debug-playground/Broken/Errors/CSxxxx.cs`
  (строки `// ERROR CSxxxx`), сверка — `tools/diag/check_errors.py roslyn | ide [--source roslyn]`, тест — `BrokenErrorFilesTest`.
- [x] 0.1.107 — присваивание без `=`: после `Console.BackgroundColor ` список сам предлагает `= ConsoleColor.Black` / `= dto.Name`, Enter пишет с `;`;
  сценарий — `debug-playground/ShopApi/Playground/EnumCompletion.cs` (`TYPE:enum-assign-no-equals`).
  Там же: обобщённые методы расширения проверяют `where` своего `this T` — у `DayOfWeek` больше нет `AddEndpointFilter`, вызов — CS1061 (`TYPE:enum-no-foreign-extension`).
- [x] 0.1.106 — enum: список открывается сам после `= ` / `return `, выбор значения в конце строки закрывает оператор (`;`, `)`);
  сценарий — `debug-playground/Console/Editor/ExpectedTypeCompletion.cs` (`TYPE:expected-enum-assign`).
- [x] 0.1.105 — навигация внутри декомпилированного кода: его имена разрешаются по сборкам проекта, ссылающегося на dll;
  сценарий — `debug-playground/Console/Editor/DecompiledNavigation.cs` (`TYPE:decompiled-ctrl-click`).
- [x] 0.1.104 — серый текст не предлагает имя после члена типа (`JsonSerializer.Serialize ` → было `serialize`); сценарий —
  `GhostAssignments.cs` (`TYPE:ghost-member-of-type`).
- [x] 0.1.103 — серый текст и список при присваивании члену: `member.Ad` + выбранный `Admin` → `min = isAdmin;` (а не имя, как после типа);
  `member.Email = ` → `dto.Email;`; строки `dto.Name` / `dto.Email` в списке у `Name = ` и `member.Name = `. Сценарий —
  `debug-playground/ShopApi/Playground/GhostAssignments.cs` (`TYPE:ghost-assign-*`).
- [x] 0.1.102 — серый текст при открытом списке completion и строка `Member { … }`. Серое идёт за выбранной строкой, как у Full Line
  completion платформы: на `InlineCompletionEvent.LookupChange` провайдер 0.1.101 (`NativeCSharpTypingGhostProvider`, `restartOn` на
  событиях списка) показывает остаток строки и то, что дадут правила после неё: `new ` + тип → `Member();` (тип без аргументов
  конструктора и без `required`, `;` — если на нём кончается оператор), тип там, где дальше имя (`Draft(Mem` → `berDto memberDto`),
  значение `Name = `, если выбранная строка его начинает (`NativeCSharpTypingGhost.itemPlace` / `afterItem`). Tab — вставка серого
  (платформенный `InlineCompletionActionsPromoter` ставит её перед Tab списка и закрывает список), Enter — строка списка. Строка
  `Member { … }` под `Member` после `new` (`NativeCSharpObjectInitializers.initializerRow`, у выбора — инициализатор живым шаблоном:
  член на строку, `required` первыми, значения по правилу 0.1.101 выделены, Tab по значениям); показывается для типов solution, чьё имя
  набрано (от двух букв, не больше 8 строк, проверка по объявлениям), для типа, названного переменной, и для ожидаемого типа. Тип с
  `required` (0.1.98) пишет свой инициализатор тем же шаблоном со значениями. В выгруженном в репозиторий дампе Rider строки `{ … }` нет
  (только `Order()`), вид строки — наш. Сценарий — `debug-playground/ShopApi/Playground/GhostSuggestions.cs` (`TYPE:ghost-list-new`,
  `TYPE:ghost-list-name`, `TYPE:new-initializer-row`); `docs/LIVE_CHECKS.md` E-350…E-353. Вживую не проверено
- [x] 0.1.101 — серый текст при наборе по правилам, без ML (`lang/NativeCSharpTypingGhost.kt`, свой inline-провайдер перед провайдером
  аргументов; место — по тексту на EDT, семантика — в фоне на закоммиченном дереве): `var user = new ` → `User();` по имени переменной
  (`users` → `List<User>();`, только тип без аргументов конструктора и без `required`); `new User(|)` → `;` за `)` (skip-элемент
  платформы); `;` после `}` многострочного инициализатора; пустая строка `new User() { }` — члены по строке (`required` первыми) со
  значениями под рукой; `Name = ` в инициализаторе — переменная / параметр / член того же имени (без учёта регистра) и подходящего типа или
  его свойство до двух уровней (`userDto.Name`), правило значения со `;` там молчит; имя после типа (`UserDto ` → `userDto`, коллекция —
  множественное, интерфейс без `I`, поле — `_`, `foreach`). Completion: имя файла после `class `/`record`/… с телом-шаблоном
  (`NativeCSharpTypeNameCompletion`), `class` один раз — live template с именем ключевого слова убран из списка рядом с ним
  (`CSharpTemplateKeywordDedupe`). Сценарий — `debug-playground/ShopApi/Playground/GhostSuggestions.cs` (`TYPE:ghost-*`);
  `docs/LIVE_CHECKS.md` E-340…E-346. Вживую не проверено
- [x] 0.1.100 — находки редактора из сквозного прохода (`docs/DEV_JOURNEY.md`, этап 4): quick fixes «Create class / record / struct /
  interface / enum / field / property / method / local variable / parameter» на неразрешённом имени (`lang/NativeCSharpCreateFromUsage.kt`;
  тип — новым файлом рядом, в том же namespace; типы членов и параметров — по месту использования); Implement missing members сохраняет
  `= default` и атрибуты параметров; CS7036 до сборки для метода класса без значения по умолчанию интерфейса и для `new T(…)`, CS1729;
  Reformat Code раскладывает код, набранный в одну строку, как Rider (`riderBlocks` в `NativeCSharpFormatter`, уступает
  `csharp_preserve_single_line_blocks`); `;` внутри `()` в конце оператора уходит за скобки, `)` после строки перепечатывает парную
  (`CSharpParentheses`); отступ после однострочного `enum` (правило движка + примеры `csharpIndent/rules.json`); postfix не после имени
  типа; каретка в `()` у шаблона record; `,` перед запятой completion в appsettings*.json. Сценарий —
  `debug-playground/Console/Editor/JourneyFixes.cs` (`TYPE:journey-*`); `docs/LIVE_CHECKS.md` E-330…E-337. Вживую не проверено
- [x] 0.1.99 — находки сквозной проверки (`docs/DEV_JOURNEY.md` 1.1, 2.1–2.3, 3.1, 3.2, 5.2): New Solution на SDK 10 —
  каркасы шаблона из блока `Type: choice` у `--framework` (`TemplateOptions.frameworks`, фикстуры help SDK 9 и 10 в
  `src/test/resources/dotnetNew/sdk9|sdk10`), непрочитанный help = «любой TFM»; NPE окна NuGet из «Manage NuGet Packages…» проекта
  (порядок полей `NuGetPanel`); хранилище паролей — только для фидов с сохранёнными учётными данными или после 401/403, ответ кэшируется,
  nuget.org никогда (`NuGetCredentialPolicy`); «was not started» в уведомлении о сборке перед запуском; «Move to Solution Folder…» и
  перетаскивание проектов на папку / solution (`actions/MoveToSolutionFolder.kt`, `SolutionEditor.moveProject`, свой drop target
  `SolutionViewPane`); Add → New Project — окно New Solution (`NewSolutionDialog.AddProjectTarget`), опции шаблона по условиям
  (`TemplateOptionConditions`: `Enabled if` и «use with … auth» у Web API); новый проект выделяется и раскрывается
  (`view/SolutionViewReveal.kt`). `docs/LIVE_CHECKS.md` E-320…E-326. Вживую не проверено
- [x] 0.1.98 — object initializers и `required`-члены C# 11, как в Rider (`lang/NativeCSharpObjectInitializers.kt`,
  `lang/semantic/CSharpRequiredMembers.kt`): `new OrderLine` из completion у типа с `required` пишет инициализатор с ними (по строке на
  член, каретка у первого значения) вместо `()`, у типа только с конструкторами с параметрами — `new T(|)`; CS9035 без сервера (типы
  solution и сборок по флагу индекса, `[SetsRequiredMembers]` снимает требование) с fix «Add initializer for required members»; в
  `new T { | }` — строки «Fill required members» / «Fill all members», член пишется как `Name = `; Alt+Enter «Initialize members» /
  «Initialize required members». Сценарий — `debug-playground/ShopApi/Playground/RequiredMembers.cs` (`TYPE:required-*`);
  `docs/LIVE_CHECKS.md` E-310…E-313. Вживую не проверено
- [x] 0.1.97 — палитры C# (`lang/palette`, данные — `resources/csharpPalettes/<id>.json` с источниками цветов): Rider, Visual Studio,
  VS Code, Nord, Dracula, One Dark / One Light, Solarized, GitHub — у каждой тёмный и светлый вариант, выбирается по фону схемы. Палитра
  пишет только ключи `CSHARP_*` (цвет, жирный / курсив, подчёркивание; фона нет) в редактируемую копию текущей схемы (`_@user_…`, как
  Settings | Color Scheme) и переписывается при смене схемы / темы; «Как в IDE» возвращает то, что было в схеме до палитры. Выбор —
  Settings | .NET («Палитра C#») и .NET → C# Color Palette… (живой просмотр, Esc — назад); при первом открытии `.cs` в схеме IDE без
  цветов C# — одно предложение. Схемы «Rider Dark» / «Rider Light» оставлены. Сценарий — `debug-playground/README.md`, «Палитры C#»
  (`ShopApi/Endpoints/OrderEndpoints.cs`); `docs/LIVE_CHECKS.md` E-300…E-303. Робот: снимки всех палитр на Dark и Light, фон не меняется
- [x] 0.1.96 — double completion (`docs/COMPLETION_GAPS.md` 3.12; `lang/NativeCSharpDoubleCompletion.kt`, два хука в `NativeCSharpCompletion`,
  `CSharpMemberLookup.entries(inaccessibleToo)`, `AssemblyIndexService.unreferenced`): второй Ctrl+Space после точки — недоступные члены
  (private / protected / internal чужих типов, protected библиотечных) серым с «(not accessible)», вставка как есть; в позиции типа — типы
  сборок пакетов и выходов проектов, на которые ссылаются другие проекты solution, а этот нет, с `using` и предложением добавить пакет /
  ссылку (уведомление, команда только по кнопке); второй Ctrl+Shift+Space — цепочки `order.Customer` по ожидаемому типу на один доступ;
  строка-реклама списка на первом нажатии. Сценарии — `debug-playground/ShopApi/Playground/DoubleCompletion.cs` (`TYPE:shop-double-*`),
  `debug-playground/Console/Editor/DoubleCompletion.cs` (`TYPE:double-package`); `docs/LIVE_CHECKS.md` E-290…E-293. Вживую не проверено
- [x] 0.1.95 — completion: csproj и редкие места (`docs/COMPLETION_GAPS.md` 3.11, 3.13, 3.14). (1) MSBuild-файлы
  (`msbuild/MsBuildReferenceCompletion.kt`): `$(` — свойства файла, его явных `Import` и `Directory.Build.props` / `.targets` /
  `Directory.Packages.props` выше, известные MSBuild и схемы; `@(` — типы элементов; `%(` / `%(Item.` — метаданные; скобка закрывается;
  пути в `Import Project` (файлы MSBuild) и `ProjectReference Include` (csproj / fsproj / vbproj), `..\` и `$(MSBuildThisFileDirectory)`.
  (2) `lang/CSharpRareCompletion.kt`: `[assembly: InternalsVisibleTo("` → проекты solution; `extern alias ` → `Aliases` ссылок проекта;
  `delegate* unmanaged[` → Cdecl / Stdcall / Thiscall / Fastcall / SuppressGCTransition; `#:package ` файлового приложения → id пакетов
  (тот же `PackageCompletionService`, что у csproj), после `@` — версии; `#:` → package / sdk / property / project. (3) Настройка
  «Exclude from completion» (Settings | .NET, шаблоны `System.Data.*`, `lang/CSharpCompletionExclusions`): фильтр типов сборок и
  неимпортированных типов / extension-методов в `NativeCSharpImportCompletion`. (4) Live templates `hal`, `ua`, `rta`, `ctx` (ASP.NET
  Core без Razor); защита от чисел в `CSharpCaseInsensitiveCompletion` пропускает формат, начинающийся с цифры (`$"{x:0`). Тесты —
  `MsBuildReferenceCompletionTest`, `CSharpRareCompletionTest`, `CSharpLiveTemplatesTest`; сценарии —
  `debug-playground/Console/Editor/RareCompletion.cs` (`TYPE:rare-*`) и `Console/Console.csproj` (`TYPE:msbuild-*`); `docs/LIVE_CHECKS.md`
  E-280…E-286. Вживую не проверено
- [x] 0.1.94 — языковые места completion (`docs/COMPLETION_GAPS.md` 3.2, 3.3, 3.5, 3.6; `lang/NativeCSharpLanguageCompletion.kt`, явные
  реализации — `NativeCSharpInheritedMembers.explicitItems`): `void IFoo.|` / `int IFoo.|` / `IFoo.|` в начале члена — члены интерфейса, не
  реализованные явно, пишутся целиком с телом `throw new NotImplementedException();` (тип возврата заменяется верным); `void |` предлагает
  имена реализуемых интерфейсов (пишут `IFoo.` и открывают список); `[]` после точки у массива, строки, коллекций и типов с `this[...]`
  (`x.` → `x[|]`, `x?.` → `x?[|]`); имена элементов кортежа после точки и имена переменных деконструкции (`var (|, b) = pair`,
  `(var x, var |) = pair`, `foreach (var (a, |) in pairs)`) — из кортежа, `Deconstruct`, позиционного record; `partial class |` — partial-типы
  того же namespace с частью в другом файле. Не сделано: операторы и преобразования после точки, явная реализация событий. Сценарий —
  `debug-playground/Console/Editor/LanguageCompletion.cs` (`TYPE:explicit-*`, `indexer-*`, `tuple-names`, `deconstruct-*`, `partial-types`),
  `docs/LIVE_CHECKS.md` E-270…E-275; вживую не проверено
- [x] 0.1.93 — строки ASP.NET Core (`docs/COMPLETION_GAPS.md` 3.7–3.9). Шаблоны логгера (`lang/CSharpLoggerTemplates.kt`): `LogX(…)` / `Log(level, …)` /
  `BeginScope` у `ILogger`, Serilog `Log.Information(…)` (получатель с именем логгера), `[LoggerMessage(Message = …)]` — плейсхолдеры цветом
  format item, после `{` имена из аргументов (`order.Id` → `OrderId`, `Id`; аргумент этой позиции первым) или параметры метода
  `[LoggerMessage]`, Ctrl+Space в тексте — `{Name}` для свободного аргумента; предупреждение о несовпадении числа аргументов и плейсхолдеров
  (CA2017; повторное имя может брать один аргумент) и о плейсхолдере без параметра (SYSLIB1014). Маршруты (`lang/CSharpRouteTemplates.kt`):
  `[Route]`, `[HttpGet…]`, `MapGet/MapPost/…/MapGroup`, `MapControllerRoute(…, pattern)`, `[StringSyntax("Route")]`, `// lang=route` —
  цвета скобок, параметров и ограничений; после `{` параметры действия / обработчика (лямбда или метод того же типа; без сервисов,
  `CancellationToken`, `[FromBody]`…), после `:` ограничения маршрута, после `[` — `controller` / `action` / `area`. JSON (`CSharpJsonInjection.kt`,
  `META-INF/dotnet-json.xml`, optional depends на JSON-плагин): `// lang=json`, параметры `[StringSyntax(Json)]` решения, `JsonDocument.Parse`,
  `JsonSerializer.Deserialize`, Newtonsoft `JObject.Parse`…; `CSharpRegexPlaces` обобщён на любой синтаксис `[StringSyntax]`. Ключи конфигурации
  (`CSharpConfigurationKeys.kt`): индексатор, `GetSection` / `GetRequiredSection` / `GetValue<T>` / `GetConnectionString` — ключи
  `appsettings*.json` проекта через `:`, относительно секции. DI (`CSharpServiceRegistrations.kt`): `AddScoped/AddTransient/AddSingleton<I, `
  (и `TryAdd…`, `AddKeyed…`) — реализации из solution первыми (индекс супертипов, транзитивно, без abstract), с `using`. Список открывается сам
  после `{`, `:`, кавычки ключа и `AddScoped<I, ` (`CSharpAspNetStrings.kt`). Сценарий — `debug-playground/ShopApi/Playground/AspNetCompletion.cs`
  (`TYPE:shop-log-*`, `shop-route-*`, `shop-map-param`, `shop-json`, `shop-config-*`, `shop-di-impl`); `docs/LIVE_CHECKS.md` E-260…E-265.
  Вживую не проверено
- [x] 0.1.92 — доделки completion 0.1.85–0.1.91: метод, выбранный символом `.` / `;` (`Total().`, `Save();`, `Register(|);`) или
  вставленный сам как единственный пункт, получает скобки как по Enter, тип — `()` / `<>` (`NativeCSharpCalls`); тип и параметры
  extension-методов сборок — с аргументами типа, которые даёт получатель (`ImmutableArray<Order>`, `Func<Order, bool> predicate`, и в
  parameter info; `CSharpExpressionTypes.receiverTypeArguments`); место лямбды — по разрешённому типу параметра (делегат с любым именем,
  `delegate bool Rule(Order o)`), проверка по имени типа осталась запасной; в `catch (` первыми только наследники `Exception` (платформа
  поднимала короткое имя — enum `Ex` — к `Exception`; свой вес перед `liftShorter`). Сценарий —
  `debug-playground/Console/Editor/CompletionInsertion.cs` (`TYPE:insertion-*`), `docs/LIVE_CHECKS.md` E-250…E-254. Вживую не проверено
- [x] 0.1.91 — поведение списка completion (`docs/COMPLETION_GAPS.md` 2.8–2.14, 3.4, 3.10; `lang/CSharpCompletionBehaviour.kt`,
  `CSharpKeywordRecommendations.kt`, `CSharpCompletionAutoPopup.kt`, `CSharpLookupDocumentation.kt`). Статистика выбора (`SuggestionStats`)
  теперь и в нативном списке — весом после приоритета вида, так что порядок групп Rider не меняется; символы выбора `.` `,` `;` пробел `=`
  `[` `)` `(` (`CharFilter`); режим подсказки у новых имён и параметров лямбд (список без выделения); список открывается сам после `#`,
  `<`, `(`/`,` у параметра-делегата, `== `, `case `, `[`; Quick Doc на пункте списка (имя пункта резолвится в копии файла); ключевые
  слова по рекомендерам Roslyn (`and`/`or`/`when`, `with`, `get`/`set`/`init`, `field`, `allows`, `extension`, `assembly:`/`module:`,
  `managed`/`unmanaged`); `nameof(` без ключевых слов, `typeof(` без `dynamic`; совпадение в середине от трёх букв. Сценарий —
  `debug-playground/Console/Editor/CompletionBehaviour.cs` (`TYPE:behaviour-*`), `docs/LIVE_CHECKS.md` E-241…E-248
- [x] 0.1.90 — completion внутри строк, директив и doc-комментариев (`docs/COMPLETION_GAPS.md` 2.3–2.7): дырки `$"{…}"` всех видов
  (обычные, `$@`, raw `$$"""`) — имена и члены после точки (работало, закреплено тестами); форматы после `{x:`, `{0:` у `string.Format` /
  `Console.WriteLine` / `AppendFormat`, в `ToString("…")` и `ParseExact` — по типу значения (числа, даты, `TimeSpan`, `Guid`, enum) с
  примерами, как в Rider (`lang/CSharpFormatSpecifierCompletion`); регулярные выражения — инъекция языка RegExp платформы в литералы
  `new Regex`, статических `Regex.*`, `[GeneratedRegex]`, `[RegularExpression]`, параметров решения с `[StringSyntax(Regex)]`, после
  `// lang=regex` (`lang/CSharpStringLiteralHost`, `CSharpRegexPlaces`, `CSharpRegexInjection`; строковые токены встроенного дерева — хосты
  инъекций); `#` → директивы, `#if` → символы `DefineConstants` и всех TFM, `#nullable`, `#pragma warning disable` → коды
  (`lang/CSharpPreprocessorCompletion`); `///` — теги с закрывающей частью, параметры без документации, `cref` (`lang/CSharpDocCommentCompletion`).
  Сценарии — `debug-playground/Console/Editor/StringCompletion.cs`, `DocCompletion.cs`; `docs/LIVE_CHECKS.md` E-235…E-240
- [x] 0.1.89 — postfix по типу выражения и шаблоны Rider (`COMPLETION_GAPS.md` 2.1, 2.2, 3.1). (1) `lang/semantic/CSharpPostfixFacts` спрашивает
  тип выражения перед ключом: `.await` — у task / awaitable, `.foreach` — у коллекций и строк, `.for` / `.forr` — у коллекций с `Count` /
  `Length` (`i < orders.Count`) и чисел, `.if` / `.else` / `.while` / `.not` — у `bool`, `.null` / `.notnull` — не у значимых типов без `?`,
  `.using` — у `IDisposable`, `.lock` — у ссылочных, `.throw` — у исключений; тип неизвестен — предлагается всё, как раньше. Имена — `CSharpExpressionNames`
  (`order.Total.var` → `orderTotal`, `total` в списке; `GetOrders()` → `orders`; элемент `orders` → `order`; занятое рядом имя — с цифрой) в
  рамке шаблона. (2) Новые postfix Rider с его описаниями: `.field`, `.prop` (член в типе — `CSharpPostfixMembers`), `.to`, `.arg`, `.sel`,
  `.parse` / `.tryparse` (список типов), `.inject` между членами (primary constructor или параметр конструктора + поле); statement-шаблоны
  между членами больше не предлагаются; `CSharpExpressions.before` понимает generic-вызовы (`new List<Order>()`). (3) Live templates — 69
  (было 33): `ctorf` / `ctorp`, `itli` / `itar` / `ritar`, `sfc`, `outv`, `asrt*`, `psvm`, `sim`, `~`, `indexer`, `iterator`, `iterindex`,
  `equals`, `Attribute`, `Exception`, `namespace`, `#if`, `#region`, `checked` / `unchecked` / `unsafe`, `pci` / `pcs` / `psr`, `ear`, `nguid`,
  `from` / `join`, `mbox`, `propdp` / `dependencyProperty` / `attachedProperty`; описания как в Rider; макросы `csharpSuggestVariableName`,
  `csharpSuggestElementName`, `csharpNewGuid`, `csharpConstructorParameters` / `Body` (`lang/CSharpTemplateMacros`). Не сделано: ASP.NET MVC
  `hal` / `ua` / `rta` и `ctx`. Сценарии — `debug-playground/Console/Editor/Postfix.cs` (`TYPE:postfix-*`, `TYPE:live-templates`), `docs/LIVE_CHECKS.md`
  E-230…E-234. Проверено UI-роботом (Windows, IC 2026.1.4, скрипт `tools/ui-robot/scripts/template_expand.js`); человеком вживую не проверено
- [x] 0.1.88 — completion по ожидаемому типу без сервера (`lang/NativeCSharpExpectedCompletion`, по дампам Rider 3, 7–14, 26, 38, 39, 42,
  46): инициализаторы объектов / коллекций / `with` — только члены, которые ещё можно присвоить; property pattern (вложенный тоже) — члены
  проверяемого типа; члены ожидаемого enum строками `OrderStatus.Paid` первыми (`==`, `case`, ветка `switch`, `is`, аргумент,
  присваивание, `{ Status: `), список сам открывается после `== ` и `case `; строки `await Highlights` (метод становится `async`); `new` с
  целевым типом — сам тип первым, затем наследники / реализации, `throw new` — только исключения, `catch (` — исключения первыми, базовый
  список — классы и интерфейсы, `event` — делегаты, ограничение — классы и интерфейсы; smart completion (Ctrl+Shift+Space) — только
  подходящее по типу. Где тип неизвестен — список прежний. Тип цели — `CSharpExpressionTypes.target` и вход шаблона. Тест
  `CSharpExpectedTypeCompletionTest`; сценарий — `debug-playground/Console/Editor/ExpectedTypeCompletion.cs` (`TYPE:expected-*`),
  `docs/LIVE_CHECKS.md` E-220…E-226. Вживую человеком не проверено
- [x] 0.1.87 — import completion без сервера, как в Rider (`lang/NativeCSharpImportCompletion`, хуки в `NativeCSharpCompletion`): типы сборок
  в namespace, которые видит место (свои, `using`, неявные и `global using`), — без порога в три буквы, и при пустом префиксе
  (`Li` / Ctrl+Space → `List<>`); по индексу сборок namespace за namespace, с кэшем на набор ссылок. С первой буквы — то, что не
  импортировано: типы solution и сборок строками «Name (in Namespace)», выбор добавляет `using` (или пишет namespace перед именем, если
  файл видит другой тип с тем же именем); после точки — неимпортированные extension-методы сборок и solution (подходит ли получатель — по
  типам `lang/semantic`, у generic-методов проверяются ограничения); у `[` — атрибуты сборок. До первой буквы неимпортированного нет,
  completion перезапускается на ней. Замер на 307 реальных индексах (тест с `completeBasic`): пустой Ctrl+Space 26–48 → 40–44 мс (63 → 535
  пунктов), `Li` 28–40 → 20–22 мс, после точки 17–25 → 19–26 мс; в песочнице роботом 45–110 мс до готового списка. Тесты —
  `NativeImportCompletionTest`; сценарий — `Console/Editor/ImportCompletion.cs` (`TYPE:import-types-*`, `import-neighbour-*`,
  `import-library-type`, `import-qualified`, `import-extension*`, `import-attribute`) + `ImportCompletionTargets.cs`; `docs/LIVE_CHECKS.md`
  E-215…E-219. Проверено UI-роботом (Windows, IC 2026.1.4); человеком вживую не проверено
- [x] 0.1.86 — completion в списке аргументов без сервера (с 0.1.76 сервер выключен, и эти фичи жили только в модуле `roslyn`):
  (1) parameter info открывается сама после выбора метода из своего списка (`NativeCSharpCalls.callHandler` → `NativeCSharpCallPopups`),
  вместе с серым текстом аргументов; (2) лямбда там, где ждут делегат, — по перегрузкам своей семантики (`NativeCSharpLambdas`:
  `NativeCSharpParameterInfo.candidates` + `CSharpExpressionTypes.parameterTypesAt` с выводом type arguments, `Func` / `Action` / свои
  делегаты по `Invoke`): в списке первой (`x => `, `(x, i) => ` второй перегрузки `Where`), серым текстом (`NativeCSharpLambdaGhost`,
  на нём же аргументы под рукой вместо `RoslynLambdaGhost`); у `Changed += ` и `Func<int, bool> f = ` — `(sender, e) => {}` и
  «Create method OnChanged(object?, EventArgs)» (метод под текущим членом, типы — из сигнатуры сборки); (3) именованные аргументы
  `quantity:` перегрузок, что подходят по числу позиционных и уже названным (первыми, когда префикс совпал, иначе ниже значений), у
  атрибута — параметры конструкторов и settable-свойства `DiagnosticId =` (`NativeCSharpArgumentCompletion`). Имена лямбд вынесены
  в `lang/CSharpLambdaNames` (общие с модулем `roslyn`). Тесты — `CSharpArgumentCompletionTest`; сценарий —
  `debug-playground/Console/Editor/LambdaSuggestions.cs` (`TYPE:lambda-*`, `named-*`, `parameter-info`); `docs/LIVE_CHECKS.md`
  E-210…E-214. Проверено UI-роботом (Windows, IC 2026.1.4, сервер выключен); человеком вживую не проверено
- [x] 0.1.85 — переопределение и недостающие члены как в Rider (жалоба пользователя: в `Grpc/Greeter.cs` после удаления `SayHello`
  набор `override ` ничего не предлагал). Причины: (1) после пробела список открывал только клиент сервера, а он выключен по умолчанию
  (`CSharpSpaceAutoPopupHandler`: пробел после `override` / `partial` / `new`); (2) `override |` брал кандидатов из синтаксических
  заглушек solution и не видел вложенную базу `Greeter.GreeterBase` с `global::` / `grpc::` и базы из сборок. Теперь кандидаты — из
  семантики Generate (`NativeCSharpInheritedMembers.overrideItems`): базы solution, сборок и сгенерированных сборкой файлов, generic-базы,
  без переопределённого и `sealed`; вставка с доступом базы, типами как в файле, `base.X(...)` / `throw`, `await` после `async`, `using`.
  Красные CS0534 / CS0535 без сервера (`CSharpSemanticChecks.checkMissingMembers`; гейт — ложных 0 на playground и трёх библиотеках
  корпуса), Alt+Enter «Implement missing members» / «Override members...» на заголовке, базе и пустом месте тела
  (`NativeCSharpInheritedIntentions`), Ctrl+O / Ctrl+I (были), `base.` с членами баз из сборок, Ctrl+P в `: base(…)` / `: this(…)`.
  Тесты — `CSharpOverrideCompletionTest`, `CSharpOverrideAutoPopupTest`, `CSharpInheritedIntentionsTest`,
  `CSharpSemanticErrorsTest.testMissing*`; сценарий — `Console/Editor/Overrides.cs` (`TYPE:override-*`), `Grpc/Greeter.cs`
  (`TYPE:grpc-override`); `docs/LIVE_CHECKS.md` E-200…E-205. Робот (2026-10-05): автопопап и вставка в `Overrides.cs` и `Greeter.cs`, CS0534 и
  Alt+Enter, Ctrl+O; по его находкам — одна группа на базу в диалоге и члены настоящей базы выше `object`. Человеком вживую не проверено
- [x] 0.1.84 — цвета C#-файла сразу при открытии, без «довкрашивания» идентификаторов через полсекунды (жалоба пользователя). Замер
  роботом (Windows, IC 2026.1.4, `tools/ui-robot/scripts/color_timing.js`): демон платформы начинает первый проход через 0,4–0,6 с после
  создания редактора, свой расчёт цветов — ещё 10–300 мс; ошибки (`NativeCSharpDiagnosticsAnnotator`) цвета не задерживали — аннотаторы
  идут параллельно, цвета ложатся раньше конца диагностики. Решение — слой `lang/CSharpOpeningColors`: при создании редактора цвета,
  запомненные при закрытии файла с тем же текстом (до первой отрисовки), иначе посчитанные сразу в фоне теми же функциями, что у
  аннотаторов; семантические цвета кэшируются на файле (`NativeCSharpSemanticColors.colors`), и проход демона их не считает заново; слой
  уходит, когда демон закончил, — его подсветки те же, ничего не перерисовывается. После перезапуска IDE цвета восстанавливает кэш
  разметки платформы (слой тогда не нужен). Было → стало: первое открытие большого файла (2244 строки) 0,80–0,89 → 0,27 с, маленького
  0,46 → 0,16 с, повторное открытие после выгрузки документа 0,86 с → до первой отрисовки, холодное открытие проекта 11,9 → 0,76 с
  (простые цвета, полные — по готовности индексов). Тесты — `CSharpOpeningColorsTest`; сценарий — `Console/Editor/OpeningColors.cs`
  (`TYPE:colors-on-open`); `docs/LIVE_CHECKS.md` E-195. Вживую человеком не проверено
- [x] 0.1.83 — ложная CS1061 на унаследованных членах (сообщение коллеги пользователя, EF Core: `x.BookDate` в `Where` по
  `DbSet<DirectStressTest>`, члены объявлены в базовом `StressTest`, а файл обработчика импортирует DTO с тем же простым именем `StressTest`).
  Причина: синтаксическая карта членов (`NativeCSharpResolver.membersOf`) искала базовые типы по простому имени так, как их видит файл
  проверки (его `using` предпочитались), и брала DTO вместо базы сущности. Теперь база части другого файла ищется сначала в типах и
  пространствах имён вокруг самой части (`baseOf`), а проверка CS1061 / CS0117 (`CSharpSemanticChecks.has`) и поиск члена
  (`CSharpNameResolver.membersNamed`) идут по базам, разрешённым в файле объявления (`baseTypes`). Тесты —
  `CSharpQueryTranslationTest.testMembersOfAnEntityBase*`; сценарий — `Console/Editor/Queries.cs` (`TYPE:queries-inherited-member`,
  сущности — `QueryEntities.cs`); `docs/LIVE_CHECKS.md` E-190. Вживую не проверено
- [x] 0.1.83 — цвета C# отданы схеме IDE (решение пользователя 2026-10-05): плагин больше не кладёт свои цвета в схемы IDE —
  в Dark / Islands Dark / Light они не применялись (такие схемы берут Language Defaults раньше родителя), а правки Language Defaults не
  доходили до C#. Виды C# падают на свои ключи Language Defaults (класс, интерфейс, объявление и вызов функции, статические метод и поле,
  константа, metadata); палитра Rider — схемы «Rider Dark» / «Rider Light» (`colorSchemes/Rider*.xml`, `bundledColorScheme`).
  Проверка — `CSharpSemanticColorsTest`; вживую — E-191
- [x] 0.1.82 — шум анализаторов, неявные вызовы в Find Usages, C# из целей сборки. (1) В редакторе по умолчанию только то, что
  показывают Rider / VS: warning и error по severity, которую считает компилятор (`.editorconfig`, ruleset, AnalysisLevel / AnalysisMode;
  `RunAnalyzers` / `RunAnalyzersDuringLiveAnalysis = false` выключают анализаторы помощника). Info (IDE0290, CA1859, CA1822…) — невидимые
  аннотации, их fixes на Alt+Enter; «Show suggestions» (теперь выключен по умолчанию) возвращает слабые предупреждения. Замер на Console:
  было 174 видимых метки в 31 файле (171 Info), стало 3 (только `Analyzers.cs`). Run Code Analysis в окне Build — узлы по severity
  («Warnings (n)» раскрыт, «Suggestions (n)» свёрнут, `CodeAnalysisReport`). (2) Find Usages / Rename по solution
  (`lang/semantic/CSharpSolutionSearch`): неявные вызовы `Deconstruct` (деконструкция, `foreach (var (a, b) …)`; позиционные шаблоны,
  как у Roslyn, не считаются), `Add` (элементы инициализатора коллекции), `GetEnumerator` (`foreach`), `Dispose` (`using`), `GetAwaiter`
  (`await`) — места как у `SymbolFinder`; Rename их не трогает. Target-typed `new(…)` в `["a"] = new()`, элементах инициализатора коллекции
  и коллекционных выражениях (тип ImplicitPoint — 14 мест, как у сервера). (3) C#, который пишут в `obj/` цели MSBuild (XAML WPF
  `*.g.cs` / `*.g.i.cs`, Grpc.Tools, Resources.Designer): список — из design-time сборки помощника (`buildFiles` ответа `generate`), для
  проектов старого формата — `obj/<конфигурация>[/<TFM>]` после последней сборки (`codeanalysis/BuildGeneratedSources`); файлы проиндексированы
  (они в content root), в дереве — Dependencies → Analyzers → «Generated by build» → вид → файлы, баннер «Generated by the build (… from …)»;
  свежие — WPF / gRPC больше не молчат об ошибках (`CSharpSemanticEnvironment.mayGenerateTypes`), сохранённая правка `.xaml` / `.proto` /
  `.resx` делает их устаревшими (узел и баннер «out of date», снова молчание) до следующей генерации. Площадка — новый проект
  `debug-playground/Grpc` (собирается офлайн из кэша NuGet); WPF в SDK-стиле не добавлен — не соберётся на macOS. Тесты
  `AnalyzerNoiseTest`, `CSharpImplicitUsagesTest`, `BuildGeneratedSourcesTest`; сценарии — `Console/Editor/Analyzers.cs` (`TYPE:an-quiet`,
  `an-project`), `Console/Editor/ImplicitUsages.cs` (`TYPE:implicit-*`), `Grpc/Greeter.cs` (`TYPE:grpc-*`), `NetFramework/LegacyWpf/MainWindow.xaml.cs`
  (`TYPE:wpf-generated`); `docs/LIVE_CHECKS.md` E-180…E-185. Заодно: имена через псевдоним пространства имён (`pb::IMessage<T>`, как пишет protoc) резолвятся (`CSharpNameResolver`); непубличный интерфейс библиотечного типа (WPF: `IAddChildInternal`, `IHaveResources`) больше не делает тип «неизвестным» — ошибки на WPF-типах (`CSharpSemanticChecks.libraryKnown`; semanticGate — ложных 0); проект без source generators не становится навсегда «устаревшим» после правки; баннер «Not a part of …» не показывается на файлах, которые сделала сборка. Проверено UI-роботом (Windows, IC 2026.1.4): 3 метки вместо 174, группы Run Code Analysis, Find Usages неявных вызовов как у сервера (кроме каскада `GetEnumerator`), gRPC и LegacyWpf с ошибками и баннером; человеком вживую не проверено. Не сделано: каскад Find Usages к методам библиотечных
  интерфейсов (сервер для `GetEnumerator` / `Dispose` показывает все `foreach` / `using` solution); устаревшие файлы старого формата сами не
  обновляются — нужна сборка MSBuild'ом VS
- [x] 0.1.81 — хвосты A7 и C4d без сервера. (1) Extract Method в циклах (`lang/NativeCSharpExtractMethod`): «после выделения» — и
  остаток цикла с условием и следующей итерацией (код до выделения, чтение в самом выделении), если переменная объявлена вне тела цикла;
  запись — «на всех путях» только вне условий / циклов / `try` / лямбд, иначе переменная входит параметром (и `ref` вместо `out`);
  `x = x + 1` читает до записи. Один вид выхода из выделения (`break` / `continue` к циклу или `switch` вокруг, `return;` у `void` /
  `async Task` / конструктора / сеттера) — метод `bool`, вызов `if (NewMethod(…)) break;`, выходы — `return true;`, в конце `return false;`;
  если конец выделения недостижим — `NewMethod(…); break;`, завершающий выход из тела убран. Отказ с подсказкой: два вида выходов, выход
  вместе с возвращаемым значением (`ref` годится), `return` со значением / из лямбды. (2) Introduce Parameter (`lang/NativeCSharpIntroduceParameter`,
  действие `IntroduceParameter` переопределено — Ctrl+Alt+P, строка Refactor This): выражение метода / конструктора → параметр (перед
  необязательными, перед `params`), вызовы по solution — поиском использований плагина (`CSharpSolutionSearch`, только чтение): аргумент
  позиционно или `name: value` в конце, параметры метода в выражении заменены аргументами вызова (или значением по умолчанию), `new T(…)`,
  `: this(…)`, `new(…)`; для константы — попап «Pass It at Every Call» / «Make the Parameter Optional (= …)»; имя в рамке
  (`NativeCSharpInplaceName`). Отказ: локальные, параметры `ref` / `out` / `params`, type parameters метода, override / реализация /
  переопределённый метод, член экземпляра при вызове на другом объекте или конструктора, члены типа по простому имени при вызове из другого
  типа, method group. Introduce Field — имя в рамке у поля и использований. (3) Alt+Enter: «Convert '?:' to 'if' statement» на `?:` в
  аргументе / операнде — оператор повторён в ветках, у локальной — `T x;` и присваивания (тип выписан); «Introduce variable» — выбор
  платформы `OccurrencesChooser` («Replace this occurrence only» / «Replace all N occurrences»), вхождения — те же токены в блоке
  оператора, объявление перед первым; строки сервера «Introduce parameter for …», «Introduce field for …» (Alt+Enter) и «Introduce parameter
  for …», «Generate comparison operators» (попапы) скрыты. (4) Generate: Delegating members (попап «Delegate To», затем члены типа и его
  баз; без членов `object`, статических и уже объявленных), Equality comparer (вложенный `NameAgeEqualityComparer : IEqualityComparer<T>`
  и `public static IEqualityComparer<T> NameAgeComparer { get; }`), Relational members (`IComparable<T>`, флажки «Implement non-generic
  'IComparable' interface» и «Overload relational operators»; строки — `string.Compare(…, Ordinal)`, `CompareTo`, `Comparer<T>.Default`),
  Relational comparer (`IComparer<T>`); `lang/NativeCSharpGenerateComparers`, порядок строк Rider. Тесты `CSharpExtractMethodTest`,
  `CSharpIntroduceParameterTest`, `CSharpContextActionsTest`, `CSharpGenerateTest`, `RiderUiTest`; сценарии — `ExtractMethod.cs`
  (`TYPE:extract-loop-*`, `extract-return-void`), `IntroduceParameter.cs` (`TYPE:introduce-parameter*`), `ContextActions.cs`
  (`TYPE:ctx-conditional-split`, `ctx-introduce`), `Generate.cs` (`TYPE:gen-delegating`, `gen-equality-comparer`, `gen-relational*`);
  `docs/LIVE_CHECKS.md` E-170…E-175. Проверено UI-роботом (Windows, Community 2026.1.4); человеком вживую не проверено. Не сделано: Introduce Parameter не заменяет другие вхождения выражения и не правит именованные аргументы
  в других файлах при смене имени в рамке, `using` для типов выражения в файлах вызовов не добавляет; «все вхождения» Introduce variable —
  только по совпадению токенов (без проверки, что между ними ничего не менялось); Equality / Relational comparer без выбора «вложенный
  класс / отдельный класс»
- [x] 0.1.80 — поток nullable-состояний и LINQ как вызовы методов (остаток D1 и D2 `CSHARP_PSI_MIGRATION.md`). Поток
  (`lang/semantic/CSharpNullableFlow`): по каждой функции состояние «не null / может быть null / неизвестно» локальных, параметров и путей
  к членам (`x`, `this.F`, `x.A.B`, до трёх шагов) через присваивания (с копированием вложенных путей, как Roslyn), `== null`, `is null`,
  `is not null`, `is { }`, `is T t`, `??`, `??=`, `?.`, `!`, `&&` / `||`, ранние `return` / `throw`, циклы (до неподвижной точки), `try`,
  `switch`; атрибуты `System.Diagnostics.CodeAnalysis` своего кода и сборок (индекс формата 3: `IndexedNullability`, oblivious-типы старого
  кода). Предупреждения CS8602, CS8601, CS8604 (текст с сигнатурой метода), CS8600 / CS8603 / CS8625 теперь по потоку (старые проверки
  литерала `null` убраны), CS8618 на конструкторах (поле не задано на всех путях; `required`, `[SetsRequiredMembers]`, `: this(...)`,
  `[MemberNotNull]`), CS8618 partial-типов, когда генераторы известны. Сначала точность: неизвестный вызов, захваченные лямбдой переменные,
  параметр типа — молчим. Гейт: ложных nullable-предупреждений 0 на playground и 17 библиотеках runtime (887 файлов); на playground все 9
  nullable-предупреждений Roslyn совпали. LINQ (`lang/semantic/CSharpQueryTranslation`): запрос переводится в вызовы по §12.20.3 и
  разрешается против настоящего типа источника — `Queryable` с `Expression<Func<>>` для `IQueryable<T>` (и `DbSet<T>`), свои `Select` /
  `Where` (экземпляра и extension); прозрачные идентификаторы, `let`, `join … into`, `group … into`, продолжения; вызовы методами на
  `IQueryable` выбирают `Queryable` (лучший получатель). Гейт: имена 26 575 → 26 724, типы 39 655 → 39 877 верных (с новыми файлами),
  неверных не прибавилось. Тесты `CSharpNullableFlowTest` (сверка `nullableFlow/Cases.cs` с диагностиками Roslyn), `CSharpQueryTranslationTest`,
  `AssemblyIndexTest`. Сценарии — `debug-playground/Console/Editor/NullableFlow.cs` (`TYPE:nullable-*`) и `Queries.cs` (`TYPE:queries-*`);
  `docs/LIVE_CHECKS.md` E-160…E-165. Не сделано: состояния захваченных переменных внутри лямбд и параметры лямбд по типу делегата
  (Roslyn там предупреждает, плагин — нет), CS8620 / CS8619 (nullability аргументов типов), CS1936, Ctrl+B с ключевых слов запроса.
  Проверено UI-роботом (Windows, IC 2026.1.4): подсветка `NullableFlow.cs` — те же 7 предупреждений, что у `dotnet build`, набранные
  строки дают CS8604 / CS8625 / CS8602; Quick Doc и completion в `Queries.cs` как в EXPECT, CS1061 в запросе. Человеком вживую не проверено
- [x] 0.1.78 — задачи D1 и остаток D2 `CSHARP_PSI_MIGRATION.md`. D1: разбор перегрузок по §12.6.4 (`lang/semantic/CSharpOverloads`):
  применимость (неявные преобразования — тождество, числовые, nullable, ссылочные, boxing, пользовательские `op_Implicit`; `params` в
  обычной и развёрнутой форме, в том числе `params ReadOnlySpan<T>` библиотек; необязательные и именованные аргументы; вывод generic-аргументов
  в две фазы с лямбдами; extension-методы в сокращённой форме; группы методов и лямбды против `Func` / `Action` / своих делегатов;
  target-typed `null` / `default` / `new()` / `?:` / collection expressions, интерполированные строки и их handler'ы), лучший член
  (better conversion target, точное совпадение, не-generic, обычная форма, меньше подставленных значений по умолчанию, более
  конкретный), `OverloadResolutionPriority`; статические и экземплярные кандидаты по получателю, явные реализации интерфейсов не находятся
  по имени. Гейт (те же входы): имена 26 303 → 26 440 верных из 26 616, неверных 16 → 2; типы 39 344 → 39 445 из 39 732, неверных 8 → 1. D2: CS0162 (серым до конца блока),
  CS0168 / CS0219 (серым, «Remove unused variable»), CS4014 («Add 'await'»), CS0120, CS1503 и CS1501 / CS7036 для generic / `params` /
  необязательных и с именованными аргументами, CS0029 / CS0266 для `?:` (по естественному типу) и switch-выражений (по веткам), nullable
  CS8600 / CS8625 / CS8603 / CS8618 в простых случаях (`null`-константа; класс без конструкторов), `#pragma warning disable`, `<NoWarn>`,
  `.editorconfig` (`lang/semantic/CSharpSemanticWarnings`, `CSharpWarningContext`); контекстное действие «Add argument name». Ложных — 0 на
  гейте (playground, три библиотеки runtime). Не сделано: CS8601 / CS8602 / CS8604 (нужен поток nullable-состояний), CS1998 (компилятор .NET 10
  его больше не выдаёт), «Make method non-async», перевод LINQ-запросов в вызовы методов для `IQueryable` и своих источников (типы запроса — по
  `IEnumerable<T>` как раньше). Сценарии — `debug-playground/Broken/SemanticErrors2.cs` (`TYPE:sem2-*`), `debug-playground/Console/Editor/Overloads.cs`
  (`TYPE:overloads-*`); `docs/LIVE_CHECKS.md` E-140…E-146. Ctrl+B Built-in ведёт к выбранной перегрузке (раньше — список всех с этим именем).
  Проверено UI-роботом (Windows, IC 2026.1.4): метки файла сценария как у Roslyn, оба fix и «Add argument name» правят своё место, Ctrl+B на
  17 вызовах; человеком вживую не проверено
- [x] 0.1.77 — задачи D3 и D4 (`CSHARP_PSI_MIGRATION.md`): source generators и анализаторы Roslyn без сервера через помощник
  CodeAnalysisHelper (`helpers/codeanalysis`, несётся исходником, `cli/DotNetHelper` собирает его SDK машины ≥ 8 против Roslyn из
  `DotnetTools/dotnet-format` SDK — без сети; пакет `codeanalysis`). Генераторы проекта (кроме Razor) — на сохранении, после сборки и по
  .NET → Code Analysis → Refresh Generated Files; файлы — в кэше IDE, в дереве Dependencies → Analyzers → генератор → тип (только чтение,
  баннер), в индексе и резолвере (`CSharpSourceScope`); правило C4c «есть генераторы → молчим» снято, пока вывод свежий. Анализаторы
  пакетов, CA и IDE из SDK по `.editorconfig` — в фоне через секунду после сохранения файла и по Run Code Analysis (проект / solution →
  окно Build); подсветка — своим ExternalAnnotator (id в начале, Info — слабые предупреждения), fixes — Alt+Enter правками помощника
  одной командой; предупреждение последней сборки, которое анализатор показывает вживую, не дублируется. Settings | .NET | Analyzers and
  Generators. Один помощник на solution, выход после простоя (10 мин). Работает, пока сервер выключен. Сценарии —
  `debug-playground/Console/Editor/Generators.cs` (`TYPE:sg-*`) и `Analyzers.cs` (`TYPE:an-*`), `docs/LIVE_CHECKS.md` E-130…E-136.
  Проверено UI-роботом (Windows, IC 2026.1.4); человеком вживую не проверено. Не сделано: генераторы MSBuild-задач (gRPC, XAML) и Razor;
  fix с переименованием не правит проекты, которые ссылаются на проект; при выключенных генераторах файл, открытый до загрузки
  решения, анализируется только после следующего сохранения
- [x] 0.1.76 — веха 12.1 миграции (задача C4e): `roslyn-language-server` выключен по умолчанию (`RoslynLanguageServerSettings.ENABLED_BY_DEFAULT`),
  все фичи со своей реализацией отвечают сами; галочка на Settings | .NET | Language Server включает сервер обратно; без сервера нет
  вопроса о .NET 10 SDK. Проверка вживую — `docs/LIVE_CHECKS.md` E-121. Не сделано: замер памяти без сервера
- [x] 0.1.75 — задача C4d: Generate (Alt+Insert) своими генераторами Rider без сервера (`lang/NativeCSharpGenerate`, запуск и диалог —
  `lang/NativeCSharpGenerateActions`): Constructor (с базовыми конструкторами), Read-only properties, Properties, Missing members
  (абстрактные члены и интерфейсы, в том числе из сборок по индексу), Overriding members, Partial members, Deconstructor, Equality members
  (`IEquatable<T>`, `HashCode.Combine`, операторы), Formatting members (`ToString`), Dispose pattern. Диалог — `MemberChooser` платформы с
  группами как в Rider, код — встроенным форматтером, `using` — по месту, члены — на строку каретки; недоступные строки серые, строки
  сервера с тем же смыслом и «Override / Implement Methods…» платформы из списка убраны; Ctrl+I / Ctrl+O в C# — те же генераторы
  (`codeInsight.implementMethod` / `overrideMethod`). Refactor This: Extract Method (`lang/NativeCSharpExtractMethod`, действие
  `ExtractMethod` переопределено: вне C# — платформенное) — выражение или операторы одного блока, параметры по потоку данных, возврат /
  `out` / `ref`, `async`, `static`, имя в рамке; Introduce Field (`lang/NativeCSharpIntroduceField`). Сценарии —
  `debug-playground/Console/Editor/Generate.cs` (`TYPE:gen-*`) и `ExtractMethod.cs` (`TYPE:extract-*`, `TYPE:introduce-field`),
  `docs/LIVE_CHECKS.md` E-115…E-120. Проверено UI-роботом (Windows, Community 2026.1.4); человеком вживую не проверено. Не сделано:
  ~~Delegating members, Equality comparer, Relational members / comparer, Introduce Parameter, рамка имени у Introduce Field; поток данных
  Extract Method не учитывает циклы~~ (сделано в 0.1.81); реализованный член интерфейса сверяется по имени, виду и числу параметров
- [x] 0.1.73 — ссылки по solution без сервера (задача C4b; «Navigation and usages» и «Rename» = Built-in, по умолчанию):
  `lang/semantic/CSharpSolutionSearch` — кандидаты по слову (индекс слов платформы), каждый резолвится резолвером C1/C2; ключ объявления —
  файл + смещение (одно и то же для PSI из стаба и из AST); каскад по иерархии члена, как у Find References сервера; `base(…)` / `this(…)` /
  target-typed `new()` для конструкторов и типа; `cref` (в т. ч. `T.M`), `nameof`, имена в property patterns; подтипы — новый stub-индекс
  `csharp.supertype` (простые имена базовых типов). Find Usages / Show Usages на платформенной машинерии (`lang/NativeCSharpFindUsages`:
  `TargetElementEvaluator`, `ReferencesSearch`, `DefinitionsScopedSearch`, `UsageTypeProvider`), поиск от члена сборки (metadata view);
  подсветка использований члена под кареткой; Go to Implementation (платформенный обработчик на `DefinitionsScopedSearch`), Go to Super,
  Type / Call Hierarchy и иконки gutter (`lang/NativeCSharpHierarchies`, первыми; при ROSLYN уступают модулю `roslyn`, у LSP-клиента
  `findReferencesCustomizer` выключается при Built-in); rename типов и членов по solution (`lang/NativeCSharpSolutionRename`: inplace в
  текущем редакторе, остальное — по Enter одной командой; вопрос про иерархию; конструкторы и деструктор с типом; файл, названный по типу;
  конфликты). Тесты `CSharpSolutionUsagesTest`, `CSharpSolutionRenameTest`, `CSharpNativeHierarchiesTest`. Сценарий —
  `debug-playground/Console/Editor/SolutionUsages.cs` + `Lib/SolutionShapes.cs` (`TYPE:solution-*`), `docs/LIVE_CHECKS.md` E-103…E-108.
  Робот (Windows, IDEA Community, `usages_compare.js` — 384 объявления площадки против `textDocument/references` / `implementation`):
  Find Usages 372 из 378 мест сервера, лишних 0; Go to Implementation 396 из 396. Не хватало: неявный `Deconstruct` (`var (a, b) = x`),
  target-typed `new()` в элементах `[…]` (после прогона добавлен тип цели элемента коллекции — `CSharpExpressionTypes.target`, тест; роботом
  повторно не сверено) и в инициализаторе словаря `["a"] = new(…)` — остался; объявление сервер кладёт в результаты отдельной группой
  «Declaration», платформа — нет (как Rider). Человеком вживую не проверено
- [x] 0.1.66 — шаг 11c, первая часть (задача C3): completion после точки на своей семантике (`lang/NativeCSharpMemberCompletion`,
  `lang/semantic/CSharpMemberLookup`; «Completion» = Built-in) — члены типа значения из solution и сборок с унаследованными и
  подставленными аргументами-типами (`Add(int item)` у `List<int>`), extension-методы в области (без `this`-параметра), static-члены и
  вложенные типы после типа, namespace и типы после namespace, `a?.`, `this.` — и члены библиотечных баз (protected только через `this`);
  видимость по C# (private внутри типа, protected в наследнике), дубли сервера убираются. Quick documentation (Ctrl+Q / наведение) и
  Parameter Info (Ctrl+P) — переключатель «Documentation and parameter info» (по умолчанию Language server до робота;
  `lang/NativeCSharpDocumentation`, `NativeCSharpParameterInfo`, `semantic/CSharpSymbolText`): строка как Quick Info Roslyn
  (`void Console.WriteLine(string value) (+ N overloads)`, `(parameter) int limit`), XML-документация из `///` и из доков сборок
  (summary, параметры, returns, exceptions, remarks), перегрузки строками. Extension-методы solution на `this string` теперь находятся
  и резолвером. Сценарий — `debug-playground/Console/Editor/MemberCompletion.cs` (`TYPE:dot-*`, `TYPE:quick-doc`, `TYPE:parameter-info`);
  `docs/LIVE_CHECKS.md` E-80…E-84. Роботом и вживую не проверено
- [x] 0.1.67 — исправления по роботу в WSL (2026-10-05): папка без системы сборки открывается в IDEA 2026.1 проектом без модулей, её
  файлы не индексируются, и платформа молча пропускает всё не-`DumbAware` — цвета Built-in и Alt+Enter-действия плагина пропадали.
  `solution/DotNetModuleSetup` заводит модуль на папку с solution, цвета и intention-действия C# — `DumbAware` (E-46, E-55, E-54,
  E-62, E-65, E-66). Серый неактивный `#if` (`CSharpColors.INACTIVE_BRANCH`, как `CSHARP_PREPROCESSOR_INACTIVE_BRANCH` в Rider), код
  у текста ошибок сервера («CS0230: …»), нет списка completion внутри числа (`1` + Enter давало `1_resized`), строка Ctrl+наведения у
  целей Built-in (`NativeCSharpQuickNavigateInfo`), имя поставщика серого текста «C# Project Support» вместо id, серверный «Fix All: …»
  уходит вместе со своей строкой, которую заменяет действие плагина. Робот (WSL) — `docs/LIVE_CHECKS.md`; без сервера, кроме E-51
- [x] 0.1.62 — шаг 10, Go to Class / Symbol по сборкам и metadata view (задача B4): `index/AssemblyGotoContributors` — типы (и члены
  для Go to Symbol) всех проиндексированных сборок solution, только при «Include non-project items» (`scope.isSearchInLibraries`), одна
  строка на сборку и версию, `List<T> (System.Collections.Generic, System.Collections 10.0)`; имена — отсортированные таблицы на индекс
  (замер: 582 сборки площадки, 41 тыс. имён — 110 мс первый раз, 7 мс потом). `index/AssemblyMetadataText` — C# из индекса без тел
  (заголовок сборки, `using`, generic-параметры, `where`, базы, атрибуты, `///`, вложенные типы; все типы 582 сборок разбираются без
  синтаксических ошибок), `index/AssemblyNavigation` — файлы своей ФС `dotnet-metadata://` (вкладки и история переживают перезапуск),
  только чтение, заголовок вкладки и баннер; Built-in Go to Declaration к члену сборки без готового сервера — туда же
  (`AssemblyNavigation.declarationTargets` из `CSharpGotoDeclarationHandler`). Площадка — `debug-playground/README.md`, «Go to Class /
  Symbol по сборкам», и `LibraryNames.cs`, `TYPE:library-navigation`; `docs/LIVE_CHECKS.md` E-73–E-75. Роботом и вживую не проверено
  Замер на `debug-playground`: 111 библиотек, 592 dll, поиск в VFS ≈ 1,1 с (фон), индексация ≈ 0,5 с. Go to Class по типам сборок — в
  0.1.62 (B4). Площадка — `debug-playground/README.md`, «External Libraries». Вживую не проверено
- [x] 0.1.61 — `using` по встроенному дереву (задача A8, синтаксическая часть; `lang/NativeCSharpUsings`): completion `using var` /
  `await using var` (второй делает метод `async`), `using (` — локальные / `var` / `new`, директивы — namespace solution и сборок, после
  `using static` / alias — и типы, `global using` вверху файла; postfix `.awaitusing` (и `.using`) с именем по типу; Alt+Enter «Convert to
  'using' declaration» / «… statement», «Wrap in 'using' statement», «Sort 'using' directives», «Convert to 'global using'» (в
  `GlobalUsings.cs` проекта); «Make method async» и на `await using` / `await foreach`. Неиспользуемые директивы и всё, что требует
  `IDisposable`, — после C2/C3 (список в `CSHARP_PSI_MIGRATION.md`, A8). Сценарий — `debug-playground/Console/Editor/Usings.cs`
  (`TYPE:using-*`). Роботом и вживую не проверено
- [x] 0.1.60 — «Navigation and usages», «Completion» и «Colors of identifiers» встроенные по умолчанию (робот: те же цели Go to
  Declaration, из списка сервера ничего не теряется, все имена сервера окрашены тем же цветом или точнее); `new T()` — к конструктору;
  недостающие ключевые слова, без двойных `override`; выбор `await` / `override` / `partial` сервера больше не падает; цвета сервера
  не остаются под встроенными. Проверка — `docs/LIVE_CHECKS.md` E-44…E-47, E-52…E-56
- [x] 0.1.59 — шаг 10, library roots (задача B3): сборки, против которых компилируется solution, — внешние библиотеки платформы
  (`index/AssemblyLibraries`, `AdditionalLibraryRootsProvider`): Project view → External Libraries — reference packs, `.NETFramework`,
  пакеты NuGet (и `packages.config` по папке) с версиями, сборки по `HintPath`; корни — сами dll (XML-доки и прочее содержимое папок
  пакетов не индексируются), в `allScope`, не в project scope. Пересчёт вместе с индексом сборок (restore, файл проекта, TFM в тулбаре).
  Замер на `debug-playground`: 111 библиотек, 592 dll, поиск в VFS ≈ 1,1 с (фон), индексация ≈ 0,5 с. Go to Class по типам сборок — не
  сделан (нет `ChooseByNameContributor` по индексу сборок). Площадка — `debug-playground/README.md`, «External Libraries». Вживую не проверено
- [x] 0.1.58 — шаг 11b, типы выражений (задача C2, `lang/semantic/CSharpExpressionTypes`): тип любого выражения — вызов с выводом
  аргументов-типов (по аргументам и телам лямбд), `await`, индексатор, `?.`, `??`, `?:`, операторы, кортежи, деконструкция, `foreach`,
  `out var`, шаблоны, запросы LINQ, параметры лямбд; `var` — тип инициализатора. Цвета и Go to Declaration Built-in — член после точки
  у любого выражения. Гейт типов — 99,0 % (было 4,9 %), неверных 8; имён — 98,8 %. Подсказки типа `var` нет: своего quick documentation
  C# ещё нет (C3). Сценарий — `debug-playground/Console/Editor/ExpressionTypes.cs` (`TYPE:types-*`). Роботом и вживую не проверено
- [x] 0.1.57 — шаг 11a, разрешение имён (задача C1, `lang/semantic`): namespace, `using` / alias / `global using` (из других файлов и
  `ImplicitUsings` / `Using` проекта) / `using static`, `global::`, типы solution и сборок (порядок поиска C#, арность, суффикс
  `Attribute`), члены после точки у namespace, типа и значения известного типа (локальная, параметр, поле, свойство, `this` / `base`,
  `new T()`, вызов), унаследованные из solution и сборок с подстановкой, перегрузки по числу и известным типам аргументов, extension-методы.
  Цвета Built-in раскрашивают типы и члены сборок (`Console`, `WriteLine`), Go to Declaration Built-in ведёт к членам solution после точки
  у значения (член сборки — никуда, декомпилятора нет). Гейт семантики — 98,4 % (было 68,1 %), неверных 20 (было 282). Сценарий —
  `debug-playground/Console/Editor/LibraryNames.cs` (`TYPE:library-*`). Роботом и вживую не проверено
- [x] 0.1.56 — «Errors and warnings» и «Rename» встроенные по умолчанию (после робота: те же ошибки в тех же местах, что у сервера; rename
  сценариев площадки как у сервера). Переключение ошибок без задвоений и пропусков до первой правки; rename не пишет файл из слушателя
  шаблона. Скрипт робота — `tools/ui-robot/scripts/rename_check.js`
- [x] 0.1.55 — шаг 9, `COMPLETION`, синтаксическая часть (задача A6, «Completion», встроенный по умолчанию с 0.1.60): ключевые слова
  по месту, локальные / параметры / члены / типы в области с порядком Rider (локальные > параметры > члены > типы > ключевые слова,
  выше — что подходит по ожидаемому типу), `override` (абстрактный — `throw new NotImplementedException();`, виртуальный — вызов `base`),
  `partial`-методы, имена переменной после типа (`lang/NativeCSharpCompletion`, место — `NativeCSharpCompletionPlace`). Пункты сервера
  добавляются к своим без повторов (сервер теперь работает при любом переключателе). Сценарии — `debug-playground/Console/Editor/NativeCompletion.cs`
  (`TYPE:complete-*`) и `CommonCalls.cs`; робот — `complete_at_line.js` / `rider_complete.js`. Роботом и вживую не проверено
- [x] 0.1.54 — шаг 9, `DIAGNOSTICS`, синтаксическая часть («Errors and warnings», по умолчанию пока Language server): синтаксические ошибки
  Roslyn по своему дереву — парсер, литералы, директивы — с кодами, текстами и местами компилятора (`CS1002: ; expected` за концом строки);
  сервер при Built-in оставляет семантические ошибки и уступает только синтаксические, что показаны встроенными (`lang/NativeCSharpDiagnostics`,
  ядро — `csharp-psi-core` `lang/diagnostics`). Сценарий — `debug-playground/Broken/SyntaxErrors.cs` (`TYPE:diag-*`); робот — `errors_at.js`.
  Роботом и вживую не проверено
- [x] 0.1.53 — шаг 9, `RENAME` (задача A5, «Rename», по умолчанию пока Language server): inplace rename по дереву без сервера
  (`lang/NativeCSharpRename`, обработчик `NativeCSharpRenameHandler` первым): локальные, параметры (лямбд, анонимных методов, локальных
  функций, методов / конструкторов / индексаторов / первичных конструкторов, если именованного аргумента в других файлах нет), локальные
  функции, метки, переменные запросов, параметры типов (не `partial`); именованные аргументы своих вызовов и теги `<param>` / `<typeparam>`;
  `@` перед ключевым словом; конфликты (захват поля, параметр лямбды, второе объявление в области, член скрывает параметр первичного
  конструктора) — диалог «Problems Detected»; одно Ctrl+Z. Члены и типы — серверу (готов) или подсказка. Заодно один резолвер имён на
  навигацию, цвета и rename (`lang/NativeCSharpScopes` + `lang/NativeCSharpResolver`, дубликат в `NativeCSharpNavigation` удалён). Сценарий —
  `debug-playground/Console/Editor/Rename.cs` (`TYPE:rename-*`); робот — `inline_rename.js`. Роботом и вживую не проверено
- [x] 0.1.51 — шаг 9, `SEMANTIC_COLORS` («Colors of identifiers», встроенные по умолчанию с 0.1.60): палитра Rider (`lang/CSharpColors`,
  ~30 ключей `CSHARP_*_IDENTIFIER` с откатом на прежние `CSHARP_TYPE` / `METHOD` / `MEMBER`, страница Color Scheme | C# с группами Rider),
  токены сервера — в ту же палитру (`static`, `ReassignedVariable`, локальные, параметры, namespace, метки); встроенные цвета
  (`lang/NativeCSharpSemanticColors`): объявления по виду и модификаторам, локальные / параметры / метки по синтаксическим областям
  (`lang/NativeCSharpScopes`, на переиспользование в A2 / A5), члены своего типа, его partial-частей и базовых классов solution, типы
  solution по виду — из stub-индексов, без AST чужих файлов. Сценарий — `debug-playground/Console/Editor/SemanticColors.cs`
  (`TYPE:colors-*`); робот — `highlight_keys.js`. Роботом и вживую не проверено
- [x] 0.1.48 — шаг 9, `EDITING` («Typing assistance», по умолчанию Built-in после робота): Extend Selection по узлам дерева
  (`name.Trim()`, выражение, оператор, тело, член с doc-комментарием, текст строки без кавычек), Complete Statement по оператору под кареткой
  (недостающие `)` `]` `;`, блок для `if` / `foreach` / `while` / метода / класса в стиле скобок файла, многострочный вызов не разрывается),
  серая `;` для многострочных операторов (`lang/NativeCSharpEditing`). Сценарии — `debug-playground/Console/Editor/ExtendSelection.cs`,
  `CompleteStatement.cs` (`TYPE:extend-selection-*`, `TYPE:complete-*`, `TYPE:gray-semicolon`); робот — `extend_selection.js`,
  `complete_statement.js`. Роботом и вживую не проверено
- [x] 0.1.46 — шаг 9, первая фича: виды использований Find Usages по PSI файла (`lang/NativeCSharpUsageKinds`, переключатель «Kinds of
  usages», по умолчанию Built-in после проверки роботом); точнее эвристики на деконструкции, вложенных инициализаторах, `out Order o`, паттернах
  `switch`, переменных запроса и параметрах лямбд. Сценарий — `debug-playground/Console/Editor/FindUsages.cs` (`TYPE:find-usages-native-*`).
  Робот: все маркеры совпали с ожидаемым; вживую пользователем не проверено
- [x] 0.1.41 — шаг 10, часть для парсера: `msbuild/CompilationModel` — по файлу проект (свой каталог, если он файл не исключает; иначе
  вычисленный проект, который его подключает), конфигурация и TFM тулбара (у multi-target без выбора — первый) и что получает компилятор:
  `DefineConstants` вместе с неявными символами SDK (MsBuildHost прогоняет таргет `AddImplicitDefineConstants` на копии вычисления),
  `LangVersion` с умолчанием SDK, `Nullable`, `ImplicitUsings` и `Using`, `RootNamespace`, `Compile`. До ответа помощника и без него —
  статическое чтение проекта и ближайшего `Directory.Build.props` (`CompilationOptionsReader`, в т.ч. legacy-проекты), совпадает с MSBuild на
  всех фикстурах. API для csharp-psi: `symbolsFor(file)`, `languageVersionFor(file)`, топик `CHANGED`. Тест `CompilationOptionsTest` на
  ответах настоящего MsBuildHost (SDK 10.0.401, `tools/compilation-fixtures/capture.py`). В IDE не подключено (ключи csharp-psi — шаг 7)
- [ ] Шаги 10–11: project model (ссылки: `project.assets.json`, индекс сборок, library roots), семантика по слоям со сверкой по Roslyn — 0.1.52: формат индекса для семантики и ссылки проекта (B1–B2); library roots (B3) и семантика — дальше
- [ ] Шаг 12: сервер опционален (снимается требование .NET 10) → сервер удалён

### Подсказки для частых вызовов (по просьбе пользователя 2026-10-04)

Как в Rider: готовое продолжение там, где пишут одно и то же. Серым текстом (`CSharpGhostText`, Tab) и первым пунктом completion.
Сначала то, что видно из синтаксиса (заголовок метода, его параметры, `async`), потом — с типами (шаг 11c). Сценарий —
`debug-playground/Console/Editor/CommonCalls.cs` (создать вместе с первой подсказкой). Что в этих местах даёт Rider — `docs/RIDER_REFERENCE.md`.
- [x] `return` в методе, который возвращает `Task<T>` / `ValueTask<T>` и не `async`: `return Task.FromResult(|);`
  (`ValueTask.FromResult`); у `Task` / `ValueTask` без `T` — `return Task.CompletedTask;` (`default`). В `async` — ничего такого
  (там `return value;`). Синтаксис: тип возврата и модификаторы из заголовка — можно до семантики. Сделано в 0.1.55: серый текст
  (`CSharpGhostText`, правило «Task return») при любом источнике, пункт completion — при «Completion» = Built-in
  (`NativeCSharpCommonCalls`; `TYPE:complete-task-from-result`, `TYPE:complete-task-completed`)
- [ ] `CancellationToken` в вызов: в методе с параметром `CancellationToken ct` при наборе аргументов вызова, у которого последний
  параметр — `CancellationToken` (сигнатура из индекса сборок / signature help сервера, позже из семантики), — серый `ct`
  (или `cancellationToken:`, если до него пропущены необязательные); в completion аргумента — переменная токена выше всех. Без
  параметра в методе — `CancellationToken.None` ниже. Как Rider «Pass cancellation token»
- [ ] Параметр `CancellationToken cancellationToken = default` в конец списка у `async`-метода, который зовёт методы с токеном
  (intention / серый текст после последнего параметра)
- [ ] `await` перед вызовом, который возвращает `Task`, в `async`-методе (completion вставляет `await `); `.ConfigureAwait(false)`
  — только по настройке (для библиотек)
- [x] `async` в заголовок, когда в теле набран `await` (quick-fix, как в Rider); `Task` вместо `void` у такого метода — 0.1.55: выбор
  `await` в completion (Built-in) и intention «Make method async» (`NativeCSharpMakeAsyncIntention`); обработчик события остаётся
  `async void` (`TYPE:complete-await-async`, `TYPE:complete-make-async`). `.ConfigureAwait(false)` не делался
- [ ] Частые продолжения из статистики (`suggest/`): что после `ArgumentNullException.` (`ThrowIfNull(|);`), `string.` (`IsNullOrEmpty(|)`),
  `Task.` (`WhenAll(|)`, `Run(|)`) — порядок completion по тому, что выбирают чаще (`RoslynCompletionRanking` + статистика)

## Платформа
- [x] Минимальная версия — 2026.1 (`sinceBuild = 261`), сборка и тесты на IntelliJ IDEA 2026.1.4, Kotlin API 2.3. Папки под узлом проекта в панели Solution получили короткие имена и в IDEA (Java-плагин называл их как пакеты). Убраны устаревшие `ReadAction.compute`, `DaemonCodeAnalyzer.restart()`, `isLenient`, `createSingleFileDescriptor`
- [x] События окна Build (2026-09-30): в 2026.1 конструкторы `*EventImpl` уже `@Deprecated` + `@Internal` (у `StartBuildEventImpl` — for removal),
  а `BuildEvents` с builder-ами — `@Internal` (в master тоже), ждать стабилизации нечего. Перешли на builder-ы через один класс `build/BuildViewEvents`
  (started / output / message / finished); у `MessageEventBuilder` 2026.1 ещё нет `withFilePosition` — файл через `fileMessage(...)`.
  Оба потребителя (`DotNetBuildService`, `BuildViewCommandOutput`) идут через него. Тест `BuildViewEventsTest`
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
- [x] Фаза 1, хвосты (проверено роботом 2026-09-30): `.cs`, созданный после загрузки solution, попадал в проект секунд через 15–20 окольным
  путём через `didOpen`. Причина: после `initialized` сервер регистрирует у клиента `workspace/didChangeWatchedFiles` (`.cs` / `.razor` / `.cshtml`
  под папкой проекта и файл проекта — `capture-5.12/63-server_to_client_requests.json`), а платформенный LSP-клиент регистрацию не выполняет.
  Сделано 2026-09-30: `RoslynFileWatcher` (`BulkFileListener` модуля `roslyn`) → `RoslynWatchedFiles.changes` (чистая: создание / удаление /
  правка на диске / переименование и перемещение как удаление + создание; `.cs`, файлы проектов, props / targets, `.editorconfig`, `.sln[x]`,
  Razor; без `bin` / `obj` / `.git`) → `RoslynWorkspace.filesChanged` → уведомление каждому клиенту. Тест `RoslynFileWatcherTest`. Вживую не проверено
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
  Объявляемая на этой же строке переменная (`int amount = |` → `amount`, её предлагает сервер) стояла первой — с 2026-09-30 отсеивается:
  `CSharpExpected.declared` из `DECLARATION`, `RoslynCompletionRanking.isBeingDeclared` (только kind Variable, не после точки), тест в `CompletionRankingTest`
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
