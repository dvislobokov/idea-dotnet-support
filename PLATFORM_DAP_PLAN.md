# Отладчик на платформенном DAP: поэтапный план

Статус: **этапы 0–5 сделаны и проверены вживую (пользователем и UI-роботом, 2026-09-21)**; остался этап 6 (полировка, по желанию) (2026-09-21, см. «Журнал»; план составлен 2026-09-21). Заменяет слои 1–3 из `DAP_PLAN.md` (свой DAP-клиент): клиент, сессию и мост в XDebugger
даёт модуль платформы `intellij.platform.dap`, мы описываем адаптер и дописываем недостающее. `DAP_PLAN.md` остаётся как запасной путь
и как справка (таблица XDebugger ↔ DAP, особенности адаптера).

## Промпт для новой сессии

Скопировать целиком:

```text
Работаем по плану PLATFORM_DAP_PLAN.md (корень репозитория): отладчик .NET на платформенном DAP-клиенте IntelliJ 2026.1.

Перед началом прочитай: PLATFORM_DAP_PLAN.md целиком, раздел «Что клиент обязан учитывать» в DAP_PLAN.md,
«What works» и заголовки находок в dap-probe/FINDINGS.md, дескриптор src/main/resources/io.github.dotnetsupport.dap.xml.
API платформы не угадывай: сигнатуры смотри в tools/platform-api/api.json (ключ "dap") или javap-ом по
"C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\lib\intellij.platform.dap.jar" (javap — в ~/.jdks/jdk-25*/bin);
как API используют штатные плагины — в модулях intellij.javascript.debugger.dap.launcher и intellij.jupyter.py.dap той же IDE.

Правила:
1. Иди по этапам по порядку, один этап за раз. Этап закончен, когда выполнены его «Готово, когда» и зелёные
   ./gradlew.bat test buildPlugin. После этапа — остановись, скажи, что сделано, что проверено тестами, а что я должен
   проверить вживую (чек-лист из этапа), и жди моего ответа. Живую IDE ты проверить не можешь — говори это прямо.
2. Весь код, которому нужен платформенный DAP, — только в пакете io.github.dotnetsupport.dap (content-модуль плагина);
   расширения — только в io.github.dotnetsupport.dap.xml. Остальной плагин не ссылается ни на этот пакет, ни на
   com.intellij.platform.dap.*: без DAP модуль не грузится, а плагин обязан работать. Общение модуля с остальным плагином —
   через то, что уже есть в основном коде (run configuration, сборка, DotNetTool), в одну сторону: модуль → плагин.
3. Сначала штатное поведение платформы; переопределять (свой XDebugProcess, фабрика значений, обработчик точек останова)
   только там, где оно реально мешает или чего-то нет, и каждый раз писать в KDoc, почему.
4. API @Experimental: всё, что от него зависит, держи в тонких классах рядом, логику (аргументы launch, выбор dll,
   разбор вывода) — чистыми функциями с тестами без платформенного DAP.
5. Настоящий адаптер и dotnet в тестах не запускать. Сценарий с настоящим адаптером — отдельный тест, который пропускается,
   если адаптер не найден (DAP_ADAPTER или установленный tool).
6. Нашёл расхождение плана с реальным API или поведением адаптера — сначала поправь план (раздел «Журнал»), потом код.
7. Соглашения проекта — в CLAUDE.md (комментарии по-английски, ROADMAP.md по-русски, коммиты только по просьбе,
   чужие незакоммиченные изменения не трогать, python-скрипты с обратными слэшами — файлами, не heredoc).

Начни с этапа 0 и скажи, что из него получилось.
```

## Что уже известно (проверено 2026-09-21)

- **Модуль есть и работает без лицензии.** Диагностика «меню .NET → Probe Platform LSP / DAP API...» в IntelliJ IDEA 2026.1.4 без лицензии
  (`com.intellij.modules.ultimate` отключён): точки расширения DAP зарегистрированы, раннер `DebugAdapterRunner` есть, 98 из 98 классов API совпали
  с эталоном, content-модуль плагина `io.github.dotnetsupport.dap` загрузился, его расширение принято в `launchArgumentsProvider`, классы DAP ему видны.
  **Не проверено:** реальная отладочная сессия в этом режиме; наличие модуля в GoLand / PyCharm / WebStorm / Rider 2026.1 (модуль подключён
  в наборе `ide.ultimate`; смотреть той же диагностикой).
- **Каркас готов:** `<content><module name="io.github.dotnetsupport.dap"/></content>` в `plugin.xml`, дескриптор
  `resources/io.github.dotnetsupport.dap.xml` (`package`, зависимость на `intellij.platform.dap`), `bundledModule("intellij.platform.dap")` в
  `build.gradle.kts`, заготовки `DotNetDebugAdapterId` и `DotNetDapLaunchArgumentsProvider` (`isApplicable = false`).
- **Адаптер:** dotnet tool `dotnet-debugger-dap`, команда `dotnet-debugger`, DAP по stdio; уже заведён как `DotNetTool.DEBUGGER`
  (поиск, Install / Update на странице .NET Tools). Умеет (FINDINGS, «What works»): условия, hit count, logpoints, фильтры исключений с типами,
  `setVariable` / `setExpression`, `gotoTargets` / `goto`, `restart`, `runInTerminal`, launch по `program` (dll или apphost) и по `project` + `build`.
- **Что платформа закрывает сама:** запуск адаптера (`CommandLineDebugAdapterHandle`), `initialize` / `launch` / `attach` (аргументы — произвольная карта),
  точки останова на строках с условием, точки останова на исключениях, потоки, стек порциями, переменные **постранично** (`start` / `count`),
  evaluate, шаги, pause, terminate / disconnect с таймаутами (`dap.timeout.*`), консоль, прогресс. Трассировка протокола — ключ реестра `dap.message.trace.dir`.
- **Чего в платформе нет:** `setVariable` / `setExpression`, `hitCondition`, `logMessage`, `restart`, `runInTerminal`, `completions`, `cancel`,
  `gotoTargets`, `stepInTargets`, `modules` / `loadedSources` / `source`, function / data breakpoints. Подробно — `docs/platform-lsp-dap.html`.
- **Точки расширения клиента** (по сигнатурам из jar, вживую не пробовано): `DebugAdapterDescriptor` — `launchDebugAdapter`, `configureProfileState`,
  `getDebugAdapterServerClass`, `createClient`, `createInitializeParams`, `beforeSessionStart`, `getBreakpointsDescription`, `createVirtualFileResolver`,
  `createXDebugProcess`; любой запрос протокола — `session.commandProcessor.submitCommand { server.… }` (`CommandScope.getServer()` отдаёт сырой
  `IDebugProtocolServer`); `DapXDebugProcess`, `AbstractDapXValue`, `DefaultDapXStackFrame` — open, `DapXDebuggerPresentationFactory` — интерфейс;
  `DapSourceBreakpointHandler`, `DefaultDapXValue`, `DefaultDapXDebuggerEvaluator` и стандартные обработчики событий в `DapClient` — `final`.

## Этапы

Оценки — дни работы, без живой проверки. После каждого этапа — проверка пользователем в IDE по чек-листу.

### Этап 0. Разведка API на живом примере (0,5 д)
Цель — не угадывать, как платформа связывает run configuration, раннер и адаптер.
- Разобрать по байткоду `com.intellij.platform.dap.impl.DapProgramRunner` (`canRun`, `execute`): по чему он решает, что профиль «его»
  (через `DapLaunchArgumentsProvider.isApplicable`?), когда вызывается `configureProfileState`, что попадает в `ExecutionResult`, кто запускает процесс
  отлаживаемой программы — адаптер по `launch` или `RunProfileState`.
- Разобрать, как это делают `intellij.javascript.debugger.dap.launcher` и `intellij.jupyter.py.dap` (дескриптор, аргументы, типы точек останова).
- Выяснить, что несёт `DapSourceBreakpoint` (есть ли место под hit count / log message) — от этого зависит цена этапа 4.
- Итог: раздел «Журнал → Этап 0» в этом файле: схема вызовов в 10–15 строк и поправки к этапам 1–4.

**Готово, когда:** схема записана, спорные места этапов 1–2 уточнены. Кода плагина этап не меняет.

### Этап 1. Первая остановка на точке останова (1–1,5 д)
- `DotNetDebugAdapterSupportProvider` (`platform.dap.debugAdapterSupportProvider`) → `DotNetDebugAdapterDescriptor`:
  `launchDebugAdapter` запускает `dotnet-debugger` через `DotNetTool.DEBUGGER` и `CommandLineDebugAdapterHandle`; адаптер не найден —
  `ExecutionException` с понятным текстом и действием «Install» (как у остальных tools).
- `DotNetDapLaunchArgumentsProvider`: применим к `DotNetRunConfiguration` с командой `run` и executor-ом Debug; аргументы `launch` — чистой функцией
  `DotNetLaunchArguments.build(...)`: `program`, `args`, `cwd`, `env` (поле Environment + профиль `launchSettings.json`, включая `applicationUrl` →
  `ASPNETCORE_URLS`), `justMyCode`, `console: internalConsole`. Тесты на функцию.
  `isApplicable` обязательно проверяет executor: `DapProgramRunner` берёт и Run (журнал, п. 1).
- `DotNetRunConfiguration.getState` для executor-а Debug отдаёт состояние, которое ничего не запускает (`execute` → `null`): платформа исполняет
  `RunProfileState` перед сессией, а программу запускает адаптер (журнал, п. 2). Правка в основном коде, без ссылок на DAP.
- Сборка и путь к dll (журнал, п. 3): before-run task «Build .NET Project» в основном коде — наш Build (ошибки — в окно Build и в редактор), при ошибке
  запуск отменяется; после сборки `dotnet msbuild -getProperty:TargetPath` с выбранными конфигурацией и TFM, разбор — чистой функцией, результат —
  в user data `ExecutionEnvironment`. Дескриптор в `createXDebugProcess` дописывает `program`; пути нет — `launch` с `project` + `build`.
- `CSharpLineBreakpointType : XLineBreakpointType` для `.cs` (ставится на строках с кодом: не в комментариях, не на пустых, не на `using` / объявлениях
  без тела — по лексеру и `CSharpDeclarations`) и `DapBreakpointsDescription`. Тип исключений обязателен (non-null): `DotNetExceptionBreakpointType` —
  заготовка без обработчика до этапа 3 (журнал, п. 4).
- Кнопка Debug у наших конфигураций и действие `DotNet.DebugProject` ведут в DAP там, где модуль загружен; где его нет — прежнее поведение
  (понять, какое оно сейчас, и не сломать).

**Готово, когда:** тесты на аргументы, выбор dll, места для точек останова, регистрацию расширений модуля; сборка зелёная.
**Проверить вживую:** консольное приложение — точка останова в `Main`, Debug → остановка, видны кадры и локальные переменные, F8 / F7 / Shift+F8,
Resume, вывод программы в консоли, Stop завершает и программу, и адаптер (нет висящих `dotnet-debugger` в диспетчере задач). То же в IDE без лицензии.
При проблемах — включить `dap.message.trace.dir` и приложить трассу.

### Этап 2. Пригодно для ежедневной работы (2 д)
- ASP.NET Core: профили `launchSettings.json`, `launchBrowser`, переменные окружения; остановка в обработчике запроса.
- Evaluate / Watches / hover — штатные; проверить контексты и ошибки вычисления (тексты адаптера показывать как есть).
- Значения: строки > 4096 символов приходят ошибкой — показывать её, не падать; «дорогие» значения (`ToString()` до 5 с на значение, FINDINGS №9) —
  раскрывать лениво. Если штатное дерево раскрывает автоматически — своя `DapXDebuggerPresentationFactory`.
- Зависший адаптер: длинный запрос блокирует его целиком, `disconnect` включительно (FINDINGS №6). Проверить, что делает платформа по таймауту;
  если процесс адаптера остаётся — переопределить `stopAsync()` и убивать процесс (он наш, из `launchDebugAdapter`). На Linux — и отлаживаемый процесс (L4).
- Сопоставление путей (`createVirtualFileResolver`), если штатное не открывает файлы из других проектов solution / с другой формой пути.
- Run to Cursor — штатный `runToPosition` (временная точка останова); проверить.
- Снять замки на странице Settings | Tools | .NET | Debugger с опций, за которыми теперь есть реализация (Save all files on launch, Just My Code, …),
  обновить `SettingsPagesTest`.

**Проверить вживую:** веб-проект с профилем https; большая коллекция (100k элементов) раскрывается порциями и не вешает IDE; объект с «вечным»
геттером не теряет сессию; Stop во время долгого вычисления срабатывает за секунды.

### Этап 3. Исключения и Set Value (1,5–2 д)
- Точки останова на исключениях: тип для фильтров адаптера (`all`, `user-unhandled`) с условием по типам; `doesExceptionMatchBreakpoint`.
  Штатного обработчика нет — свой `XBreakpointHandler` в `getBreakpointHandlers()` нашего наследника `DapXDebugProcess` (журнал, п. 4).
  По умолчанию `all` выключен (шумно), `user-unhandled` включён. Ограничение адаптера: необработанное исключение сейчас не останавливает (FINDINGS №1) —
  не обходить, отметить в UI / ROADMAP.
- Set Value: наследник `AbstractDapXValue` с `getModifier()` → `server.setVariable(...)` (а для выражений — `setExpression`) через
  `commandProcessor.submitCommand`; после успеха обновить узел. Своя `DapXDebuggerPresentationFactory` (скорее всего уже есть с этапа 2).
- Restart: штатный Rerun (стоп + старт); `restart` протокола — только если это заметно быстрее, решить по факту.

**Проверить вживую:** остановка на брошенном `InvalidOperationException` с фильтром по типу; F2 на переменной меняет значение, и программа идёт с ним дальше.

### Этап 4. Hit count, logpoints (1–2 д, зависит от этапа 0)
- Штатным путём нельзя: платформа отправляет только `line` / `column` / `condition` (журнал, п. 5).
- Свой `XBreakpointHandler` + `initBreakpointsCustomWay()` в `getBreakpointHandlers()` с прямым `server.setBreakpoints(...)` по файлу: тогда вся синхронизация (pending → verified по
  событию `breakpoint`, добавление и удаление на ходу, временные точки для Run to Cursor) на нас — оценить, стоит ли, или ждать платформу.
- UI: «Log message» платформы → `logMessage`, поле hit count — в панели свойств типа точки останова (`XBreakpointCustomPropertiesPanel`).

### Этап 5. Attach и отладка тестов (2 д)
- `XAttachDebuggerProvider`: список .NET-процессов (есть в `monitor`), `attach` с `processId`. Не предлагать процесс, который уже отлаживается
  (второй attach на Windows убивает его, FINDINGS №5); не-.NET процесс не предлагать (адаптер рапортует успех, №14).
- Тесты: `dotnet test` с `VSTEST_HOST_DEBUG=1`, PID из вывода — чистой функцией, затем attach; Debug у ▶ в редакторе и в окне Unit Tests.

### Этап 6. Полировка (по желанию, 2 д)
Значения в редакторе (inline), async-стек, `runInTerminal` (свой наследник `DapClient` с обратным запросом) для консольных программ с вводом,
Set Next Statement (`gotoTargets` / `goto`), completion в Evaluate, второй адаптер (netcoredbg) тем же дескриптором.

## Риски и развилки
| Риск | Как узнаем | Что делаем |
|---|---|---|
| `DapProgramRunner` без лицензии отказывается запускать | этап 1, живая проверка в IDE без лицензии | запасной путь ниже |
| Модуля нет в GoLand / PyCharm / WebStorm | диагностика Probe в этих IDE (`plugin_modules: MISSING`) | там отладки нет, либо запасной путь |
| API поменяли в 2026.2 | диагностика покажет `PARTIAL` со списком членов; сборка против новой IDE (`-PlocalIdePath=…`) | тонкие классы-адаптеры, правка в одном пакете |
| Штатное дерево переменных зависает на адаптере | этап 2 | своя фабрика значений, выключатель «не вычислять свойства» |
| Своя синхронизация точек останова слишком дорога | этап 0 / 4 | этап 4 отложить, оставить условия (они штатные) |

**Запасной путь** (если платформа упрётся): в том же модуле свой клиент на `org.eclipse.lsp4j.debug` из платформы
(`DSPLauncher.createClientLauncher` + `IDebugProtocolClient`) и мост в XDebugger по таблице из `DAP_PLAN.md`; описание адаптера, аргументы `launch`,
типы точек останова и тесты переиспользуются. Для IDE без модуля DAP — отдельный content-модуль без зависимости на него.

## Проверка
- Чистые функции (аргументы, dll, PID тестового хоста, места точек останова) — обычные тесты.
- Регистрация: тест на состав `io.github.dotnetsupport.dap.xml` (все классы в пакете модуля) уже есть в `PlatformApiProbeTest`, расширять его.
- Приёмочные сценарии — solution `debug-playground/` (консоль со сценариями, библиотека, ASP.NET, multi-target, несобирающийся проект),
  маркеры `// BP:<имя>` в коде и чек-лист по этапам в `debug-playground/README.md`. Новую проверку — сначала сценарием туда.
- Всё, что видно только в UI, — чек-листы этапов, проверяет пользователь; в ответе после этапа прямо писать «вживую не проверено».

## Журнал
Сюда — находки по ходу работы, которые меняют план (дата, этап, что выяснилось, что поправлено).

### Этап 0 (2026-09-21) — разведка по байткоду, вживую не проверено
Источники: `javap -p -c` по `intellij.platform.dap.jar`, `intellij.jupyter.py.dap.jar`, `intellij.javascript.debugger.dap.launcher.jar` (IU-261).

Схема вызовов:
```text
Debug ▶ → ProgramRunner.getRunner → DapProgramRunner.canRun(executorId, profile)
            = есть DapLaunchArgumentsProvider с isApplicable(executorId, profile)
              И (executor Debug, профиль не RunConfigurationWithSuppressedDefaultDebugAction
                 ИЛИ executor Run, профиль не ...SuppressedDefaultRunAction)      ← раннер берёт и Run, и Debug
        → (before-run tasks платформы) → profile.getState(executor, env)
        → DapProgramRunner.execute(env, state)                                    ← синхронно, поток запуска (EDT)
            provider.getLaunchArguments(project, profile) → LaunchRequestArguments(adapterId, request, map)
            Debug: XDebuggerManager.newSessionBuilder(DapProcessStarter).environment(env).startSession()
            Run:   DapProcessStarter.startNoDebug()  (те же аргументы + noDebug=true)
        → DapProcessStarter.start(xSession)                                       ← синхронно
            descriptor = DebugAdapterSupportProvider с тем же adapterId .createDebugAdapterDescriptor(project)
                         (нет такого — ExecutionException)
            descriptor.beforeSessionStart(project); descriptor.configureProfileState(env, state)
            result = state.execute(executor, runner)                              ← RunProfileState ИСПОЛНЯЕТСЯ; null допустим
            descriptor.createXDebugProcess(xSession, dapSession, scopes, descriptor, env, result, request, map)
        → DapXDebugProcess.sessionInitialized() → корутина:
            dapSession.initialize(env, result): descriptor.launchDebugAdapter(env, result, sessionId) (suspend) → createClient,
                                                createInitializeParams → initialize
            dapSession.start(request, map) → launch / attach
            initBreakpointsCustomWay() ? — : xSession.initBreakpoints() → DapSourceBreakpointHandler → DapBreakpointManager
            ошибка DapInitializationException → нотификация, xSession.stop()
```
Процесс отлаживаемой программы запускает адаптер по `launch`; процесс-обработчик сессии — штатный (`DefaultDebugProcessHandler`), консоль —
`TerminalExecutionConsole`, в неё печатаются события `output` (`formatAndPrintOutput`, protected — можно переопределить).

Что это меняет:
1. **`isApplicable` обязан проверять `executorId == Debug`.** Иначе `DapProgramRunner` заберёт и Run (`noDebug`) и станет соперничать с обычным запуском.
2. **`DotNetRunConfiguration.getState` для Debug не должен запускать `dotnet run`**: платформа вызывает `state.execute(...)` перед сессией. Нужно
   состояние-пустышка (Jupyter возвращает из `execute` `null`). Это правка в основном коде, но без ссылок на DAP: «executor Debug → ничего не запускать».
   Сейчас раннера для Debug у конфигурации нет вовсе (кнопка Debug и `DotNet.DebugProject` выключены) — это и есть «прежнее поведение» без модуля.
3. **`getLaunchArguments` — синхронный, на потоке запуска**: `dotnet msbuild -getProperty:TargetPath` оттуда звать нельзя. Решение: свой before-run task
   «Build .NET Project» в основном коде (фоновый, отменяет запуск при ошибке сборки; полезен и сам по себе, как в Rider) — после сборки он же выясняет
   `TargetPath` и кладёт в user data `ExecutionEnvironment`; дескриптор в `createXDebugProcess(…, env, …, map)` дописывает `program` в карту (карта туда
   приходит копией и хранится как есть). Задачи нет или путь не выяснен — `launch` с `project` (+ `build`), адаптер разберётся сам
   (в `dap-probe/batch3.py` работают и папка, и `.csproj`; уважает ли он конфигурацию / TFM — не проверено). `beforeSessionStart` для сборки не годится:
   вызывается синхронно.
4. **Тип точек останова на исключениях обязателен** (`DapBreakpointsDescription` — оба параметра non-null), а штатного обработчика для них нет:
   `DapXDebugProcess.getBreakpointHandlers()` отдаёт только `DapSourceBreakpointHandler`. Значит, на этапе 1 — зарегистрированный тип-заготовка
   `DotNetExceptionBreakpointType`, на этапе 3 — свой `XBreakpointHandler` (по образцу `JupyterPyDapExceptionBreakpointHandler`):
   `DapExceptionBreakpoint.create(filter, condition, ideBreakpoint)` → `breakpointManager.addExceptionBreakpoint(scope, bp)` через `commandProcessor`.
5. **Этап 4 — только своим обработчиком.** `DapSourceBreakpoint` несёт `position`, `condition`, `isTemporary`, `ideBreakpoint`, `dapBreakpoint`;
   `DapBreakpointManagerImpl` заполняет в `SourceBreakpoint` только `line` / `column` / `condition` — `hitCondition` и `logMessage` не отправляются
   никогда, подсунуть их через интерфейс нельзя. Остаётся вариант «свой `XBreakpointHandler` + `initBreakpointsCustomWay()`» со всей синхронизацией на нас;
   решение «делать или ждать платформу» — перед этапом 4.
6. JS-модуль — не пример дескриптора, а помощник запуска адаптера на Node (`NodeJsBasedDebugAdapter(...)`); образец дескриптора и процесса — Jupyter
   (`JupyterPythonDapAdapterSupportProvider$createDebugAdapterDescriptor$1`, `JupyterPythonDapDebugProcess`). `CommandLineDebugAdapterHandle` —
   конструктор от `GeneralCommandLine`, `disconnect()` suspend. Проверок лицензии в `DapProgramRunner` / `DapProcessStarter` нет.

### Этап 1 (2026-09-21) — код и тесты, вживую не проверено
Сделано: `run/DotNetDebugLaunch.kt` (`DotNetLaunchArguments`, `MsBuildTargetPath`), `run/BuildProjectBeforeRun.kt`, состояние-пустышка для Debug в
`DotNetRunConfiguration.getState`, `lang/CSharpBreakpointLines`, в модуле — `DotNetDebugAdapter.kt` (провайдер + дескриптор), `DotNetBreakpointTypes.kt`;
тесты — `DebugLaunchTest`. Отложено и почему:
- Before-run task собирает только для Debug + `dotnet run`: Run / watch / test собирают сами, двойная сборка — лишние секунды. У конфигураций, созданных
  раньше, задачи может не быть (зависит от того, как платформа хранит список) — тогда сработает `launch` с `project` + `build`.
- `justMyCode` всегда `true` до настроек этапа 2. «Pass parent environment» конфигурации на Debug не действует: окружение программа наследует от адаптера.
- Редактор условий точки останова (`getEditorsProvider` у типа) не задан — посмотреть вживую, даёт ли платформа поле условия без него; если нет — этап 2.
- Тип исключений скрыт из диалога Breakpoints (`isAddBreakpointButtonVisible = false`) до этапа 3.
- Адаптер не найден: нотификация с Install (`DotNetTool.offerInstallation`) + `ExecutionException` из `launchDebugAdapter`, т.е. уже после открытия
  сессии — раньше платформа адаптер не спрашивает.

### Этап 1, живая проверка №1 (2026-09-21): «Build failed, но отладка не остановилась»
Причина (по `workspace.xml` и `idea.log`, трассы протокола не было): у сохранённых ранее конфигураций `<method v="2" />` — это явный пустой список
Before launch, задачи «Build .NET Project» в них нет. Сборку делал адаптер (`project` + `build: true`), а её провал сессию не завершает и в окно Build
не попадает. Поправлено: сборка адаптера — только последнее средство. Если before-run сборки не было (`DotNetLaunchArguments.BUILT` нет в окружении),
`DotNetDebugAdapterDescriptor.launchDebugAdapter` (suspend, вызывается до отправки `launch`) сам делает наш Build + `TargetPath`
(`DotNetDebugBuild.buildAndLocate`) и при ошибке бросает `ExecutionException` — сессия закрывается. Аргументы дописываются в ту же карту, что
отдана `DapXDebugProcess` в `createXDebugProcess` (процесс хранит её как есть — проверено по байткоду конструктора); другого асинхронного шага между
запуском и `launch` платформа не даёт. Минус пути без задачи: окно Debug успевает открыться до сборки. Вопрос к адаптеру: `launch` с `build: true`
при провале сборки должен отвечать ошибкой.

### Этап 1, живая проверка №2 (2026-09-21): «отладка будто не запускается», в списке потоков не Main Thread
Причина — в платформе (`DapEventConsumerImpl$stopped$1`): остановившийся поток ищется `binarySearchBy { id }` по списку из ответа `threads`, список
не сортируется. У `dotnet-debugger` id — системные, в порядке создания потоков; поиск промахивается, `stoppedAtThread = null`, и
`DapXDebugProcess` берёт первый поток в состоянии `Paused` (при `allThreadsStopped` — любой). Срабатывает «иногда», потому что зависит от того,
как легли id. Обход: `DotNetDebugProcess` (наследник `DapXDebugProcess`, только ради фабрики) + `DotNetPresentationFactory.createSuspendContext` —
активный поток берётся по `threadId` из `StoppedEventArguments`, которые платформа хранит в `DapThreadState.Paused.rawEvent`.
Надёжнее всего — чтобы адаптер отдавал `threads` отсортированными по id; обход после этого не мешает. Стоит сообщить в JetBrains.

Логи: лог адаптера пишется на каждую сессию (`dotnet-debugger --log=…`, `<логи IDE>/dotnet-debugger/adapter-*.log`, хранятся последние 20, ключ
реестра `dotnet.debugger.adapter.log`); меню .NET → Show Debugger Logs открывает папку, .NET → Trace Debugger Protocol включает трассу DAP-сообщений
платформы (`dap.message.trace.dir` → `…/dotnet-debugger/protocol`, действует со следующей сессии).

### Этап 2 (2026-09-21) — код и тесты, вживую не проверено
Этап 1 проверен пользователем вживую (остановка, шаги, переменные, логи). По этапу 2 сделано:
- **Исходники адаптера** (`~/dotnet-debugger`, новее установленного 0.1.0) показали аргументы `launch`, которых не было в FINDINGS: `configuration`,
  `launchSettingsProfile` (null — адаптер сам применяет профиль по умолчанию, `""` — не применяет), `allowImplicitFuncEval`, `enableStepFiltering`,
  `sourceFileMap`, `symbolOptions`, `suppressJitOptimizations`. Теперь шлём `launchSettingsProfile: ""` (профиль применяем мы, по правилам `dotnet run`),
  `configuration` (для запасного пути с `project`), `allowImplicitFuncEval`. Сборка адаптера при провале бросает ошибку `launch` — т.е. зависание из
  проверки №1 было не в адаптере, а в том, как платформа переживает ошибку `launch`; на нашем пути (своя сборка) это не возникает.
- **Процесс адаптера — свой `DotNetDebugAdapterHandle`** вместо `CommandLineDebugAdapterHandle`: штатный останавливает через soft kill (на Windows —
  Ctrl+C через helper), в `idea.log` пользователя это `SEVERE Cannot send Ctrl+C` на Stop, когда адаптер ещё жив. Наш ждёт 1 с после `disconnect`
  и убивает дерево процессов — это же закрывает зависший адаптер (FINDINGS №6) и отлаживаемый процесс на Linux (L4). stderr адаптера — в `idea.log`.
- **`launchBrowser` при отладке**: вывод программы приходит событиями `output`, `DotNetDebugProcess.formatAndPrintOutput` отдаёт его тому же
  `ListeningUrlListener`, что и Run.
- **Настройки** (Settings | Tools | .NET | Debugger), замки сняты с трёх опций: Save all files on debugger launch (сборка перед отладкой сохраняет
  документы или нет), Enable external source debug (= `justMyCode: false`; по умолчанию выключено, в отличие от Rider: декомпилятора нет),
  Allow property evaluations and other implicit function calls (= `allowImplicitFuncEval`).
- Без кода, только живая проверка: Evaluate / Watches / hover, Run to Cursor, длинные строки, большие коллекции, «дорогие» значения. Своя фабрика
  значений и `createVirtualFileResolver` — только если проверка покажет проблему.

### Живая проверка №3 (2026-09-21): «иногда F8 ничего не делает»
По логу адаптера (`adapter-20260921-141825.log`, проект `Web`): остановка `stopped` на потоке 52904, а `next` ушёл с `threadId` 29620 — потока, который
уже завершился; адаптер ответил `ERROR_INVALID_PARAMETER` (`CorDebugProcess.GetThread`), платформа ошибку шага не показывает (необработанное
исключение корутины в `idea.log`), сессия остаётся «на паузе». Шаг берёт поток из `session.suspendContext.activeThread`, т.е. это та же ошибка
платформы с выбором потока. Первый обход (искать поток по `DapThreadState.Paused.rawEvent`) был с гонкой: состояния потоков платформа
проставляет асинхронно (`pause` → `submitCommandAsync`), к моменту `createSuspendContext` нужный поток мог ещё не быть `Paused`, и оставался
выбор платформы. Теперь `threadId` берётся из самого события: `DotNetDebugAdapterDescriptor.createClient` оборачивает `DapEventConsumer`
(`StoppedThread.recording`), запись происходит до того, как платформа начинает обработку события. Не закрыто: ошибку неудавшегося шага
пользователь по-прежнему не видит (только `idea.log` и лог адаптера).

### Разбор логов адаптера (2026-09-21, 10 сессий 14:04–14:30)
- Исправление выбора потока работает: в сессии 14:28:55 (после установки сборки с `StoppedThread`) 32 `next` из 32 ушли с потоком события `stopped`;
  в сессиях до неё — `next` на завершившийся поток (14:18:39, 14:19:48, ответ `ERROR_INVALID_PARAMETER`).
- `continue` уходит с «чужим» `threadId` (первый поток списка) — безвредно: адаптер отвечает `allThreadsContinued: true`.
- «F8 ничего не делает» бывает и законным: `next` с последней строки обработчика запроса уводит исполнение во фреймворк (Just My Code), программа
  просто работает дальше; повторный F8 получает от адаптера «The debuggee is running.», платформа ответ не показывает (14:29:25).
- **Наша ошибка, исправлена:** сборка перед отладкой вызывалась через `invokeLater(..., ModalityState.any())`, а `saveAllDocuments` в таком контексте —
  `SEVERE Write-unsafe context` в `idea.log` на каждый запуск (13 раз). Теперь `ModalityState.nonModal()`.
- Нагрузка: на каждую остановку платформа запрашивает `stackTrace` по всем потокам (в ASP.NET ~12 запросов), адаптер отвечает за миллисекунды;
  запросов дольше 0,5 с и запросов без ответа нет.
- К адаптеру: `ICorDebugProcess.Terminate failed: CORDBG_E_PROCESS_NOT_SYNCHRONIZED` на каждом Stop во время паузы (нужен `Stop()` перед
  `Terminate()`; процесс в итоге завершается); `exited` всегда с `exitCode: 0`, в том числе при принудительном завершении; событие `continued` не шлётся.
- Не объяснено: сессия 14:17:15 получила `terminate` от IDE через 0,2 с после `launch` (в `idea.log` причины нет).

### Разбор логов, сессии 14:35 и 14:38 (2026-09-21)
- 14:38 (`Console: All`, 5 остановок, Resume до конца): чисто — ни одного отказа, весь вывод (включая кириллицу / 日本語 при `Console.OutputEncoding = UTF8`,
  stderr, `Debug.WriteLine` категорией `console`) дошёл, `exited` + `terminated`, штатные `terminate` / `disconnect`.
- 14:35, раскрытие `huge` (`List<int>` на 100k): платформа **постранична только для индексированных** детей. Сначала она шлёт
  `variables {filter: "named"}` без `start` / `count`, потом страницы `filter: "indexed", start, count: 100`. Адаптер понимает только `"indexed"`
  (`DebugAdapter.cs`, case `"variables"`), на `"named"` отдаёт всех детей: ответ 9,97 МБ за 6,66 с, адаптер на это время занят (FINDINGS №6, на Linux —
  L1 с OOM). Лечится в адаптере: при `filter: "named"` не материализовать и не отдавать индексированных детей. Обойти со стороны плагина можно только
  своей реализацией значений — не делаем, пока адаптер наш.

### Этап 2, живая проверка (2026-09-21)
Проверено пользователем: Monitor (только процессы IDE, программа под отладчиком в списке), браузер (в т.ч. `no browser` не открывает), Stop не
оставляет процессов, multi-target. Найдено: **hover в редакторе не работал** — `DefaultDapXDebuggerEvaluator` (final) реализует только `evaluate`,
«выражение под курсором» у него нет. Сделано: `lang/CSharpHoverExpression` (по токенам: идентификатор + цепочка доступа слева, `this`, `?.`; не
вызовы, не строки / комментарии / ключевые слова, не `Foo().x` и `a[0].x`) и в модуле `DotNetStackFrame` + `HoverEvaluator` — обёртка над штатным
вычислителем, подключена через `DotNetPresentationFactory.createStackFrame`. LSP для этого не нужен: стандартного запроса «вычислимое выражение» в
нём нет (ближайшее — `selectionRange`, пригодится для уточнения, когда LSP появится). Осталось проверить вживую: hover, Evaluate / Watches,
Run to Cursor, значения (длинная строка, «дорогие»), настройки страницы Debugger.

### Этап 3 (2026-09-21) — код и тесты, вживую не проверено
- **Исключения.** В исходниках адаптера фильтров уже три: `all`, `user-unhandled`, `unhandled` (последний закрывает FINDINGS №1 — необработанное
  исключение теперь останавливает; в установленном 0.1.0 его может не быть), условие фильтра — типы с `*` и `!`. Сделано по образцу «Break when» Rider:
  `DotNetExceptionBreakpointType` со свойствами «типы + thrown / user-unhandled / unhandled», панель свойств в диалоге Breakpoints, точка останова
  по умолчанию «Any exception (user-unhandled, unhandled)» — включена (платформа создаёт точки по умолчанию выключенными, включаем сами), «+» добавляет
  точку «thrown» под конкретные типы. `DotNetExceptionBreakpointHandler` (штатного нет) отдаёт менеджеру сессии по `DapExceptionBreakpoint` на каждый
  выбранный фильтр; методы менеджера — Kotlin-расширения на `CommandScope` (`with(breakpointManager) { addExceptionBreakpoint(bp) }`). Платформа
  при остановке на исключении без подходящей точки берёт точку по умолчанию нашего типа и сама включает её (`createDefaultExceptionBreakpoint`).
- **Set Value.** `DapVariable` платформы не отдаёт ссылку на контейнер, поэтому `setVariable` недоступен; используем `setExpression` по `evaluateName`
  и id текущего кадра. `DotNetValue : AbstractDapXValue` (штатный `DefaultDapXValue` — final) с `XValueModifier`; вид значения повторяет штатный.
  Без `evaluateName` (группы вроде Raw View) модификатора нет. Ошибка адаптера показывается его текстом.
- **Restart** — штатный Rerun, `restart` протокола не подключали.
- Чистые функции и тесты: `run/DotNetExceptionBreakpoints.kt`, `DebugLaunchTest` (настройки точки, тип и его точка по умолчанию, сериализация свойств,
  аргументы `setExpression`, текст ошибки).

### Этап 3, живая проверка UI-роботом (2026-09-21, адаптер 0.1.1, песочница IDE без лицензии)
Прогон на копии `debug-playground` (`build/ui-robot/debug-playground`: у песочницы и рабочей IDE общий `.idea` каталога проекта, чужие точки
останова трогать нельзя). Скрипты — `tools/ui-robot`.
- Диалог Breakpoints: группа «.NET Exception Breakpoints», точка по умолчанию включена, панель свойств (типы + три галочки) — ок.
- «Thrown» + `Playground.Lib.ShopException`: остановка на `Scenarios.cs:149`, значок исключения, «Out of stock», `$exception`; после Resume на
  `FormatException` остановки нет, программа доходит до `Done.` — ок.
- Типы `System.*, !System.FormatException` останавливают и на `ShopException`: адаптер сравнивает условие с типом **и всеми базовыми**
  (`System.Exception`). Не ошибка; формулировка в README песочницы поправлена.
- `Console: Crash`: остановка на необработанном исключении — ок, но единственный кадр `[External Code]` (к адаптеру).
- `Web` → `/fail`: остановка user-unhandled на `Program.cs:42` — ок.
- Set Value: `counter := 100`, `label := "after"`, `person.Age := 37` → программа печатает `counter=130 label=after age=37`; `counter := abc` →
  текст адаптера «The name 'abc' does not exist in the current context.» — ок.

Найдено и исправлено:
1. **Остановка на исключении не показывалась вовсе.** Платформа при `stopped` с `reason: exception` и `allThreadsStopped: true` запрашивает
   `exceptionInfo` у каждого потока; на потоках без исключения адаптер отвечает ошибкой, платформа её не ловит — обработка остановки срывается
   (программа стоит, IDE считает, что она работает). Обход: `StoppedThread.forPlatform` снимает `allThreadsStopped` у таких событий — платформа
   спрашивает только остановившийся поток. Цена: на время такой остановки остальные потоки числятся работающими.
2. **Выключенная точка по умолчанию всё равно останавливала.** Без активных точек платформа не шлёт `setExceptionBreakpoints` вовсе, адаптер
   применяет умолчания своих фильтров, а платформа, встретив остановку без точки, сама включает точку по умолчанию нашего типа
   (`createDefaultExceptionBreakpoint` → `setEnabled(true)`). Исправление: `NoExceptionBreakpoints` на событии `initialized` явно отправляет пустой
   список, когда активных точек нет. Проверено: `/fail` при выключенной точке — ответ 500 без остановки, точка остаётся выключенной.
Не лечится в плагине: фильтр `unhandled` у адаптера не выключается — на необработанном исключении он останавливается всегда
(`DebugEngine.SetExceptionFilters` читает только `all` и `user-unhandled`).
Попутно: `SEVERE Slow operations are prohibited on EDT` из `SmartRestore.isUpToDate` (`DotNetBuildOptions.kt:104`, чтение `project.assets.json` при
вычислении аргументов сборки на EDT) — давняя проблема любого Build, не отладчика; исправить отдельно.

### Этап 2, остаток живой проверки UI-роботом (2026-09-21, адаптер 0.1.1)
Скрипты — `tools/ui-robot/scripts` (`session.sh`: `evaluate`, `set_value`, `wait_state`; `run_to.js`, `debugger_settings.js`, `edit_unsaved.js`).
- Evaluate: `person.Friend.Name`, `number * 2`, `access.HasFlag(Access.Write)`, дерево `person`, кириллица — ок, единицы–десятки мс; `nope.x` →
  текст адаптера «The name 'nope.x' does not exist in the current context.».
- Run to Cursor (`runToPosition`) — ок, три перехода подряд.
- Коллекции: `huge` (100k) и `hugeArray` (5M) — адаптер отдаёт `namedVariables` / `indexedVariables`, платформа грузит страницами, ответы за мс
  (исправление `filter: "named"` в адаптере действует).
- Длинная строка: значение приходит обрезанным до 4096 символов, без ошибки и без вреда для сессии.
- «Дорогие» значения: `SlowToString` описывается типом, без вызова `ToString()`; вечный геттер — «Evaluation timed out.» за 1,6 с; дерево отзывчиво.
  Своя фабрика значений ради этого не нужна.
- Stop во время вычисления — ~3 с, процессов не остаётся; Pause в `Console: Wait` — кадры в `Scenarios.Wait()`.
- Настройки: «Enable external source debug» → `justMyCode: false`, «Allow property evaluations…» → `allowImplicitFuncEval: false` — доходят до адаптера.
- **«Save all files on debugger launch» ни на что не влияла:** платформа сама сохраняет все документы перед любым запуском (с выключенной опцией
  несохранённая правка всё равно попала в сборку). Привязка убрана, опция снова под замком с причиной «The IDE saves all files before every launch».
- Лог песочницы за прогон: нет `Write-unsafe context`, нет `Cannot send Ctrl+C`, нет `Slow operations are prohibited on EDT`.

Исправлено попутно: `DotNetBuildService.run(target, command)` сохраняет документы на EDT, а аргументы (в них «Smart Restore» читает
`project.assets.json`) считает и процесс запускает на пуле — раньше любое Build давало `SEVERE Slow operations are prohibited on EDT`.
Не проверено роботом: hover (подсказки не снять), F7 во внешний код при «Enable external source debug», вторая тема.

### Этап 5 (2026-09-21) — attach и отладка тестов, проверено UI-роботом
- **Как устроено.** В основном коде — точка расширения `io.github.dotnetsupport.processAttacher` (`DotNetProcessAttacher`): плагин отладчика не знает,
  модуль `dap` её реализует. Attach-сессия идёт через тот же `DapProgramRunner`: процесс — это `RunProfile` (`DotNetAttachProfile`), провайдер аргументов
  отвечает на него запросом `attach` (`processId`, `justMyCode`, `allowImplicitFuncEval`).
- **Run | Attach to Process:** `DotNetAttachDebuggerProvider`, группа «.NET». Предлагается `dotnet` / `testhost` и apphost-ы (рядом `X.runtimeconfig.json`
  или `X.dll` + `X.deps.json`); не предлагается сам `dotnet-debugger`, IDE и процессы, которые уже под нашим отладчиком (`DebuggedProcesses`,
  наполняется из события `process`).
- **Stop = отсоединение.** Платформа при Stop всегда шлёт `terminate`, потом `disconnect {}`, а адаптер по `terminate` убивает процесс. Для attach
  `DotNetDebugProcess.stopAsync` сначала сам шлёт `disconnect {terminateDebuggee: false}`. Найдено вживую: **адаптер, отсоединяясь от процесса,
  стоящего на точке останова, роняет его** (от работающего — нет), поэтому перед `disconnect` шлём `continue`. Второе: когда процесс завершился сам
  (тестовый хост), соединение уже закрыто, команда не исполнится — тогда сразу штатная остановка, плюс страховочный таймаут 3 с (без этого сессия висела).
- **Отладка тестов:** `DotNetTestRunner` берёт и Debug, когда есть кому подключиться; `dotnet test` запускается с `VSTEST_HOST_DEBUG=1`, PID хоста
  берётся из вывода («Process Id: N, Name: testhost» — не локализуется даже в русском CLI), подключение — к каждому хосту один раз. Хост после
  подключения вызывает `Debugger.Break()` (остановка `reason: pause, description: Debugger.Break` во внешнем коде) — `InitialBreak` отвечает на неё
  `continue` и платформе не показывает. Debug у ▶ в редакторе появляется сам (`withExecutorActions`), в окне Unit Tests — «Debug Selected Tests».
  Проекты на Microsoft.Testing.Platform (без vstest-хоста) так не отладить — не проверялось.
- Проверено роботом: остановка в тесте (`PricingTests.cs:13`), локальные переменные, после Resume тест `Passed`, сессия закрывается сама; attach к
  `Console: Wait` и точка в его цикле; отсоединение на бегу и на точке останова — процесс жив.
- Попутно: id раннера платформы — `DebugAdapterRunner`, а не строка из её `plugin.xml`; старый тест «Debug уходит в DAP-раннер» из-за этого молча
  пропускался — исправлен.

### Этап 4 (2026-09-21) — hit count и logpoints без своего обработчика, проверено UI-роботом
Решение, которого в плане не было: платформа шлёт только `line` / `column` / `condition`, но **поток сообщений к адаптеру создаёт плагин**
(`DotNetDebugAdapterHandle`). `DapMessageRewritingStream` разбирает кадры `Content-Length` и в запросах `setBreakpoints` дописывает точкам
`hitCondition` / `logMessage` (`DapSetBreakpoints`, чистые функции в `run/DapBreakpointExtras.kt`); всё остальное проходит как есть, при любой
ошибке сообщение уходит неизменённым. Вся синхронизация точек остаётся штатной — свой `XBreakpointHandler` не понадобился.
- Свойства точки: `DotNetLineBreakpointProperties` (hit count, log message) + панель в диалоге Breakpoints; синтаксис hit count — как у адаптера
  (`5`, `== 5`, `>= 3`, `> 3`, `<= 3`, `< 3`, `% 10`), неверное значение не сохраняется. Лог-сообщение — шаблон адаптера с `{выражение}`.
- Найдено вживую: тип обязан переопределять и `createProperties()`, не только `createBreakpointProperties(file, line)` — иначе сохранённые свойства
  теряются при загрузке проекта; искать точку надо по `fileUrl`, `presentableFilePath` — относительный путь.
- Заодно закрыт хвост с условием: `getEditorsProvider` → `DefaultDapEditorsProvider`, у точки появилось поле Condition (и штатное «Evaluate and log»).
- Проверено роботом: hit count `3` — остановка на третьем проходе цикла (`i = 2`, `counter = 20`), одна за сессию; логпоинт печатает
  `greeting = Hello, Ada!, length = 11` без остановки; условие `i == 1`; свойства переживают перезапуск IDE.

Замечания к адаптеру по итогам этапов 3–5: отсоединение от процесса, стоящего на точке останова, убивает процесс; фильтр `unhandled` не
выключается; при остановке на необработанном исключении единственный кадр — `[External Code]`.
