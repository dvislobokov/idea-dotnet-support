# debug-playground

Solution для живой проверки плагина: отладчика (чек-лист разбит по этапам `PLATFORM_DAP_PLAN.md`, теперь это история; действующий план — `DAP_PLAN.md`, пакет `debugger`)
и редактора. К сборке плагина не относится. Открывать как проект:
`debug-playground/DebugPlayground.sln`. Строки, на которых стоит ставить точку останова, помечены `// BP:<имя>`; в комментарии — на что смотреть.
Строки, на которых проверяют набор в редакторе, помечены `// TYPE:<имя>` (`Console/Editor/*.cs`): курсор на пустую строку под маркером, набрать то, что
сказано в комментарии, сверить с `EXPECT`, отменить набранное (Ctrl+Z). Файлы сценариев компилируются как есть.

| Проект | Зачем |
|---|---|
| `Console` | сценарии по одному на проверку (`Scenarios.cs`), точка входа — top-level statements. Без аргументов идут все безопасные сценарии; `evil`, `crash`, `wait`, `input`, `leak` — только по имени (профили `launchSettings.json`) |
| `Lib` | код другого проекта solution: шаг в него, точка останова в нём, сопоставление путей |
| `Web` | ASP.NET Core: профили `http` / `https` / `no browser`, `launchBrowser`, `launchUrl`, переменные профиля, точка останова в обработчике |
| `MultiTarget` | `net9.0;net10.0`: отладчик запускает фреймворк, выбранный в тулбаре (или первый при «Default») |
| `Tests` | xUnit: отладка тестов (`BP:test`, `BP:theory`) |
| `Broken` | не компилируется, **в solution не входит** (ломал бы Build Solution): конфигурацию «.NET Project» для `Broken.csproj` создать руками |

Профили `Console`: `All` (всё безопасное), `Launch` (аргументы и окружение), `Threads`, `Evil`, `Crash`, `Wait`, `Input` (ввод с консоли),
`Leak` (память растёт: статический кэш и подписчики статического события — для .NET Monitor), `Allocations` (память выделяется на известных строках
с известными долями, строки помечены `// ALLOC:<имя>` — для проверки аллокаций по строкам, `tools/alloc-probe`; без паузы — аргумент `allocations-fast`).

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

### .NET Monitor — Memory Dump (2026-09-22 пройден UI-роботом, кроме Save Dump As и перехода по полям двойным кликом)
- [ ] `Console: Leak` через Run; окно .NET Monitor → Memory Dump → диалог «Memory of …» за секунды; фильтр `PriceWatcher` → объекты типа;
      Who Holds It: strong handle → `System.Object[]` → `EventHandler<Decimal>` → … → `PriceWatcher`; у объекта «keeps alive …»
- [ ] `CachedOrder`: путь через `Dictionary<Int32, CachedOrder>` и его `Entry[]`; Fields: двойной клик по `<Name>k__BackingField` открывает строку, Back возвращает
- [ ] SOS Console: `dumpheap -stat -type Playground`, `syncblk`, `clrstack -all`; неизвестная команда — ошибка текстом SOS
- [ ] Close → в `<tmp IDE>/dotnet-dumps` файла нет, процесса `dotnet-dump` нет; Save Dump As… сохраняет `.dmp`

### Слой 5 — полировка (2026-09-22 пройден UI-роботом, кроме контекстного меню редактора на глаз)
- [ ] `Console: Input`: в консоли отладки `Your name: `, набрать `Ада` + Enter → остановка на `BP:input`, `name = "Ада"`, `name.Length = 3`;
      Resume → `Hello, Ада! (3 chars)`, `Done.`; после сессии нет процессов `dotnet-debugger --run-in-terminal`
- [ ] `Console: Threads`, `BP:async-inner`: в кадрах `Compute()`, `[External Code]`, разделитель «Async Call Stack», под ним `Async()`, `Run()`,
      `Program.<Main>$`; у кадра `Async()` видны `before`, `value`, `after`
- [ ] `BP:variables`: ПКМ в редакторе на строке `long big = …` → Set Next Statement — текущая строка стала ею, F8 идёт дальше; на строке другого метода —
      сообщение «Cannot set the next statement», позиция не меняется; без отладки пункта в меню нет

## Редактор

Перед проверкой дождаться виджета «Roslyn: DebugPlayground.sln» в статус-баре.

### Порядок списка completion и статистика подсказок — `Console/Editor/CompletionRanking.cs`
- [ ] `TYPE:expected-type`: `int amount = ` → сразу серый текст `count;`, Tab принимает; по Ctrl+Space — `count` первым, затем `Count`; строки и ключевые слова ниже
- [ ] `TYPE:method-by-type`: `decimal sum = ` → серый текст `Total(order);`; по Ctrl+Space `Total` выше переменных, выбранный из списка даёт `Total(|);` с серым `order` внутри
- [ ] `TYPE:parameter-name`: `Save(` → серый текст `order, cancellationToken` сразу после скобки (и до загрузки solution: `Save` объявлен в этом файле); после `order, ` — `cancellationToken`
- [ ] `TYPE:parameter-name`, через список: `Sa` + Tab (или Enter) → `Save(|);` и тот же серый текст `order, cancellationToken`
- [ ] `TYPE:parameter-type`: `Send(` → строки (`customerName`, `text`, `_title`, `Name`) выше `count` и `order`
- [ ] `TYPE:partial-name`: `Run(` → серый текст `cancellationToken` (параметр называется `token`, подбор по типу); то же после `Ru` + Tab
- [ ] `TYPE:assignment`: `Name = ` → строки первыми
- [ ] `TYPE:return`: `return ` → серый текст `order;` (метод `async Task<RankedOrder>`)
- [ ] `TYPE:value-silent`: `string label = ` → серого текста **нет** (несколько строк на выбор, ни одна не названа так)
- [ ] `TYPE:after-dot`: `int amount = order.` → `Amount` первым по имени; `Quantity` (тоже `int`) **не** поднят — типы членов чужих типов плагину неизвестны
- [ ] `TYPE:declared-nearby`: `var copy = ` → `text`, `count` выше полей `_orders`, `_title`
- [ ] `TYPE:chosen-before`: трижды выбрать `_orders`, затем набрать `_` → `_orders` выше `_title`; Reset в статистике возвращает порядок сервера
- [ ] список внутри скобок вызова открывается без заметной задержки (там добавился запрос `signatureHelp`, таймаут 400 мс)
- [ ] `TYPE:stats-ghost`: `public string Title`, Tab по серому тексту → в .NET → Suggestion Statistics строка `auto-property`: shown 1, taken 1, 100%
- [ ] `TYPE:stats-list`: в отчёте «Completion list: N chosen», большинство в `position first`, внизу причины `expected type` / `name` / `declared nearby`; Copy и Reset работают

## Solution view

### Reload Solution / Reload Project
- [ ] не переключаясь из IDE, создать файл мимо неё (из встроенного терминала: `mkdir Console/Outside` и `echo "class Outside { }" > Console/Outside/Outside.cs`) —
      в дереве его нет; ПКМ на проекте `Console` → **Reload Project** → папка `Outside` и файл появились, в статус-баре «Console reloaded»
- [ ] кнопка Reload Solution в заголовке окна Project (рядом с «глазом») видна только в Solution view; после нажатия виджет сервера проходит
      `Roslyn: starting...` → `loading` → `Roslyn: DebugPlayground.sln`
- [ ] Reload Project сервер не перезапускает: виджет остаётся `Roslyn: DebugPlayground.sln`, ошибки в новом файле появляются в Problems
- [ ] изменить `Console.csproj` внешним редактором (например `Nullable` на `disable`), Reload Project → Properties… показывает новое значение
- [ ] меню .NET → Reload Solution / Reload Project '<имя>' (проект — по файлу в редакторе); без файла проекта пункт Reload Project выключен

## Редактор: то, что не импортировано — `Console/Editor/ImportCompletion.cs`

Элементы приходят из индекса сборок, а не от сервера: работают до появления «Roslyn: DebugPlayground.sln». Первый запуск IDE собирает
индексатор и индексирует сборки solution — в `idea.log` строки «The indexer of assemblies is built for net…» и «Index of assemblies: …».

- [ ] в `idea.log` обе строки есть; в `<system IDE>/dotnet-support/indexer/<хэш>-net…/bin` лежит `AssemblyIndexer.dll`, в `…/index/v1` — файлы `.dnix`
- [ ] `TYPE:import-void`: `WriteLi` → `Console.WriteLine(|);`, подсказка параметров открыта, нового `using` нет (System подключён неявно)
- [ ] `TYPE:import-using`: `Stopw` → `Stopwatch.StartNew();` и `using System.Diagnostics;` вверху файла
- [ ] `TYPE:import-value`: `var path = Combi` → `Path.Combine(|);`
- [ ] `TYPE:import-property`: `var now = UtcN` → `DateTime.UtcNow` без скобок
- [ ] `TYPE:import-generic`: `var none = Empt` → `Array.Empty<|>();`
- [ ] `TYPE:import-expected`: `int length = Ma` → члены `Math`, дающие `int`, выше остальных
- [ ] `TYPE:import-silent-dot`, `import-silent-name`, `import-silent-short`: `Console.WriteLine` **не** предлагается после точки, на месте имени переменной и на двух буквах
- [ ] `TYPE:import-package`: `Assert.Equal` предлагается в проекте `Tests` и **не** предлагается в `Console`
- [ ] две IDE с одним solution, открытые одновременно при пустом кэше: индексатор собран один раз, в `idea.log` второй IDE «Index of assemblies» — за десятки миллисекунд (всё уже проиндексировано)
- [ ] `TYPE:import-stats`: в .NET → Suggestion Statistics причина `not imported`

## Редактор: аллокации по строкам — `Console/Allocations.cs`

Профиль `Allocations` (аргумент `allocations`; без пауз — `allocations-fast`). Строки, которые выделяют память, помечены `// ALLOC:<имя>`,
в комментарии в начале файла — ожидаемые доли. Сначала собрать solution: числа привязаны к строкам сборки.

**Переключатель по умолчанию выключен** — без него программа работает, а в редакторе ничего нет. Где он: меню **.NET** в главной строке меню, пункт сразу под «Monitor .NET Process»; ПКМ в редакторе файла `.cs`, последний пункт; Ctrl+Shift+A → «Show Allocations in Editor». Это пункт меню с галочкой, в Settings его нет.

- [ ] меню .NET → **Show Allocations in Editor** включить, пока программа не запущена: уведомление «No .NET program is running. Start one with Run or Debug…»
- [ ] включить при работающей программе: через несколько секунд уведомление «Listening to Console: Allocations (pid)…»; первый раз в `idea.log` —
      «AllocWatch is built for net…» (нужны пакеты NuGet: из кэша или из сети)
- [ ] в `idea.log` по слову `Allocations` виден весь путь: переключатель, выбранная программа, процесс среди процессов запуска, командная строка помощника,
      «listens to the process …»; если чисел нет — причина там же и в уведомлении об ошибке
- [ ] Run профиля `Allocations`, открыть `Allocations.cs`: через 1–3 с в конце строки `ALLOC:buffers` — около `19 MB/s, 18K obj/s, byte[]  95%` цветом полосы,
      у `ALLOC:strings` — около `900 KB/s, 18K obj/s, string  4%` серым
- [ ] у `numbers.Add(i)` — `int[]` (массивы `List` при росте записаны на строку вызова), у `ALLOC:boxing` — `int`; эти строки могут появляться и пропадать — выборка
- [ ] у объявления `Numbers()` — `allocates …` (в методе выделяют две строки); у `Buffer()` суммы нет (одна строка)
- [ ] полоса слева у строк с числами, у `ALLOC:buffers` — самая плотная; наведение на полосу — типы с долями, «… since the watcher was attached», метод
- [ ] числа обновляются раз в секунду; набрать две пустые строки в начале файла — числа остаются у своих строк
- [ ] Stop программы → числа и полосы исчезают; повторный Run → появляются снова
- [ ] выключить переключатель при работающей программе → числа исчезают, программа работает дальше (в консоли продолжает печатать `round N`)
- [ ] Debug того же профиля: числа есть и под отладчиком
- [ ] `Web` (`dotnet run` с запускающим процессом): числа появляются у обработчиков, когда на них идут запросы
- [ ] профиль `allocations-fast` (аргумент вручную): программа печатает круги в секунду; с включённым переключателем их меньше примерно на четверть — цена при 12 ГБ/с

## Страницы о плагине и настройки по-русски

Кода-сценария нет: проверяется интерфейс, а не поведение на коде.

- [ ] меню .NET → **Welcome to C# Project Support**: вкладка открывается, код на первом экране набирается сам; в подвале «Редакция страницы 2026-09-29.5»
- [ ] ссылка «Документация» в шапке открывает документацию **в той же вкладке**; «О плагине» возвращает обратно
- [ ] меню .NET → **Plugin Documentation** открывает документацию сразу; содержание слева ведёт по разделам
- [ ] тема страниц совпадает с темой IDE; после смены темы IDE и повторного открытия — новая
- [ ] Settings | Tools | .NET → «Language of the settings pages» = Русский, Apply, закрыть и открыть Settings: страницы .NET, Toolset and Build, NuGet, Coverage,
      Debugger, Language Server — по-русски; названия страниц в дереве слева остаются английскими (их берёт платформа по языку IDE)
- [ ] на русской странице Language Server группы «Анализ», «Автодополнение», «Подсказки в коде»; значения в списках (`openFiles`, `at_the_end`) не переведены
- [ ] «Документация плагина...» внизу страницы .NET открывает документацию в системном браузере на разделе «Настройки»
- [ ] язык = English: всё как было; язык = «Как в IDE» при английской IDE — английский

