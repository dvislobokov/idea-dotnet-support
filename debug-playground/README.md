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
| `Tests` | xUnit: отладка тестов (`BP:test`, `BP:theory`); результаты по ходу прогона (`LiveResultsTests`, маркеры `LIVE:`) |
| `AspireHost` | Aspire 13.6 AppHost, запускает только `Web` (без контейнеров и Docker): Debug AppHost подключает отладчик к `Web` сам (`BP:aspire-service`). Пакеты Aspire берутся из nuget.org при restore, workload не нужен |
| `NetFramework/NetFramework.sln` | **отдельное** решение .NET Framework старого формата (без SDK, `v4.8.1`): `LegacyWpf` (WPF: .NET SDK собирает его без XAML и падает с CS5001) и `LegacyConsole` (`packages.config`, Newtonsoft.Json по `HintPath` из `..\packages`). Нужны Visual Studio или Build Tools 2022 и targeting pack 4.8.1; в основное решение не входит, чтобы `DebugPlayground.sln` собирался без них |
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

### Замеры редактора — `Console/Editor/Measurements.cs`
Якоря скрипта `tools/ui-robot/baseline.py` (исходные замеры шага 0 `CSHARP_PSI_MIGRATION.md`; потом тем же скриптом — путь `NATIVE`). Маркеры
и пустые строки под ними не трогать.
- [ ] `TYPE:measure-member`: `text.` → список открывается сам, в нём `Length`
- [ ] `TYPE:measure-prefix`: `Consol` → список открывается сам, в нём `Console`
- [ ] `TYPE:measure-edit`: сюда скрипт набирает операторы (сессия правки перед вторым замером памяти)

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

### Find Usages с группировкой — `Console/Editor/FindUsages.cs` (+ `Lib/UsageLog.cs`)
Нужен загруженный solution: использования отдаёт сервер. Курсор куда сказано, Alt+F7, окно Find. Группы включаются переключателями самого окна
(View Options / шестерёнка): Group by Usage Type, Module, File Structure, Merge Usages on the Same Line — те же, что у других языков.
- [ ] `TYPE:find-usages-field`: на `Counter` → 13: «Read access» 3, «Write access» 7 (`=`, `+=`, `++`, `--`, `ref`, `out`), «Usage in nameof» 2, «Declaration» 1; группы «Unclassified» **нет**
- [ ] `TYPE:find-usages-field` + Group by File Structure: `UsageSample` → `UsageSample()` / `Read()` / `Write()` / `ByRef()` / `Name()`; `nameof` в атрибуте — прямо под `UsageSample`
- [ ] `TYPE:find-usages-field` + Merge Usages on the Same Line: два чтения в `Read()` — одна строка
- [ ] `TYPE:find-usages-method`: на `Record` → «Invocation» 5, «Declaration» 1, «Usage in documentation» 1 (`<see cref>`); Group by Module — проекты `Console` (3) и `Lib` (4)
- [ ] `TYPE:find-usages-type`: на `UsageSample` → «Declaration» 2, «Usage in base type list», «New instance creation», «Usage in typeof», «Type check (is / as)», «Usage in declaration type», «Usage in type argument», «Usage in nameof»
- [ ] `TYPE:find-usages-attribute`: на `UsageNoteAttribute` → «Usage in attribute» 1 и «Declaration»
- [ ] выключить Group by Usage Type / Module / File Structure → соответствующий уровень дерева пропадает, остальное как у Java / Kotlin

## Окно IL Viewer — `Console/Editor/IlViewer.cs`

Меню .NET → **IL Viewer** (окно справа). Сначала собрать solution (Debug, фреймворк тулбара); места помечены `// IL:<имя>`, в комментарии — что
сделать и что ожидать. IL обновляется через ~0,3 с после остановки курсора и только пока окно открыто. Ошибки помощника — .NET → Plugin Logs, категория `il`.

- [ ] `IL:il-simple`: в списке сверху `…IlViewer::Add` (method); строки `ldarg.1 / ldarg.2 / add / stloc.0` подсвечены и видны; клик по `ldarg.2` подсвечивает `var sum = a + b;` в редакторе, курсор там не двигается; клик по `.maxstack` — подсветки нет
- [ ] `IL:il-overloads`: IL второй перегрузки `Scale` (с `mul`), при переходе на первую — IL с `ldc.i4.2`
- [ ] `IL:il-async`: первым в списке `MoveNext` машины состояний (state machine), там же `LoadAsync` (method); выбор `LoadAsync` держится, пока курсор в этом методе
- [ ] `IL:il-iterator`: первым `MoveNext` итератора `<Squares>d__…`, подсвечен `mul`; сам `Squares` тоже в списке
- [ ] `IL:il-lambda`: первой лямбда `<Filter>b__…` (lambda), подсвечены `ldfld limit / cgt`; курсор на `return` — первым `Filter` с `ldftn`
- [ ] `IL:il-local-function`: первой локальная функция `<Compute>g__Factorial|…` с рекурсивным `call`
- [ ] `IL:il-field`: объявление поля `.field private int32 _count`, в списке один элемент `IlViewer._count` (field), под ним серая пометка «`_count` is a field: it has no IL of its own…»; без ошибки и без баннера, не «has no method `_count`»
- [ ] `IL:il-type-header`: заголовок класса `.class public auto ansi beforefieldinit …IlViewerHeader extends [System.Runtime]System.Object`, раскраска: директива, ключевые слова, имя типа; то же во второй теме
- [ ] `IL:il-stale`: правка без сохранения → жёлтый баннер «Source changed after the last build» с Build; после Build баннер пропадает, IL обновляется сам
- [ ] `IL:il-states`: `README.md` → «Open a C# file to see the IL of the code at the caret»; после Clean Solution → «Build the project to see its IL» и ссылка Build; scratch-файл C# → «The file is not a part of a .NET project»; курсор на комментарии `IL:il-states` (вне типа) или на строке 1 `Web/Program.cs` → одна надпись «No IL at line N» с причиной серыми строками под ней, без второй надписи сверху

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

## Редактор: схема `appsettings.json` из кода — `Console/Editor/AppSettingsSchema.cs`

Набирать в `Console/appsettings.json`, маркеры и `EXPECT` — в файле сценария. Схему строит DotNetHelper в фоне после открытия файла (первый раз
собирается сам помощник — секунды); журнал — .NET → Plugin Logs, категория `appsettings`.
- [ ] `TYPE:appsettings-keys`: в `"Shop"` Ctrl+Space → `Name`, `Mode`, `Timeout`, `Tags`, `Prices`, `Retry`, `Owner` с описаниями из `<summary>`; нет `Secret`, `Total`
- [ ] `TYPE:appsettings-enum`: `"Mode":` → `"Fast"`, `"Cheap"`, `"Balanced"`; `"Slow"` подсвечен
- [ ] `TYPE:appsettings-wrong-type`: `"Height": "tall"` — предупреждение; `5` и `"5"` — без
- [ ] `TYPE:appsettings-timespan`: `"Timeout": "soon"` — предупреждение; `"00:00:30"`, `"1.02:00:00"` — без
- [ ] `TYPE:appsettings-unknown-key`: `"Colour"` в `"Position"` — слабое предупреждение «PositionOptions has no property Colour»; ключ в корне и в `"Limits"` — без
- [ ] `TYPE:appsettings-dictionary`: внутри `"Prices"` ключи не предлагаются, значение `"x"` подсвечено, `1.5` — нет
- [ ] `TYPE:appsettings-marker`: `"Cache"` → `"Redis"` → `SizeMb`, `Endpoints` (класс с комментарием `// appsettings: Cache:Redis`)
- [ ] `TYPE:appsettings-base`: `Logging`, `Kestrel`, `AllowedHosts`, `ConnectionStrings` рядом с секциями кода — и с включёнными Remote JSON Schemas (из SchemaStore), и с выключенными (своя база); уровни логирования для `Default`
- [ ] `TYPE:appsettings-code-change`: новое свойство в `ShopOptions` появляется в дополнении через секунду, без сохранения `.cs`

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

## Aspire — `AspireHost` (шаг 1: распознавание, dashboard, автоподключение отладчика)
AppHost запускает `Web` как ресурс `web`. Профиль `https` (по умолчанию) требует доверенный dev-сертификат; без него — профиль `http`
(в нём `ASPIRE_ALLOW_UNSECURED_TRANSPORT=true`, без этой переменной AppHost на http падает при старте).
- [ ] Solution view: у `AspireHost` своя иконка (как у окна Services), ПКМ — Run / Debug, как у других запускаемых проектов
- [ ] на свежем открытии solution (автогенерация конфигураций ещё не делалась) конфигурация `AspireHost: https` — первая в списке и выбрана
- [ ] **Run** `AspireHost: https`: в консоли ссылка на dashboard с `/login?t=…` кликабельна и открывает dashboard без ввода токена; браузер открывается
  сам на этой ссылке (а не на голом адресе со страницей логина); в Services у строки ссылка **Open Dashboard**, голого адреса рядом нет
- [ ] **Debug** `AspireHost: https`: в консоли AppHost строка `Aspire: attaching the debugger to Web (<pid>)`, появляется вкладка отладки `Web (<pid>)`;
  открыть в dashboard адрес ресурса `web` + `/aspire` → остановка на `BP:aspire-service` во вкладке `Web`
- [ ] `BP:aspire-apphost` в `AspireHost/AppHost.cs` останавливает сессию AppHost; пока она стоит, вкладки `Web` нет (DCP ещё не запущен)
- [ ] в dashboard у ресурса `web` — Restart: старая вкладка `Web` закрывается, через 1–2 с появляется новая с новым pid, `BP:aspire-service` снова срабатывает
- [ ] Stop сессии AppHost: вкладка `Web` тоже завершается, процессов `dcp.exe` / `Playground.Web.exe` не остаётся
- [ ] известное ограничение шага 1: точка на `BP:web-start` (`Web/Program.cs`, старт сервиса) под AppHost обычно **проскакивает** — отладчик подключается
  после старта процесса; это решит шаг 2 (протокол IDE execution)

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

## Сборка проектов старого формата MSBuild'ом Visual Studio — `NetFramework/NetFramework.sln`
Нужны Visual Studio или Build Tools 2022 (workload «.NET desktop build tools») и targeting pack .NET Framework 4.8.1. Открыть папку `NetFramework`.
- [ ] Settings | Tools | .NET | Toolset and Build: строка «MSBuild version» = «Auto», под ней — какая установка найдена и где; в списке ещё «.NET SDK (dotnet build)» и установки VS
- [ ] удалить `bin`, `obj` обоих проектов и папку `packages` → .NET → Build Solution: окно Build зелёное, в выводе «Восстановление пакета NuGet Newtonsoft.Json…»
      (restore `packages.config` самим MSBuild), `LegacyConsole -> …exe`, `LegacyWpf -> …exe`; в .NET | Plugin Logs, категория `commands`, — команда `MSBuild …\amd64\MSBuild.exe -t:Build -restore -p:RestorePackagesConfig=true -m -v:m …`
- [ ] `LegacyWpf\bin\Debug\LegacyWpf.exe` запускается, окно «Legacy WPF», кнопка Click считает нажатия
- [ ] в `MainWindow.xaml.cs` сломать строку (`Greeting.Text = 1;`) → Build: ошибка CS0029 в окне Build с переходом к строке и в редакторе; вернуть
- [ ] Rebuild Solution, Clean Solution — тоже через `MSBuild.exe` (`-t:Rebuild`, `-t:Clean`, у Clean нет `-restore`)
- [ ] «MSBuild version» = «.NET SDK (dotnet build)» → Build: `LegacyWpf` падает с CS5001 (так и задумано: SDK пропускает XAML); вернуть «Auto»
- [ ] основное решение `DebugPlayground.sln`: Build идёт через `dotnet build`, как раньше (проектов старого формата в нём нет)
- [ ] Run `LegacyConsole` (конфигурация «.NET Project» с этим проектом, аргументы `first "two words"`): сборка в окне Build через `MSBuild.exe`,
      затем в консоли путь к `LegacyConsole.exe`, JSON с `"Runtime":"4.0.30319.42000"` и `"Arguments":["first","two words"]`; строка
      «Кириллица: привет, ёжик» читается на Windows с русской OEM-кодировкой (866), на английской (437) — `?` вместо букв: так их пишет сама программа, как в `cmd`
- [ ] Build одного `LegacyConsole` (ПКМ → Build) после удаления папки `packages`: restore проходит (в журнале `commands` у команды есть `-p:SolutionDir=…\NetFramework\`)
- [ ] Run `LegacyWpf`: окно «Legacy WPF», Stop в Services закрывает его сразу (в диспетчере задач `LegacyWpf.exe` нет)
- [ ] без Visual Studio (на другой машине или «MSBuild version» указывает на удалённый файл и VS нет): Build проекта старого формата — уведомление
      «Visual Studio Build Tools Not Found» с кнопкой Download Build Tools, один раз за сессию, сборка идёт через `dotnet build`

## Publish
Диалог Publish (ПКМ на проекте в Solution view → **Publish...**, или меню .NET → **Publish...**), run configuration «.NET Publish», профили `.pubxml`.
Вывод `bin\Release\...\publish*` после проверки удалить (`bin` в `.gitignore`, но место занимает).
- [ ] ПКМ на `Lib` и на `Tests` — пункта **Publish...** нет (не приложения); на `Console` и `Web` — есть; в меню .NET пункт после «Measure Build Performance»
- [ ] **Console, framework-dependent**: ПКМ на `Console` → Publish...; проект выбран и заблокирован; Configuration `Release`, Target framework `net9.0`,
      Target runtime `Portable` → Deployment mode, Produce single file, ReadyToRun, Trim выключены; внизу команда
      `dotnet publish …\Console.csproj -c Release -f net9.0 -o …\Console\bin\Release\net9.0\publish -nologo -clp:NoSummary`, у Target location серым тот же путь;
      Publish → окно Build «Publish Console» с выводом MSBuild, затем уведомление «Published 'Console'» с кнопкой открыть папку (Show in Explorer / Reveal in Finder) — открывает папку `publish`
- [ ] **Console, self-contained single file `win-x64`**: Target runtime `win-x64`, Deployment mode `Self-Contained`, Produce single file → в команде
      `-r win-x64 --self-contained true -p:PublishSingleFile=true`, папка `…\net9.0\win-x64\publish`; после публикации там один `Playground.Console.exe` (~70 МБ)
      и `appsettings.json` / `.pdb`; Trim unused assemblies включается только при Self-Contained
- [ ] ошибки: Trim при Framework-Dependent недоступен; в Target runtime набрать `win x64` → ошибка «Target runtime cannot contain spaces», кнопка Publish не публикует
- [ ] **профиль Console**: Publish profile → `WinX64SingleFile` (лежит в `Console/Properties/PublishProfiles`) → поля заполнились из файла (win-x64, Self-Contained,
      single file, Target location `bin\Release\net9.0\win-x64\publish-profile\`), в команде `-p:PublishProfile=WinX64SingleFile` и `-p:…=false` у выключенных флагов
- [ ] **Web по профилю**: ПКМ на `Web` → Publish... → профиль `FolderProfile` (как его пишет Visual Studio: `PublishUrl`, `LastUsedBuildConfiguration`) →
      Target location `bin\Release\net9.0\publish-folder-profile\`, Portable; публикация кладёт сайт именно туда (сам `dotnet publish` `PublishUrl` не читает — поэтому `-o`)
- [ ] **Save as Profile...** (кнопка слева внизу): имя `Linux` → появился `Console/Properties/PublishProfiles/Linux.pubxml` со свойствами `PublishDir`,
      `RuntimeIdentifier`, `SelfContained`, …; профиль выбран в списке; закрыть и открыть диалог — `Linux` в списке, выбор заполняет поля. Файл после проверки удалить
- [ ] **run configuration**: галочка «Save as run configuration» → после Publish в Run widget конфигурация «Publish Console» (тип «.NET Publish», значок deploy);
      Edit Configurations — те же поля, что в диалоге; Run → публикация в окне Build, не в консоли Run; повторный Save с другими полями обновляет ту же конфигурацию
- [ ] диалог помнит последние значения отдельно для `Console` и `Web` (открыть снова — то, с чем публиковали); галочка «Save as run configuration» тоже помнится
- [ ] **Container** (нужен запущенный Docker / Podman): включить «Publish as a container image», Image name `playground-console`, tag `dev` → в команде
      `-t:PublishContainer -p:ContainerRepository=playground-console -p:ContainerImageTag=dev`; после публикации `docker images` показывает `playground-console:dev`.
      Без демона сборка падает с ошибкой контейнерных задач в окне Build. Проверка без Docker — дописать в команду `-p:ContainerArchiveOutputPath=bin\image.tar.gz` руками

## Результаты тестов по ходу прогона — `Tests/LiveResultsTests.cs`
Логгер VSTest плагина (`testlogger/`) собирается при первом прогоне тестов на машине (окно «Preparing live test results», секунды) и дальше берётся
из кэша IDE. Сценарий — класс `LiveResultsTests` (xUnit), маркеры `LIVE:` в комментариях.
- [ ] ▶ у класса `LiveResultsTests` (или Run на `Tests.csproj`): в окне Unit Tests узел класса появляется сразу, тесты — по мере запуска, с крутилкой,
      и зеленеют / краснеют **по одному, с интервалом около секунды** (`LIVE:slow`), а не все разом в конце; у каждого длительность (у `Slow3` — 3 s).
      Результат может приходить с задержкой до ~1 s: VSTest отдаёт результаты логгерам пачками
- [ ] `Fails` (`LIVE:fail`) красный: сообщение `Assert.Equal() Failure: Values differ`, стек со ссылкой `LiveResultsTests.cs:line N`, клик открывает строку
- [ ] `Skipped` (`LIVE:skip`) серый, с причиной «Shows the reason»
- [ ] `WritesOutput` (`LIVE:output`): две строки вывода видны, когда выбран этот тест, и не попадают к другим тестам
- [ ] Stop, пока идёт `Slow3` (`LIVE:stop`): `Slow3` помечен как прерванный (не зелёный), завершённые тесты сохраняют результат
- [ ] Rerun Failed Tests перезапускает только `Fails`
- [ ] Debug у `TotalAppliesTheDiscount` (`BP:test`) по-прежнему останавливается; Run with Coverage — покрытие `Lib` в редакторе
- [ ] в журнале плагина (.NET | Plugin Logs, категория `helpers`) — строка `DotNetSupport.TestLogger is built for netstandard2.0`
- [ ] проект на Microsoft.Testing.Platform (MSTest runner / xunit.v3 / TUnit): как раньше — дерево из TRX в конце прогона
