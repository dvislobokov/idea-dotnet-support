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
- [ ] `BP:view-text` (0.1.1): у длинных строк ссылка «View»; во всплывающем окне вкладки JSON (`order`), XML (`feed`), HTML (`page`), JWT (`token`), у `multiline` — текст с переносами; hover над `order` в редакторе — то же окно; у `word` ссылки нет
- [ ] `BP:dependent-master` с «Remove once hit», `BP:dependent-slave` с «Disable until hitting the following breakpoint» = master (0.1.1): первая остановка — master при `round == 2`, точка исчезает; следующая — slave при `round == 3`
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
- [ ] Retained (помощник DiagnosticsHelper, ClrMD): после открытия диалога — фоновая задача «Computing retained sizes of …», под сводкой строка
      «Retained sizes of N live objects, computed in X s»; до конца расчёта в колонке Retained прочерки; сортировка по Retained: сверху
      `System.Object[]` (хранилище статических полей), `Dictionary<Int32, CachedOrder>`, его `Entry[]`, `CachedOrder` — у `CachedOrder`
      Retained во много раз больше Bytes (держит свои `byte[]`), у `Byte[]` Retained = Bytes
- [ ] вкладка Dominators слева: сверху `System.Object[]` (StrongHandle) с почти всем размером кучи; раскрыть → `Dictionary<Int32, CachedOrder>`
      первым, затем `EventHandler<Decimal>`; раскрыть дальше до `CachedOrder` → его `byte[]` и строка; выбор узла открывает объект справа
      (Who Holds It, Fields); узел «N more, X» в конце длинных списков
- [ ] Close во время расчёта (большой дамп) → расчёт отменяется, файл дампа удалён; отмена фоновой задачи → «Retained sizes: cancelled»

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
- [ ] `TYPE:expected-type`: `int amount = ` → сразу серый текст `count;`, Tab принимает; по Ctrl+Space — `count` первым, затем `Count`; строки и ключевые слова ниже; самого `amount` в списке **нет**
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

### Лямбда там, где ждут делегат — `Console/Editor/LambdaSuggestions.cs`
Нужен загруженный solution («Roslyn: DebugPlayground.sln»): типы параметров берутся из signature help сервера. Проверено роботом 2026-09-30.
- [ ] `TYPE:lambda-action`: `Each(` → серый `lambdaOrder => ` сразу после скобки; по Ctrl+Space `lambdaOrder => ` первым, блочный вариант вторым
- [ ] `TYPE:lambda-func`: `Register(` → серый `serviceProvider => ` (имя из типа `IServiceProvider`)
- [ ] `TYPE:lambda-two`: `Retry(3, ` → серый `(i, s) => ` (`Func<int, string, bool>`, известные типы — по букве)
- [ ] `TYPE:lambda-linq`: `_orders.Where(` → серый `lambdaOrder => `; в списке следом `(lambdaOrder, i) => ` второй перегрузки
- [ ] `TYPE:lambda-event`: `Changed += ` и `Changed += (` → лямбды **нет**
- [ ] `TYPE:lambda-silent`: `Console.WriteLine(` → лямбды **нет**

### Go to Base (Ctrl+U) на членах — `Console/Editor/GoToBase.cs`
Нужен загруженный solution («Roslyn: DebugPlayground.sln»): базовые типы берутся из type hierarchy сервера. Ничего не набирать — курсор и Ctrl+U.
- [ ] `TYPE:go-to-base-member`: на `Area` у `override` → сразу в `Area` класса `MiddleShape` (ближайшая база), без списка
- [ ] `TYPE:go-to-base-property`: на `Name` → в `Name` класса `BaseShape`
- [ ] `TYPE:go-to-base-body`: курсор в теле `Rename` → список из `Rename(string name, bool notify)` в `BaseShape` и `INamedShape`, перегрузки с одним параметром **нет**
- [ ] `TYPE:go-to-base-metadata`: на `Dispose` → без исключения: декомпилированный `IDisposable` или подсказка «No base symbols of Dispose found…»
- [ ] `TYPE:go-to-base-none`: на `Own` → подсказка «No base symbols of Own found»
- [ ] `TYPE:go-to-base-type`: на имени класса `GoToBase` → как раньше, список базовых типов `MiddleShape`, `IDisposable`

## Окно IL Viewer — `Console/Editor/IlViewer.cs`

Меню .NET → **IL Viewer** (окно справа). Сначала собрать solution (Debug, фреймворк тулбара); места помечены `// IL:<имя>`, в комментарии — что
сделать и что ожидать. IL обновляется через ~0,3 с после остановки курсора и только пока окно открыто. Ошибки помощника — .NET → Plugin Logs, категория `il`.

- [ ] `IL:il-simple`: в списке сверху `…IlViewer::Add` (method); строки `ldarg.1 / ldarg.2 / add / stloc.0` подсвечены и видны; клик по `ldarg.2` подсвечивает `var sum = a + b;` в редакторе, курсор там не двигается; клик по `.maxstack` — подсветки нет
- [ ] `IL:il-overloads`: IL второй перегрузки `Scale` (с `mul`), при переходе на первую — IL с `ldc.i4.2`
- [ ] `IL:il-async`: первым в списке `MoveNext` машины состояний (state machine), там же `LoadAsync` (method); выбор `LoadAsync` держится, пока курсор в этом методе
- [ ] `IL:il-iterator`: первым `MoveNext` итератора `<Squares>d__…`, подсвечен `mul`; сам `Squares` тоже в списке
- [ ] `IL:il-lambda`: первой лямбда `<Filter>b__…` (lambda), подсвечены `ldfld limit / cgt`; курсор на `return` — первым `Filter` с `ldftn`
- [ ] `IL:il-local-function`: первой локальная функция `<Compute>g__Factorial|…` с рекурсивным `call`
- [ ] `IL:il-field`: IL поля `_count`, без ошибки и без баннера
- [ ] `IL:il-type-header`: заголовок класса `.class public auto ansi beforefieldinit …IlViewerHeader extends [System.Runtime]System.Object`, раскраска: директива, ключевые слова, имя типа; то же во второй теме
- [ ] `IL:il-stale`: правка без сохранения → жёлтый баннер «Source changed after the last build» с Build; после Build баннер пропадает, IL обновляется сам
- [ ] `IL:il-states`: `README.md` → «Open a C# file to see the IL of the code at the caret»; после Clean Solution → «Build the project to see its IL» и ссылка Build; scratch-файл C# → «The file is not a part of a .NET project»

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

## Hot Reload в `dotnet watch` — `Web/HotReload.cs`

Конфигурация «.NET Project» для `Web` с Command = `dotnet watch`, запуск через **Run** (под отладчиком Hot Reload нет), открыть `/hot-reload`.
Состояние видно в строке конфигурации в окне Services («Hot Reload: …») и цветом строк `dotnet watch` в консоли. После каждой правки — Ctrl+Z и снова сохранить.

- [ ] `TYPE:hot-reload-start`: сразу после запуска в Services «Hot Reload: Building», затем «Watching for changes»; адрес-ссылка рядом остаётся
- [ ] `TYPE:hot-reload-apply`: `"v1"` → `"v2"`, Ctrl+S → строка «C# and Razor changes applied in N ms.» цветом info, в Services «Changes applied», `/hot-reload` отдаёт v2 без перезапуска
- [ ] `TYPE:hot-reload-error`: вместо `"v1"` — `undefinedThing` → «Unable to apply changes due to compilation errors.» красным, в Services «Build failed» красным
- [ ] `TYPE:hot-reload-rude`: убрать `sealed` → «Restart is needed to apply the changes.» оранжевым, в Services «Restart needed» и ссылка Restart;
      строка «❔ Do you want to restart your app?» — ссылка; ссылки и кнопка «Restart dotnet watch» в тулбаре консоли перезапускают конфигурацию в той же вкладке
- [ ] остановить конфигурацию → в строке Services состояния нет

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


## Запуск нескольких проектов и Compound
Проекты `Web` и `Console`, оба ссылаются на `Lib`.
- [ ] в Solution view выделить `Web` и `Console` (Ctrl+клик) → ПКМ: пункты **Run 2 Projects** и **Debug 2 Projects**; при выделенном вместе с ними `Lib` или solution — обычные «Run Project» не для набора
- [ ] **Run 2 Projects**: в окне Build две сборки **одна за другой** (не одновременно), без `MSB3026` / «being used by another process»; затем оба запущены, в Services — две строки, у `Web` ссылка на адрес; в консолях `dotnet run` не собирает заново
- [ ] **Debug 2 Projects**: две сессии отладки, сборки перед ними идут по очереди; точка `BP:` в `Lib` останавливает обе программы
- [ ] Run | Edit Configurations → + → **Compound** с `Web` и `Console`: Debug запускает обе сессии, сборки по очереди. Run через Compound собирает проекты параллельно (`dotnet run` каждый сам) — общие зависимости могут дать предупреждение о повторной попытке копирования
- [ ] сборка одного из проектов падает (испортить строку в `Console`) → Run 2 Projects ничего не запускает, ошибка в окне Build

## Attach к процессам .NET Framework (за флагом реестра)
Нужна программа net4x: любой `.exe` .NET Framework (например, собранный под `net48` или `C:\Windows\Microsoft.NET\Framework64\v4.0.30319\AddInProcess.exe`, если его запустить).
- [ ] Run | Attach to Process: процесса .NET Framework в группе «.NET» **нет**
- [ ] Help | Find Action → Registry → `dotnet.debugger.attach.netFramework` = true: процесс появился в «.NET»; `notepad.exe` и другие нативные — нет; apphost `.NET` (`Playground.Console.exe`) — по-прежнему есть
- [ ] attach к процессу net4x с текущим адаптером: ожидаемо ошибка адаптера (desktop CLR он пока не умеет) с кнопкой Plugin Logs, IDE не зависает
- [ ] хосты, которые сами грузят desktop CLR (по exe не видно): с ключом реестра запустить `powershell.exe` (Windows PowerShell 5.1) и
      `C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe` (32 бита) → Run | Attach to Process (при первом открытии помощник
      DiagnosticsHelper собирается, процессы могут появиться только со второго открытия списка) → оба `powershell.exe` в «.NET»; `pwsh.exe`
      (PowerShell 7) — тоже, но как .NET; `explorer.exe`, `notepad.exe` — нет; список открывается без заметной задержки; в .NET | Plugin Logs
      категория `diagnostics` — «starting DiagnosticsHelper», без ошибок
- [ ] ключ реестра выключен → `powershell.exe` в «.NET» нет, помощник DiagnosticsHelper не запускается

## MSBuild-вычисление проектов (MsBuildHost)
Помощник `helpers/msbuildhost` вычисляет проекты MSBuild'ом SDK (условия, `$(…)`, импорты). Журнал — .NET → Plugin Logs, категория `msbuild`:
`starting MsBuildHost`, затем `MSBuild <версия> of the SDK in …` и `evaluated <проект> [...] in N ms`.

**TargetPath для отладки при нестандартном `OutputPath`** — временная правка `Console/Console.csproj` (после проверки откатить): в `PropertyGroup`
`<OutputPath Condition="'$(Configuration)' == 'Debug'">bin\Custom\$(Configuration)\</OutputPath>` и `<AppendTargetFrameworkToOutputPath>false</AppendTargetFrameworkToOutputPath>`.
- [ ] Debug конфигурации `Console` с точкой `BP:main` → сборка, остановка на `BP:main`; в журнале категории `run` —
      `TargetPath of Console.csproj (MsBuildHost): …\Console\bin\Custom\Debug\Playground.Console.dll`, строки про `msbuild -getProperty:TargetPath` нет
- [ ] первый Debug после старта IDE: в журнале `msbuild` — `starting MsBuildHost` (в самый первый раз ещё `MsBuildHost is built for net10.0` в категории `helpers`)
- [ ] `MultiTarget` (два фреймворка): Debug находит сборку фреймворка, выбранного в тулбаре (`net9.0` или `net10.0` в пути)
- [ ] откатить правку `Console.csproj`: Debug снова находит `bin\Debug\net9.0\Playground.Console.dll`

**Проект старого формата** — в solution площадки его не добавлять. В отдельной папке (например `%TEMP%\legacy`) создать `Legacy\Legacy.csproj`:
`ToolsVersion="15.0"`, `xmlns="http://schemas.microsoft.com/developer/msbuild/2003"`, `TargetFrameworkVersion` = `v4.7.2`, импорты `Microsoft.Common.props`
и `$(MSBuildToolsPath)\Microsoft.CSharp.targets`; `<Compile Include="Program.cs" />`, `<Compile Include="Form1.cs" />`,
`<Compile Include="Form1.Designer.cs"><DependentUpon>Form1.cs</DependentUpon></Compile>`, `<Compile Include="..\Shared\Util.cs"><Link>Shared\Util.cs</Link></Compile>`,
`<Content Include="Views\**\*.cshtml" />`, `<Folder Include="Empty\" />` и
`<Import Project="$(VSToolsPath)\WebApplications\Microsoft.WebApplication.targets" Condition="'$(VSToolsPath)' != ''" />`. Рядом файлы `Program.cs`, `Form1.cs`,
`Form1.Designer.cs`, `Stray.cs`, `Old\NotInProject.cs`, `Views\Index.cshtml`, папка `Empty`, `..\Shared\Util.cs` и `Legacy.sln` с этим проектом; открыть папку в IDE.
- [ ] Solution view: сначала (на секунду) видны все файлы, затем дерево обновляется само: `Program.cs`, `Form1.cs` (под ним `Form1.Designer.cs`),
      `Views\Index.cshtml`, пустая папка `Empty`, `Shared\Util.cs` со значком ссылки; **нет** `Stray.cs` и папки `Old`
- [ ] Show All Files: `Stray.cs`, `Old` и `Legacy.csproj` появились серыми; выключить — снова скрыты
- [ ] открыть `Stray.cs`: баннер «Not a part of Legacy.csproj: the project file does not list it, it is not compiled.»
- [ ] в журнале `msbuild` — предупреждение `… is evaluated without the imports that are not there: … Microsoft.WebApplication.targets …`, проект всё равно показан
- [ ] дописать внешним редактором `<Compile Include="Stray.cs" />` → через секунду `Stray.cs` в дереве обычным цветом; убрать строку — снова скрыт
- [ ] создать `Views\New.cshtml` → появляется (wildcard); создать `New.cs` в корне проекта → не появляется (его нет в проекте)
- [ ] `global.json` рядом с `Legacy.sln` с несуществующей версией SDK, перезапустить IDE: в журнале `msbuild` ошибка, дерево показывает все файлы (как до
      помощника), IDE не зависает; убрать `global.json`
