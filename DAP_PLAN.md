# Отладка через DAP: план интеграции

Статус: **действующий план, слои 1–5 сделаны и проверены вживую (2026-09-22)**; не сделан только второй адаптер (netcoredbg) — по решению
пользователя отложен. Код — пакет `debugger` в основной части
плагина. История: 2026-09-21 отладчик был сделан на платформенном DAP-клиенте (`PLATFORM_DAP_PLAN.md`, этапы 0–5 и completion), 2026-09-22 перенесён
на свой клиент: модуля `intellij.platform.dap` нет в IntelliJ IDEA Community и её форках. Находки того плана (журнал) — про адаптер и XDebugger, они
действуют и здесь. Отладчик — `dotnet-debugger` (MIT, на ICorDebug, говорит по Debug Adapter
Protocol), ставится как dotnet tool: пакет `dotnet-debugger-dap`, команда `dotnet-debugger`. Результаты его проверки на
Windows и Linux — в `dap-probe/FINDINGS.md`, там же скрипты, которыми можно прогнать любую новую версию.

## Платформенный DAP (найдено 2026-09-21, при переходе на 2026.1)
В платформе 261 есть модуль `intellij.platform.dap` (`lib/intellij.platform.dap.jar`, `visibility="public"`, весь API `@Experimental`): клиент протокола, сессия,
точки останова, стек, переменные, evaluate и мост в XDebugger (`DapProgramRunner`, пакет `xdebugger`). Точки расширения:
`com.intellij.platform.dap.debugAdapterSupportProvider` (`DebugAdapterSupportProvider` → `DebugAdapterDescriptor`) и `...launchArgumentsProvider`
(`DapLaunchArgumentsProvider`). Таймауты — ключи реестра `dap.timeout.*`, трассировка — `dap.message.trace.dir`. Им пользуются JS-отладчик и Jupyter.
Отладчик на нём был сделан (`PLATFORM_DAP_PLAN.md`) и заменён своим клиентом 2026-09-22: модуль включается по продуктам (`productModuleV2`,
набор `ide.ultimate`), в IntelliJ IDEA Community и её форках его нет, а плагин обязан отлаживать везде. Попутно исчезли и обходы платформы
(выбор остановившегося потока, переписывание `setBreakpoints` на пути к адаптеру ради hit count / logpoints, свой evaluator для hover).

## Решения
- **Отладчик — обычный dotnet tool плагина**, как `dotnet-counters`: строка на странице Settings | .NET (путь, Install /
  Update), поиск PATH → `~/.dotnet/tools` → путь из настроек. Id пакета и имя команды разные, в описании инструмента они разведены.
  В плагин отладчик не встраивается: версии обновляются независимо.
- **Свой DAP-клиент**, без LSP4IJ и без платформенного API: JSON поверх stdio адаптера с фреймингом `Content-Length`. Тот же
  слой фрейминга — фаза 1 из `LSP_PLAN.md`.
- **Платформенный XDebugger API** (есть во всех IDE на платформе, включая GoLand): UI отладки не пишется, пишется мост.
- Клиент не привязан к одному адаптеру: netcoredbg говорит тем же DAP с похожими аргументами `launch`, его можно подключить позже.

- **Настройки** — страница Settings | .NET | Debugger (`DotNetDebuggerConfigurable`), формулировки Rider, но только опции, за которыми
  есть реализация (заглушки под замком убраны 2026-09-21); новая опция появляется вместе с тем, что она включает. Blazor WASM, Predictive debugger,
  JIT-отладчик Windows — вне плана.

## Соответствие XDebugger ↔ DAP
| Платформа | DAP |
|---|---|
| `XDebugProcess` (старт, стоп, сессия) | `initialize` → `launch` / `attach` → `configurationDone`; `disconnect` / `terminate` |
| `XLineBreakpointType` для `.cs` + обработчик | `setBreakpoints` по файлу; событие `breakpoint` → «verified» и сообщение в UI |
| Условие, hit count, лог-сообщение точки останова | `condition`, `hitCondition`, `logMessage` |
| Точки останова на исключениях | `setExceptionBreakpoints`: фильтры `all` / `user-unhandled`, условия по типам |
| `XSuspendContext` / `XExecutionStack` / `XStackFrame` | событие `stopped`, `threads`, `stackTrace` (постранично) |
| Дерево `XValue` | `scopes`, `variables` — **всегда** с `start` / `count` |
| `XDebuggerEvaluator` (watches, hover, Evaluate) | `evaluate` с контекстом `watch` / `hover` / `repl` |
| Set Value | `setVariable`, `setExpression` |
| Step Over / Into / Out, Resume, Pause | `next`, `stepIn`, `stepOut`, `continue`, `pause` |
| Run to Cursor | временная точка останова + `continue` (или `gotoTargets` / `goto` для Set Next Statement) |
| Консоль | события `output` (stdout / stderr / console) |
| Attach to Process | `XAttachDebuggerProvider` → `attach` с `processId` |

## Слои
1. **Инструмент и DAP-клиент — сделано.** `DotNetTool.DEBUGGER` в `.NET Tools`; `DapConnection`: фрейминг `Content-Length`, корреляция по
   `request_seq` (ответы приходят не по порядку), события, запросы адаптера, ошибки (`DapException` с текстом адаптера), закрытие
   (`DapClosedException` всем ждущим). `DebugAdapterProcess` — процесс адаптера, останавливается убийством дерева. Тесты — `DapClientTest`.
2. **MVP отладки — сделано.** `DotNetDebugRunner` / `DotNetDebugProcess`: Debug у «.NET Project» (сборка своим билдом, аргументы `launch` — `DotNetLaunchArguments`),
   точки останова на строках, остановка, потоки, кадры порциями, переменные постранично, шаги, pause, evaluate / watches / hover, консоль, Stop.
3. **Полнота — сделано.** Условия / hit count / logpoints (`DotNetLineBreakpointProperties`), исключения как «Break when» Rider (`exceptionInfo`
   для описания), Set Value через `setExpression`, Run to Cursor, attach (`DotNetAttachDebuggerProvider`, повторный attach не предлагается),
   completion в выражениях. `restart` адаптера не используется намеренно: он перезапускает программу без сборки, то есть со старым кодом;
   Rerun платформы в окне Debug останавливает сессию, собирает проект задачей «Build .NET Project» и начинает новую — как Restart в Rider.
4. **Отладка тестов — сделано.** `VSTEST_HOST_DEBUG=1` → attach к тестовому хосту через `DotNetProcessAttacher`; Debug у ▶, у конфигурации `dotnet test`
   и в окне Unit Tests. Microsoft.Testing.Platform — с 0.1.287 (проверено роботом в WSL, xunit.v3 3.2.2 + `TestingPlatformDotnetTestSupport`,
   площадка `TestsMtp`, `BP:mtp-test`): `TESTINGPLATFORM_WAIT_ATTACH_DEBUGGER=1`, приложение ждёт отладчик. Строку «Waiting for debugger to
   attach... Process Id: N» `dotnet test` SDK 8 / 9 отдаёт только после конца приложения (MSBuild её держит), поэтому плагин ищет приложение
   среди потомков `dotnet test` (`TestingPlatformDebug`: `--internal-msbuild-node` / `--dotnet-test-pipe`, или имя сборки) и подключается;
   после подключения платформа, как хост VSTest, зовёт `Debugger.Break()` — эта остановка пропускается. Найдено по дороге: xunit.v3 с одним
   `TestingPlatformDotnetTestSupport` плагин считал VSTest-проектом (а `dotnet test` гнал его через платформу, «MTP0001: VSTest-specific
   properties ... ignored») — `MsBuildProject.usesTestingPlatform`.
5. **Полировка — сделано (2026-09-22, проверено UI-роботом).**
   - Значения в редакторе (`computeInlineDebuggerData`).
   - Async-стек: адаптер отдаёт его в `stackTrace` после кадра с подсказкой `label` («[Async Call Stack]»); кадр-метка не показывается, а
     становится разделителем над первым ожидающим методом (`StackFrames`, `XStackFrameWithSeparatorAbove`). Evaluate в таких кадрах адаптер
     не умеет (метод приостановлен), переменные — поля state machine.
   - Ввод в программу: `launch` с `console: integratedTerminal`, адаптер присылает `runInTerminal` — запустить себя помощником
     (`--run-in-terminal`), помощник создаёт программу со своими потоками. Плагин запускает помощника с pipes (`DebugTerminal`): вывод идёт в
     консоль отладки, набранное там — в stdin программы. Кодировка UTF-8, как у Run. На Windows у помощника скрытая консоль в OEM-кодировке,
     и в терминальном пути адаптер её сам не переключает (переключает только для программы, которую запускает сам): плагин вызывает его же
     `--console-utf8 <pid помощника>`. Это гонка со стартом программы, но выигрышная: программа стартует только после attach адаптера.
     Правильное место исправления — адаптер (`OnRuntimeStartup` переключать консоль и при внешнем запуске); тогда вызов в плагине станет лишним.
   - Set Next Statement: действие `DotNet.Debugger.SetNextStatement` в контекстном меню редактора (после Run to Cursor): `gotoTargets` по строке
     каретки → `goto` для потока остановки → адаптер присылает `stopped` с причиной `goto`. Пустой список целей (другой метод) — сообщение, позиция
     не меняется. Своего действия у платформы нет; горячая клавиша Rider (Ctrl+Shift+F10) в раскладке IDEA занята, поэтому без клавиши.
   - Точки останова в лямбдах (0.1.287, проверено роботом в WSL, `BP:lambda-variants`): у строки с лямбдами платформа предлагает варианты
     (`computeVariants`: «Line» и каждая лямбда, телом которой начинается на строке; в 2026.1 — inline-точками в строке), точка лямбды хранит
     её номер на строке (`lambdaOrdinal`, как у Java) и уходит адаптеру с колонкой начала тела — он ставит её на точку следования в лямбде.
     Лямбды — из дерева csharp-psi (`CSharpBreakpointLambdas`); у эвристического дерева выражений нет, там вариантов нет.
   - Точки останова на методе по имени (0.1.287, проверено роботом в WSL): тип «.NET Function Breakpoints», «+» в диалоге Breakpoints,
     `setFunctionBreakpoints` (`Namespace.Class.Method`, `Class.Method`, `Method`; условие и hit count). Платформа помечает «verified» только
     строчные точки — отказ адаптера пишется в консоль.
   - Вкладка Modules (0.1.287, проверено роботом в WSL): загруженные сборки по событиям `module` адаптера с самого старта программы — номер
     загрузки, имя, состояние символов, путь. Версии и «user code» `dotnet-debugger` не присылает — колонок нет.
   - **Не сделано:** второй адаптер (netcoredbg) — отложен; Smart Step Into и возвращаемые значения — сначала узнать, умеет ли адаптер.
6. **.NET Framework — сделано (0.1.69, адаптер 0.2.0, проверено UI-роботом).** Debug проекта старого формата — `program` = `TargetPath`,
   рабочая папка — папка вывода; 32-битные программы и процессы адаптер не отлаживает, плагин отказывает им сам до запуска адаптера
   (`run/DebugBitness`, PE-заголовок / `IsWow64Process`); attach к net4x без ключа реестра. Подробности и факты — `NET_FRAMEWORK_PLAN.md`, п. 4.
   32-битные программы отлаживать не собираемся (решение пользователя 2026-10-19): отказ до адаптера — окончательное поведение, а не обход.

Узкое место то же, что всюду: UX отладки проверяется только в живой IDE (UI-робот — `tools/ui-robot`, сценарии — `debug-playground`).

## Что клиент обязан учитывать (по `dap-probe/FINDINGS.md`)
- `variables` — только постранично: без `start` / `count` большой список убивает адаптер на Linux (OOM) и на минуты
  блокирует его на Windows. Длинный запрос блокирует адаптер целиком, включая `disconnect`: на стороне плагина нужен
  таймаут и принудительное завершение процесса адаптера.
- Неявные вычисления (раскрытие объекта, `ToString()`) могут стоить 5 с на значение, а на Linux «вечный» геттер теряет
  сессию: раскрывать лениво, не раскрывать автоматически, дать пользователю выключатель.
- Необработанное исключение сейчас не останавливает отладчик, а на Windows ещё и отдаёт код выхода 0: до исправления
  в адаптере IDE этого не обойдёт — включать фильтр `all` по умолчанию нельзя, слишком шумно.
- Вывод программы не в ASCII на Windows приходит испорченным (кодовая страница): тоже чинится только в адаптере.
- `stepIn` в async-метод не работает: до исправления подсказывать Run to Cursor.
- Второй `attach` к тому же процессу на Windows убивает процесс: не предлагать attach к процессу, который уже отлаживается.
- Строки длиннее 4096 символов приходят ошибкой: показывать её как есть, не падать.
- Адаптер умирает от некорректного JSON: клиент никогда не шлёт ничего, кроме собственных сериализованных сообщений.

## Проверка
- Транспорт и клиент — юнит-тесты на фейковом адаптере (сценарный обмен через pipe).
- Мост XDebugger — платформенные тесты там, где API это позволяет; остальное — скрипты `dap-probe/` как приёмочные
  сценарии (тот же тестовый проект, те же маркеры `// BP:`).
- Настоящий адаптер в тестах — только если найден (`DAP_ADAPTER` или установленный tool); иначе тест пропускается.
- UX — вручную в GoLand после каждого слоя.

## Закрыто: потерянная остановка в WSL (найдено 2026-10-19, проверка R8 Razor роботом; разобрано в тот же день — не ошибка плагина)

Разбор: Stop + новый Debug трижды подряд и Stop and Rerun кнопкой (в том числе со старым сеансом, стоящим на точке) — каждый сеанс
останавливается на точке, после Stop процессов адаптера, помощника и программы не остаётся. «Потерю» делал робот: `robot.py run` запускал
конфигурацию через `ProgramRunnerUtil.executeConfiguration`, а этот путь платформы (2026.1, `ExecutionManagerImpl.executeConfiguration`)
сразу зовёт runner и не проходит `restartRunProfile` — запущенный экземпляр не останавливается и не спрашивается «Stop and Rerun». Вторая
программа падала на занятом порту («Failed to bind to address http://127.0.0.1:5000: address already in use»), запрос уходил в первую — в
старый сеанс; так же и у Run (`stop_all.js` останавливает только сеансы отладки). Ещё одна ловушка проверки: `curl … &` в вызове `wsl`
умирает вместе с ним — страница не запрашивалась вовсе. Исправлено в роботе: `run` идёт путём кнопки (`ExecutionUtil.runConfiguration`),
скрипты `run_like_toolbar.js`, `running_probe.js` (что платформа знает о запущенных экземплярах). Ниже — исходная запись.

В песочнице WSL (Linux, `dotnet-debugger` из `~/.dotnet/tools`) первый сеанс отладки после старта IDE останавливается на точке
нормально, а второй и следующие (после Stop того же run configuration) — нет: `setBreakpoints` отвечает, событие `breakpoint`
`changed` даёт `verified: true`, программа на точке встаёт (запрос страницы висит, Pause показывает поток ровно на строке точки),
но события `stopped` адаптер не присылает (трасса `dotnet.debugger.protocol.trace`, `~/idea-dotnet-logs/dotnet-debugger/protocol/`).
Так и с точкой в `.cs`, не только в `.razor`. После Stop остаются живые процессы `dotnet-debugger` прошлых сеансов. Разобрать:
кто из живых адаптеров держит событие, убивает ли Stop дерево `--run-in-terminal`, повторяется ли на Windows.

