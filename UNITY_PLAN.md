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

## Сделано

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
