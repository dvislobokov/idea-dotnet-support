# Карта возможностей сервера: что их заменит без `roslyn-language-server`

Записано 2026-10-04 по коду плагина 0.1.45 (задание — `docs/NEXT_SESSION.md`, раздел 2). Шаги — по `CSHARP_PSI_MIGRATION.md`:
7 — свой парсер (сделан), 8 — stub-индексы, 9 — синтаксические фичи на PSI, 10 — project model, 11a — разрешение имён,
11b — типы членов и выражений, 11c — фичи на 11a–b (completion после точки, Go to Declaration, Find Usages, rename, цвета,
документация, parameter info), 11d — перегрузки / generics / лямбды / extension / LINQ, 11e — диагностики, 11f — source
generators, 12 — «только Roslyn, по запросу» (12.1 — сервер опционален, 12.2 — удалён, анализаторы через помощник).

**Расхождение в буквах.** `NEXT_SESSION.md` пишет «разрешение имён (11b), типы (11c)». В плане это 11a и 11b, а 11c — фичи на
них. Ниже везде буквы плана.

Переключатель — значение `CSharpFeature` из `lang/CSharpFeatures.kt` (`SYNTAX_TREE`, `FORMATTING`, `EDITING`, `USAGE_KINDS`,
`NAVIGATION`, `COMPLETION`, `DOCUMENTATION`, `DIAGNOSTICS`, `SEMANTIC_COLORS`, `RENAME`); сейчас `hasNative` есть только у `SYNTAX_TREE`.

## 1. Страница Language Server и возможности сервера

Страница — `lsp/RoslynLanguageServerConfigurable.kt`, каталог опций — `RoslynOptions.ALL` в `lsp/RoslynLanguageServerSettings.kt`,
названия групп — `roslyn.group.*` в `DotNetBundle*.properties`. На странице 8 групп опций сервера (Analysis / Анализ, Projects /
Проекты, Completion / Автодополнение, Navigation and Documentation / Навигация и документация, Code Lens, Inlay Hints / Подсказки
в коде, Editing / Редактирование, Code Generation / Генерация кода), над ними — группа «Сервер» (путь, `--logLevel`, папка логов,
`--autoLoadProjects`, запуск генераторов, аргументы) и «Источник возможностей» (`CSharpFeatures.offered()`), под ними — «Прочие
параметры сервера». Группа «Сервер» уходит вместе с сервером; из неё по смыслу переживает только запуск генераторов → **11f**.

### 1.1. Опции страницы

| Группа / опция (`section`) | Что это | Без сервера — чем и на каком шаге | Что в плагине уже есть |
|---|---|---|---|
| Анализ: `dotnet_compiler_diagnostics_scope` | ошибки компилятора: открытые файлы / весь solution / нет | синтаксические ошибки — **9** (`DIAGNOSTICS`); семантические — **11e**; по всему solution — 11e поверх stub-индексов **8** | `build/BuildProblemsAnnotator` (ошибки последней сборки в редакторе), Build tool window. `CSharpHighlightErrorFilter` сейчас прячет `PsiErrorElement` своего парсера |
| Анализ: `dotnet_analyzer_diagnostics_scope` | анализаторы из NuGet и SDK | **только Roslyn (12.2, помощник по запросу)** | нет |
| Проекты: `dotnet_enable_automatic_restore` | restore, когда серверу не хватает пакетов | не нужно: свой restore | `NuGetSettings.automaticRestore`, `build/SmartRestore` |
| Проекты: `dotnet_enable_file_based_programs(_when_ambiguous)` | `.cs`, запускаемый `dotnet run file.cs` | project model **10** (директивы `#:package` и т. п. — не проверял, есть ли они в `CompilationModel`) | действие `DotNet.RunCSharpFile` (запуск) |
| Проекты: `dotnet_binary_log_path` | binlog загрузки проектов сервером | не нужно (у плагина свой MsBuildHost, **10**) | `msbuild/MsBuildEvaluation` |
| Автодополнение: `show_completion_items_from_unimported_namespaces` | элементы из неимпортированных namespace | статические члены — уже без сервера; типы — из `AssemblyIndex` сразу (индекс их хранит, но `ImportCompletion` вызывает только `members(prefix)`); типы своего solution — **8**; после точки — **11c** | `index/ImportCompletion` (голое имя ≥ 3 букв, статические члены, авто-`using`) |
| Автодополнение: `show_name_completion_suggestions` | имена для новых переменных и членов по типу | синтаксис **9**: имя выводится из типа, написанного перед ним | `CSharpPropertyNameConfidence` (гасит автопопап на имени свойства), серый текст `CSharpGhostText` (имена параметров конструктора, `_name = name;`) |
| Автодополнение: `provide_regex_completions` | completion внутри регулярных выражений | инъекция языка RegExp платформы: эвристика по вызову (`new Regex("…")`, `Regex.IsMatch`) — **9**; точно по `[StringSyntax("Regex")]` — **11c** | нет |
| Автодополнение: `trigger_completion_in_argument_lists` | автопопап в списке аргументов | ожидаемый тип параметра — **11c**, при перегрузках — **11d** | `CSharpArguments` / `CSharpExpectations` (`lang/CSharpScopeTypes.kt`, по токенам), `RoslynLambdaCompletion` (на signature help сервера) |
| Навигация: `navigate_to_decompiled_sources` | Go to Declaration в сборку — декомпилят | см. часть 2: по имени типа — сразу / **8**, по символу — **11a/11c** | IL Viewer (`helpers/dotnethelper/Il.cs`, `ICSharpCode.Decompiler`); оформление декомпилята `roslyn/RoslynDecompiledSources.kt` |
| Навигация: `navigate_to_source_link_and_embedded_sources` | исходники пакета по Source Link / встроенные в PDB | часть 2 | чтение portable PDB (рядом, embedded) в `Il.cs` |
| Навигация: `show_remarks_in_quick_info` | `<remarks>` в quick documentation | quick doc — **11c** + XML-документация пакетов (в индексе её нет, расширение формата — **10**) | для C# нет; документация только у MSBuild (`MsBuildDocumentationProvider`) |
| Навигация: `search_reference_assemblies` | искать символы в эталонных сборках | часть 3: Go to Symbol по `AssemblyIndex` — сразу | `AssemblyIndex` (типы + статические члены) |
| Code Lens: `enable_references_code_lens` | «N references» над объявлением | Find Usages по solution — **11c** (счёт по слову из IdIndex раньше возможен, но врёт на одноимённых — не предлагаю) | нет |
| Code Lens: `enable_tests_code_lens` | Run / Debug test | уже без сервера; на стабах атрибутов — **8**; унаследованные тесты и свои атрибуты-наследники — **11a** | `testing/TestDiscovery` + ▶ `DotNetTestRunLineMarkerContributor`, Unit Tests explorer |
| Подсказки: имена параметров (`for_parameters`, `literal`, `indexer`, `object_creation`, `other`) | `Foo(count: 1)` | нужен вызываемый метод: единственная перегрузка — **11c**, выбор перегрузки — **11d**; индексаторы — **11b** | сигнатуры статических членов в `AssemblyIndex` (параметры с именами); своих inlay-провайдеров нет |
| Подсказки: три `suppress_…` (суффикс, намерение метода, имя аргумента) | когда подсказку не показывать | чистые функции над именами — вместе с провайдером (**11c**) | `CSharpNameLikeness` (похожесть имён) |
| Подсказки: типы (`for_types`, `implicit_variable_types`) | тип `var` | **11b** (`var` из инициализатора) | `CSharpScopeTypes` угадывает типы локальных по тексту |
| Подсказки: `lambda_parameter_types` | типы параметров лямбд | **11d** (лямбды) | нет |
| Подсказки: `implicit_object_creation`, `collection_expressions` | тип `new()` и `[...]` | target typing: `new()` — **11b**, коллекции — **11d** | нет |
| Редактирование: `dotnet_enable_auto_insert` | `///` → `<summary>`, закрывающие скобки | уже свои; на PSI — **9** (`EDITING`) | `CSharpDocCommentTypedHandler`, `CSharpDocCommentEnterHandler`, `CSharpBracketTypedHandler`, `CSharpEnterAfterBraceHandler` (`lang/CSharpTemplatesAndDocs.kt`, `lang/CSharpEditing.kt`). Не проверял, ходит ли платформа к серверу за auto-insert вообще |
| Редактирование: `organize_imports_on_format` | сортировка и удаление `using` при форматировании | сортировка — **9** (`FORMATTING`); удаление неиспользуемых — **11a** | порядок вставки `using` в `CSharpUsings` (`lang/CSharpCalls.kt`) |
| Редактирование: `highlight_related_regex/json_components` | парные части regex / JSON в строке | инъекции RegExp / JSON: по вызову — **9**, по `[StringSyntax]` — **11c** | нет |
| Генерация кода: `member_insertion_location`, `property_generation_behavior` | куда и как Alt+Insert вставляет члены | свои генераторы: члены интерфейса / базового — **11a** + экземплярные члены сборок в индексе (**10**); Equals / ctor по полям — **9** | live templates (`prop`, `$CLASS$`), постфиксные шаблоны, серый текст конструктора; `RoslynGenerateAction` — сервер |

### 1.2. Что сервер делает помимо опций (модуль `io.github.dotnetsupport.roslyn`)

| Возможность (класс) | Без сервера — шаг | Что уже есть |
|---|---|---|
| Семантические токены, тёплый кэш (`LspSemanticTokensSupport`, `RoslynTokensCache`; `SEMANTIC_COLORS`) | объявления, локальные, параметры — **9**; ссылки на типы и члены — **11c** | `CSharpIdentifierAnnotator` + `CSharpIdentifierClassifier` (по токенам, уступают по `RoslynServerStatus.colorsIdentifiers`) |
| Completion LSP + `RoslynCompletionItems` / `Ranking` / `Restart`, лямбды (`RoslynLambdaCompletion`, `RoslynLambdaGhost`) (`COMPLETION`, `EDITING`) | ключевые слова и локальные — **9**; после точки — **11c**; лямбды по делегату — **11d** | `ImportCompletion`, `CSharpCaseInsensitiveCompletion`, completion выражений отладчика, live / postfix templates, `CSharpGhostText` |
| Go to Declaration, Ctrl+наведение (`RoslynCtrlHover`) (`NAVIGATION`) | локальные и параметры — **9**; типы своего solution по имени — **8** (неоднозначно при одноимённых); точно — **11a / 11c** | 0.1.50 (NATIVE): `NativeCSharpNavigation` + `CSharpGotoDeclarationHandler` — локальные, параметры, метки, запросы, параметры типов, члены своего типа и partial-частей, типы solution по имени с `using`; `a.B` и прочее — сервер; Go to Class по stub-индексам |
| Find Usages, `documentHighlight` (`NAVIGATION`, `USAGE_KINDS`) | локальные — **9**; по solution — **11c**; вид использования — **9** | `CSharpUsageKinds`, `CSharpUsageGrouping`, `CSharpHighlightUsages` (0.1.50, NATIVE: локальные по дереву с чтением / записью; остальное — совпадение текста, уступает по `covers`) |
| Rename (`RENAME`), переименование файла с типом (`RoslynFileRename`) | локальные — **9**; публичные члены — **11c** | `RenameFileToTypeIntention`, `MoveTypeToFileIntention`, вето rename |
| Hover / quick doc, Parameter Info (`RoslynParameterInfo`) (`DOCUMENTATION`) | **11c**; перегрузки — **11d**; XML-доки — **10** | сигнатуры статических членов в `AssemblyIndex` |
| Go to Implementation, Type Hierarchy, Go to Super (`RoslynGotoImplementation`, `RoslynTypeHierarchy`, `RoslynGotoSuper` + `RoslynBaseMembers`) | список базовых типов в стабах — **8** (по имени); точные супертипы, включая сборки, — **11a** (+ иерархия типов в индексе, **10**) | члены супертипа уже ищет сканер / модель (`RoslynBaseMembers`), сервер даёт только супертипы |
| Call Hierarchy (`RoslynCallHierarchy`) | **11c** (входящие — Find Usages), исходящие — **11d** | нет |
| Диагностики (pull), проблемы solution (`RoslynSolutionProblems`) (`DIAGNOSTICS`) | синтаксис — **9**; семантика — **11e**; анализаторы — **12** | `BuildProblemsAnnotator`, Build tool window |
| Alt+Enter, Fix All (`RoslynCodeActions`, `RoslynClientCommands`), Alt+Insert (`RoslynGenerateAction`) | синтаксические рефакторинги (`if` ↔ тернарный, block ↔ expression body, extract / inline variable) — **9**; fixes диагностик — **11e**; fixes анализаторов — **12** | `CSharpIntentions`: Adjust Namespace, Move Type to File, Rename File to Type, Add Partial Part, Create Test |
| Смена namespace с usages после Move (`RoslynNamespaceAdjuster`) | объявление — уже; usages — **11c** | `AdjustNamespaceIntention`, `CSharpMoveFile` |
| Форматирование, on-type formatting (`FORMATTING`) | **9**: форматтер платформы по дереву | `resources/csharpIndent/rules.json` + `CSharpIndent`, `CSharpCompleteStatement`, CSharpier / `dotnet format` (`format/`) |
| Workspace symbols (`RoslynWorkspaceSymbols`) | **8** (стабы вместо `CSharpDeclarationIndex`) | `CSharpGotoClassContributor` / `CSharpGotoSymbolContributor` (уступают по `RoslynServerStatus.covers`) |
| Code lens, inlay hints | см. 1.1 | ▶ тестов |
| Декомпилят metadata-as-source (`RoslynDecompiled*`) | часть 2 | IL Viewer |
| Загрузка solution, file watching, restore, статус, Timings | не нужны | `SolutionService`, MsBuildHost |

Вывод: к вехе S без сервера покрываются синтаксические строки (9) и Go to Class / Symbol (8). Основная масса (completion после
точки, переход, usages, rename, quick doc, parameter info, цвета ссылок) — 11a–11c, то есть веха 12.1. Подсказки параметров, лямбды,
`trigger_completion_in_argument_lists` — 11d. Анализаторы и их fixes — только Roslyn.

## 2. Декомпиляция и Source Link без сервера

**Что есть.** `helpers/dotnethelper` (постоянный процесс, протокол `helpers/protocol`) уже ссылается на `ICSharpCode.Decompiler`
11.1.0.9782 (`DotNetHelper.csproj`). Сейчас используется только дизассемблер (`ICSharpCode.Decompiler.Disassembler`, метод `il` —
IL Viewer, `il/IlViewerService` и др.). `CSharpDecompiler` в том же пакете не используется. Там же есть чтение portable PDB
(`Il.cs`, `LoadedAssembly.ReadPdb`): embedded PDB (`EmbeddedPortablePdb`), PDB рядом со сборкой со сверкой id по CodeView,
документы и sequence points методов. Сборка с её PDB читается в память и кэшируется по пути и времени, поэтому новой сборке
файл не блокируется. Пункт ROADMAP «Декомпиляция сборок из Dependencies через `ilspycmd`» не начат. Новая зависимость не нужна:
декомпилятор уже в помощнике.

**Что делает сервер сегодня.** Go to Declaration в тип фреймворка или пакета → `textDocument/definition` отдаёт файл
`%TEMP%/MetadataAsSource/<guid>/DecompilationMetadataAsSourceFileProvider/<guid>/Console.cs` (фикстура
`capture-5.12/14-textDocument_definition_framework_type_metadata_as_source.json`). Сервер этот файл понимает: hover и переход
дальше работают (`tools/roslyn-lsp/README.md`). Плагин только оформляет его: `RoslynDecompiledWritingAccess` (только чтение),
`RoslynDecompiledTabTitle` (`Console.cs [System.Console]`), `RoslynDecompiledBanner` (сборка и версия из `#region Assembly …`,
ссылка на dll). Опции `navigate_to_decompiled_sources` и `navigate_to_source_link_and_embedded_sources` — серверные.
Не проверено: подставляет ли сервер реализацию вместо эталонной сборки (`ref/` содержит только `throw null`). Это видно по
строке `// <путь к dll>` в шапке декомпилята.

**Предложение (по шагам).**
1. *Помощник, метод `decompile {assembly, typeName}`* → `{text, origin, members: [{name, signature, line, column}]}`. Внутри
   `CSharpDecompiler.DecompileType(FullTypeName)` и вывод с позициями объявлений: найти `EntityDeclaration` по аннотации символа,
   как это делает ILSpy; способ получения позиций при печати проверить на 11.1. Шапка — как у Roslyn (`#region Assembly …`,
   `// путь`), тогда `RoslynDecompiledBanner` / `TabTitle` переиспользуются. Их стоит вынести из модуля `roslyn` в основную
   часть: файлы плагина не должны зависеть от content-модуля. Кэш на диске по MVID + имя типа (сборки пакетов неизменны, как индекс).
2. *Эталонная сборка → реализация.* Проекты видят `packs/*.Ref/<ver>/ref/<tfm>` (`ProjectAssemblies.referencePack`). Для
   декомпиляции нужна сборка из `shared/Microsoft.NETCore.App/<ver>/` (`dotnet --list-runtimes`, версия — по ref-пакету). У пакета
   NuGet — `lib/<tfm>` вместо `ref/<tfm>`. Это чистая функция рядом с `ProjectAssemblies`, тестируется на путях.
3. *Source Link и встроенные исходники* (опция «Navigate to Source Link…»). Порядок, как в Rider и Roslyn:
   (а) embedded source в PDB (custom debug info `EmbeddedSource`) — текст прямо из PDB;
   (б) Source Link JSON (custom debug info `SourceLink`): путь документа → URL (`raw.githubusercontent.com/...`);
   (в) иначе — декомпилят.
   PDB искать так: рядом с dll / embedded (уже умеет `Il.cs`) → `.snupkg` / symbol server по SSQP-ключу из CodeView. Для
   фреймворка — `msdl.microsoft.com`, для пакетов — symbol server nuget.org. Документ и строку метода дают sequence points: первая
   точка тела члена (код уже есть в `Il.cs`). Сеть — только из помощника (.NET `HttpClient` берёт прокси системы и `nuget.config`;
   из JVM на корпоративной машине `UnknownHostException`, см. память «Corporate NuGet feed»). Скачанное кэшировать на машину;
   включать опцией на странице Debugger или Navigation. Она же нужна отладчику: `debugExternalSource` сейчас выключен с пометкой
   «нет декомпилятора» (`DotNetSettings`).
4. *Переход по имени типа — сразу, без семантики.* Из Go to Class по сборкам (часть 3) и из Dependencies в окне Solution:
   типу из `AssemblyIndex` известны namespace, имя, арность и сборка — этого хватает для `decompile`. Из кода по имени типа
   переходить после **8** (стабы своего solution) и **10** (список сборок проекта), с выбором при неоднозначности: тип с таким
   простым именем ищется в своих стабах, потом в индексах сборок проекта, с учётом `using` файла (синтаксис **9**). Это
   эвристика до 11a, ошибается на одноимённых типах.
5. *Переход по символу* — после **11a / 11c**. Резолвер отдаёт символ сборки (тип + член + сигнатура). Член ищется в
   `members` ответа `decompile`, перегрузки различаются по сигнатуре. Внутри декомпилята свой PSI работает как в обычном файле:
   переход дальше — тем же путём.
6. Пока сервер есть: выбор по `CSharpFeature.NAVIGATION`. При ROSLYN декомпилят даёт сервер, при NATIVE и без сервера — помощник.

Стоимость: помощник (1) + (2) — 1 сессия; Source Link (3) — 1–2 сессии; Go to Class → декомпилят (4) — вместе с частью 3.
Риски: позиции членов в выводе декомпилятора; размер декомпилята больших типов (`Enumerable` — десятки тысяч строк, нужен ленивый
PSI-разбор, он уже есть); лицензия — `ICSharpCode.Decompiler` MIT, уже в помощнике.

## 3. Go to Symbol по `AssemblyIndex` — сразу, без PSI

**Что хранит индекс** (`indexer/README.md`, читатель — `index/AssemblyIndex.kt`, формат 1, файл `<mvid>.dnix` на сборку):
- публичные типы, включая вложенные (`Dictionary.Enumerator`): namespace, имя, вид (class / struct / interface / enum /
  delegate / static class), арность, `[Obsolete]`;
- публичные **статические** члены: методы, extension-методы, свойства, поля, константы, значения enum — с возвращаемым типом и
  параметрами;
- **нет**: экземплярных членов, конструкторов, операторов, документации, имён параметров типа, базовых типов, пути к dll;
- `[EditorBrowsable(Never)]` в индекс не попадает.

Поиск идёт по таблице имён, отсортированной по нижнему регистру: `types(prefix)` / `members(prefix)` двоичным поиском, файл
отображён в память. Объём: SDK 10 — 3 779 типов и 25 938 членов (1,7 МБ); ASP.NET — 2 997 и 3 311. Solution `debug-playground` —
317 разных сборок, 2 МБ. Поиск префикса по SDK — 0,2–2,9 мс. Список сборок проекта — `AssemblyIndexService.indexes(projectFile)`
(по `project.assets.json`, строится в фоне при открытии проекта). Сейчас из индекса берутся только члены (`ImportCompletion`).
`types()` не вызывается нигде.

**Как выглядит контрибутор.** `CSharpLibraryGotoClassContributor` (только типы) и `…GotoSymbolContributor` (типы + статические
члены), `ChooseByNameContributorEx, DumbAware` в основной части, рядом с `CSharpGotoContributor`:
- `processNames`: только если `FindSymbolParameters` / scope просит не только проектные элементы (галка «Include non-project
  items», как у библиотек в Java). Имена — новый метод `AssemblyIndex.names()`: проход по таблице имён без декодирования лишнего.
  Сопоставление с запросом делает платформа;
- `processElementsWithName`: `types(name)` / `members(name)` с фильтром по точному имени по всем индексам solution (нужен
  `AssemblyIndexService.all()`);
- элемент — свой `NavigationItem` (не PSI): текст `Dictionary<,>` или `WriteLine(string, params object[])`. Имён параметров типа в
  индексе нет, поэтому `<,>` или `` `2 ``. Location — `System.Collections.Generic (System.Collections)`, иконка по виду, устаревшее
  зачёркнуто, перегрузки члена — одной строкой (`+17 overloads`), как в `ImportItem`;
- переход: декомпилят из части 2 (шаг 4 там). Пока помощника `decompile` нет, переходить некуда. IL Viewer показывает IL
  строки *своего* исходника по PDB, произвольный член сборки он не умеет; метод `il` пришлось бы расширять. Поэтому выпускать
  контрибутор вместе с `decompile`, а не раньше. Для промежуточной версии можно открывать «Show Assembly» (папку dll), но это
  мало что даёт.

**Стоимость.** Индекс и его читатель есть. Нужно: `names()` в `AssemblyIndex`, `all()` и сохранение пути сборки в
`AssemblyIndexService` (сейчас `refresh` выбрасывает связь сборка → индекс из `IndexerTool.index`), два контрибутора и
`NavigationItem` — ≈200–300 строк. Тесты — на фикстурах `src/test/resources/index/System.Console.dnix` / `System.Linq.dnix`
(`set(projectFile, indexes)` уже есть для тестов). Формат индекса менять не нужно. С декомпиляцией — ещё сессия (часть 2, п. 1–2).

**Риски.**
- *Объём.* Сотни сборок, десятки тысяч имён на solution: для ChooseByName это немного (JDK в Java больше). Но `processNames`
  проходит все таблицы имён: декодировать лениво и отдавать имя один раз (`distinct` по всем индексам). Замерить на solution с
  ASP.NET + EF.
- *Дубли между сборками и проектами.* Один пакет разных версий в двух проектах — два MVID, два индекса, одинаковые типы. Polyfill-
  пакеты определяют тот же тип (`IAsyncEnumerable` в `Microsoft.Bcl.AsyncInterfaces`). Схлопывать по (qualifiedName, арность,
  имя сборки) и брать новейшую версию; разные сборки показывать отдельными строками с именем сборки в location. Type forwards
  (`netstandard.dll`) индексатор, по README, не пишет — не проверял, стоит проверить на фикстуре `netstandard`.
- *Дубли с сервером.* `RoslynWorkspaceSymbolSupport` превращает символ сервера в своём файле в объявление плагина.
  `RoslynServerStatus.covers` гасит контрибутор плагина для файлов, которые загрузил сервер. К сборкам это не относится:
  `covers` — про исходные файлы. Отдаёт ли `workspace/symbol` Roslyn символы метаданных, не проверено. В снятой фикстуре
  (`capture-5.12/20-workspace_symbol.json`, запрос `Person`) только исходники; опция `symbol_search.dotnet_search_reference_assemblies`
  в VS Code описана как влияющая на add-import, а не на workspace symbols (по памяти, не проверено). Проверить `tools/roslyn-lsp/capture.py`
  запросом `Console` / `WriteLine`. Если сервер их отдаёт (URI `MetadataAsSource`, `RoslynDecompiled.isDecompiledPath`), то при
  готовом сервере строки библиотек показывать только из одного источника — по `CSharpFeature.NAVIGATION`.
- *Неполнота.* Экземплярных членов нет: `List.Add` не найдётся, `Console.WriteLine` найдётся. Это надо честно сказать в
  CHANGELOG. Расширение — п. 4 «Что дальше» `indexer/README.md` (экземплярные члены, требуется и для 11b / 10).
- *Целевая платформа.* Индексы берутся по первой TFM из `project.assets.json`, тулбар не учитывается (README индексатора, п. 1).
  Для навигации это допустимо.
