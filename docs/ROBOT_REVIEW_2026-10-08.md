# Прогон роботом и анализ плагина — 2026-10-08

Сборка 0.1.148 с ML (`-PmlEnabled=true -Pml.big=true`), IDEA Community 2026.1.4. Код плагина не менялся; в `tools/ui-robot/scripts`
добавлены скрипты (ниже). Площадка — копия `debug-playground`, solution `DebugPlayground.sln`, сервер Roslyn загружен.

Где гонялось: песочница Windows (`runIdeForUiTests`) оказалась бесполезной для редактора — рабочий стол был заблокирован (`LogonUI`),
у окна нет фокуса, попапы completion не показываются. Основной прогон — песочница в WSL на Xvfb (`tools/ui-robot/wsl`): там работают
API-скрипты, настоящая клавиатура и мышь.

## Что проверено и работает

| Область | Что сделано | Результат |
|---|---|---|
| Completion после точки | `text.`, `_orders.`, `Console.`, `System.Collections.Generic.`, `this.` | 152 / 151 / 52 пункта, ожидаемые члены на месте, статика и инстанс не смешаны |
| Completion по префиксу | `to` → `total` первым, `Cons` → `Console` первым, `text.Sub` → один `Substring` | автопопап без Ctrl+Space |
| Ожидаемый тип, override, имена, `goto` | маркеры `complete-*` в `NativeCompletion.cs` | `other` и `new CompletionSquare` первыми; 5 членов для override; `builder`, `stringBuilder`; `again` |
| Семантические ошибки | `Broken/SemanticErrors.cs` (59 ошибок), пробный файл с CS0029/CS0246/CS0103 | коды и места как у Roslyn, предупреждения CS0219 |
| Синтаксические ошибки | `Broken/SyntaxErrors.cs` | 11 ошибок/предупреждений, CS1011, CS1009, CS1021, CS0595, CS1519, CS1040, CS1030… |
| Ложные ошибки | все 50 файлов `Console/Editor/*.cs` (проект собирается) | одна ложная, см. ниже |
| Go to Declaration | 11 якорей `Navigation.cs` | локальные, `out var`, метки, переменные запросов, члены, библиотечный `Count` → `List.cs:79` |
| Extend Selection | `name.Trim()) + 1`, 6 шагов | `name` → `name.Trim` → `name.Trim()` → `count, name.Trim()` → `(…)` → `Compute(…)` |
| Форматирование | метод `Sum`, встроенный форматтер | Allman, `if/else` разложен, Undo вернул текст |
| Postfix и live templates | `orders.for`, `order.Total.var`, `itli` | разворачиваются, у `.var` список имён `orderTotal`, `total` |
| Inlay hints | `InlayHints.cs` | 58 подсказок: имена параметров, типы `var`, Code Vision «N usages» |
| Quick doc / hover | Ctrl+Q через провайдер, наведение настоящей мышью | `(parameter) int limit`, `(field) List<MemberOrder> MemberCompletion._orders` |
| Intentions | `Stopwatch` в `SemanticErrors.cs` | «Import 'System.Diagnostics.Stopwatch'», Create class / enum |
| Rename | Shift+F6 с клавиатуры на `subtotal` → `sum` | все три вхождения переименованы inline-шаблоном |
| Find Usages | `Counter` в `FindUsages.cs` | дерево по видам: Read / Write / nameof, см. замечание ниже |
| Типы параметров лямбд (0.1.147) | `Web/Program.cs` `/healthy`, пробный файл | `HttpContext context`, `object state` (конструктор `Timer`), `int n`, `int x` |
| Отладчик | `Console: All`, точки 60 и 91, 4× Step Over, Step Into мимо `Console.WriteLine`, Set Value, Resume, Stop | кадры с `[External Code]`, `number := 100`, вычисления 4–180 мс, длинные строки с `(167 chars)` |
| Сборка перед запуском | `Console` с синтаксической ошибкой | уведомление «Build Failed», окно Build с деревом ошибок и консолью |
| Тесты | конфигурация `dotnet test` на `Tests.csproj` | 12 тестов: 10 passed, 1 failed (намеренный), 1 ignored, время по каждому |
| Окна инструментов | NuGet (Packages / Sources / Folders / Log), Unit Tests, .NET Monitor, Endpoints, IL Viewer, EF Core | открываются, NuGet показывает 21 пакет solution с новыми версиями, IL Viewer следует за кареткой |
| Настройки | .NET, Toolset and Build, NuGet, Debugger, Language Server, ML completion, Code Analysis | страницы открываются, тексты на месте |
| Скорость | `Scenarios.cs`: 411 идентификаторов раскрашены через 57 мс после открытия; попап completion появляется в первом опросе | |

## Слабые места — ошибки

1. **Ложная ошибка CS0121 на явной реализации интерфейса.** `Console/Editor/ImplicitUsages.cs:37`:
   `IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();` — плагин считает `GetEnumerator()` неоднозначным между публичным
   методом и явной реализацией `IEnumerable.GetEnumerator`. Roslyn явную реализацию по простому имени не видит. Подтверждено с
   выключенным сервером (сообщает сам плагин). Это `extra` в живом коде, который собирается; паттерн частый
   (`IEnumerable<T>` + `IEnumerable`). Проверить тем же кодом `CS0121` в корпусе — в корпусе такого примера нет.
2. **Find Usages считает два вхождения в одной строке за одно.** `FindUsages.cs:30`: `Counter + 1 + Counter.ToString().Length` —
   в дереве «Read() (1)», ожидание маркера — 3 чтения, итого 13 использований, получено 12. Либо теряется второе вхождение
   (приёмник `Counter.ToString()`), либо результаты схлопываются по строке.
3. **Именованные элементы кортежа в отладчике.** `var tuple = (Id: 1, Name: "tuple")` в Variables и Evaluate показывает `Item1`,
   `Item2`. Rider показывает `Id`, `Name` (берёт имена из объявленного типа локальной переменной).
4. **Обозреватель Unit Tests показывает 10 тестов, прогон находит 12.** Theory/ignored-тесты в дереве обозревателя не учтены.
5. **Файлы проекта из другого solution того же каталога — тихая деградация.** Открыт `DebugPlayground.sln`, файл
   `ShopApi/Playground/*.cs` (solution `ShopApi.sln`): параметры из объявлений типизированы (`IServiceCollection services`), но
   библиотечной семантики нет вовсе — `services.AddSin` даёт пустой список, `context` в `MapGet` без типа, ошибок нет. Индекс сборок
   строится только для выбранного solution («8 projects, 732 assemblies»). Пользователю ничего не говорит, что файл «вне solution»;
   уведомление о трёх solution показывается только при старте.
6. **Индексатор сборок запускается на каждое движение.** В журнале Windows-песочницы 93 запуска `AssemblyIndexer.dll` за один сеанс
   (по 130–160 мс, «732 indexed» каждый раз), в WSL — та же картина: открытие файла, проверка, запуск — каждый раз новый процесс
   `dotnet`. Дёшево по времени, но это лишние процессы и запись в журнал; должен быть ответ «ничего не изменилось» без запуска.
7. **Подсказка типа `var` там, где тип очевиден.** `var DateTime when = new DateTime(…)`, `var Color color = Color.Green`,
   `var string multiline = "…"`, `var Point point = new Point(3, 4)`. Rider прячет подсказку, когда инициализатор — `new T(…)`,
   литерал или член перечисления. Вместе с именами параметров (`year:`, `month:`, `day:`, `hour:`, `minute:`, `second:`) строка
   превращается в кашу (см. снимок `Scenarios.cs:56`).
8. **ML-сборка: серый текст сети не показался.** После включения `inlineEnabled` / `rankerEnabled` на лету модели загрузились
   (`cs31m` за 418 мс, GBDT за 510 мс), но на `i` в `ml-if-header` и на пустой строке `ml-return-line` серого текста нет при
   пороге 0.7. Серый текст от PSI работает (`return Result.` → `Ok(customer);`). Не проверено с перезапуском IDE и
   с включённой большой моделью — возможно, порог или переключение на лету.

## Слабые места — удобство

- **Конфигурация по умолчанию — `AspireHost: https`.** После открытия площадки в тулбаре стоит первая по алфавиту конфигурация;
  Run без выбора запускает Aspire. Для solution с консольным/веб-проектом логичнее стартовый проект (как `StartupProject` в Rider).
- **Контекстное меню проекта — 24 пункта.** Плагинных 20 (New, Reload, Add, Rename Project, Move to Solution Folder, Edit csproj,
  Properties, Run, Debug, Build, Rebuild, Publish, Manage NuGet, Run MSBuild Target, Analyze Upgrade, Code Metrics, Code Analysis,
  Format, Verify Formatting, Clean, Remove) плюс платформенные Cut/Copy/Paste (серые), Analyze, **Rename… Shift+F6 рядом с
  Rename Project…**, Refactor, Bookmarks, Delete, Repair IDE. Два «Rename» и серые Cut/Paste — лишнее; редкие действия (Metrics,
  Upgrade, Verify Formatting, MSBuild Target) просятся в подменю «Tools».
- **Страница «C# Project Support» открывается при каждом старте** новой версии (и в Windows, и в WSL видна вкладка), в JCEF.
  Для разработчика, который ставит сборки каждый день, это шум; достаточно уведомления «What's New».
- **Уведомление «Make C# colors like Rider, Visual Studio, VS Code…?»** показывается в каждой новой песочнице; с кнопкой «Don't Show
  Again» нормально, но вместе с «3 solutions found», «ASP.NET certificate» и «Build Failed» при первом открытии четыре балуна сразу.
- **Отладчик живёт в окне Services**, а не в Debug: кадры, переменные и консоль — вкладки внутри Services с деревом всех
  конфигураций слева. Привычная для IDEA раскладка, но для пришедших из Rider не очевидно, где искать Variables.
- **«Build Failed» — только в уведомлении**, в журнале плагина текста ошибки нет («failed with 1 error»). Для отчётов пользователя
  (и робота) полезно писать первую ошибку в лог.
- **Ошибки сервера и плагина разного вида.** В `CodeLens.cs` (сломанный файл HEAD) на `str` стоит `IDE1007: The name 'str' does not
  exist` от Roslyn, плагинный CS0246 при живом сервере не показан — это по правилу «сервер главный», но коды в одном файле выглядят
  то как `CSxxxx`, то как `IDExxxx`.
- **`@string` среди предложений имени** для `StringBuilder` (`complete-names`): `builder`, `stringBuilder`, `@string`. Третье — мусор.
- **Два слоя подсказок по тексту:** после `Console.WriteLine(` показывается `value:`, после `Substring(` — `startIndex:`, `length:`,
  в `Pair(1, 2)` — `arg2:` (имя параметра из кортежа/ValueTuple). Rider имена `arg1`/`arg2`/`item1` скрывает.
- Площадка в HEAD не собирается: `Console/Editor/CodeLens.cs:59` (`public override str`), `ShopApi/Controllers/CustomersController.cs:26`,
  `Web/Program.cs:25`. Для чек-листа «файл компилируется как есть» это рушит отладочные сценарии — пришлось чинить копию.

## Сценарии разработки — оценка

- **Открыл папку → пишу код.** Хорошо: подсветка за 60 мс, completion, навигация, ошибки компилятора без сервера. Слабое место —
  несколько solution в одной папке (п. 5) и ложная ошибка на распространённом паттерне (п. 1).
- **Собрал и запустил.** Хорошо: Build перед запуском, окно Build как в IDEA, ошибка ведёт на строку. Слабое место — выбор
  конфигурации по умолчанию, ошибка сборки не в логе.
- **Отлаживаю.** Хорошо: точки, шаги, Step Into мимо внешнего кода, Set Value, Evaluate с детьми, длинные строки, inline-значения
  в редакторе. Слабое место — имена элементов кортежей, раскладка в Services.
- **Тестирую.** Хорошо: конфигурация `dotnet test`, дерево результатов с временем, вывод теста. Слабое место — счётчик в обозревателе.
- **Пакеты.** Окно NuGet с четырьмя вкладками, список установленных с новыми версиями; поиск с nuget.org отвечает за ~1,2 с.
- **Настройки.** Семь страниц, формулировки как в Rider, двуязычие; плотные страницы (ML completion — восемь числовых полей с
  длинными пояснениями) трудно читать в диалоге шириной 740 px.

## Инструменты проверки — что мешало и что добавлено

- Песочница Windows при заблокированном столе не годится для редактора (нет фокуса) — только WSL. В `tools/ui-robot/README.md` это
  стоит записать явно: «если `Get-Process LogonUI` даёт процесс — идти в WSL».
- `complete_at_line.js` снимает список в момент показа и часто даёт `items: 0`; строка набора в маркере вычисляется вручную и
  легко промахивается (у меня — набор внутри комментария и между методами). Добавлен **`complete_poll.js`**: набирает через
  `TypedAction`, опрашивает живой lookup, печатает текст строки, число пунктов, презентацию (`[item]`, хвост, тип) и позиции
  искомых; при отсутствии автопопапа жмёт Ctrl+Space; откатывает набор. Он же показал порядок `total` / `Console`.
- `popup_at.js` падает в 2026.1.4 (`IdeFrameImpl cannot be cast`), `rename_check.js` / `inline_rename.js` / `rename_solution.js`
  отвечают `handler: none` — хотя Shift+F6 с настоящей клавиатуры переименовывает. Добавлены **`doc_at.js`** (Quick Doc через
  провайдеры, без попапа), **`inlays.js`** (все inlay-подсказки файла с текстом), **`show_tool_window.js`** (окно по id и его
  вкладки), **`hover_point.js`** (координаты для xdotool относительно рамки — `screen_point.js` давал `y` за пределами экрана),
  **`all_highlights.js`** (все подсветки, включая слабые предупреждения). Rename роботом — `caret_at` (в `build/ui-robot`) + `shift+F6`
  через `screen.sh key`.
- `test_configuration.js` с именем проекта вместо пути открывает модальный диалог «Project file not found» и вешает робота — нужен
  путь к `.csproj`; в README это не сказано.
- `robot_js` срезает начало первой строки ответа (`items:` → `tems:`, `total` → `al`): вывод скриптов начинать не с `t`.
- Скрипт `copy-playground.sh` берёт `CompletionRanking.cs` из HEAD, но не `CodeLens.cs` — копия не собирается; список «сломанных в
  HEAD» файлов лучше держать в одном месте.

## Что не проверено

NetFramework (отдельный solution), ShopApi как загруженный solution (CompletionTour, EnumCompletion, GhostSuggestions, мои
`LambdaParameterTypes` / `GenericMethodRows` — в режиме «файл вне solution» списков нет), Hot Reload, Aspire, покрытие, аллокации,
EF Core миграции, `dotnet format` / CSharpier, Solution-wide errors, Find Usages по solution, Go to Class/Symbol, .NET Monitor под
нагрузкой, вторая тема и шрифты Windows (всё — Linux-рендер).
