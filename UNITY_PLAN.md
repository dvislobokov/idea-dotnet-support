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

## Дальше — по порядку (модуль `frameworks.unity`)

1. **Распознавание и панель Solution** (1 выпуск). Проект Unity = `ProjectSettings/ProjectVersion.txt` + `Assets/`. Узел solution с
   иконкой Unity и версией редактора; при Show All Files скрывать `Library`, `Temp`, `Logs`, `obj`, `UserSettings`, `*.csproj.user`,
   `.meta` (переключатель «Show .meta files» в меню панели, как в Rider); `.asmdef` с иконкой. Уведомления «C# solution build scripts
   found» и «Several solutions» не считать `.sln` внутри `Library/PackageCache`. Кнопки Build / Run для solution Unity — спрятать или
   заменить на «Open in Unity» (редактор по `ProjectVersion.txt` через Unity Hub: `Hub/Editor/<версия>/Editor/Unity.exe -projectPath`).
2. **Семантика** (1 выпуск). Точка расширения «подавление диагностик» (RAZOR_PLAN, «Модульность»): методы-сообщения `MonoBehaviour`
   (`Awake`, `Start`, `Update`, `OnEnable`, `OnCollisionEnter`…) и `ScriptableObject` не «не используются»; поля с `[SerializeField]`
   и публичные поля компонентов — тоже; `Debug.Log` в `UNITY_EDITOR`. Список сообщений — из `UnityEngine.CoreModule` (документация
   `MonoBehaviour`) таблицей в модуле, как у анализаторов Unity (`UNT0001`…).
3. **Completion и генерация** (1 выпуск). Внутри класса-наследника `MonoBehaviour` — completion сообщений (`void Update()` с
   сигнатурой, как Rider «Generate Unity event functions»), атрибуты `[SerializeField]`, `[Header]`, `[Range]`; `GetComponent<T>` —
   T из компонентов. `Input`-ось / тэг / слой строками из `ProjectSettings` — позже.
4. **Проверка** без площадки в репозитории: проекты Unity лежат у пользователя (`~/My project`, `~/nine-to-fire`, `~/TOP DOWN SHOOTER`);
   копия для песочницы WSL — `tools/ui-robot/wsl/copy-unity.sh` (переписывает пути `HintPath` на `/mnt/c/...`, так же как сделал бы
   Unity под Linux), пробы — скрипты робота `index_probe.js`, `msbuild_probe.js`, `references_probe.js`, `errors_at.js`,
   `complete_at_line.js`, `doc_at.js`. Тесты — на статике (`ProjectsInOneFolderTest`): настоящий Unity в тестах не нужен.
5. Не делать (пока): отладчик Unity (Mono), шейдеры, UXML/USS, профайлер, `.unity`/`.prefab` (YAML — платформа и так подсвечивает).
