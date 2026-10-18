# Unity: план поддержки

Действующий план: проекты Unity в IDE на платформе IntelliJ тем же плагином, что и остальной .NET, по образцу Rider. Решение
пользователя 2026-10-17: простая поддержка — узел Unity в панели Solution, completion, скрытие `.meta` и прочего мусора, остальное по
мере надобности. Фреймворк — модулем рядом с ядром (`RAZOR_PLAN.md`, «Модульность»): пакет `frameworks/unity`, дескриптор
`io.github.dotnetsupport.frameworks.unity.xml`, ядро о Unity не знает (`FrameworkBoundaryTest`), новое знание — через точку расширения.

## Как устроен проект Unity (факты, 2026-10-17, Unity 6000.6.0f1, проект `nine-to-fire` пользователя)

- Корень проекта: `Assets/`, `Packages/` (`manifest.json`, `packages-lock.json`), `ProjectSettings/` (`ProjectVersion.txt` —
  `m_EditorVersion: 6000.6.0f1`), `Library/` (1,4 ГБ: `ScriptAssemblies/*.dll` — собранные сборки проекта, `PackageCache/` — исходники и
  dll пакетов), `Temp/`, `Logs/`, `UserSettings/`. Рядом с каждым файлом в `Assets` — `.meta`.
- Редактор Unity генерирует solution и проекты **старого формата** (`<Project ToolsVersion="4.0">`, `TargetFrameworkVersion v4.7.1`,
  `LangVersion 9.0`, `DefineConstants` с `UNITY_6000_6_0;UNITY_5_3_OR_NEWER;…;UNITY_EDITOR`), по одному `.csproj` на assembly definition
  (`Assembly-CSharp`, `Assembly-CSharp-Editor`, `Game.Core`, `FishNet.Runtime`… — 18 в `nine-to-fire`), **все в корне проекта**, с
  явными `<Compile Include="Assets\Game\Boot\X.cs">` и `<ProjectReference>` между собой. Ссылки — `<Reference><HintPath>` с
  **абсолютными путями**: dll редактора `C:\Program Files\Unity\Hub\Editor\<версия>\Editor\Data\Managed\UnityEngine\*.dll` (338 файлов),
  `Library\ScriptAssemblies\*.dll`, dll пакетов из `Library\PackageCache`, и сам фреймворк —
  `Editor\Data\NetStandard\ref\2.1.0\netstandard.dll` и `NetStandard\compat\2.1.0\shims\netfx\mscorlib.dll`, `System.dll`… (314 ссылок у
  `Game.Boot`). `_TargetFrameworkDirectories` стоит заглушкой `non_empty_path_generated_by_unity.rider.package` — reference assemblies
  .NET Framework проекту не нужны, всё приходит по `HintPath`. Внутри `Library/PackageCache` лежат чужие `.sln` (`cecil.sln`).
- `dotnet msbuild <проект>.csproj -t:Compile` на Linux проходит (помощник анализа загрузил каждый проект: 313 сборок, 126 анализаторов
  Unity — `Microsoft.Unity.Analyzers` идут `<Analyzer>` в csproj), по 10 с на проект, помощник 1,2 ГБ на 18 проектов.
- Собирает код сам редактор Unity; `dotnet build` solution не нужен и не ожидается. Отладчик Unity — протокол Mono, не DAP.

## Интеграция с редактором: проба встроенного пакета (2026-10-18, Unity 6000.6.0f1, копия `nine-to-fire`)

Вопрос пользователя: можно ли ставить свой пакет в проект Unity автоматически и управлять открытым редактором из IDE (Build / Play), как
Rider делает через `com.unity.ide.rider`. Проба — пакет `io.github.dotnetsupport.unity` (`package.json`, редакторская asmdef,
`[InitializeOnLoad]`-скрипт: журнал в `Logs/`, команды из файла `Temp/dotnetsupport-command.txt` по `EditorApplication.update`) в копии
проекта `~/unity-probe/nine-to-fire-probe`, редактор открыт пользователем. Факты:
- **Встроенный пакет** (папка в `Packages/`) подхватывается без записи в `manifest.json` — `manifest.json` не меняется. Unity дописывает
  его в `Packages/packages-lock.json` (6 строк, `"version": "file:io.github.dotnetsupport.unity"`, `"source": "embedded"`), создаёт `.meta`
  каждому файлу пакета и `DotNetSupport.Unity.Editor.csproj` в корне (его скрывает обычный `.gitignore` Unity).
- **Без фокуса Unity изменений файлов не видит**: новый пакет и правка его скрипта ждут, пока окно Unity получит фокус (за 60 с — ничего).
  Сам `EditorApplication.update` без фокуса идёт (тик каждые 10 с), поэтому уже загруженный пакет выполняет команды IDE в фоне:
  `AssetDatabase.Refresh()` — правка подхвачена и скомпилирована за 3 с; `RequestScriptCompilation()` — компиляция 0,1 с, перезагрузка
  домена ~2 с; `isPlaying = true` — Play за 2,3 с, `false` — Stop за 0,2 с. Значит, первая установка дойдёт до Unity при следующем
  переходе в его окно, дальше IDE управляет редактором сама.
- После перезагрузки домена `[InitializeOnLoad]` пакета срабатывает 3 раза подряд за ~4 с (в этом проекте) — инициализация канала должна
  быть идемпотентной.
- **Открыт ли редактор**: `Library/EditorInstance.json` (`process_id`, `version`, `app_path`) и `Temp/UnityLockfile` есть, пока он открыт;
  нормальное закрытие их удаляет, аварийное — оставляет (`nine-to-fire` пользователя: файл есть, процесса 10540 нет) — проверять ещё и
  живость процесса. Пока редактор открыт, второй Unity (batchmode: сборка плеера, тесты) проект не откроет.
- Следы в git: папка пакета — неотслеживаемая (скрыть локально через `.git/info/exclude`, `.gitignore` проекта не трогать), строки в
  `packages-lock.json` — изменение отслеживаемого файла. **Решение пользователя (2026-10-18): принять и честно предупреждать** при
  установке, что Unity допишет пакет в `Packages/packages-lock.json`; файл не откатывать.

### Шаги (решение пользователя 2026-10-18: сначала открытый редактор, batchmode пока не трогать)

1. **Пакет и связь с открытым редактором.** Пакет `io.github.dotnetsupport.unity` — исходники в ресурсах плагина (как индексатор), плагин
   кладёт его в `Packages/` проекта Unity по согласию пользователя (один раз на проект; «Не спрашивать»), обновляет при новой версии,
   прячет папку через `.git/info/exclude`. Канал — файлы в `Temp/DotNetSupport/` (переживают перезагрузку домена, без портов; `Temp`
   Unity чистит при закрытии): команды IDE → редактор, состояние редактора (Play / Pause / компиляция, сердцебиение) → IDE. Действия
   Play / Pause / Step / Stop / Refresh (меню .NET и панель инструментов), обновление ассетов при сохранении C# (не в Play mode), статус
   связи в строке состояния. Открыт ли редактор — `Library/EditorInstance.json` + живой процесс.
2. **Лог консоли Unity** в окне IDE (сообщения, предупреждения, ошибки со стеком и переходом к строке), ошибки компиляции редактора.
3. **Unity как внешний редактор кода**: пакет регистрируется через `CodeEditor.Register` — генерация `.csproj` без Rider, двойной клик в
   Unity открывает файл в IDE на строке; выбор в Preferences — только по согласию пользователя.
4. **Тесты в открытом редакторе** (Test Runner: EditMode / PlayMode) в окне Unit Tests.
5. Позже: batchmode при закрытом редакторе (сборка плеера, тесты), отладчик (Mono).

## Сделано

- [x] 0.1.272 — шаг 3 «IDE как внешний редактор Unity»: пакет 1.2.0, `ExternalEditor.cs` — `IExternalCodeEditor` через
  `CodeEditor.Register` (в Preferences | External Tools — «IntelliJ IDEA 2026.1.4 (C# Project Support)», путь — лаунчер IDE из
  `ide.json`, запомнен в EditorPrefs на время, пока IDE закрыта); `OpenProject` — при живом `ide.json` команда в
  `Temp/DotNetSupport/ide-commands/` + `AllowSetForegroundWindow(pid IDE)` на Windows, иначе запуск лаунчера с папкой проекта,
  `--line` / `--column` и файлом; генерация проектов (`SyncIfNeeded` / `SyncAll`, кнопка «Regenerate project files» в OnGUI) —
  генератором пакета Rider (`Packages.Rider.Editor.ProjectGeneration.ProjectGeneration`, internal — рефлексией), иначе Visual Studio
  (`GeneratorFactory.GetInstance(Legacy)`); без них — предупреждение на странице настроек. Выбор — только по согласию: уведомление один
  раз («Use This IDE» / «Don't ask again») и действия меню .NET «Open Scripts from Unity in This IDE» / «Stop Opening Scripts from Unity
  Here» (команды `use-as-editor` / `restore-editor`; настройка Unity общая на машину, прежний редактор хранится в EditorPrefs). IDE:
  `UnityExternalEditor` (`ide.json` раз в 2 с, пока редактор открыт, — `Temp` чужой, IDE его без Unity не создаёт; команды 4 раза в
  секунду → `OpenFileDescriptor` + `ProjectUtil.focusProjectWindow`), `ideIsEditor` в состоянии. Проверено роботом с открытым Unity на
  копии `nine-to-fire`: `use-as-editor` → Unity записал IDEA в `kScriptsDefaultApp`; `open-asset Assets/Game/Boot/GameBootstrap.cs 57`
  (команда для проверки: `AssetDatabase.OpenAsset`, тот же путь, что двойной клик) → в IDE `GameBootstrap.cs 57:1`; новый скрипт → в
  `Game.Boot.csproj` через 5 с, удалённый — убран через 5 с (генератор Rider); `restore-editor` → снова Rider; уведомление показано;
  0 SEVERE. Тест `UnityExternalEditorTest`. Не проверено: настоящий двойной клик в окне Unity и вывод окна IDE на передний план
  (робот окно Unity не трогает), запуск закрытой IDE лаунчером, macOS / Linux. Человеком вживую не проверено.


- [x] 0.1.271 — шаг 2 «лог консоли Unity»: пакет 1.1.0, `ConsoleLog.cs` — `Application.logMessageReceivedThreaded` (сообщения
  с любого потока в очередь, запись из `EditorApplication.update`, перед перезагрузкой домена и при выходе) и сообщения компилятора из
  `CompilationPipeline.assemblyCompilationFinished` (с кодом и местом; их же текстовые копии из лога отбрасываются по виду
  `file(l,c): error CSxxxx:`), маркеры `compile-started` и `play-started` (на `ExitingEditMode`, до `Awake`), файл
  `Temp/DotNetSupport/console.jsonl` с первой строкой `start` (id файла, pid редактора), заново при 8 МБ. IDE: `UnityConsole`
  (разбор, чтение с места остановки с недописанной строкой и началом заново, места стека, переход, `Packages/<имя>/…` →
  `Library/PackageCache/<имя>@<hash>/…`, свёртка), `UnityConsoleService` (опрос 2 раза в секунду, до 20 000 сообщений, другой pid —
  очистка, компиляция убирает ошибки прошлой, Clear on Play), окно **Unity** → «Log» (`UnityLogToolWindow`: список, детали со ссылками,
  фильтры с числами, Collapse, поиск, Clear, Clear on Play, Autoscroll; доступно, как только solution оказался Unity), «Show Unity Log»
  в меню .NET и во всплывающем меню строки состояния. Заодно: старая версия пакета, которую ещё держит редактор, получает `refresh`
  после замены файлов — Unity берёт новую без фокуса (проверено: 1.0.0 → 1.1.0 за секунды). Проверено роботом с открытым Unity на
  копии `nine-to-fire`: сообщение редактора, Play → Clear on Play убрал его, пришли `[Boot]` игры со стеком; ошибка в `HealthRules.cs`
  → две ошибки CS1525 / CS1002 с местом, без дублей; Enter на ошибке → `HealthRules.cs 34:24`, на сообщении → `GameBootstrap.cs 127`;
  исправление → ошибки ушли; снимок `build/ui-robot/unity-log.png`. Тест `UnityConsoleTest`. Человеком вживую не проверено.
  Находки: в копии проекта с чужим `Library` стек называет файлы исходного проекта абсолютными путями (пока скрипт не
  перекомпилирован) — переход берёт первое место внутри проекта; Unity генерирует `DotNetSupport.Unity.Editor.csproj` для встроенного
  пакета (галочка «Embedded packages» генератора включена по умолчанию) — проект пакета виден в solution (не исправлено).


- [x] 0.1.270 — шаг 1 «пакет и связь с открытым редактором»: пакет `io.github.dotnetsupport.unity` (`unity-package/`, в ресурсах
  плагина; `[InitializeOnLoad]` `IdeConnection`, опрос команд из `EditorApplication.update` каждые 0,2 с, `state.json` руками — модуль
  JsonUtility может быть выключен), `UnityEditorPackage` (установка с сохранением `.meta`, обновление молча, `.git/info/exclude`),
  `UnityEditorLink` / `UnityEditorConnection` (статусы, опрос раз в секунду, сердцебиение 6 с), действия Play / Pause / Step / Refresh
  Assets / Install Editor Package (меню .NET, панель инструментов), виджет строки состояния, `refresh` после сохранения C# в IDE.
  Проверено вживую на копии `nine-to-fire` с открытым Unity 6000.6 (робот, Unity без фокуса): Play / Pause / Step / Resume / Stop,
  сохранение → компиляция в Unity (~16 с), число ошибок компиляции в строке состояния. Находки и исправления того же выпуска:
  - Unity без фокуса тикает `update`, но изменений файлов не видит — IDE шлёт `refresh` сама;
  - индексатор падал на атрибуте с аргументом `typeof` = `null` (сборка пакета Unity): `CustomAttributeDecoder` отдаёт имя `null`
    (509 / 509 сборок после исправления, фикстура `TaggedWithoutType`);
  - исходники пакетов в `Library/PackageCache` брали первый проект папки и его `#if` (и при новом проекте Unity — другие символы):
    «PSI and index do not match» сотнями. Теперь файл в папке, которую пишет Unity (`SolutionLayout.isGeneratedDirectory`), без
    проекта, перечисляющего его, ничей; ключи `#if` / версии языка сохраняются атрибутом файла (`CSharpSavedParseOptions`), и первый
    разбор нового сеанса совпадает с индексом (второй запуск песочницы — 0 SEVERE);
  - переименованный скрипт пропадал из дерева на секунды (Unity переписывает `.csproj` через ~1 с, IDE замечала через 14): по смене
    состояния связи IDE обновляет `*.csproj` / `*.sln` корня, а переносы и переименования самой IDE сразу видны в дереве проекта
    старого формата (`MsBuildEvaluation.moved`, пока `.csproj` не догонит; замер: новое имя в дереве в момент переименования);
  - NuGet для проекта Unity недоступен (`SolutionLayout.nuGetUnavailable`): окно объясняет почему, меню NuGet выключено, автовосстановления нет.
  Тесты `UnityEditorLinkTest`, `ProjectsInOneFolderTest`, `MsBuildEvaluationTest`, `AssemblyIndexTest`, `UnityProjectsTest`.
  Человеком вживую не проверено: действия на панели инструментов и виджет глазами; переименование через Shift+F6 (робот переименовывал
  через VFS — `.meta` за ним не шёл, GUID в копии вернул из git).


- [x] 0.1.254 — два бага ядра, на которых Unity не работал вовсе (найдены песочницей WSL на копии `nine-to-fire`):
  - абсолютный `HintPath` склеивался с папкой проекта (`ProjectAssemblies.hintPaths`), все 314 ссылок отбрасывались как
    несуществующие, индексатор не запускался — ни одного типа Unity, семантика молчала (ссылки «неполные»). Теперь абсолютный путь
    берётся как есть: индекс 18 проектов / 509 сборок за 7 с, `Vector3`, `MonoBehaviour`, `List<T>` находятся, completion по
    `Vector3` (53 члена), Quick Documentation `T Component.GetComponent<T>()`, 0 ложных ошибок в `DebugHotkeys.cs`;
  - несколько `.csproj` в одной папке: `DotNetProjects.findOwningProject(file)` брал первый проект папки — панель Solution показывала
    под `Game.Boot` файлы `Game.Net`, над файлом висел баннер «Not a part of Game.Net.csproj». Теперь среди проектов папки выбирается тот,
    чьи явные `Compile` перечисляют файл (`DotNetProjects.compileItems`, статическое чтение с кэшем по штампу файла), панель берёт
    проект узла-предка (`ProjectContentNodes`), баннер — `CompilationModel.projectOf`. Тест `ProjectsInOneFolderTest`.
  Что уже было: solution из 18 проектов открывается, в дереве только `.cs` и `.asmdef` (явные `Compile`), `Dependencies` → «.NET
  Framework 4.7.1, not restored» с Projects и Assemblies, предупреждения анализаторов Unity в редакторе (UAC0009), define-символы
  `UNITY_*` из csproj.

- [x] 0.1.255 — модуль `frameworks.unity` (пакет `frameworks/unity`, дескриптор `io.github.dotnetsupport.frameworks.unity.xml`):
  проект Unity = `ProjectSettings/ProjectVersion.txt` + `Assets/` (`UnityProjects`); узел solution с иконкой Unity и версией редактора
  («Unity 6000.6.0f1 · 18 projects», `UnitySolutionDecorator`); `Library`, `Temp`, `Logs`, `obj`, `UserSettings` не показываются,
  `.meta` — по переключателю «Show .meta Files» в меню панели (`UnityTreeStructureProvider`, `ShowMetaFilesAction`); новая точка
  расширения ядра `solutionLayout` — искатель solution'ов не заходит в сгенерированные папки (`.sln` из `Library/PackageCache` не
  предлагаются); ПКМ → New: после разделителя «Unity» — MonoBehaviour Script, ScriptableObject, Editor Script, Property Drawer, Editor
  Window, Assembly Definition (`UnityNewItemGroup`, шаблоны `dotnetTemplates/unity/`, рендер как у остальных C#-шаблонов: namespace
  папки, `using UnityEngine;`); «Open in Unity Editor <версия>» в меню .NET и ПКМ панели (`OpenInUnityAction`: редактор по версии из
  `ProjectVersion.txt` в `Hub/Editor/<версия>`, Windows / Linux / macOS, иначе уведомление). Папка среди нескольких `.csproj` относится к
  проекту, у которого есть её файлы (Build 'Game.Boot' у папки `Assets/Game/Boot`, не 'Game.Net'). Тест `UnityProjectsTest`; проверено
  в песочнице WSL на `nine-to-fire`: узел, меню New, «Open in Unity Editor 6000.6.0f1» (снимки `build/ui-robot/unity*.png`).

- [x] 0.1.256 — шаг 2, семантика:
  - **«IDE error occurred» при открытии** (`UpToDateStubIndexMismatch` / `Stub count (214) doesn't match stubbed node length (215)` на
    файлах с множеством `#if`: `Assets/FishNet/Runtime/Managing/NetworkManager.cs`, `InputControl.cs` пакета Input System, 12 раз за
    сеанс). Причина в ядре: `CSharpParseOptions` клал символы `#if` и версию языка на `VirtualFile` в фоновом read action, а заново
    разбирал PSI позже на EDT; у Unity 18 проектов оцениваются один за другим, набор символов файла меняется несколько раз, и проход
    подсветки между этими моментами загружал AST stub-PSI с новыми символами против стабов старых. Теперь фон только вычисляет, а
    ключи, `requestReindex` и `reparseFiles` идут на EDT одним write action (`applyPending`): ни один read action не видит новые ключи
    со старым PSI. Песочница после чистки индексов: 0 SEVERE, 0 несовпадений стабов. Тест `CSharpParseOptionsTest.testTheKeysArePutOnlyWithTheReparse`.
  - **Неявные использования**: новая точка расширения ядра `csharpImplicitUsage` (`lang/CSharpImplicitUsage`) — линза использований
    вместо «no usages» показывает то, что ответит модуль. Реализация Unity (`UnityImplicitUsage`, таблица `UnityMessages` по базовым
    классам: `MonoBehaviour`, `ScriptableObject`, `StateMachineBehaviour`, `Editor`, `EditorWindow`, `AssetPostprocessor`,
    `AssetModificationProcessor`): «Event function» над сообщениями (`Update`, `OnTriggerEnter`…; не `override`), «Set by Unity» над
    сериализуемыми полями (`[SerializeField]` / `[SerializeReference]`, публичные не `static` / `const` / `readonly` поля компонентов
    и классов `[Serializable]`, без `[NonSerialized]`), «Called by Unity» над методами `[MenuItem]`, `[ContextMenu]`,
    `[RuntimeInitializeOnLoadMethod]`, `[InitializeOnLoadMethod]`…, «Unity script» над классами-наследниками. Наследование — по
    семантике плагина (исходники и индекс сборок), с кэшем до изменения PSI. Тест `CSharpCodeLensTest.testWhatUnityUsesWithoutCodeIsNotNoUsages`;
    в песочнице над `DebugHotkeys` — «Unity script», над `Update()` — «Event function».
  - **Namespace папки** считается от `.asmdef` над ней, как у Unity и Rider (`SolutionLayout.namespaceFor`): `Assets/Game/Boot` с
    `Game.Boot.asmdef` — `Game.Boot` (было `Game.Boot.Assets.Game.Boot`, и проверка «namespace не совпадает с папкой» ругалась на
    каждый файл); без `.asmdef` — от `Assets`, папки `Scripts` не в счёт. Новые файлы проекта с `LangVersion` < 10 получают блочный
    namespace (`isFileScopedPreferred`). Символы `#if` Unity (151, `UNITY_EDITOR`, `UNITY_6000_6_OR_NEWER`) и C# 9 доходят до парсера
    (проверено пробой `parse_keys.js`).

- [x] 0.1.257 — шаги 3 и 4:
  - **Completion событий** (`UnityEventFunctionCompletion`, как «Generate Unity event functions» Rider): в теле класса-наследника базы
    Unity, в начале члена (строка — отступ, модификаторы, начало имени; без тела метода, списка аксессоров, аргументов, параметров,
    инициализатора, `=>` между ними) — сообщения этой базы, которых в классе ещё нет, без устаревших сетевых Unity 4, с параметрами
    (`OnTriggerEnter(Collider other)`, `OnPostprocessAllAssets(…)` static, `OnWillDeleteAsset` с возвращаемым `AssetDeleteResult`) и
    подписью «event function»; Enter пишет метод с телом и кареткой внутри (`return false;` / `return 0;` / `return default;` у не-void),
    набранный модификатор доступа сохраняется, иначе `private`. Тест `UnityCompletionTest`; в песочнице на `GameBootstrap.cs` —
    настоящий набор `OnTrigEn` + Enter (снимки `build/ui-robot/unity9.png`, `unity10.png`). Атрибуты (`[SerializeField]`, `[Header]`,
    `[Range]`) completion типов уже предлагает из индекса сборок редактора.
  - **Только `.sln` корня**: `SolutionLayout.isForeignSolution` — `.sln` глубже корня проекта Unity (`Assets/FishNet/…/Mono.Cecil.sln`)
    искатель не берёт: нет попапа «Select Solution» и уведомления «Several solutions».
  - **Namespace папки** берёт `rootNamespace` самого `.asmdef` (папка с `.asmdef`, для которой Unity ещё не сгенерировал проект).
  - Площадка: `debug-playground/Unity` (маленький проект в формате генератора, маркеры `UNITY:*` и `TYPE:unity-event-function`).

- [x] 0.1.258 — недорогие «плюшки», как в Rider (решение пользователя 2026-10-17: «добавим плюшки, которые не дорого реализовывать»):
  - **инспекции** (`UnityInspections.kt`, группа C# | Unity): `UnityObjectNullPropagation` (`?.`, `??`, `??=` на наследнике
    `UnityEngine.Object`), `UnityCompareTag` (исправление на `CompareTag`, `!` для `!=`), `UnityRedundantSerializeField` (static / const
    / readonly; исправление убирает атрибут), `UnityEventFunctionSignature` (регистр имени — переименовать; тип параметров — записать
    сигнатуру Unity; сообщение без параметра Unity тоже вызывает — не ошибка), `UnityExpensiveCallInFrame` (`GetComponent*`,
    `Find*ObjectOfType`, `GameObject.Find`, `FindWithTag`, `SendMessage`, `AddComponent` в `Update` / `FixedUpdate` / `LateUpdate` /
    `OnGUI` не через лямбду; для `GetComponent<T>()` своего объекта — «Introduce a field and initialize it in 'Awake'», `Awake` создаётся,
    если его нет), `UnityGetComponentType` (`T` не компонент и не интерфейс; у `AddComponent` — не компонент), `UnityUnknownName`
    (тэг, слой, сцена, ось, которых нет в `ProjectSettings`);
  - **значок Unity** у событий в gutter (`UnityEventFunctionLineMarkerProvider`), у кадровых — «called every frame»;
  - **completion строк** (`UnityStringCompletion`, `UnityProjectSettings`: `TagManager.asset` + встроенные тэги, `EditorBuildSettings.asset`,
    `InputManager.asset` как текст YAML, кэш до изменения файлов) и **компонентов в `GetComponent<`** (`UnityComponentCompletion`:
    наследники `MonoBehaviour` проекта по stub-индексу базовых списков, затем частые компоненты `UnityEngine`);
  - **`.meta` следует за файлом** при Rename / Move (`UnityMetaFilesListenerProvider`, слушатель рефакторинга; у цели уже есть свой
    `.meta` — не трогаем);
  - **шаблоны** `liveTemplates/Unity.xml` (контекст `UNITY_MEMBER` — член типа в проекте Unity).
  Тесты `UnityInspectionsTest`, `UnityExtrasTest`; сценарии — `debug-playground/Unity/Assets/Scripts/Checks.cs`.

- [x] 0.1.259 — `.asmdef` / `.asmref` (вопрос пользователя 2026-10-17 «что ещё для удобства, Rider умеет читать из meta файлов»):
  отдельный модуль `frameworks.unityjson` (зависит от плагина JSON, которого может не быть — без него выключается только эта часть):
  оба расширения — JSON, у `.asmdef` своя схема (`resources/unityjson/asmdef.schema.json`: ключи, типы, описания для Ctrl+Q);
  строки `"references"` (у `.asmref` — `"reference"`) — ссылки на `.asmdef` проекта по `name` или по `"GUID:<guid>"` из `.asmdef.meta`:
  completion, Ctrl+B, инспекция `UnityAssemblyDefinitionReference` на несуществующую. Тест `UnityAssemblyDefinitionsTest`, сценарий —
  `debug-playground/Unity/Assets/Gameplay/Editor/Demo.Gameplay.Editor.asmdef`.

- [x] 0.1.260 — индекс ассетов, как в Rider (решение пользователя 2026-10-17 «Приступай»):
  - **индекс** (`frameworks.unity`, `UnityAssetIndex.kt`, без YAML): `UnityAssetParser` читает текст сцен, префабов, ассетов, анимаций
    построчно (`--- !u!<класс> &<id>`, свойства верхнего уровня, вложенное — только нужное): компонент скрипта (`m_Script` документа
    `MonoBehaviour`, кроме `stripped`) с именем GameObject и полями после `m_EditorClassIdentifier`, вызов `UnityEvent` (`m_MethodName`
    с целью: GUID скрипта компонента `m_Target` в том же файле, иначе `m_TargetAssemblyTypeName`), событие анимации (`functionName`
    документа `AnimationClip`). `UnityAssetUsageIndex` — ключи `s:<guid>` / `m:<метод>`, вход по расширениям, без лимита размера
    (сцены — мегабайты); `UnityMetaGuidIndex` — GUID `.meta` → файл;
  - **линзы** (`UnityAssetUsages`, новый метод `usageNote` точки `csharpImplicitUsage`): «Used in Boot.unity» / «Used in 3 assets» над
    классом, «Event handler in …» / «Animation event in …» над методом, «Set by Unity: 5» / «Set by Unity: 3 values in …» над полем;
    при использованиях в коде — «2 usages, used in Boot.unity»;
  - **`[FormerlySerializedAs("old")]`** при переименовании сериализуемого поля, значение которого записано в ассетах, и
    `using UnityEngine.Serialization;` (новая точка ядра `csharpRenameParticipant`: вставки считаются вместе с планом переименования
    и пишутся той же командой);
  - **модуль `frameworks.unityyaml`** (зависит от плагина YAML и модуля `frameworks.unity`): сцены, префабы, ассеты и `.meta` — YAML по
    содержимому (`fileTypeDetector`: заголовок `%YAML` с тэгом Unity, `.meta` с `fileFormatVersion:`; расширения не регистрируем —
    `.asset`, `.mat` бывают чужими), ошибки плагина YAML в них не показываются (формат Unity, `stripped`); `guid:` — ссылка на скрипт
    (его класс) или ассет, `m_MethodName:` — на метод; Find Usages класса, метода, поля находит их в ассетах (группа «Usage in Unity
    assets»); инспекция `UnityMissingScript` — `m_Script` скрипта с GUID, которого нет ни у одного `.meta` (только когда
    `Library/PackageCache` проиндексирован, иначе скрипты пакетов выглядели бы пропавшими).
  Инспекции самого плагина YAML (`YAMLUnusedAnchor`: «Anchor &1100 is never used» на каждом объекте) в файлах Unity выключены
  (`UnityYamlInspectionSuppressor`).
  Тест `UnityAssetsTest`; сценарий — `debug-playground/Unity/Assets/Scripts/Door.cs` со сценой `Assets/Scenes/Boot.unity` и анимацией
  `Assets/Animations/DoorOpen.anim`. Проверено в песочнице WSL (копия площадки, пути `HintPath` переписаны на `/mnt/c`): линзы, Find
  Usages с группой «Usage in Unity assets», сцена без красного и шума, Ctrl+B из `m_MethodName`, «Missing script», переименование
  `openSpeed` с атрибутом и `closeDelay` без него.
  Найдено в песочнице (ошибка ядра, исправлена в том же выпуске): модуль, который `DotNetModuleSetup` делает для папки в IDEA,
  пропадал через 4 с — модель проекта, загруженная из кэша, синхронизируется с `.idea`, где модуля ещё нет (IDE закрыли без
  сохранения); через 5 минут проверка платформы выкидывала файлы из индексируемых, и индексы плагина, Find Usages и инспекции не-C#
  файлов замолкали. Теперь модуль сразу сохраняется, а активность минуту следит и создаёт его снова, если синхронизация его убрала.
  Пробы робота: `modules_probe.js`, `solution_finder_probe.js`, `meta_guid_probe.js`, `missing_script_probe.js`.
  Не сделано: переопределения полей в экземплярах префабов (`m_Modifications` с `propertyPath`), значения вложенных полей и списков,
  обработчики `UnityEvent` из базового класса, бинарные ассеты.

## Известные мелочи

- Build / Run у solution Unity — кнопки на месте. `dotnet build` проекта Unity работает (старый формат, все ссылки по `HintPath`) и
  полезен как проверка ошибок; запускает игру редактор («Open in Unity Editor»). Оставлено как есть.
- Вставленный метод события занимает пустую строку, на которой набирали: отделяющая пустая строка до следующего члена не добавляется.

## Дальше — по порядку (модуль `frameworks.unity`)

1. ~~Распознавание, панель Solution, New → Unity~~ — 0.1.255. Осталось из него: уведомление «C# solution build scripts found» на чужие
   `.sln` в `Assets` (сторонние пакеты кладут свои solution'ы в `Assets/...`, как `Mono.Cecil.sln` у FishNet) — пока показывается;
   Build / Run для solution Unity — кнопки на месте, `dotnet build` проекта Unity не нужен (собирает редактор). New → Razor тем же
   приёмом — `RAZOR_PLAN.md`, R9. Проект Unity = `ProjectSettings/ProjectVersion.txt` + `Assets/`. Узел solution с
   иконкой Unity и версией редактора; при Show All Files скрывать `Library`, `Temp`, `Logs`, `obj`, `UserSettings`, `*.csproj.user`,
   `.meta` (переключатель «Show .meta files» в меню панели, как в Rider); `.asmdef` с иконкой. Уведомления «C# solution build scripts
   found» и «Several solutions» не считать `.sln` внутри `Library/PackageCache`. Кнопки Build / Run для solution Unity — спрятать или
   заменить на «Open in Unity» (редактор по `ProjectVersion.txt` через Unity Hub: `Hub/Editor/<версия>/Editor/Unity.exe -projectPath`).
2. ~~Семантика~~ — 0.1.256. Серых «неиспользуемых» членов плагин не рисует (только локальные и недостижимый код), поэтому
   подавление свелось к линзе; если появится серый для членов — спрашивать ту же `csharpImplicitUsage`.
3. ~~Completion и генерация~~ — 0.1.257. Дальше по желанию: `GetComponent<T>` — T из компонентов; строки осей `Input`, тэгов и
   слоёв из `ProjectSettings/InputManager.asset` / `TagManager.asset`; отладчик Unity (Mono) — отдельный большой шаг.
4. **Проверка** без площадки в репозитории: проекты Unity лежат у пользователя (`~/My project`, `~/nine-to-fire`, `~/TOP DOWN SHOOTER`);
   копия для песочницы WSL — `tools/ui-robot/wsl/copy-unity.sh` (переписывает пути `HintPath` на `/mnt/c/...`, так же как сделал бы
   Unity под Linux), пробы — скрипты робота `index_probe.js`, `msbuild_probe.js`, `references_probe.js`, `errors_at.js`,
   `complete_at_line.js`, `doc_at.js`, `lenses.js` (линзы файла), `parse_keys.js` (символы `#if` и версия языка файла). Перед проверкой
   «IDE error» — убрать `~/robot/sandbox/log/idea.log` и `system/{caches,index}` песочницы, иначе считаются старые ошибки. Тесты — на
   статике (`ProjectsInOneFolderTest`, `UnityProjectsTest`, `UnityCompletionTest`, Unity-случай `CSharpCodeLensTest`): настоящий
   Unity в тестах не нужен. Живой набор в песочнице: `caret_at.js` ставит каретку и фокус, затем `screen.sh type` / `key`.
5. Не делать (пока): отладчик Unity (Mono), шейдеры, UXML/USS, профайлер, `.unity`/`.prefab` (YAML — платформа и так подсвечивает).
