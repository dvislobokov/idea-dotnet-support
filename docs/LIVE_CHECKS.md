# Что осталось проверить вживую

Единый список всего, что сделано и покрыто тестами, но **человеком в живой IDE ещё не посмотрено**. Собрано 2026-10-04 из `ROADMAP.md`
(пункты «Вживую не проверено» и т. п.), непроставленных пунктов чек-листа `debug-playground/README.md`, `CSHARP_PSI_MIGRATION.md`,
`NET_FRAMEWORK_PLAN.md`, `DAP_PLAN.md`, `LSP_PLAN.md`; версии — по `CHANGELOG.md`, у пунктов без версии — дата из `ROADMAP.md`.

Устройство: по каждой области — таблица-обзор (номер, версия, что, где, статус), под ней — шаги по каждой строке (`#### E-01 …`).

**Статусы:** `⬜ не проверено` · `🤖 робот` — проверено UI-роботом (`tools/ui-robot`), человеку остаётся то, чего робот не видит (вид,
мышь, фокус, ощущение скорости, вторая тема) · `✅ вживую` · `❌ не работает` (с пометкой, что именно).

С 2026-10-05 у робота есть песочница в WSL на невидимом экране (`tools/ui-robot/README.md`, «Песочница в WSL»): там он сам водит настоящей
мышью (подсказки по наведению), набирает с клавиатуры, снимает экран и пишет видео. Пункты, где человеку оставались только мышь и набор,
можно проверять ею; отметка в статусе — `🤖 робот (WSL)`. Оговорка: это IDEA для Linux, отрисовка и шрифты Windows так не проверяются.

## Подготовка

1. **Собрать плагин** (Git Bash, из корня репозитория):
   ```sh
   export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
   ./gradlew.bat buildPlugin
   ```
   Готовый zip — в `build/distributions/`.
2. **Поставить в IDE** (IDEA или GoLand 2026.1+): Settings | Plugins → ⚙ → **Install Plugin from Disk…** → выбрать zip → Restart IDE.
   Старую версию плагина перед этим удалять не нужно — она заменится.
3. **Собрать площадку** один раз (нужно для IL Viewer, аллокаций, `TargetPath`): `dotnet build debug-playground/Console`
   (или .NET → Build Solution уже в IDE). Ожидается 0 ошибок.
4. **Открыть** папку `debug-playground` (File | Open). Если IDE спросит, какой solution грузить, — выбрать `DebugPlayground.sln`.
   Для раздела .NET Framework — отдельно папку `debug-playground/NetFramework` (`NetFramework.sln`).
5. **Дождаться сервера**: в статус-баре виджет «Roslyn: DebugPlayground.sln» (не `loading…`). Всё, что помечено «сервер», без него не проверять.
6. **Переключатели «свой парсер / сервер»**: Settings | .NET | **Language Server** → группа **Source of Features**:
   «Structure, folding and breadcrumbs» и «Kinds of usages» — `Built-in` (по умолчанию, свой парсер) или `Language server` (сервер +
   прежние эвристики). Если в шагах не сказано иное — оставлять по умолчанию (Built-in).
7. **Маркеры**: `// TYPE:<имя>` — курсор на пустую строку под маркером, набрать сказанное, сверить, **отменить Ctrl+Z** перед следующим
   маркером; `// BP:<имя>` — поставить точку останова на эту строку (клик по полю или Ctrl+F8) и запустить Debug указанного профиля;
   `// IL:`, `// LIVE:`, `// ALLOC:` — как написано в комментарии. Полный текст ожиданий — в `EXPECT` комментария маркера; здесь — коротко.
8. **Журналы**: .NET → **Plugin Logs** (окно, категории), Help | Show Log in Explorer → `idea.log`.
9. **Как сообщать**: номер строки + статус + заметка, например `E-07 ✅`, `F-02 ❌ группа File Structure пустая`, `D-07 ✅ hover работает,
   но медленно`. Оркестратор поставит отметки в этот файл, `ROADMAP.md` и `debug-playground/README.md`.

---

## C# PSI / свой парсер (0.1.45+)

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| P-14 | 0.1.48 | Extend Selection (Ctrl+W) по дереву | `Console/Editor/ExtendSelection.cs`, `TYPE:extend-selection-*` | 🤖 |
| P-15 | 0.1.48 | Complete Statement (Ctrl+Shift+Enter) по дереву; Ctrl+Z после него | `Console/Editor/CompleteStatement.cs`, `TYPE:complete-*` | 🤖 |
| P-16 | 0.1.48 | Серый `;` в конце многострочного оператора | `CompleteStatement.cs`, `TYPE:gray-semicolon` | 🤖 |
| P-17 | 0.1.47 | Go to Class / Go to Symbol из stub-индексов | Ctrl+N / Ctrl+Alt+Shift+N по площадке | 🤖 |
| P-18 | 0.1.47 | Go to Class после смены TFM ведёт на нужную ветку `#if` | `MultiTarget/ActiveBranch.cs` + комбобокс TFM | 🤖 (ошибка найдена и исправлена, робот 2026-10-04) |
| P-19 | 0.1.47 | Первая индексация после установки плагина заполняет Go to Class | любой solution, первый запуск IDE с новой версией | ⬜ (робот видел пустой индекс один раз, не повторилось) |
| P-01 | 0.1.46 | Виды использований на своём дереве: поле `Total` | `Console/Editor/FindUsages.cs`, `TYPE:find-usages-native-field` | 🤖 |
| P-02 | 0.1.46 | То же: члены с вложенным инициализатором `Lines`, `Inner` | `FindUsages.cs`, `TYPE:find-usages-native-members` | 🤖 |
| P-03 | 0.1.46 | То же: тип `UsageTally` | `FindUsages.cs`, `TYPE:find-usages-native-type` | 🤖 |
| P-04 | 0.1.46 | Built-in: старые маркеры дают те же числа, что сервер | `FindUsages.cs`, `find-usages-field/-method/-type/-attribute` | 🤖 |
| P-05 | 0.1.46 | Обратно на «Language server» — сразу, без переоткрытия | Source of Features → Kinds of usages | 🤖 |
| P-06 | 0.1.45 | Переключатель «Structure, folding and breadcrumbs» на странице | Settings \| .NET \| Language Server | 🤖 |
| P-07 | 0.1.45 | Structure / folding / breadcrumbs: своё дерево = эвристика | `Console/Types.cs` (без маркера) | 🤖 |
| P-08 | 0.1.45 | ▶ у тестов и `Main` на своём дереве | `Tests/PricingTests.cs`, `NetFramework/LegacyConsole/Program.cs` | 🤖 |
| P-09 | 0.1.45 | Ветки `#if` по TFM тулбара | `MultiTarget/ActiveBranch.cs`, `TYPE:active-branch` | 🤖 |
| P-10 | 0.1.45 | Исправленные случаи дерева (`#if` с двумя заголовками и др.) | нет сценария | ⬜ |
| P-11 | 0.1.45 | Потребители модели на своём дереве (Go to Symbol, IL, Go to Base, маршруты) | `GoToBase.cs`, `IlViewer.cs`, `Web/Program.cs` | ⬜ |
| P-12 | 0.1.45 | Ошибки своего парсера не подчёркиваются | нет сценария | ⬜ |
| P-13 | 0.1.43 | Исходные замеры шага 0 — подтвердить на глаз | `Console/Editor/Measurements.cs`, `TYPE:measure-*` | 🤖 |

#### P-14 Extend Selection по дереву
1. Source of Features → «Typing assistance» = `Built-in` (умолчание с 0.1.48).
2. `Console/Editor/ExtendSelection.cs`, `TYPE:extend-selection-call`: каретка внутри `name` в `name.Trim()`, Ctrl+W раз за разом.
3. Ожидается: `name` → `name.Trim` → `name.Trim()` → аргументы → `(аргументы)` → вызов → `… + 1` → `total = …` → `var total = …;` → с
   комментарием → тело → метод с атрибутом → с doc-комментарием. Не должно быть прыжка с `name` сразу к телу метода (так делают токены).
4. `TYPE:extend-selection-string`: в `"plain text here"` второй Ctrl+W выделяет текст **без** кавычек; в `$"total {…}"` — текст без `$"`.
5. Переключить на «Language server» — сразу, без переоткрытия файла, выделение снова по токенам.

#### P-15 Complete Statement по дереву
1. «Typing assistance» = `Built-in`. `Console/Editor/CompleteStatement.cs`, по маркерам `TYPE:complete-*`: набрать на пустой строке
   под маркером то, что в нём сказано, нажать **Ctrl+Shift+Enter**.
2. Ожидается: `Make(a, b` → `Make(a, b);` и новая строка; `if (ready` → `if (ready)` и блок `{ }` с кареткой внутри; `while (Ready(` →
   `while (Ready())` и блок; `public void Run()` → тело; `public int Total` → `;`; `Make(a, ` — ничего не дописано.
3. `TYPE:complete-two-lines`: каретка в конец строки `Make(a,` → вызов не разрывается, новая строка под `b);`.
4. После каждого случая **Ctrl+Z** до исходного текста: робот откатывал за 3 нажатия в обоих режимах — проверить, что так же руками и
   ничего не остаётся.

#### P-16 Серый `;`
1. `CompleteStatement.cs`, метод `Grays`: стереть `);` в конце `.Where(x => x > 0);`, набрать `)`.
2. Ожидается: серый `;` после `)`, Tab вставляет его. С «Language server» серого `;` там нет.

#### P-17 Go to Class / Symbol из stub-индексов
1. Открыть площадку, дождаться конца индексации. Удобнее с выключенным сервером (Language Server → снять Enable): тогда отвечает только плагин.
2. **Ctrl+N** `UsageTally` → `UsageTally (Playground.Editor)`, переход на `FindUsages.cs:91`; `PricingTests`, `UsageLog` — так же.
3. **Ctrl+Alt+Shift+N** `Total` → 4 строки (метод `Pricing`, свойство `ShopOptions`, метод `CompletionRanking`, поле `UsageTally`).
4. Не должно быть дублей одной строки и долгого «Indexing…» на маленьком solution.

#### P-18 Ветки `#if` после смены TFM
1. Сервер выключен (как в P-17). `MultiTarget/ActiveBranch.cs` не открывать.
2. TFM на тулбаре — по умолчанию: Ctrl+N `Net9Only` → есть; `Net10Only` → нет.
3. TFM = net10.0, подождать пару секунд: Ctrl+N `Net10Only` → строка `Net10Only`, переход на неё; `Net9Only` → нет.
4. Обратно net9.0 / по умолчанию — снова только `Net9Only`. Раньше (до исправления) `Net10Only` вёл на класс `Net9Only`.

#### P-19 Первая индексация после установки
1. Поставить новую версию плагина, перезапустить IDE с большим solution.
2. После индексации Ctrl+N по имени любого класса из проекта → находится. Если пусто — File → Invalidate Caches не трогать, сообщить
   (и приложить `idea.log`): робот один раз видел пустой индекс стабов при первом запуске новой песочницы.

#### P-01 Поле `Total` (виды использований, Built-in)
1. Source of Features → «Kinds of usages» = `Built-in`; дождаться «Roslyn: DebugPlayground.sln».
2. `Console/Editor/FindUsages.cs`, маркер `TYPE:find-usages-native-field`: курсор на `Total` под маркером, **Alt+F7**.
3. В окне Find включить Group by Usage Type (шестерёнка / View Options).
4. Ожидается 5: «Declaration» 1, «Write access» 2 (`(Total, var count) = other` и `Total = 3` внутри `Inner = { … }`), «Read access» 2.
5. Не должно быть группы «Unclassified». `t.Total` в запросе отсутствует — так отвечает сервер 5.12, это не ошибка.

#### P-02 `Lines` и `Inner`
1. Как P-01, маркер `TYPE:find-usages-native-members`: курсор на `Lines` (выше маркера), Alt+F7.
2. Ожидается «Read access» 2 (в т. ч. `Lines = { 1, 2 }`), «Declaration» 1.
3. Курсор на `Inner`, Alt+F7: «Read access» 1 (`Inner = { Total = 3 }`), «Declaration» 1. «Write access» здесь быть не должно.

#### P-03 Тип `UsageTally`
1. Как P-01, маркер `TYPE:find-usages-native-type`: курсор на `UsageTally` в заголовке класса, Alt+F7.
2. Ожидается: «Declaration» 1, «Usage in declaration type» 4 (в т. ч. `out UsageTally made`), «Usage in type argument» 1
   (`IEnumerable<UsageTally>`), «Type check (is / as)» 1 (`UsageTally { Total: > 0 }` в `switch`).
3. Не должно быть «Write access» у `out`-параметра и «Read access» у паттерна.

#### P-04 Старые маркеры на Built-in
1. «Kinds of usages» = `Built-in`. Пройти F-01, F-04, F-05 (маркеры `find-usages-field`, `-method`, `-type`, `-attribute`).
2. Числа и группы — ровно те, что в EXPECT маркеров (как было на «Language server»).

#### P-05 Переключение обратно
1. Открыт `FindUsages.cs`, окно Find с результатами P-01.
2. Source of Features → «Kinds of usages» = `Language server`, OK. Файл **не** закрывать.
3. Повторить Alt+F7 на `Total`, `Lines`, `UsageTally`: теперь как в «tokens:» EXPECT — `(Total, …) = other` и `Lines = { … }`,
   `Inner = { … }` — «Read/Write access» по токенам, `out UsageTally` — «Write access», `UsageTally { … }` — «Read access».
4. Вернуть `Built-in` — снова как в P-01…P-03, тоже без переоткрытия.

#### P-06 Переключатель на странице настроек
1. Settings | .NET | Language Server, группа Source of Features.
2. Ожидается строка «Structure, folding and breadcrumbs» со значением `Built-in` (и «Kinds of usages» — `Built-in`).
3. Переключить на `Language server`, OK — открытые `.cs` перепарсены (Structure обновился), IDE не подвисает, в `idea.log` нет исключений. Вернуть `Built-in`.

#### P-07 Structure / folding / breadcrumbs на `Types.cs`
1. Built-in. Открыть `Console/Types.cs`, **Alt+7** (Structure), просмотреть дерево типов и членов.
2. Свернуть всё (**Ctrl+Shift+−**) и развернуть (**Ctrl+Shift+=**) — регионы на типах, методах, `using`, комментариях.
3. Походить курсором по 5–6 строкам — breadcrumbs внизу редактора показывают тип → член.
4. Переключить на `Language server` и повторить: должно совпадать (робот насчитал 21 регион и одинаковые breadcrumbs).

#### P-08 ▶ у тестов и `Main`
1. Built-in. Открыть `Tests/PricingTests.cs` — ▶ на полях у класса и у каждого из трёх тестов.
2. Открыть `NetFramework/LegacyConsole/Program.cs` (из папки площадки) — ▶ у `Main`.
3. Клик по ▶ у теста → Run — запускается только этот тест.

#### P-09 `TYPE:active-branch`
1. Built-in. Открыть `MultiTarget/ActiveBranch.cs`, **Alt+7**.
2. В тулбаре переключатель конфигурации: `Debug | .NET 10.0` → в Structure `Net10Only` (с `Describe`), `Net9Only` нет.
3. Выбрать `.NET 9.0` (или Default) → `Net9Only`, `Net10Only` нет. Файл не переоткрывать.
4. Переключить на `Language server` → файл сразу перепарсен эвристикой (тоже без переоткрытия).

#### P-10 Исправленные случаи дерева (нет сценария)
1. В любом файле `Console` (временно, потом Ctrl+Z) написать: метод с двумя заголовками под `#if DEBUG … #else … #endif` и одним телом;
   класс после top-level `using (…) { }` (в `Program.cs`); поле `int a, b;`; `[assembly: System.Reflection.AssemblyMetadata("a","b")]` над
   namespace; `record R<T>(T X);`; член enum с атрибутом `[Obsolete] B`.
2. Alt+7: виден весь класс после `#if` (метод не «съел» остальное), `record` после `using`, оба поля `a` и `b`, атрибут сборки не внутри
   namespace, primary constructor у `R<T>`, атрибут у члена enum.

#### P-11 Потребители модели на Built-in
1. Built-in. Пройти F-07 (`TYPE:go-to-symbol`), F-08 (Go to Base на членах), E-33 (хотя бы `IL:il-simple`).
2. `Web/Program.cs`: значки маршрутов на полях у `MapGet`…, клик ведёт в окно Endpoints.
3. Ctrl+наведение на вызов метода — подчёркивание (см. F-13).
4. EF-значков в площадке нет (нет `DbContext`) — пропустить.

#### P-12 Ошибки своего парсера не показываются
1. Built-in. В `Console/Editor/CompletionRanking.cs` на пустой строке набрать недописанное: `if (x ` без скобки.
2. Красным подчёркивает только сервер (с кодом `CSxxxx` в подсказке); отдельных ошибок без кода от парсера плагина («… expected») быть не должно.
3. Ctrl+Z.

#### P-13 Замеры шага 0
1. Перезапустить IDE с открытой площадкой, засечь на глаз: когда раскрасились идентификаторы, когда сервер готов.
2. `Console/Editor/Measurements.cs`: `TYPE:measure-member` — набрать `text.` → список открывается сам, есть `Length`; `TYPE:measure-prefix` —
   `Consol` → есть `Console`. Ctrl+Z.
3. Сверить с таблицей «Исходные замеры» в `CSHARP_PSI_MIGRATION.md` (цвета ~5 с, сервер ~5 с, completion 60–70 мс) — сильно ли отличается ощущение.

---

## Редактор

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| E-41 | 0.1.48 | Без статических методов из индекса там, где нужен тип (`Task<str`) | `ImportCompletion.cs`, `TYPE:import-silent-type` | ⬜ |
| E-42 | 0.1.49 | Встроенный форматтер: метод, файл, `switch`, инициализаторы, опции `.editorconfig` | `Formatting.cs`, `TYPE:format-method` / `-file` / `-switch` / `-initializers` / `-options` | 🤖 метод, `switch`, инициализаторы, файл совпали с сервером; `-options` — вживую; `-initializers` с 0.1.68 — по-новому, см. E-85 |
| E-43 | 0.1.49 | Выбор форматтера: «Built-in» на лету, «dotnet format (on save)», CSharpier | `Formatting.cs`, `TYPE:format-choice`, `TYPE:format-csharpier` | 🤖 выбор не зависит от переключателя; «None» исправлен (форматировал) — после исправления не проверено роботом; Actions on Save — вживую |
| E-44 | 0.1.50 | Go to Declaration и Ctrl+наведение по встроенному дереву: локальные, параметры, лямбды, метки, запросы, члены, типы | `Navigation.cs`, `TYPE:nav-locals` / `-lambdas` / `-labels-queries` / `-members` / `-types` | 🤖 робот (WSL), 0.1.67: Ctrl+наведение даёт строку `(local variable) int total` |
| E-45 | 0.1.50 | Подсветка использований по встроенному дереву (чтение / запись); неразрешимое и Go to Super — у сервера | `Navigation.cs`, `TYPE:nav-highlight`, `TYPE:nav-types`; `GoToBase.cs` | 🤖 робот (WSL) |
| E-46 | 0.1.51 | Цвета идентификаторов Built-in: виды типов, static / const / event, локальные, изменяемые (подчёркнуты), параметры, члены partial-класса и базового класса из другого файла | `SemanticColors.cs` (+ `SemanticColorsPart.cs`), `TYPE:colors-declarations` / `-locals` / `-members` / `-shadowing` | 🤖 робот (WSL), 0.1.67: исправлено — у папки без модуля файлы не индексировались (см. ниже) |
| E-47 | 0.1.51 | Цвета сервера в новой палитре и переключение «Colors of identifiers» на лету; страница Color Scheme \| C# | `SemanticColors.cs`, те же маркеры; Settings \| Editor \| Color Scheme \| C# | 🤖 робот (WSL): цвета сервера, тёмная и светлая темы |
| E-48 | 0.1.53 | Rename (Shift+F6) по встроенному дереву: локальные, параметры, локальные функции, метки, запросы, лямбды, параметры типов, `@`; одно Ctrl+Z | `Rename.cs`, `TYPE:rename-local` / `-parameter` / `-local-function` / `-label-query-lambda` / `-type-parameter-keyword` | 🤖 робот (WSL): `rename-local` |
| E-49 | 0.1.53 | Rename: конфликты (диалог «Problems Detected»), члены и типы — серверу или подсказка, «Rename in place» выключен | `Rename.cs`, `TYPE:rename-conflict`, `TYPE:rename-primary-member` | 🤖 робот (WSL): `rename-conflict` |
| E-50 | 0.1.54 | Синтаксические ошибки Built-in: коды и места Roslyn, без двойных отметок, сразу при наборе | `Broken/SyntaxErrors.cs`, `TYPE:diag-semicolon` / `-paren` / `-expression` / `-brace` / `-edit` | 🤖 робот (WSL) |
| E-51 | 0.1.54 | Built-in: литералы, директивы, предупреждения, исключённый текст; сервер оставляет семантические и не пойманные деревом ошибки | `Broken/SyntaxErrors.cs`, `TYPE:diag-literals` / `-member` / `-misplaced` / `-directives` / `-end` / `-server-keeps` | 🤖 робот (WSL), 0.1.67: исключённый `#if` текст серый; CS0230 на `in` — как у Roslyn |
| E-52 | 0.1.55 | Completion Built-in: ключевые слова по месту, порядок (локальные > параметры > члены > типы > ключевые слова), ожидаемый тип, без двойных пунктов после загрузки сервера | `NativeCompletion.cs`, `TYPE:complete-keywords` / `-expected` / `-goto-query` | 🤖 робот (WSL), 0.1.67: после `1` списка нет, Enter → новая строка |
| E-53 | 0.1.55 | Completion Built-in: `override` (член целиком), `partial`, имена переменной после типа | `NativeCompletion.cs`, `TYPE:complete-override` / `-partial` / `-names` | 🤖 робот (WSL) |
| E-54 | 0.1.55 | `return ` в не-`async` методе с `Task`: серый `Task.FromResult();` / `Task.CompletedTask;`, первый пункт; `await` делает метод `async`, Alt+Enter «Make method async» | `CommonCalls.cs`, `TYPE:complete-task-from-result` / `-task-completed` / `-await-async` / `-make-async` | 🤖 робот (WSL), 0.1.67: «Make method async» плагина в Alt+Enter, применяется |
| E-55 | 0.1.57 | Цвета Built-in у типов и членов сборок (`Console`, `WriteLine`, `List.Count`, `Math.PI`, extension-методы), namespace в `using`; файл на 1000 строк без задержки | `LibraryNames.cs`, `TYPE:library-colors`; `SemanticColors.cs`, `TYPE:colors-members` | 🤖 робот (WSL), 0.1.67: 69 диапазонов `CSHARP_*` в `LibraryNames.cs` |
| E-56 | 0.1.57 | Go to Declaration Built-in к члену значения известного типа (`Current.Total`); член сборки без сервера — никуда, не в неверное место | `LibraryNames.cs`, `TYPE:library-navigation` | 🤖 робот (WSL), с готовым сервером |
| E-57 | 0.1.58 | Go to Declaration Built-in к члену после выражения: вызов с выводом типа, `await`, индексатор, `?.`, `??`, `?:` | `ExpressionTypes.cs`, `TYPE:types-after-call` / `-await` / `-operators` | 🤖 робот (WSL) |
| E-58 | 0.1.58 | Go to Declaration Built-in к члену кортежа, деконструкции, `foreach` по словарю, переменной запроса LINQ; цвета этих членов | `ExpressionTypes.cs`, `TYPE:types-tuples` / `-deconstruction` / `-query` | 🤖 робот (WSL): навигация; цвета — см. E-46 |
| E-60 | 0.1.59 | Сборки solution в Project view → External Libraries: reference packs, пакеты с версиями, dll внутри; не в project scope | `debug-playground/README.md`, «External Libraries» | ⬜ |
| E-62 | 0.1.61 | Completion Built-in: `using var` / `await using var` (второй делает метод `async`), `using (` — локальные, `var`, `new` | `Usings.cs`, `TYPE:using-var` / `-await` | 🤖 робот (WSL): шаги 1–2; шаг 3 (Alt+Enter) — 0.1.67 |
| E-63 | 0.1.61 | Completion Built-in в директивах: namespace solution и сборок по уровню, типы после `using static` / alias, `global using` вверху файла | `Usings.cs`, `TYPE:using-directive` | 🤖 робот (WSL): частично |
| E-64 | 0.1.61 | Postfix `.using` / `.awaitusing`: имя по типу, выделено; `.awaitusing` делает метод `async` | `Usings.cs`, `TYPE:using-postfix` | 🤖 робот (WSL) |
| E-65 | 0.1.61 | Alt+Enter: Convert to 'using' declaration / statement, Wrap in 'using' statement — без дубля строки сервера | `Usings.cs`, `TYPE:using-to-declaration` / `-to-statement` / `-wrap` | 🤖 робот (WSL), 0.1.67: «Convert to 'using' statement», «Wrap in 'using' statement» в Alt+Enter |
| E-66 | 0.1.61 | Alt+Enter на директиве: Sort 'using' directives, Convert to 'global using' (`GlobalUsings.cs` проекта) | `Usings.cs`, `TYPE:using-sort` / `-global` | 🤖 робот (WSL), 0.1.67: «Sort 'using' directives», «Convert to 'global using'» в Alt+Enter |
| E-70 | 0.1.63 | Decompile... на сборке фреймворка: список типов, C# read-only из реализации (не reference assembly), баннер, XML-доки | `debug-playground/README.md`, «Декомпиляция» | ⬜ |
| E-71 | 0.1.63 | Decompile... на пакете и forwarded-тип (`String` через `System.Runtime`); время первого и следующих запросов | `debug-playground/README.md`, «Декомпиляция» | ⬜ |
| E-72 | 0.1.63 | Декомпилированная вкладка: Back после закрытия, вкладка после перезапуска IDE, кэш, правка запрещена | `debug-playground/README.md`, «Декомпиляция» | ⬜ |
| E-73 | 0.1.62 | Go to Class (Ctrl+N) по типам сборок только с «Include non-project items», `JsonSerializer (System.Text.Json, System.Text.Json 10.0)` один раз; Enter — metadata view | `debug-playground/README.md`, «Go to Class / Symbol по сборкам» | ⬜ |
| E-74 | 0.1.62 | Metadata view: заголовок сборки, сигнатуры без тел, `///`-доки, вложенные типы, цвета и folding, только чтение, вкладка `X.cs [Сборка 10.0]`, баннер; вкладка переживает перезапуск | там же | ⬜ |
| E-75 | 0.1.62 | Go to Symbol по членам сборок (перегрузки `WriteLine`) на строку члена; Ctrl+click Built-in без сервера по `Add` / `WriteLine` — в metadata view | там же; `LibraryNames.cs`, `TYPE:library-navigation` | ⬜ |
| E-76 | 0.1.64 | Alt+Enter Built-in: «Convert to '?:' expression» / «Convert '?:' to 'if' statement» — формы `return` и присваивания, ветка `throw`; одна строка на действие (без строки сервера) | `ContextActions.cs`, `TYPE:ctx-if-to-conditional` / `-conditional-to-if` | 🤖 робот (WSL, IC, 2026-10-05, 0.1.72): Sign / Clamp / Assign / Parity / Check — как EXPECT, Mixed и Print — нет; дубль сервера «Replace conditional expression with statements» скрыт |
| E-77 | 0.1.64 | Alt+Enter Built-in: «To expression body» / «To block body» — метод, `void`, свойство с `get`, аксессор; «Use explicit type» / «Use 'var'» по типам C2 | `ContextActions.cs`, `TYPE:ctx-expression-body` / `-block-body` / `-var` | 🤖 робот (WSL, IC, 2026-10-05): все места как EXPECT; встроенная предлагает больше сервера (Use explicit type на `var`, To block body) |
| E-78 | 0.1.64 | Alt+Enter Built-in: «Introduce variable» (имя правится шаблоном в обоих местах, выделение) и «Inline variable» (скобки по месту); где их нет | `ContextActions.cs`, `TYPE:ctx-introduce` / `-inline` | 🤖 робот (WSL, IC, 2026-10-05, 0.1.72): Introduce / Inline как EXPECT, inline и на `var`; шаблон имени не проверен |
| E-79 | 0.1.65 | CS1674 / CS8417 Built-in на `using` / `await using` без `IDisposable` / `IAsyncDisposable`, один раз; список `using (` без не-disposable | `Usings.cs`, `TYPE:using-cs1674` / `-list` | ⬜ |
| E-80 | 0.1.66 | Completion Built-in после точки у значения: члены типа из сборок и solution, унаследованные, аргументы-типы подставлены, LINQ без `this`, видимость | `MemberCompletion.cs`, `TYPE:dot-instance` / `-generic` | 🤖 робот (WSL, IC, 2026-10-05): списки как EXPECT, дублей имён нет |
| E-81 | 0.1.66 | Completion Built-in после типа и namespace: static-члены, вложенные типы, члены enum, namespace и типы; `this.` в наследнике библиотечного типа | `MemberCompletion.cs`, `TYPE:dot-static` / `-namespace` / `-this` | 🤖 робот (WSL, IC, 2026-10-05, 0.1.72): нет `Void`, `Finalize`, статиков System.Enum после enum |
| E-82 | 0.1.66 | Completion после точки с готовым сервером: один пункт на имя, ничего из списка сервера не потеряно, выбор метода — `()` / `();` | `MemberCompletion.cs`, `TYPE:dot-generic` | 🤖 робот (WSL): один пункт на имя; выбор метода `()` / `();` не проверен |
| E-83 | 0.1.66 | Quick documentation Built-in (Ctrl+Q и наведение): строка как в Rider, XML-доки из `///` и из доков сборок, одно окно без сервера | `MemberCompletion.cs`, `TYPE:quick-doc` | 🤖 робот (WSL, IC, 2026-10-05, 0.1.72): одна страница (hover сервера выключен при Built-in), `string? value`, Params / Exceptions / Returns |
| E-84 | 0.1.66 | Parameter Info Built-in (Ctrl+P): перегрузки строками, выбранная отмечена, параметр под кареткой выделен, конструкторы `new T(` | `MemberCompletion.cs`, `TYPE:parameter-info` | 🤖 робот (WSL, IC, 2026-10-05, 0.1.72): перегрузки Add, `new StringBuilder(16)` — отмечен `int capacity` |
| E-85 | 0.1.68 | Встроенный форматтер как Rider: многострочные инициализаторы и `[...]` — скобки на своих строках, элементы на отступ глубже, разбиение по строкам прежнее; однострочные `{ 1, 2 }` / `[1, 2]` | `Formatting.cs`, `TYPE:format-initializers` | ⬜ |
| E-86 | 0.1.68 | Встроенный форматтер как Rider: строки аргументов и параметров на отступ правее строки вызова (не под первым аргументом), одинокая `)` под ней, вложенные списки | `Formatting.cs`, `TYPE:format-arguments` | ⬜ |
| E-91 | 0.1.70 | Настройки плагина — узел .NET в корне Settings (Toolset and Build, NuGet, Coverage, Debugger, Language Server), под Tools его нет | Settings (Ctrl+Alt+S) | 🤖 робот (WSL) |
| E-92 | 0.1.70 | Тулбар: кнопка Build Solution перед Run widget вместо комбобокса Debug / TFM; меню стрелки — Build / Rebuild / Clean / Restore / Cancel Build, Configuration, Target Framework | тулбар, `MultiTarget` для TFM | 🤖 робот (WSL); статус «cancelled» после Cancel Build — не проверен |
| E-93 | 0.1.70 | Refactor This (Ctrl+Alt+Shift+T) в C#: Rename, Inline / Introduce Variable, рефакторинги сервера; недоступного нет | `RiderPopups.cs`, `TYPE:refactor-method` / `-inline` / `-introduce` | 🤖 робот (WSL), с сервером; Built-in Inline / Introduce Variable — не проверены |
| E-94 | 0.1.70 | Navigate To (Ctrl+Shift+G и меню Navigate): строки Rider (Declaration, Implementation, Base Symbols…), переходы | `RiderPopups.cs`, `TYPE:navigate-call` | 🤖 робот (WSL); пункт меню Navigate → Navigate To... — не проверен |
| E-95 | 0.1.70 | Generate (Alt+Insert) одним списком в порядке Rider; ПКМ редактора C# — строки и порядок Rider | `RiderPopups.cs`, `TYPE:generate-in-class`, `TYPE:editor-menu` | 🤖 робот (WSL) |
| E-96 | 0.1.71 | Цвета внутри строк: код в дырках `$"…{…}…"` цветами кода, скобки дырки, `,5` / `:N2` — элемент формата; raw `$$"""` | `StringColors.cs`, `TYPE:strings-holes` / `-format` / `-raw` | 🤖 робот (Windows, 2026-10-05): ключи лексера и аннотатора, набор — как в EXPECT; вид глазами — нет |
| E-97 | 0.1.71 | Escape-последовательности: два чередующихся цвета, неверная (`\q`), `""` verbatim, `{{` / `}}`, char; набор — цвета по ходу, кавычка перешагивается | `StringColors.cs`, `TYPE:strings-escapes` / `-invalid-escape` / `-brace-escapes` / `-typing` | 🤖 робот (Windows, 2026-10-05): ключи лексера и аннотатора, набор — как в EXPECT; вид глазами — нет |
| E-98 | 0.1.71 | Элементы формата `string.Format` / `Console.WriteLine` / `AppendFormat`; страница Color Scheme \| C# → String | `StringColors.cs`, `TYPE:strings-format-items` | 🤖 робот (Windows, 2026-10-05): ключи лексера и аннотатора, набор — как в EXPECT; вид глазами — нет |
| E-99 | 0.1.72 | Quick doc `<inheritdoc/>`: член интерфейса и базы, `cref` у inheritdoc, свои части главнее | `MemberCompletion.cs`, `TYPE:quick-doc-inherit` | 🤖 робот (WSL, IC, 2026-10-05) |
| E-100 | 0.1.72 | Quick doc: `cref` ссылками, клик — документация символа, F4 — исходник или metadata view | `MemberCompletion.cs`, `TYPE:quick-doc-cref` | 🤖 робот (WSL): ссылки видны; клик и F4 не проверены |
| E-101 | 0.1.72 | Quick doc на `var`: тип и аргументы-типы (`T is int`) | `MemberCompletion.cs`, `TYPE:quick-doc-var` | 🤖 робот (WSL, IC, 2026-10-05) |
| E-102 | 0.1.72 | Parameter Info: именованный аргумент подсвечивает свой параметр, перегрузки без него серые | `MemberCompletion.cs`, `TYPE:parameter-info-named` | 🤖 робот (WSL, IC, 2026-10-05) |
| E-103 | 0.1.73 | Find Usages (Alt+F7) / Show Usages (Ctrl+Alt+F7) Built-in по solution: свойство из двух проектов, группы Read / Write / nameof / documentation; без сервера | `SolutionUsages.cs` + `Lib/SolutionShapes.cs`, `TYPE:solution-find-usages` | 🤖 робот (Windows, IDEA Community, 2026-10-05): окно Usages — 7 мест, группы как у сервера (кроме его «Declaration»); `usages_compare.js` — 372 из 378 мест сервера на 384 объявлениях, лишних 0; Show Usages и вид глазами — нет |
| E-104 | 0.1.73 | Подсветка использований члена / типа под кареткой в файле (запись — цветом записи), Ctrl+Shift+F7 | `SolutionUsages.cs`, `TYPE:solution-highlight` | ⬜ |
| E-105 | 0.1.73 | Go to Implementation (Ctrl+Alt+B) и Go to Super (Ctrl+U) Built-in: список реализаций, переход к базовому члену в другом проекте | `SolutionUsages.cs`, `TYPE:solution-goto-implementation`, `TYPE:solution-goto-super` | 🤖 робот: Ctrl+U — переход в `SolutionShapes.cs:20`; `usages_compare.js` implementation — 396 из 396; попап списка глазами — нет |
| E-106 | 0.1.73 | Type Hierarchy (Ctrl+H: цепочка баз сверху, Supertypes / Subtypes) и Call Hierarchy (Ctrl+Alt+H: вызывающие, вызываемые) Built-in | `SolutionUsages.cs`, `TYPE:solution-hierarchy` | 🤖 робот: Call Hierarchy `Measure` → `Run`; Type Hierarchy снят до переделки вида (базы сверху), после — только тест |
| E-107 | 0.1.73 | Иконки gutter: Overrides / Implements member, Is overridden, Has implementations, Has subclasses; клик — список | `SolutionUsages.cs` + `Lib/SolutionShapes.cs`, `TYPE:solution-goto-super` | 🤖 робот: маркеры на всех строках из EXPECT; клик и вид иконок — нет |
| E-108 | 0.1.73 | Rename (Shift+F6) членов и типов по solution: inplace, затем все файлы; вопрос про иерархию (Rename All / Only This); конструктор с типом; файл типа; конфликт; одно Ctrl+Z (платформа спрашивает «Undo … affects other files») | `SolutionUsages.cs`, `TYPE:solution-rename` | 🤖 робот (`rename_solution.js`, имя из контекста, без inplace и диалогов): `Side` → `Edge` (7 мест, 2 проекта), `Area` с иерархией → `Surface` (4 строки), `SolutionTile` → `SolutionPlate`; inplace, диалог иерархии, Undo — нет |
| E-109 | 0.1.74 | Семантические ошибки «Built-in»: имена и типы — CS0103, CS0246, CS0234 с текстами Roslyn, без сборки и сервера | `Broken/SemanticErrors.cs`, `TYPE:sem-names` / `-namespace` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): коды, тексты и места как в EXPECT, на ImportType.cs совпали с сервером один в один; глазами — нет |
| E-110 | 0.1.74 | Члены: CS1061, CS0117; молчание на `ToString` интерфейса, `Deconstruct` записи, extension-методах, `dynamic`, кортежах | `SemanticErrors.cs`, `TYPE:sem-members` / `-members-silent`; `ImportType.cs`, `TYPE:import-type-member` / `-silent` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): как в EXPECT, `-silent` без красного |
| E-111 | 0.1.74 | Аргументы и типы: CS1501, CS7036, CS0029, CS0266, CS0161; молчание на `byte b = 1`, `while (true)` | `SemanticErrors.cs`, `TYPE:sem-arguments` / `-conversions` / `-conversions-silent` / `-paths` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): как в EXPECT |
| E-112 | 0.1.74 | «Import type»: синяя подсказка `N.Type? Alt+Enter`, Alt+Enter добавляет `using`; список при нескольких namespace | `ImportType.cs`, `TYPE:import-type-hint` / `-declaration` / `-choice` / `-extension`; `SemanticErrors.cs`, `TYPE:sem-typing` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): «Import 'System.Text.StringBuilder'» добавил `using`, список «Import 'Canvas' From» из двух namespace, выбор добавил `using`; синяя подсказка не проверена (песочница без фокуса) |
| E-113 | 0.1.74 | Серые `using` (CS8019, CS8933) и «Remove unused directives in file» | `SemanticErrors.cs`, `TYPE:sem-unused`; `ImportType.cs`, `TYPE:import-type-unused` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): серые CS8019 / CS8933, «Remove unused directives in file» убрал обе |
| E-114 | 0.1.74 | Без дублей: ошибка, которую показывает плагин, не повторяют сервер и последняя сборка; «Errors and warnings» = Language server — ошибки сервера как раньше | `SemanticErrors.cs` после Build Solution, с готовым сервером, в обоих режимах | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): с готовым сервером каждая ошибка одна; Language server — те же 12 ошибок сервера; дубли с Build — не проверены |
| E-115 | 0.1.75 | Generate (Alt+Insert) без сервера: строки генераторов как в Rider, недоступные серые, нет дублей сервера и «Override / Implement Methods…» платформы | `Generate.cs`, `TYPE:gen-list` | 🤖 робот (Windows, 2026-10-05): состав и серые строки по снимку попапа; с готовым сервером — нет |
| E-116 | 0.1.75 | Constructor (с базовым), Read-only properties / Properties: диалог выбора членов по группам, члены на строке каретки | `Generate.cs`, `TYPE:gen-constructor` / `-base-constructor` / `-properties` | 🤖 робот (Windows, Community 2026.1.4, 2026-10-05): вызов из кода строки, OK диалога через API; клик мышью и вид — нет |
| E-117 | 0.1.75 | Equality members (флажки `IEquatable<T>` и операторов), Formatting members, Deconstructor, Dispose pattern, Partial members | `Generate.cs`, `TYPE:gen-equality` / `-formatting` / `-deconstructor` / `-dispose` / `-partial` | 🤖 робот (Windows, Community 2026.1.4, 2026-10-05): вызов из кода строки, OK диалога через API; клик мышью и вид — нет |
| E-118 | 0.1.75 | Missing members (абстрактная база, интерфейсы из сборок) и Overriding members; Ctrl+I / Ctrl+O открывают те же диалоги | `Generate.cs`, `TYPE:gen-missing-abstract` / `-missing-library` / `gen-override` | 🤖 робот (Windows, Community 2026.1.4, 2026-10-05): вызов из кода строки, OK диалога через API; клик мышью и вид — нет; Ctrl+I / Ctrl+O — нет |
| E-119 | 0.1.75 | Extract Method без сервера: параметры, возврат, `out`, выражение, `async`, `static`; имя в рамке; отказ подсказкой | `ExtractMethod.cs`, `TYPE:extract-*` | 🤖 робот (Windows, 2026-10-05): действие ExtractMethod с выделением, шаблон имени завершён; набор имени — нет |
| E-120 | 0.1.75 | Introduce Field без сервера: инициализатор поля или присваивание в члене; строки Refactor This | `ExtractMethod.cs`, `TYPE:introduce-field` | 🤖 робот (Windows, 2026-10-05): оба варианта, строки Refactor This по снимку |
| E-121 | 0.1.76 | Сервер выключен по умолчанию: новая установка (или без сохранённой галочки) — нет процесса `roslyn-language-server`, нет баннера «нет сервера», нет вопроса о .NET 10; completion, Ctrl+B, Alt+F7, Shift+F6, ошибки, Ctrl+Q, Alt+Enter, Alt+Insert работают; галочка Language Server включает сервер | любые сценарии `Console/Editor/*.cs` | ⬜ |
| E-130 | 0.1.77 | Source generators без сервера: Dependencies → .NET 9.0 → Analyzers → генератор → тип → файлы, открываются только для чтения с баннером | `Generators.cs`, `TYPE:sg-tree` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): дерево по снимку (три генератора, 6 файлов JSON); баннер и замок вкладки — нет |
| E-131 | 0.1.77 | Completion и Ctrl+click по сгенерированному: члены `PlaygroundJsonContext.Default.` | `Generators.cs`, `TYPE:sg-member` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): `GeneratedOrder` 2-й, `String` 5-й в списке; Ctrl+click — нет |
| E-132 | 0.1.77 | Ошибки на partial-типе с генератором после сохранения (CS1061), молчание до первого прогона и при несохранённой правке; `[JsonSerializable]` добавил — новый член после сохранения | `Generators.cs`, `TYPE:sg-error` / `sg-new` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): CS1061 на `Customer` после сохранения; `sg-new` — нет |
| E-133 | 0.1.77 | Анализаторы на сохранении: VSTHRD200, VSTHRD103, CA1822 жёлтым, по одному, id в начале, подсказка «Roslyn analyzer»; без дублей с последней сборкой | `Analyzers.cs`, `TYPE:an-on-save` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): три предупреждения по снимку, после сохранения через ~0,1 с; дубли со сборкой и подсказка по наведению — нет |
| E-134 | 0.1.77 | Code fixes анализаторов по Alt+Enter: «Await ReadAllTextAsync instead», «Rename to LoadAsync», «Make static»; одна команда Undo | `Analyzers.cs`, `TYPE:an-fix` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): «Await ReadAllTextAsync instead» в списке и применился, Undo с его именем откатил; Rename / Make static — нет |
| E-135 | 0.1.77 | Правила IDE из SDK по `.editorconfig` (IDE0059) и его fix; Run Code Analysis на проекте — окно Build «Code Analysis Console» | `Analyzers.cs`, `TYPE:an-ide` / `an-project` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): окно Build «0 errors, 3 warnings, 151 suggestions» по снимку; IDE0059 — нет |
| E-136 | 0.1.77 | Settings \| .NET \| Analyzers and Generators: флажки действуют (без генераторов — снова молчание C4c; без подсказок — нет слабых предупреждений), помощник выходит после простоя | страница настроек, `Generators.cs`, `Analyzers.cs` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): страница по снимку (подписи в песочнице срезаны на букву, как у платформенной Coverage); флажки и простой — нет |
| E-140 | 0.1.78 | Перегрузки: Ctrl+B / Ctrl+Q на вызове ведёт к перегрузке из EXPECT (числа, ссылки, `params`, необязательные, generic, лямбды, группа методов), без списка кандидатов | `Overloads.cs`, `TYPE:overloads-*` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): Ctrl+B (`goto_declaration.js`) на 17 вызовах — перегрузка из EXPECT, без списка; Ctrl+Q и наведение — нет |
| E-141 | 0.1.78 | Недостижимый код CS0162: серым до конца блока, подсказка с кодом; `#pragma warning disable` глушит | `Broken/SemanticErrors2.cs`, `TYPE:sem2-unreachable`; `Web/Program.cs` (`/fail`) — ничего | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): CS0162 в местах оракула, серым (снимок редактора); `/fail` Web и подсказка — нет |
| E-142 | 0.1.78 | Неиспользуемые локальные CS0168 / CS0219 серым, Alt+Enter «Remove unused variable» | `SemanticErrors2.cs`, `TYPE:sem2-unused` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): CS0168 / CS0219 серым, «Remove unused variable» убрал строку; Ctrl+Z — нет |
| E-143 | 0.1.78 | CS4014 жёлтым, Alt+Enter «Add 'await'»; CS0120 красным | `SemanticErrors2.cs`, `TYPE:sem2-await`, `TYPE:sem2-static` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): CS4014 на обоих вызовах, «Add 'await'» только на своём; CS0120 красным |
| E-144 | 0.1.78 | Nullable CS8600 / CS8625 / CS8603 / CS8618 жёлтым при `#nullable enable` / `<Nullable>enable`; без контекста — ничего | `SemanticErrors2.cs`, `TYPE:sem2-nullable`, `TYPE:sem2-uninitialized` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): CS8600 / CS8625 / CS8603 / CS8618 в местах оракула; без контекста — только тест |
| E-145 | 0.1.78 | CS1503 / CS7036 generic / `params` / именованных; CS0029 / CS0266 `?:` и switch-выражений | `SemanticErrors2.cs`, `TYPE:sem2-arguments`, `TYPE:sem2-target-typed` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): коды, тексты и места как у Roslyn (23 метки файла — список оракула) |
| E-146 | 0.1.78 | «Add argument name»: `Resize(width: 640, height: 480)` | `Overloads.cs`, `TYPE:overloads-argument-name` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): `Resize(width: 640, height: 480)` |
| E-180 | 0.1.82 | Тихие анализаторы: в редакторе только warning / error (по severity компилятора: `.editorconfig`, ruleset, AnalysisLevel); Info — без волн, но их fixes на Alt+Enter; «Show suggestions» возвращает слабые | `Analyzers.cs`, `TYPE:an-quiet` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): в `Overloads.cs` / `ContextActions.cs` ни одной метки анализаторов (были 18 и 19), в `Analyzers.cs` — 3 предупреждения; «Make static» на Info — только тестом |
| E-181 | 0.1.82 | Run Code Analysis: узлы «Warnings (n)» открыт, «Suggestions (n)» свёрнут, итог «0 errors, 3 warnings, N suggestions» | `Analyzers.cs`, `TYPE:an-project` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): окно Build «0 errors, 3 warnings, 171 suggestions», «Warnings (3)» раскрыт, «Suggestions (171)» свёрнут (снимок); двойной клик — нет |
| E-182 | 0.1.82 | Find Usages неявных вызовов: `Deconstruct` (деконструкция, `foreach (var (a, b) …)`), `Add` (инициализатор коллекции), `GetEnumerator` (`foreach`), `Dispose` (`using`), `GetAwaiter` (`await`); Rename их не трогает | `ImplicitUsages.cs`, `TYPE:implicit-*` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): `usages_compare.js`: `Deconstruct` 3/3, `Add` 3/3, `Dispose` 1/1, `GetAwaiter` 1/1 как у сервера; `GetEnumerator` 2 из 6 — остальные 4 сервер берёт каскадом к `IEnumerable<T>`; Rename — только тестом |
| E-183 | 0.1.82 | Find Usages типа: target-typed `new(…)` в `["a"] = new()`, коллекционных выражениях, `return`, аргументах, инициализаторах свойств — 14 мест, как у сервера | `ImplicitUsages.cs`, `TYPE:implicit-new` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): `ImplicitPoint` — 14 мест, у сервера те же 14 |
| E-184 | 0.1.82 | gRPC без сервера: типы из `obj/…/Protos/*.cs` (design-time сборка помощника) резолвятся, есть ошибки CS1061; узел «Generated by build» → Protobuf; баннер; правка `.proto` — «out of date» до новой генерации | `Grpc/Greeter.cs`, `TYPE:grpc-resolve` / `grpc-errors`; `Grpc/Protos/greet.proto`, `TYPE:grpc-stale` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): без `obj/Debug` помощник за ~3 с сделал Greet.cs / GreetGrpc.cs; CS1061 на `Nmae`, `Name` без ошибки; Ctrl+B на `HelloRequest` — Greet.cs:43 с баннером (снимок); узел дерева — через API (`GeneratorNode.files`), глазами — нет; `grpc-stale` — нет |
| E-185 | 0.1.82 | WPF старого формата после сборки MSBuild'ом VS: `obj/Debug/*.g.cs` известны, ошибки в `MainWindow.xaml.cs` видны; до сборки — молчание; правка XAML — баннер «Out of date» | `NetFramework/LegacyWpf/MainWindow.xaml.cs`, `TYPE:wpf-generated` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): после сборки MSBuild VS: CS1061 на `Txet`, CS0103 на `Missing`, `Title` без ошибки (снимок); правка XAML — stale, `Missing` замолчал, баннер «Out of date»; до первой сборки — только тестом |
| E-01 | 0.1.44 | Ключевые слова выше неимпортированных типов | `CompletionRanking.cs`, `TYPE:keyword-order` | ⬜ |
| E-02 | 0.1.44 | `string` первым в TYPE шаблона `prop` | `TYPE:prop-type` | ⬜ |
| E-03 | 0.1.44 | Имя свойства не открывает список имён | `TYPE:property-name-ghost` | ⬜ |
| E-04 | 2026-09-29 | Ожидаемый тип: серый `count;` и порядок | `TYPE:expected-type` | 🤖 частично |
| E-05 | 2026-09-29 | Метод по типу целым вызовом | `TYPE:method-by-type` | ⬜ |
| E-06 | 2026-09-29 | Аргументы по именам параметров | `TYPE:parameter-name` | ⬜ |
| E-07 | 2026-09-29 | Аргумент по типу параметра | `TYPE:parameter-type` | 🤖 |
| E-08 | 2026-09-29 | Частичное совпадение имени | `TYPE:partial-name` | ⬜ |
| E-09 | 2026-09-29 | Присваивание, `return`, молчание | `TYPE:assignment` / `return` / `value-silent` | ⬜ |
| E-10 | 2026-09-29 | После точки; объявленное рядом | `TYPE:after-dot` / `declared-nearby` | ⬜ |
| E-11 | 2026-09-29 | Выбранное раньше — выше | `TYPE:chosen-before` | ⬜ |
| E-12 | 2026-09-29 | Suggestion Statistics | `TYPE:stats-ghost` / `stats-list` | ⬜ |
| E-13 | 2026-09-29 | Список в скобках вызова без задержки | `CompletionRanking.cs` | ⬜ |
| E-14 | 2026-09-29 | Лямбда там, где ждут делегат | `LambdaSuggestions.cs`, `TYPE:lambda-*` | 🤖 |
| E-15 | 2026-09-29 | Индексатор сборок собрался | `idea.log`, кэш IDE | ⬜ |
| E-16 | 2026-09-29 | Неимпортированное: вставка вызова, `using`, свойство, generic | `ImportCompletion.cs`, `TYPE:import-*` | ⬜ |
| E-17 | 2026-09-29 | Где неимпортированное молчит; пакет своего проекта | `TYPE:import-silent-*`, `import-package`, `import-stats` | ⬜ |
| E-18 | 2026-09-29 | Две IDE с пустым кэшем — индексатор один раз | README «то, что не импортировано» | ⬜ |
| E-19 | 2026-09-29 | Completion без учёта регистра | нет маркера | ⬜ |
| E-20 | 2026-09-29 | `;` после void-метода из completion | нет маркера | ⬜ |
| E-21 | 2026-09-29 | `<>` у generic из completion | нет сценария | ⬜ |
| E-22 | 2026-09-29 | Парная `>` для `<` | нет сценария | ⬜ |
| E-23 | 2026-09-29 | Серый текст, партия 1 | частично `TYPE:stats-ghost` | ⬜ |
| E-24 | 2026-09-29 | Серый текст, партия 2 | нет сценария | ⬜ |
| E-25 | 0.1.17 | Серый `break;` в `case` | нет сценария | ⬜ |
| E-26 | 0.1.16 | Серый `;` и `=> throw new NotImplementedException();` | нет сценария | ⬜ |
| E-27 | 0.1.15 | Complete Statement | нет сценария | ⬜ |
| E-28 | 0.1.10–0.1.12 | Ctrl+W, Enter в комментарии, подсветка вхождений | нет сценария | ⬜ |
| E-29 | 2026-09-29 | `.editorconfig` для C# | нет сценария | ⬜ |
| E-30 | 2026-09-29 | Баннеры над `.cs` | нет сценария | ⬜ |
| E-31 | 2026-09-29 | Intentions файла, Alt+Insert → Generate | нет сценария | ⬜ |
| E-32 | 2026-09-29 | Move `.cs` → namespace сервером | нет сценария | ⬜ |
| E-33 | 0.1.32 / 0.1.39 | IL Viewer: методы, async, итератор, лямбда | `IlViewer.cs`, `IL:*` | ⬜ |
| E-34 | 0.1.32 / 0.1.39 | IL Viewer: поле, заголовок типа, несвежая сборка, состояния | `IlViewer.cs`, `IL:*` | ⬜ |
| E-35 | 0.1.33 | Схема `appsettings.json` из кода | `AppSettingsSchema.cs`, `TYPE:appsettings-*` | ⬜ |
| E-36 | 0.1.33 | Схемы `ConfigurationSchema.json` пакетов | нет сценария | ⬜ |
| E-37 | 2026-09-29 | Аллокации: переключатель и уведомления | `Console/Allocations.cs` | ⬜ |
| E-38 | 2026-09-29 | Аллокации: числа, полоса, обновление | `ALLOC:*` | ⬜ |
| E-39 | 2026-09-29 | Аллокации под Debug, в `Web`, цена | `Allocations.cs`, `Web` | ⬜ |
| E-40 | 0.1.6 | Run C# File with dotnet | нет сценария | ⬜ |

Для E-01…E-13: открыть `Console/Editor/CompletionRanking.cs`, дождаться «Roslyn: DebugPlayground.sln», после каждого маркера — Ctrl+Z.

#### E-41 `TYPE:import-silent-type`
1. `Console/Editor/ImportCompletion.cs`, пустая строка под маркером: набрать `List<Str` → в списке типы (`String`, `StringBuilder`, `Stream`…), **нет** `Conversion.Str` и других методов. Ctrl+Z.
2. Так же `var t = typeof(Str`, `var s = item as Str`, и в заголовке класса `: IDisposable, Str` — только типы.
3. Контроль: `var x = count < Rang` — методы из индекса (`Enumerable.Range`) по-прежнему предлагаются.

#### E-42 `TYPE:format-method`, `-file`, `-switch`, `-initializers`, `-options`
1. Settings | .NET | Toolset and Build → «Formatter» = **Built-in**. OK.
2. `Console/Editor/Formatting.cs`: по каждому маркеру выполнить то, что написано в его комментарии (выделить, Ctrl+Alt+L), сравнить с `EXPECT` — пробелы, отступы, `{` на своей строке, `case` внутри `switch`; литералы и комментарии не тронуты.
3. После каждой проверки Ctrl+Z — файл вернулся, как был.
4. `TYPE:format-options`: положить рядом `.editorconfig` из комментария маркера, Ctrl+Alt+L → `{` остаётся на строке заголовка. Удалить `.editorconfig`.

#### E-43 `TYPE:format-choice`
1. Toolset and Build → «Formatter»: в списке Auto, **Built-in**, **dotnet format (on save)**, CSharpier, None; под списком описание — Built-in работает на лету, dotnet format — только файлы целиком, по Reformat Code и при сохранении.
2. **Built-in**: выделить пару строк метода с испорченными пробелами, Ctrl+Alt+L — исправлены только они, сразу. Code | Auto-Indent Lines (Ctrl+Alt+I) тоже работает.
3. **dotnet format (on save)**: тот же Ctrl+Alt+L форматирует весь файл (с задержкой около секунды без сервера языка). Settings | Tools | Actions on Save → «Reformat code» включить, испортить пробелы, Ctrl+S → файл отформатирован при сохранении.
4. **Auto** без CSharpier: Language Server → «Formatting» = Built-in — ведёт себя как п. 2; = Language server — как п. 3.
5. Вернуть «Auto». Ctrl+Z правок.

#### E-44 `TYPE:nav-locals`, `-lambdas`, `-labels-queries`, `-members`, `-types`
Робот 2026-10-05 (WSL, **0.1.67**): Ctrl+наведение на `total` в `return` строки `Locals` — подсказка `(local variable) int total` (`e44_ctrlhover_total.png`); раньше у Built-in-целей не было `lang.documentationProvider` C#.
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): Ctrl+click Built-in по всем пяти маркерам туда же, что ожидается: локальные (`total`, `parsed`, `first`, `value`, `input`, `text`, `item`, `index`), параметры лямбд (`x`, `p`, `n`, `seed`, `Twice`), метки и запросы (`retry`, `o`, `doubled`, `g`, `T`), члены (`_count`, `Total`, `this._count`, `Reset` во второй части partial, `Entry`), типы (`UsageSample` → FindUsages.cs:13, `UsageLog` → UsageLog.cs:7, `Record` → UsageLog.cs:11, `Count` → List.cs:79). Ctrl+наведение подчёркивает имя и даёт руку, на объявлении — нет. **Не так:** у целей Built-in (`total`, `parsed`) при Ctrl+наведении нет всплывающей подсказки, у целей сервера есть (`Length`); обычное наведение даёт `(local variable) int total` (`e44_ctrlhover_total.png`, `e44_ctrlhover_length.png`). `Add(1)` сразу в `Add(int)` без списка перегрузок — так и должно быть после выбора перегрузки 0.1.60, EXPECT шага и маркера устарел.
Робот 2026-10-05 (0.1.60, `goto_batch.js`): 90 мест `Navigation.cs` и `LibraryNames.cs` — Built-in и сервер в одних и тех же местах
(перегрузка `Add(1)`, члены сборок через сервер); `new UsageSample()` теперь к конструктору, как у сервера. Built-in — по умолчанию.
1. Settings | .NET | Language Server → Source of Features → «Navigation and usages» = **Built-in**. OK.
2. Открыть `Console/Editor/Navigation.cs`. Можно не ждать сервера: переходы этих маркеров идут без него.
3. `TYPE:nav-locals`: в строке `return total + parsed + …` метода `Locals` по очереди Ctrl+click на `total`, `parsed`, `first`, `value`,
   `input` — каретка сразу на имени в объявлении (`var total`, `out var parsed`, `var (first, …)`, параметры метода), без списка. Ctrl+click
   на `text` в `text.Length`, на `item` в `total += item`, на `index` в `total += index` — на `is string text`, `foreach (var item`, `for (int index`.
4. Зажать Ctrl и навести мышь на `total` в той же строке — имя подчёркнуто, всплывает подсказка с объявлением. Навести на `total` в
   `var total = 0;` — подчёркивания нет (это само объявление).
5. `TYPE:nav-lambdas`: Ctrl+click на `x` в `x => x * 2` (второй `x`) — на параметр лямбды, **не** на `var x = 10`; `p` в `p + q` — на `int p`;
   `Twice` в `Twice(3)` — на локальную функцию внизу метода; `n` в `n * 2` — на `int n`; `seed` — на `Navigation(int seed)` в заголовке класса.
6. `TYPE:nav-labels-queries`: Ctrl+click на `retry` в `goto retry;` — на метку `retry:`; в запросе: `o` в `o * 2` — на `from o`, `doubled`
   в `where doubled > 2` — на `let doubled`, `g` в `select g.Key` — на `into g`; `T` в `new List<T>()` — на `T` в `Pick<T>`.
7. `TYPE:nav-members`: Ctrl+click на `_count` и на `_count` после `this.` — на поле `private int _count;`; `Total` — на свойство;
   `Add` в `Add(1)` — всплывает список из двух строк (`Add(int amount)`, `Add(string amount)`), выбор ведёт к перегрузке;
   `Reset` — на `Reset()` во второй части `public partial class Navigation` в конце файла; `Entry` — на `private sealed class Entry`.
8. `TYPE:nav-types`: Ctrl+click на `UsageSample` — открывается `FindUsages.cs` на `class UsageSample`; на `UsageLog` — `Lib/UsageLog.cs`.
9. Повторить шаги 3–8 при «Navigation and usages» = **Language server** с готовым сервером — те же цели. Вернуть по умолчанию.

#### E-45 `TYPE:nav-highlight`, fallback к серверу
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): на `total` — цвет записи на объявлении, `+=`, `++`, `out total`, цвет чтения на `return`; на `step` — объявление и одно чтение; на `_count` — все вхождения в файле; Ctrl+U на `Area` → `MiddleShape` (GoToBase.cs:34). Снимки `e45_*.png`.
Робот 2026-10-05 (0.1.60, `goto_batch.js`): неразрешимое деревом (`Count` сборки, `Any`) уходит серверу и там же остаётся, как у
сервера. Вживую — рамки подсветки чтения / записи.
1. «Navigation and usages» = **Built-in**, `Console/Editor/Navigation.cs`.
2. `TYPE:nav-highlight`: каретку (без клика с Ctrl) на `total` в `total += step;` метода `Highlight`. Подсвечены все `total` метода:
   `var total`, `total +=`, `total++`, `out total` — цветом записи; `return total` — цветом чтения. `total` в методе `Locals` **не** подсвечены.
3. Каретку на `step` — подсвечены параметр `int step` и `step` в `+= step`, больше ничего.
4. Каретку на `_count` — подсветка как раньше: при готовом сервере — его ответ, без сервера — все `_count` файла по тексту.
5. Дождаться «Roslyn: DebugPlayground.sln». `TYPE:nav-types`: Ctrl+click на `Record` в `UsageLog.Record(…)` — переход в `Lib/UsageLog.cs`
   (это ответ сервера); на `Count` в `items.Count` — декомпилированный `List<T>`. Без сервера (Restart C# Language Server и сразу кликнуть) —
   ничего не происходит, неверного перехода нет.
6. `Console/Editor/GoToBase.cs`, `TYPE:go-to-base-member`: Ctrl+U на `Area` — переход в `MiddleShape`, как при «Language server».
7. Вернуть «Navigation and usages» по умолчанию (Language server).

#### E-46 `TYPE:colors-declarations`, `-locals`, `-members`, `-shadowing`
Робот 2026-10-05 (WSL, **0.1.67**, снимки — `C:/tmp/wsl-fix/`): исправлено. Причина ❌ в 0.1.63 — IDEA 2026.1 открывает папку без системы сборки проектом без модулей, её файлы не индексируются (`CONTENT_NON_INDEXABLE`), и платформа пропускает в них всё, что не `DumbAware` (аннотаторы цветов, intention-действия плагина). Теперь плагин сам заводит модуль на папку с solution (`DotNetModuleSetup`), а аннотатор цветов и Alt+Enter-действия помечены `DumbAware`. `SemanticColors.cs`: 60 диапазонов `CSHARP_*` в строках 14–60.
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): **❌ цвета Built-in не видны.** `highlight_keys.js` — 0 диапазонов `CSHARP_*` в `SemanticColors.cs`, `Navigation.cs`, `LibraryNames.cs`, а `NativeCSharpSemanticColors.colors(file)` возвращает 95 записей за 20 мс, и аннотатор через `AnnotationHolderImpl` даёт 95 аннотаций. Не причина: dumb mode, incomplete-dependencies, essential highlighting, power save, уровень подсветки, перезапуск демона, переключение NATIVE→ROSLYN→NATIVE, правка файла. С ROSLYN цвета сервера появляются сразу. Аннотатор диагностики (DumbAware) работает, `NativeCSharpSemanticColorsAnnotator` (не DumbAware, на элементе `CSharpFile`) — нет; в журнале ошибок нет. На Windows-роботе 0.1.60 цвета были — вероятна регрессия 0.1.61–0.1.63 или пропуск аннотатора демоном. Снимки `e46_dark_1.png`, `e46_dark_decl.png`.
Робот 2026-10-05 (0.1.60, `highlight_keys.js` на 4 файлах): всё, что красит сервер, Built-in красит тем же цветом или точнее
(объявление метода, изменяемая локальная, локальная функция, параметр primary-конструктора). Built-in — по умолчанию.
1. Settings | .NET | Language Server → Source of Features → «Colors of identifiers» = **Built-in**. Apply — цвета в открытом редакторе меняются сразу, без правки файла.
2. Дождаться конца индексации (во время неё цвета прежние, эвристические). Открыть `Console/Editor/SemanticColors.cs`.
3. По каждому маркеру сверить имена под ним с `EXPECT` комментария (цвета Darcula / Islands Dark): классы фиолетовые, enum / record struct / delegate — светлее, интерфейс — как класс; поля и свойства бирюзовые, константы и члены enum — жирные; событие `Changed` — розовое; методы — зелёные; локальные и параметры — цвета текста; `total` подчёркнут во всех местах.
4. `TYPE:colors-members`: `Tick`, `_ticks` (базовый класс в `SemanticColorsPart.cs`), `Reset`, `Count` (другая часть `partial class ColorRegistry`, через `using static`) раскрашены; `Console` и `WriteLine` — цвета текста (библиотечное знает только сервер).
5. `TYPE:colors-shadowing`: `count` лямбды и параметр `count` — цвета текста, `Count` — бирюзовое поле, локальная `title` — не цвет свойства.
6. Поменять что-нибудь в `SemanticColorsPart.cs` (например, переименовать `Reset` в `Reset2`) — в `SemanticColors.cs` вызов `Reset()` теряет цвет после повторной подсветки; Ctrl+Z в обоих файлах.
7. Роботом: `feature_source.js` (`SEMANTIC_COLORS`, `NATIVE`), затем `highlight_keys.js` по строкам каждого маркера — ключи `CSHARP_*_IDENTIFIER`, источник `daemon`.

#### E-47 Цвета сервера и страница цветов
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): цвета сервера читаемы в тёмной (`e47_server_dark*.png`) и светлой (LAF «IntelliJ Light» + схема `_@user_Light`, `e47_server_light*.png`) темах: типы фиолетовые, методы зелёные, свойства бирюзовые, событие розовое. Отличия от EXPECT: сервер отдаёт `total` как `CSHARP_LOCAL_VARIABLE` без признака изменяемости — не подчёркнут; `MaxLines` серый (не используется), жирность не видна. Смена LAF не меняет схему редактора (её пришлось ставить отдельно); платформа пишет WARN «Theme IntelliJ Light refers to unknown color scheme IntelliJ Light». Переключение на Built-in — пусто, см. E-46.
Робот 2026-10-05 (0.1.60): после переключения на Built-in цвета сервера больше не остаются под встроенными (раньше у каждого
имени было два ключа до правки файла). Вживую — светлая тема и страница Color Scheme.
1. «Colors of identifiers» = **Language server**, дождаться виджета «Roslyn: DebugPlayground.sln». Те же маркеры `SemanticColors.cs`: цвета те же, что в E-46, плюс сервер красит `Console` (статический класс) и `WriteLine` (статический метод) и подчёркивает `total`; методы и в объявлениях — цвета вызова (сервер их не различает).
2. Переключить обратно Built-in → Apply: раскраска меняется сразу (в Built-in `Console` / `WriteLine` теряют цвет).
3. Settings | Editor | Color Scheme | C#: группы Types / Methods / Properties and variables с именами Rider; в превью раскрашены все примеры; поменять цвет «Types//Struct» — в редакторе меняются `ColorPoint`, `ColorSize`. Reset.
4. Светлая тема (Light / IntelliJ Light): цвета читаемы, константы жирные, `total` подчёркнут.

#### E-48 `TYPE:rename-local`, `-parameter`, `-local-function`, `-label-query-lambda`, `-type-parameter-keyword`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): `rename-local`: Shift+F6 на `subtotal`, `sum` — рамка вокруг набираемого имени, все три вхождения следуют при наборе; после Enter `var sum = 0m;`, `sum += price;`, `return sum * …`; одно Ctrl+Z возвращает файл (`e48_rename_typed.png`, `e48_rename_done.png`). Остальные маркеры не прогонялись.
Робот 2026-10-05 (0.1.56, `rename_check.js`): все сценарии `Rename.cs` как у сервера, `<param name>` тоже; конфликт — диалог,
отмена ничего не меняет. Вживую — подсветка рамки при наборе и ощущение скорости.
1. Settings | .NET | Language Server → Source of Features → «Rename» = **Built-in**. OK. Дождаться конца индексации.
2. Открыть `Console/Editor/Rename.cs`. Сервера можно не ждать: эти символы переименовываются без него.
3. `TYPE:rename-local`: каретка на `subtotal` в `var subtotal = 0m;`, Shift+F6 — имя в рамке, остальные два `subtotal` метода тоже
   обведены. Набрать `sum` — все три меняются на лету. Enter: `var sum = 0m;`, `sum += price;`, `return sum * (1 - discount);`; в других
   методах ничего. Одно Ctrl+Z — снова `subtotal` везде. Затем `price` → `cost`: меняется только строка `foreach`. Ctrl+Z.
4. `TYPE:rename-parameter`: каретка на `discount` в `Total(List<decimal> prices, decimal discount)`, Shift+F6, `rate`, Enter: параметр,
   `(1 - rate)` и `<param name="rate">` в doc-комментарии над методом; `Limits(decimal discount)` — без изменений. Ctrl+Z.
5. `TYPE:rename-local-function`: каретка на `Scale` в `return Scale(2)`, Shift+F6, `Times`, Enter — оба вызова и `int Times(int factor)`
   внизу метода; `factor:` остаётся. Ctrl+Z. Каретка на `factor` в `int Scale(int factor)`, Shift+F6, `k`, Enter: `Scale(k: 3)`,
   `int Scale(int k) => k * 10;`. Ctrl+Z.
6. `TYPE:rename-label-query-lambda`: `again` в `goto again;` → `retry` (и метка `retry:`); `line` в `line => line.Trim()` → `text` (оба);
   `o` в `from o in numbers` → `item`: `where item > 1 group item by item % 2`, `into g select g.Key` — без изменений. Ctrl+Z после каждого.
7. `TYPE:rename-type-parameter-keyword`: `TValue` в `First<TValue>` → `TItem`: возвращаемый тип, `List<TItem>`, `<typeparam name="TItem">`.
   Ctrl+Z. `kind` → набрать `class`, Enter: `var @class = values.Count;` и `return @class > 0 ? …` — `@` дописан. Ctrl+Z.
8. Повторить 3–7 при «Rename» = **Language server** с готовым сервером («Roslyn: DebugPlayground.sln») — результат тот же
   (сервер тоже правит `<param>` и именованные аргументы). Вернуть Built-in для E-49.
9. Роботом: `feature_source.js` (`RENAME`, `NATIVE`), затем `inline_rename.js` (подстановки `robot_js`): `__AT__` — текст кода, с первой
   буквы которого начинается имя (`subtotal = 0m;`; каретка встанет на его второй символ; первое вхождение в файле — подобрать текст, которого
   нет в комментариях маркеров), `__NEW__` — новое имя, `__WAIT__` — 500. В отчёте `handler: NativeCSharpRenameHandler`, `template on: <старое имя>`.
   Шаблону нужен фокус окна песочницы — если `template` не появился, проверить руками.

#### E-55 `TYPE:library-colors`
Робот 2026-10-05 (WSL, **0.1.67**, снимки — `C:/tmp/wsl-fix/`): исправлено. Причина ❌ в 0.1.63 — IDEA 2026.1 открывает папку без системы сборки проектом без модулей, её файлы не индексируются (`CONTENT_NON_INDEXABLE`), и платформа пропускает в них всё, что не `DumbAware` (аннотаторы цветов, intention-действия плагина). Теперь плагин сам заводит модуль на папку с solution (`DotNetModuleSetup`), а аннотатор цветов и Alt+Enter-действия помечены `DumbAware`. `LibraryNames.cs`: 69 диапазонов `CSHARP_*` в строках 1–60 (`e55_library_colors.png`).
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): **❌** `highlight_keys.js` на `LibraryNames.cs` — 0 диапазонов `CSHARP_*` с Built-in, имена сборок не окрашены; см. E-46.
Робот 2026-10-05 (0.1.60, `highlight_keys.js`): все имена сборок `LibraryNames.cs` окрашены как у сервера, включая `WriteLine(x)` с
аргументом неизвестного типа, `g.Key` после `into g` и `query.Any()`.
1. «Colors of identifiers» = **Built-in**; для чистоты Language Server выключить (Settings | .NET). После restore площадки дождаться
   конца индексации (сборки индексирует помощник плагина).
2. `Console/Editor/LibraryNames.cs`, метод `Colors`: сверить с `EXPECT` маркера — `Console` / `StringBuilder` / `List` цветом класса, `Math`
   static-класса, `DateTime` структуры, `WriteLine` / `Round` / `Max` static-вызова, `Append` / `ToString` вызова, `Count` / `Length` / `Year`
   свойства, `PI` константы, `Where` / `First` extension-метода; `System` / `Text` / `Json` в `using` — namespace.
3. `SemanticColors.cs`, `TYPE:colors-members`: `Console` и `WriteLine` теперь тоже раскрашены.
4. Открыть любой файл на 1000+ строк: цвета появляются сразу, набор не тормозит (замер теста — 30–60 мс на 1000 строк).

#### E-70 Decompile... на сборке фреймворка
1. Открыть `debug-playground` (restore сделан). Solution view → `Console` → Dependencies → .NET 9.0 → Frameworks → Microsoft.NETCore.App →
   **System.Text.Json** → ПКМ → **Decompile...**. Первый раз помощник плагина может собираться (до минуты, в статусе задача).
2. Список типов с поиском: набрать `JsonSerializer`, Enter. Открывается вкладка `JsonSerializer.cs [System.Text.Json]`, каретка на имени типа.
3. Баннер «Decompiled from System.Text.Json 9.0.0.0. Read-only»; «Show Assembly in Explorer» показывает
   `dotnet/shared/Microsoft.NETCore.App/9.0.x/System.Text.Json.dll` — реализацию, не `packs/...Ref`. В начале файла комментарий с MVID и путём.
4. У методов есть тела (не `throw null`), над членами `/// <summary>` из документации; цвета, folding, Structure (Alt+7) работают.
5. Набрать что-нибудь в файле — ничего не меняется, диалога «make writable» нет. Ошибок в Plugin Logs (.NET | Plugin Logs) нет, в категории
   `decompiler` — строка «… decompiled in N ms».

#### E-71 Decompile... на пакете, forwarded-тип
1. Dependencies → Packages → `Microsoft.Extensions.Hosting` → Decompile... → `Host` → `Host.cs [Microsoft.Extensions.Hosting]`.
2. Frameworks → Microsoft.NETCore.App → **System.Runtime** → Decompile... → `String` → вкладка `String.cs [System.Private.CoreLib]`
   (тип живёт там, `System.Runtime` его только пересылает). Первый раз 2–4 с (в статусе «Decompiling String»), не дольше.
3. Второй тип той же сборки (например `JsonDocument` после `JsonSerializer`) — быстрее секунды. Замеры агента: `System.Console` 0,3 с,
   следующий тип 0,03 с, `String` 2,2 с, `JsonSerializer` 0,5 с, Newtonsoft `JsonConvert` 0,2 с.

#### E-72 Вкладка декомпилированного типа
1. Открыть `JsonSerializer` (E-70), перейти в свой файл, закрыть вкладку декомпиляции, Back (Ctrl+Alt+←) — вкладка открывается снова, на том же месте.
2. Перезапустить IDE с открытой вкладкой — она восстановлена (из кэша на диске, помощник не нужен).
3. Повторный Decompile... того же типа — мгновенно, та же вкладка. После `dotnet build` / обновления пакета (другая dll) — декомпилируется заново.
4. На узле проекта, Analyzers, Projects, самого Microsoft.NETCore.App пункта Decompile... нет.

#### E-60 External Libraries
1. Открыть `debug-playground` (restore сделан), дождаться конца индексации. Окно Project, вид **Project** (не Solution) → узел
   **External Libraries**: `Microsoft.NETCore.App.Ref 10.0.x` и `9.0.x`, `Microsoft.AspNetCore.App.Ref`, `.NETFramework 4.8.1` (если есть
   reference assemblies), пакеты (`Newtonsoft.Json 13.0.x`, `xunit.assert 2.9.2`, `Aspire.Hosting …`) с иконкой NuGet; внутри — только dll.
2. Двойной клик по dll — открывается как бинарный файл (или IL Viewer), IDE не виснет. Go to File (Ctrl+Shift+N) `Newtonsoft.Json.dll`:
   без «Include non-project items» не находится, с ним — находится (библиотека, не файл проекта).
3. Сменить версию пакета в `Console.csproj` и сделать restore (.NET → Restore) — через несколько секунд в External Libraries новая
   версия, старой нет. Сменить TFM в тулбаре на `MultiTarget` — набор packs меняется.
4. Время: индексация после открытия заметно не выросла (замер в тесте — ≈ 0,5 с на 592 dll). В GoLand / PyCharm — то же самое.

#### E-62 `TYPE:using-var`, `-await`
Робот 2026-10-05 (WSL, **0.1.67**): шаг 3 — см. E-65.
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): шаг 1: `usi` + Ctrl+Space — `using`, `using var`, `await using var`; `using var` → `using var |` (`e62_usi.png`); шаг 2: `aw` → `await using var` даёт `await using var ` и `public async Task Run()` (`e62_aw.png`, `e62_await_using.png`). Шаг 3 (Alt+Enter «Make method async») — см. ❌ E-54; шаг 4 (`using (`) не прогонялся.
1. «Completion» = **Built-in**. `Console/Editor/Usings.cs`, метод `Var`: набрать `usi`, Ctrl+Space → в списке `using var`, `await using var`,
   `using`; Enter на `using var` → `using var |`. В геттере `Name` — `using var` есть, `await using var` нет. Ctrl+Z.
2. `TYPE:using-await`, метод `Run`: `aw` → `await using var` → вставлено `await using var `, заголовок `public async Task Run()`. Ctrl+Z до исходного.
3. Там же набрать вручную `await using var s = new MemoryStream();`, Alt+Enter на `await` → «Make method async» одной строкой (и при
   готовом сервере: строки сервера с тем же текстом нет). Ctrl+Z.
4. Внутри метода набрать `using (` и Ctrl+Space → локальные, `var`, `new`.

#### E-63 `TYPE:using-directive`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): `using System.Coll` + Ctrl+Space — единственный вариант сразу вставлен: `using System.Collections` (`e63_coll.png`); `using static System.` — namespace и тип `Buffer (System)` (`e63_static.png`). Alias и `global using` не прогонялись.
1. Built-in, после индексации и индекса сборок (restore сделан). Пустая строка под маркером вверху `Usings.cs`: `using System.Coll` +
   Ctrl+Space → `Collections` (иконка пакета), типов нет. `using static System.` → namespace и типы `Console`, `Math`. `using J =
   System.Text.` → `Json`, `Encoding`, `StringBuilder`. `using ` → `static` первым словом, после `using static ` его нет.
2. На первой строке файла (до директив) Ctrl+Space → пункт `global using`; после обычного `using` его нет. Набрать `global ` → `using`. Ctrl+Z.

#### E-64 `TYPE:using-postfix`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): `new StringReader("x").using` — в списке два `using` («using var value = expr;» и «using statement», `e64_postfix_popup.png`); Enter или Tab по первому → `using var reader = new StringReader("x");`, `reader` выделено (`e64_postfix_tab.png`); `new MemoryStream().awaitusing` → `await using var stream = new MemoryStream();` и `public async Task Postfix()` (`e64_awaitusing_tab.png`).
1. Метод `Postfix`: `new StringReader("x").using` + Enter → `using var reader = new StringReader("x");`, `reader` выделено (набор
   заменяет имя). `new MemoryStream().awaitusing` → `await using var stream = …;`, метод стал `public async Task Postfix()`. Ctrl+Z.
2. В списке postfix-шаблонов (Settings | Editor | General | Postfix Completion → C#) есть `.awaitusing`.

#### E-65 `TYPE:using-to-declaration`, `-to-statement`, `-wrap`
Робот 2026-10-05 (WSL, **0.1.67**, снимки — `C:/tmp/wsl-fix/`): исправлено. Причина ❌ в 0.1.63 — IDEA 2026.1 открывает папку без системы сборки проектом без модулей, её файлы не индексируются (`CONTENT_NON_INDEXABLE`), и платформа пропускает в них всё, что не `DumbAware` (аннотаторы цветов, intention-действия плагина). Теперь плагин сам заводит модуль на папку с solution (`DotNetModuleSetup`), а аннотатор цветов и Alt+Enter-действия помечены `DumbAware`. «Convert to 'using' statement» первым, с превью, применяется (`e65_tostatement_popup.png`, `e65_tostatement_done.png`); «Wrap in 'using' statement» есть (`e65_wrap_popup.png`), рядом серверное «Introduce 'using' statement» (другое название, не скрыто).
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): **❌ строк плагина в Alt+Enter нет.** На `using` в `ToDeclaration` — только серверное «Fix All: Use simple 'using' statement» (`e65_todecl_popup.png`); на `using var writer` в `ToStatement` «Convert to 'using' statement» нет совсем; на `wrapped` в `Wrap` — серверное «Introduce 'using' statement», плагинного «Wrap in 'using' statement» нет. При этом `NativeCSharpToUsingDeclarationIntention.isAvailable` = true и на EDT, и в фоне, `ShowIntentionActionsHandler.availableFor` = true, интенция включена в настройках, а `ShowIntentionsPass.getActionsToShow` её не возвращает (`AddPartialPartIntention` и `CreateTestIntention` того же файла — возвращает). Общая причина с E-54 и E-66.
1. Alt+Enter на `using` в `ToDeclaration` → «Convert to 'using' declaration»: `using var reader = …;`, `WriteLine` ниже на уровень левее,
   скобок нет. Ctrl+Z возвращает как было. Окно превью Alt+Enter показывает результат.
2. «Typing assistance» = Language server и сервер готов: вместо своего — пункт сервера «Use simple 'using' statement»; Built-in — только
   свой, без строки сервера.
3. `ToStatement`: Alt+Enter на `using` → «Convert to 'using' statement»: `using (var writer = …)` и два оператора в новых скобках. Ctrl+Z.
4. `Wrap`: Alt+Enter на `wrapped` → «Wrap in 'using' statement»: остаток метода в `using (…) { }`; на `text` (значение вызова) пункта нет.
   Отступы при табах (другой `.editorconfig`) — только вживую.

#### E-66 `TYPE:using-sort`, `-global`
Робот 2026-10-05 (WSL, **0.1.67**, снимки — `C:/tmp/wsl-fix/`): исправлено. Причина ❌ в 0.1.63 — IDEA 2026.1 открывает папку без системы сборки проектом без модулей, её файлы не индексируются (`CONTENT_NON_INDEXABLE`), и платформа пропускает в них всё, что не `DumbAware` (аннотаторы цветов, intention-действия плагина). Теперь плагин сам заводит модуль на папку с solution (`DotNetModuleSetup`), а аннотатор цветов и Alt+Enter-действия помечены `DumbAware`. на `using System.Text;` — «Convert to 'global using'» и «Sort 'using' directives»; Sort применён (`e66_directive_popup.png`, `e66_sorted.png`).
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): **❌** на `using System.Text;` — серверные «Sort Usings» и «Fix All: Sort Usings», плагинных «Sort 'using' directives» и «Convert to 'global using'» нет (та же причина, что E-65).
1. Alt+Enter на любой из трёх директив вверху `Usings.cs` → «Sort 'using' directives»: `System`, `System.IO`, `System.Text`. После
   сортировки пункта нет. Ctrl+Z.
2. Alt+Enter на директиве `System.Text` → «Convert to 'global using'»: строка ушла, появился `Console/GlobalUsings.cs` с `global using
   System.Text;`, проект собирается. Ctrl+Z (если файл остался — удалить). В файле, где уже есть `global using`, директива переходит к ним.

#### E-76 `TYPE:ctx-if-to-conditional`, `-conditional-to-if`
1. Settings | .NET | Language Server → Source of Features → «Context actions» = **Built-in**, Apply. Открыть
   `Console/Editor/ContextActions.cs`, дождаться конца индексации.
2. Каретка на `if` в `Sign`, Alt+Enter → «Convert to '?:' expression»: четыре строки стали `return value >= 0 ? "plus" : "minus";`.
   Превью Alt+Enter (справа от пункта) показывает то же. Ctrl+Z — как было, одним шагом.
3. То же на `if` в `Clamp` (`if` + следующий `return`) → `return value > 100 ? 100 : value;`; в `Assign` → `_total = add ? _total + value :
   _total - value;`. На `if` в `Mixed` пункта нет. Ctrl+Z.
4. Каретка на `?` в `Parity`, Alt+Enter → «Convert '?:' to 'if' statement»: `if (value % 2 == 0)`, скобки на своих строках, `return "even";`
   / `else` / `return "odd";`. В `Check` — ветка `else` `throw new ArgumentException("no text");`. В `Print` (аргумент) пункта нет. Ctrl+Z.
5. При готовом сервере и Built-in в списке Alt+Enter нет строки сервера «Convert to conditional expression» рядом со своей; переключить
   «Context actions» = Language server — снова есть только строка сервера, своих нет.

#### E-77 `TYPE:ctx-expression-body`, `-block-body`, `-var`
1. Built-in, как в E-76. Каретка на имени `Twice`, Alt+Enter → «To expression body»: `public int Twice(int value) => value * 2;`. На `Log` —
   `=> Console.WriteLine(text);`; на `Name` — `public string Name => "context";`; на `get` у `Counter` — `get => _total;`, `set` остался.
   На `Two` и внутри тела любого метода пункта нет. Ctrl+Z после каждого.
2. На `Half` → «To block body»: блок с `return value / 2;`, скобки на своих строках, отступ как у класса. На `Shout` — без `return`. На
   `Title` — `{ get { return "title"; } }` в три строки. На `set` у `Level` — `set { _total = value; }` в той же строке. Ctrl+Z.
3. На `var` у `names` → «Use explicit type»: `List<string> names` (без `System.Collections.Generic.`). У `count` — `int`, у `query` —
   `IEnumerable<string>`, у `var` в `foreach` — `string`. На `int` у `total` → «Use 'var'»; у `long big` и `IList<string> list` «Use 'var'»
   нет. Ctrl+Z.
4. При готовом сервере и Built-in: нет строк сервера «Use expression body for method», «Use block body for property», «Use explicit type»,
   «Use implicit type».

#### E-78 `TYPE:ctx-introduce`, `-inline`
1. Built-in, как в E-76. Каретка на `Count` в первой строке `Introduce`, Alt+Enter → «Introduce variable»: строкой выше `var count =
   items.Count;`, в вызове — `count * 2`, имя в рамке шаблона. Набрать `size` — меняется в обоих местах; Enter. Ctrl+Z (один-два шага) — как было.
2. Выделить `items.Count * 2`, Alt+Enter → «Introduce variable»: `var value = items.Count * 2;`, в вызове `value`. Ctrl+Z.
3. На `Ready()` после `&&` и на `items` без члена пункта нет.
4. На `sum` в `Inline` → «Inline variable»: объявления нет, `return (a + b) * 2 + Math.Abs(a + b) + changed;`. На `changed` пункта нет. Ctrl+Z.
5. При готовом сервере и Built-in нет строк сервера «Introduce local for '…'» и «Inline temporary variable» (его «Introduce constant …»
   остаются).

#### E-79 `TYPE:using-cs1674`, `-list`
1. «Errors and warnings» = Built-in (по умолчанию), после индексации и restore. `Usings.cs`, метод `Errors`: на пустой строке набрать
   `using (var n = 5) { }` → `var n = 5` подчёркнуто красным, подсказка `CS1674: 'int': type used in a using statement must implement
   'System.IDisposable'.` При готовом сервере — одна отметка, не две (строка сервера без кода убрана).
2. `using var b = new StringBuilder();` → CS1674 с `'System.Text.StringBuilder'`; `await using var t = new
   System.Threading.CancellationTokenSource();` → CS8417 «… Did you mean 'using' rather than 'await using'?».
3. Без ошибки: `using var s = new MemoryStream();`, `await using var m = new MemoryStream();`, `using (var x = Unknown()) { }` (только CS0103
   сервера). Ctrl+Z.
4. «Completion» = Built-in. Метод `List`, пустая строка: `using (` + Ctrl+Space → `reader`, `stream`, нет `count` и `title` (ни своих, ни
   строк сервера). `using var x = ` + Ctrl+Space — то же. `await using (` + Ctrl+Space → `stream`, нет `reader`. Ctrl+Z.

#### E-73 Go to Class по сборкам
1. Открыть `debug-playground` (restore сделан), дождаться конца индексации сборок. Для чистоты Language Server выключить
   (Settings | .NET) — Go to Class сервера тоже даёт типы сборок.
2. Ctrl+N, `JsonSerializer`: без «Include non-project items» — только типы solution (или ничего); Ctrl+N ещё раз (галочка) — строка
   `JsonSerializer (System.Text.Json, System.Text.Json 10.0)` **одна**, хотя сборку видят все проекты; иконка класса. В Search Everywhere
   (Shift Shift → Classes) — то же при «All Places» и ничего при «Project Files».
3. Enter — открылась вкладка `JsonSerializer.cs [System.Text.Json 10.0]`, каретка на имени типа. `List` с галочкой — `List<T>` и
   `List<T>.Enumerator` подписаны своими namespace. Ввод `Generic.List` тоже находит `List<T>`.
4. Скорость: список обновляется на каждую букву без задержки (замер теста — 582 сборки, 41 тыс. имён: 110 мс первый раз, 7 мс потом).
   В GoLand / PyCharm — то же.

#### E-74 Metadata view
1. Из E-73 открыть `JsonSerializer.cs` и `List.cs` (Ctrl+N `List`). Вверху: `// Metadata of …`, `// Assembly: …, Version=…`, `// MVID`,
   `// Assembly location: …\System.Text.Json.dll`; `using`, `namespace … { }`, тип с generic-параметрами, `where`, базами и атрибутами;
   члены — сигнатуры без тел (`;`, `{ get; set; }`), `///` с `<summary>` / `<param>` построчно, вложенные типы (`Enumerator`) внутри.
2. Цвета C#, сворачивание блоков и `///`, Structure (Alt+7) показывает члены. Ошибок (красного) нет.
3. Набрать букву — «файл только для чтения», текст не меняется. Баннер «Metadata of System.Text.Json 10.0.0.0: … Read-only», ссылка
   «Show Assembly in Explorer» открывает папку с dll.
4. Оставить вкладку открытой, перезапустить IDE: вкладка на месте (содержимое находится по индексу сборки в кэше); Back / Forward и
   Recent Locations ведут в неё и обратно.

#### E-75 Go to Symbol по членам сборок и Ctrl+click в metadata view
1. Ctrl+Alt+Shift+N `WriteLine` с галочкой: перегрузки `WriteLine(string)`, `WriteLine(int)`… с `(Console, System, System.Console 10.0)`.
   Enter на `WriteLine(string)` — `Console.cs`, каретка на имени этой перегрузки (строка `public static void WriteLine(string? value);`).
2. «Navigation and usages» = **Built-in**, сервер выключен. `Console/Editor/LibraryNames.cs`, `TYPE:library-navigation`: Ctrl+click по
   `Add` — `List.cs` на `Add`; по `WriteLine` — список перегрузок, выбор открывает `Console.cs` на ней. `Console` в `Console.WriteLine` —
   `Console.cs` на имени класса.
3. С готовым сервером (включить, дождаться) — Ctrl+click по `WriteLine` открывает, как раньше, декомпилированный исходник сервера
   (`Console.cs [System.Console]` из `MetadataAsSource`), не metadata view.

#### E-80 `TYPE:dot-instance`, `-generic`
1. Settings | .NET | Language Server → Source of Features → «Completion» = **Built-in**; сервер выключить (Settings | .NET
   → Language Server), дождаться индексации сборок после restore.
2. `Console/Editor/MemberCompletion.cs`, метод `Instance`: на пустой строке набрать `text.` — список открылся сам: `Length`,
   `Substring`, `ToUpper`, `Split`…, ниже — `Where`, `Select`, `First` с параметрами без `this string source`. Нет `IsNullOrEmpty`,
   `Join` (static), `MemberwiseClone`, `Finalize` (protected), ключевых слов. Выбрать `Substring` — `text.Substring(|)`. Ctrl+Z.
3. Метод `Generic`: `_orders.` — `Add` с хвостом `(MemberOrder item)`, `Count` с типом `int`, LINQ. `_orders[0].` — `Total`,
   `Ship`, `Lines`, `Add` с `(+ 1)`; нет `_secret`. Ctrl+Z.

#### E-81 `TYPE:dot-static`, `-namespace`, `-this`
1. Настройки — как в E-80.
2. Метод `Static`: `Console.` → `WriteLine` (`(string value) (+ N)`), `ReadLine`, `Out`, нет экземплярных членов; `string.` →
   `Join`, `Empty`, `IsNullOrEmpty`, нет `Length`; `MemberOrder.` → `Empty`, `State`, нет `Total`; `MemberOrder.State.` → `New`, `Shipped`.
3. Метод `Namespaces`: `System.Collections.Generic.` → `List<>`, `Dictionary<,>`, `HashSet<>` (выбор `List` — `List<|>`); `System.` → namespace
   (`Collections`, `Text`) и типы (`Console`, `String`).
4. Класс `Numbers`, метод `Fill`: `this.` → `Add`, `Count` (из `List<int>`) и `_extra`. Ctrl+Z после каждого.

#### E-82 Completion после точки рядом с сервером
1. «Completion» = **Built-in**, сервер включён и загружен (виджет в статус-баре без `loading…`).
2. `TYPE:dot-generic`: `_orders.` — каждое имя один раз (нет второго `Add` / `Count` от сервера); всё, что сервер даёт сверх
   встроенного списка (сниппеты, `await`-пункты), осталось. Выбор `Add` — `_orders.Add(|);`, `ToString` — `_orders.ToString()`.
3. Переключить «Completion» = Language server — список тот же по составу (только сервер), без двойных пунктов.

#### E-83 `TYPE:quick-doc`
1. «Documentation and parameter info» = **Built-in**, сервер выключен; сборки проиндексированы.
2. `MemberCompletion.cs`, метод `Documented`: Ctrl+Q на `WriteLine` — `void Console.WriteLine(string value) (+ N overloads)` и текст
   документации System.Console с разделом Params; на `Substring` — `string string.Substring(int startIndex, int length) (+ 1 overload)`;
   на `Total` — `decimal MemberOrder.Total { get; set; }` и «The sum of the lines.»; на `Add` — перегрузка с `count`, разделы Params;
   на `limit` — `(parameter) int limit` и «How many to show.»; на имени `Documented` — «Shows the first orders.».
3. То же наведением мыши (подсказка с задержкой). С включённым сервером и Built-in — одно окно, не два; с Language server — окно сервера.

#### E-84 `TYPE:parameter-info`
1. Настройки — как в E-83.
2. Каретка в скобках `first.Add("book", limit)`, Ctrl+P: две строки — `string item` и `string item, int count = 1`, вторая отмечена,
   `int count = 1` выделен; каретка на `"book"` — выделен первый параметр.
3. `Console.WriteLine(` (набрать в `Documented`) — строка на каждую перегрузку; после второго аргумента перегрузки с одним параметром серые.
   `new StringBuilder(` в `Build` — конструкторы `StringBuilder`. Ctrl+Z.

#### E-85 `TYPE:format-initializers`
1. Settings | .NET | Toolset and Build → «Formatter» = **Built-in** (или Auto при «Formatting» = Built-in). OK.
2. `Console/Editor/Formatting.cs`, метод `Initializers`: выделить метод целиком, Ctrl+Alt+L. `var box = new Box` / `{` / `Count = 3,` /
   `Name = "n"` / `};` — `{` и `}` под `var`, члены на 4 глубже; `new List<int>` / `{` / `1, 2,` / `3` / `};` — `1, 2,` остались вместе;
   `int[] numbers =` / `[` / `1,` / `2` / `];`.
3. `var pair = new` / `{` / `Box = new Box` / `{` (на 4 глубже) / `Count = 1,` … / `},` / `Total = 2` / `};` — соседи вложенного
   многострочного инициализатора разложены по строкам. `new List<int> { 4, 5 }` и `[6, 7]` — в одну строку с пробелами как в Rider.
   `Sum([` / `8,` / `9` / `]);` — `[` остался после `(`, `]` под `var`.
4. Не должно быть: склеенных строк, элементов, выровненных по первому, изменений в запросе и в лямбде `print`. Повторный Ctrl+Alt+L
   ничего не меняет. Ctrl+Z — метод вернулся.
5. С «Formatter» = dotnet format те же многострочные инициализаторы остаются как были (так делает `dotnet format`) — это ожидаемо.

#### E-86 `TYPE:format-arguments`
1. Настройки — как в E-85.
2. Метод `Arguments`: выделить, Ctrl+Alt+L. `Combine(1,` / `2,` / `3);` — `2,` и `3);` ровно на 4 правее `var` (не под `1`);
   `Combine(` / `1,` / `2,` / `3` на 4 правее / `);` под `var`.
3. `Combine(Combine(1,` / `2, 3),` на 8 правее `var` / `4, 5);` на 4; `Combine(1, Combine(2,` / `3, 4), 5);` — на 4.
4. `if (Combine(a,` / `b, c) > d)` — `b` на 4 правее `Combine` (первого токена в скобках `if`), не под `a`.
5. Параметры `private static int Combine(int x,` / `int y, int z)` — вторая строка на 4 правее `private`. Повторный Ctrl+Alt+L ничего
   не меняет; строки не склеены и не разбиты. Ctrl+Z.

#### E-91 Settings: узел .NET в корне
Робот 2026-10-05 (WSL, 0.1.70; снимок `C:/tmp/rider-ui/06-settings-tree.png`): .NET на верхнем уровне между Backup and Sync и
Advanced Settings, внутри Coverage, Debugger, Language Server, NuGet, Toolset and Build (по алфавиту, как сортирует платформа).
1. Settings (Ctrl+Alt+S). В дереве слева на верхнем уровне — **.NET** (рядом с Build, Execution, Deployment / Languages & Frameworks /
   Tools), раскрывается в Toolset and Build, NuGet, Coverage, Debugger, Language Server. Под Tools узла .NET нет.
2. Editor | Code Style | C# и Editor | Color Scheme | C# — на прежних местах. Поиск в Settings по «Formatter» находит страницу .NET.
3. Ссылки плагина на настройки (баннер «нет сервера языка», уведомление «нет dotnet») открывают страницы под новым узлом.

#### E-92 Тулбар: Build Solution
Робот 2026-10-05 (WSL, 0.1.70; `01-toolbar.png`…`04-build-running.png`): молоток со стрелкой перед виджетом запуска, подсказка
`Build DebugPlayground.sln (Debug)` → после выбора Release `(Release)`; клик собирает (9 warnings, 0 errors); во время сборки Cancel Build
активен и останавливает её. Найдено и исправлено: остановленная сборка показывалась как «failed with exit code» — теперь «cancelled»
(после исправления вживую не смотрели).
1. Открыть `debug-playground`. Справа в тулбаре перед виджетом запуска — молоток со стрелкой; комбобокса `Debug | …` больше нет.
   Подсказка на молотке — `Build DebugPlayground.sln (Debug)`. Сравнить с `docs/rider-analysis/img/02-main-toolbar.png`.
2. Клик по молотку — сборка solution в окне Build. Пока идёт — стрелка → **Cancel Build** активен и останавливает сборку.
3. Стрелка: Build Solution, Rebuild Solution, Clean Solution, NuGet Restore, Cancel Build (серый без сборки); под заголовком
   **Configuration** — Debug / Release с галочкой у выбранной; под **Target Framework** — Default, .NET 9.0, .NET 10.0 (из `MultiTarget`).
   Выбор Release → подсказка молотка `(Release)`, следующая сборка с `-c Release`; ветки `#if` в `MultiTarget/ActiveBranch.cs` следуют TFM.

#### E-93 `TYPE:refactor-method`, `-inline`, `-introduce`
Робот 2026-10-05 (WSL, 0.1.70, сервер готов; `09-refactor-this.png`, `09b-…selection.png`, `09c-…method.png`): на `area` — Rename...,
Move type, Introduce local, Inline temporary variable, Introduce parameter, Move File... / Copy File...; на выделении — Introduce local for
'Math.Round(area, digits)', Extract method, Extract local function, выбор Introduce local применился, Ctrl+Z вернул. Найдено и исправлено:
серверу уходила каретка, а не выделение (строк выделения не было). Change signature сервер не предлагает.
1. `Console/Editor/RiderPopups.cs`. Каретка на `Describe`, Ctrl+Alt+Shift+T: попап «Refactor This», первая строка Rename...; с готовым
   сервером — его рефакторинги (Move type, Extract base class...). Нет Java-строк и серых строк. Сравнить с `img/25-refactor-this.png`.
2. Каретка на `area` в `var area = Area();` → среди строк **Inline Variable**; выбор подставляет `Area()` в `return`. Ctrl+Z.
3. Выделить `Math.Round(area, digits)` → **Introduce Variable** (Built-in) или строки сервера по выделению: Introduce local for …,
   Extract method, Extract local function. Выбор применяется, Ctrl+Z возвращает.
4. В .txt / .json-файле Ctrl+Alt+Shift+T — прежний попап платформы.

#### E-94 `TYPE:navigate-call`
Робот 2026-10-05 (WSL, 0.1.70; `07-navigate-to.png`): строки и сочетания как в шаге 1, Declaration → `IPopupShape.Area` (16:12).
1. Каретка на `Area` в `shape.Area()`, Ctrl+Shift+G: попап «Navigate To» — Declaration (Ctrl+B), Implementation (Ctrl+Alt+B), Base Symbols,
   Find Usages, Related Files, Type of Symbol, Show Usages; Type Hierarchy, Call Hierarchy, IL Code; строка файлового менеджера.
   Сравнить с `img/26-navigate-to.png`.
2. Declaration → `IPopupShape.Area`; Implementation (с сервером) → `PopupCircle.Area`. Navigate → Navigate To... в главном меню — тот же попап.

#### E-95 `TYPE:generate-in-class`, `TYPE:editor-menu`
Робот 2026-10-05 (WSL, 0.1.70; `08-generate.png`, `10-generate-on-class.png`, `11-editor-menu.png`): на имени класса — Generate constructor
'PopupCircle()', Unit Test, Extract interface..., Add 'DebuggerDisplay' attribute | Insert New GUID; на пустой строке сервер не даёт ничего —
Unit Test и Insert New GUID. ПКМ — строки на местах Rider, Inspect ▸ Browse Call / Type Hierarchy, IL Viewer; Call Hierarchy из меню открылась.
Найдено и исправлено: Call / Type Hierarchy в меню редактора спрашивали сервер в фоновом update под модальным прогрессом
(`IllegalStateException: Calling invokeAndWait from read-action`) — теперь сервер спрашивается только по клику.
1. Каретка на `PopupCircle` в объявлении класса, Alt+Insert: сразу один попап «Generate» — с готовым сервером его генераторы в порядке
   Rider (конструктор первым), Unit Test, после разделителя — Insert New GUID. Второго попапа «Generate...» нет. На пустой строке сервер
   генераторов не даёт (у Rider там полный список).
   Сравнить с `img/24-generate-alt-insert.png` (у Rider недоступные генераторы серые, у нас их нет в списке).
2. ПКМ на `Total`: порядок как в `TYPE:editor-menu` — после Find Usages строка Find Usages Settings..., подменю Inspect (Call Hierarchy,
   Type Hierarchy, IL Code), Generate Code..., Quick Definition перед Compare with Clipboard. В .txt этих строк от плагина нет.

#### E-96 `TYPE:strings-holes`, `TYPE:strings-format`, `TYPE:strings-raw`
1. Схема Darcula или Islands Dark. `debug-playground/Console/Editor/StringColors.cs`, метод `Holes`.
2. `$"Order {x + 1} of …"`: `+` — цвет оператора, `1` и `0` — цвет числа, `null` — ключевое слово, `"yes"` — отдельная строка, `{` `}`
   дырок — цвет скобок, `x` / `name` / `Length` — как параметр и свойство; `Order ` и ` of ` — коричневые (строка).
3. `$"{x,5:D} …"`: `,5:D`, `,-12:N2`, `:yyyy-MM-dd` — фиолетовые (элемент формата).
4. Метод `BraceEscapes`, `$$"""…"""`: `{{` `}}` вокруг `count` — цвет скобок, `count` — код; одиночные `{` `}` и `\n` — текст строки.

#### E-97 `TYPE:strings-escapes`, `TYPE:strings-invalid-escape`, `TYPE:strings-brace-escapes`, `TYPE:strings-typing`
1. Метод `Escapes`: в `"a\tb\n\r\n\\ \u0041"` `\t` — розово-фиолетовый, `\n` — тоже, `\r` сразу после него — бирюзовый, следующий `\n` —
   снова первый цвет (соседние чередуются); в `@"C:\dir ""quoted"" \d"` `""` — escape, `\d` — текст; `'\n'` — escape.
2. Набрать `\q` между пробелами в `"bad  escape"` — красное волнистое подчёркивание (неверный escape); `\x41` вместо него — верный. Ctrl+Z.
3. `$"{{ \"count\": {count} }}"`: `{{` и `}}` — цвет escape, `{count}` — дырка.
4. `TYPE:strings-typing`: набрать `var t = $"{` `total` `}` `\t"` — `total` окрашивается сразу, `\t` — escape, последняя `"` перешагивает
   закрывающую (в конце одна кавычка). Ctrl+Z.

#### E-98 `TYPE:strings-format-items`
1. Метод `FormatItems`: `{0}`, `{1,8:N2}` у `string.Format`, `{0:D}` у `Console.WriteLine`, `{0}{1}` у `AppendFormat` — фиолетовые;
   `{{literal}}` и `Console.WriteLine("{0}")` без аргументов — без цвета формата.
2. Settings | Editor | Color Scheme | C# → String: «String text», «Escape sequence» → Valid / Valid 2 / Invalid, «Format item», «Format item 2»;
   выбор пункта подсвечивает его в превью; смена цвета «Format item» меняет `{0}` в редакторе.

#### E-99…E-102 `TYPE:quick-doc-inherit`, `-cref`, `-var`, `TYPE:parameter-info-named`
1. «Documentation and parameter info» = Built-in (по умолчанию с 0.1.72). `MemberCompletion.cs`, метод `Inherited`.
2. Ctrl+Q на `Ship`: «Ships it.», Params `express` – «Faster.», Returns «Always the same.». На `Name` — «The name of it.».
3. Ctrl+Q на `ShippedOrder` в заголовке: три ссылки; клик по `MemberShipping` — его документация, F4 в ней — переход к классу; `Console` —
   metadata view System.Console.
4. Ctrl+Q на `var`: `class System.Collections.Generic.List<T>` и «T is int».
5. Каретка на `2` в `count: 2`, Ctrl+P: в строке `string item, int count = 1` выделен `int count = 1`, строка `string item` серая.
#### E-109 `TYPE:sem-names`, `TYPE:sem-namespace`
1. «Errors and warnings» = **Built-in** (по умолчанию). Открыть `debug-playground/Broken/SemanticErrors.cs` (в сборку не входит, редактор его видит).
2. Метод `Names`: красное с кодами и текстами Roslyn, как в EXPECT маркеров, сразу после открытия, без Build и до готовности сервера.
   `System.Nothing()` подчёркнут целиком, `System.Foo.Bar` — только `Foo`.

#### E-110 `TYPE:sem-members`, `TYPE:sem-members-silent`, `TYPE:import-type-member`, `TYPE:import-type-silent`
1. `Members`: CS1061 / CS0117 на имени члена с типом без namespace (`'Calc'`, `'List<int>'`); строки под `sem-members-silent` — без красного.
2. `ImportType.cs`: набрать `Console.WriteLin("x");` — CS0117; `dynamic d = 1; d.Anything();` и кортеж — без красного. Ctrl+Z.

#### E-111 `TYPE:sem-arguments`, `TYPE:sem-conversions`, `TYPE:sem-paths`
1. `Arguments`: CS7036 у `Add(1)` и `Twice()`, CS1501 у остальных (`Math.Max(1)` тоже).
2. `Conversions`: CS0029 / CS0266 на выражении справа; в CS0029 у `List<int>` тип полностью (`'System.Collections.Generic.List<int>'`), как у
   Roslyn; три строки `-silent` — без красного.
3. CS0161 на `NoReturn` и на `get` у `Prop2`; `Loop` и `Both` — без красного.

#### E-112 `TYPE:import-type-hint`, `-declaration`, `-choice`, `-extension`, `TYPE:sem-typing`
1. `ImportType.cs`, метод `Statements`: набрать `var sw = Stopwatch.StartNew();` — CS0103 и синяя подсказка `System.Diagnostics.Stopwatch? Alt+Enter`;
   Alt+Enter добавляет `using System.Diagnostics;` перед `namespace`, красное уходит. Ctrl+Z.
2. `StringBuilder builder = new StringBuilder();` — Alt+Enter → «Import 'System.Text.StringBuilder'». `Canvas canvas = null!;` — «Import type
   'Canvas'…» открывает список из двух namespace (`ImportTypeTargets.cs`); Escape ничего не добавляет. `new List<int>().AsReadOnly2();` — CS1061 без импорта.

#### E-113 `TYPE:sem-unused`, `TYPE:import-type-unused`
1. `SemanticErrors.cs`: две директивы `using` вверху серые, подсказка «CS8019: Unnecessary using directive.»; Alt+Enter → «Remove unused
   directives in file» убирает обе. Ctrl+Z.
2. `ImportType.cs`: в начале файла набрать `using System.IO;` — серая, «CS8933: The using directive for 'System.IO' appeared previously as
   global using». Ctrl+Z.

#### E-114 Нет дублей с сервером и сборкой
1. Build Solution на `Broken` с временно убранным `<Compile Remove="SemanticErrors.cs" />` (или любая ошибка из E-109 в компилируемом
   файле): каждая ошибка, которую показывает плагин, видна один раз, не второй раз из Build.
2. С готовым сервером (Language Server включён): то же — одна отметка на ошибку. Переключить «Errors and warnings» = Language server —
   ошибки сервера (с его IDE0005 вместо CS8019) как раньше, отметок плагина нет.

#### E-115 `TYPE:gen-list`
1. Сервер выключен (Settings | .NET | Language Server). `Generate.cs`, каретка на пустой строке под маркером, Alt+Insert.
2. Строки: Constructor, Read-only properties, Properties, Missing members (серая, Ctrl+I), Overriding members (Ctrl+O), Partial members
   (серая), Partial Part, Deconstructor, Equality members, Formatting members, Dispose pattern, Unit Test, ниже — Insert New GUID.
   Нет «Override Methods…» / «Implement Methods…» платформы.
3. С готовым сервером — те же строки, без «Generate constructor …», «Generate Equals …», «Implement interface» сервера.

#### E-116 `TYPE:gen-constructor`, `TYPE:gen-base-constructor`, `TYPE:gen-properties`
1. Constructor: диалог «Generate Constructor», группы Fields (`_id: int` отмечен) и Properties; OK — конструктор на строке каретки,
   отформатирован, пустые строки до и после. Ctrl+Z.
2. В `GenDerived` — группа «Base constructor»: `public GenDerived(string name, int size) : base(name)`.
3. Read-only properties: `public int Id => _id;`; Properties — только `_customer`, get / set на трёх строках.

#### E-117 `TYPE:gen-equality`, `TYPE:gen-formatting`, `TYPE:gen-deconstructor`, `TYPE:gen-dispose`, `TYPE:gen-partial`
1. Equality members: два флажка внизу диалога (подпись первого видна целиком?); результат как в EXPECT, `dotnet build` без ошибок.
2. Formatting members, Deconstructor — по EXPECT.
3. Dispose pattern в `GenResource`: `: IDisposable` в заголовке, `Dispose(bool)` с `_buffer` / `_timer?`.
4. Partial members: диалог с группой `GenPartial`, `partial void OnSaved(int id) { }`.

#### E-118 `TYPE:gen-missing-abstract`, `TYPE:gen-missing-library`, `TYPE:gen-override`
1. `GenSquare`: Missing members (и Ctrl+I) — `Area(): double`, `Label: string` отмечены; OK — `throw new NotImplementedException();`.
2. `GenMoney`: дописать `: IComparable<GenMoney>, IDisposable`, Missing members — группы интерфейсов, `CompareTo(GenMoney? other): int`;
   красные пометки уходят.
3. `GenCircle`: Overriding members (и Ctrl+O) — группы `GenShape` (`Describe`) и `object`; `Area` / `Label` не предлагаются.

#### E-119 `TYPE:extract-statements`, `-returned`, `-out`, `-expression`, `-async`, `-refused`
1. Выделить строки под маркером, Ctrl+Alt+M: вызов на месте выделения, метод после текущего, имя `NewMethod` в рамке в обоих местах —
   набрать `Sum`, Enter: поменялось и там, и там. Ctrl+Z возвращает всё.
2. `extract-expression`: метод без `static` (читает поле); `extract-async`: `await NewMethod(work)` и `async Task<int>`.
3. `extract-refused`: красная подсказка «The selection contains 'return' …», текст не меняется.
4. Refactor This (Ctrl+Alt+Shift+T) с выделением — «Extract Method...», без второй строки сервера.

#### E-120 `TYPE:introduce-field`
1. Выделить `string.Concat("a", "b")`, Ctrl+Alt+F: `private readonly string _concat = string.Concat("a", "b");` после последнего поля,
   `_concat` на месте выражения.
2. Выделить `a * 2`: поле `private int _value;` и `_value = a * 2;` перед строкой.

Для E-130…E-136: сервер выключен (Settings | .NET | Language Server), площадка собрана хотя бы раз (`project.assets.json`). Первый запрос
собирает помощник CodeAnalysisHelper (~5 с на SDK 10), загрузка Console — ещё ~4 с; журнал — .NET | Plugin Logs, категория `codeanalysis`.

#### E-130 `TYPE:sg-tree`
1. Solution → Console → Dependencies → .NET 9.0 → Analyzers: узлы-молнии System.Text.RegularExpressions.Generator,
   System.Text.Json.SourceGeneration, Microsoft.Extensions.Logging.Generators среди пакетов-анализаторов; под ними тип генератора и файлы.
2. Открыть `PlaygroundJsonContext.g.cs`: баннер «Generated by …», замок на вкладке, набор не меняет файл.

#### E-131 `TYPE:sg-member`
1. На пустой строке под маркером набрать `var info = PlaygroundJsonContext.Default.`: в списке `GeneratedOrder`, `String`, `Int32`.
2. Ctrl+click по `Default` в `Serialize` — открывается `PlaygroundJsonContext.g.cs` из кэша IDE.

#### E-132 `TYPE:sg-error`, `TYPE:sg-new`
1. Набрать `var missing = PlaygroundJsonContext.Default.Customer;`, Ctrl+S: через секунду красное CS1061 на `Customer`. Ctrl+Z, Ctrl+S.
2. Над `PlaygroundJsonContext` добавить `[JsonSerializable(typeof(int[]))]`, Ctrl+S, набрать `PlaygroundJsonContext.Default.` — есть
   `Int32Array`, без красного. Откатить и сохранить.

#### E-133 `TYPE:an-on-save`
1. Открыть `Analyzers.cs`: жёлтые VSTHRD200 на `Load`, VSTHRD103 на `ReadAllText`, CA1822 на `Twice`, по одному; подсказка по наведению
   кончается «Roslyn analyzer».
2. Build Solution, снова открыть файл: те же три, не по два.

#### E-134 `TYPE:an-fix`
1. Alt+Enter на `ReadAllText` → «Await ReadAllTextAsync instead»: `await File.ReadAllTextAsync(path)`; Ctrl+Z одним шагом.
2. Alt+Enter на `Load` → «Rename to LoadAsync»; на `Twice` → «Make static». После Ctrl+S предупреждение уходит. Откатить и сохранить.

#### E-135 `TYPE:an-ide`, `TYPE:an-project`
1. В `Unused` набрать `var value = 1; value = 2; Console.WriteLine(value);`, Ctrl+S: IDE0059 на первом `value = 1`, Alt+Enter →
   «Remove unnecessary value assignment».
2. .NET → Code Analysis → Run Code Analysis: окно Build «Code Analysis Console», «0 errors, 3 warnings, N suggestions», двойной клик
   ведёт на место.

#### E-136 Settings | .NET | Analyzers and Generators
1. Снять «Run source generators», Apply: в `Generators.cs` после правки из E-132 красного нет (молчание C4c). Вернуть.
2. Снять «Show suggestions»: в `Allocations.cs` пропали слабые CA1859. Вернуть.
3. Поставить простой 1 минуту, подождать: в журнале «CodeAnalysisHelper stopped after 1 min without requests», процесс `dotnet` ушёл;
   следующее сохранение поднимает помощник снова.

#### E-140 `TYPE:overloads-*`
1. «Navigation and usages» = **Built-in**, сервер выключен, сборки проиндексированы.
2. `Console/Editor/Overloads.cs`, метод `Calls`: Ctrl+B на имени каждого вызова под маркером — переход к перегрузке из EXPECT, без
   попапа со списком кандидатов; Ctrl+Q — документация той же перегрузки. Наведение на `var sum` — `int`.

#### E-141…E-145 `TYPE:sem2-*`
1. «Errors and warnings» = **Built-in**; открыть `Broken/SemanticErrors2.cs`.
2. Сравнить метки с EXPECT каждого маркера: серым — недостижимый код и неиспользуемые локальные, жёлтым — CS4014 и nullable, красным —
   CS0120, CS1503, CS7036, CS0029, CS0266. Под «Silent» — ничего.
3. Alt+Enter на `a` (`sem2-unused`): «Remove unused variable» убирает строку; на `Work()` (`sem2-await`): «Add 'await'». Ctrl+Z после.
4. То же с сервером («Errors and warnings» = Language server): те же коды в тех же местах.
5. `Web/Program.cs`, `/fail`: строка `return "never";` под `#pragma warning disable CS0162` — без серого.

#### E-146 `TYPE:overloads-argument-name`
1. Каретка на `640` в `Resize(640, 480)`, Alt+Enter → «Add argument name»: `Resize(width: 640, height: 480)`. Ctrl+Z.
2. На `480` — только `height: 480`. На аргументе с именем действия нет.

#### E-180 `TYPE:an-quiet`
1. Сервер выключен, «Show suggestions» снят (по умолчанию с 0.1.82). Открыть `Editor/Overloads.cs`, `Editor/ContextActions.cs`, Ctrl+S.
2. Ни зелёных, ни серых волн анализаторов (раньше 18 и 19 слабых предупреждений); в `Analyzers.cs` — свои 3 предупреждения.
3. Alt+Enter на методе, который не трогает поля (CA1822, Info), — «Make static» в списке.
4. Включить «Show suggestions» — Info снова слабыми предупреждениями. Вернуть.

#### E-181 `TYPE:an-project`
1. .NET → Code Analysis → Run Code Analysis на Console: «0 errors, 3 warnings, N suggestions», под ним «Warnings (3)» раскрыт,
   «Suggestions (N)» свёрнут; двойной клик по строке ведёт на место.

#### E-182, E-183 `TYPE:implicit-*`
1. «Navigation and usages» = Built-in. `Console/Editor/ImplicitUsages.cs`: Alt+F7 на `Deconstruct`, `Add`, `GetEnumerator`, `Dispose`,
   `GetAwaiter`, на типе `ImplicitPoint` — места из EXPECT маркеров (для `ImplicitPoint` — 14, из них 7 `new(`).
2. Shift+F6 на `Deconstruct` → другое имя: меняется объявление, `var (x, y) = …` не трогается. Ctrl+Z.
3. С сервером (`usages_compare.js`): для `GetEnumerator` / `Dispose` сервер даёт больше — он каскадирует к `IEnumerable<T>` / `IDisposable`.

#### E-184 gRPC — `debug-playground/Grpc`
1. Сервер выключен, площадка восстановлена (`dotnet restore`), `Grpc/obj` можно удалить. Открыть `Grpc/Greeter.cs`, подождать несколько секунд
   (помощник делает design-time сборку, журнал `codeanalysis`: «Grpc.csproj: 2 files generated by the build: Greet.cs, GreetGrpc.cs»).
2. `HelloRequest`, `Greeter.GreeterBase`, `request.Name` — не красные; Ctrl+B на `HelloRequest` — `obj/Debug/net9.0/Protos/Greet.cs` с баннером
   «Generated by the build (Protobuf from greet.proto) for Grpc…».
3. `TYPE:grpc-errors`: `new HelloRequest().Nmae` — красное CS1061.
4. Solution → Grpc → Dependencies → .NET 9.0 → Analyzers → «Generated by build» (иконка сборки) → Protobuf → Greet.cs, GreetGrpc.cs.
5. `TYPE:grpc-stale`: поле `age` в `greet.proto`, Ctrl+S — через секунды `request.Age` резолвится. Откатить, сохранить.

#### E-185 `TYPE:wpf-generated`
1. Открыть `NetFramework/NetFramework.sln`. Удалить `LegacyWpf/obj`: в `MainWindow.xaml.cs` никаких семантических ошибок (XAML неизвестен).
2. Build (MSBuild Visual Studio). Набрать `Greeting.Txet = "";` — красное CS1061; `InitializeComponent`, `Greeting` — не красные;
   Ctrl+B на `Greeting` — `obj/Debug/MainWindow.g.cs` с баннером.
3. Поменять `MainWindow.xaml`, Ctrl+S: баннер `MainWindow.g.cs` жёлтый «Out of date…», узел «Generated by build» — «out of date: build the
   project»; имена, которые мог бы дать XAML, молчат до сборки (ошибка на члене библиотечного типа остаётся).

#### E-170 `TYPE:extract-loop-*`, `extract-return-void`
1. `ExtractMethod.cs`, `LoopCarried`: выделить `last = i * 2;` → Ctrl+Alt+M → `last = NewMethod(i);`, метод возвращает `last`. Ctrl+Z.
2. `LoopSum`: две строки под маркером → `if (NewMethod(item, ref sum)) break;`, в методе `return true;` / `return false;`. Только вторая строка → `sum = NewMethod(item, sum);`.
3. `LoopFound` → `found = NewMethod(values, i, wanted, found);`. `ReturnEarly` → `if (NewMethod(text)) return;`.
4. `Refused`: строка с `return item` — красная подсказка; строка с `continue` — `if (NewMethod(item)) continue;`; обе строки — подсказка про `return` со значением.
5. После каждого шага `dotnet build debug-playground/Console` без новых ошибок, затем Ctrl+Z.

#### E-171 `TYPE:introduce-parameter*`
1. `IntroduceParameter.cs`: выделить `10` в `Area` → Ctrl+Alt+P → попап из двух строк. «Pass It at Every Call»: `Area(int width, int …)`, вызовы `Area(3, 10)` и
   `s.Area(5, 10)` (в `IntroduceParameterCaller`), имя в рамке — набрать `factor`, Enter: и в объявлении, и в `return`. Ctrl+Z (вызовы в другом классе тоже).
2. «Make the Parameter Optional»: `int … = 10`, вызовы не меняются.
3. `width * 2` в `Twice` → `Twice(4, 4 * 2)`, `Twice(a + b, (a + b) * 2)`; `"> "` в `Prompt` → параметр перед `int level = 1`.
4. `local + 1` и `_seed * 2` — красные подсказки, текст не меняется.

#### E-172 `TYPE:introduce-field`
1. Выделить `string.Concat("a", "b")` → Ctrl+Alt+F → поле `_concat`, имя в рамке у поля и в `Console.WriteLine(_concat)`; набрать `_text`, Enter — оба места.

#### E-173 `TYPE:ctx-conditional-split`
1. `ContextActions.cs`, `Print`: каретка на `?` → Alt+Enter → «Convert '?:' to 'if' statement» → `Console.WriteLine("yes")` / `Console.WriteLine("no")` в ветках.
2. `Label`: → `string label;` и `label = Format(count, "items");` / `"item"` в ветках. `Lazy` — действия нет. Строки сервера «Replace conditional expression with statements» нет.

#### E-174 `TYPE:ctx-introduce`
1. Каретка на `Count` в первой строке `Introduce` → Alt+Enter → «Introduce variable» → список «Replace this occurrence only» / «Replace all 2 occurrences»,
   при наведении подсвечиваются вхождения. Второе: `var count = items.Count;` над первой строкой, `count` и в `if`; имя в рамке меняется везде.

#### E-175 `TYPE:gen-delegating`, `gen-equality-comparer`, `gen-relational*`
1. `Generate.cs`, `GenBag`: Alt+Insert → Delegating members → «Delegate To» (`_items`, `Title`) → `_items` → отметить `Add` и `Count` → OK.
2. `GenPerson`: Equality comparer, Relational members (оба флажка), Relational comparer — тексты из EXPECT; после каждого `dotnet build` без ошибок; Ctrl+Z.
3. `TYPE:gen-list`: строки в порядке Rider (Delegating members после Overriding members, Equality comparer после Equality members, затем Relational members / comparer).

#### E-56 `TYPE:library-navigation`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): с готовым сервером: Ctrl+click `Current.Total` → `LibraryOrder.Total` (48:16), `Lines` → поле (46:22), `Add` → `List.cs` 106:21, `WriteLine` → декомпилированный `Console.cs [System.Console]` 825:24 с баннером «Decompiled from System.Console 9.0.0.0. Read-only» (`e56_writeline.png`). Без сервера не проверено.
Робот 2026-10-05 (0.1.60, `goto_batch.js`): члены значений известного типа и сборок — туда же, что сервер (метаданные сборок через сервер).
1. «Navigation and usages» = **Built-in**, сервер выключен.
2. `LibraryNames.cs`, метод `Navigation`: Ctrl+click по `Total` в `Current.Total` → свойство `Total` класса `LibraryOrder`; по `Lines` → поле.
3. Ctrl+click по `Add` и `WriteLine` — с 0.1.62 metadata view сборки на этом члене (E-75); с готовым сервером — его декомпилированный
   исходник. Не должно быть перехода в неверное место.

#### E-57 `TYPE:types-after-call`, `-await`, `-operators`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): Ctrl+click Built-in на `Total` / `Name` после `FirstOrDefault()?.`, `First(o => o.…)`, `_orders[0].`, `ElementAt(0).`, `Last().`, `(await LoadAsync()).`, `(await Task.Run(…)).`, `(… ?? new TypesOrder()).`, `Pick(true).` — все в `TypesOrder`.
1. «Navigation and usages» и «Colors of identifiers» = **Built-in**, Language Server выключен (Settings | .NET); дождаться
   индексации сборок после restore.
2. `Console/Editor/ExpressionTypes.cs`, метод `Members`: Ctrl+click по каждому `Total` (после `FirstOrDefault()?.`, `First(o => …)`,
   `[0]`, `ElementAt(0)`, `(await LoadAsync())`, `(await Task.Run(() => _orders[0]))`) → свойство `Total` класса `TypesOrder`;
   по `Name` после `Select(o => o).Last()` → `Name`. Все эти имена — цветом свойства, не простым текстом.
3. Метод `Expressions`, `TYPE:types-operators`: `Total` после `(_orders[0] ?? new TypesOrder())` и после `Pick(true).` → `TypesOrder.Total`.
4. Перехода в неверное место быть не должно; член сборки (`Append`, `ToString`) — никуда без сервера.

#### E-58 `TYPE:types-tuples`, `-deconstruction`, `-query`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): Ctrl+click Built-in: `pair.Order.Total`, `order.Total` после деконструкции, `o.Total` в `foreach` по `Index().Values`, `big.Total`, `g.Key.Total`, `n.Name` в запросах — все в `TypesOrder`. Цвета этих членов не проверить — см. E-46.
1. Настройки — как в E-57.
2. `TYPE:types-tuples`: Ctrl+click по `Total` в `pair.Order.Total` → `TypesOrder.Total`; `Item1` (поле `ValueTuple`) — никуда.
3. `TYPE:types-deconstruction`: `Total` после `order.` (из `var (order, count) = pair`) и после `o.` в `foreach (var o in Index().Values)`
   → `TypesOrder.Total`.
4. `TYPE:types-query`: `Total` после `big.` и `g.Key.` → `TypesOrder.Total`; `Name` после `n.` → `TypesOrder.Name`.
5. Открыть файл на 1000+ строк с LINQ и лямбдами: цвета без заметной задержки (замер теста `CSharpExpressionTypesTest` — 60–100 мс тёплым).

#### E-49 `TYPE:rename-conflict`, `TYPE:rename-primary-member`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): `rename-conflict`: `prices` + Enter — всплывает окно конфликтов «2 conflicts» с «A parameter named 'prices' is already declared at line 30» и кнопками Refactor Anyway / Cancel (вместо названного в EXPECT диалога «Problems Detected» — платформа 2026.1 так показывает конфликты); Cancel оставляет `subtotal` (`e49_conflict.png`). `rename-primary-member` не прогонялся.
1. «Rename» = **Built-in**, `Console/Editor/Rename.cs`.
2. `TYPE:rename-conflict`: каретка на `subtotal`, Shift+F6, набрать `prices`, Enter — диалог «Problems Detected»: «A parameter named
   'prices' is already declared…». Cancel — везде снова `subtotal`. Повторить, Continue — переименовано (код не компилируется), Ctrl+Z.
3. `TYPE:rename-primary-member`: каретка на `owner` в `Describe`, Shift+F6, `customer`, Enter: `RenameScenarios(string customer)`,
   `<param name="customer">` над классом, `{customer}` в `Describe`. Ctrl+Z.
4. Каретка на `Limit` в `Limit * discount`, Shift+F6. Сервер готов — переименовывает он (шаблон платформы, все использования); не готов
   (сразу после открытия solution или Language Server выключен в Settings | .NET) — подсказка над кареткой «'Limit' is not a local symbol:
   members and types are renamed by the C# language server, which is not ready», текст не меняется. Не должно быть окна выбора
   «Rename: Built-in / Language server».
5. Settings | Editor | Code Editing → снять «In-place» у Rename (Specify refactoring options → in modal dialogs): Shift+F6 на `subtotal` —
   окно «Rename subtotal to:», `sum`, OK — как в шаге 3 E-48. Вернуть галочку и «Rename» = Language server по умолчанию.

#### E-50 `TYPE:diag-semicolon`, `-paren`, `-expression`, `-brace`, `-edit`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): в светлой теме: `int x = 1` — одна CS1002 после конца строки, подсказка «CS1002: ; expected» (`e50_tooltip_light3.png`); `diag-paren` — CS1026 на `;` (+ CS1513 из-за автоматически вставленной `)`); `diag-expression` — CS1525 на `;`; `diag-brace` — автопара вставляет `}`, после её удаления CS1513 в 58:2 за `}` класса, но строка 40 меняется с CS1519 на CS1001 (похоже на Roslyn, а шаг говорит «ничего между»); `diag-edit` — отметки идут за правкой сразу. Пока открыт список completion, подсветка не обновляется (нормально).
Робот 2026-10-05 (0.1.56, `errors_at.js` на копии файла в проекте `Console`): Built-in и сервер — те же 18 отметок в тех же местах,
без задвоений при переключении в обе стороны. Вживую — подсказка по наведению, отметка в конце строки, вторая тема.
1. Settings | .NET | Language Server → Source of Features → «Errors and warnings» = **Built-in**, Apply. Открыть `Broken/SyntaxErrors.cs`
   (проект `Broken` — не в solution: открыть файл через Project или Ctrl+Shift+N).
2. `TYPE:diag-semicolon`: на пустой строке под маркером набрать `int x = 1` без `;`. Красная отметка — **за концом этой строки** (не в
   начале следующей), подсказка `CS1002: ; expected`. Дождаться виджета «Roslyn: …» — второй отметки `CS1002` на той же строке не
   появляется. Ctrl+Z.
3. `TYPE:diag-paren`: `Typing(1;` → `CS1026: ) expected` на `;`. `TYPE:diag-expression`: `int y = 1 + ;` → `CS1525: Invalid expression term ';'`
   на `;`. Ctrl+Z после каждого.
4. `TYPE:diag-brace`: `if (true) {` → одна ошибка `CS1513: } expected` в самом конце файла, между ними ничего нового. Ctrl+Z — исчезает.
5. `TYPE:diag-edit`: в строке `char empty = '';` стереть вторую `'` и набрать снова — отметки переезжают сразу, старых нет.
6. «Errors and warnings» = **Language server**, Apply: те же ошибки, но от сервера (после загрузки) — коды и места те же.
7. Роботом: `feature_source.js` (`DIAGNOSTICS`, `NATIVE` / `ROSLYN`), затем `errors_at.js` (`__FILE__` = путь к `SyntaxErrors.cs`) после
   каждого набора; сравнить два прогона.

#### E-51 `TYPE:diag-literals`, `-member`, `-misplaced`, `-directives`, `-end`, `-server-keeps`
Робот 2026-10-05 (WSL, **0.1.67**): исключённый текст `#if NEVER` серый (`CSHARP_PREPROCESSOR_INACTIVE_BRANCH`, строка 67); CS0230 на `in` — так ставит и Roslyn, EXPECT поправлен; префикс «CS0230:» у текста ошибок сервера добавлен (вживую с сервером не смотрели).
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): коды и места как в EXPECT, CS1030 и CS1634 — жёлтые. Отличия: CS0230 на `in` (55:20), а не на `x`, и текст без префикса «CS0230:»; CS0029 нет (файл вне компиляции). **❌ Текст под `#if NEVER` не серый** ни в светлой, ни в тёмной теме (`e51_light.png`, `e51_dark_directives.png`) — возможно, та же причина, что в E-46.
1. Built-in, `Broken/SyntaxErrors.cs` без правок. Сверить с `EXPECT` каждого маркера: `CS1011` у `''`, `CS1009` на `\q`, `CS1021` в начале
   большого числа, `CS0595` в начале `1e`; `CS1519` на `;` в `int ;`, `CS1001` на `)` в `Member(int)`; `CS1040` на `#` в `int a = 1; #if X`
   и больше ничего в этой строке.
2. `TYPE:diag-directives`: `CS1024` на `foo` в `#foo`; **жёлтые** `CS1030: #warning: 'Look here'` и `CS1634: Expected 'disable' or 'restore'`;
   внутри `#if NEVER` (серый текст) ошибок нет, хотя `int broken = ;` сломан.
3. `TYPE:diag-end`: `CS1035: End-of-file found, '*/' expected` у `/*` последней строки.
4. `TYPE:diag-server-keeps`: после загрузки сервера `CS0230` (на `x` в `foreach`) и `CS0029` (на `"three"`) есть и при Built-in — их
   показывает сервер, встроенные их не сообщают. Если сервер не анализирует файл вне solution — отметить это, пункт не провален.
5. Settings → Language Server → выключить сервер: синтаксические ошибки остаются (встроенные), `CS0029` пропадает. Включить обратно.
6. Подсказка по наведению (текст — `CSxxxx: …`, без HTML-мусора) и вторая тема — только вживую.

#### E-52 `TYPE:complete-keywords`, `-expected`, `-goto-query`
Робот 2026-10-05 (WSL, **0.1.67**): `int x = 1` — списка нет, Enter → перевод строки, без `_resized` (`e52_intx_1.png`, `e52_intx_enter.png`).
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): порядок `total`, `limit`, поля, `Count`, методы; `whi` + Enter → `while (|)` (вторая строка `while` — live template платформы); `break` внутри `foreach` предлагается (`e52_keywords.png`, `e52_whi.png`, `e52_break.png`). **❌ Числовой литерал:** `NativeCompletion.cs`, метод `Names`, пустая строка 80 — после `int x = ` открывается список ожидаемого типа, набор `1` его не закрывает и не фильтрует, Enter даёт `int x = 1_resized` (`e52_intx_eq.png`, `e52_intx_1.png`); с COMPLETION = ROSLYN Enter — перевод строки. То же в `SyntaxErrors.cs`. Ещё: в панели inline completion видно «Tab to complete io.github.dotnetsupport.declarations» — внутренний id наружу (`e50_semicolon_light.png`).
Робот 2026-10-05 (0.1.60, `complete_at_line.js`, 26 мест): из списка сервера Built-in не теряет ничего, кроме `yield` вне итератора и
`await` в геттере (там они неверны); недостающие ключевые слова добавлены, `override`-члены сервера больше не двоятся. Built-in — по умолчанию.
1. Settings | .NET | Language Server → Source of Features → «Completion» = **Built-in**, Apply. Открыть
   `Console/Editor/NativeCompletion.cs` до готовности «Roslyn: DebugPlayground.sln» (или с выключенным сервером).
2. `TYPE:complete-keywords`: Ctrl+Space на пустой строке — список есть сразу; `total`, `limit`, затем поля / свойства, методы, типы,
   ключевые слова в конце; нет `break` / `continue` / `case` / `public`. Внутри `foreach` — есть `break` и `continue`. `whi` + Enter → `while (|)`.
3. `TYPE:complete-expected`: `CompletionSquare copy = ` → `other` выше `count`, `copy` нет; `Resize(` → `amount` первым.
4. `TYPE:complete-goto-query`: `goto ` → только `again`; после `from x in items ` → `where`, `select`, `orderby`, `join`, `let`, `group`.
5. Дождаться сервера и повторить шаг 2: пункты сервера (типы фреймворка) добавились, `total` и `if` по одному разу.
6. «Completion» = Language server: список как до 0.1.55 (сервер). Ctrl+Z после каждого набора.
7. Роботом: `feature_source.js` (`__FEATURE__` = `COMPLETION`, `__SOURCE__` = `NATIVE`, потом `ROSLYN`), в каждом режиме
   `complete_at_line.js` (`__FILE__` — путь к `NativeCompletion.cs` с прямыми слэшами, `__LINE__` — пустая строка под маркером, `__TYPE__`,
   `__LIMIT__` = 30, `__UNDO__` = `yes`); сравнить два вывода и с Rider — `rider_complete.js` на копии площадки (`__KIND__` = `BASIC`).
   В Rhino: сравнение строк — `String(x) == "true"`, числа в Java — `new java.lang.Integer(n)`.

#### E-53 `TYPE:complete-override`, `-partial`, `-names`
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): `override` — Describe, Equals, GetHashCode, Sides, ToString; Describe пишет член целиком, каретка после `;`; `partial` — `OnResized` с пустым телом; после `StringBuilder ` — `builder`, `stringBuilder`, `@string` (`e53_*.png`).
Робот 2026-10-05 (0.1.60, `complete_select.js`): Built-in `override Describe` и `partial OnResized` пишут член целиком; у сервера
выбор этих пунктов больше не падает (StackOverflowError) и не оставляет `();`.
1. Built-in. `TYPE:complete-override`: `public override ` + Ctrl+Space → `Describe`, `Sides`, `Equals`, `GetHashCode`, `ToString`, нет `Area`
   и `NotVirtual`. `Describe` → член целиком с `return base.Describe(digits);`, каретка после `;`. В новом классе-наследнике `override Area` →
   `throw new NotImplementedException();`.
2. `TYPE:complete-partial`: `partial ` → `OnResized`; Enter → `partial void OnResized(int amount)` с пустым телом.
3. `TYPE:complete-names`: `StringBuilder ` → `builder`, `stringBuilder`; `List<CompletionShape> ` → `shapes`; на уровне класса
   `private readonly StringBuilder ` → `_builder`. Ctrl+Z после каждого. Отступы вставленного члена при другом размере таба — только вживую.

#### E-54 `TYPE:complete-task-from-result`, `-task-completed`, `-await-async`, `-make-async`
Робот 2026-10-05 (WSL, **0.1.67**, снимки — `C:/tmp/wsl-fix/`): исправлено. Причина ❌ в 0.1.63 — IDEA 2026.1 открывает папку без системы сборки проектом без модулей, её файлы не индексируются (`CONTENT_NON_INDEXABLE`), и платформа пропускает в них всё, что не `DumbAware` (аннотаторы цветов, intention-действия плагина). Теперь плагин сам заводит модуль на папку с solution (`DotNetModuleSetup`), а аннотатор цветов и Alt+Enter-действия помечены `DumbAware`. `make-async`: Alt+Enter на `await` — «Make method async» плагина, даёт `public async Task<int> Delayed()` (`e54_make_async_popup.png`, `e54_make_async_after.png`). Серверное «Fix All: Make method async» стояло выше — с 0.1.67 скрыто вместе с его строкой.
Робот 2026-10-05 (WSL, 0.1.63, Xvfb, настоящие мышь и клавиатура; снимки — `C:/tmp/wsl-checks/`): серый `Task.FromResult();` и первый пункт списка, Tab → `return Task.FromResult(|);` (`e54_fromresult_ghost.png`); `aw` → `await` делает `public async Task<int> Total()` (`e54_await_total.png`). **❌ `make-async`:** Alt+Enter на `await` в `Delayed` показывает только серверное «Fix All: Make method async» (и с COMPLETION = NATIVE, и с ROSLYN), применение ничего не меняет — заголовок остаётся `public int Delayed()`. `NativeCSharpMakeAsyncIntention.isAvailable` на `await` = true (смещения +8..+11), но строки плагина в списке нет — как у E-65/E-66 (`e54_make_async*.png`).
Робот 2026-10-05 (0.1.60, `complete_select.js`): `Task.FromResult` первым и с кареткой в скобках; `await` делает `async Task<int> Total()`,
`async Task Fire()`, `async void OnClick`; у сервера `await` больше не пишет `awaitait`.
1. `Console/Editor/CommonCalls.cs`, любой источник «Completion». `TYPE:complete-task-from-result`: `return ` → серый `Task.FromResult();`,
   Tab — каретка в скобках; в `CountAsync` серого текста нет.
2. `TYPE:complete-task-completed`: в `Save` → `Task.CompletedTask;`; Built-in Ctrl+Space в `Load` / `Flush` — `ValueTask.FromResult` /
   `ValueTask.CompletedTask` и `default` первыми.
3. Built-in, `TYPE:complete-await-async`: `aw` → `await` → заголовок `public async Task<int> Total()`; в `Fire` — `async Task`; в `OnClick` —
   `async void`; в геттере `Name` пункта `await` нет.
4. `TYPE:complete-make-async`: набрать `await Task.Delay(10);`, Alt+Enter на `await` → «Make method async» (один пункт, без дубля сервера
   при Language server). Серый текст в другой теме — только вживую.

#### E-01 `TYPE:keyword-order`
1. На пустой строке под маркером набрать `pub`: в списке `public` первым, `PublicKey` (тип неимпортированного namespace) ниже.
2. Стереть, набрать `public s`: `sbyte`, `sealed`, `short`, `static`, `string`, `struct` выше `String` и `SByte`.
3. `public str` → `string` первым. Ctrl+Z.

#### E-02 `TYPE:prop-type`
1. Набрать `prop`, **Tab** — развернулся шаблон, курсор в поле TYPE.
2. Набрать `str` → в списке `string` первым (не `String`, не `StringBuilder`). Esc, Ctrl+Z.

#### E-03 `TYPE:property-name-ghost`
1. Набрать `public RankedOrder Order` — над именем **не** всплывает список имён сервера.
2. Виден серый ` { get; set; }`, **Tab** принимает.
3. Ctrl+Z; набрать `private RankedOrder ` — список имён (`rankedOrder`) всплывает, как раньше. Ctrl+Space на имени — список есть в обоих случаях.

#### E-04 `TYPE:expected-type`
1. Набрать `int amount = ` → сразу серый `count;`, **Tab** принимает.
2. Ctrl+Z, снова `int amount = `, **Ctrl+Space**: `count` первым, затем `Count`; строки (`customerName`, `text`, `_title`) и ключевые слова ниже.
3. Самой `amount` в списке быть **не должно**.

#### E-05 `TYPE:method-by-type`
1. Набрать `decimal sum = ` → серый `Total(order);`, Tab принимает.
2. Ctrl+Z, `decimal sum = ` + Ctrl+Space: `Total` выше переменных; выбор Enter → `Total(` курсор `);` с серым `order` внутри.

#### E-06 `TYPE:parameter-name`
1. Набрать `Save(` → сразу серый `order, cancellationToken` (работает и до загрузки сервера — `Save` объявлен в файле).
2. Набрать `order, ` → серый `cancellationToken`.
3. Ctrl+Z; набрать `Sa`, выбрать `Save` Tab или Enter → `Save();` с курсором в скобках и тем же серым `order, cancellationToken`.

#### E-07 `TYPE:parameter-type`
1. Набрать `Send(` + Ctrl+Space.
2. Строки `customerName`, `text`, затем `_title`, `Name` — выше `count` и `order`.

#### E-08 `TYPE:partial-name`
1. Набрать `Run(` → серый `cancellationToken` (параметр называется `token`, подбор по типу).
2. Ctrl+Z; `Ru` + Tab → `Run();` с курсором в скобках и тем же серым `cancellationToken`.

#### E-09 `TYPE:assignment`, `TYPE:return`, `TYPE:value-silent`
1. `assignment`: `Name = ` + Ctrl+Space → строки первыми, `count` ниже.
2. `return`: `return ` → серый `order;` (метод `async Task<RankedOrder>`).
3. `value-silent`: `string label = ` → серого текста **нет** (несколько строк, ни одна не называется `label`).

#### E-10 `TYPE:after-dot`, `TYPE:declared-nearby`
1. `after-dot`: `int amount = order.` → `Amount` первым; `Quantity` (тоже `int`) **не** поднят.
2. `declared-nearby`: `var copy = ` + Ctrl+Space → `text` и `count` выше полей `_orders`, `_title`.

#### E-11 `TYPE:chosen-before`
1. Три раза: набрать `_or`, Enter (`_orders`), Ctrl+Z.
2. Набрать `_` → `_orders` выше `_title`.
3. .NET → Suggestion Statistics → **Reset** → снова `_` — порядок сервера.

#### E-12 `TYPE:stats-ghost`, `TYPE:stats-list`
1. Reset статистики. Под `stats-ghost` набрать `public string Title` по буквам, дождаться серого ` { get; set; }`, Tab.
2. .NET → Suggestion Statistics: строка `auto-property` — shown 1, taken 1, 100% (набор по буквам — **один** показ).
3. Пройти E-04…E-11, снова открыть отчёт: «Completion list: N chosen», большинство в `position first`, внизу причины `expected type` /
   `name` / `declared nearby`. **Copy** кладёт отчёт в буфер, **Reset** обнуляет.

#### E-13 Список в скобках вызова
1. Набрать `Send(` и сразу буквы `cu` — список появляется без заметной паузы (в скобках добавлен запрос `signatureHelp` с таймаутом 400 мс).
2. Сравнить по ощущению с набором вне скобок. Ctrl+Z.

#### E-14 Лямбды (`LambdaSuggestions.cs`)
1. Дождаться сервера. Открыть `Console/Editor/LambdaSuggestions.cs`.
2. `lambda-action`: `Each(` → серый `lambdaOrder => `; Ctrl+Space — `lambdaOrder => ` первым, блочный вариант вторым.
3. `lambda-func`: `Register(` → `serviceProvider => `; `lambda-two`: `Retry(3, ` → `(i, s) => `; `lambda-linq`: `_orders.Where(` → `lambdaOrder => `.
4. `lambda-event`: `Changed += ` и `Changed += (` — лямбды **нет**; `lambda-silent`: `Console.WriteLine(` — **нет**. Ctrl+Z после каждого.

#### E-15 Индексатор сборок
1. Закрыть IDE, удалить `<system IDE>/dotnet-support/indexer` (system — Help | Show Log in Explorer, на уровень выше `log`). Открыть площадку.
2. В `idea.log`: «The indexer of assemblies is built for net…» и «Index of assemblies: …».
3. В `…/indexer/<хэш>-net…/bin` есть `AssemblyIndexer.dll`, в `…/index/v1` — файлы `.dnix`.

#### E-16 Неимпортированное (`ImportCompletion.cs`)
1. Открыть `Console/Editor/ImportCompletion.cs` (сервер не обязателен).
2. `import-void`: `WriteLi` → выбрать `Console.WriteLine` → `Console.WriteLine();` курсор в скобках, подсказка параметров открыта, нового `using` нет.
3. `import-using`: `Stopw` → `Stopwatch.StartNew();` и `using System.Diagnostics;` вверху файла.
4. `import-value`: `var path = Combi` → `Path.Combine();` курсор в скобках; `import-property`: `var now = UtcN` → `DateTime.UtcNow` без скобок.
5. `import-generic`: `var none = Empt` → `Array.Empty<>();` курсор в `<>`; `import-expected`: `int length = Ma` → члены `Math`, дающие `int`, выше.
6. Ctrl+Z после каждого (и `using` тоже).

#### E-17 Где молчит; пакет; статистика
1. `import-silent-dot`: `name.WriteLi` — `Console.WriteLine` **нет**; `import-silent-name`: `string WriteLi` — **нет**; `import-silent-short`: `Wr` — из индекса ничего.
2. `import-package`: в методе теста `Tests/PricingTests.cs` набрать `Equa` → `Assert.Equal` есть; то же в `Console` — **нет**.
3. `import-stats`: .NET → Suggestion Statistics — причина `not imported`.

#### E-18 Две IDE, пустой кэш
1. Закрыть IDE, удалить кэш индексатора (как в E-15).
2. Открыть площадку в двух IDE (например IDEA и GoLand с плагином) почти одновременно.
3. Индексатор собран один раз; в `idea.log` второй IDE «Index of assemblies» — за десятки миллисекунд.

#### E-19 Без учёта регистра
1. `ImportCompletion.cs`, пустая строка под `import-void`: набрать `writeli` (строчными).
2. В списке есть `WriteLine` / `Console.WriteLine`; при `WriteLi` с правильным регистром совпавшее по регистру — выше. Ctrl+Z.

#### E-20 `;` после void-метода
1. Сервер готов. В методе `CompletionRanking.cs` набрать `Console.WriteLi`, выбрать `WriteLine` Enter.
2. Ожидается `Console.WriteLine();` с курсором в скобках (как в Rider), подсказка параметров открыта.
3. Набрать `"x"` и `;` — набранная `;` перешагивает существующую, двух `;` нет.
4. `var t = text.Trim` + Enter → `text.Trim();` (вызов завершает оператор). Ручной набор `(` `;` не добавляет. Ctrl+Z.

#### E-21 `<>` у generic
1. Сервер готов. В методе набрать `var list = new List`, выбрать `List<>` → `new List<>()` с курсором в `<>`.
2. `Array.Empt` → выбрать `Empty<>` → курсор в `<>`, потом `();`.
3. `_orders.Selec` → `Select()` курсор в круглых скобках (аргумент типа выводится — `<>` не ставится). Ctrl+Z.

#### E-22 Парная `>`
1. В методе набрать `AddSingleton<` → появилась пара `<>`, курсор внутри.
2. Набрать `Count<5` в условии — пара снимается (это сравнение); то же для `Count<=` и `Count<limit;`. Ctrl+Z.

#### E-23 Серый текст, партия 1
1. В классе набрать `public string Title` → серый ` { get; set; }`.
2. `private readonly List<int> _items = ` → серый `new();`.
3. В конструкторе с параметром `name` и полем `_name`: `_name = ` → серый `name;`.
4. Вызов метода с параметрами (сервер готов): после `(` серые имена аргументов. Tab принимает каждый. Ctrl+Z.

#### E-24 Серый текст, партия 2
1. Создать в `Console/Editor` новый файл `Tmp.cs`: на `namespace ` — серый namespace папки (`Playground.Editor` в стиле file-scoped, если так принято);
   `class ` → серое `Tmp`.
2. В классе: `private readonly ILogger<` → серое `Tmp>`; при readonly-полях `public Tmp(` → параметры по полям.
3. `catch (` → серое `Exception e)`. Удалить `Tmp.cs`.

#### E-25 Серый `break;`
1. В методе `CompletionRanking.cs` набрать `switch (count) { case 1:` и Enter.
2. На первой пустой строке секции — серый `break;`, Tab принимает. Так же после `default:`. Ctrl+Z.

#### E-26 Серый `;` и `NotImplementedException`
1. В методе набрать `count = 1` (без `;`) — серый `;` в конце.
2. В классе набрать заголовок `public int Calc(int x)` → серый ` => throw new NotImplementedException();`, Tab. Ctrl+Z.

#### E-27 Complete Statement
1. В методе набрать `Console.WriteLine("x")`, **Ctrl+Shift+Enter** → дописана `;`, курсор на новой строке.
2. На `if (count > 0)` и на заголовке `void M()` Ctrl+Shift+Enter `;` **не** добавляет; на строке с несбалансированными скобками — тоже. Ctrl+Z.

#### E-28 Ctrl+W, Enter в комментарии, подсветка вхождений
1. Курсор внутри аргумента вызова, **Ctrl+W** несколько раз: слово → содержимое `()` → со скобками → оператор → блок; **Ctrl+Shift+W** — обратно.
2. В строке `// abc` нажать Enter в середине — следующая строка начинается с `// `; внутри `/* */` — с ` * `.
3. Перезапустить IDE, пока сервер грузится — курсор на идентификаторе: остальные вхождения в файле подсвечены.

#### E-29 `.editorconfig`
1. Открыть (или создать) `debug-playground/.editorconfig`, секция `[*.cs]`.
2. Набрать `csharp_` + Ctrl+Space — опции с описаниями; **Ctrl+Q** на опции — документация. То же для `dotnet_` и `dotnet_naming_rule.`.
3. Если списка нет — проверить ключи реестра `editor.config.csharp.support` и `editor.config.resharper.support`. Откатить правку.

#### E-30 Баннеры над `.cs`
1. Файл вне проекта: создать `debug-playground/Loose.cs` → баннер «не в проекте».
2. Файл, исключённый из проекта (`<Compile Remove="…" />`), — свой баннер; проект без `obj` (удалить `obj` у `Lib`) — «не восстановлен».
3. На каждом — **Don't Show Again** скрывает только этот вид. Откатить всё.

#### E-31 Intentions файла и Generate
1. В `Console/Types.cs` курсор на имени типа, **Alt+Enter**: Move type to file, Rename file to type, Add partial part, Create test, Change namespace.
2. Выполнить «Move type to file» и Ctrl+Z.
3. **Alt+Insert** → Generate… → список Roslyn «Generate / Implement …» (сервер готов). Описания intentions — в Settings | Editor | Intentions.

#### E-32 Move `.cs` в другую папку
1. Сервер готов. В Solution view перетащить файл с типом, на который есть ссылки, в другую папку `Console` (или F6 → Move).
2. Вопрос о смене namespace → Да: namespace файла и `using` в местах использования поправлены сервером (может занять пару секунд).
3. Build — 0 ошибок. Откатить (Ctrl+Z / VCS Rollback).

#### E-33 IL Viewer: методы
1. Build Solution (Debug, TFM тулбара). Меню .NET → **IL Viewer** (окно справа). Открыть `Console/Editor/IlViewer.cs`.
2. `IL:il-simple`: сверху `…IlViewer::Add`; подсвечены `ldarg.1 / ldarg.2 / add / stloc.0`; клик по `ldarg.2` подсвечивает `var sum = a + b;`, курсор не двигается.
3. `IL:il-overloads`: IL второй `Scale` с `mul`, на первой — с `ldc.i4.2`.
4. `IL:il-async`: первым `MoveNext` машины состояний; `IL:il-iterator`: `MoveNext` `<Squares>d__…` с `mul`.
5. `IL:il-lambda`: первой `<Filter>b__…` с `ldfld limit / cgt`; `IL:il-local-function`: `<Compute>g__Factorial|…` с рекурсивным `call`.

#### E-34 IL Viewer: поле, заголовок, несвежая сборка, состояния
1. `IL:il-field`: `.field private int32 _count`, серая пометка «`_count` is a field: it has no IL of its own…», без ошибки и баннера.
2. `IL:il-type-header`: `.class public auto ansi beforefieldinit …` с раскраской; переключить тему (Settings | Appearance) — читается и там.
3. `IL:il-stale`: изменить код без сохранения → жёлтый баннер «Source changed after the last build» с Build; Build → баннер ушёл, IL обновился.
4. `IL:il-states`: на `README.md` — «Open a C# file…»; после Clean Solution — «Build the project…»; scratch C# — «not a part of a .NET project»;
   курсор вне типа — одна надпись «No IL at line N». Ctrl+Z / Build после.

#### E-35 Схема `appsettings.json`
1. Открыть `Console/Editor/AppSettingsSchema.cs` (маркеры и EXPECT) и рядом `Console/appsettings.json` — набирать в JSON.
2. `appsettings-keys`: в `"Shop"` Ctrl+Space → `Name`, `Mode`, `Timeout`, `Tags`, `Prices`, `Retry`, `Owner` с описаниями; нет `Secret`, `Total`.
3. `-enum`: `"Mode":` → `"Fast"`, `"Cheap"`, `"Balanced"`, `"Slow"` подсвечен; `-wrong-type`: `"Height": "tall"` — предупреждение, `5` и `"5"` — нет.
4. `-timespan`: `"soon"` — предупреждение, `"00:00:30"` — нет; `-unknown-key`: `"Colour"` в `"Position"` — слабое предупреждение.
5. `-dictionary`: в `"Prices"` ключи не предлагаются, `"x"` подсвечен; `-marker`: `"Cache"` → `"Redis"` → `SizeMb`, `Endpoints`.
6. `-base`: `Logging`, `Kestrel`, `AllowedHosts`, `ConnectionStrings` рядом с секциями кода — с включёнными и выключенными Remote JSON Schemas.
7. `-code-change`: новое свойство в `ShopOptions` (без сохранения `.cs`) через секунду в дополнении. Откатить JSON и `.cs`.

#### E-36 `ConfigurationSchema.json` пакетов
1. Нужен проект с пакетом, несущим `ConfigurationSchema.json` (например `Aspire.*` компонент) — в площадке такого нет.
2. После restore в `appsettings.json` проекта Ctrl+Space в корне — секции пакета (напр. `Aspire`) с описаниями.

#### E-37 Аллокации: переключатель
1. Ничего не запущено. Меню .NET → **Show Allocations in Editor** → уведомление «No .NET program is running. Start one with Run or Debug…».
2. Run профиля `Console: Allocations` при включённом переключателе → через несколько секунд «Listening to Console: Allocations (pid)…»;
   первый раз в `idea.log` — «AllocWatch is built for net…».
3. В `idea.log` по слову `Allocations` — весь путь: переключатель, программа, командная строка помощника, «listens to the process …».

#### E-38 Аллокации: числа и полоса
1. Сначала Build. Run `Console: Allocations`, переключатель включён, открыть `Console/Allocations.cs`.
2. Через 1–3 с: у `ALLOC:buffers` — около `19 MB/s, 18K obj/s, byte[] 95%` цветом; у `ALLOC:strings` — около `900 KB/s … string 4%` серым.
3. `numbers.Add(i)` — `int[]`, `ALLOC:boxing` — `int` (могут мигать — выборка); у `Numbers()` — `allocates …`, у `Buffer()` суммы нет.
4. Полоса слева, наведение — типы с долями и метод. Числа обновляются раз в секунду; две пустые строки в начале файла — числа остаются у своих строк.
5. Stop — числа исчезают; Run — появляются; выключить переключатель — исчезают, программа печатает `round N` дальше.

#### E-39 Аллокации: Debug, `Web`, цена
1. Debug того же профиля — числа есть и под отладчиком.
2. `Web` (Run) + запросы к его адресу — числа у обработчиков в `Web/Program.cs`.
3. Конфигурация `Console` с аргументом `allocations-fast`: программа печатает круги/с; с включённым переключателем их меньше примерно на четверть.

#### E-40 Run C# File with dotnet
1. SDK 10. Создать `debug-playground/hello.cs` (вне проектов) с `System.Console.WriteLine("hi");`.
2. ПКМ в редакторе → **Run C# File with dotnet** → консоль с `hi`; у долгой программы Stop работает. Удалить файл.

---

## Find Usages / навигация

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| F-01 | 0.1.37 | Find Usages поля по видам | `FindUsages.cs`, `TYPE:find-usages-field` | 🤖 (числа) |
| F-02 | 0.1.37 | Group by File Structure | `TYPE:find-usages-field` | ⬜ |
| F-03 | 0.1.37 | Merge Usages on the Same Line | `TYPE:find-usages-field` | ⬜ |
| F-04 | 0.1.37 | Метод + Group by Module | `TYPE:find-usages-method` | 🤖 (числа) |
| F-05 | 0.1.37 | Тип и атрибут | `TYPE:find-usages-type` / `-attribute` | 🤖 |
| F-06 | 0.1.37 | Выключение группировок | `FindUsages.cs` | ⬜ |
| F-07 | 0.1.44 | Go to Symbol / Class: вид строк | `GoToBase.cs`, `TYPE:go-to-symbol` | ⬜ |
| F-08 | 0.1.25 | Go to Base (Ctrl+U) на членах | `GoToBase.cs`, `TYPE:go-to-base-*` | ⬜ |
| F-09 | 2026-09-29 | Ctrl+U на типе, Type / Call Hierarchy | `TYPE:go-to-base-type`; Hierarchy — без маркера | 🤖 |
| F-10 | 2026-09-21 | Go to Implementation: выбор мышью | нет сценария | ⬜ |
| F-11 | 2026-09-21 | Shift+F6 на классе переименовывает файл; Ctrl+Z | нет сценария | ⬜ |
| F-12 | 2026-09-21 | Диалог / inline Rename | нет сценария | ⬜ |
| F-13 | 2026-09-21 | Ctrl+наведение | нет сценария | ⬜ |

Для F-01…F-06: сервер готов, открыт `Console/Editor/FindUsages.cs` (+ `Lib/UsageLog.cs`), группировки — шестерёнка / View Options окна Find.

#### F-01 Поле `Counter`
1. Курсор на `Counter` под `TYPE:find-usages-field`, **Alt+F7**, Group by Usage Type включён.
2. 13: «Read access» 3, «Write access» 7 (`=`, `+=`, `++`, `--`, `ref`, `out`, конструктор), «Usage in nameof» 2, «Declaration» 1.
3. Группы «Unclassified» быть **не должно**. Проверить внешний вид дерева (иконки, подписи групп).

#### F-02 Group by File Structure
1. Результат F-01, включить Group by File Structure.
2. `UsageSample` → `UsageSample()` / `Read()` / `Write()` / `ByRef()` / `Name()`; `nameof` в атрибуте — прямо под `UsageSample`.

#### F-03 Merge Usages on the Same Line
1. Результат F-01, включить Merge Usages on the Same Line.
2. Два чтения в `Read()` — одна строка; выключить — снова две.

#### F-04 Метод `Record`
1. Курсор на `Record` под `TYPE:find-usages-method`, Alt+F7.
2. «Invocation» 5, «Declaration» 1 (в `Lib`), «Usage in documentation» 1 (`<see cref>`).
3. Group by Module: проекты `Console` (3) и `Lib` (4). Group by File Structure: `RecordTwice` — две строки.

#### F-05 Тип и атрибут
1. `TYPE:find-usages-type`: курсор на `UsageSample` в заголовке класса, Alt+F7 → 9: «Declaration» 2, «Usage in base type list», «New instance
   creation», «Usage in typeof», «Type check (is / as)», «Usage in declaration type», «Usage in type argument», «Usage in nameof».
2. `TYPE:find-usages-attribute`: на `UsageNoteAttribute` → «Usage in attribute» 1 и «Declaration».

#### F-06 Выключение группировок
1. По очереди выключить Group by Usage Type, Module, File Structure.
2. Соответствующий уровень дерева пропадает, остальное — как в Java / Kotlin файле той же IDE (сравнить с любым `.kt`/`.java`, если есть).

#### F-07 `TYPE:go-to-symbol`
1. До готовности сервера (сразу после открытия площадки) **Ctrl+Alt+Shift+N**, набрать `area`.
2. Строки `Area()` с типом серым: `IBaseShape`, `BaseShape`, `MiddleShape`, `GoToBase`, `GoToBase.Nested`; справа `GoToBase.cs`. Не четыре одинаковых «Area GoToBase.cs».
3. Повторить после «Roslyn: DebugPlayground.sln» — строки те же.
4. **Ctrl+N** `nested` → `Nested` с серым `Playground.Editor.GoToBase`.

#### F-08 Go to Base на членах
1. Сервер готов, `Console/Editor/GoToBase.cs`, курсор где сказано, **Ctrl+U**.
2. `go-to-base-member` (`Area` у `override`) → сразу в `Area` класса `MiddleShape`, без списка.
3. `go-to-base-property` (`Name`) → `Name` в `BaseShape`.
4. `go-to-base-body` (курсор на `notify` в теле `Rename`) → список «Choose Base Symbol of Rename»: `Rename(string name, bool notify)` в `BaseShape` и `INamedShape`; перегрузки с одним параметром **нет**.
5. `go-to-base-metadata` (`Dispose`) → декомпилированный `IDisposable` или подсказка «No base symbols of Dispose found…», без исключения.
6. `go-to-base-none` (`Own`) → подсказка «No base symbols of Own found».

#### F-09 Ctrl+U на типе и Hierarchy
1. `go-to-base-type`: курсор на `GoToBase` в заголовке класса, Ctrl+U → список `MiddleShape`, `IDisposable`.
2. Курсор на `MiddleShape`, **Ctrl+H** → окно Hierarchy: база, наследники; переключить на Supertypes / Subtypes.
3. Курсор на методе `Record` в `Lib/UsageLog.cs`, **Ctrl+Alt+H** → Callers; переключить на Callees. Клик по узлу открывает код.

#### F-10 Go to Implementation мышью
1. Сервер готов. Курсор на `IBaseShape` (или его методе `Area`) в `GoToBase.cs`, **Ctrl+Alt+B**.
2. Список «имя: тип in Контейнер (файл:строка)»; **кликнуть мышью** по второй строке — переход туда (робот этот клик не умеет).

#### F-11 Shift+F6 переименовывает файл
1. Сервер готов. В `Console/Types.cs` (или другом файле, где имя файла = имя типа) курсор на имени типа, **Shift+F6**, новое имя, Enter.
2. Файл переименован вместе с типом, ссылки поправлены, ошибок нет.
3. **Ctrl+Z**: откатываются текст и имя файла (ожидаемо двумя шагами, не одним — отметить, как на деле). Проверить, что всё вернулось.

#### F-12 Rename
1. На локальной переменной и на методе — Shift+F6: inline rename (рамка на имени) или диалог; ввести имя, Enter.
2. Все вхождения переименованы сервером; на объявлении нет заглушки «needs a language server». Ctrl+Z.

#### F-13 Ctrl+наведение
1. Сервер готов. Зажать **Ctrl** и навести мышь на вызов метода из другого файла.
2. Имя подчёркнуто, курсор — «рука», клик переходит к объявлению. На самом объявлении подчёркивания нет.

---

## Отладчик

Этапы 1–6 и слой 5 проверены 2026-09-21/22 (`ROADMAP.md`: свой DAP-клиент «проверено вживую 2026-09-22»), но галочки в
`debug-playground/README.md` не проставлены. Для всех строк: сначала Build, профиль выбирается в Run widget (`Console: All` и т. п.),
`BP:` — точка на строке маркера в `Console/Scenarios.cs`, если не сказано иное.

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| D-01 | 0.1.0 | Запуск, остановка, шаги, Stop | `BP:main`, `BP:stepping` | ✅ (по ROADMAP) |
| D-02 | 0.1.0 | Где ставятся точки; лямбда, итератор, свойство, `Lib` | `BP:lambda` / `iterator` / `property` / `lib` / `lib-expression-body` | ✅ (по ROADMAP) |
| D-03 | 0.1.0 | Debug проекта с ошибками компиляции | `Broken` | ⬜ |
| D-04 | 0.1.0 | Потоки при нескольких запусках; логи адаптера | `Console: Threads`, `BP:worker` / `BP:parallel` | ⬜ |
| D-05 | 0.1.0 | Web-профили, окружение, аргументы | `Web: *`, `BP:web-handler`, `BP:environment` | ✅ (этап 2) |
| D-06 | 0.1.0 | Значения, Evaluate / Watches, коллекции, строки | `BP:variables` / `collections` / `strings` | ✅ (этап 2) |
| D-07 | 0.1.0 | Hover над переменной | `BP:variables` | ⬜ |
| D-08 | 0.1.1 | «View» у длинных строк, окно по клику | `BP:view-text` | 🤖 |
| D-09 | 0.1.1 | Временные и зависимые точки | `BP:dependent-master` / `-slave` | 🤖 |
| D-10 | 0.1.0 | Дорогие свойства, Run to Cursor, Evil, Wait, MultiTarget, вывод | `BP:expensive` и др. | ✅ (этап 2) |
| D-11 | 0.1.0 | F7 во внешний код | `Exceptions` → `int.Parse` | ⬜ |
| D-12 | 0.1.0 | Точки на исключениях, Set Value | `BP:exception`, `BP:web-exception`, `BP:setvalue` | 🤖 |
| D-13 | 0.1.0 | Правка свойств точки исключений во время сессии | View Breakpoints | ⬜ |
| D-14 | 0.1.0 | Hit count, logpoint, Condition | `BP:setvalue` | 🤖 |
| D-15 | 0.1.0 | Правка hit count во время сессии; после перезапуска IDE | `BP:setvalue` | ⬜ |
| D-16 | 0.1.0 | Completion в Evaluate / Watches / Condition | `BP:variables` | 🤖 |
| D-17 | 0.1.0 | Диалог Attach to Process | `Console: Wait` | ⬜ |
| D-18 | 0.1.0 | Отладка тестов; окно Unit Tests → Debug Selected | `BP:test`, `BP:theory` | 🤖 (кроме окна) |
| D-19 | 0.1.0 | Ввод с консоли, async-стек, Set Next Statement | `BP:input`, `BP:async-inner`, `BP:variables` | 🤖 |
| D-20 | 2026-09-22 | Debug MTP-тестов — уведомление | нет сценария | ⬜ |
| D-21 | 0.1.0 | Monitor: программа под отладчиком в списке | нет маркера | ⬜ |
| D-22 | 0.1.0 | Memory Dump: Who Holds It, SOS, Close | `Console: Leak` | 🤖 |
| D-23 | 0.1.0 | Save Dump As, двойной клик по полю | `Console: Leak` | ⬜ |
| D-24 | 0.1.29 | Retained sizes, Dominators, отмена | `Console: Leak` | ⬜ |

#### D-01 Запуск и шаги
1. Новая конфигурация `Console: All` → Edit Configurations: в Before launch есть «Build .NET Project».
2. Точка на `BP:main`, Debug → остановка, выбран Main Thread, кадр `Program.<Main>$`.
3. На `BP:stepping`: F8 / F7 / Shift+F8, Resume; вывод в консоли. Stop → в диспетчере задач нет `dotnet-debugger`.

#### D-02 Где ставятся точки
1. В `Scenarios.cs`, `Program.cs` кликнуть по полю на `using`, комментарии, заголовке метода — точка **не** ставится; на строке кода — ставится.
2. Точки на `BP:lambda`, `BP:iterator`, `BP:property`, `BP:lib`, `BP:lib-expression-body` (`Lib`) — каждая срабатывает.

#### D-03 `Broken`
1. Run | Edit Configurations → + «.NET Project», проект `debug-playground/Broken/Broken.csproj`.
2. Debug → окно Build с ошибками CS0029 / CS0103, они же в редакторе; сессия отладки **не** остаётся открытой.

#### D-04 Потоки и логи
1. Профиль `Console: Threads`, точки `BP:worker` и `BP:parallel`, Debug несколько раз подряд.
2. Всегда выбран остановившийся поток (на `BP:worker` — «Playground worker»).
3. Меню .NET → Show Debugger Logs → после сессии есть `adapter-*.log`; Trace Debugger Protocol → файлы в `protocol`.

#### D-05 Web и окружение
1. `Web: http`, `BP:web-handler` → браузер сам на `/orders/3`, остановка; страница `/` — окружение, URL, переменная профиля.
2. `Web: https`; `Web: no browser` — браузер не открывается, Production. Environment = Staging в конфигурации `Web` → `/` показывает Staging.
3. `Console: Launch`, `BP:environment`: аргументы `environment output`, свои аргументы заменяют их, `PLAYGROUND_FROM_PROFILE=yes`, переменная из таблицы конфигурации.

#### D-06 Значения и Evaluate
1. `BP:variables`: значения всех видов; Evaluate (Alt+F8) `person.Friend.Name`, `number * 2`, `access.HasFlag(Access.Write)`; `nope.x` — ошибка текстом адаптера.
2. `BP:collections`: `huge` и `hugeArray` раскрываются порциями, IDE не виснет. `BP:strings`: у `longText` ошибка вместо значения, сессия жива.

#### D-07 Hover
1. На `BP:variables` навести мышь на `number` — всплывает значение; на `person` — раскрывается; на `person.Friend.Name` (выделить) — значение.
2. На вызове метода и внутри строкового литерала — ничего не всплывает.

#### D-08 «View» у строк
1. `BP:view-text`, Debug. В Variables у длинных строк ссылка «View».
2. **Кликнуть «View»** мышью: окно с вкладками JSON (`order`), XML (`feed`), HTML (`page`), JWT (`token`); у `multiline` — текст с переносами.
3. Hover над `order` в редакторе — то же окно. У `word` ссылки нет.

#### D-09 Зависимые точки
1. `BP:dependent-master`: ПКМ по точке → More → «Remove once hit». `BP:dependent-slave` → «Disable until hitting the following breakpoint» = master.
2. Debug: первая остановка — master при `round == 2`, точка исчезает; следующая — slave при `round == 3`.

#### D-10 Остальное этапа 2
1. `BP:expensive`: `slow` описывается ~2 с, остальное дерево работает; выключить Settings | .NET | Debugger «Allow property evaluations…» → значения без вызова кода.
2. Run to Cursor (Alt+F9) из `BP:stepping` на строку с `Console.WriteLine`.
3. `Console: Evil`, `BP:evil`: раскрыть `evil`, Stop — за секунды, в `idea.log` нет `Cannot send Ctrl+C`. `Console: Wait`: Pause → кадры в `Wait`, Stop.
4. `MultiTarget`, `BP:multitarget`: `framework` = выбор в тулбаре. `BP:output`: stderr виден, кириллица (известная проблема адаптера на Windows — отметить, как выглядит).

#### D-11 F7 во внешний код
1. Settings | .NET | Debugger → включить «Enable external source debug».
2. Конфигурация `Console` с аргументом `exceptions`, точка на строке `int.Parse("not a number")` в `Exceptions()` (`Scenarios.cs`), Debug.
3. **F7** → шаг в исходник фреймворка (`Int32.Parse` / `Number.Parsing`). Без галочки — F7 не уходит во внешний код.

#### D-12 Исключения и Set Value
1. Run | View Breakpoints: группа «.NET Exception Breakpoints», включена «Any exception (user-unhandled, unhandled)»; «+» добавляет «thrown» с полем типов.
2. Точка «thrown» с типом `Playground.Lib.ShopException` → `Console: All` стоит на `BP:exception`, на `int.Parse` — нет; Evaluate `$exception`, `e.Code`.
3. `Web` → `/fail` (`BP:web-exception`) — остановка как user-unhandled. `Console: Crash` — необработанное останавливает.
4. `BP:setvalue`: **F2** на `counter` (100), `label` ("after"), `person.Age` — программа печатает новые; `abc` в `counter` — ошибка, сессия жива. Set Value в Watches и у `small[0]`.

#### D-13 Свойства точки исключений во время сессии
1. Сессия `Console: All` идёт. View Breakpoints → у точки «thrown» поменять тип или галочки «Break when».
2. Действует сразу, без перезапуска сессии. Выключить точку по умолчанию → `/fail` у `Web` отвечает 500 без остановки.

#### D-14 Hit count, logpoint, Condition
1. `BP:setvalue`, свойства точки (ПКМ) Hit count = `3` → остановка на `i = 2`, одна за запуск.
2. Строка `Console.WriteLine($"{greeting} {length}")`: Log message = `greeting = {greeting}, length = {length}`, без остановки → строка в консоли.
3. Condition `i == 1`; hit count `abc` подсвечивается и не сохраняется.

#### D-15 Hit count во время сессии; после перезапуска
1. Сессия стоит на `BP:setvalue`; поменять Hit count на `2` → действует без перезапуска.
2. Перезапустить IDE → свойства точек (hit count, condition, log message) на месте.

#### D-16 Completion в выражениях
1. `BP:variables`, Evaluate: Ctrl+Space в пустом поле → локальные; `per` → `person`; `person.` → `Name`, `Age`, `Friend`; `person.Friend.N`.
2. После `Greet(person).` — ничего. То же в Watches и в поле Condition; в поле подсветка C#.

#### D-17 Attach to Process
1. `Console: Wait` через **Run**. Run | Attach to Process → группа «.NET»: есть `Playground.Console`; нет `dotnet-debugger` и уже отлаживаемых.
2. Подключиться, точка в цикле `Wait` срабатывает. Stop отсоединяет — программа продолжает печатать `tick` (и если Stop нажат на точке).

#### D-18 Отладка тестов
1. `Tests/PricingTests.cs`: Debug у ▶ теста → остановка на `BP:test` без промежуточной во внешнем коде; F7 в `Lib`; Resume — тест зелёный, сессия закрылась.
2. `BP:theory` — остановка на каждую строку `InlineData`.
3. **Окно Unit Tests** → выделить тест → «Debug Selected Tests» → остановка на `BP:test`.

#### D-19 Ввод, async-стек, Set Next Statement
1. `Console: Input`: в консоли отладки `Your name: `, ввести `Ада` + Enter → `BP:input`, `name = "Ада"`, `Length = 3`; Resume → `Hello, Ада! (3 chars)`.
2. `Console: Threads`, `BP:async-inner`: кадры `Compute()`, `[External Code]`, «Async Call Stack», под ним `Async()`, `Run()`, `Program.<Main>$`.
3. `BP:variables`: **ПКМ в редакторе** на строке `long big = …` → Set Next Statement (посмотреть пункт меню на глаз); на строке другого метода — «Cannot set the next statement».

#### D-20 MTP-тесты
1. Нужен проект на Microsoft.Testing.Platform (например `dotnet new mstest` с `EnableMSTestRunner`) — в площадке нет.
2. Debug у ▶ теста → понятное уведомление, что отладки MTP-тестов нет; IDE не зависает.

#### D-21 Monitor и отладка
1. Debug `Console: Wait`. Открыть окно .NET Monitor.
2. Отлаживаемая программа в списке процессов (pid из события адаптера), графики идут.

#### D-22 Memory Dump
1. `Console: Leak` через Run. .NET Monitor → Memory Dump → диалог «Memory of …» за секунды.
2. Фильтр `PriceWatcher` → объекты; Who Holds It: strong handle → `System.Object[]` → `EventHandler<Decimal>` → … → `PriceWatcher`.
3. SOS Console: `dumpheap -stat -type Playground`, `syncblk`, `clrstack -all`; неизвестная команда — ошибка SOS. Close → в `<tmp IDE>/dotnet-dumps` файла нет.

#### D-23 Save Dump As и поля
1. В диалоге дампа (D-22) **Save Dump As…** → `.dmp` сохранён.
2. `CachedOrder` → вкладка Fields: **двойной клик** по `<Name>k__BackingField` открывает строку, Back возвращает.

#### D-24 Retained и Dominators
1. После открытия дампа — фоновая задача «Computing retained sizes of …», затем строка «Retained sizes of N live objects, computed in X s».
2. Сортировка по Retained: сверху `System.Object[]`, `Dictionary<Int32, CachedOrder>`, `Entry[]`, `CachedOrder` (Retained ≫ Bytes); у `Byte[]` Retained = Bytes.
3. Вкладка Dominators: `System.Object[]` (StrongHandle) сверху, раскрытие до `CachedOrder` → `byte[]`; узел «N more, X».
4. Close во время расчёта → расчёт отменён, файл удалён; отмена задачи → «Retained sizes: cancelled».

---

## Сборка, запуск, тесты

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| B-01 | 0.1.36 / 0.1.39 | Результаты тестов по ходу прогона | `Tests/LiveResultsTests.cs`, `LIVE:slow` | ⬜ |
| B-02 | 0.1.36 / 0.1.39 | Упавший, пропущенный, вывод теста | `LIVE:fail` / `skip` / `output` | ⬜ |
| B-03 | 0.1.36 / 0.1.39 | Stop, Rerun Failed, Debug и Coverage, логгер | `LIVE:stop`, `BP:test` | ⬜ |
| B-04 | 0.1.36 | MTP-проект — дерево из TRX в конце | нет сценария | ⬜ |
| B-05 | 2026-09-29 | Microsoft.Testing.Platform: запуск и фильтры | нет сценария | ⬜ |
| B-06 | 0.1.38 | Aspire: иконка, конфигурация, dashboard | `AspireHost` | 🤖 |
| B-07 | 0.1.38 | Aspire: Debug подключается к `Web`, Restart, Stop | `BP:aspire-service`, `BP:aspire-apphost` | 🤖 |
| B-08 | 0.1.35 | Publish: диалог, framework-dependent, single file | README «Publish» | ⬜ |
| B-09 | 0.1.35 | Publish: профили `.pubxml`, Save as Profile | README «Publish» | ⬜ |
| B-10 | 0.1.35 | Publish: run configuration, память, контейнер | README «Publish» | ⬜ |
| B-11 | 0.1.28 | `TargetPath` через MsBuildHost при своём `OutputPath` | README «MSBuild-вычисление» | ⬜ |
| B-12 | 0.1.28 | Старт MsBuildHost; MultiTarget; SDK 8 | README «MSBuild-вычисление» | ⬜ |
| B-13 | 0.1.26 | Hot Reload: Building → Watching → Changes applied | `Web/HotReload.cs`, `TYPE:hot-reload-start` / `-apply` | ⬜ |
| B-14 | 0.1.26 | Hot Reload: ошибка, rude edit, Restart, Stop | `TYPE:hot-reload-error` / `-rude` | ⬜ |
| B-15 | 0.1.22 | Run / Debug 2 Projects | README «Запуск нескольких проектов» | ⬜ |
| B-16 | 0.1.22 | Compound; падение сборки одного проекта | README «Запуск нескольких проектов» | ⬜ |
| B-17 | 2026-09-29 | Services: ссылка на адрес у Run и Debug | `Web: http` | ⬜ |
| B-18 | 0.1.30 / 0.1.39 | New Solution до конца, кнопка на Welcome, SDK | нет сценария | ⬜ |
| B-19 | 2026-09-28 | New Project в IDEA (мастер) и в GoLand | нет сценария | ⬜ |
| B-20 | 2026-09-29 | Параметры шаблона, прокрутка | нет сценария | ⬜ |
| B-21 | 0.1.2 | Code Metrics | нет сценария | ⬜ |
| B-22 | 0.1.7 | HTTPS dev-сертификат | нет сценария | ⬜ |
| B-23 | 0.1.9 | Insert Development JWT | нет сценария | ⬜ |
| E-150 | 0.1.79 | Run 2 Projects / Run compound: одна сборка `Web+Worker.slnf`, оба `dotnet run --no-build` | README «Запуск нескольких проектов и Compound», `Web` + `Worker` | 🤖 робот (Windows, 2026-10-05): одна сборка 2,3–2,7 с с чистого, оба `--no-build` — через compound, Run 2 Projects и Run группы в Services |
| E-151 | 0.1.79 | Save as Compound Configuration: из уведомления, из ПКМ Solution view, из меню Run (запущенные) | там же | 🤖 робот: из уведомления (действие вызвано скриптом, не кликом); ПКМ и меню Run — только тестом |
| E-152 | 0.1.79 | Wait for: listens / health URL / таймаут, ошибки «ждёт сам себя» и цикла | там же, `Worker` → `Web: http` | 🤖 робот: listens (ждал 1,7 с, первый раунд `Web answered`), таймаут 60 с с уведомлением; health URL и ошибки редактора — только тестом |
| E-153 | 0.1.79 | Services: группа `Web + Worker`, Stop / Rerun группы, адрес у `Web` | там же | 🤖 робот: группа и адрес на снимке, `RunDashboard.Run` / `Stop` на узле группы запускают (одной сборкой) и останавливают оба |
| E-154 | 0.1.79 | Debug compound: одна сборка, две сессии, `BP:worker-round`, `BP:lib`; падение сборки не запускает ничего | там же | 🤖 робот: одна сборка, две сессии, остановка на `BP:worker-round`; при ошибке компиляции ни одного процесса, уведомление «Build Failed» |
| E-160 | 0.1.80 | Поток nullable: CS8602 по пути (`name`, `node.Next`), нет предупреждения после проверок, `is not null`, `is { }`, `??`, `?.`, `!` | `Console/Editor/NullableFlow.cs`, `TYPE:nullable-deref`, `TYPE:nullable-tests` | 🤖 робот (Windows, 2026-10-05): подсветка файла — те же 7 предупреждений, что у `dotnet build`; набранные `node.Next.Name` / `node.Next.Text` → CS8602 на `node.Next` |
| E-161 | 0.1.80 | CS8600 / CS8601 / CS8625 / CS8604 / CS8603 по состоянию значения, текст CS8604 с сигнатурой | там же, `TYPE:nullable-assign`, `TYPE:nullable-return` | 🤖 робот (Windows, 2026-10-05): CS8600 / CS8625 / CS8601 / CS8603 в файле, набранные `Take(_cache);` → CS8604 с сигнатурой, `Take(null);` → CS8625 |
| E-162 | 0.1.80 | Атрибуты nullable из индекса сборок и своего кода (`IsNullOrEmpty`, `TryGetValue`, `ThrowIfNull`, `[NotNullWhen]`, `[NotNull]`), циклы | там же, `TYPE:nullable-attributes`, `TYPE:nullable-loops` | 🤖 робот (Windows, 2026-10-05): в `Attributes` пусто, `!TryGetValue(…, out var other)` + `other.Length` → CS8602; CS8602 на `last` после цикла |
| E-163 | 0.1.80 | CS8618 на конструкторе (поле задано не на всех путях), `[MemberNotNull]`, `: this(...)`, `required`, `[SetsRequiredMembers]` | там же, `TYPE:nullable-ctor` | 🤖 робот (Windows, 2026-10-05): CS8618 на `FlowOwner` первого конструктора, на остальных нет |
| E-164 | 0.1.80 | Запросы LINQ по `IQueryable` / `DbSet`-подобному источнику: типы `var` и range-переменных, completion в запросе и в лямбде `Expression<Func<>>` | `Console/Editor/Queries.cs`, `TYPE:queries-queryable`, `-dbset`, `-method-syntax` | 🤖 робот (Windows, 2026-10-05): Ctrl+Q: `IQueryable<string> expensive`, `(range variable) Order o`, `IOrderedQueryable<Customer> names`, `IQueryable<int> ids`; ` && o.` → `CustomerId`, `Id`, `Name`, `Total`; `where c.Nmae` → CS1061; в файле ошибок нет |
| E-165 | 0.1.80 | `join` / `join … into` / `group … into` и свои источники (методы экземпляра, extension `Select`) | там же, `TYPE:queries-join`, `-own-source`, `-group` | 🤖 робот (Windows, 2026-10-05): Ctrl+Q на `g` → `(range variable) IGrouping<int, Order> g`; join и свои источники — только тестом и гейтом |
| E-170 | 0.1.81 | Extract Method в циклах: переменная следующей итерации возвращается, `break` / `continue` / `return;` — `if (NewMethod(…)) break;`, запись не на всех путях — параметром | `ExtractMethod.cs`, `TYPE:extract-loop-*`, `extract-return-void`, `extract-refused` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): LoopCarried, LoopSum, LoopFound, ReturnEarly — текст как в EXPECT; подсказку отказа робот не снял |
| E-171 | 0.1.81 | Introduce Parameter (Ctrl+Alt+P, Refactor This): вызовы по solution получают аргумент, параметры выражения заменены аргументами вызова; необязательный параметр со значением по умолчанию; имя в рамке | `IntroduceParameter.cs`, `TYPE:introduce-parameter*` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): попап из двух строк, `Area(3, 10)` / `s.Area(5, 10)`, необязательный `int value = 10`, `Twice(4, 4 * 2)`, рамка имени; отказы текст не меняют, подсказку робот не снял |
| E-172 | 0.1.81 | Introduce Field: имя в рамке у поля и у использований | `ExtractMethod.cs`, `TYPE:introduce-field` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): поле `_concat` после `_factor`, рамка на 2 местах |
| E-173 | 0.1.81 | «Convert '?:' to 'if' statement» на `?:` в аргументе / значении локальной: оператор в обеих ветках, локальная объявлена до `if` | `ContextActions.cs`, `TYPE:ctx-conditional-split` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): Print и Label — как в EXPECT; в Lazy действия нет |
| E-174 | 0.1.81 | «Introduce variable»: выбор «Replace this occurrence only» / «Replace all N occurrences» с подсветкой | `ContextActions.cs`, `TYPE:ctx-introduce` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): выбор из двух строк, «Replace all 2 occurrences» — `count` в обоих местах, рамка на 3 местах; подсветку при наведении не видел |
| E-175 | 0.1.81 | Generate: Delegating members (сначала поле, потом члены), Equality comparer, Relational members, Relational comparer — в порядке Rider | `Generate.cs`, `TYPE:gen-delegating`, `gen-equality-comparer`, `gen-relational*`, `gen-list` | 🤖 робот (Windows, IC 2026.1.4, 2026-10-05): Delegate To → `_items` → члены `List<int>` без членов `object`; `Add` / `Count`; Equality comparer, Relational members, Relational comparer — как в EXPECT; порядок строк Alt+Insert — только тестом |

#### B-01 Результаты по ходу
1. `Tests/LiveResultsTests.cs`, ▶ у класса `LiveResultsTests` → Run (первый раз — окно «Preparing live test results», секунды).
2. В окне Unit Tests узел класса сразу, тесты — с крутилкой и зеленеют **по одному, с интервалом ~1 с** (`LIVE:slow`), а не все в конце.
3. У каждого длительность, у `Slow3` — 3 s. Задержка до ~1 с допустима.

#### B-02 Упавший, пропущенный, вывод
1. `Fails` (`LIVE:fail`) красный: `Assert.Equal() Failure: Values differ`, стек со ссылкой `LiveResultsTests.cs:line N`, клик открывает строку.
2. `Skipped` (`LIVE:skip`) серый с причиной «Shows the reason».
3. `WritesOutput` (`LIVE:output`): две строки вывода только у этого теста; сводка `dotnet test` (результаты, путь к TRX) — у корня прогона, не у теста (0.1.39).

#### B-03 Stop, Rerun, Debug, Coverage
1. Run класса, нажать Stop, пока идёт `Slow3` (`LIVE:stop`) → `Slow3` прерван (не зелёный), завершённые тесты **не** помечены прерванными (0.1.39).
2. Rerun Failed Tests — запускается только `Fails`.
3. Debug у `TotalAppliesTheDiscount` (`BP:test`) — остановка; Run with Coverage — покрытие `Lib` в редакторе.
4. Plugin Logs, категория `helpers`: `DotNetSupport.TestLogger is built for netstandard2.0`.

#### B-04 MTP — TRX в конце
1. Создать рядом проект `dotnet new mstest` с `<EnableMSTestRunner>true</EnableMSTestRunner>` (или xunit.v3 / TUnit), добавить в solution.
2. Run тестов → дерево появляется в конце прогона из TRX, без ошибок. Удалить проект.

#### B-05 MTP: режимы и фильтры
1. На том же проекте ▶ у одного теста → запускается только он (в журнале `commands` — `--filter` / `--filter-method` / `--treenode-filter` по фреймворку).
2. С `global.json` `"test": { "runner": "Microsoft.Testing.Platform" }` (SDK 10) — команда с `--project`. Run with Coverage — `--coverage`.

#### B-06 Aspire: распознавание и dashboard
1. Solution view: у `AspireHost` своя иконка, ПКМ — Run / Debug.
2. Свежее открытие — `AspireHost: https` первая и выбрана. Run → в консоли ссылка с `/login?t=…`, клик открывает dashboard без токена;
   браузер открывается сам на ней; в Services ссылка **Open Dashboard**.

#### B-07 Aspire: Debug
1. Debug `AspireHost: https` → в консоли `Aspire: attaching the debugger to Web (<pid>)`, вкладка `Web (<pid>)`.
2. Адрес ресурса `web` + `/aspire` → остановка на `BP:aspire-service`. `BP:aspire-apphost` в `AspireHost/AppHost.cs` останавливает AppHost.
3. Restart ресурса `web` в dashboard → новая вкладка с новым pid; Stop AppHost → нет `dcp.exe` / `Playground.Web.exe`.
4. Известно: `BP:web-start` под AppHost проскакивает — не ошибка шага 1.

#### B-08 Publish: диалог
1. ПКМ на `Lib` и `Tests` — пункта Publish… нет; на `Console`, `Web` — есть; в меню .NET — после «Measure Build Performance».
2. `Console` → Publish…: Release, `net9.0`, `Portable`; внизу команда `dotnet publish … -c Release -f net9.0 -o …\publish …`; Publish → окно Build,
   уведомление «Published 'Console'» с кнопкой открыть папку.
3. Target runtime `win-x64`, Self-Contained, single file → `-r win-x64 --self-contained true -p:PublishSingleFile=true`, в папке один `.exe` (~70 МБ).
4. Trim недоступен при Framework-Dependent; `win x64` в runtime → «Target runtime cannot contain spaces», Publish не публикует.

#### B-09 Publish: профили
1. Publish profile → `WinX64SingleFile` → поля из файла, в команде `-p:PublishProfile=WinX64SingleFile`.
2. `Web` → профиль `FolderProfile` → Target location `bin\Release\net9.0\publish-folder-profile\`, сайт кладётся туда.
3. **Save as Profile…** `Linux` → появился `Console/Properties/PublishProfiles/Linux.pubxml`, в списке после переоткрытия. Удалить файл.

#### B-10 Publish: run configuration, память, контейнер
1. Галочка «Save as run configuration» → после Publish конфигурация «Publish Console» (тип «.NET Publish»); Run — публикация в окне Build.
2. Повторное открытие диалога для `Console` и `Web` — последние значения у каждого свои.
3. С Docker / Podman: «Publish as a container image», `playground-console:dev` → `docker images` показывает образ. Удалить `bin\Release\…\publish*`.

#### B-11 `TargetPath` через MsBuildHost
1. Временно в `Console/Console.csproj`: `<OutputPath Condition="'$(Configuration)' == 'Debug'">bin\Custom\$(Configuration)\</OutputPath>` и
   `<AppendTargetFrameworkToOutputPath>false</AppendTargetFrameworkToOutputPath>`.
2. Debug `Console` с `BP:main` → остановка; Plugin Logs `run`: `TargetPath of Console.csproj (MsBuildHost): …\bin\Custom\Debug\Playground.Console.dll`,
   строки про `msbuild -getProperty:TargetPath` нет.
3. Откатить правку → Debug снова находит `bin\Debug\net9.0\Playground.Console.dll`.

#### B-12 MsBuildHost: старт, MultiTarget, SDK 8
1. Первый Debug после старта IDE: в журнале `msbuild` — `starting MsBuildHost` (самый первый раз ещё `MsBuildHost is built for net10.0` в `helpers`).
2. `MultiTarget`: Debug берёт сборку TFM тулбара (`net9.0` или `net10.0` в пути).
3. По возможности — машина / `global.json` с SDK 8: то же самое (SDK 8 не проверялся вовсе).

#### B-13 Hot Reload: применение
1. Конфигурация «.NET Project» для `Web`, Command = `dotnet watch`, **Run** (не Debug). Открыть `/hot-reload` в браузере.
2. `TYPE:hot-reload-start`: в Services «Hot Reload: Building», затем «Watching for changes»; ссылка на адрес рядом.
3. `TYPE:hot-reload-apply`: `"v1"` → `"v2"`, Ctrl+S → «C# and Razor changes applied in N ms.» цветом info, Services «Changes applied», `/hot-reload` отдаёт v2. Ctrl+Z, Ctrl+S.

#### B-14 Hot Reload: ошибки и Restart
1. `TYPE:hot-reload-error`: вместо `"v1"` — `undefinedThing`, Ctrl+S → красное «Unable to apply changes…», Services «Build failed». Откатить.
2. `TYPE:hot-reload-rude`: убрать `sealed`, Ctrl+S → оранжевое «Restart is needed…», Services «Restart needed» + ссылка Restart; «❔ Do you want to restart your app?» — ссылка.
3. Ссылки и кнопка «Restart dotnet watch» в тулбаре консоли перезапускают конфигурацию в той же вкладке. Stop → в Services состояния нет.

#### B-15 Run / Debug 2 Projects
1. Solution view: Ctrl+клик `Web` и `Console` → ПКМ: **Run 2 Projects** и **Debug 2 Projects** (с `Lib` или solution в выделении — их нет).
2. Run 2 Projects: в Build две сборки **по очереди**, без `MSB3026` / «being used by another process»; в Services две строки, у `Web` ссылка.
3. Debug 2 Projects: две сессии; точка в `Lib` останавливает обе программы.

#### B-16 Compound и ошибка сборки
1. Run | Edit Configurations → + Compound с `Web` и `Console`: Debug — сборки по очереди, обе сессии.
2. Испортить строку в `Console` → Run 2 Projects ничего не запускает, ошибка в Build. Откатить.

#### B-17 Ссылка в Services
1. Run `Web: http` → в окне Services у строки конфигурации ссылка «Now listening on» (адрес), клик открывает браузер.
2. То же для Debug `Web: http`.

#### B-18 New Solution
1. Welcome screen: кнопка **New Solution** сразу после New Project (в IDEA и GoLand).
2. File | New → Solution…: шаблон Console, framework, SDK (пин в `global.json`), Git → Create: проект открывается, собирается, `global.json` с выбранной версией.
3. Длинное описание шаблона переносится, а не обрезается.

#### B-19 New Project в IDEA
1. IDEA: File | New | Project… → слева «.NET» (мастер платформы: имя / папка / Git + панель шаблона) → создать.
2. GoLand / PyCharm: New Project — «.NET» один раз, без дубля.

#### B-20 Параметры шаблона
1. New Project / Add New Project → шаблон с многими параметрами (например `webapi` или `blazor`).
2. Параметры — в области прокрутки (не выше 320 px), без горизонтальной прокрутки; диалог не больше 90% экрана, не сжимается при смене шаблона.
3. Поменять параметр → в `dotnet new` (журнал `commands`) уходит только отличное от умолчания.

#### B-21 Code Metrics
1. ПКМ на solution → **Calculate Code Metrics** (или меню .NET) — solution перед этим собран.
2. Окно «Code Metrics»: namespace / тип / член, колонки как в VS, цветная полоса у индекса; двойной клик — переход к коду.

#### B-22 HTTPS dev-сертификат
1. `dotnet dev-certs https --clean` (сертификат станет недоверенным), открыть площадку.
2. Уведомление с **Trust** (запускает `dotnet dev-certs https --trust`) и «Don't ask again»; после Don't ask again при переоткрытии не спрашивает.

#### B-23 Insert Development JWT
1. Создать `Web/test.http` (или открыть существующий `.http` проекта `Web`), курсор на строке под запросом.
2. ПКМ → **Insert Development JWT** → на строке `Authorization: Bearer …`. Удалить файл; `dotnet user-jwts list` в `Web` — токен там.

---

#### E-150 Одна сборка на запуск нескольких проектов
1. Открыть `debug-playground`, в Solution view выделить `Web` и `Worker` → ПКМ → **Run 2 Projects**.
2. В окне Build — одна сборка `Build Web+Worker.slnf`, `Lib` в её выводе один раз; нет `MSB3026` / «being used by another process».
3. В консолях `Web: http` и `Worker` командная строка с `--no-build`; `Worker` пишет `round N: … Web answered …`.
4. То же для Run compound `Web + Worker` из тулбара. В .NET → Plugin Logs, категория `run`: `build before the launch of …: filter of DebugPlayground.sln with 2 projects`.

#### E-151 Save as Compound Configuration
1. После Run 2 Projects — уведомление «Started Web, Worker» со ссылкой **Save as Compound Configuration** → в тулбаре выбран `Web + Worker`.
2. Run | Edit Configurations: Compound `Web + Worker` с обеими конфигурациями, они в папке `Web + Worker`. Повторный Run 2 Projects уведомления не даёт.
3. Удалить compound, выделить `Web` и `Worker` → ПКМ → Save as Compound Configuration → то же.
4. Запустить `Web: http` и `Worker` по отдельности, фокус в редакторе → Run | Save as Compound Configuration → compound из запущенных.

#### E-152 Wait for
1. `Worker` → Edit Configuration → Wait for = `Web: http`, «listens on its address» → Run compound: внизу «Waiting for 'Web: http' to listen on http://localhost:5187»,
   `Worker` стартует после `Now listening on`, с первого раунда `Web answered`, нет `Web is not up yet`.
2. «answers on the health URL», Health URL `/health` → то же.
3. Wait timeout 5 и запуск только `Worker` → через 5 с уведомление «'Worker' Was Not Started», процесса нет.
4. Wait for = `Worker` у самого `Worker` → ошибка в редакторе конфигурации; взаимное ожидание `Web: http` ↔ `Worker` → «wait for each other».

#### E-153 Services
1. При запущенном compound в Services строки `Web: http` и `Worker` в узле `Web + Worker` (группировка по папкам; если выключена — Group By → Folder).
2. У `Web: http` ссылка `http://localhost:5187`. Stop на узле группы останавливает оба, Rerun — запускает оба снова одной сборкой.

#### E-154 Debug compound и падение сборки
1. Точки `BP:worker-round` (`Worker/Program.cs`) и `BP:lib` (`Lib/Pricing.cs`, сработает при запросе `/orders/N`) → Debug `Web + Worker`.
2. Одна сборка `Build Web+Worker.slnf`, две сессии отладки, обе останавливаются.
3. Испортить строку в `Worker/Program.cs` → Run compound: ошибка в Build, уведомление «Build Failed … none of … was started», ни одного процесса; Ctrl+Z.


#### E-160 Поток nullable: разыменование и проверки
Сервер выключен (Settings | .NET | Language Server), сборки проиндексированы. Открыть `Console/Editor/NullableFlow.cs`.
1. `TYPE:nullable-deref`: жёлтое CS8602 только на первом `name` в `name.Length + name.Length`; в `if (name != null)` и после `if (name is null) return` — нет.
2. Набрать под маркером `Console.WriteLine(node.Next.Name);` → CS8602 на `node.Next`, не на `node`; Ctrl+Z.
3. `TYPE:nullable-tests`: в методе `Tests` нет ни одного предупреждения; `Console.WriteLine(node.Next.Text);` после строк с `if` → CS8602 на `node.Next`.

#### E-161 Присваивания, аргументы, возвраты
1. `TYPE:nullable-assign`: CS8600 на `_cache` в `string local = _cache;`, CS8625 на `null` в `_title = null;`, CS8601 на `_cache` в `_title = _cache;`.
2. Набрать `Take(_cache);` → CS8604 «Possible null reference argument for parameter 'value' in 'void NullableFlow.Take(string value)'»; `Take(null);` → CS8625.
3. `TYPE:nullable-return`: CS8603 только на первом `return _cache;`.

#### E-162 Атрибуты и циклы
1. `TYPE:nullable-attributes`: ни одного предупреждения в `Attributes`. Набрать `Console.WriteLine(found.Length);` в `else` → CS8602 на `found`.
2. `TYPE:nullable-loops`: CS8602 на `last` в `Console.WriteLine(last.Length);`, внутри `while` — нет.

#### E-163 CS8618 на конструкторе
1. `TYPE:nullable-ctor`: CS8618 «Non-nullable field 'Name' must contain a non-null value when exiting constructor…» на `FlowOwner` первого конструктора; на двух других — нет.
2. Убрать `if (named)` → предупреждение пропадает; Ctrl+Z.

#### E-164 LINQ по IQueryable
1. `Console/Editor/Queries.cs`, `TYPE:queries-queryable`: Ctrl+Q на `expensive` → `IQueryable<string>`, на `o` → `(range variable) Order o`; ` && o.` после `where o.Total > 1` → `Total`, `Name`, `CustomerId`.
2. `TYPE:queries-dbset`: `names` — `IOrderedQueryable<Customer>`; `c.Nmae` в `where` → красное CS1061.
3. `TYPE:queries-method-syntax`: `ids` — `IQueryable<int>`, `c` — `Customer`, `c.` → `Id`, `Name`.

#### E-165 join, group, свои источники
1. `TYPE:queries-join`: `bought` — `IEnumerable<Order>`, `total` — `decimal`, `pairs` — `IQueryable<string>`.
2. `TYPE:queries-own-source`: `boxed` — `QueryBox<string>`, `x` — `int`, `maybe` — `QueryMaybe<int>`, красного нет.
3. `TYPE:queries-group`: `g` — `IGrouping<int, Order>`, `groups` — `IQueryable<int>`, `g.` → `Key`, `Count`, `Sum`.

## NuGet

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| N-01 | 0.1.27 | Поиск через DotNetHelper, когда IDE не доходит до фида | нет сценария | ⬜ |
| N-02 | 0.1.24 | `packages.config` в Dependencies и окне NuGet | `NetFramework/LegacyConsole` | ⬜ |
| N-03 | 0.1.21 | Журнал фидов | нет сценария | ⬜ |
| N-04 | 0.1.8 | Consolidate Package Versions | нет сценария | ⬜ |
| N-05 | 0.1.5 | Why Is This Installed? | нет сценария | ⬜ |
| N-06 | 0.1.4 | Уязвимые и устаревшие пакеты | нет сценария | ⬜ |

#### N-01 DotNetHelper за прокси
1. Машина за корпоративным прокси (или в IDE задать неверный HTTP Proxy в Settings | Appearance & Behavior | System Settings | HTTP Proxy).
2. Окно NuGet → поиск `Newtonsoft` → пакеты находятся; в журнале `nuget` — что фид идёт через помощник (10 минут сразу через него).
3. Вернуть настройки прокси.

#### N-02 `packages.config`
1. Открыть `debug-playground/NetFramework`. Solution view → `LegacyConsole` → Dependencies → Packages: `Newtonsoft.Json`.
2. Окно NuGet, область `LegacyConsole`: пакет как установленный; Install / Update / Remove выключены с объяснением в подсказке.

#### N-03 Журнал фидов
1. Окно NuGet → поиск любого пакета. Plugin Logs, категория `nuget` (и вкладка Log окна NuGet).
2. На каждый GET: URL, маршрут (`direct` / прокси), код, размер, время; первый запрос — сводка прокси IDE и `*_PROXY`.
3. Добавить заведомо мёртвый фид → заголовок списка «N of M feeds did not answer», ошибка с подсказкой. Удалить фид.

#### N-04 Consolidate
1. Поставить в `Console` и `Lib` один пакет разных версий (например `Newtonsoft.Json` 13.0.1 и 13.0.3).
2. Меню .NET → NuGet → **Consolidate Package Versions** → предпросмотр, OK → обе версии 13.0.3. Откатить.

#### N-05 Why Is This Installed?
1. SDK ≥ 8.0.400. Окно NuGet → ПКМ по транзитивному пакету → **Why Is This Installed?**
2. Диалог: дерево зависимостей по каждому TFM.

#### N-06 Уязвимые и устаревшие
1. Поставить в `Console` заведомо уязвимый пакет (например `System.Text.Json` 8.0.0 или `Newtonsoft.Json` 12.0.1) и deprecated (например `Microsoft.Azure.Storage.Blob`).
2. Окно NuGet: в строке пометка (severity / «Deprecated»), в карточке — ссылка на advisory. Откатить.

---

## .NET Framework

Нужны Visual Studio или Build Tools 2022 (workload «.NET desktop build tools») и targeting pack 4.8.1. Открыть папку `debug-playground/NetFramework`.

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| NF-01 | 0.1.42 | Run `LegacyConsole`: аргументы, OEM-кодировка | `NetFramework.sln` | 🤖 |
| NF-02 | 0.1.42 | Run `LegacyWpf`, Stop закрывает окно | `NetFramework.sln` | 🤖 |
| NF-03 | 0.1.42 | Build одного `LegacyConsole` после удаления `packages` | ПКМ → Build | ⬜ |
| NF-04 | 0.1.41 | «MSBuild version» в Toolset and Build | Settings | ⬜ |
| NF-05 | 0.1.41 | Build Solution с чистого, WPF запускается | `NetFramework.sln` | 🤖 (сборка) |
| NF-06 | 0.1.41 | Ошибка CS0029; Rebuild / Clean через `MSBuild.exe` | `LegacyWpf/MainWindow.xaml.cs` | ⬜ |
| NF-07 | 0.1.41 | «.NET SDK» → CS5001; основное решение — `dotnet build` | Toolset and Build | ⬜ |
| NF-08 | 0.1.41 | Без VS — уведомление | другая машина / неверный путь | ⬜ |
| NF-09 | 0.1.41 | Цели WebApplications, WinFX при вычислении | нет сценария | ⬜ |
| NF-10 | 0.1.28 | Legacy-проект в Solution view | временный `Legacy.csproj` | ⬜ |
| NF-11 | 0.1.28 | Legacy: правка извне, wildcard, плохой `global.json` | временный `Legacy.csproj` | ⬜ |
| NF-12 | 0.1.23 / 0.1.29 | Attach к .NET Framework: ключ реестра снят в 0.1.69 — см. E-90; хосты CLR (`powershell.exe`) | README «Attach к процессам .NET Framework» | ⬜ |
| NF-13 | открыт | Roslyn LS и legacy-проекты | `NetFramework.sln` | ⬜ |
| NF-14 | факт плана | Двойная сборка при Debug legacy — была (и у SDK-проектов), исправлено в 0.1.69, см. E-87 | `NetFramework.sln` | 🤖 |
| NF-15 | для плана | `dotnet test` / `dotnet vstest` на legacy | нет сценария | ⬜ |
| E-87 | 0.1.69 | Debug `LegacyConsole`: одна сборка, остановка, кадры, переменные, Evaluate, шаги, вывод, рабочая папка | `LegacyConsole/Program.cs`, `BP:legacy-console` | 🤖 |
| E-88 | 0.1.69 | Debug `LegacyWpf`: клик → остановка, `clicks`, Step Over, Stop закрывает окно | `LegacyWpf/MainWindow.xaml.cs`, `BP:legacy-wpf-click` | 🤖 |
| E-89 | 0.1.69 | 32-битная программа и процесс: отказ уведомлением до адаптера | `LegacyConsole.csproj` без `Prefer32Bit` | 🤖 |
| E-90 | 0.1.69 | Attach to Process к net4x без ключа реестра; Debug теста `net481` | `BP:legacy-wpf-click`; свой проект тестов `net481` | 🤖 |

#### NF-01 Run `LegacyConsole`
1. Конфигурация «.NET Project» с `LegacyConsole`, аргументы `first "two words"`, Run.
2. Сборка в Build через `MSBuild.exe`; в консоли путь к `LegacyConsole.exe`, JSON с `"Runtime":"4.0.30319.42000"` и `"Arguments":["first","two words"]`.
3. «Кириллица: привет, ёжик» читается при OEM 866; при 437 — `?` (так пишет сама программа).

#### NF-02 Run `LegacyWpf`
1. Run `LegacyWpf` → окно «Legacy WPF».
2. Stop в Services → окно закрывается сразу, `LegacyWpf.exe` нет в диспетчере задач.

#### NF-03 Build одного проекта
1. Удалить папку `NetFramework/packages`. ПКМ на `LegacyConsole` → Build.
2. Restore проходит; в журнале `commands` у команды `-p:SolutionDir=…\NetFramework\`.

#### NF-04 «MSBuild version»
1. Settings | .NET | Toolset and Build: «MSBuild version» = «Auto», под ней — какая установка и где.
2. В списке ещё «.NET SDK (dotnet build)» и установки VS.

#### NF-05 Build с чистого
1. Удалить `bin`, `obj` обоих проектов и `packages` → .NET → Build Solution.
2. Окно Build зелёное, «Восстановление пакета NuGet Newtonsoft.Json…», `LegacyConsole -> …exe`, `LegacyWpf -> …exe`;
   журнал `commands`: `MSBuild …\amd64\MSBuild.exe -t:Build -restore -p:RestorePackagesConfig=true -m -v:m …`.
3. `LegacyWpf\bin\Debug\LegacyWpf.exe` запускается, кнопка Click считает.

#### NF-06 Ошибка и Rebuild / Clean
1. В `MainWindow.xaml.cs` написать `Greeting.Text = 1;` → Build: CS0029 в окне Build с переходом и в редакторе. Вернуть.
2. Rebuild Solution, Clean Solution — тоже `MSBuild.exe` (`-t:Rebuild`, `-t:Clean`, у Clean нет `-restore`).

#### NF-07 «.NET SDK» и основное решение
1. «MSBuild version» = «.NET SDK (dotnet build)» → Build: `LegacyWpf` падает с CS5001 (так и задумано). Вернуть «Auto».
2. Открыть `DebugPlayground.sln`: Build идёт через `dotnet build`, как раньше.

#### NF-08 Без Visual Studio
1. Машина без VS, или «MSBuild version» указывает на удалённый файл и VS нет.
2. Build legacy-проекта → уведомление «Visual Studio Build Tools Not Found» с Download Build Tools, один раз за сессию; сборка через `dotnet build`.

#### NF-09 Цели WebApplications и WinFX
1. Поставить web-workload Build Tools («ASP.NET and web development build tools»).
2. Legacy-проект с `<Import Project="$(VSToolsPath)\WebApplications\Microsoft.WebApplication.targets" />` (см. NF-10): в журнале `msbuild`
   **нет** предупреждения о пропущенном импорте.
3. `LegacyWpf` в Solution view: XAML-файлы с `.xaml.cs` под ними (цели WinFX вычислены).

#### NF-10 Legacy-проект в Solution view
1. Создать `%TEMP%\legacy\Legacy\Legacy.csproj` по описанию в `debug-playground/README.md` «Проект старого формата» (+ файлы и `Legacy.sln`), открыть папку.
2. Сначала на секунду видны все файлы, затем: `Program.cs`, `Form1.cs` (под ним `Form1.Designer.cs`), `Views\Index.cshtml`, пустая `Empty`,
   `Shared\Util.cs` со значком ссылки; **нет** `Stray.cs` и `Old`.
3. Show All Files: `Stray.cs`, `Old`, `Legacy.csproj` серыми. Открыть `Stray.cs` → баннер «Not a part of Legacy.csproj…».
4. Журнал `msbuild`: предупреждение о пропущенном `Microsoft.WebApplication.targets` (без web-workload), проект показан.

#### NF-11 Legacy: изменения
1. Дописать внешним редактором `<Compile Include="Stray.cs" />` → через секунду `Stray.cs` обычным цветом; убрать — снова скрыт.
2. Создать `Views\New.cshtml` → появляется; `New.cs` в корне → не появляется.
3. `global.json` с несуществующей версией SDK, перезапуск IDE → ошибка в журнале `msbuild`, дерево показывает все файлы, IDE не виснет. Убрать.

#### NF-12 Attach к .NET Framework
С 0.1.69 ключа реестра нет: процессы net4x предлагаются всегда (на Windows); attach и отладка — E-90.
1. Run | Attach to Process: `powershell.exe` 5.1 (64 и 32 бита, `SysWOW64`) в «.NET» (иногда со второго открытия), `pwsh.exe` — как .NET;
   `notepad.exe` — нет; журнал `diagnostics` без ошибок.
2. Attach к 32-битному `powershell.exe` → уведомление «Cannot Debug a 32-bit Process», сессии нет.

#### NF-13 Roslyn LS и legacy
1. Открыть `NetFramework`, дождаться виджета сервера. Plugin Logs, категория `roslyn` (и Show Language Server Log).
2. Записать: грузит ли сервер оба проекта, есть ли «has unresolved dependencies», работают ли ошибки / completion в `LegacyConsole/Program.cs`.
   Это исследование для открытого пункта ROADMAP — результат описать заметкой.

#### NF-14 Двойная сборка при Debug
Было: две сборки подряд на каждый Debug (робот, 2026-10-05; у SDK-проектов так же). С 0.1.69 — одна: шаги в E-87.

#### E-87 Debug `LegacyConsole` — `BP:legacy-console`
1. Открыть `debug-playground/NetFramework` (копию), `NetFramework.sln`. Точка останова на строке `BP:legacy-console`. Конфигурация «.NET Project»
   с `LegacyConsole`, аргументы `first second` → Debug.
2. Окно Build: **одна** сборка `MSBuild.exe` (в Plugin Logs, категория `run`: `built before the launch N` и `debug of LegacyConsole (N): built
   before the launch` с тем же N, второй `Build LegacyConsole.csproj` нет).
3. Остановка на строке маркера; кадр `LegacyConsole.Program.Main()`; переменные `args` (`{string[2]}`) и `json` с `"Runtime":"4.0.30319.42000"`.
4. Evaluate: `Environment.Version.ToString()` → `"4.0.30319.42000"`; `Environment.CurrentDirectory` → `…\LegacyConsole\bin\Debug` (папка вывода,
   как у Run и Visual Studio); `Newtonsoft.Json.JsonConvert.SerializeObject(args)` → `"[\"first\",\"second\"]"`.
5. Step Over → строка 14, Step Into → 15 (в `Console.WriteLine` не заходит), Resume → в Console JSON и «Кириллица: привет, ёжик» целиком, сессия кончается.
   Человеку: вид окна Debug, подсказки значений в редакторе.

#### E-88 Debug `LegacyWpf` — `BP:legacy-wpf-click`
1. Точка останова на `BP:legacy-wpf-click`, Debug конфигурации с `LegacyWpf` → окно «Legacy WPF».
2. Click → остановка в `OnClick`, кадры `LegacyWpf.MainWindow.OnClick()` и `[External Code]`, `this`, `sender` (`Button: Click`), `e`;
   Evaluate `clicks` → 1, `Greeting.Text` → «Built by MSBuild of Visual Studio».
3. Step Over → строка 20; Resume; второй Click → `clicks` = 2. Stop → окно закрыто, `LegacyWpf.exe` в диспетчере задач нет.

#### E-89 32-битная программа
1. В `LegacyConsole.csproj` убрать строку `<Prefer32Bit>false</Prefer32Bit>` → Debug `LegacyConsole`.
2. Сборка проходит, адаптер не запускается; уведомление «Cannot Debug a 32-bit Process»: «LegacyConsole.exe runs as a 32-bit process (AnyCPU
   with "Prefer 32-bit", …) … Set Prefer32Bit to false in LegacyConsole.csproj and debug again.» Платформенного «Error running» нет. Вернуть строку.
3. Attach: запустить 32-битную сборку `LegacyWpf.exe` (собрать без `Prefer32Bit` в другую папку) → Attach to Process → то же уведомление
   «LegacyWpf.exe (PID) is a 32-bit process…». Человеку: текст уведомления читается целиком (раскрыть стрелкой).

#### E-90 Attach к net4x, тесты `net481`
1. Запустить `LegacyWpf\bin\Debug\LegacyWpf.exe` вне IDE. Run | Attach to Process → он в группе «.NET» **с первого открытия** списка.
2. Attach (точка на `BP:legacy-wpf-click`) → Click → остановка, `clicks` = 1. Stop (detach) → окно живо.
3. SDK-проект тестов `net481` (`dotnet new mstest`, `TargetFramework` = `net481`), точка в тесте → Debug теста (▶ у метода или конфигурация
   `dotnet test`) → сессия «Tests of …», остановка в тесте, Evaluate локальной; Resume → «Tests passed: 1».
   Роботом проверено через API (список — `attach_list.js`, attach — `attach.js`); сам диалог Attach to Process человеку.

#### NF-15 Тесты legacy (факт для плана)
1. В консоли: `dotnet test` на legacy-проекте с тестами (появится `NetFramework/LegacyTests`) → ожидается MSB4057.
2. `dotnet vstest <путь к dll тестов net4x>` — работает ли. Результат — в `NET_FRAMEWORK_PLAN.md`.

---

## Roslyn LSP

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| L-01 | 0.1.44 | Цвета идентификаторов при загрузке | нет маркера (`Console/Scenarios.cs`) | ⬜ |
| L-02 | 0.1.44 | Нет `AlreadyDisposedException` после закрытия проекта | нет сценария | ⬜ |
| L-03 | 0.1.31 / 2026-09-29 | Виджет сервера в статус-баре | нет сценария | ⬜ |
| L-04 | 2026-09-30 | Файл с диска сразу в проекте | README «Reload» | ⬜ |
| L-05 | 2026-09-29 | Reload Solution / Reload Project | README «Reload» | ⬜ |
| L-06 | 2026-09-22 | Ctrl+P: все перегрузки | нет сценария | ⬜ |
| L-07 | 2026-09-21 | Signature help; папка без solution | нет сценария | ⬜ |
| L-08 | 2026-09-21 | Language Server Timings на большом solution | нет сценария | ⬜ |
| L-09 | 2026-09-29 | Устаревший resolve не роняет корутину | нет сценария | ⬜ |

#### L-01 Цвета при загрузке
1. Открыть `Console/Scenarios.cs`, закрыть IDE с ним открытым; открыть README.md поверх (вкладкой), закрыть IDE.
2. Запустить IDE: `Scenarios.cs` раскрашен эвристикой сразу при показе; через ~2 с — цветами из кэша сервера; потом — ответом сервера.
3. Не должно быть: периода без цветов идентификаторов, «мигания» при переходе кэш → сервер, пропадания всех цветов при обновлении токенов.

#### L-02 Закрытие проекта
1. Сервер готов. File | Close Project.
2. В `idea.log` нет `AlreadyDisposedException`.

#### L-03 Виджет сервера
1. В статус-баре значок C# с CPU и памятью, обновляется каждые ~2 с; подсказка — имя solution.
2. Клик → окно: состояние, CPU и память дерева процессов, PID, число процессов, время работы — обновляется раз в секунду.
3. Действия Restart, Select Solution…, Log, Timings, Settings… работают.

#### L-04 Файл с диска
1. Сервер готов. Не переключаясь из IDE, во встроенном терминале: `mkdir Console/Outside` и `echo "class Outside { int x = \"s\"; }" > Console/Outside/Outside.cs`.
2. Открыть файл — ошибка типа подчёркнута быстро (а не через 15–20 с). Удалить папку.

#### L-05 Reload
1. Файл мимо IDE (как L-04) — в дереве его нет; ПКМ на `Console` → **Reload Project** → появился, в статус-баре «Console reloaded».
2. Кнопка Reload Solution в заголовке окна Project (рядом с «глазом») только в Solution view; после нажатия виджет: starting → loading → `DebugPlayground.sln`.
3. Reload Project сервер не перезапускает; ошибки нового файла в Problems.
4. Изменить `Console.csproj` внешним редактором (`Nullable` → `disable`), Reload Project → Properties… показывает новое. Вернуть.
5. Меню .NET → Reload Solution / Reload Project '<имя>'; без файла проекта Reload Project выключен.

#### L-06 Ctrl+P
1. Сервер готов. В методе набрать `Console.WriteLine(`, **Ctrl+P**.
2. Список всех перегрузок (19), текущий параметр подсвечен, неподходящие серые. Набрать `"x"` — подсветка следует. Ctrl+Z.

#### L-07 Signature help; папка без solution
1. Вызов метода с несколькими параметрами — после `(` и `,` подсказка параметров открывается сама.
2. Открыть папку только с `.csproj` без `.sln` (например копия `Lib` во временной папке) → сервер грузит проект, ошибки и completion работают.

#### L-08 Timings
1. Открыть большой solution (например выборку aspnetcore из `tools/ui-robot/make-aspnetcore-sample.py`). Поработать минуту.
2. Меню .NET → Language Server Timings: таблица по методам, копируется в буфер; отметить время загрузки в виджете.

#### L-09 Устаревший resolve
1. В файле с code lens и inlay hints быстро печатать и стирать минуту, пролистывать.
2. В `idea.log` нет необработанных исключений «Resolve version … does not match current version …».

---

## Прочее

| # | Версия | Что проверить | Сценарий | Статус |
|---|---|---|---|---|
| O-01 | 0.1.19 | Окно Plugin Logs и уведомления | нет сценария | ⬜ |
| O-02 | 2026-09-29 | Страница о плагине и документация в IDE | README «Страницы о плагине…» | ⬜ |
| O-03 | 2026-09-29 | Страницы настроек по-русски | README «Страницы о плагине…» | ⬜ |
| O-04 | 2026-09-29 | Значки нового UI в обеих темах | нет сценария | ⬜ |
| O-05 | 0.1.18 | Дополнительные папки поиска `dotnet` | нет сценария | ⬜ |
| O-06 | 0.1.33 / 0.1.34 | Безопасность: UNC-пути, недоверенный проект | нет сценария | ⬜ |

#### O-01 Plugin Logs
1. Меню .NET → **Plugin Logs**: события с категориями (`dotnet`, `sdk`, `roslyn`…); Clear, «Warnings and Errors Only», Open Logs Folder работают.
2. Вызвать ошибку (например неверный путь к `dotnet` в Settings | .NET) → уведомление с кнопкой Plugin Logs. Вернуть путь.

#### O-02 Страницы о плагине
1. Меню .NET → **Welcome to C# Project Support**: вкладка, код на первом экране набирается сам, в подвале «Редакция страницы 2026-09-29.5».
2. «Документация» в шапке открывает документацию **в той же вкладке**, «О плагине» — обратно; .NET → Plugin Documentation — сразу документация, содержание слева.
3. Тема страниц = тема IDE; сменить тему и открыть снова — новая.

#### O-03 Настройки по-русски
1. Settings | .NET → «Language of the settings pages» = Русский, Apply, закрыть и открыть Settings.
2. Страницы .NET, Toolset and Build, NuGet, Coverage, Debugger, Language Server — по-русски; названия в дереве слева английские.
3. Language Server: группы «Анализ», «Автодополнение», «Подсказки в коде»; значения (`openFiles`, `at_the_end`) не переведены.
4. «Документация плагина...» внизу страницы .NET → системный браузер на разделе «Настройки». English / «Как в IDE» — английский.

#### O-04 Значки
1. Solution view площадки в светлой теме: значки solution, проектов (`C#` в рамке), `.cs`, Dependencies, папка `Properties`.
2. Переключить на тёмную (Settings | Appearance) — значки различимы, не «грязные», в стиле нового UI.

#### O-05 Папки поиска `dotnet`
1. Положить SDK в нестандартную папку (или указать папку с `dotnet*` внутри) в Settings | .NET → Additional search folders.
2. Убрать `dotnet` из PATH для IDE (или переменная `DOTNET_SUPPORT_SEARCH_PATHS`) → плагин находит `dotnet` из папки, новейшую версию по имени.

#### O-06 Безопасность
1. В копии проекта в `obj/project.assets.json` поменять `packageFolders` на `\\somehost\share\` → открыть: IDE не обращается к хосту (журнал / Wireshark, нет запроса логина).
2. Открыть чужую папку и выбрать «не доверять» (safe mode) → Solution view и IL Viewer не вычисляют MSBuild проекта.

---

## Нет сценария в debug-playground

По `CLAUDE.md` у всего, что смотрят вживую, должен быть код-сценарий с маркером. Сейчас его нет у строк ниже — добавить, когда дойдут руки.

**Нет ни маркера, ни пункта чек-листа README:**
- Свой парсер: P-10 (просится `Console/Editor/SyntaxTree.cs` с перечисленными конструкциями), P-12, P-11 (EF-значков нет: в площадке нет `DbContext`).
- Редактор: E-19, E-20, E-21, E-22, E-23 (кроме `stats-ghost`), E-24, E-25, E-26, E-27, E-28, E-29, E-30, E-31, E-32, E-36, E-40.
- Навигация: F-09 (Hierarchy — робот проверял на своём `ProbeCircle`, не на площадке), F-10, F-11, F-12, F-13.
- Отладчик: D-20 (нет MTP-проекта), D-21.
- Сборка / тесты: B-04, B-05 (нет MTP-проекта), B-17, B-18, B-19, B-20, B-21, B-22, B-23.
- NuGet: N-01, N-03, N-04, N-05, N-06.
- .NET Framework: NF-09, NF-13, NF-14, NF-15 (план: `NetFramework/LegacyTests`; маркеры `BP:legacy-console` / `BP:legacy-wpf-click` уже есть — для будущей отладки net4x).
- Roslyn LSP: L-01, L-02, L-03, L-06, L-07, L-08, L-09.
- Прочее: O-01, O-04, O-05, O-06.

**Есть только пункт чек-листа README, без маркера в коде** (для UI-проверок допустимо, но с маркером точнее): P-07 (`Console/Types.cs`),
P-08, E-13, E-15, E-18, B-08…B-12, B-15, B-16, L-04, L-05, N-02, NF-03…NF-08, NF-10, NF-11, NF-12, O-02, O-03, D-22, D-23, D-24.
