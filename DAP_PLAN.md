# Отладка через DAP: план интеграции

Статус: **план; начат только слой 1** (2026-09-21). Отладчик — `dotnet-debugger` (MIT, на ICorDebug, говорит по Debug Adapter
Protocol), ставится как dotnet tool: пакет `dotnet-debugger-dap`, команда `dotnet-debugger`. Результаты его проверки на
Windows и Linux — в `dap-probe/FINDINGS.md`, там же скрипты, которыми можно прогнать любую новую версию.

## Решения
- **Отладчик — обычный dotnet tool плагина**, как `dotnet-counters`: строка на странице Settings | Tools | .NET (путь, Install /
  Update), поиск PATH → `~/.dotnet/tools` → путь из настроек. Id пакета и имя команды разные, в описании инструмента они разведены.
  В плагин отладчик не встраивается: версии обновляются независимо.
- **Свой DAP-клиент**, без LSP4IJ и без платформенного API: JSON поверх stdio адаптера с фреймингом `Content-Length`. Тот же
  слой фрейминга — фаза 1 из `LSP_PLAN.md`.
- **Платформенный XDebugger API** (есть во всех IDE на платформе, включая GoLand): UI отладки не пишется, пишется мост.
- Клиент не привязан к одному адаптеру: netcoredbg говорит тем же DAP с похожими аргументами `launch`, его можно подключить позже.

- **Настройки уже размечены**: страница Settings | Tools | .NET | Debugger (`DotNetDebuggerConfigurable`) перечисляет опции Rider под
  замком. По мере слоёв замки снимаются: слой 2 — Save all files on launch, Allow property evaluations + Evaluation timeout, Truncate
  long strings; слой 3 — Process exceptions outside of my code, Show return values, hex, fully qualified names, Disable JIT optimization
  (`justMyCode` / `enableStepFiltering` в `launch`). Blazor WASM, Predictive debugger, JIT-отладчик Windows — вне плана.

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

## Слои и оценки (дни работы разработчика)
1. **Инструмент и DAP-клиент (1 д).** Запись в `.NET Tools`; транспорт (фрейминг, поток чтения), клиент: запросы с
   корреляцией по `request_seq` (ответы приходят не по порядку — на этом уже обожглась проверочная обвязка), события,
   обратные запросы (`runInTerminal`), ошибки, закрытие. Тесты на фейковом адаптере через pipe + проба на настоящем.
   *Сделано из этого: запись инструмента (`DotNetTool.DEBUGGER`).*
2. **MVP отладки (3–4 д).** Debug у наших run configurations (сборка своим билдом → `launch` с `program`, `args`, `cwd`,
   `env`, включая поле Environment), точки останова на строках, остановка, кадры, переменные, шаги, evaluate, консоль, Stop.
3. **Полнота (2–3 д).** Условия / hit count / logpoints, исключения, Set Value, watches, Run to Cursor, attach, restart.
4. **Отладка тестов (1–2 д).** `dotnet test` с `VSTEST_HOST_DEBUG=1` → attach по напечатанному PID; Debug у ▶ тестов и в
   окне Unit Tests.
5. **Полировка (2 д).** Значения прямо в редакторе, async-стек, многопоточность, запуск в терминале (`runInTerminal`),
   второй адаптер (netcoredbg).

Итого 9–12 дней. Узкое место то же, что всюду: UX отладки проверяется только в живой IDE.

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
