# idea-dotnet-support

Плагин «C# Project Support» (`io.github.dotnetsupport`) для IDE на платформе IntelliJ (GoLand, PyCharm, WebStorm, IDEA...):
Rider-подобная работа с .NET **без LSP, Roslyn и (пока) отладчика**. Всё держится на `dotnet` CLI, файлах проектов
(`.sln`/`.slnx`, MSBuild, `project.assets.json`) и лексере C#. Парсера C# нет — «понимание» кода делается эвристиками по токенам:
`lang/CSharpDeclarations` находит объявления (namespace / типы / члены), по ним парсер строит PSI-узлы `CSharpDeclaration` (Structure view, breadcrumbs,
folding, Go to Class); внутри членов токены плоские. Меняешь, что сканер считает объявлением, — подними `VERSION` у `CSharpDeclarationIndex`.

Планы и статус: `ROADMAP.md` (чек-лист фич, ведётся по-русски), **`PLATFORM_DAP_PLAN.md`** (отладчик на платформенном DAP-клиенте: этапы и промпт для сессии;
действующий план), `DAP_PLAN.md` (свой DAP-клиент — запасной путь и справка по адаптеру), `LSP_PLAN.md` (C# через `roslyn-language-server`: платформенный LSP-клиент, кэш ответов, свои индексы плагина; действующий план). Анализ платформенных API LSP / DAP —
`docs/platform-lsp-dap.html`, скрипты и дамп — `tools/platform-api/`; зонд и факты о `roslyn-language-server` — `tools/roslyn-lsp/`. Клиент сервера — content-модуль `io.github.dotnetsupport.roslyn` (фаза 1 сделана). `dap-probe/` — питоновские эксперименты с отладчиком, к сборке плагина не относятся. `debug-playground/` — .NET solution
для живой проверки отладчика пользователем (сценарии с маркерами `// BP:`, чек-лист по этапам в его `README.md`), к сборке тоже не относится.

## Сборка и проверка

Системных JDK и Gradle нет. Wrapper запускать с JBR целевой IDE (Git Bash):

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"   # JBR 25; JBR 21 старого GoLand тоже годится
./gradlew.bat test buildPlugin -q      # основная проверка перед тем, как сказать «готово»
./gradlew.bat compileKotlin -q         # быстрая проверка компиляции
./gradlew.bat test --tests "io.github.dotnetsupport.NuGetTest" -q
```

- Целевая платформа — локальная IntelliJ IDEA 2026.1.4 (`localIdePath` в `gradle.properties`), ничего не скачивается. `sinceBuild = 261`:
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
- Если `compileKotlin` висит минутами и падает с `OutOfMemoryError` при заданной памяти — это почти наверняка конструкция в новом коде,
  на которой зацикливается вывод типов (так было с рекурсивной локальной функцией `fun run(): Unit = x.run(...) { run() }`), а не нехватка памяти. Переписать проще, `./gradlew.bat --stop`, повторить.

## Устройство

`src/main/kotlin/io/github/dotnetsupport/`, пакет = область:

| Пакет | Что там |
|---|---|
| `solution` | модель и парсеры `.sln`/`.slnx`, `SolutionService` (solutions, `msBuildProject(file)`, assets, central package versions) |
| `msbuild` | типы файлов MSBuild/XML, `DotNetProjects.findOwningProject`, target frameworks |
| `view` | панель Solution (узлы, ключи `SolutionKey`/`ProjectKey`/…, Dependencies, nesting, Show All Files) |
| `actions` | действия над solution; `SolutionContext` — что выбрано в дереве (`fromSelection`, `buildTarget`) |
| `cli` | `DotNetCli` (поиск `dotnet`, `commandLine`, `execute`, `runInBackground`, нотификации), `DotNetTool` — глобальные tools |
| `build` | Build/Rebuild/Clean/Restore → Build tool window, разбор вывода MSBuild, конфигурация Debug/Release + TFM |
| `run` | run configuration «.NET Project», producer, автогенерация по `launchSettings.json`, Run/Debug проекта, консольные фильтры |
| `testing`, `coverage` | `dotnet test`, Unit Tests explorer, покрытие |
| `nuget` | окно NuGet (Packages / Sources / Folders / Log), `NuGetService`, клиент V3-фидов, действия меню NuGet |
| `format` | CSharpier / `dotnet format` за Reformat Code |
| `monitor`, `endpoints`, `upgrade`, `sdk`, `settings`, `templates`, `newproject`, `lang` | по названию |

Регистрация всего — `src/main/resources/META-INF/plugin.xml`. Исключение — то, чему нужен платформенный DAP (`intellij.platform.dap`, есть не в каждой IDE):
это content-модуль плагина `io.github.dotnetsupport.dap` — дескриптор `resources/io.github.dotnetsupport.dap.xml` (зависимость на модуль платформы и свои
расширения), классы строго в пакете `io.github.dotnetsupport.dap` (у модуля свой загрузчик по префиксу пакета). Остальной код на этот пакет и на классы
`com.intellij.platform.dap.*` ссылаться не должен: без DAP модуль не грузится, а плагин обязан работать. Так же устроен клиент `roslyn-language-server`:
модуль `io.github.dotnetsupport.roslyn` (`resources/io.github.dotnetsupport.roslyn.xml`, зависит от `intellij.platform.lsp.impl`), пакет именно `roslyn` — в `lsp`
лежит страница настроек основной части. Из основной части в модуль — только через топик (`RoslynLanguageServerSettings.CHANGED`). Формы ответов сервера не угадывать: `tools/roslyn-lsp/capture.py` снимает трафик (фикстуры — `src/test/resources/roslyn/capture-5.12`,
тест на потери в lsp4j — `RoslynCapturedTrafficTest`); у платформенного LSP-клиента два поколения API — переопределять обе перегрузки (`LspClient` и устаревшую `LspServer`).
Roslyn главный: свои эвристики (раскраска идентификаторов, folding, ошибки последней сборки, `dotnet format whitespace`) проверяют
`RoslynServerStatus.isReady(project)` и уступают готовому серверу — новую эвристику, которую сервер тоже умеет, ставить под ту же проверку. В unit-test режиме
провайдер сервер не запускает (иначе любой тест, открывший `.cs`, поднимает настоящий сервер машины); URI серверу — с обычным двоеточием, не `c%3A`. Проверка вживую — меню .NET → Probe Platform LSP / DAP API.

Действия:
- `DotNet.MainMenu` — меню **.NET** в главной строке меню (после Tools), в нём подменю `DotNet.NuGet`, `DotNet.EfCore`;
- `DotNet.SolutionViewPopup` — ПКМ в панели Solution; действия объявляются здесь, в главное меню попадают через `<reference>`
  (группа с объявлением должна идти в файле раньше ссылки);
- настройки — Settings | Tools | .NET (`DotNetSettingsConfigurable`) и дочерние страницы: Toolset and Build, NuGet, Coverage, Debugger,
  Language Server (`lsp/`, параметры `roslyn-language-server`; каталог опций — `RoslynOptions`, факты о сервере — `tools/roslyn-lsp`), плюс
  Editor | Code Style | C#. Группы и формулировки — как в Rider, но на страницах **только то, за чем есть реализация**: выключенных
  опций-заглушек «как в Rider, под замком» нет (убраны по решению пользователя 2026-09-21), опция появляется вместе с тем, что она включает.
  Есть тест на состав страниц и на отсутствие выключенных контролов (`SettingsPagesTest`).

## Соглашения кода

- Kotlin, строки до ~180 символов, плотный стиль; однострочные функции-выражения — норма.
- Комментарии и KDoc — **по-английски**, короткие, объясняют «почему» (часто с отсылкой «as in Rider»). `ROADMAP.md` и общение с пользователем — по-русски.
- Тексты UI — английские, в Title Case для действий, формулировки сверять с Rider.
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

## Тесты

`src/test/kotlin/io/github/dotnetsupport/*Test.kt`, JUnit 3-стиль на `BasePlatformTestCase` (`fun testXxx()`), файлы через `myFixture.addFileToProject`.
- Light-проект **общий для тестов класса и между классами**: run configurations, файлы с тем же путём и т.п. переживают тест.
  Брать уникальные имена проектов и убирать за собой то, что регистрируется (`RunManager`, listeners).
- Реальный `dotnet` в тестах не запускать; HTTP подменяется (`NuGetClient(fetch = ...)`).
- Tool window в тестах — `ToolWindowHeadlessManagerImpl.MockToolWindow`, disposable освобождать вручную.
- Есть тесты на состав UI (вкладки окна NuGet, состав меню) — при изменении состава обновлять их.

## Git

Коммитить только по просьбе. В рабочем дереве обычно лежит незакоммиченная работа пользователя по нескольким фичам сразу — чужие изменения не трогать и не откатывать.
