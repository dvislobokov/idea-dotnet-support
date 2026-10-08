# idea-dotnet-support

Плагин «C# Project Support» (`io.github.dotnetsupport`) для IDE на платформе IntelliJ (GoLand, PyCharm, WebStorm, IDEA...):
Rider-подобная работа с .NET **без LSP, Roslyn и (пока) отладчика**. Всё держится на `dotnet` CLI, файлах проектов
(`.sln`/`.slnx`, MSBuild, `project.assets.json`) и своём парсере C# — порте парсера Roslyn (`csharp-psi-core`, с шага 7 миграции, 0.1.45). Дерево `.cs` — одно из двух, выбор `lang/CSharpSyntaxTrees.nativeTree()` (фича `SYNTAX_TREE`, по умолчанию NATIVE): PSI Roslyn-видов (`CompilationUnit`, `MethodDeclaration`…, классы сгенерированы из `Syntax.xml`) или прежнее эвристическое (`lang/CSharpDeclarations` + узлы `CSharpDeclaration`, внутри членов токены плоские). Код, который смотрит на листья PSI, должен понимать оба дерева — `lang/CSharpLeaves`; многие эвристики лексят текст сами лексером хоста `CSharpLexer` (подсветка тоже). Потребители берут объявления только через фасад `lang/CSharpSyntaxModel` (`NativeCSharpSyntaxModel` / `HeuristicCSharpSyntaxModel`); что он отдаёт, записано снимками `CSharpSyntaxSnapshotTest` (`src/test/resources/syntaxSnapshots`, `<вход>.txt` — эвристика, `<вход>.native.txt` — PSI) — поменял поведение модели, перезапиши и просмотри их; поменял, что модель считает объявлением, — подними `VERSION` у `CSharpDeclarationIndex`.

Планы и статус: `ROADMAP.md` (чек-лист фич, ведётся по-русски), **`DAP_PLAN.md`** (отладчик на своём DAP-клиенте, пакет `debugger`: действующий план, справка по адаптеру
и что осталось), `PLATFORM_DAP_PLAN.md` (история: отладчик на платформенном DAP-клиенте, этапы 0–6 и журнал находок — перенесён на свой клиент 2026-09-22), `LSP_PLAN.md` (C# через `roslyn-language-server`: платформенный LSP-клиент, кэш ответов, свои индексы плагина; действует до вехи 12.1 миграции), **`CSHARP_PSI_MIGRATION.md`** (свой PSI C# вместо сервера: парсер по образцу Roslyn, семантика со сверкой по Roslyn, переключатели `ROSLYN | NATIVE`, вехи отказа от сервера; решение 2026-10-04), **`NET_FRAMEWORK_PLAN.md`** (.NET Framework и проекты
старого формата: сделанное, порядок дальнейших работ, найденные факты; площадка — `debug-playground/NetFramework`). Анализ платформенных API LSP / DAP —
`docs/platform-lsp-dap.html`, скрипты и дамп — `tools/platform-api/`; зонд и факты о `roslyn-language-server` — `tools/roslyn-lsp/`. Зонд аллокаций по строкам (EventPipe) — `tools/alloc-probe/`; помощник плагина для них — `allocwatch/` (несётся
исходником и собирается у пользователя, как индексатор; общий сборщик — `cli/DotNetHelper`), сторона плагина — пакет `allocations`. Клиент сервера — content-модуль `io.github.dotnetsupport.roslyn` (фаза 1 сделана). `dap-probe/` — питоновские эксперименты с отладчиком, к сборке плагина не относятся. `debug-playground/` — .NET solution
для живой проверки плагина пользователем: отладчика (сценарии с маркерами `// BP:`) и редактора (сценарии набора с маркерами `// TYPE:`), чек-лист — в его
`README.md`; к сборке тоже не относится. `indexer/` — индексатор сборок .NET на C# (`Program.cs`): плагин несёт его
**исходником** (сборка плагина кладёт `Program.cs` и `.csproj` в ресурсы, dll в репозитории нет) и собирает на машине пользователя под тот SDK, что там
стоит (`index/IndexerTool`). Формат индекса, замеры и блокировки — `indexer/README.md`; читатель и completion — пакет `index`. Меняешь формат — подними
`Program.FormatVersion` и `AssemblyIndex.FORMAT_VERSION` вместе и пересоздай фикстуры `src/test/resources/index` (команда — в README индексатора).

## Ошибки компилятора

Правило пользователя (2026-10-08): **каждая ошибка Roslyn — ошибка и у нас**, в том же месте, с тем же кодом; имя, которого нет, красное.
Семантика не молчит «на всякий случай»: синтаксическая ошибка глушит проверки только на своих токенах, нерезолвленный `using` — ошибка на
директиве, а не повод замолчать. Мера — `CompilerMessagesCoverageTest`: корпус примеров со страниц compiler-messages dotnet/docs против
оракула Roslyn (`tools/compiler-messages/README.md`, состояние в `src/test/resources/compilerMessages/coverage.txt`: `ok` / `missing` /
`extra`). `extra` (у нас есть, у Roslyn нет) — хуже, чем `missing`; тест падает на любое изменение состояния — просмотреть
`build/reports/compilerMessages/coverage.txt` и скопировать. Новая семантическая проверка — сначала пример в корпус (или в
`debug-playground/Broken/RiderComparison.cs`, его копия там же), потом код.

## Сборка и проверка

Системных JDK и Gradle нет. Wrapper запускать с JBR целевой IDE (Git Bash):

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2026.1.4\jbr"   # JBR 25; JBR 21 старого GoLand тоже годится
./gradlew.bat test buildPlugin -q      # основная проверка перед тем, как сказать «готово»
./gradlew.bat compileKotlin -q         # быстрая проверка компиляции
./gradlew.bat test --tests "io.github.dotnetsupport.NuGetTest" -q
```

Сборка плагина и запуск песочницы, без ML и с ML (модели — `ml-models/csharp`, см. комментарий к `mlEnabled` в `build.gradle.kts`):

```sh
./gradlew.bat buildPlugin                                                  # обычная сборка: build/distributions/idea-dotnet-support-<v>.zip
./gradlew.bat buildPlugin -PmlEnabled=true                                 # ML-сборка с трансформером 31m: …-<v>-ml.zip
./gradlew.bat buildPlugin -PmlEnabled=true -Pml.big=true                   # ML-сборка с 31m и большим 50m (переключатель на Settings | .NET | ML completion)
./gradlew.bat runIde --offline --no-configuration-cache                    # песочница IDE без ML
./gradlew.bat runIde -PmlEnabled=true -Pml.big=true --offline --no-configuration-cache   # песочница IDE с ML и 50m
```

- Целевая платформа — локальная **IntelliJ IDEA Community Edition 2026.1.4** (`localIdePath` в `gradle.properties`; решение пользователя 2026-10-05:
  все прогоны — на публичном дистрибутиве, без платных плагинов и библиотек; полная IDEA 2026.1.4 тоже стоит, но для сборки не используется — то,
  чего нет в Community, плагину недоступно), ничего не скачивается. `sinceBuild = 261`:
  с 2026.1 в платформе есть модуль DAP (`intellij.platform.dap`, `@Experimental`), на нём планируется отладчик. Проверить другую IDE, не трогая файлы:
  `./gradlew.bat test "-PlocalIdePath=C:/Program Files/JetBrains/<IDE>"`.
- IDEA тянет Java-плагин, и он меняет поведение платформы (папка без родителя-папки в дереве называется как пакет, `src.App.Models`): то, что работало
  в GoLand, в IDEA может выглядеть иначе — тесты на дерево это ловят.
- Gradle 9.7.1, Kotlin 2.3.21, `apiVersion`/`languageVersion` = **2.3** (stdlib берётся из платформы, в 2026.1 это 2.3.20) — не использовать API Kotlin новее.
- Демонам задана память в `gradle.properties` (`kotlin.daemon.jvmargs`, `org.gradle.jvmargs`): classpath IDEA в разы больше GoLand, с умолчаниями компилятор
  Kotlin падал с `GC overhead limit exceeded` посреди полной компиляции.
- Упавшие тесты: `build/test-results/test/TEST-*.xml` (grep по `<failure`), отчёт — `build/reports/tests/test/index.html`.
- GUI агент может проверить сам через UI-робота: `./gradlew.bat runIdeForUiTests` (в фоне) поднимает песочницу IDE с Remote Robot на
  `127.0.0.1:8583`, `tools/ui-robot/robot.py` открывает проект, ставит точки останова, запускает Debug, кликает и снимает окно IDE (команды и
  оговорки — в `tools/ui-robot/README.md`; снимать только компоненты IDE, не весь экран; после проверки песочницу закрыть). Чего так не видно
  (подсказки по наведению, ощущение скорости, вторая тема), по-прежнему просить пользователя посмотреть вживую и прямо говорить, что не проверено.
  Настоящие мышь и клавиатура Windows — `tools/ui-robot/desktop.ps1` (только когда пользователь сказал, что не трогает мышь; снимать только окно
  песочницы), реальный ввод без занятого стола — песочница в WSL (`tools/ui-robot/wsl`); оговорки — в README робота.
- **У всего, что надо смотреть вживую, должен быть код-сценарий в `debug-playground`.** Тесты проверяют правило на готовых данных, а не путь, которым
  данные приходят в IDE (так `;` после void-метода прошла все тесты и не работала). Поэтому вместе с фичей, поведение которой видно только в
  редакторе или в отладчике, в `debug-playground` добавляется кусок кода, на котором её проверяют руками:
  - место действия помечено маркером — `// BP:<имя>` для точки останова, `// TYPE:<имя>` для набора в редакторе; в комментарии маркера написано, **что
    сделать** (что набрать, какую клавишу нажать) и **что должно получиться** (`EXPECT: …`), включая то, чего получиться не должно;
  - код компилируется как есть и ничего не ломает в solution (`dotnet build debug-playground/Console` — 0 ошибок): набирают на пустой строке под
    маркером и отменяют через Ctrl+Z; то, что компилироваться не должно, — в `Broken`, который в solution не входит;
  - сценарии редактора лежат в `debug-playground/Console/Editor/<Фича>.cs`, по файлу на фичу; в `README.md` площадки — пункты чек-листа с именами
    маркеров, в `ROADMAP.md` у фичи — имя файла сценария;
  - в ответе пользователю называть файл и маркеры, с которых начинать проверку, и по-прежнему прямо говорить «вживую не проверено», пока не проверено.
- Если `compileKotlin` висит минутами и падает с `OutOfMemoryError` при заданной памяти — это почти наверняка конструкция в новом коде,
  на которой зацикливается вывод типов (так было с рекурсивной локальной функцией `fun run(): Unit = x.run(...) { run() }`), а не нехватка памяти. Переписать проще, `./gradlew.bat --stop`, повторить.

## Устройство

`src/main/kotlin/io/github/dotnetsupport/`, пакет = область:

| Пакет | Что там |
|---|---|
| `solution` | модель и парсеры `.sln`/`.slnx`, `SolutionService` (solutions, `msBuildProject(file)`, assets, central package versions) |
| `msbuild` | типы файлов MSBuild/XML, `DotNetProjects.findOwningProject`, target frameworks, `MsBuildEvaluation` (MsBuildHost), `CompilationModel` (символы `#if`, `LangVersion`, usings, `Compile` по файлу — для csharp-psi) |
| `view` | панель Solution (узлы, ключи `SolutionKey`/`ProjectKey`/…, Dependencies, nesting, Show All Files) |
| `actions` | действия над solution; `SolutionContext` — что выбрано в дереве (`fromSelection`, `buildTarget`) |
| `cli` | `DotNetCli` (поиск `dotnet`, `commandLine`, `execute`, `runInBackground`, нотификации), `DotNetTool` — глобальные tools |
| `build` | Build/Rebuild/Clean/Restore → Build tool window, разбор вывода MSBuild, конфигурация Debug/Release + TFM |
| `run` | run configuration «.NET Project», producer, автогенерация по `launchSettings.json`, Run/Debug проекта, консольные фильтры |
| `testing`, `coverage` | `dotnet test`, Unit Tests explorer, покрытие |
| `nuget` | окно NuGet (Packages / Sources / Folders / Log), `NuGetService`, клиент V3-фидов, действия меню NuGet |
| `format` | CSharpier / `dotnet format` за Reformat Code |
| `monitor`, `endpoints`, `upgrade`, `sdk`, `settings`, `templates`, `newproject`, `lang` | по названию |

Регистрация всего — `src/main/resources/META-INF/plugin.xml`. Отладчик — в основной части, пакет `debugger`: свой DAP-клиент (`DapConnection`) на XDebugger API,
который есть в каждой IDE. На платформенный DAP (`intellij.platform.dap`) не опираться: его нет в IntelliJ IDEA Community и её форках (с него ушли 2026-09-22,
находки — в `PLATFORM_DAP_PLAN.md`). Исключение из «всё в plugin.xml» — то, чему нужен модуль платформы, которого есть не в каждой IDE: это content-модуль
плагина со своим дескриптором, классы строго в его пакете (свой загрузчик по префиксу пакета), остальной код на этот пакет ссылаться не должен. Так устроен клиент `roslyn-language-server`:
модуль `io.github.dotnetsupport.roslyn` (`resources/io.github.dotnetsupport.roslyn.xml`, зависит от `intellij.platform.lsp.impl`), пакет именно `roslyn` — в `lsp`
лежит страница настроек основной части. Из основной части в модуль — только через топик (`RoslynLanguageServerSettings.CHANGED`). Формы ответов сервера не угадывать: `tools/roslyn-lsp/capture.py` снимает трафик (фикстуры — `src/test/resources/roslyn/capture-5.12`,
тест на потери в lsp4j — `RoslynCapturedTrafficTest`); у платформенного LSP-клиента два поколения API — переопределять обе перегрузки (`LspClient` и устаревшую `LspServer`).
Roslyn главный: свои эвристики (раскраска идентификаторов, folding, ошибки последней сборки, `dotnet format whitespace`) проверяют
`RoslynServerStatus.isReady(project)` и уступают готовому серверу — новую эвристику, которую сервер тоже умеет, ставить под ту же проверку. В unit-test режиме
провайдер сервер не запускает (иначе любой тест, открывший `.cs`, поднимает настоящий сервер машины); URI серверу — с обычным двоеточием, не `c%3A`. Проверка вживую — меню .NET → Probe Platform LSP / DAP API.
Свой PSI C# (`CSHARP_PSI_MIGRATION.md`) — Gradle-подпроекты `csharp-psi-core` / `-semantic` / `-ide` (пакеты `io.github.dotnetsupport.csharp.*`, дескрипторы `META-INF/csharp-psi-*.xml` через `xi:include`): в `-core` лексер, парсер и PSI (тесты, `testData`, корпусные гейты `:csharp-psi-core:corpusTest`; правила и команды — `docs/csharp-psi/`, оракул — `tools/csharp-psi/roslyndump`), `-semantic` / `-ide` пока пустые; тип файла и парсер C# регистрирует хост (`lang/CSharpParserDefinition` переключает деревья), в ядре нет иконок, текстов и настроек; `pluginComposedModule` кладёт их классы в основной jar: модуль `roslyn` их видит, они его — нет. Кто отвечает за фичу — `lang/CSharpFeatures` (`ROSLYN | NATIVE`, выбор в `RoslynLanguageServerSettings`); обработчик модуля `roslyn`, который отвечает за фичу из `CSharpFeature`, сначала спрашивает `RoslynFeatures.serves`.

Действия:
- `DotNet.MainMenu` — меню **.NET** в главной строке меню (после Tools), в нём подменю `DotNet.NuGet`, `DotNet.EfCore`;
- `DotNet.SolutionViewPopup` — ПКМ в панели Solution; действия объявляются здесь, в главное меню попадают через `<reference>`
  (группа с объявлением должна идти в файле раньше ссылки);
- настройки — Settings | .NET (`DotNetSettingsConfigurable`) и дочерние страницы: Toolset and Build, NuGet, Coverage, Debugger,
  Language Server (`lsp/`, параметры `roslyn-language-server`; каталог опций — `RoslynOptions`, факты о сервере — `tools/roslyn-lsp`), плюс
  Editor | Code Style | C#. Группы и формулировки — как в Rider, но на страницах **только то, за чем есть реализация**: выключенных
  опций-заглушек «как в Rider, под замком» нет (убраны по решению пользователя 2026-09-21), опция появляется вместе с тем, что она включает.
  Есть тест на состав страниц и на отсутствие выключенных контролов (`SettingsPagesTest`).

## Соглашения кода

- Kotlin, строки до ~180 символов, плотный стиль; однострочные функции-выражения — норма.
- Комментарии и KDoc — **по-английски**, короткие, объясняют «почему» (часто с отсылкой «as in Rider»). `ROADMAP.md` и общение с пользователем — по-русски.
- Тексты UI — английские, в Title Case для действий, формулировки сверять с Rider. **Страницы настроек — на двух языках** (решение пользователя
  2026-09-29): тексты лежат в `resources/messages/DotNetBundle.properties` и `DotNetBundle_ru.properties`, берутся через `DotNetBundle.message(key)`;
  язык — параметр плагина (Settings | .NET, «Language of the settings pages»: как в IDE / English / Русский), потому что русского языкового
  пакета у самой IDE нет. Новый текст на странице настроек — ключ в обоих файлах (`DotNetBundleTest` сверяет ключи и параметры `{0}`); у опций сервера
  языка английский текст в `RoslynOptions`, русский — в файле под ключом `roslyn.option.<section>`. Меню, действия и окна остаются английскими.
- Страницы о плагине: `docs/demo.html` (возможности, продающая, с анимациями) и `docs/guide.html` (документация). Вторая **генерируется**:
  `uv run --no-project python tools/guide/generate.py` — названия параметров она берёт из файлов строк, оформление из `demo.html`; править скрипт,
  а не файл. Новая опция на странице настроек без перегенерации роняет `DotNetBundleTest.testTheDocumentationNamesEveryOption`. В IDE обе страницы
  открываются файлами из кэша IDE (`WelcomePage`), чтобы ссылки между ними работали.
- Действия: `AnAction(), DumbAware`, `getActionUpdateThread() = BGT`; в `update` обычно `isEnabledAndVisible`.
  В контекстном меню неподходящее скрывать, в главном — оставлять видимым, но выключенным (`e.isFromContextMenu`).
- Команды `dotnet`: строить через `DotNetCli.commandLinesOrNotify { DotNetCli.commandLine(dir, ...) }`, запускать `DotNetCli.runInBackground`
  (вывод — в Build tool window или свой `CommandOutput`, напр. `NuGetService.log`). Блокирующие `DotNetCli.execute` — только не на EDT;
  результат на EDT через `invokeLater(..., ModalityState.any())` с проверкой `project.isDisposed`.
- Парсинг вывода CLI и файлов — чистыми функциями (`NuGetResponses`, `MsBuildOutputParser`, …), чтобы тестировать без процесса.
- Схема MSBuild-файлов — наша, составная: `resources/msbuildSchema/<фрагмент>.json` + список в `MsBuildSchema.FRAGMENT_FILES` (порядок = приоритет при
  совпадении имён). Фрагмент на инструмент сообщества: `packages` / `sdks` — когда он считается активным для проекта. `values: "bool"` или список;
  `open: true` — значения только подсказка, без предупреждения (ставить всегда, когда перечисление может пополниться). Структура файла
  (Project / Target / задачи / их атрибуты) — в коде `MsBuildSchema`, она не растёт вместе с экосистемой.
- Отступы C# при наборе — правила в `resources/csharpIndent/rules.json` (словарь условий и якорей описан в `about` файла, движок — `lang/CSharpIndent.kt`). Новое поведение сначала пробовать выразить правилом и его порядком; в движок лезть только за новым условием или якорем. У правила обязательны `examples`: `CSharpIndentRulesTest` прогоняет их все и проверяет, что пример решён именно своим правилом.
- Python-скрипты с обратными слэшами не передавать через heredoc в Bash: слэши теряются (`\n` становится переводом строки). Класть скрипт файлом в scratchpad.
- Новую фичу отмечать в `ROADMAP.md`; при переносе пунктов меню править упоминания путей («меню .NET → …») там же и в KDoc.
- **Версии и changelog** (решение пользователя 2026-10-01): каждая фича — новая версия `0.1.x`, `x` растёт на единицу (`pluginVersion` в
  `gradle.properties`). Вместе с версией — раздел `## 0.1.x` в `CHANGELOG.md` (по-английски, пункты `- …`, это видит пользователь): сборка кладёт
  раздел текущей версии и предыдущие в `<change-notes>` плагина (окно Plugins → What's New при установке и обновлении) и падает, если раздела
  для `pluginVersion` нет. В `ROADMAP.md` у сделанного пункта — версия, в которой он вышел.

## Тесты

`src/test/kotlin/io/github/dotnetsupport/*Test.kt`, JUnit 3-стиль на `BasePlatformTestCase` (`fun testXxx()`), файлы через `myFixture.addFileToProject`.
- Light-проект **общий для тестов класса и между классами**: run configurations, файлы с тем же путём и т.п. переживают тест.
  Брать уникальные имена проектов и убирать за собой то, что регистрируется (`RunManager`, listeners).
- Реальный `dotnet` в тестах не запускать; HTTP подменяется (`NuGetClient(fetch = ...)`).
- Tool window в тестах — `ToolWindowHeadlessManagerImpl.MockToolWindow`, disposable освобождать вручную.
- Есть тесты на состав UI (вкладки окна NuGet, состав меню) — при изменении состава обновлять их.

## Git

Коммитить только по просьбе. В рабочем дереве обычно лежит незакоммиченная работа пользователя по нескольким фичам сразу — чужие изменения не трогать и не откатывать.
