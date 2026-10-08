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
| `Broken` | не компилируется, **в solution не входит** (ломал бы Build Solution): конфигурацию «.NET Project» для `Broken.csproj` создать руками. `SyntaxErrors.cs` — синтаксические ошибки для редактора (`TYPE:diag-*`), `SemanticErrors.cs` — семантические (`TYPE:sem-*`, 0.1.74), `SemanticErrors2.cs` — ошибки и предупреждения D2 (`TYPE:sem2-*`, 0.1.78); все три из компиляции исключены |

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
- [ ] `Broken/SyntaxErrors.cs`, «Errors and warnings» = Built-in (Settings | .NET | Language Server): `TYPE:diag-semicolon` — `int x = 1` →
  одна красная отметка за концом строки, подсказка `CS1002: ; expected`, второй такой же от сервера нет; `TYPE:diag-paren`, `-expression`,
  `-brace`, `-edit` — по `EXPECT`; постоянные `TYPE:diag-literals`, `-member`, `-misplaced`, `-directives` (жёлтые `#warning` / `#pragma`,
  в `#if NEVER` ошибок нет), `-end` (незакрытый `/*`) — коды и места как в `EXPECT`; `TYPE:diag-server-keeps` — `CS0230` и `CS0029` от сервера
  остаются и при Built-in. То же при «Language server» — те же коды от сервера
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
- [ ] Settings | .NET | Debugger: «Enable external source debug» (F7 в `int.Parse` из `Exceptions`), «Allow property evaluations…»;
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
- [ ] `TYPE:keyword-order`: `pub` → `public` первым, `PublicKey` (тип неимпортированного namespace) ниже; `public s` → `sbyte`, `sealed`, `short`, `static`, `string`, `struct` выше `String` и `SByte`; `public str` → `string` первым
- [ ] `TYPE:prop-type`: `prop` + Tab, в TYPE набрать `str` → `string` первым
- [ ] `TYPE:property-name-ghost`: `public RankedOrder Order` → список имён не всплывает, серый ` { get; set; }`, Tab принимает; `private RankedOrder ` → имена (`rankedOrder`) всплывают как раньше
- [ ] `TYPE:stats-list`: в отчёте «Completion list: N chosen», большинство в `position first`, внизу причины `expected type` / `name` / `declared nearby`; Copy и Reset работают

### External Libraries (0.1.59)
- [ ] вид **Project** окна Project → **External Libraries**: reference packs (`Microsoft.NETCore.App.Ref 10.0.x`, `Microsoft.AspNetCore.App.Ref`),
      пакеты с версиями и иконкой NuGet (`Newtonsoft.Json 13.0.x`, `xunit.assert 2.9.2`), внутри — только dll, без XML-доков
- [ ] после смены версии пакета и restore — новая версия в списке, старой нет; Go to File `Newtonsoft.Json.dll` находит её только с «Include non-project items»

### Декомпиляция (0.1.63) — без файла сценария, Solution view → Dependencies
- [ ] `Console` → Dependencies → .NET 9.0 → Frameworks → Microsoft.NETCore.App → **System.Text.Json** → ПКМ → **Decompile...** → список типов
      с поиском, набрать `JsonSerializer`, Enter → вкладка `JsonSerializer.cs [System.Text.Json]`: баннер «Decompiled from System.Text.Json 9.0.0.0.
      Read-only», ссылка «Show Assembly in Explorer» открывает `shared/Microsoft.NETCore.App/9.0.x/System.Text.Json.dll` (не `packs/...Ref`);
      у методов тела (не `throw null`), над членами `/// <summary>`, цвета, folding, Structure (Alt+7); набор в файле ничего не меняет, без диалога
- [ ] то же для пакета: Dependencies → Packages → `Microsoft.Extensions.Hosting` → Decompile... → `Host` → `Host.cs [Microsoft.Extensions.Hosting]`;
      Frameworks → System.Runtime → Decompile... → `String` открывается из `System.Private.CoreLib` (первый раз — пара секунд, в статусе «Decompiling String»)
- [ ] закрыть вкладку, Back (Ctrl+Alt+←) — возвращается; перезапустить IDE с открытой вкладкой — вкладка на месте; второй раз тот же тип — мгновенно
- [ ] на узле проекта / Analyzers / Projects пункта Decompile... нет

### Go to Class / Symbol по сборкам, metadata view (0.1.62)
Кода не нужно: после restore дождаться индексации сборок; лучше с выключенным сервером (Settings | .NET → Language Server).
- [ ] Ctrl+N `JsonSerializer`: без «Include non-project items» — только типы solution; с галочкой — `JsonSerializer (System.Text.Json, System.Text.Json 10.0)` один раз, хотя сборку видят несколько проектов; Enter — вкладка `JsonSerializer.cs [System.Text.Json 10.0]`, баннер «Metadata of …», каретка на имени типа
- [ ] в metadata view: `// Assembly …` / `// Assembly location …` в начале, `using`, `namespace`, тип с generic-параметрами и базами, члены без тел (`;`, `{ get; set; }`), `///`-доки, вложенные типы; цвета и сворачивание как у C#; набрать что-нибудь — файл только для чтения
- [ ] Ctrl+Alt+Shift+N `WriteLine` с галочкой — перегрузки `WriteLine(string)` и т. д. `(Console, System, System.Console 10.0)`; Enter — на строке этой перегрузки
- [ ] `Console/Editor/LibraryNames.cs`, `TYPE:library-navigation`: Ctrl+click по `Add` / `WriteLine` без сервера — metadata view `List<T>` / `Console` на этом члене (у `WriteLine` — список перегрузок); с готовым сервером — его декомпилированный исходник
- [ ] вкладку metadata view оставить открытой, перезапустить IDE — вкладка восстановилась (после индексации сборок); Back (Ctrl+Alt+←) из неё возвращает в код

## Solution view

### Reload Solution / Reload Project
- [ ] не переключаясь из IDE, создать файл мимо неё (из встроенного терминала: `mkdir Console/Outside` и `echo "class Outside { }" > Console/Outside/Outside.cs`) —
      в дереве его нет; ПКМ на проекте `Console` → **Reload Project** → папка `Outside` и файл появились, в статус-баре «Console reloaded»
- [ ] кнопка Reload Solution в заголовке окна Project (рядом с «глазом») видна только в Solution view; после нажатия виджет сервера проходит
      `Roslyn: starting...` → `loading` → `Roslyn: DebugPlayground.sln`
- [ ] Reload Project сервер не перезапускает: виджет остаётся `Roslyn: DebugPlayground.sln`, ошибки в новом файле появляются в Problems
- [ ] изменить `Console.csproj` внешним редактором (например `Nullable` на `disable`), Reload Project → Properties… показывает новое значение
- [ ] меню .NET → Reload Solution / Reload Project '<имя>' (проект — по файлу в редакторе); без файла проекта пункт Reload Project выключен

### Лямбда там, где ждут делегат, именованные аргументы — `Console/Editor/LambdaSuggestions.cs`
С 0.1.86 отвечает своя семантика плагина (Completion и Documentation = Built-in, по умолчанию), сервер не нужен. С сервером проверено роботом 2026-09-30.
- [ ] `TYPE:lambda-action`: `Each(` → серый `lambdaOrder => ` сразу после скобки; по Ctrl+Space `lambdaOrder => ` первым, блочный вариант вторым
- [ ] `TYPE:lambda-func`: `Register(` → серый `serviceProvider => ` (имя из типа `IServiceProvider`)
- [ ] `TYPE:lambda-two`: `Retry(3, ` → серый `(i, s) => ` (`Func<int, string, bool>`, известные типы — по букве)
- [ ] `TYPE:lambda-linq`: `_orders.Where(` → серый `lambdaOrder => `; в списке следом `(lambdaOrder, i) => ` второй перегрузки
- [ ] `TYPE:lambda-event`: `Changed += ` + Ctrl+Space → первым `(sender, e) => {}`, вторым `Create method OnChanged(object?, EventArgs)`
      (Enter: `Changed += OnChanged;` и новый метод `OnChanged(object? sender, EventArgs e)` под `Use`); серого текста здесь нет
- [ ] `TYPE:lambda-silent`: `Console.WriteLine(` → лямбды **нет**
- [ ] `TYPE:named-prefix`: `Place(qu` + Ctrl+Space → `quantity:` первым, Enter → `Place(quantity: `
- [ ] `TYPE:named-next`: `Place(3, ` + Ctrl+Space → `name:`, `urgent:`, `when:` ниже значений, `quantity:` **нет**
- [ ] `TYPE:named-attribute`: `[Obsolete(Di` → `DiagnosticId =`; `[Obsolete(` + Ctrl+Space → `message:`, `error:`, `DiagnosticId =`, `UrlFormat =`
- [ ] `TYPE:parameter-info`: `Ret` → Enter на `Retry` → `Retry(|);` и parameter info открылась сама, без Ctrl+P

### Go to Base (Ctrl+U) на членах — `Console/Editor/GoToBase.cs`
Нужен загруженный solution («Roslyn: DebugPlayground.sln»): базовые типы берутся из type hierarchy сервера. Ничего не набирать — курсор и Ctrl+U.
- [ ] `TYPE:go-to-base-member`: на `Area` у `override` → сразу в `Area` класса `MiddleShape` (ближайшая база), без списка
- [ ] `TYPE:go-to-base-property`: на `Name` → в `Name` класса `BaseShape`
- [ ] `TYPE:go-to-base-body`: курсор в теле `Rename` → список из `Rename(string name, bool notify)` в `BaseShape` и `INamedShape`, перегрузки с одним параметром **нет**
- [ ] `TYPE:go-to-base-metadata`: на `Dispose` → без исключения: декомпилированный `IDisposable` или подсказка «No base symbols of Dispose found…»
- [ ] `TYPE:go-to-base-none`: на `Own` → подсказка «No base symbols of Own found»
- [ ] `TYPE:go-to-base-type`: на имени класса `GoToBase` → как раньше, список базовых типов `MiddleShape`, `IDisposable`
- [ ] `TYPE:go-to-symbol`: Ctrl+Alt+Shift+N `area` → строки `Area()` с типом серым (`IBaseShape`, `BaseShape`, `MiddleShape`, `GoToBase`, `GoToBase.Nested`), справа `GoToBase.cs`; одинаково до готовности сервера и после. Ctrl+N `nested` → `Nested` с серым `Playground.Editor.GoToBase`

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
- [ ] Виды использований по встроенному дереву (0.1.46): Settings | .NET | Language Server → Source of Features → «Kinds of usages» = Built-in (умолчание), затем:
  - [ ] пункты выше (`find-usages-field` / `-method` / `-type` / `-attribute`) — те же числа, что с «Language server»
  - [ ] `TYPE:find-usages-native-field`: на `Total` класса `UsageTally` → 5: «Declaration» 1, «Write access» 2 (`(Total, var count) = other` и `Total = 3` в `Inner = { … }`), «Read access» 2 (`t.Total` в запросе сервер 5.12 не отдаёт)
  - [ ] `TYPE:find-usages-native-members`: на `Lines` → «Read access» 2 (в т. ч. `Lines = { 1, 2 }`), «Declaration» 1; на `Inner` → «Read access» 1, «Declaration» 1
  - [ ] `TYPE:find-usages-native-type`: на `UsageTally` → «Declaration» 1, «Usage in declaration type» 4 (в т. ч. `out UsageTally made`), «Usage in type argument» 1, «Type check (is / as)» 1 (`UsageTally { Total: > 0 }`)
  - [ ] вернуть «Language server» → у `(Total, …) = other`, `Lines = { … }`, `Inner = { … }` снова «Read/Write access» по токенам (см. «tokens:» в EXPECT), `out UsageTally` — «Write access», `UsageTally { … }` — «Read access»; переключение действует сразу, без переоткрытия файла

### Помощь при наборе по встроенному дереву (0.1.48) — `Console/Editor/ExtendSelection.cs`, `Console/Editor/CompleteStatement.cs`
Settings | .NET | Language Server → Source of Features → «Typing assistance» = Built-in (умолчание; Language server — прежние токены),
затем маркеры; потом вернуть Language server и сверить с «Tokens:» в EXPECT. Набранное отменять Ctrl+Z.
- [ ] `TYPE:extend-selection-call`: Ctrl+W на `name` → `name.Trim` → `name.Trim()` → аргументы → `Compute(…)` → `Compute(…) + 1` → … → `var total = …;` → с комментарием над ним → тело без скобок → тело → метод с `[Obsolete]` → метод с doc-комментарием
- [ ] `TYPE:extend-selection-string`: `plain` → `plain text here` (без кавычек) → `"plain text here"`; в интерполированной строке — текст без `$"` и `"`
- [ ] `TYPE:extend-selection-condition`: `>` → `total > 1` → `(total > 1)` → весь `if` с блоком
- [ ] `TYPE:quote-interpolated` / `TYPE:quote-verbatim` (`Editor/Quotes.cs`): `$"`, `@"`, `$@"` получают вторую кавычку, закрывающая перешагивается
- [ ] `TYPE:no-popup-after-brace` (`Editor/NativeCompletion.cs`): после `{` и `(` список сам не открывается, после `this.` открывается
- [ ] `TYPE:complete-call` / `complete-nested`: `Make(a, b` + Ctrl+Shift+Enter → `Make(a, b);`, каретка на новой строке (токены: ничего не добавляют)
- [ ] `TYPE:complete-two-lines`: каретка в конце `Make(a,` → вызов не разорван, новая строка под `b);`; без `;` — `;` добавлена после `b)`
- [ ] `TYPE:complete-unfinished`: `Make(a, ` → ничего не добавлено в обоих режимах
- [ ] `TYPE:complete-if` / `complete-foreach` / `complete-while`: блок `{ }` на своих строках, каретка внутри с отступом; у `if (ready` и `while (Ready(` добавлены `)`
- [ ] `TYPE:complete-if-same-line`: `if (ready) Make(1, 2` → `if (ready) Make(1, 2);`
- [ ] `TYPE:complete-method`: `public void Run()` → тело с кареткой внутри; `public int Total` → `public int Total;`
- [ ] `TYPE:gray-semicolon`: серая `;` после `.Where(x => x > 0)` второй строки (токены: нет); после `Make(2, 3)` второй строки незакрытого `Make(1,` — нет (токены: есть)

### Встроенный форматтер (0.1.49) — `Console/Editor/Formatting.cs`
Settings | .NET | Language Server → Source of Features → «Formatting» = Built-in (умолчание пока Language server), форматтер в
Toolset and Build — Auto или dotnet format; затем маркеры (Ctrl+Alt+L), потом вернуть Language server и сравнить (текст должен совпасть).
Робот: `tools/ui-robot/scripts/reformat.js` (см. `tools/ui-robot/README.md`). Изменения отменять Ctrl+Z.
- [ ] `TYPE:format-method`: выделенный `Sum` — Allman, отступ 4, пробелы вокруг операторов и после `if`; `if (a > b) { return a - b; }` остаётся в строку; соседние методы не тронуты
- [ ] `TYPE:format-file`: весь файл; пустые строки, строки-литералы, комментарии и ветка `#else` (выключенная при `DEBUG`) — как были; дырки `$"{…}"` отформатированы
- [ ] `TYPE:format-switch`: `case` с отступом внутри `switch`, операторы глубже, блок `case 2:` — под `case`
- [ ] `TYPE:format-initializers` (0.1.68, как в Rider): у многострочных инициализаторов и `[...]` скобка `{`/`[` на своей строке под `var`, элементы на 4 глубже, разбиение по строкам прежнее (`1, 2,` вместе), `}`/`]` под `{`; вложенный многострочный инициализатор раскладывает соседей по строкам; однострочные — `{ 4, 5 }` и `[6, 7]`; `Sum([` — `[` остаётся после `(`; запрос и лямбда не тронуты
- [ ] `TYPE:format-arguments` (0.1.68, как в Rider): строки аргументов — на 4 правее строки вызова, а не под первым аргументом; одинокая `)` — под строкой вызова; `Combine(Combine(1,` при внешних аргументах по строкам — на два отступа; внутри `if (` — от первого токена условия; параметры `Combine` — на 4 правее `private`; строки не склеиваются и не разбиваются
- [ ] `TYPE:format-options`: `.editorconfig` с `csharp_new_line_before_open_brace = none`, `indent_size = 2` → `{` в конце строки, отступ 2
- [ ] `TYPE:format-choice`: «Built-in» форматирует выделение на лету, «dotnet format (on save)» — файл целиком, по Ctrl+Alt+L и при сохранении; «Auto» — по переключателю «Formatting»
- [ ] `TYPE:format-csharpier`: при форматтере CSharpier — стиль CSharpier в обоих режимах; при None — ничего

### Навигация по встроенному дереву (0.1.50) — `Console/Editor/Navigation.cs`
Settings | .NET | Language Server → Source of Features → «Navigation and usages» = Built-in (умолчание пока Language server);
ничего не набирать — Ctrl+click / Ctrl+B / Ctrl+наведение / каретка, как сказано в маркере. Потом вернуть Language server и сравнить:
цели те же (сервер должен быть готов). Робот: `tools/ui-robot/scripts/goto_declaration.js` (см. `tools/ui-robot/README.md`).
- [ ] `TYPE:nav-locals`: в строке `return` метода `Locals` — `total`, `parsed` (`out var`), `text` (паттерн), `item` (`foreach`), `index` (`for`), `first` (деконструкция), `value` / `input` (параметры) — сразу на имя в объявлении, без списка; Ctrl+наведение подчёркивает, на самом объявлении — нет
- [ ] `TYPE:nav-lambdas`: `x` в `x * 2` → параметр лямбды (не локальная `x`); `p` → `(int p, …)`; `Twice` → локальная функция ниже вызова; `n` → её параметр; `seed` → параметр первичного конструктора
- [ ] `TYPE:nav-labels-queries`: `goto retry` → метка; в запросе `o` → `from o`, `doubled` → `let doubled`, `g` → `into g`; `T` в `List<T>` → `Pick<T>`
- [ ] `TYPE:nav-members`: `_count`, `this._count` → поле; `Total` → свойство; `Add(1)` → список из двух перегрузок; `Reset` → вторая `partial`-часть внизу файла; `Entry` → вложенный класс
- [ ] `TYPE:nav-types`: `UsageSample` → `FindUsages.cs`; `UsageLog` → `Lib/UsageLog.cs`; `Record` и `items.Count` — к серверу (готов — переход как у него, не готов — ничего, без неверного перехода)
- [ ] `TYPE:nav-highlight`: каретка на `total` в `Highlight` — все `total` метода, объявление / `+=` / `++` / `out total` цветом записи, чтения — цветом чтения; `total` из `Locals` не подсвечен; на `_count` — как раньше (сервер или совпадения текста)
- [ ] Go to Super (Ctrl+U) в `GoToBase.cs` работает и при «Navigation and usages» = Built-in

### Completion по встроенному дереву (0.1.55) — `Console/Editor/NativeCompletion.cs`, `Console/Editor/CommonCalls.cs`
Settings | .NET | Language Server → Source of Features → «Completion» = Built-in (умолчание пока Language server); набрать под
маркером, сверить с `EXPECT`, Ctrl+Z. Робот: `tools/ui-robot/scripts/complete_at_line.js` в обоих режимах (`feature_source.js`, `COMPLETION`).
- [ ] `TYPE:complete-keywords`: список сразу, до сервера; порядок локальные > параметры > члены > типы > ключевые слова; `break` / `continue` только в цикле; после загрузки сервера — без двойных пунктов
- [ ] `TYPE:complete-expected`: подходящее по типу выше (`other` над `count`), объявляемой `copy` нет; в аргументе `Resize(` — `amount` первым
- [ ] `TYPE:complete-goto-query`: `goto ` → метка `again`; в запросе — `where`, `select`, `orderby`…
- [ ] `TYPE:complete-override`: `public override ` → `Describe`, `Sides`, `Equals`… (не `Area`, не `NotVirtual`); член целиком с вызовом `base`; абстрактный — `throw new NotImplementedException();`
- [ ] `TYPE:complete-partial`: `partial ` → `OnResized` с пустым телом
- [ ] `TYPE:complete-names`: `StringBuilder ` → `builder`, `stringBuilder`; поле `private readonly` → `_builder`
- [ ] `TYPE:complete-task-from-result`, `-task-completed` (`CommonCalls.cs`): `return ` → серый `Task.FromResult();` / `Task.CompletedTask;`, первый пункт completion; в `async` — нет
- [ ] `TYPE:complete-await-async`, `-make-async`: выбор `await` / Alt+Enter «Make method async» — `async Task<int>`, `async Task` вместо `void`, обработчик события — `async void`

### Completion по ожидаемому типу (0.1.88) — `Console/Editor/ExpectedTypeCompletion.cs`
Built-in completion (умолчание с 0.1.76). Набрать под маркером, сверить с `EXPECT`, Ctrl+Z.
- [ ] `TYPE:expected-initializer`, `-initializer-rest`, `-with`: `new ExpectedOrder { ` → `Buyer`, `Customer`, `Id`, `Lines`, `Status` (без `Total`, `Code`, `Summary`, локальных, ключевых слов); после `Id = 1, Status = …, ` — без них; `point with { X = 1, ` → `Y`
- [ ] `TYPE:expected-property-pattern`, `-nested-pattern`: `order is { ` → члены `ExpectedOrder`; `{ Buyer: { ` → `Age`, `Name`; `{ Status: ` → `ExpectedStatus.*` первыми
- [ ] `TYPE:expected-enum-equals`: `status == ` — список открывается сам, первые строки `ExpectedStatus.Cancelled : 7`, `New : 0`, `Paid : 5`, `Shipped : 6`; после `_created == ` сам не открывается
- [ ] `TYPE:expected-enum-case`, `-enum-argument`: `case ` (открывается сам), `status switch { `, `order.Status is `, `Take(`, `status = ` → `ExpectedStatus.*` первыми
- [ ] `TYPE:expected-await`: `string s = ` в `Plain` → `await Highlights : Task<string>`, Enter → `await Highlights();` и `private async Task<string> Plain()`
- [ ] `TYPE:expected-new`, `-throw-new`, `-catch`: `ExpectedOrder o = new ` → `ExpectedOrder()` первым; `throw new ` — только исключения; `catch (` — исключения первыми
- [ ] `TYPE:expected-base-list`, `-event`: базовый список — классы и интерфейсы (без sealed, enum, делегатов, `int`), у struct — интерфейсы; `public event ` — делегаты
- [ ] `TYPE:expected-smart`: Ctrl+Shift+Space после `int n = ` — только `int` (`number`, `_created`, `await Counted`, `default`…); `string s = ` — `name`, `_mutable`, `String.Empty`, `null`; `Take(` — `ExpectedStatus.*`

### `using` по встроенному дереву (0.1.61) — `Console/Editor/Usings.cs`
Completion — при «Completion» = Built-in; Alt+Enter — при любом источнике (при готовом сервере и «Typing assistance» = Language server
вместо «Convert to 'using' declaration» — его «Use simple 'using' statement»). Набрать под маркером, сверить с `EXPECT`, Ctrl+Z.
- [ ] `TYPE:using-var`, `-await`: `using var` / `await using var` в начале оператора (второго нет в геттере); выбор `await using var` делает метод `async Task`; Alt+Enter на `await` набранного `await using` — «Make method async» одной строкой
- [ ] `TYPE:using-directive`: `using System.Coll` → `Collections`; `using static System.` → и типы (`Console`, `Math`); `global using` вверху файла
- [ ] `TYPE:using-postfix`: `.using` → `using var reader = …` (имя в рамке, с 0.1.89); `.awaitusing` → `await using var stream = …` и `async Task`
- [ ] `TYPE:using-to-declaration`, `-to-statement`, `-wrap`: «Convert to 'using' declaration» / «… statement», «Wrap in 'using' statement», отступы тела на уровень
- [ ] `TYPE:using-sort`, `-global`: «Sort 'using' directives» (`System`, `System.IO`, `System.Text`); «Convert to 'global using'» — `Console/GlobalUsings.cs` (удалить после проверки)
- [ ] `TYPE:using-cs1674` (0.1.65, «Errors and warnings» = Built-in): `using (var n = 5) { }` — CS1674 про `'int'` один раз (без копии сервера); `await using` на `CancellationTokenSource` — CS8417; `MemoryStream` и неизвестный тип — без ошибки
- [ ] `TYPE:using-list` (0.1.65): `using (` + Ctrl+Space — `reader`, `stream`, но не `count` / `title` (и не строки сервера с ними); `await using (` — `stream`, но не `reader`

### Alt+Enter по встроенному дереву (0.1.64) — `Console/Editor/ContextActions.cs`
«Context actions» = Built-in (по умолчанию пока Language server: при готовом сервере отвечают его действия); без сервера встроенные — при любом
источнике. Каретка по маркеру, Alt+Enter, сверить с `EXPECT`, Ctrl+Z. При Built-in у каждого действия одна строка: строк сервера с тем же
действием нет.
- [ ] `TYPE:ctx-if-to-conditional`: «Convert to '?:' expression» в `Sign` (`if`/`else`), `Clamp` (`if` + `return`), `Assign` (присваивания); в `Mixed` — нет
- [ ] `TYPE:ctx-conditional-to-if`: «Convert '?:' to 'if' statement» в `Parity`, в `Check` — ветка `throw`
- [ ] `TYPE:ctx-conditional-split` (0.1.81): `?:` в аргументе `Print` — оператор повторён в обеих ветках; в `Label` — сначала `string label;`; в `Lazy` (после `||`) — нет
- [ ] `TYPE:ctx-expression-body`, `-block-body`: «To expression body» / «To block body» — метод, `void`, свойство с одним `get`, аксессор (однострочный список — в той же строке)
- [ ] `TYPE:ctx-var`: «Use explicit type» — `List<string>` без namespace, `int`, `IEnumerable<string>`, `string` в `foreach`; «Use 'var'» на `int total`, но не на `long big` / `IList<string> list`
- [ ] `TYPE:ctx-introduce`, `-inline`: «Introduce variable» — выбор «Replace this occurrence only» / «Replace all 2 occurrences» (0.1.81), `var count = items.Count;`, имя в рамке правится во всех местах; не после `&&`; «Inline variable» — `(a + b) * 2`, на `changed` нет

### Попапы Rider: Refactor This, Navigate To, Generate, ПКМ редактора (0.1.70) — `Console/Editor/RiderPopups.cs`

Клавиши — раскладка по умолчанию, сочетания Rider: Refactor This — Ctrl+Alt+Shift+T, Navigate To — Ctrl+Shift+G, Generate — Alt+Insert.
Строки сервера — когда готов виджет «Roslyn: DebugPlayground.sln». Попап закрывать Escape.

- [ ] `TYPE:refactor-method` — Refactor This на методе: Rename... первым, рефакторинги сервера, недоступного нет
- [ ] `TYPE:refactor-inline` / `TYPE:refactor-introduce` — Inline на локальной, Introduce / Extract method на выделении
- [ ] `TYPE:navigate-call` — Navigate To: строки Rider, Declaration ведёт на `IPopupShape.Area`; то же из меню Navigate
- [ ] `TYPE:generate-in-class` — Alt+Insert на имени класса: сразу список генераторов в порядке Rider, без второго попапа
- [ ] `TYPE:editor-menu` — ПКМ: Find Usages Settings..., Inspect ▸, Generate Code..., Quick Definition на местах Rider
- [ ] Тулбар: молоток Build Solution перед Run widget, в меню стрелки Rebuild / Clean / Restore / Cancel Build и Configuration / Target Framework
- [ ] Settings: узел .NET в корне дерева, под Tools его нет

### Цвета идентификаторов (0.1.51) — `Console/Editor/SemanticColors.cs` (+ `SemanticColorsPart.cs`)
Settings | .NET | Language Server → Source of Features → «Colors of identifiers» = Built-in (умолчание пока Language server); после
индексации сверить имена под маркерами с `EXPECT`, затем вернуть Language server и сравнить. Робот: `tools/ui-robot/scripts/highlight_keys.js`.
- [ ] `TYPE:colors-declarations`: виды типов своими цветами (enum / record struct / delegate светлее класса), константы и члены enum жирные, событие розовое, static-члены и методы
- [ ] `TYPE:colors-locals`: локальные и параметры цвета текста, `total` подчёркнут везде, `TFormat` — цвет параметра типа, локальная функция `Indent` — зелёная и до объявления
- [ ] `TYPE:colors-members`: члены базового класса и другой части partial-класса из `SemanticColorsPart.cs` раскрашены; в Built-in с 0.1.56 `Console` / `WriteLine` тоже (класс / static-метод)
- [ ] `TYPE:colors-shadowing`: параметр лямбды и внешний параметр `count` — параметры, локальная `title` — не свойство `Title`
- [ ] Settings | Editor | Color Scheme | C#: группы и имена Rider, все примеры превью раскрашены; переключение Built-in / Language server меняет цвета сразу по Apply

### Цвета при открытии файла (0.1.84) — `Console/Editor/OpeningColors.cs`
Открыть файл, закрыть вкладку, открыть снова; то же с самым большим файлом площадки `Console/Scenarios.cs` (или `Console/Editor/Formatting.cs`).
Робот: `tools/ui-robot/scripts/color_timing.js` (время от открытия до первого цвета идентификатора, снимки редактора).
- [ ] `TYPE:colors-on-open`: цвета идентификаторов, серый неактивный `#if` и элементы формата `{0}` — в одном кадре с ключевыми словами и строками; EXPECT: ни на миг белых имён, ничего не перекрашивается, когда пропадает «Analyzing…»

### Цвета внутри строк (0.1.71) — `Console/Editor/StringColors.cs`
Сверить с `EXPECT` под маркерами (Darcula / Islands Dark), набранное отменять Ctrl+Z. Робот: `tools/ui-robot/scripts/highlight_keys.js` — ключи лексера
(`CSHARP_ESCAPE_CHARACTER_1` / `_2`, `CSHARP_FORMAT_STRING_ITEM`) и аннотатора элементов формата.
- [ ] `TYPE:strings-holes`: в дырках `$"…{…}…"` операторы, числа, `null`, вложенная строка — своими цветами, `{` `}` — цвет скобок; текст строки — коричневый
- [ ] `TYPE:strings-format`: `,5`, `:D`, `,-12:N2`, `:yyyy-MM-dd` — цвет элемента формата (фиолетовый)
- [ ] `TYPE:strings-escapes` / `TYPE:strings-invalid-escape`: `\t` и `\n` подряд — два разных цвета, `""` в verbatim — escape, `\d` там — текст; набранный `\q` — неверный escape (волнистое подчёркивание)
- [ ] `TYPE:strings-brace-escapes` / `TYPE:strings-raw`: `{{` `}}` — escape; в `$$"""` дырка `{{count}}`, одиночные `{` `}` и `\n` — текст
- [ ] `TYPE:strings-format-items`: `{0}`, `{1,8:N2}`, `{0:D}`, `{0}{1}` у `string.Format` / `Console.WriteLine` / `AppendFormat` — цвет элемента формата; у `Console.WriteLine("{0}")` без аргументов — нет
- [ ] `TYPE:strings-typing`: набор `$"{total}\t"` — цвета появляются по ходу набора, закрывающая кавычка перешагивается
- [ ] Settings | Editor | Color Scheme | C# → String: «String text», «Escape sequence» (Valid / Valid 2 / Invalid), «Format item» (и 2) — в превью видны

### Семантические ошибки без сервера (0.1.74) — `Broken/SemanticErrors.cs`, `Console/Editor/ImportType.cs`
«Errors and warnings» = Built-in (по умолчанию). Ошибки появляются сразу, без Build и до готовности сервера; коды, тексты и места — как в `EXPECT`.
LIVE_CHECKS E-109…E-114.
- [ ] `TYPE:sem-names`, `-namespace`: CS0103, CS0246, CS0234 — тексты Roslyn
- [ ] `TYPE:sem-members`, `-members-silent`: CS1061 / CS0117; на `ToString` интерфейса, `Deconstruct` записи, `First()` — ничего
- [ ] `TYPE:sem-arguments`, `-conversions`, `-conversions-silent`, `-paths`: CS1501 / CS7036, CS0029 / CS0266, CS0161
- [ ] `TYPE:sem-unused`, `TYPE:import-type-unused`: серые `using` (CS8019, CS8933), «Remove unused directives in file»
- [ ] `TYPE:sem2-unreachable`, `-unused`, `-static`, `-await` (`Broken/SemanticErrors2.cs`, 0.1.78): CS0162 серым до конца блока, CS0168 / CS0219
  серым и «Remove unused variable», CS0120, CS4014 и «Add 'await'»
- [ ] `TYPE:sem2-nullable`, `-uninitialized`, `-arguments`, `-target-typed`: CS8600 / CS8625 / CS8603 / CS8618 при `#nullable enable`, CS1503 /
  CS7036 generic / `params` / именованных, CS0029 / CS0266 `?:` и switch-выражений
- [ ] `TYPE:overloads-*` (`Console/Editor/Overloads.cs`, 0.1.78): Ctrl+B / Ctrl+Q ведут к перегрузке из EXPECT; `TYPE:overloads-argument-name` —
  «Add argument name»
- [ ] `TYPE:import-type-hint`, `-declaration`, `-choice`, `-extension`, `TYPE:sem-typing`: синяя подсказка `System.Diagnostics.Stopwatch? Alt+Enter`, «Import …», список namespace
- [ ] `TYPE:import-type-member`, `-silent`, `-arguments`, `-conversion`: ошибки при наборе; `dynamic` и кортежи — без красного
- [ ] Build Solution и готовый сервер: каждая ошибка видна один раз; «Errors and warnings» = Language server — ошибки сервера как раньше

### Source generators без сервера (0.1.77) — `Console/Editor/Generators.cs`
Сервер выключен (Settings | .NET | Language Server); первый запрос собирает помощник CodeAnalysisHelper (секунды), журнал — категория `codeanalysis`.
- [ ] `TYPE:sg-tree`: Dependencies → .NET 9.0 → Analyzers → генераторы (молния) → тип → файлы; файл открывается только для чтения с баннером
- [ ] `TYPE:sg-member`: completion `PlaygroundJsonContext.Default.` — `GeneratedOrder`, `String`; Ctrl+click по `Default` — сгенерированный файл
- [ ] `TYPE:sg-error`: CS1061 на `Customer` после сохранения; `TYPE:sg-new`: новый `[JsonSerializable]` — член `Int32Array` после сохранения

### Анализаторы Roslyn без сервера (0.1.77) — `Console/Editor/Analyzers.cs`
Пакет `Microsoft.VisualStudio.Threading.Analyzers` и секция `[Editor/Analyzers.cs]` в `Console/.editorconfig`. Сервер выключен.
- [ ] `TYPE:an-on-save`: VSTHRD200, VSTHRD103, CA1822 жёлтым, по одному, без дублей с Build Solution
- [ ] `TYPE:an-fix`: Alt+Enter — «Await ReadAllTextAsync instead», «Rename to LoadAsync», «Make static»; Ctrl+Z одним шагом
- [ ] `TYPE:an-ide`: IDE0059 и его fix; `TYPE:an-project`: .NET → Code Analysis → Run Code Analysis — окно Build, узлы «Warnings (3)»
  раскрыт и «Suggestions (N)» свёрнут (0.1.82)
- [ ] `TYPE:an-quiet` (0.1.82): в `Overloads.cs` / `ContextActions.cs` нет волн анализаторов уровня Info, «Make static» на Alt+Enter остался;
  «Show suggestions» возвращает слабые предупреждения

### Неявные вызовы и target-typed `new` в Find Usages (0.1.82) — `Console/Editor/ImplicitUsages.cs`
- [ ] `TYPE:implicit-deconstruct`, `-add`, `-enumerator`, `-dispose`, `-awaiter`: Alt+F7 на методе — места `var (…)`, элементов `{ 1, 2 }`,
  `foreach`, `using`, `await`; Rename `Deconstruct` не трогает `var`
- [ ] `TYPE:implicit-new`: Alt+F7 на `ImplicitPoint` — 14 мест, 7 из них `new(`

### C# из целей сборки: gRPC, XAML (0.1.82) — `Grpc/Greeter.cs`, `NetFramework/LegacyWpf/MainWindow.xaml.cs`
Сервер выключен. gRPC собирается офлайн из кэша NuGet (Grpc.AspNetCore 2.83.0). WPF в SDK-стиле (`net9.0-windows`) в площадку не добавлен:
он не собирается на macOS, а площадка должна собираться везде; WPF проверяется на `LegacyWpf` (MSBuild Visual Studio).
- [ ] `TYPE:grpc-resolve`: `HelloRequest`, `Greeter.GreeterBase` не красные, Ctrl+B — `obj/Debug/net9.0/Protos/Greet.cs` с баннером;
  Dependencies → .NET 9.0 → Analyzers → Generated by build → Protobuf → Greet.cs, GreetGrpc.cs
- [ ] `TYPE:grpc-errors`: CS1061 на `Nmae`; `TYPE:grpc-stale`: новое поле в `greet.proto` после Ctrl+S резолвится
- [ ] `TYPE:wpf-generated`: после сборки — CS1061 на `Txet`, Ctrl+B на `Greeting` — `obj/Debug/MainWindow.g.cs`; до сборки — молчание;
  правка XAML — «Out of date» в баннере и в узле

### Имена сборок без сервера (0.1.57) — `Console/Editor/LibraryNames.cs`
Source of Features → «Colors of identifiers» и «Navigation and usages» = Built-in, лучше с выключенным сервером (Settings | .NET →
Language Server). Дождаться индексации сборок (после restore). Робот: `tools/ui-robot/scripts/highlight_keys.js`.
- [ ] `TYPE:library-colors`: типы и члены сборок своими цветами — `Console` / `StringBuilder` / `List` класс, `Math` static-класс, `DateTime` структура, `WriteLine` / `Round` static-вызов, `Count` / `Length` свойство, `PI` константа, `Where` / `First` extension; namespace в `using`
- [ ] `TYPE:library-navigation`: Ctrl+click по `Total` / `Lines` ведёт в `LibraryOrder`; по `Add` / `WriteLine` без сервера — metadata view сборки на этом члене (с 0.1.62; не неверное место)

### Типы выражений без сервера (0.1.58) — `Console/Editor/ExpressionTypes.cs`
Как выше: «Colors of identifiers» и «Navigation and usages» = Built-in, сервер выключен, сборки проиндексированы.
- [ ] `TYPE:types-after-call`: Ctrl+click по `Total` / `Name` после `FirstOrDefault()?.`, `First(o => …)`, `[0]`, `ElementAt(0)`, `Select(o => o).Last()` ведёт в `TypesOrder`; имена — цветом свойства
- [ ] `TYPE:types-await`: `Total` после `(await LoadAsync())` и `(await Task.Run(() => _orders[0]))` → `TypesOrder.Total`
- [ ] `TYPE:types-tuples`: `pair.Order.Total` → `TypesOrder.Total`; `Item1` без сервера — никуда
- [ ] `TYPE:types-deconstruction`: `order.Total` (деконструкция кортежа) и `o.Total` (`foreach` по `Values` словаря) → `TypesOrder.Total`
- [ ] `TYPE:types-query`: `big.Total`, `g.Key.Total`, `n.Name` в запросах LINQ → `TypesOrder`
- [ ] `TYPE:types-operators`: `Total` после `(… ?? new TypesOrder())` и `Pick(true).` → `TypesOrder.Total`

### Поток nullable (0.1.80) — `Console/Editor/NullableFlow.cs`
Сервер выключен, сборки проиндексированы. Жёлтые предупреждения файла — те, что выдаёт `dotnet build`; набрать под маркером, Ctrl+Z.
- [ ] `TYPE:nullable-deref`: CS8602 только на первом `name` в `name.Length + name.Length`; `node.Next.Name` → CS8602 на `node.Next`
- [ ] `TYPE:nullable-tests`: в `Tests` предупреждений нет; `node.Next.Text` после `if` → CS8602
- [ ] `TYPE:nullable-assign`: CS8600 / CS8625 / CS8601 на трёх строках; `Take(_cache);` → CS8604 с сигнатурой, `Take(null);` → CS8625
- [ ] `TYPE:nullable-return`: CS8603 только на первом `return _cache;`
- [ ] `TYPE:nullable-attributes`: предупреждений нет (`IsNullOrEmpty`, `TryGetValue`, `ThrowIfNull`, свои `[NotNullWhen]` / `[NotNull]`); `found.Length` в `else` → CS8602
- [ ] `TYPE:nullable-loops`: CS8602 на `last` после цикла
- [ ] `TYPE:nullable-ctor`: CS8618 на `FlowOwner` первого конструктора, на остальных нет
- [ ] `TYPE:nullable-unknown`: предупреждений нет (лямбда и делегат — молчим)

### Completion в строках, регулярные выражения (0.1.90) — `Console/Editor/StringCompletion.cs`
Сервер выключен, сборки проиндексированы. Набрать под маркером (Ctrl+Space, где сказано), сверить с `EXPECT`, Ctrl+Z.
- [ ] `TYPE:string-hole`, `TYPE:string-hole-kinds`: в `$"{tic` / `$@"{ticket.` / `$$"""{{ticket.` — имена и члены, после `.` список сам; в тексте строки — пусто
- [ ] `TYPE:format-number`: `{ticket.Price:` + Ctrl+Space → `0000 - custom`, `C - currency ¤1,234.45` … `P1` как у Rider; у `{count:` ещё `D`, `X`; у строки — ничего
- [ ] `TYPE:format-date`, `TYPE:format-enum`: даты (`d`, `D`, `t`, `T`, `yyyy-MM-dd`, `o`, `s`, `u`), у enum `G F D X`
- [ ] `TYPE:format-calls`: `{1:` у `Console.WriteLine` берёт тип второго аргумента; `ToString("` у `TimeSpan` → `hh\:mm\:ss` (вставляется `hh\\:mm\\:ss`); Guid — `N D B P`
- [ ] `TYPE:regex-colors`, `TYPE:regex-generated`: шаблоны `Regex`, `[GeneratedRegex]`, после `// lang=regex`, аргумент `[StringSyntax(Regex)]`-параметра — цвета RegExp, `(?<year>` / `(?'month'` без ошибок, `Plain` и `"plain(text"` — обычные строки; Alt+Enter → Check RegExp
- [ ] `TYPE:regex-completion`: после `\` в шаблоне Ctrl+Space → `\d`, `\w`, `\s`… с описаниями

### Completion в директивах и XML-доках (0.1.90) — `Console/Editor/DocCompletion.cs`
- [ ] `TYPE:directive-hash`: `#` в начале строки — список директив сам; `if` + Enter → `#if ` и список символов (`DEBUG` жирным, `NET9_0_OR_GREATER`…)
- [ ] `TYPE:directive-arguments`, `TYPE:directive-elif`: `#nullable ` → `enable disable restore`; `#pragma warning disable ` → коды с названиями; `#elif ` → символы
- [ ] `TYPE:doc-tags`: `<` в пустой строке `///` над `Find` — теги сами; `returns` → `<returns>|</returns>`; `param` → `<param name="|"></param>` и список `name`, `limit` (без `id`)
- [ ] `TYPE:doc-names`, `TYPE:doc-cref`: `<typeparam name="` → `TKey`; `<see cref="` → `Find`, `Count`, типы; `<exception cref="` → `ArgumentNullException` первым

### Запросы LINQ как вызовы методов (0.1.80) — `Console/Editor/Queries.cs`
Сервер выключен, сборки проиндексированы. Ctrl+Q — как в `EXPECT`, тип источника решает, чьи методы зовёт запрос.
- [ ] `TYPE:queries-queryable`: `expensive` — `IQueryable<string>` (не `IEnumerable`), `o` — `(range variable) Order o`; ` && o.` после `where o.Total > 1` → `Total`, `Name`, `CustomerId`
- [ ] `TYPE:queries-dbset`: `names` — `IOrderedQueryable<Customer>`, `c` — `Customer`; `c.Nmae` в `where` → красный CS1061 (отменить), `c.Name` — без ошибки
- [ ] `TYPE:queries-method-syntax`: `ids` — `IQueryable<int>`, параметр лямбды `c` — `Customer`; `c.` внутри `Where(c => …)` → `Id`, `Name`
- [ ] `TYPE:queries-join`: `bought` — `IEnumerable<Order>` (join … into), `total` — `decimal`, `pairs` — `IQueryable<string>`
- [ ] `TYPE:queries-own-source`: `boxed` — `QueryBox<string>` (свои методы экземпляра), `x` — `int`, `maybe` — `QueryMaybe<int>` (extension `Select`); красного нет
- [ ] `TYPE:queries-group`: `g` — `IGrouping<int, Order>`, `groups` — `IQueryable<int>`; после `g.` — `Key`, `Count`, `Sum`
- [ ] `TYPE:queries-inherited-member`: `x.BookDate` / `x.CalculationTypeId` (члены базы сущности из `QueryEntities.cs`) без красного CS1061, хотя файл импортирует DTO `QueryStressTest`; `x.` → `BookDate`, `CalculationTypeId`, `Comment`, `Id`; `x.Nope` — CS1061

### Completion после точки, quick documentation, Parameter Info (0.1.66) — `Console/Editor/MemberCompletion.cs`
Settings | .NET | Language Server → Source of Features → «Completion» и «Documentation and parameter info» = Built-in (второе
по умолчанию Language server); лучше с выключенным сервером, после restore и индексации сборок. Набрать под маркером, сверить с
`EXPECT`, Ctrl+Z.
- [ ] `TYPE:dot-instance`: `text.` → `Length`, `Substring`… и LINQ (`Where`, `Select`) ниже, без `this`-параметра; нет `IsNullOrEmpty`, `Join`, `MemberwiseClone`, ключевых слов
- [ ] `TYPE:dot-generic`: `_orders.` → `Add(MemberOrder item)`, `Count : int`, LINQ; `_orders[0].` → `Total`, `Ship`, `Lines`, нет `_secret`; после загрузки сервера — без двойных пунктов
- [ ] `TYPE:dot-static`: `Console.` → `WriteLine (+ N)`, `ReadLine`, `Out`; `string.` → `Join`, `Empty`; `MemberOrder.` → `Empty`, `State`; `MemberOrder.State.` → `New`, `Shipped`
- [ ] `TYPE:dot-namespace`, `TYPE:dot-this`: `System.Collections.Generic.` → `List<>`, `Dictionary<,>`; `this.` в наследнике `List<int>` → `Add`, `Count`, `_extra`
- [ ] `TYPE:quick-doc`: Ctrl+Q / наведение на `WriteLine`, `Substring`, `Total`, `Add`, `limit`, `Documented` — строка как у Rider и текст документации; без второго окна сервера
- [ ] `TYPE:parameter-info`: Ctrl+P в `first.Add("book", limit)` — две строки, вторая отмечена, `int count = 1` выделен; `Console.WriteLine(` — строка на перегрузку; `new StringBuilder(` — конструкторы

### Поведение списка completion (0.1.91) — `Console/Editor/CompletionBehaviour.cs`
«Completion» = Built-in (по умолчанию при выключенном сервере). Набрать под маркером, сверить с `EXPECT`, Ctrl+Z.
- [ ] `TYPE:behaviour-stats`: после пяти выборов `_items` он выше `_counter`; локальные по-прежнему выше полей, ключевые слова внизу
- [ ] `TYPE:behaviour-commit`: `var copy = cou` + `;` → `var copy = counter;`; `cou` + `)` в вызове, `CustomerNa` + `.`; `unt` + `;` остаётся `unt;`
- [ ] `TYPE:behaviour-suggestion`: `foreach (var num` + пробел — список без выделения, остаётся `num `; `out var res` + пробел — то же; стрелка вниз выделяет пункт
- [ ] `TYPE:behaviour-lambda`: `numbers.Where(` — список открывается сам, без выделения; `n` + пробел — остаётся `n `
- [ ] `TYPE:behaviour-autopopup`: `new List<` — типы открываются сами; `Status == ` и `case ` — список открывается сам
- [ ] `TYPE:behaviour-quickdoc`: `this.Ord` + Ctrl+Q при открытом списке — документация `OrderCount`; при движении по списку окно следует
- [ ] `TYPE:behaviour-generic`: `new Dictionary<string, ` + Ctrl+Space — типы, без `if` / `for`
- [ ] `TYPE:behaviour-keywords`: `is int and > 0 ` → только `and`, `or`; ветка `1 ` → `when`, `and`, `or`; `value ` → `as`, `is`, `switch`, `with`
- [ ] `TYPE:behaviour-nameof`: `nameof(` — имена без ключевых слов; `typeof(` — только типы, без `dynamic`
- [ ] `TYPE:behaviour-middle`: `ReceiptLi` → `WriteReceiptLine`; при `rec` он ниже пунктов, начинающихся с `rec`
- [ ] `TYPE:behaviour-accessors`, `TYPE:behaviour-field`, `TYPE:behaviour-extension`: `get`/`set`/`init`; `field` в `get => `; `extension` в static-классе

### Вставка из списка completion (0.1.92) — `Console/Editor/CompletionInsertion.cs`
«Completion» = Built-in (по умолчанию). Набрать под маркером, сверить с `EXPECT`, Ctrl+Z.
- [ ] `TYPE:insertion-dot`: `var text = Tot` + `.` → `var text = Total().`, каретка после точки, список членов `decimal` открылся сам; **не** `Total.`
- [ ] `TYPE:insertion-semicolon`: `Recalcul` + `;` → `Recalculate();`; `Regist` + `;` → `Register(|);`, каретка в скобках, parameter info `int id` сама
- [ ] `TYPE:insertion-auto`: `Recalcula` + Ctrl+Space → списка нет, вставлено `Recalculate();`; `var w = new Widgetr` + Ctrl+Space → `new Widgetry(|)`
- [ ] `TYPE:insertion-extension-types`: `_orders.ToImm` → справа `ImmutableArray<InsertionOrder>`; `_orders.Fir` → `First : InsertionOrder`;
      Ctrl+P в `_orders.Where(` → `Func<InsertionOrder, bool> predicate`, без `TSource`
- [ ] `TYPE:insertion-delegate-lambda`: `Check(` → список сам, без выделения, `order => ` сверху; `o` + пробел — остаётся `o `
- [ ] `TYPE:insertion-catch`: `try { } catch (` + Ctrl+Space → сначала исключения; `Ex` (enum) и `Exc` (класс) — ниже всех исключений

### Языковые места completion (0.1.94) — `Console/Editor/LanguageCompletion.cs`
«Completion» = Built-in (по умолчанию при выключенном сервере). Набрать под маркером, сверить с `EXPECT`, Ctrl+Z. Части partial-типов — `LanguageCompletionParts.cs`.
- [ ] `TYPE:explicit-members`: `void ILcAudited.` → только `Audit`; Enter пишет `void ILcAudited.Audit(string who)` с `throw new NotImplementedException();`; `int ILcPriced.` → `Price` пишется как `decimal ILcPriced.Price { get => throw …; }`
- [ ] `TYPE:explicit-names`: `void ` → `ILcPriced`, `ILcAudited`, `IDisposable`; выбор пишет `IFoo.` и открывает список членов; после `public void ` имён нет
- [ ] `TYPE:explicit-alone`: `IDisposable.` → `Dispose`, пишется `void IDisposable.Dispose()`
- [ ] `TYPE:indexer-string`, `TYPE:indexer-collections`: `text.`, `_numbers.`, `_names.`, `_counts.`, `self.` → `[]` (`this[int index]`); Enter даёт `x[|]`, `?.` — `?[|]`; у `object`, `Console.` и с набранной буквой пункта нет
- [ ] `TYPE:tuple-names`: `pair.` → `Title`, `Count`; `named.` → `width`, `height`
- [ ] `TYPE:deconstruct-var`, `TYPE:deconstruct-foreach`, `TYPE:deconstruct-record`: имя элемента в `var () = pair;`, `foreach (var (a, ) in pairs)`, `var (x, ) = new LcPoint(1, 2);` (`y`), `Deconstruct` с `units`, `currency`
- [ ] `TYPE:partial-types`: `partial class ` → `LcOrder`, `LcCart`, `LcBox<T>`; не `LcMoney`, `LcTotals`, `LcOwn`; `partial struct ` → `LcTotals`; `partial interface ` → `ILcShape`

### Rename по встроенному дереву (0.1.53) — `Console/Editor/Rename.cs`
Settings | .NET | Language Server → Source of Features → «Rename» = Built-in (умолчание пока Language server); каретка на имя,
Shift+F6, новое имя, Enter — сверить с `EXPECT`, затем одно Ctrl+Z. Потом Language server с готовым сервером — результат тот же.
Робот: `tools/ui-robot/scripts/inline_rename.js` (см. `tools/ui-robot/README.md`).
- [ ] `TYPE:rename-local`: `subtotal` → `sum` (все три вхождения меняются при наборе), `price` → `cost` — только `foreach`; одно Ctrl+Z возвращает всё
- [ ] `TYPE:rename-conflict`: `subtotal` → `prices` — диалог «Problems Detected» (параметр `prices` уже объявлен); Cancel — без изменений
- [ ] `TYPE:rename-parameter`: `discount` → `rate` — параметр, использование и `<param name="rate">`; `discount` в `Limits` не тронут
- [ ] `TYPE:rename-local-function`: `Scale` → `Times` (вызов до объявления тоже); параметр `factor` → `k` вместе с `k: 3`
- [ ] `TYPE:rename-label-query-lambda`: метка `again` → `retry`; параметр лямбды `line` → `text`; `o` запроса → `item` (`g` не тронут)
- [ ] `TYPE:rename-type-parameter-keyword`: `TValue` → `TItem` (с `<typeparam>`); `kind` → `class` даёт `@class`
- [ ] `TYPE:rename-primary-member`: `owner` → `customer` (с `<param>` класса); `Limit` (свойство) → `Cap` — встроенный rename по solution (с 0.1.73)

### Ссылки по solution без сервера (0.1.73) — `Console/Editor/SolutionUsages.cs` + `Lib/SolutionShapes.cs`
«Navigation and usages» и «Rename» = Built-in (по умолчанию); сервер остановить или открыть файл до его готовности. Робот:
`tools/ui-robot/scripts/usages_compare.js` (против сервера), `rename_solution.js`, `line_markers.js`, `hierarchy.js`, `find_usages.js`.
- [ ] `TYPE:solution-find-usages`: Alt+F7 на `Side` — 7 мест в двух проектах, группы Read / Write / Usage in nameof / Usage in documentation; Ctrl+Alt+F7 — те же строки попапом
- [ ] `TYPE:solution-highlight`: каретка на `Side` / `square` в `Measure` — подсвечены все использования в файле, запись — цветом записи
- [ ] `TYPE:solution-goto-implementation`: Ctrl+Alt+B на `figure.Area()` — список SolutionSquare.Area и SolutionTile.Area; на `ISolutionFigure` — SolutionSquare и SolutionTile
- [ ] `TYPE:solution-goto-super`: Ctrl+U на `Area` в SolutionTile — SolutionSquare.Area в `Lib`; иконки gutter Overrides / Implements / Is overridden / Has implementations
- [ ] `TYPE:solution-hierarchy`: Ctrl+H на `ISolutionFigure` — ISolutionFigure → SolutionSquare → SolutionTile; Ctrl+Alt+H на `Measure` — вызывающий `Run`, вызываемые
- [ ] `TYPE:solution-rename`: `Side` → `Edge` (оба проекта, `nameof`, `cref`); `Area` в SolutionTile → `Surface` — диалог Rename All / Only This; `SolutionTile` → `SolutionPlate` с конструктором; `Side` → `Label` — конфликт; одно Ctrl+Z (платформа спрашивает про другие файлы)

### Generate (Alt+Insert) без сервера (0.1.75) — `Console/Editor/Generate.cs`
Работает и с выключенным сервером (Settings | .NET | Language Server), и с готовым: тогда строк сервера с тем же смыслом
(«Generate constructor …», «Generate Equals …», «Generate overrides…», «Implement interface / abstract class») в списке нет. Каретка на пустую
строку под маркером, Alt+Insert, строка, в диалоге выбора членов — OK; сверить с `EXPECT`, затем Ctrl+Z.
- [ ] `TYPE:gen-list`: строки как в Rider, недоступные серые, у Missing / Overriding members — Ctrl+I / Ctrl+O; нет «Override / Implement Methods…» платформы
- [ ] `TYPE:gen-constructor`, `gen-base-constructor`: группы Fields / Properties (и «Base constructor»), конструктор встаёт на строку каретки
- [ ] `TYPE:gen-properties`: Read-only properties и Properties (readonly-поле в Properties не предлагается)
- [ ] `TYPE:gen-equality`: флажки «Implement 'IEquatable<T>' interface» и «Overload equality operators»; `==` для `int` / `string` / `decimal`
- [ ] `TYPE:gen-formatting`, `gen-deconstructor`, `gen-dispose`, `gen-partial`
- [ ] `TYPE:gen-override`: группы `GenShape` и `object`, `Area` / `Label` не предлагаются; Ctrl+O открывает тот же диалог
- [ ] `TYPE:gen-missing-abstract`, `gen-missing-library`: абстрактные члены базы и интерфейсы из сборок (`IComparable<GenMoney>`, `IDisposable`), Ctrl+I
- [ ] `TYPE:gen-delegating` (0.1.81): сначала список «Delegate To» (`_items`, `Title`), затем члены `List<int>`; `Add` с вызовом `_items.Add(item)`, `Count => _items.Count`
- [ ] `TYPE:gen-equality-comparer`, `gen-relational`, `gen-relational-comparer` (0.1.81): вложенные `NameAgeEqualityComparer` / `NameAgeRelationalComparer` и статическое свойство; `IComparable<GenPerson>, IComparable`, операторы `<` `>` `<=` `>=`; файл собирается

### Переопределение и недостающие члены (0.1.85) — `Console/Editor/Overrides.cs`, `Grpc/Greeter.cs`
Сервер выключен. Набирать на пустой строке под маркером, сверить с `EXPECT`, затем Ctrl+Z до исходного текста (файл собирается как есть).
- [ ] `TYPE:override-popup`, `override-access`: список сам открывается после `override ` / `public override `; без `Area`, `ToString` (sealed), ключевых слов; доступ базы ставится перед `override`
- [ ] `TYPE:grpc-override` (`Grpc/Greeter.cs`): после удаления `SayHello` — `public override ` предлагает `SayHello` из `Greeter.GreeterBase` (файл в `obj/`), типы без `global::`; Ctrl+O там же
- [ ] `TYPE:override-library`, `override-base-dot`: члены `BackgroundService` (`StartAsync`, `StopAsync`), `async override` → `await base…`; `base.` — члены базы из сборки
- [ ] `TYPE:override-ctrl-o`: Ctrl+O — диалог «Override Members», Ctrl+I — «Nothing to generate»
- [ ] `TYPE:override-alt-enter`, `implement-missing`: красные CS0534 / CS0535; Alt+Enter «Implement missing members» первым — на имени класса, на интерфейсе в списке баз, на пустой строке тела; «Override members...» — на пустом месте тела и на заголовке класса с базой
- [ ] `TYPE:override-ctor-info`: Ctrl+P в `: base(…)` — конструкторы `Exception`
- [ ] `TYPE:override-by-type`: `public override str` — в списке Describe (тип string), `bo` — Equals; нет `struct` / `class` / `string` и шаблонов; `public override string ` — список сам, только члены типа string, `string D` заменяется целиком; пробел после `str` не выбирает строку
- [ ] `TYPE:override-early`: `public ov` — ключевое слово `override` первым, под ним строки `override string Describe` и т. д.; выбор пишет весь член; `ov` без доступа — доступ базы (`protected override int Sides`)
- [ ] `TYPE:override-gray`: серый текст лучшего override при `public ov` (и за выбранной строкой списка), `public override `, `public override str` / `bo`; Tab — то же, что выбор строки, каретка в теле; без `public` и для protected-члена серого текста нет

### Extract Method и Introduce Field без сервера (0.1.75) — `Console/Editor/ExtractMethod.cs`
Выделить, Ctrl+Alt+M (Introduce Field — Ctrl+Alt+F) или Refactor This; имя нового метода в рамке — набрать своё, Enter; затем Ctrl+Z.
- [ ] `TYPE:extract-statements`, `extract-returned`, `extract-out`: параметры из локальных, возврат переменной, `out` для второй
- [ ] `TYPE:extract-expression` (метод не `static`: читает поле), `extract-async` (`async Task<int>` и `await` в вызове)
- [ ] `TYPE:extract-refused`: красная подсказка про `return` со значением, текст не меняется; строка с `continue` — `if (NewMethod(item)) continue;` (0.1.81)
- [ ] `TYPE:extract-loop-carried`, `extract-loop-break`, `extract-loop-sometimes`, `extract-return-void` (0.1.81): переменная, которую читает следующая итерация, возвращается; `break` / `return;` — `if (NewMethod(…)) break;` и `ref`; записанная не на всех путях — входит параметром
- [ ] `TYPE:introduce-field`: `private readonly string _concat = …` после последнего поля; для `a * 2` — поле и присваивание перед строкой; имя в рамке у поля и у использований (0.1.81)

### Introduce Parameter без сервера (0.1.81) — `Console/Editor/IntroduceParameter.cs`
Выделить по маркеру, Ctrl+Alt+P или Refactor This → «Introduce Parameter...»; имя параметра в рамке — набрать своё, Enter; затем Ctrl+Z.
- [ ] `TYPE:introduce-parameter`: попап «Pass It at Every Call» / «Make the Parameter Optional (= 10)»; вызовы в `Use` и в `IntroduceParameterCaller` получают `10`; во втором случае вызовы не меняются
- [ ] `TYPE:introduce-parameter-args`: вызовы передают выражение со своими аргументами: `Twice(4, 4 * 2)`, `Twice(a + b, (a + b) * 2)`
- [ ] `TYPE:introduce-parameter-optional`: новый параметр встаёт перед `int level = 1`
- [ ] `TYPE:introduce-parameter-refused`: подсказка про локальную `local` и про вызов на другом объекте (`other.Seeded()`)

### Postfix по типу, новые postfix и live templates Rider (0.1.89) — `Console/Editor/Postfix.cs`
Набрать на пустой строке под маркером, Tab или Enter; имя в рамке — остановка шаблона (Tab дальше); затем Ctrl+Z.
- [ ] `TYPE:postfix-by-type`: у `ready.` — `if`/`else`/`while`/`not`, нет `foreach`/`await`/`null`; у `orders.` — `foreach`/`for`/`forr`, нет `if`/`await`; у `task.` — `await`; у `count.` — `for`, нет `null`; у `name.` — `parse`/`tryparse`; у неизвестного имени — всё
- [ ] `TYPE:postfix-loops`: `orders.for` → `i < orders.Count`, `numbers.forr` → `numbers.Length - 1`, `orders.foreach` → `var order` в рамке; у `sequence.` нет `for`
- [ ] `TYPE:postfix-var-names`: `order.Total.var` → `var orderTotal` (в списке и `total`), `GetOrders().var` → `var orders`
- [ ] `TYPE:postfix-field-prop`: `.field` в конструкторе → `private readonly DateTime _now;` после `_orders`; `.prop` → `public DateTime Now { get; }`
- [ ] `TYPE:postfix-inject`: `IPostfixClock.inject` в `PostfixService` → primary constructor; в `PostfixRepository` → параметр конструктора, поле и присваивание; `.if` между членами не предлагается
- [ ] `TYPE:postfix-to-arg-sel`: `.to`, `.arg`, `.sel`, `.parse` (список типов у `int`), `.tryparse` → `int.TryParse(name, out var value)`
- [ ] `TYPE:live-templates`: `itli`, `nguid`, `unchecked`, `#if`, `sfc`, `outv`; в списке — описания Rider; в `PostfixCtor` — `ctorf`, `ctorp`, `equals`, `indexer`, `propdp`

### Встроенное дерево C# и символы `#if` фреймворка — `MultiTarget/ActiveBranch.cs`
- [ ] `TYPE:active-branch`: Settings | .NET | Language Server → «Structure, folding and breadcrumbs» = Built-in; в Structure (Alt+7)
      при .NET 10.0 в тулбаре — `Net10Only`, при .NET 9.0 / Default — `Net9Only` (второго класса нет); переключение без переоткрытия файла;
      обратно на «Language server» — файл сразу перепарсен эвристическим деревом

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
- [ ] `TYPE:import-silent-type`: в `List<Str`, `typeof(Str`, после `as` и в списке базовых типов статических членов из индекса нет (раньше в `Task<str>` был `Conversion.Str`)
- [ ] `TYPE:import-package`: `Assert.Equal` предлагается в проекте `Tests` и **не** предлагается в `Console`
- [ ] две IDE с одним solution, открытые одновременно при пустом кэше: индексатор собран один раз, в `idea.log` второй IDE «Index of assemblies» — за десятки миллисекунд (всё уже проиндексировано)
- [ ] `TYPE:import-stats`: в .NET → Suggestion Statistics причина `not imported`

Без сервера (0.1.87, метод `NotImported` того же файла, соседние namespace — `Console/Editor/ImportCompletionTargets.cs`):

- [ ] `TYPE:import-types-visible`: Ctrl+Space на пустой строке — среди типов `List<>`, `Dictionary<,>`, `Task`, `File` (неявные usings), строк «(in …)» нет
- [ ] `TYPE:import-types-short`: `Li` → `List<>`, выбор даёт `List<|>`, нового `using` нет
- [ ] `TYPE:import-neighbour-type`: `Recei` → `Receipt (in Playground.ImportCompletionTargets.Billing)`, выбор добавляет `using` вверху файла
- [ ] `TYPE:import-neighbour-new`: `var book = new ReceiptB` → `new ReceiptBook<|>()` и `using`
- [ ] `TYPE:import-library-type`: `StringBu` → `StringBuilder (in System.Text)`, выбор добавляет `using System.Text;`
- [ ] `TYPE:import-qualified`: `Time` → `Timer (in System.Timers)`, выбор пишет `System.Timers.Timer` целиком, `using` не добавляется
- [ ] `TYPE:import-extension`, `import-extension-generic`: `name.Yel` → `Yell() (in …Text)`, `numbers.EveryO` → `EveryOther()`; выбор — вызов и `using`; `numbers.Yel` — `Yell` нет
- [ ] `TYPE:import-extension-library`: `numbers.ToImm` → `ToImmutableArray() (in System.Collections.Immutable)` и `using`
- [ ] `TYPE:import-extension-silent`: `numbers.` — строк «(in …)» нет, с первой буквой появляются
- [ ] `TYPE:import-attribute`: `[Obs` → `Obsolete` и `ObsoletedOSPlatform (in System.Runtime.Versioning)`, выбор второго добавляет `using`

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
- [ ] Settings | .NET → «Language of the settings pages» = Русский, Apply, закрыть и открыть Settings: страницы .NET, Toolset and Build, NuGet, Coverage,
      Debugger, Language Server — по-русски; названия страниц в дереве слева остаются английскими (их берёт платформа по языку IDE)
- [ ] на русской странице Language Server группы «Анализ», «Автодополнение», «Подсказки в коде»; значения в списках (`openFiles`, `at_the_end`) не переведены
- [ ] «Документация плагина...» внизу страницы .NET открывает документацию в системном браузере на разделе «Настройки»
- [ ] язык = English: всё как было; язык = «Как в IDE» при английской IDE — английский


## Запуск нескольких проектов и Compound
Проекты `Web` и `Worker` (с 0.1.79), оба ссылаются на `Lib`; `Worker` раз в 3 с ходит в `Web` (`http://localhost:5187/orders/N`) и пишет
`round N: ... Web answered ...` или `Web is not up yet`. Проверка — в окне Build, тулбаре Run и окне Services, набирать ничего не надо.
- [ ] в Solution view выделить `Web` и `Worker` (Ctrl+клик) → ПКМ: пункты **Run 2 Projects**, **Debug 2 Projects** и **Save as Compound Configuration**;
      при выделенном вместе с ними `Lib` или solution их нет (а «Run Project» — обычный)
- [ ] **Run 2 Projects** (E-150): в окне Build **одна** сборка `Build Web+Worker.slnf` (Lib собран один раз), без `MSB3026` / «being used by another process»;
      затем оба запущены, в консолях `dotnet run ... --no-build` (своей сборки нет); в Services — две строки, у `Web` ссылка `http://localhost:5187`.
      EXPECT: уведомление «Started Web, Worker» со ссылкой **Save as Compound Configuration**; НЕ две сборки одновременно
- [ ] ссылка **Save as Compound Configuration** в уведомлении (E-151): в тулбаре выбрана конфигурация `Web + Worker` (тип Compound), в Run | Edit Configurations —
      она и обе конфигурации .NET Project в папке `Web + Worker`; уведомление «Compound Configuration 'Web + Worker' Saved». Повторный Run 2 Projects
      уведомления о сохранении больше не показывает
- [ ] Run `Web + Worker` из тулбара: то же, что Run 2 Projects — одна сборка `Build Web+Worker.slnf`, оба `dotnet run --no-build`.
      EXPECT в Services (E-153): строки сгруппированы в узел-папку `Web + Worker` (если группировка по папкам выключена — значок Group By в Services → Folder);
      Stop на узле группы останавливает оба процесса, Rerun на нём — перезапускает оба (снова одной сборкой)
- [ ] запустить `Web: http` и сразу (за долю секунды) конфигурацию `Worker` из Services / тулбара: сборка одна на оба или вторая ждёт первую — не одновременно
- [ ] **Wait for** (E-152): Run | Edit Configurations → `Worker` → «Wait for:» = `Web: http`, условие «listens on its address», Apply; Run `Web + Worker`:
      в строке состояния внизу «Waiting for 'Web: http' to listen on http://localhost:5187», `Worker` стартует после строки `Now listening on` у `Web`,
      и в его консоли с первого раунда `Web answered`, НЕ `Web is not up yet`
- [ ] Wait for с условием «answers on the health URL» и Health URL = `/health`: то же, ждёт ответа 200 от `http://localhost:5187/health`
- [ ] таймаут: у `Worker` Wait for = `Web: http`, «Wait timeout» = 5, запустить **только** `Worker`: через 5 с уведомление «'Worker' Was Not Started»
      с текстом «'Web: http' does not listen on http://localhost:5187 (it is not running) after 5 s…»; процесса `Worker` нет
- [ ] Wait for = сама конфигурация `Worker` → в редакторе конфигурации ошибка «The configuration waits for itself»; `Web: http` ждёт `Worker`, а `Worker` — `Web: http` →
      «The configurations wait for each other: …»
- [ ] **Debug 2 Projects** / Debug `Web + Worker` (E-154): одна сборка `Build Web+Worker.slnf`, затем две сессии отладки; `BP:worker-round` в `Worker/Program.cs`
      и `BP:lib` в `Lib` останавливают обе программы
- [ ] сборка падает (испортить строку в `Worker/Program.cs`) → Run `Web + Worker`: ни один процесс не запущен, уведомление «Build Failed … none of Web: http, Worker was started»,
      ошибка в окне Build; вернуть строку (Ctrl+Z)
- [ ] у `Worker` снять «Build .NET Project» в Before launch → при запуске compound `Worker` собирается сам (`dotnet run` без `--no-build`), `Web` — сборкой `Build Web.csproj`
- [ ] Run | **Save as Compound Configuration** при двух запущенных по отдельности .NET-конфигурациях (фокус не в Solution view) → compound из запущенных

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

## Attach к процессам .NET Framework
С 0.1.69 (адаптер `dotnet-debugger` 0.2.0) ключа реестра нет: на Windows процессы .NET Framework в «.NET» всегда. Отладка с attach — в разделе
`NetFramework/NetFramework.sln` ниже.
- [ ] Run | Attach to Process: запущенный `NetFramework\LegacyWpf\bin\Debug\LegacyWpf.exe` в группе «.NET» с первого открытия списка; `notepad.exe` и другие нативные — нет; apphost `.NET` (`Playground.Console.exe`) — есть
- [ ] хосты, которые сами грузят desktop CLR (по exe не видно): запустить `powershell.exe` (Windows PowerShell 5.1) и
      `C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe` (32 бита) → Run | Attach to Process (при первом открытии помощник
      DiagnosticsHelper собирается, процессы могут появиться только со второго открытия списка) → оба `powershell.exe` в «.NET»; `pwsh.exe`
      (PowerShell 7) — тоже, но как .NET; `explorer.exe`, `notepad.exe` — нет; список открывается без заметной задержки; в .NET | Plugin Logs
      категория `diagnostics` — «starting DiagnosticsHelper», без ошибок
- [ ] Attach к 32-битному `powershell.exe` (`SysWOW64`) → уведомление «Cannot Debug a 32-bit Process» (адаптер отлаживает только 64-битные процессы), сессии нет

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
- [ ] Settings | .NET | Toolset and Build: строка «MSBuild version» = «Auto», под ней — какая установка найдена и где; в списке ещё «.NET SDK (dotnet build)» и установки VS
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

### Отладка .NET Framework (0.1.69, адаптер `dotnet-debugger` ≥ 0.2.0, только Windows)
Оба проекта собираются 64-битными (`<Prefer32Bit>false</Prefer32Bit>`): адаптер отлаживает только 64-битные процессы, а MSBuild без этой
строки делает AnyCPU-программу «Prefer 32-bit». Открывать **копию** папки `NetFramework` (общий `.idea` с точками останова).
- [ ] `BP:legacy-console` (`LegacyConsole/Program.cs`): Debug конфигурации с `LegacyConsole`, аргументы `first second` → в окне Build **одна**
      сборка; остановка на строке маркера, кадр `Main`, `args` = `{string[2]}`, `json` с `"Runtime":"4.0.30319.42000"`; Evaluate
      `Environment.CurrentDirectory` → папка `bin\Debug` (как у Run); Step Over → строка 14, Step Into → 15; Resume → JSON и кириллица в Console
- [ ] `BP:legacy-wpf-click` (`LegacyWpf/MainWindow.xaml.cs`): Debug `LegacyWpf`, нажать Click → остановка, `clicks` = 1, `sender` — `Button: Click`;
      Resume, ещё Click → `clicks` = 2; Stop закрывает окно
- [ ] Attach to Process к запущенному вне IDE `LegacyWpf.exe` → Click → та же остановка; Stop отпускает процесс, окно живо
- [ ] убрать `<Prefer32Bit>false</Prefer32Bit>` из `LegacyConsole.csproj` → Debug: сборка есть, сессии нет, уведомление «Cannot Debug a 32-bit Process»
      с советом «Set Prefer32Bit to false in LegacyConsole.csproj»; вернуть строку

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

## Редактор: редкие места, «Exclude from completion», `$(…)` в csproj (0.1.95) — `Console/Editor/RareCompletion.cs`, `Console/Console.csproj`

Сценарии C# — в `Console/Editor/RareCompletion.cs`, сценарии MSBuild — в конце `Console/Console.csproj` (комментарии `TYPE:msbuild-*`).

- [ ] `TYPE:rare-internals-visible-to`: `[assembly: InternalsVisibleTo("` над `namespace` → проекты solution, `Li` оставляет `Lib`
- [ ] `TYPE:rare-extern-alias`: в `Console.csproj` временно `Aliases="LibAlias"` у ProjectReference на Lib; в пустом .cs `extern alias ` → `LibAlias` (без `Aliases` список пуст); вернуть csproj
- [ ] `TYPE:rare-calling-convention`: `delegate* unmanaged[` → `Cdecl`, `Stdcall`, `Thiscall`, `Fastcall`, `SuppressGCTransition`
- [ ] `TYPE:rare-file-package`: в пустом файле `#:package seri` → `Serilog`, `Serilog.AspNetCore`…; `#:package Serilog@` → версии; `#:` → `package`, `sdk`, `property`, `project`
- [ ] `TYPE:rare-format-digit`: `$"{total:0` + Ctrl+Space → `0000`, `0.##`; после обычного числа списка по-прежнему нет
- [ ] `TYPE:rare-exclude`: Settings | .NET → «Exclude from completion» = `System.Text.*` → `StringBu` не предлагает `StringBuilder (in System.Text)`; строку убрать — строка вернулась
- [ ] `TYPE:msbuild-property` (файл `Console/Console.csproj`): `$(MSBuildProj` → `MSBuildProjectDirectory`…, `$(Root` → `RootNamespace`, скобка закрывается; `@(Comp` → `Compile`; `%(File` → `Filename`
- [ ] `TYPE:msbuild-paths` (там же): `<Import Project="` → `..\`, папки, .props/.targets/.csproj; `<ProjectReference Include="..\Lib\` → только `Lib.csproj`
- [ ] live templates `hal`, `ua`, `rta`, `ctx` в классе контроллера: `[HttpGet] public IActionResult Index()`, `Url.Action("Index", "Home")`, `return RedirectToAction("Index");`, `HttpContext.`

## ShopApi — completion на живом сервисе

Отдельный solution `ShopApi/ShopApi.sln`: ASP.NET Core на CQRS (MediatR), EF Core (PostgreSQL), OpenTelemetry (трассы и метрики,
Prometheus), Serilog, фоновый outbox-воркер. Собирается офлайн из локального кэша NuGet; для запуска нужен PostgreSQL. Тур по completion —
`ShopApi/Playground/CompletionTour.cs`, маркеры `TYPE:shop-*`, в порядке версий 0.1.85–0.1.91.
Строки ASP.NET Core (0.1.93) — `ShopApi/Playground/AspNetCompletion.cs`:
- [ ] `TYPE:shop-log-placeholder` — `{` в шаблоне `LogInformation` открывает список имён из аргументов (`OrderId`, `Id`), Enter дописывает `}`;
  `{OrderId}` / `{Total}` цветом format item
- [ ] `TYPE:shop-log-free`, `TYPE:shop-log-count` — Ctrl+Space в тексте даёт `{OrderCustomer}`; лишний / недостающий аргумент — жёлтое предупреждение
- [ ] `TYPE:shop-log-serilog`, `TYPE:shop-log-message` — Serilog и `[LoggerMessage]`: цвета, параметры метода в списке, плейсхолдер без параметра — предупреждение
- [ ] `TYPE:shop-route-param`, `TYPE:shop-route-constraint`, `TYPE:shop-route-token`, `TYPE:shop-map-param` — `{` → параметры действия / лямбды,
  `:` → ограничения (`minlength()` с кареткой в скобках), `[` → `controller` / `action` / `area`; цвета `{id:long}`
- [ ] `TYPE:shop-json` — JSON-цвета в строке после `// lang=json` и в `JsonDocument.Parse`, Alt+Enter → Edit JSON Fragment
- [ ] `TYPE:shop-config-key`, `TYPE:shop-config-connection`, `TYPE:shop-config-section` — ключи `appsettings.json` (вложенные через `:`), строки подключения, ключи секции
- [ ] `TYPE:shop-di-impl` — `AddScoped<IPriceCalculator, ` → реализации из solution сверху, без абстрактного класса

### Double completion (0.1.96) — `ShopApi/Playground/DoubleCompletion.cs`
«Completion» = Built-in. Набрать под маркером, нажать то, что сказано в маркере, сверить с `EXPECT`, Ctrl+Z.
- [ ] `TYPE:shop-double-members`: `ledger.` + Ctrl+Space — строка «Press Ctrl+Space again to show members that are not accessible here»; второй Ctrl+Space — `_entries`, `_version` серым с «(not accessible)» внизу, без `Open`; Enter пишет `ledger._version`
- [ ] `TYPE:shop-double-protected`: `order.` + Ctrl+Space дважды — `MemberwiseClone() (not accessible)` серым
- [ ] `TYPE:shop-double-chain`: `Customer customer = ` + Ctrl+Shift+Space — `_fallback`, `null`, `default` и строка-реклама; второй раз — `order.Customer`, `ledger.LastCustomer`; Enter пишет цепочку
- [ ] `TYPE:shop-double-chain-method`: `int n = ` + Ctrl+Shift+Space дважды — `ledger.Count : int`, `order.GetHashCode() : int`; нет `order.CustomerId`, нет `_fallback.Orders.Count`
- [ ] `Console/Editor/DoubleCompletion.cs`, `TYPE:double-package` (основной solution): `GrpcServi` + Ctrl+Space дважды — `GrpcServiceOptions (in Grpc.AspNetCore.Server, Grpc.AspNetCore.Server 2.83.0)`; Enter — `using` и уведомление с кнопкой «Add package …»; `TYPE:double-nothing` — без буквы пакетов нет

### Object initializers и `required` (0.1.98) — `ShopApi/Playground/RequiredMembers.cs`
«Completion» и «Errors and warnings» = Built-in. Набрать под маркером, сверить с `EXPECT`, Ctrl+Z.
- [ ] `TYPE:required-new`, `TYPE:required-expected`: `new OrderL` + Enter — `{` на своей строке, `Sku = ,` / `Title = ` по строке, каретка после `Sku = `, без `()`; строка списка — `OrderLine { Sku, Title }`
- [ ] `TYPE:required-error`, `TYPE:required-partial`: `new OrderLine();` — красный CS9035 на `OrderLine` для `Sku` и `Title`; Alt+Enter → «Add initializer for required members» дописывает недостающие
- [ ] `TYPE:required-fill`, `TYPE:required-member`: Ctrl+Space в `new OrderLine { }` — «Fill required members» / «Fill all members» сверху; выбор `Title` пишет `Title = `
- [ ] `TYPE:required-intention`: Alt+Enter в инициализаторе — «Initialize members» (все незаданные), в пустом `{ }` ещё «Initialize required members»
- [ ] `TYPE:required-sets`, `TYPE:required-ctor`: `new Money(1m)` без ошибки (`[SetsRequiredMembers]`), `new Mon` + Enter — `{ Amount =  }` в строку; `new Shipm` + Enter — `new Shipment(|)`, инициализатор — через fix

### Серый текст при наборе (0.1.101) — `ShopApi/Playground/GhostSuggestions.cs`
Серый текст по правилам (не ML), Tab принимает. Пока открыт список completion, серого нет — Esc.
- [ ] `TYPE:ghost-new-by-name`: `var member = new ` (Esc; с 0.1.102 серое видно и при открытом списке) — серое `Member();`; `var members = new ` — `List<Member>();`; у `shipment` / `product` — ничего
- [ ] `TYPE:ghost-close-call`: `var member = new Member(` — серая `;` за `)`, Tab — `new Member();` (одна `)`); у `new Shipment(|)` — ничего
- [ ] `TYPE:ghost-fill`: `new Member()`, Enter, `{`, Enter — серые строки `Name = name,` / `Email = dto.Email,` / `Age = age,` / `Admin = `; после `}` — серая `;`
- [ ] `TYPE:ghost-member-value`: `new Member { Name = ` — серое `name`, `Name = dt` — `o.Name`, `Email = ` — `dto.Email`; без `;`
- [ ] `TYPE:ghost-parameter-name`, `TYPE:ghost-field-name`: `Draft(MemberDto ` — `memberDto`, `IMemberService ` — `memberService`, `List<Member> ` — `members`; поле `private static readonly MemberDto ` — `_memberDto`
- [ ] `TYPE:ghost-list-new` (0.1.102): `var member = new ` — список открылся сам, выбрана `Member`, серое `Member();`; стрелкой на `MemberDto` — `MemberDto();`; Tab пишет строку и серое и закрывает список, Enter — только строку
- [ ] `TYPE:ghost-list-name` (0.1.102): `Draft(Mem` + Ctrl+Space — на `MemberDto` серое `berDto memberDto`, Tab — `Draft(MemberDto memberDto)`
- [ ] `TYPE:new-initializer-row` (0.1.102): `var member = new Membe` — под `Member` строка `Member { … }`, Enter — инициализатор со значениями шаблоном (Tab — по значениям); у `Product` — `Sku = |,` / `Title = ` тем же шаблоном; у `Shipment` строки нет
- [ ] `TYPE:ghost-class-once`, `TYPE:ghost-file-name`: `public clas` — одна строка `class`; `public class ` + Ctrl+Space в конце файла — `GhostSuggestions` (file name), Enter — тело `{ }`, имя редактируется как в шаблоне

## Палитры C# (0.1.97) — `ShopApi/Endpoints/OrderEndpoints.cs`

Файл без маркеров: смотреть на весь файл. В нём есть почти всё, что красит палитра: `using` и namespace, статический класс, метод
расширения `MapOrderEndpoints`, вызовы (`MapGroup`, `Send`, статические `TypedResults.Ok`), типы (`IMediator` — интерфейс, `OrderStatus` —
enum, `ListOrders` — record, generic `Results<Ok<OrderView>, NotFound>`), параметры лямбд, локальные (`orders`, `id`), строки с `{id:long}`,
интерполяцию `$"/api/orders/{id}"`, числа. Нужен готовый семантический цвет (Built-in или сервер), иначе видны только грубые цвета.
- [ ] .NET → **C# Color Palette…**: стрелками по списку — файл перекрашивается сразу; фон редактора, выделение, строка каретки, номера строк
  не меняются ни на одной палитре; Esc — вернулась прежняя палитра, Enter — выбранная осталась (и видна в Settings | .NET, «C# color palette»)
- [ ] сравнить с оригиналом: Rider — синие ключевые слова, сиреневые типы, зелёные методы; Visual Studio — бирюзовые классы, светло-зелёные
  структуры, жёлтые методы, голубые локальные; VS Code — то же семейство, константы `#4FC1FF`; Nord / Dracula / One Dark / Solarized /
  GitHub — узнаются по ключевым словам и строкам
- [ ] сменить тему IDE (Settings | Appearance, Dark ↔ Light) или схему (Settings | Editor | Color Scheme) — палитра осталась, в светлом
  варианте (Alucard у Dracula, One Light у One); схема «Rider Dark» тоже перекрашивается выбранной палитрой
- [ ] «IDE default» — C# снова в цветах Language Defaults схемы, ничего от палитр не осталось; JSON / XML / Java-файлы не меняются никогда
- [ ] новая конфигурация IDE: первое открытие `.cs` при «IDE default» — уведомление «Make C# colors like Rider, Visual Studio, VS Code…?»,
  «Choose Palette…» открывает тот же список, «Don't Show Again» — больше не появляется

### Находки сквозного прохода (0.1.100) — `Console/Editor/JourneyFixes.cs`
«Errors and warnings», «Completion», «Formatting» = Built-in. Сделать то, что сказано в маркере, сверить с `EXPECT`, Ctrl+Z (созданный fix'ом файл удалить).
- [ ] `TYPE:journey-create-class`, `TYPE:journey-create-enum`: Alt+Enter на `InMemoryOrderStore` / `ShipmentState` — «Create class / record / struct / enum …», тип новым файлом в `Editor/`, `namespace Playground.Editor;`
- [ ] `TYPE:journey-create-value`, `TYPE:journey-create-member`: «Create field '_total'» (`private decimal _total;`), local / parameter / property, «Create method 'Recalculate'», «Create property 'Count'» в `JourneyStore`
- [ ] `TYPE:journey-implement-defaults`: Implement missing members — `ct = default`, `level = 0`, `params object[] args`, `[CallerMemberName] string caller = ""`
- [ ] `TYPE:journey-semicolon`, `TYPE:journey-paren-string`: `new JourneyStore(` + `;` → `new JourneyStore();`; `Console.WriteLine($"…");` без лишней `)`
- [ ] `TYPE:journey-indent-enum`, `TYPE:journey-indent-foreach`: Enter после `enum Kind { A, B }` — тот же отступ, после `foreach (…)` — +1
- [ ] `TYPE:journey-reformat`: Ctrl+Alt+L раскладывает `JourneyOneLine` по строкам, как Rider
- [ ] `TYPE:journey-cs7036`: `store.Add(1);` — CS7036, `new JourneyStore(1);` — CS1729 до сборки
- [ ] `TYPE:journey-postfix-enum`, `TYPE:journey-record`, `TYPE:journey-appsettings-comma`: после `JourneyStatus.` без postfix; New → Record — каретка в `()`; `},` в appsettings.json без `,,`

### Присваивание члену (0.1.103) — `ShopApi/Playground/GhostAssignments.cs`
- [ ] `TYPE:ghost-assign-selected`: `member.Ad` — на выбранном `Admin` серое `min = isAdmin;`, а не `min admin`
- [ ] `TYPE:ghost-assign-value`: `member.Email = ` — серое `dto.Email;`, `member.Name = ` — `name;`
- [ ] `TYPE:ghost-assign-list`: `member.Email = ` + Ctrl+Space — строки `dto.Email`, `dto.Name` вверху списка
- [ ] `TYPE:ghost-member-of-type` (0.1.104): `JsonSerializer.Serialize ` / `Console.Out ` — без серого `serialize` / `out`

### Навигация в декомпилированном коде (0.1.105) — `Console/Editor/DecompiledNavigation.cs`
- [ ] `TYPE:decompiled-ctrl-click`: Ctrl+Click по `StringBuilder`, внутри — по `ArgumentOutOfRangeException`, `Span<T>`, `Math.Max`: открывается следующий декомпилированный тип
- [ ] `TYPE:expected-enum-assign` (0.1.106): `Console.BackgroundColor = ` — список сам, Enter на `ConsoleColor.Black` пишет `ConsoleColor.Black;`
- [ ] `TYPE:enum-*` в `ShopApi/Playground/EnumCompletion.cs` (0.1.106–0.1.107): enum после `= `, `==`, `(`, `return `, и без `=` — `Console.ForegroundColor ` → `= ConsoleColor.Black;`
- [ ] `Broken/Errors/CSxxxx.cs` (0.1.108–0.1.115): по файлу на ошибку компилятора — подчёркнуты ровно строки с `// ERROR CSxxxx`, остальные чистые; робот: `tools/diag/check_errors.py ide debug-playground/Broken/Errors`

### Цветные парные скобки — `Console/Editor/BracketColors.cs`
Settings | .NET, «Colorize matching brackets» (по умолчанию включено); три цвета по кругу: Darcula — золотой, орхидея, голубой, светлые схемы — синий, зелёный, коричневый (умолчания VS Code).
- [ ] `TYPE:brackets-nesting`: `{` `}` класса — уровень 1, метода — 2, `(` `)` у `Range`, `Select`, `ToArray` — 3, пустые `[` `]` у `new[]` и `{` `}` инициализатора за ними — снова 1, `(i + 1)` — 2; у пары один цвет с обоих концов; после закрытия и открытия вкладки цвета есть сразу, без перекраски
- [ ] `TYPE:brackets-generics`: `<` `>` у `Dictionary<string, List<int>>` и `Generics<T>` — как скобки, `<` `>` у `a < b`, `b > 1`, `=>` — обычный цвет оператора
- [ ] `TYPE:brackets-strings`: скобки в строках, символе и комментариях не раскрашены; в `$"…"` скобки `(` `)` внутри дырки раскрашены, `{` `}` дырки — нет
- [ ] `TYPE:brackets-mismatch`: набрать `(` — она без цвета, скобки этого и следующего метода не сдвигаются; Ctrl+Z
- [ ] `TYPE:brackets-inactive`: скобки в ветке `#if NEVER` серые, в активной — цветные
- [ ] `TYPE:brackets-off`: снять галку, Apply — все скобки обычного цвета сразу; вернуть — снова цветные; Settings | Editor | Color Scheme | C# | Braces and operators | Matching brackets — три уровня, превью на строке `Enumerable.Range(…)`

### Inlay hints без сервера (0.1.117) — `Console/Editor/InlayHints.cs`
Settings | .NET | Language Server → Source of Features → «Inlay hints» = Built-in, сервер выключен; в Settings | Editor | Inlay Hints | C# есть «Parameter names» и «Types», оба включены. Опции группы «Inlay Hints» той же страницы .NET действуют на встроенные подсказки так же, как на серверные.
- [ ] `TYPE:inlay-literals`: `width:` `title:` `shape:` у литералов и `new Shape()`, ничего у `Draw(number, text, shape)` («for everything else» выключено)
- [ ] `TYPE:inlay-others`: с «for everything else» имена есть и там, но не у `width` (аргумент назван как параметр), не у `this.Width` / `Width`; `predicate:` у лямбды
- [ ] `TYPE:inlay-indexer`: `key:` у `map["a"]`; пропадает с выключенным «for indexers»
- [ ] `TYPE:inlay-named-params`: нет подсказки у `width: 1`, нет у аргументов `params`; `TYPE:inlay-suffix`, `TYPE:inlay-intent`, `TYPE:inlay-constructors` — по `EXPECT`, включая обратное при выключении опции подавления
- [ ] `TYPE:inlay-var`: типы `var` — объявление, `foreach`, `out var`, деконструкция `var (a, b)`; ничего у `int plain`; `TYPE:inlay-linq` (пример из отчёта): `IOrderedEnumerable<Certificate>` у `resp`, `Certificate` перед `certificate` и `c`, `int` перед `x`, `y`; ничего перед `(int x)`
- [ ] `TYPE:inlay-new`, `TYPE:inlay-collection`: по умолчанию ничего; с «of 'new()' expressions» — `List<int>` после `new`, с «of collection expressions» — `List<int>` перед `[1, 2]` и `[3]`
- [ ] `TYPE:inlay-click`: клик по подсказке `Certificate` ведёт в `class Certificate`, по `List<int>` — в metadata view `List<T>`
- [ ] `TYPE:inlay-switch`: «Inlay hints» = Language server при включённом сервере — подсказки только серверные, двух одинаковых в одном месте нет; обратно Built-in — только встроенные (после перерисовки файла)

### Настройки страницы Language Server у встроенных фич — `Console/Editor/ServerOptions.cs`
Сервер выключен, все фичи Built-in; каждую опцию переключить (Apply), сделать по маркеру, проверить `EXPECT`, вернуть опцию.
- [ ] `TYPE:option-unimported`, `-names`, `-regex-completion`, `-arguments`: опции группы Completion выключены — нет типов чужих пространств имён (`StringBuilder`, `ToImmutableList()`; `System.Linq` импортирован через `ImplicitUsings`, по нему не судить) / имён после типа / списка в regex / списка после `(`; включены — всё на месте
- [ ] `TYPE:option-decompiled`, `-remarks`, `-symbol-search`: Ctrl+Click только в metadata view, Ctrl+Q без «Remarks:», Ctrl+N без типов сборок
- [ ] `TYPE:option-auto-insert`: `///` не раскрывается, Enter не продолжает `/// `; пара `{` `}` остаётся платформенной
- [ ] `TYPE:option-regex-highlight`, `-json-highlight`: строка без цветов и без парной скобки
- [ ] `TYPE:option-organize`: Reformat Code сортирует `using` (System первым) и убирает неиспользуемые; при выключенной опции — не трогает
- [ ] `TYPE:option-insertion`, `-properties`: конструктор / свойство в конце типа при `at_the_end`; Implement missing members — авто-свойство при `prefer_auto_properties`, бросающее по умолчанию

### Code Vision без сервера (0.1.119) — `Console/Editor/CodeLens.cs`, `Tests/CodeLensTests.cs`
«Code Vision» = Built-in (по умолчанию), Settings | .NET | Language Server → Code Lens: «References» и «Run and debug tests» включены. Смотреть на серую строку над объявлением, сверять с `EXPECT`.
- Маркеры линз стоят отдельной строкой над объявлением: при позиции Code Vision «Right» линза видна сразу за кодом, а не после длинного комментария
- [ ] `TYPE:lens-interface`, `TYPE:lens-class`, `TYPE:lens-constructor`, `TYPE:lens-property`, `TYPE:lens-enum`: «N usages» над типами и членами с числами из EXPECT (`base(radius)` — использование конструктора, не типа; у однострочного enum три записи в одной строке: тип и оба члена); клик — Show Usages плагина ровно с N строками
- [ ] `TYPE:lens-interface-member`, `TYPE:lens-override`, `TYPE:lens-virtual`, `TYPE:lens-override-only`: у члена интерфейса, реализации и переопределения одно число — как у Find Usages (каскад по иерархии); «2 implementations» / «1 override» / «1 inheritor» рядом, клик — список
- [ ] `TYPE:lens-no-usages`, `TYPE:lens-two-fields`, `TYPE:lens-overloads`: «no usages»; у `int _a, _b;` одна строка с двумя записями «1 usage | no usages»; перегрузки считаются порознь, по вызовам («1 usage» и «2 usages»)
- [ ] `TYPE:lens-typing`: набрать `public void Extra() { Sum(); }` — над `Sum` сразу «1 usage», остальные линзы файла не мигают и не исчезают; Ctrl+Z
- [ ] `TYPE:lens-test-class`, `TYPE:lens-test-method`, `TYPE:lens-test-theory`, `TYPE:lens-test-helper`: «Run | Debug» над тестами и классом тестов (над `[Fact]`, не под ним), не над helper; Run запускает ту же конфигурацию `dotnet test --filter`, что ▶ в гаттере, Debug — под отладчиком
- [ ] выключить «References» — «N usages» пропадают при следующем проходе, «Run | Debug» остаются; выключить «Run and debug tests» — наоборот; Settings | Editor | Inlay Hints | Code Vision: группы «Usages», «Inheritors», «C# tests» тоже выключают своё
- [ ] «Code Vision» = Language server при включённом сервере: линзы сервера «N references» вместо родных, никогда обе; обратно — родные

### Исходники библиотек по Source Link и из PDB (0.1.116) — `Grpc/SourceLink.cs`
Сервер выключен, опция Settings | .NET | Language Server → «Navigate to Source Link and embedded sources» включена (по умолчанию), сеть есть.
PDB с Source Link лежат рядом с dll в кэше NuGet у Grpc.Net.* 2.83.0 (Grpc.AspNetCore площадки); у Microsoft.Extensions.* PDB нет —
откат к декомпиляту. Журнал: .NET | Plugin Logs, категория «sourcelink».
- [ ] `TYPE:sourcelink-type`: Ctrl+Click по `GzipCompressionProvider` — вкладка «GzipCompressionProvider.cs [Grpc.Net.Common]» с настоящим
  исходником grpc-dotnet, каретка на `class GzipCompressionProvider`, баннер «Navigated to source from Source Link: https://raw.githubusercontent.com/…
  Read-only» с «Open in Browser»; в первый раз — прогресс «Looking for the source of …»; набор в файле ничего не меняет; Back / Forward ведут обратно
- [ ] `TYPE:sourcelink-member`: Ctrl+Click по `CreateCompressionStream` — та же вкладка без повторной загрузки, каретка на методе; `Status`,
  `StatusCode` — «Status.cs [Grpc.Core.Api]», каретка на struct / на свойстве
- [ ] `TYPE:sourcelink-fallback`: Ctrl+Click по `Host` — сразу декомпилят (или метаданные), в журнале одна строка «No source location … no PDB»
- [ ] `TYPE:sourcelink-off`: опция выключена — декомпилят; включена снова — вкладка Source Link сразу, из кэша на диске
- [ ] `TYPE:sourcelink-offline`: без сети — после прогресса декомпилят, в журнале «could not be downloaded from …», IDE не замирает

### Mapping completion и память выбора (0.1.134, 0.1.135) — `Console/Editor/Mapping.cs`
- [ ] `TYPE:map-statement`: Ctrl+Space под `dto.Id = user.Id;` → первыми `dto.Name = user.Name;`, `dto.Email = user.Profile.Email;` (серое `map`, `from user`)
  и жирная «Map all remaining members from user»; `dto.Note` (init), `dto.Id` (задан), `dto.Created` (DateTime → int) **нет**; Enter на map-all пишет обе строки с отступом строки
- [ ] `TYPE:map-initializer`: в инициализаторе после `Id = user.Id,` → `Name = user.Name`, `Email = user.Profile.Email`, `Note = note` первыми, потом обычные члены;
  Enter на `Name = user.Name` дописывает запятую; map-all пишет Name и Email (Note — другой объект, Active — нечем)
- [ ] `TYPE:map-empty`: `var d = new MappingUserDto { ` → строки mapping первыми; с `Active = true, ` перед кареткой — после членов
- [ ] `TYPE:map-off`: Settings | .NET → Behavior → «Offer to copy members…» выключено → строк `… = user.…` нет
- [ ] `TYPE:remember`: трижды выбрать `user` в начале оператора, затем Ctrl+Space на пустой строке → `user` выше соседей своей группы; .NET → Behavior → «Forget the Choices» возвращает порядок

### Scope «Analysis» без сервера (0.1.122) — `Console/Editor/SolutionProblems.cs`
Settings | .NET | Language Server, группа Analysis: «Compiler diagnostics for» / «Analyzer diagnostics for» (openFiles / fullSolution / none) действуют и без сервера. Окно Problems → вкладка Project Errors.
- [ ] `TYPE:problems-closed-file`: fullSolution, файл не открыт — в Project Errors под `SolutionProblems.cs` строка CS0219 (жёлтая); у остальных файлов solution — их настоящие ошибки и предупреждения, по одной строке на диагностику; `Broken` (не в solution) не перечислен; в журнале плагина (.NET → Plugin Logs, категория «solution problems») — «N files checked in M ms»
- [ ] openFiles — вкладка показывает только открытые файлы, закрыл вкладку файла — строки ушли; none — C#-строк нет, в редакторе нет ни одной CSxxxx (ошибки последней сборки остаются)
- [ ] `TYPE:problems-typing`: fullSolution, набрать `int broken = ;` — строка CS1525 появляется в Project Errors через ~1 с один раз, Ctrl+Z — исчезает; набор не тормозит; Build Solution — вкладка не мигает (те же строки остаются)
- [ ] `TYPE:problems-analyzers`: «Analyzer diagnostics for» = fullSolution, Build Solution или сохранить любой `.cs` — предупреждения анализаторов Console (три из `Analyzers.cs`) в той же вкладке с их ID, Info-подсказок нет; openFiles — их строк нет, в открытом файле подчёркивания есть; none — подчёркиваний нет, Run Code Analysis по-прежнему показывает всё в окне Build
- [ ] Сервер включён, «Errors and warnings» = Language server, fullSolution — вкладку заполняет сервер (как раньше); Built-in — плагин; строки не удваиваются при переключении
