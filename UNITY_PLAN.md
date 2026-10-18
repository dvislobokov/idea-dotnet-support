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

Состояние на 2026-10-18: шаги 1–4 сделаны (0.1.270–0.1.273, подробности — «Сделано»), проверены роботом на одной копии `nine-to-fire`
с Unity 6000.6 под Windows, человеком вживую — нет. Шаг 5 не начат (решение пользователя: пока не трогать).

1. [x] 0.1.270 — **Пакет и связь с открытым редактором.** Пакет `io.github.dotnetsupport.unity` — исходники в ресурсах плагина (как индексатор), плагин
   кладёт его в `Packages/` проекта Unity по согласию пользователя (один раз на проект; «Не спрашивать»), обновляет при новой версии,
   прячет папку через `.git/info/exclude`. Канал — файлы в `Temp/DotNetSupport/` (переживают перезагрузку домена, без портов; `Temp`
   Unity чистит при закрытии): команды IDE → редактор, состояние редактора (Play / Pause / компиляция, сердцебиение) → IDE. Действия
   Play / Pause / Step / Stop / Refresh (меню .NET и панель инструментов), обновление ассетов при сохранении C# (не в Play mode), статус
   связи в строке состояния. Открыт ли редактор — `Library/EditorInstance.json` + живой процесс.
2. [x] 0.1.271 — **Лог консоли Unity** в окне IDE (сообщения, предупреждения, ошибки со стеком и переходом к строке), ошибки
   компиляции редактора.
3. [x] 0.1.272 — **Unity как внешний редактор кода**: пакет регистрируется через `CodeEditor.Register`, двойной клик в Unity открывает
   файл в IDE на строке; выбор в Preferences — только по согласию пользователя. Отступление от замысла: `.csproj` генерирует не свой код,
   а генератор пакета Rider (рефлексией), иначе Visual Studio — своего генератора нет.
4. [x] 0.1.273 — **Тесты в открытом редакторе** (Test Runner: EditMode / PlayMode) в окне Unit Tests. PlayMode вживую не проверен.
5. [~] Отладчик (Mono): 0.1.274 — режим Debug и клиент протокола, 0.1.275 — Attach to Unity Editor, точки, кадры, шаги (решение
   пользователя 2026-10-18 «Продолжай»), 0.1.276 — переменные («Продолжай, делай переменные»), 0.1.277 — вычисления в рантайме. Дальше —
   условия и счётчики точек (вычислитель готов), исключения, отладка тестов, игра (плеер); проверка на Unity 2023 (`~/test-dubugger-old`).
   Позже: batchmode при закрытом редакторе (сборка плеера, тесты).

### Отладчик: проба протокола (2026-10-18, Unity 6000.6.0f1, копия `nine-to-fire`)

Замысел (вопрос пользователя 2026-10-18): свой клиент протокола Mono Soft Debugger на Kotlin внутри плагина, без внешнего бинарника,
вторым бэкендом пакета `debugger` рядом с `DapConnection`; образец — `Mono.Debugger.Soft/Connection.cs` (`mono/debugger-libs`, MIT).
Проба — питоновский скрипт (рукопожатие, `VM.VERSION`, `SET_PROTOCOL_VERSION`, `ALL_THREADS`, `THREAD.GET_NAME`, `VM.DISPOSE`). Факты:
- Редактор слушает `127.0.0.1:<56000 + pid % 1000>` (pid 28276 → 56276); свой порт по той же формуле у каждого процесса Unity, в том
  числе у дочерних (импорт ассетов) — брать pid из `Library/EditorInstance.json`.
- Рукопожатие `DWP-Handshake` — сразу; рантайм `mono 6.13.0 (Visual Studio built mono)`, протокол **2.58**. Сразу после рукопожатия
  агент шлёт составное событие `VM_START` (политика остановки NONE — редактор не останавливается). 28 управляемых потоков, весь обмен — 1 с.
- **В режиме Release** (Code Optimization; на старте — `ScriptDebugInfoEnabled_h…` = 0 в реестре настроек Unity) подключение
  показывает в Unity **модальный диалог «C# Debugger Attached»** (Enable debugging for this session / for all projects / Cancel), и
  главный цикл редактора стоит, пока его не закроют (сердцебиение пакета замерло). Протокол при этом работает. Значит, перед
  подключением IDE переключает режим через пакет (`CompilationPipeline.codeOptimization = Debug`, с согласия пользователя; режим —
  в `state.json`), а не ловит диалог. Текст диалога пишется в `Logs/Editor.log` проекта («# C# Debugger Attached … Debug Mode is
  switched off in your project»).
- **Cancel в этом диалоге убивает приёмник агента до перезапуска редактора**: в `Editor.log` — `debugger-agent: Unable to listen on 4064`
  (агент печатает это, когда падает `accept` на слушающем сокете; число — дескриптор сокета), порт по-прежнему в LISTENING (два сокета
  вместо одного), соединение принимается ядром, но рукопожатия нет (3 попытки по 5 с, соединения висят в CLOSE_WAIT). Перекомпиляция и
  перезагрузка домена не помогают — агент нативный. Обычное `VM.DISPOSE` приёмник не убивает: процесс импорта ассетов той же копии
  (Release, без диалога) принял второе подключение через 1,5 с после `DISPOSE` первого (клиент плагина, оба раза `VM_START`, 9 потоков,
  2–20 мс), слушающий сокет остался один. Та же строка `Unable to listen` бывает и при закрытии редактора (`Editor-prev.log`).
  Вывод для IDE: никогда не оставлять пользователя с этим диалогом — сначала `debug-mode`, потом подключение. Проверено после
  перезапуска редактора (закрыт по WM_CLOSE, открыт `Unity.exe -projectPath`, ~4,5 мин до пакета): `debug-mode`, затем два подключения
  подряд — без диалога и без `Unable to listen`. Запущенный без Hub редактор пишет лог в `%LOCALAPPDATA%\Unity\Editor\Editor.log`,
  а не в `Logs/Editor.log` проекта (Hub передаёт `-logFile`).
- Режим через пакет (0.1.274, команда `debug-mode`): Unity без фокуса перекомпилировал за ~25 с, `codeOptimization` в `state.json` стал
  `Debug`; настройка «на старте» (`ScriptDebugInfoEnabled_h…`) не изменилась — переключение на сессию.
- Процессы импорта ассетов (`AssetImportWorkerN`) — тоже `Unity.exe` со своим агентом (`--debugger-agent=…,address=127.0.0.1:56604`
  в `Logs/AssetImportWorkerN.log`): подключаться только к pid редактора.
- **Пути исходников** в символах — абсолютные пути Windows, как компилировал Unity (`C:\Users\…\Assets\Game\Boot\GameBootstrap.cs`);
  `VM.GET_TYPES_FOR_SOURCE_FILE` находит тип и по имени файла, и по полному пути с любыми слэшами и в любом регистре;
  `TYPE.GET_SOURCE_FILES_2` — полные пути, `GET_SOURCE_FILES` — имена. Ответ `METHOD.GET_DEBUG_INFO` в 2.58: max IL, файлы с MD5 (16
  байт), точки по 6 int (IL, строка, индекс файла, колонка, конец строки и колонки) — прочитан байт в байт.
- **Точки и домены**: запрос `TYPE_LOAD` с фильтром `SOURCE_FILE_ONLY` срабатывает и по полному пути, и по имени файла; для уже
  загруженных типов агент сразу присылает `TYPE_LOAD` с политикой NONE. Перекомпиляция скриптов выгружает домен (304
  `ASSEMBLY_UNLOAD`, `APPDOMAIN_UNLOAD`) и грузит типы заново с новыми id — `TYPE_LOAD` приходит с политикой ALL, точку можно поставить
  до выполнения кода. Play в этом проекте домен не перезагружает (Enter Play Mode Options), выход из Play — перезагружает. Несколько
  запросов на одном месте приходят одним составным событием. Id запросов у агента сквозные между подключениями.
- **Кадры**: `THREAD.GET_FRAME_INFO` агент Unity умеет только целиком (start 0, length -1); «один верхний кадр» — `NOT_IMPLEMENTED`.
  Поток, который называет событие точки, может отсутствовать в `ALL_THREADS` (в Play: событие — поток X, в списке его нет, кадр
  `Awake` — на потоке 1 списка, главном): IDE берёт поток списка с тем же верхним кадром. Главный поток безымянный — «Main Thread» по
  потоку `VM_START`. Кадры кода Unity — без символов (серые).
- **Значения** (проба 2026-10-18, все ответы прочитаны до последнего байта): `null` — один байт `0xf0`; числа до 32 бит, bool и char —
  int, 64 бита — long, `I` / `U` — long; указатель (`PTR`) — long и, с 2.46, id типа, на который он указывает (`IntPtr`
  `UnityEngine.Object.m_CachedPtr` — структура из одного указателя; без этих 4 байт рушится всё, что идёт после); структура — флаг enum,
  id типа, число полей и поля по порядку `TYPE.GET_FIELDS` без статических; строка `STRING_REF.GET_VALUE` (2.41) — байт «UTF-16?», потом
  обычная строка протокола (UTF-16 — только для строк с одиночным суррогатом). `TYPE.GET_INFO` после вложенных типов несёт ещё 8 байт
  (не читаем). Локальные — имена, типы и диапазоны жизни по IL (`METHOD.GET_LOCALS_INFO`, 2.43 — сначала области); параметры —
  `STACK_FRAME.GET_VALUES` с позицией `-index - 1`. У статического метода `GET_THIS` отдаёт `null` без ошибки. Поля всей цепочки
  читаются одним `OBJECT_REF.GET_VALUES`; значения кадра — через поток, с которого прочитаны его кадры.
- **Вызовы** (`VM.INVOKE_METHOD`, проба 2026-10-18): поток, флаги, метод, получатель, аргументы; ответ — байт (1 — вернул, иначе
  исключение) и значение. Объект-получатель или аргумент шлётся с типом `OBJECT` и id — с `CLASS`, который пишет сам агент, ответ
  `INVALID_ARGUMENT`; `null` — один байт `0xf0` (так и для статического метода); структура — как агент её пишет. Агент берёт аргумент только
  своего домена: объект другого домена — `INVALID_ARGUMENT` (строки командной строки Unity — из корневого домена, `string.Equals(args[1],
  name)` не вызвать); тип по имени (`VM.GET_TYPES`) есть в каждом домене — берётся тот, чья сборка в `APPDOMAIN.GET_ASSEMBLIES` домена
  кадра. После вызовов кадры и их значения читаются по-прежнему. Флаги IDE: точки выключены, возобновляется только поток остановки.
- **Unity 2023.1.22f1, весь путь** (проект пользователя `C:\Users\dvislobokov\jnmgbmgvjkhgg`, 2026-10-18, пакет и
  `Assets/DebuggerTest/DebuggerProbe.cs` положены туда для проверки): пакет связи не собирался — `TestRunnerApi.CancelTestRun` в Test
  Framework 1.3.9 `internal` (публичный — в 1.8.0 Unity 6), ошибка нашей тестовой сборки валила компиляцию всего проекта; исправлено
  вызовом рефлексией (1.4.1). Наш внешний редактор сам сгенерировал `.sln` и проекты генератором пакета Rider 3.0.26 (`ideIsEditor` —
  путь IDEA узнан по EditorPrefs, общим на машину). Пакет обновляется без фокуса (`refresh` старому), `refresh` IDE компилирует без
  фокуса. Перезагрузка домена обнуляла `compiledAt` пакета — Attach после переключения в Debug ждал вечно; теперь он в `SessionState`
  (1.4.2), а IDE считает готовым и увиденную компиляцию. Дальше всё как на 6000.6: остановка в `Update` через 4 с после Play, Main Thread,
  переменные (`this` с `ToString()`, `Vector3`, enum, `int[]`, `List<string>`, свойства), вычисления (`Describe(frames)`, `offset * 2`,
  `numbers[1] + 10`, `names[0]`, `names.Count`, `gameObject.name`, `Time.deltaTime > 0`), Step Over, отключение.
- Найдено там же: песочница IDE предыдущей версии молча поставила свой пакет 1.4.0 поверх 1.4.1 (любое расхождение считалось
  устаревшим) — теперь пакет новее версии IDE не трогается (`UnityEditorPackage.Status.NEWER`), связь держится по его версии.
- **Unity 2023.1.22f1** (проект пользователя `~/test-dubugger-old`, 2026-10-18): тот же рантайм `mono 6.13.0 (Visual Studio built mono)`,
  протокол 2.58, порт по той же формуле (pid 19700 → 56700), тот же диалог «C# Debugger Attached» в Release. Дальше не проверено: проект
  не собирался (CS0006 у `com.unity.collab-proxy`: нет dll PlasticSCM), а после удаления пакета пользователем Unity не перекомпилировал без
  фокуса окна — пакет связи и тестовый `Assets/DebuggerTest/DebuggerProbe.cs` (создаётся на Play сам, `RuntimeInitializeOnLoadMethod`)
  лежат в проекте и ждут компиляции. Отмечено: Burst пишет «Failed to resolve assembly: DotNetSupport.Unity.Editor.Tests» при неудачной
  сборке — проверить, уйдёт ли после успешной.

### Где мы против открытых инструментов (оценка 2026-10-18)

Сравнение — с открытыми наборами, не с Rider и Visual Studio: DotRush (VS Code, Roslyn + отладчик Unity на форке отладчика Mono),
Unity Code Pro (VS Code поверх DotRush: тесты, отладка; только Windows x64, Unity 6+), связки Neovim (`nvim-unity`,
`com.remzisenel.ide.neovim`, `unity-nvim-adapter`: открыть файл из Unity и сгенерировать `.csproj`, язык — OmniSharp / Roslyn LS).
- **Сильнее:** понимание проекта Unity (индекс ассетов, линзы «Used in…» / «Set by Unity», Find Usages в сценах,
  `[FormerlySerializedAs]` при переименовании, `.meta` за файлом, `.asmdef`, инспекции, шаблоны) — у открытых этого почти нет; связь с
  редактором целиком (Play / Pause / Step, лог с переходом, внешний редактор, Test Runner) — у каждого из них в лучшем случае часть.
- **Слабее:** нет отладчика (у DotRush и Unity Code Pro есть — для многих главный критерий); свой парсер и семантика вместо Roslyn —
  риск ложных и пропущенных ошибок выше (меряем `CompilerMessagesCoverageTest` и харнессами); нет обкатки живыми пользователями
  (macOS / Linux, старые Unity, PlayMode, настоящий двойной клик не проверены).
- Отсюда порядок важности: отладчик Mono → живая проверка человеком по сценариям `UNITY:*` → проводник тестов и повтор упавших.

## Сделано

- [x] 0.1.278 — проверка на Unity 2023.1.22f1 и исправления по её итогам (решение пользователя 2026-10-18 «Протестируй отладчик на нём»):
  пакет 1.4.2 (`CancelTestRun` рефлексией — собирается с Test Framework 1.3.9; `compiledAt` в `SessionState`); IDE: `UnityDebugger.ready`
  принимает увиденную компиляцию, пакет новее версии IDE не перезаписывается (`Status.NEWER`, и действие установки его не предлагает),
  enum по имени (константа enum приходит структурой), generic-типы как в C# (`List<string>`, `Dictionary<string, List<int>>`, `+`
  вложенных → `.`), статические свойства — в «Static members» (у экземпляра — только свойства экземпляра: `Vector3.zero` с получателем
  агент отвергает). Проверено роботом на Unity 2023.1.22f1 (см. «Отладчик: проба протокола»). Тесты затронутого; человеком вживую не
  проверено.


- [x] 0.1.277 — вычисления в рантайме (решение пользователя 2026-10-18 «…потом приступай к вычислениям в рантайме»):
  `MonoEvaluation.kt` — `MonoExpressionParser` (литералы, имена, `this`, `.`, вызовы, индексаторы, `!` `-` `+`, `* / %`, `+ -`, сравнения,
  `== !=`, `&&`, `||`, `?:`; без приведений, `new`, лямбд, generic-аргументов) и `MonoInterpreter` (переменные, поля, свойства —
  геттер, методы — перегрузка по статичности и совместимости аргументов с параметрами, неявные числовые преобразования аргументов,
  статические члены по имени типа: ключевые слова C#, namespace метода и родителей, `using` файла, `UnityEngine`, `System`; элементы
  массивов, индексатор `get_Item`, операторы `op_*` структур и классов, строки литералов — `APPDOMAIN.CREATE_STRING`, `+` строк, `ToString()`
  в склейке); `MonoDebugProcess.invoke` — по одному вызову, флаги «точки выключены, только этот поток», таймаут 5 с и `ABORT_INVOKE`,
  исключение — тип и `Message`. Значения: `ToString()` в строке, если тип его переопределяет, и свойства среди детей (`MonoPropertyNode`,
  геттер при показе строки, тип — тип возврата геттера), без `material` / `mesh` и устаревших свойств Unity; всё это — только при
  включённой настройке неявных вычислений. Проверено роботом с открытым Unity 6000.6 на копии `nine-to-fire`: в `Awake` — `this` =
  `{GameBootstrap} GameBootstrap (Game.Boot.GameBootstrap)`, `transform.position` = (0.00, 0.00, 0.00), `name`, `ToString()`,
  `_levelScene.ToUpper()`, `_levelScene + "!"`, `transform.position.x + 1`, `gameObject.activeSelf && enabled`, `Time.frameCount`,
  `Application.isPlaying`, `Mathf.Max(2, 7)` = 7 (перегрузка `int`), `Vector3.one * 2` = (2.00, 2.00, 2.00); в `HasArg` — `args[1]`,
  `args[i].Length > 3`, `HasArg("-projectPath")` = true, `GetArgValue("-projectPath")`, `name == "-nosteam"`, `args.Length * 2.5` = 7.5,
  отказы `args[5]` и `i / 0`; `string.Equals(args[1], name)` — понятный отказ (домены, см. «Вызовы»). Тесты: `MonoConnectionTest` (разбор,
  аргументы на проводе, вызов через поддельный агент, `using`). Не сделано: generic-методы (`GetComponent<T>()`), приведения, `new`,
  присваивание и изменение значений, лямбды; проверка на Unity 2023; человеком вживую не проверено.


- [x] 0.1.276 — переменные отладчика (решение пользователя 2026-10-18 «Продолжай, делай переменные»): `MonoVariables.kt` —
  `MonoValueNode` (числа, bool, char, строки, `null` с объявленным типом, объекты `{Тип}` с полями своего типа и базовых и группой
  «Static members», массивы `{string[3]}` с элементами по 100, enum по имени константы (литералы читаются `TYPE.GET_VALUES`), маленькая
  структура чисел в строку, `IntPtr` адресом), `MonoTypes` (кэш сведений и полей типов на сессию), `MonoPresentation` (имена типов C#,
  тексты, имена полей: `<Name>k__BackingField` → `Name`, `<x>5__2` → `x`, `<>4__this` → `this`, служебное компилятора скрыто),
  `MonoEvaluator` (имена переменных и полей через точку, неявный `this`, `Length` строки и массива; диапазон под мышью —
  `CSharpHoverExpression`). Кадр: `this`, параметры, живые на IL кадра локальные (служебные `CS$…` скрыты); в кадре типа компилятора
  (итератор, async, лямбда) — поля `this` как переменные. `MonoConnection`: значения, `GET_THIS` / `GET_VALUES` кадра, `GET_PARAM_INFO`,
  `GET_LOCALS_INFO`, поля и статические значения типов, `OBJECT_REF`, `STRING_REF`, `ARRAY_REF`. Проверено роботом с открытым Unity
  6000.6 на копии `nine-to-fire`: в `GameBootstrap.Awake` — `this` и все поля по цепочке до `UnityEngine.Object` (строка
  `"Playground"`, null с типом, `m_CachedPtr = 0x…`, `m_EntityId = {m_rawData = …}`); вычисления `_levelScene`, `this._levelScene.Length`
  = 10, `_platform` = null, отказ на `1 + 2`; в `HasArg` — `name = "-nosteam"`, `args = {string[3]}` с тремя строками, `i = 0`, три кадра;
  переменные нижнего кадра; снимок `build/ui-robot/unity-variables.png`. Найдено и исправлено по дороге: указатель с id типа (2.46).
  Тесты: `MonoConnectionTest` (значения байтами из пробы, строки, имена, локальные и параметры через поддельный агент). Не сделано:
  свойства и методы (вычисление в рантайме, `INVOKE_METHOD`), индексаторы и выражения, изменение значений, `ToString()` в строке
  значения; человеком вживую не проверено.


- [x] 0.1.275 — отладчик скриптов в открытом редакторе (решение пользователя 2026-10-18 «Продолжай»): `UnityAttachDebuggerAction`
  «Attach to Unity Editor» (меню .NET, всплывающее меню виджета, панель рядом с Play / Pause / Step) — в Release спрашивает и шлёт
  `debug-mode`, ждёт конца компиляции и возврата сердцебиения (`UnityDebugger.ready`), затем сессия `MonoDebugProcess` на
  `127.0.0.1:<56000 + pid % 1000>` без конфигурации запуска (`newSessionBuilder` с `showTab(true)`; обработчик процесса стартует
  платформа). `debugger/mono`: `MonoDebugProcess` (события — по очереди на своём исполнителе, не на потоке чтения; Pause —
  `VM.SUSPEND`, шаги — запрос `STEP` по строке с фильтром static ctor / DebuggerHidden / DebuggerStepThrough, Run to Cursor —
  разовые точки; Stop и Detach — `RESUME` + `DISPOSE`, редактор никогда не завершается), `MonoLineBreakpointHandler` (точки типа
  `dotnet-line`: типы файла → методы → построчные таблицы, `MonoBreakpointLocations.choose` — ближайший оператор в охватывающем методе,
  внешний при лямбде; один запрос `TYPE_LOAD` по именам файлов точек — точки в ещё не загруженном коде и в новом домене после
  компиляции), `MonoFrames` (потоки, кадры по запросу, позиция по таблице строк, переменных пока нет — строка об этом). Проверено
  роботом с открытым Unity 6000.6 на копии `nine-to-fire`: Attach (подключение за ~10 мс), точка на `GameBootstrap.cs:48`, Play →
  остановка через 2–3 с, Main Thread, кадр `GameBootstrap.Awake  GameBootstrap.cs:48`, Step Over → 50 → 51, Resume, выход из Play
  (перезагрузка домена) → точка поставлена заново, Play → снова остановка; Pause → кадры кода Unity; Stop → редактор продолжает, новое
  подключение принимается (снимок `build/ui-robot/unity-debugger.png`). Найдено по дороге и исправлено: `GET_FRAME_INFO` только целиком,
  поток события не из списка, двойной `startNotify`. Тесты: `MonoConnectionTest` (разбор `GET_DEBUG_INFO`, выбор места, кодирование
  фильтров, запрос на проводе, готовность после переключения). Не сделано: переменные и вычисление, условия / счётчики / лог (точка
  останавливает всегда, в консоли об этом строка), исключения, отладка тестов Unity, плеер (собранная игра); человеком вживую не проверено.
  Раз видели: точка на строке пропала из песочницы между перезапусками (не повторилось — следить).


- [x] 0.1.274 — первый шаг к отладчику (решение пользователя 2026-10-18 «Давай начнем с чего-то простого»): пакет 1.4.0, команды
  `debug-mode` / `release-mode` (`CompilationPipeline.codeOptimization`, на сессию) и `codeOptimization` в `state.json`; IDE: действие
  «Switch Unity to Debug Mode» / «… to Release Mode» (меню .NET, всплывающее меню виджета), режим в подсказке виджета,
  `UnityEditorLink.debuggerPort`. Свой клиент протокола — `debugger/mono/MonoConnection.kt`: рукопожатие, пакеты, ответы по id, коды
  ошибок по имени, разбор составных событий, `negotiate` (`VERSION` + `SET_PROTOCOL_VERSION`), потоки, `SUSPEND` / `RESUME` / `DISPOSE`;
  из UI пока не вызывается. Тест `MonoConnectionTest` (поддельный агент на сокете отвечает, как Unity 6000.6 в пробе). Переключение
  режима проверено на открытом Unity копии командой в канал (без песочницы IDE); клиент проверен на живых агентах: процесса импорта
  ассетов той же копии и — после перезапуска редактора и `debug-mode` — самого редактора: два подключения подряд по 4–19 мс, 23
  потока с именами, `SUSPEND` + `RESUME` всего рантайма, `DISPOSE`; диалога нет, редактор работает дальше. Сценарий `UNITY:debug-mode` (конец `Player.cs`).
  Человеком вживую не проверено.


- [x] 0.1.273 — шаг 4 «тесты в открытом редакторе»: пакет 1.3.0, своя сборка `DotNetSupport.Unity.Editor.Tests` (`Editor/Tests`,
  ссылки на `UnityEditor.TestRunner` / `UnityEngine.TestRunner` / `nunit.framework.dll`, собирается только при `com.unity.test-framework`
  ≥ 1.1.0 — `versionDefines` + `defineConstraints`), команды через публичный `IdeCommands` основной сборки: `run-tests <run> <mode>` +
  строки `group:<regex>` / `assembly:<имя>` → `TestRunnerApi.Execute`, `cancel-tests` → `CancelTestRun`; колбэки
  (`RegisterTestCallback` в `[InitializeOnLoad]`, прогон в `SessionState` — переживает перезагрузку домена Play mode; чужие прогоны окна
  Test Runner не пишутся) пишут `Temp/DotNetSupport/tests/<run>/events.jsonl` в формате логгера `dotnet test` (`testStart` / `result` /
  `runComplete`). IDE: конфигурация «Unity Tests» (`UnityTestRunConfiguration`: режим, фильтры; процесс-заглушка до `runComplete`,
  ухода редактора или Stop), дерево — `TrxEventsConverter` ядра с пустой папкой TRX, переход — `DotNetTestLocator` по `Assets`;
  `UnityTestConfigurationProducer` заменяет `dotnet test` у ▶ в проекте Unity, режим — по `includePlatforms` ближайшего `.asmdef`
  (или папке `Editor`), фильтр — `^Класс\.` / `^Класс\.Метод(\(|$)`. Ядро: маркер `UnitTestsRunProfile` (окно Unit Tests берёт такие
  запуски), `[UnityTest]` в `TestDiscovery`. Проверено роботом с открытым Unity на копии `nine-to-fire`: ▶ класса `HealthRulesTests` →
  6 тестов зелёные в окне Unit Tests с длительностями; сломанная проверка и ▶ метода → один тест красный, «Expected: 76 But was: 75» и
  стек с `HealthRulesTests.cs:11` (снимок `build/ui-robot/unity-tests.png`). Тест `UnityTestsTest`. Не сделано: проводник Unit Tests
  (вкладка Explorer) тесты Unity не показывает, повтор упавших, отладка тестов, Play mode вживую не проверен (в проекте нет таких тестов).
  Человеком вживую не проверено.


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
- Unity генерирует проекты для нашего встроенного пакета (галочка «Embedded packages» генератора включена по умолчанию):
  `DotNetSupport.Unity.Editor` и `DotNetSupport.Unity.Editor.Tests` видны в solution. Не исправлено (п. 3 «Дальше»).
- Unity дописывает пакет в `Packages/packages-lock.json` — принято решением пользователя, при установке предупреждаем.
- Выбор внешнего редактора в Unity — общий на машину (`HKCU\Software\Unity Technologies\Unity Editor 5.x\kScriptsDefaultApp_h…`):
  после живых проверок вернуть пользователю его выбор (с 2026-10-18 пользователь сам поставил IntelliJ IDEA — не менять), командой `restore-editor` или действием
  «Stop Opening Scripts from Unity Here».
- Пакет: каждое изменение — новая версия (`package.json` и `PackageVersion` в `IdeConnection.cs`), новый файл — в
  `UnityEditorPackage.FILES`.

## Дальше — по порядку (модуль `frameworks.unity`)

Пройдено: распознавание, панель Solution, New → Unity (0.1.255), семантика (0.1.256), completion и генерация (0.1.257), плюшки
(0.1.258: инспекции, gutter, строки тэгов / слоёв / осей, `GetComponent<`), `.asmdef` (0.1.259), индекс ассетов (0.1.260), шаги 1–4
связи с редактором (0.1.270–0.1.273). Дальше, по важности (порядок не утверждён пользователем — спросить перед началом):

1. **Живая проверка человеком** по сценариям `UNITY:*` площадки (`debug-playground/Unity`, чек-лист — `README.md` площадки):
   `UNITY:editor-link`, `UNITY:editor-log`, `UNITY:external-editor`, `UNITY:tests`. Особенно то, чего робот не видит: настоящий
   двойной клик в окне Unity и вывод окна IDE вперёд, запуск закрытой IDE лаунчером, тесты PlayMode, macOS / Linux, другие версии Unity.
2. **Отладчик Unity (шаг 5)** — свой клиент протокола Mono (решение пользователя 2026-10-18: встроенный клиент, без внешнего
   бинарника). Сделано: 0.1.274 (режим, клиент), 0.1.275 (подключение, точки, кадры, шаги), 0.1.276 (переменные). Дальше:
   вычисление выражений своим разбором C# поверх `INVOKE_METHOD` (свойства, методы, `ToString()` в строке значения), условия и счётчики
   точек, исключения (`EXCEPTION`), отладка тестов Unity, подключение к плееру (UDP multicast `225.0.0.222`).
3. **Проекты встроенного пакета в solution**: не показывать `DotNetSupport.Unity.Editor*` (скрыть в модели solution или снимать для
   нашего пакета галочку «Embedded packages» генератора — выяснить, можно ли точечно).
4. **Тесты**: вкладка Explorer окна Unit Tests (тесты Unity в проводнике), повтор упавших, отладка тестов (после отладчика).
5. **Batchmode при закрытом редакторе** (сборка плеера, тесты): второй Unity не откроет проект, пока открыт первый
   (`Temp/UnityLockfile`) — только когда редактор закрыт.
6. Мелочи из 0.1.255: уведомление «C# solution build scripts found» на чужие `.sln` в `Assets` (как `Mono.Cecil.sln` у FishNet) — пока
   показывается; Build / Run у solution Unity — кнопки на месте («Известные мелочи»).
7. **Проверка** без площадки в репозитории: проекты Unity лежат у пользователя (`~/My project`, `~/nine-to-fire`, `~/TOP DOWN SHOOTER`);
   копия для песочницы WSL — `tools/ui-robot/wsl/copy-unity.sh` (переписывает пути `HintPath` на `/mnt/c/...`, так же как сделал бы
   Unity под Linux), пробы — скрипты робота `index_probe.js`, `msbuild_probe.js`, `references_probe.js`, `errors_at.js`,
   `complete_at_line.js`, `doc_at.js`, `lenses.js` (линзы файла), `parse_keys.js` (символы `#if` и версия языка файла). Перед проверкой
   «IDE error» — убрать `~/robot/sandbox/log/idea.log` и `system/{caches,index}` песочницы, иначе считаются старые ошибки. Тесты — на
   статике (`ProjectsInOneFolderTest`, `UnityProjectsTest`, `UnityCompletionTest`, Unity-случай `CSharpCodeLensTest`): настоящий
   Unity в тестах не нужен. Живой набор в песочнице: `caret_at.js` ставит каретку и фокус, затем `screen.sh type` / `key`.
   Связь с редактором проверяется под Windows: открытый пользователем Unity 6000.6 на копии `~/unity-probe/nine-to-fire-probe`,
   песочница `runIdeForUiTests`, скрипт робота `tools/ui-robot/scripts/unity_editor_link.js` (с `__SEND__` шлёт любую команду пакета,
   например `open-asset <путь> <строка>`). Правки робота в копии откатывать её git (переименование через VFS однажды сменило GUID в
   `.meta`); после проверки — вернуть внешний редактор Unity, какой был (сейчас — IDEA, выбор пользователя).
8. Не делать (пока): шейдеры, UXML/USS, профайлер.
