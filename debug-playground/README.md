# debug-playground

Solution для живой проверки отладчика плагина (чек-лист разбит по этапам `PLATFORM_DAP_PLAN.md`, теперь это история; действующий план — `DAP_PLAN.md`, пакет `debugger`). К сборке плагина не относится. Открывать как проект:
`debug-playground/DebugPlayground.sln`. Строки, на которых стоит ставить точку останова, помечены `// BP:<имя>`; в комментарии — на что смотреть.

| Проект | Зачем |
|---|---|
| `Console` | сценарии по одному на проверку (`Scenarios.cs`), точка входа — top-level statements. Без аргументов идут все безопасные сценарии; `evil`, `crash`, `wait` — только по имени (профили `launchSettings.json`) |
| `Lib` | код другого проекта solution: шаг в него, точка останова в нём, сопоставление путей |
| `Web` | ASP.NET Core: профили `http` / `https` / `no browser`, `launchBrowser`, `launchUrl`, переменные профиля, точка останова в обработчике |
| `MultiTarget` | `net9.0;net10.0`: отладчик запускает фреймворк, выбранный в тулбаре (или первый при «Default») |
| `Tests` | xUnit: отладка тестов (`BP:test`, `BP:theory`) |
| `Broken` | не компилируется, **в solution не входит** (ломал бы Build Solution): конфигурацию «.NET Project» для `Broken.csproj` создать руками |

Профили `Console`: `All` (всё безопасное), `Launch` (аргументы и окружение), `Threads`, `Evil`, `Crash`, `Wait`.

## Чек-лист

### Этап 1 — запуск, остановка, шаги
- [ ] `Console: All`, `BP:main` — остановка; выбран Main Thread, кадр `Program.<Main>$`; в Before launch новой конфигурации есть «Build .NET Project»
- [ ] F8 / F7 / Shift+F8 (`BP:stepping`), Resume; вывод программы в консоли; Stop — в диспетчере задач нет `dotnet-debugger`
- [ ] точки останова ставятся на строках с кодом и не ставятся на `using`, комментариях, заголовках методов (`Scenarios.cs`, `Program.cs`)
- [ ] `BP:lambda`, `BP:iterator`, `BP:property`, `BP:lib`, `BP:lib-expression-body`
- [ ] `Broken`: Debug → ошибки CS0029 / CS0103 в окне Build и в редакторе, сессия отладки не остаётся открытой
- [ ] несколько запусков подряд: выбранный поток — всегда тот, что остановился; `Console: Threads` — `BP:worker` (выбран «Playground worker»), `BP:parallel`
- [ ] меню .NET → Show Debugger Logs: после сессии есть `adapter-*.log`; Trace Debugger Protocol → файлы в `protocol`

### Этап 2 — ежедневная работа (2026-09-21 пройден пользователем и UI-роботом, кроме hover и F7 во внешний код)
- [ ] `Web: http` — браузер открывается сам на `/orders/3`, остановка на `BP:web-handler`; страница `/` показывает окружение, URL и переменную профиля
- [ ] `Web: https`; `Web: no browser` — браузер не открывается, окружение Production
- [ ] Environment = Staging в конфигурации `Web` → страница `/` показывает Staging (имя окружения сильнее профиля)
- [ ] `Console: Launch`, `BP:environment`: аргументы `environment output` из профиля; свои аргументы в конфигурации заменяют их; `PLAYGROUND_FROM_PROFILE=yes`; переменная `PLAYGROUND_FROM_CONFIGURATION` из таблицы конфигурации
- [ ] `BP:variables`: значения всех видов; Evaluate / Watches: `person.Friend.Name`, `number * 2`, `access.HasFlag(Access.Write)`, ошибка на `nope.x` — текстом адаптера; hover над переменной
- [ ] `BP:collections`: `huge` (100k) и `hugeArray` (5M) раскрываются порциями, IDE не виснет
- [ ] `BP:strings`: `longText` — ошибка вместо значения, сессия жива
- [ ] `BP:expensive`: `slow` описывается ~2 с, остальное дерево работает; выключить «Allow property evaluations and other implicit function calls» → значения без вызова кода
- [ ] Run to Cursor из `BP:stepping` на строку с `Console.WriteLine`
- [ ] `Console: Evil`, `BP:evil`: раскрыть `evil`, нажать Stop — завершение за секунды, в `idea.log` нет `Cannot send Ctrl+C`
- [ ] `Console: Wait`: Pause → кадры в `Wait`, Stop
- [ ] `MultiTarget`: `BP:multitarget`, значение `framework` соответствует выбору в тулбаре
- [ ] Settings | Tools | .NET | Debugger: «Enable external source debug» (F7 в `int.Parse` из `Exceptions`), «Allow property evaluations…»;
      «Save all files on debugger launch» под замком — платформа сохраняет файлы перед любым запуском сама
- [ ] `BP:output`: кириллица в выводе (известная проблема адаптера на Windows), stderr

### Этап 3 — исключения и Set Value (2026-09-21 пройден UI-роботом, кроме hover и правки свойств на ходу)
- [ ] Run | View Breakpoints: есть группа «.NET Exception Breakpoints» с включённой «Any exception (user-unhandled, unhandled)»; «+» добавляет точку
      «thrown», в панели свойств — поле типов и три галочки «Break when»
- [ ] добавить точку «thrown» с типом `Playground.Lib.ShopException` → `Console: All` останавливается на `BP:exception`, а на `int.Parse` (FormatException) — нет;
      Evaluate: `$exception`, `e.Code`
- [ ] типы `System.IO.*, !System.IO.FileNotFoundException` и т.п.: условие сравнивается с типом исключения и всеми его базовыми типами, поэтому
      `System.*` совпадает с любым исключением (через `System.Exception`); `!Тип` исключает
- [ ] `Web` → `/fail` (`BP:web-exception`): остановка как user-unhandled (точка по умолчанию)
- [ ] `Console: Crash`: необработанное исключение останавливает отладчик (нужен адаптер с фильтром `unhandled`; со старым 0.1.0 — не остановит)
- [ ] выключить точку по умолчанию → `/fail` больше не останавливает (ответ 500); `Crash` останавливает всё равно — адаптер не умеет выключать
      `unhandled`; изменить свойства точки во время сессии — действует без перезапуска
- [ ] `BP:setvalue`: F2 на `counter` (например 100), `label` ("after"), `person.Age` — программа печатает новые значения; F2 с мусором (`abc` в `counter`) — ошибка текстом адаптера, сессия жива
- [ ] Set Value в Watches (`person.Age`) и у элемента коллекции (`small[0]`)
- [ ] hover: наведение на `number`, `person` (раскрывается), `person.Friend.Name`; на вызове метода и внутри строки — ничего

### Этап 4 — hit count, logpoints, условия (2026-09-21 пройден UI-роботом)
- [ ] `BP:setvalue`, в свойствах точки Hit count = `3` → остановка на третьем проходе цикла (`i = 2`), одна за запуск
- [ ] `Scenarios.cs`, строка с `Console.WriteLine($"{greeting} {length}")`: Log message = `greeting = {greeting}, length = {length}` → строка в консоли, без остановки
- [ ] Condition = `i == 1` на `BP:setvalue`; неверный hit count (`abc`) подсвечивается и не сохраняется
- [ ] свойства точек целы после перезапуска IDE; правка hit count во время сессии действует без перезапуска (не проверено роботом)

### Этап 6 — completion в выражениях (2026-09-21 пройден UI-роботом)
- [ ] `BP:variables`, поле Evaluate: Ctrl+Space в пустом поле → локальные переменные; `per` → `person`; `person.` → `Name`, `Age`, `Friend`…;
      `person.Friend.N`; после `Greet(person).` ничего не предлагается; то же в Watches и в поле Condition точки останова; подсветка C# в поле

### Этап 5 — attach и тесты (2026-09-21 пройден UI-роботом, кроме диалога Attach to Process и окна Unit Tests)
- [ ] `Console: Wait` запустить через Run; Run | Attach to Process → в группе «.NET» есть `Playground.Console`, нет `dotnet-debugger` и уже отлаживаемых
- [ ] точка в цикле `Wait` срабатывает; Stop отсоединяет — программа продолжает печатать `tick`, в том числе если Stop нажат на точке останова
- [ ] `Tests/PricingTests.cs`: Debug у ▶ возле теста → остановка на `BP:test` (без промежуточной остановки во внешнем коде), F7 в `Lib`; Resume → тест зелёный, сессия закрывается сама
- [ ] `BP:theory` — остановка на каждую строку `InlineData`; окно Unit Tests → «Debug Selected Tests»
