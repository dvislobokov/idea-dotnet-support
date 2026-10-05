# Путь разработчика: сквозная проверка плагина (0.1.95)

Дата: 2026-10-05. Среда: песочница IDE в WSL (`tools/ui-robot/wsl`, IntelliJ IDEA Community 261.26222.65, Xvfb, настоящий ввод через xdotool),
.NET SDK 10.0.401, сеть до nuget.org есть. Плагин: `build/distributions/idea-dotnet-support-0.1.95.zip`. Площадка — новое solution
`~/journey/Journey` в WSL (в репозиторий не входит). Снимки — `build/dev-journey/NN-*.png` (не коммитятся; имена в графе «Снимки»).

Шкала: **блокер** — путь не проходится без обхода; **мешает** — проходится, но с потерей времени или неожиданным результатом;
**мелочь** — косметика, видно, но не мешает.

Время по этапам (с ожиданием сборок и перезапуском IDE после падения NuGet): этап 1 — ~10 мин, этап 2 — ~15 мин, этап 3 — ~10 мин,
этап 4 — ~20 мин, этап 5 — ~15 мин (из них 1 мин установка отладчика), этап 6 — ~5 мин. Итого около 75 мин чистого прохода.

---

## Этап 1. New Solution с экрана приветствия

**Сделано.** Welcome → New Solution (действие плагина). Диалог похож на Rider: слева шаблоны (Console, Class Library, Web API, xUnit…),
справа имя, путь, Target Framework, Language, «Create Git repository». Попробованы шаблоны Console, ASP.NET Core Web API, xUnit;
в итоге создано «Empty Solution» (slnx, git-репозиторий, `.gitignore`), ~15 с.

**Работает.** Диалог, валидация имени, выбор пути, создание git-репозитория, `.slnx` по умолчанию, открытие созданного solution.

| № | Серьёзность | Что не так | Как в Rider | Предложение | Снимки |
|---|---|---|---|---|---|
| 1.1 | **блокер** | С SDK 10 **все** шаблоны (Console, Class Library, Web API, xUnit) лежат в группе «Not supported for the selected Target Framework», и для любого TFM. Если всё же выбрать шаблон и нажать Create, кнопка молча гаснет: ни сообщения, ни файлов (валидация на скрытом списке). Причина: `TemplateOptions.frameworks(help)` ждёт `-f, --framework <net10.0\|net9.0>`, а `dotnet new <t> -h` в SDK 10 печатает `-f, --framework <choice>` и варианты на следующих строках → список каркасов = `["choice"]`. Обход: Empty Solution + Add → New Project (там другой диалог). | Шаблоны и TFM берутся из движка шаблонов, нет ручного разбора help. | Разбирать блок `<choice>` + строки с вариантами (или `dotnet new <t> -h` с `--language`, либо `dotnet new list --columns`); при пустом списке считать «любой TFM», а не «ни один»; при невалидном состоянии показывать текст ошибки у кнопки. Добавить тест на фикстуру help SDK 10. | 05, 06, 07, 09, 10 |
| 1.2 | мелочь | Блок описания шаблона (Identity, Group ID, Author, Classifications) занимает половину диалога и ничего не даёт пользователю. | Одно предложение описания. | Оставить описание и язык, остальное — в tooltip. | 03, 04 |
| 1.3 | мелочь | Шаблоны в группе «Not supported» без объяснения *почему*. | Группы «Other» / тип проекта. | Подпись с конкретной причиной (какие TFM поддерживает шаблон). | 05 |

---

## Этап 2. Проекты, ссылки, папки, переименование

**Сделано.** Add → New Project: консоль, class library (Journey.Domain), xUnit (Journey.Tests), Web API (Journey.Api); Add → Project Reference
из App/Tests/Api на библиотеку; New Solution Folder `src`; попытка перетащить проект в папку; Rename Project Journey.Domain → Journey.Core.

**Работает.** Создание проектов (~15–20 с каждый), csproj появляется в дереве сразу, Project Reference обновляет csproj и ветку
Dependencies → .NET 10.0 → Projects/Frameworks; Solution Folder пишется в `.slnx`; Rename Project переименовывает папку, csproj, запись
в slnx и ссылки в других проектах.

| № | Серьёзность | Что не так | Как в Rider | Предложение | Снимки |
|---|---|---|---|---|---|
| 2.1 | **мешает** | Add → New Project — это **другой, старый диалог**, не тот, что New Solution: плоский алфавитный combo всех шаблонов, опции с сырыми именами из CLI («Use program main», «Aot», «Enable pack»), для Web API показаны все поля авторизации (Aad b2c instance, Susi policy id…) даже при Auth = None, Framework «(template default)». | Один и тот же диалог New Solution / New Project. | Переиспользовать `NewSolutionDialog` в режиме «добавить в solution»; опции шаблона показывать только с зависимостями, удовлетворёнными текущими значениями (`dotnet new` отдаёт `isEnabled`-условия). | 16, 17, 21 |
| 2.2 | **мешает** | Drag & drop проекта в Solution Folder ничего не делает, а в журнале — `SEVERE AsyncPromise: Access is allowed from EDT only` из `ProjectViewDropTarget.MoveDropHandler.canDrop` (платформенный drop-хендлер зовёт `DataManager.getDataContext` в пуле). Пункта «Move to Solution Folder» в меню тоже нет. | Перетаскивание проектов между папками, «Move to folder». | Либо свой `DnDTarget` у дерева с переносом в `.slnx`, либо действие «Move to Solution Folder…» в контекстном меню и запрет платформенного drop на наших узлах. | 33 |
| 2.3 | мешает | После Add → New Project узел solution остаётся свёрнутым/новый проект не раскрыт и не выделен — надо искать, что добавилось. | Новый проект выделен и раскрыт. | Выделять и раскрывать созданный проект, открывать его главный файл. | 18 |
| 2.4 | мелочь | Add → Project Reference: безымянный список чекбоксов без заголовка, без имён путей/TFM, без фильтра. | Диалог с деревом проектов и путями. | Заголовок «Projects of the solution», TFM рядом с именем, поиск. | 26 |
| 2.5 | мелочь | Rename Project не предлагает переименовать namespace в файлах (`namespace Journey.Domain;` остался в Class1.cs). | Галочка «Rename namespaces». | После переименования — опция «Adjust namespaces» (у плагина уже есть rename по solution). | 35, 36 |
| 2.6 | мелочь | В контекстном меню узлов — чужие пункты IDEA («Repair IDE on File», «Kotlin Script»). | Только своё. | Скрывать через `ActionGroup` плагина / `actionPromoter` не поможет — убрать видимость лишних групп для наших узлов. | 24 |

---

## Этап 3. NuGet

**Сделано.** «Manage NuGet Packages…» из контекстного меню проекта (упало), после перезапуска — окно из панели инструментов: поиск
Serilog, установка в Core, удаление, повторная установка, обновление xunit.runner.visualstudio 2.9.3 → 4.0.0, вкладки Log и Sources.

**Работает.** Поиск по nuget.org (~1 с), карточка пакета с версией и кнопками «+ / ↑ / 🗑» по проектам, установка/удаление/обновление
правят csproj и делают restore, журнал запросов во вкладке Log, пометка deprecated.

| № | Серьёзность | Что не так | Как в Rider | Предложение | Снимки |
|---|---|---|---|---|---|
| 3.1 | **блокер** | «Manage NuGet Packages…» из контекстного меню проекта → `NullPointerException: "requests" is null` в `NuGetPanel.background(NuGetToolWindow.kt:688)` ← `reload(268)` ← `selectRequestedProject(244)` ← `reloadScopes(232)` ← `NuGetPanel.<init>(189)` ← `createToolWindowContent(72)` ← `ManageNuGetPackagesAction(775)`. Окно показывает «Nothing to show» до перезапуска IDE. Причина: `installedRequests` объявлен ниже init-блока, который уже зовёт `reload()`. | Открывает окно на вкладке проекта. | Перенести объявление поля выше init или отложить `selectRequestedProject()` в `invokeLater`; тест, открывающий окно с запрошенным проектом. | 37, 38, 39 |
| 3.2 | **мешает** | На каждый запрос к nuget.org зовётся `NuGetCredentialStore.get` → на Linux без keychain падает `PasswordSafe` с «Plugin to blame: C# Project Support» и всплывающим «IDE error occurred» (libsecret/KWallet). | Credential provider спрашивается только когда фид ответил 401. | Не трогать хранилище для фидов без учётных данных (nuget.org и любые, где нет записи), и только после 401/403. | лог 22:11:21 |
| 3.3 | мешает | Окно NuGet низкое: строки проектов в карточке обрезаются снизу, а уведомления-balloon закрывают кнопки «+». | Карточка со скроллом, balloons не над кнопками. | Прокрутка внутри карточки; результат установки — в Log/статусную строку, не balloon. | 42, 44 |
| 3.4 | мелочь | Нет «Installed/Updates» как отдельных фильтров в один клик — видно только через переключатель. | Вкладки Installed / Updates. | Счётчик обновлений на кнопке Updates. | 46, 49 |

---

## Этап 4. Код

**Сделано.** В Journey.Core: New → Class/Interface → record `Order`, enum `OrderStatus`, интерфейс `IOrderRepository`, класс
`InMemoryOrderRepository : IOrderRepository` с Alt+Enter → Implement missing members. В Journey.App: Serilog, использование типов из Core
(completion из другого проекта + вставка using), LINQ (`Where/Sum/OrderByDescending`), async/await, интерполяция с форматами, Serilog-плейсхолдеры.
В Journey.Api: DI (`AddSingleton<IOrderRepository, InMemoryOrderRepository>`), `builder.Configuration["Journey:Greeting"]`, два `MapGet`,
правка `appsettings.json`. Проверены Parameter Info, completion членов enum, подсказки лямбд, Go to Declaration, Find Usages, Rename,
Reformat, F2 к ошибке.

**Работает (и хорошо).** Completion типов из другого проекта с автоматическим `using Journey.Core;` (73, 74, 89, 93); completion после
`OrderStatus.` и в `== ` — члены enum первыми (76, 78); подсказки лямбд с перегрузками `Func<Order,bool>` / `Func<Order,int,bool>` и ghost-текст
`order =>` (77); Parameter Info с выделением текущего параметра (75); Implement missing members — диалог как в Rider (67, 68); Go to Declaration (84);
Find Usages с группировкой «New instance creation / Usage in declaration type / Usage in type argument» (85); inline Rename (86, 87);
F2 показывает `CS1022` текстом Roslyn (81); иконки endpoint в gutter у `MapGet` (94); схема для `appsettings.json` с секциями проекта
(статусбар «JSON: appsettings.json (.NET, sections of Journey.Api from its code)», 97); вложенность `appsettings.*.json` (95).

| № | Серьёзность | Что не так | Как в Rider | Предложение | Снимки |
|---|---|---|---|---|---|
| 4.1 | **мешает** | **Implement missing members теряет значения по умолчанию** параметров: из `Task AddAsync(Order order, CancellationToken ct = default)` сгенерировано `AddAsync(Order order, CancellationToken ct)`. Код выглядит правильным, но вызовы `repo.AddAsync(order)` падают на сборке `CS7036` — три ошибки на первой же сборке; редактор до сборки их не показывал. | Генерирует сигнатуру с `= default`. | Переносить `EqualsValueClause` параметров (и `params`, `this`, атрибуты `[NotNull]`) в сгенерированный член; тест на интерфейс с опциональными параметрами. | 68, 101, 102 |
| 4.2 | **мешает** | Alt+Enter на неразрешённом типе (`OrderStatus` в record, `_orders` в теле) предлагает только «Add a partial part…» и «Create test…» — нет **Create class/enum/field/property**. Приходится писать руками. | Create type / Create field / Create property / Create local. | Контекстные действия «Create enum/class/record 'X'» (в текущий файл или новый) и «Create field/property 'x'» из использования; это самые частые quick fix в Rider. | 58, 70, 71 |
| 4.3 | **мешает** | Автозакрытие скобок непредсказуемо: после `new InMemoryOrderRepository()` (каретка внутри) нажатие `;` дало `InMemoryOrderRepository(;` — закрывающая скобка **удалена**; в `Console.WriteLine($"…");` набранная `)` не перепечатала авто-вставленную — получилось `");)` и ошибка `CS1022`. | `;` внутри `()` допечатывает `);` и уводит каретку (complete statement), `)` всегда перепечатывает парную. | Проверить `CSharpEditing`/typed handler на `;` внутри пустых скобок и overtype `)` после интерполированной строки; добавить сценарии `// TYPE:` в `debug-playground`. | 74→75 (строка 5), 79, 80 |
| 4.4 | мешает | Enter после однострочного `public enum OrderStatus { New, Paid, Shipped }` даёт лишний отступ на следующей строке; после `foreach (…)` без `{` — отступа **нет**. | Отступ по правилам: после закрытой `{}` — тот же уровень, после `foreach` без скобок — +1. | Правила в `csharpIndent/rules.json` (условие «предыдущая строка кончается на `}` того же блока» и «предыдущая строка — заголовок statement без `{`») + примеры. | 59, 60, 79 |
| 4.5 | мешает | Reformat Code (Ctrl+Alt+L) на классе, набранном в одну строку, сообщает «Formatted 3 lines», но строку не разбивает — только поправил пустую строку после `namespace`. | Полная переформатировка (переносы `{`, по оператору на строку). | Хотя бы разбивать `{`/`}` и `;` на строки в режиме whitespace-форматирования или честно называть действие «Fix indentation». | 109 |
| 4.6 | мелочь | Шаблон record создаёт `public record Order();` с кареткой в 1:1, а не внутри скобок; в хлебных крошках у record показано «…». | Каретка внутри `()`. | Шаблон с `$CARET$`; починить presentable text у record в breadcrumbs. | 55, 57 |
| 4.7 | мелочь | После `OrderStatus.` в списке сразу за членами enum идут postfix-шаблоны (arg, await, cast, nameof, par, parse…) — шум в контексте «тип.». | Только члены. | Не предлагать postfix после имени типа. | 76 |
| 4.8 | мелочь | Serilog-плейсхолдеры `{Count}`, `{Total:N2}` не подсвечиваются и не сверяются с числом аргументов; у `new Order(1, "Alice", …)` нет inlay-подсказок имён параметров. | Подсветка message template, inlay hints. | Подсветка `{Name[:format]}` в строке-аргументе методов с `[MessageTemplateFormatMethod]`/по имени `Log.*`; inlay hints для литералов-аргументов. | 83 |
| 4.9 | мелочь | В `appsettings.json` после ввода `"Journey": { … },` появилась двойная запятая `},,` (видимо, автодобавление запятой + моя). | — | Проверить typed handler JSON у плагина (если это наш) — не добавлять запятую, если следующий символ уже `,`. | 97 |
| 4.10 | мелочь | Красные/жёлтые маркеры в правой полосе остались после Rename при зелёной галочке «No problems». | — | Сбрасывать маркеры rename-подсветки по завершении. | 87 |

---

## Этап 5. Сборка, запуск, тесты, отладка

**Сделано.** Build Solution (3 ошибки CS7036 → навигация → правка → пересборка), Run Journey.App, запуск xUnit-класса из gutter
(1 failed / 3 passed), переход к упавшему тесту, окно Unit Tests, Debug теста (сначала без адаптера → Install из уведомления → повтор),
точка останова, Step Over, переменные, Evaluate, Stop; запуск Journey.Api по профилю `http` из launchSettings, `curl` к эндпоинтам, Stop.

**Работает (и хорошо).** Сборка ~4 с с деревом ошибок и полным текстом справа, двойной клик ведёт в файл (101, 102); Run: `dotnet run --no-build`,
вывод в Services (107); тесты: ~10 с, дерево результатов, падение с Expected/Actual и кликабельным стеком (113, 114); установка отладчика
одной ссылкой из уведомления (45 с) (118); отладчик: остановка на точке ~30 с от запуска, inline-значения, **возвращённые значения**
(`GetAllAsync() returned …`, `Assert.Single() returned Order { Id = 1, … }`), Evaluate `all.Count + 1` = 2, Step Over, корректный Stop
с «Tests passed: 1» (123–128); Api: профили `http`/`https` из launchSettings, ссылка `http://localhost:5017` рядом с конфигурацией,
`/` вернул значение из appsettings, `/orders` → `[]`, graceful stop (133–135).

| № | Серьёзность | Что не так | Как в Rider | Предложение | Снимки |
|---|---|---|---|---|---|
| 5.1 | **мешает** | Debug без установленного адаптера: уведомление «The dotnet-debugger-dap global tool is not installed… Install» — хорошо, но **testhost остаётся висеть** («Host debugging is enabled. Please attach debugger…»), пока не нажмёшь Stop; после Install отладка не перезапускается сама. | Отладчик идёт в комплекте. | Проверять адаптер **до** запуска `dotnet test`; после успешной установки предлагать «Debug again» в том же уведомлении. | 118, 121 |
| 5.2 | мешает | Текст уведомления при неудачной сборке перед запуском: «The build of Journey.App before the launch has failed, so 'Journey.App' **was** started» — пропущено «not» (`LaunchBuilds.kt:116`, ветка `batch.size == 1`). | «…was not started». | Исправить строку; тест на формулировку. | 105 |
| 5.3 | мешает | Двойной клик по упавшему тесту ведёт к **объявлению метода**, а не к строке `Assert` из стека; статус тестов не отражается ни в gutter (иконки остаются серыми), ни в окне Unit Tests (там «3 tests» без результатов последнего прогона). | Переход к строке падения; зелёные/красные иконки в gutter и в Unit Tests. | Парсить первую user-frame из стека и ставить каретку туда; хранить результат по id теста и показывать в gutter/Explorer. | 115, 116 |
| 5.4 | мелочь | Gutter-меню у тестового метода называет конфигурацию «Run 'OrderRepositoryTests…'» (имя класса с многоточием) вместо имени теста. | «Run 'Test name'». | Короткое имя: `Class.Method`. | 117 |
| 5.5 | мелочь | Ошибки CS7036 (вызов без обязательного аргумента) редактор не показывал до сборки — пользователь узнаёт о них только после Build. | Ошибка сразу в редакторе. | Семантическая проверка арности вызова (уже есть overload resolution — добавить диагностику). | 79, 101 |
| 5.6 | мелочь | Результаты тестов и Run живут в окне **Services**; окно Unit Tests — отдельно и только Explorer. | Всё в одном окне Unit Tests (сессии + explorer). | Либо сессии в Unit Tests, либо убрать дублирование. | 113, 116 |

---

## Этап 6. Git, Show All Files, вложенность, Search Everywhere

**Сделано.** Проверены цвета VCS в Solution view, Show All Files, вложенность `appsettings.*.json`, Search Everywhere по символу `GetAllAsync`,
окно Git.

**Работает.** Цвета статусов git у файлов в дереве; Show All Files показывает `bin/obj/*.csproj` и выключается обратно (129); вложенность
appsettings (95); Search Everywhere находит метод в обоих местах с сигнатурами и текстовые вхождения, `Open in Right Split` (130); Git Log/Commit —
стандартные окна IDE (131).

| № | Серьёзность | Что не так | Как в Rider | Предложение | Снимки |
|---|---|---|---|---|---|
| 6.1 | мелочь | В Show All Files `bin`/`obj` — обычные папки без приглушения, csproj показан среди файлов. | `bin/obj` приглушены, csproj виден всегда. | Приглушённый цвет у `bin/obj`, csproj — всегда в дереве (как в Rider «Edit project file» открывает без Show All). | 129 |
| 6.2 | мелочь | Нет быстрого «Open in Browser» у запущенного Api (ссылка есть в дереве Services, но не в balloon/статусбаре). | Автооткрытие браузера по launchSettings (`launchBrowser`). | Уважать `launchBrowser: true` из профиля. | 133 |

---

## Что не проверено и почему

- **Coverage** — не запускалось (нет `dotnet-coverage`/coverlet в песочнице, времени на установку не тратили).
- **New Solution с шаблонами** на SDK 10 — не проходится из-за 1.1 (обойдено Empty Solution).
- **NuGet из контекстного меню** — после 3.1 только через панель инструментов.
- **Hover-подсказки и ощущение скорости** набора — робот снимает кадры, задержек не измерял; явных подвисаний не замечено
  (highlighting иногда показывает «Paused…» в правом верхнем углу во время набора — платформенное).
- Светлая тема, HiDPI — не смотрели.

## Падения и исключения за прогон (по `idea.log`)

1. `NullPointerException: "requests" is null` — `NuGetPanel.background(NuGetToolWindow.kt:688)` (см. 3.1), «Unhandled exception in EDT».
2. `PasswordSafeSettings — Unable to load library 'secret-1'` с «Plugin to blame: C# Project Support» при каждом NuGet-запросе (см. 3.2).
3. `AsyncPromise — Access is allowed from EDT only` из `ProjectViewDropTarget.MoveDropHandler.canDrop` при drag & drop в Solution view (см. 2.2).
4. `[debugger] cannot start debugging: The debug adapter is not installed` — ожидаемо, но с висящим testhost (см. 5.1).

---

## Топ-15 улучшений (по убыванию пользы)

1. **New Solution на SDK 10**: разбор `<choice>` в help шаблонов, иначе первый экран плагина не работает (1.1).
2. **NuGet из меню проекта**: NPE из-за порядка инициализации `installedRequests` (3.1).
3. **Implement missing members** переносит значения по умолчанию параметров (4.1).
4. **Create class/enum/field/property** из Alt+Enter на неразрешённом имени (4.2).
5. **`;` и `)` внутри авто-скобок**: не удалять парную скобку, перепечатывать `)` после интерполированной строки (4.3).
6. **Один диалог** для New Solution и Add → New Project; опции шаблона по зависимостям (2.1).
7. **Credential store** только по 401 — убрать «IDE error occurred» на Linux/macOS без keychain (3.2).
8. **Переход к строке падения теста** и статус тестов в gutter/Unit Tests (5.3).
9. **Проверка адаптера до запуска** `dotnet test`, «Debug again» после установки (5.1).
10. **Отступы**: после однострочного `{ … }` и после `foreach` без скобок (4.4).
11. **Move to Solution Folder** / свой drag & drop, без EDT-исключения (2.2).
12. **Reformat Code** разбивает строки, а не только чинит отступы (4.5).
13. Диагностика **CS7036 в редакторе** до сборки (5.5).
14. Текст «was **not** started» в уведомлении о неудачной сборке перед запуском (5.2).
15. Выделять и раскрывать новый проект после Add → New Project; каретка внутри `()` у шаблона record; postfix не после `Тип.` (2.3, 4.6, 4.7).
