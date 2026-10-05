# Миграция: от roslyn-language-server к своему PSI C# (csharp-psi)

Составлен 2026-10-04 по решению пользователя: уйти от `roslyn-language-server` к своему PSI, как это сделано в соседнем
`idea-golang-support` (его `MIGRATION.md` — образец: переключатели, корпусные гейты, вехи отказа от gopls). Причины, ради
которых это делается: **ограничения LSP** (вид использования в Find Usages не встроить, потери в lsp4j, два поколения API
платформы), **скорость** (до загрузки solution сервером нет ничего, кроме эвристик), **требование .NET 10** (сервер собран
под него), **память** (процесс сервера ~500 МБ на solution).

Пересмотрен 2026-10-04 после разбора: добавлены срез-разведка (шаг 0), исходные замеры, синтаксическая веха S с точкой
решения, правила сверки деревьев, `#if` по TFM, контекст ленивых тел; project model отвязан от подмены парсера.

Статус шага отмечать прямо здесь (`[ ]` → `[x]` с датой и версией), сделанное — в `ROADMAP.md`, видимое пользователю —
в `CHANGELOG.md`. `LSP_PLAN.md` остаётся планом текущего клиента сервера, пока он нужен (до вехи 12.1).

## Горизонт: опыт go-psi

`../go-psi` написан с нуля оркестратором с субагентами (`docs/AGENT_BRIEFS.md` там) за 2026-10-01…02: лексер, парсер,
стабы, project model, порт go/types с выводом generics, IDE-фичи, форматтер. Итоги на корпусе: 6727 файлов GOROOT,
11 млн узлов, 0 расхождений с go/ast; 0 диагностик чекера на GOROOT; gofmt байт в байт на 4621 файле. Значит, оценки
«неделями» для этого способа работы неверны — объём меряется сессиями, а узкое место — гейты и проверка, не набор кода.

Чем C# тяжелее Go, и почему множитель неравномерный:
- **синтаксис ×3–5:** ~400 видов узлов против ~150, неоднозначности повсюду (generics `a < b > (c)`, лямбда или скобки,
  паттерны, query-выражения, контекстные ключевые слова), директивы `#if`, интерполированные и raw-строки;
- **семантика ×10 и больше:** у go/types нет перегрузок, неявных преобразований, extension-методов, вывода типов лямбд в
  обе стороны, nullable; `Binder` Roslyn в разы больше go/types;
- **эталон дороже:** go/ast и go/types — компактные библиотеки в тулчейне; Roslyn — большая, но тоже доступная (MIT, NuGet).

Ориентиры для того же способа работы (уточняются шагом 0):
- **веха S — синтаксис на своём PSI** (шаги 0–9): 1–2 недели;
- **веха 12.1 — сервер опционален** (+ слои 11a–11c): ещё несколько недель;
- **веха 12.2 — сервер удалён** (11d–11f): месяцы, и не факт, что весь паритет достижим — поэтому 12.2 остаётся вехой
  с условием, а не обещанием.

План устроен так, чтобы **каждая веха была полезна сама по себе**, а продолжение после вехи S — отдельным решением.

## Что берём из go-psi

- **Отдельный репозиторий на время разработки** (открытое решение, см. ниже): go-psi рос отдельно со своей сборкой,
  `plugin/` с PsiViewer и `runIde`, а в плагин переехал готовым (`idea-golang-support/MIGRATION.md`, шаг 0 — перенос
  кода). Сборка этого плагина тяжёлая (classpath IDEA, `test buildPlugin`), и итерации парсера в ней медленнее.
- **Эталон — отдельный инструмент**, как `tools/astdump` на Go: `tools/roslyndump` (консольное приложение на Roslyn) с
  режимами `tokens`, `tree`, `walk` (весь корпус за один запуск), `semantics`. Это тестовый инструмент, в плагин он не
  кладётся — помощник `dotnethelper` для эталона не нужен.
- **Метрики корпуса в `testData/metrics/*.json`**, которые могут только улучшаться (`goroot-src-ast-diff.json` и т. п.);
  корпусный тест падает при регрессии, улучшение коммитится вместе с кодом. Списки исключений — отдельными файлами
  `*-allowlist.txt`, по умолчанию пустые.
- **Маппинг для сверки деревьев** — как `GoAstMapping`/`GoAstDump`: наше дерево нормализуется к форме эталона.
- **Мутационный фазз парсера** (удаление и вставка случайных токенов): 0 исключений, ошибка не «съедает» соседние
  объявления (`localityViolations` — метрика).
- **Бенчмарки с порогами** (`testData/benchmark`), инкрементальный репарс тела — отдельный замер.
- **`docs/GRAMMAR.md`** — разобранные неоднозначности с отсылкой к методу Roslyn, **`AGENT_BRIEFS.md`** — шаблон задачи
  субагенту и чек-лист приёмки оркестратором, **`tools/gates.sh`** — тихий вывод гейтов.

Где расходимся с go-psi сознательно: go-psi взял Grammar-Kit BNF + внешние правила на Kotlin, потому что у Go «две
настоящие неоднозначности». У C# они повсюду, а `Syntax.xml` уже даёт PSI декларативно — поэтому здесь ручной перенос
`LanguageParser` на `PsiBuilder` + генератор PSI из `Syntax.xml`. Бонус: виды наших узлов совпадают с Roslyn один к
одному, и маппинг для сверки почти тривиален.

## Решение: csharp-psi растёт в отдельном репозитории

Принято пользователем 2026-10-04, как у go-psi: `../csharp-psi` со своей сборкой, гейтами, `plugin/` с PsiViewer и
`runIde`; план разработки PSI — его `docs/PLAN.md`. Шаги 0, 3–6 и 8 идут там; здесь остаются 1–2, 7, 9–12 — это план
переноса готового PSI в плагин. Корневой пакет — `io.github.dotnetsupport.csharp` (`lang`, `semantic`, `ide`): он не
пересекается с пакетами плагина, переносится без переименования. Id языка — `C#`, как у `CSharpLanguage` плагина; в
песочнице csharp-psi плагин «C# Project Support» не ставится. Roslyn для эталона и переноса закреплён на коммите
`35d9211b841e7613c1d2f8f5af6d628ace696c4c` (из него собран NuGet-пакет `Microsoft.CodeAnalysis.CSharp` 5.9.0).

**С шага 7 (2026-10-04, решение пользователя) код живёт здесь целиком**, `../csharp-psi` заморожен как история (последний коммит
`0b60b06`). Где что: код и тесты ядра — `csharp-psi-core/src`, эталоны и метрики — `csharp-psi-core/testData`, оракул и скрипты корпуса —
`tools/csharp-psi/` (`roslyndump`, `fetch-roslyn.sh`, `fetch-corpus.sh`, `gates.sh`), корпус — `.corpus/` (не в git), документы парсера —
`docs/csharp-psi/` (`PLAN.md` — шаги 0, 3–6, 8 и их статус; `GRAMMAR.md`, `PORTING_MAP.md`, `TESTING.md` — команды гейтов из этого
репозитория), `roslynCommit` / `runtimeTag` / `aspnetcoreTag` — в `gradle.properties`, уведомление Roslyn — `NOTICE.md`. Корневой `test`
гоняет и `:csharp-psi-core:test`; корпусные гейты — `./gradlew.bat :csharp-psi-core:corpusTest -Dcsharppsi.lexer.corpora=runtime,aspnetcore`.

## Что даёт свой PSI, а что — только Roslyn

- **Синтаксис (свой PSI, без сервера, веха S):** Structure, folding, breadcrumbs, отступы и форматирование по дереву,
  Complete Statement и серый текст без «осторожных случаев», синтаксические ошибки при наборе, Extend Selection по узлам,
  вид использования (запись / вызов / `nameof` / атрибут), локальные переменные и параметры (подсветка, rename, extract /
  inline variable), Surround With, Structural Search, точные версии наших эвристик (метод под курсором для IL Viewer,
  привязки конфигурации для `appsettings`, run-gutter тестов).
- **Семантика (своя, со сверкой по Roslyn, шаг 11):** разрешение имён с `using` и namespace, типы членов и `var`,
  completion после точки, Go to Declaration и Find Usages по solution, rename публичных членов, перегрузки, generics,
  лямбды, extension-методы, LINQ, типовые диагностики (неразрешённое имя, несовпадение типов).
- **Только Roslyn (остаётся по запросу, веха 12):** анализаторы из NuGet и их code fixes, source generators (их вывод —
  через помощник, см. шаг 11f), полный паритет с диагностиками компилятора, nullable-анализ потока.

## Принципы

1. **Один переключатель на фичу** `ROSLYN | NATIVE` (`CSharpFeatures`, страница Settings | .NET | Language Server).
   Умолчание меняется на `NATIVE` только после корпусного гейта, робота и живой проверки; оба пути живут рядом хотя бы
   один релиз.
2. **Исключающие переключатели, не порядок EP.** Один источник на фичу: обработчик модуля `roslyn` отвечает `null`,
   нативный contributor выходит сразу, если флаг не его. Сегодняшняя проверка `RoslynServerStatus.isReady` (эвристики
   уступают готовому серверу) встраивается в тот же механизм.
3. **Атомарные шаги.** Шаг целиком в одной версии с зелёными гейтами. Самый большой — подмена парсера (шаг 7): на
   `CSharpDeclarations` висят Structure, breadcrumbs, folding, Go to Class, `CSharpDeclarationIndex`, IL Viewer, ghost text.
4. **Эталон — Roslyn.** Каждый слой сверяется с компилятором автоматически: `tools/roslyndump` отдаёт по файлам корпуса
   токены, дерево (виды узлов и диапазоны) и семантику (символ каждого идентификатора, тип каждого выражения). Гейт слоя —
   метрика в `testData/metrics/*.json`, которая может только улучшаться.
5. **Гейты перед «готово»:** `./gradlew.bat test buildPlugin -q`; корпусный гейт затронутого слоя
   (`:csharp-psi-core:corpusTest`, `:csharp-psi-semantic:corpusTest`); робот на `debug-playground`; 0 «Plugin to blame»
   в `idea.log`; замеры производительности (время парсинга корпуса, первая подсветка, completion) — в CHANGELOG.
6. **Замеры — от исходной точки.** Цифры текущего пути (сервер + эвристики) снимаются до первого шага (шаг 0) тем же
   способом, что и потом для `NATIVE`; без них «быстрее» и «меньше памяти» в CHANGELOG не с чем сравнивать.
7. **Иконки, цвета, настройки, тексты — из основного плагина** (`DotNetIcons`, `CSharpSyntaxHighlighter` ключи,
   `DotNetBundle` en/ru). PSI-модули своих не заводят. Внутри PSI-модулей нет LSP и нет кода JetBrains.
8. **Лицензии.** Парсер переносится с Roslyn (MIT): уведомление об авторстве в `NOTICE.md`, в шапке перенесённых **и
   сгенерированных из `Syntax.xml`** файлов — ссылка на исходный файл и тег Roslyn. Rider / ReSharper не декомпилировать.

## Карта шагов

| # | Шаг | Объём | Зависит от | Статус |
|---|---|---|---|---|
| 0 | Разведка: исходные замеры, `tools/roslyndump`, вертикальный срез парсера (выражения) со сверкой | 1–2 сессии | — | [ ] |
| 1 | Модули `csharp-psi-core` / `-semantic` / `-ide` в Gradle (`pluginComposedModule`), пустые | полдня | — | [x] 2026-10-04 |
| 2 | Переключатели `CSharpFeatures` + настройка + чтение в модуле `roslyn` | день | — | [x] 2026-10-04 |
| 3 | Генератор `IElementType` и PSI-классов из `Syntax.xml` + таблица соответствия и нормализация для сверки | 1 сессия (виды уже генерируются) | 0, 1 | [x] 2026-10-04 |
| 4 | Лексер под ожидания парсера Roslyn (JFlex, как в go-psi); `#if` по символам TFM; сверка токенов | 0,5–1 сессия (готов без `#if`) | 1 | [x] 2026-10-04 |
| 5 | Перенос парсера: `LanguageParser`, директивы, doc-комментарии; ленивые тела с контекстом | 3–4 сессии | 3, 4 | [x] 2026-10-04 |
| 6 | Корпусные гейты синтаксиса: 0 расхождений с Roslyn, фазз, бенчмарки | 1 сессия | 5 | [x] 2026-10-04 |
| 7 | Подмена парсера + мост вместо `CSharpDeclarations` (атомарно; при отдельном репозитории — перенос кода) | 1–2 сессии | 2, 6 | [x] 2026-10-04, 0.1.45 |
| 8 | Stub-индексы вместо `CSharpDeclarationIndex` | 1 сессия | 7 | [x] 2026-10-04 (вживую не проверено) |
| 9 | Синтаксические фичи на PSI, по одной за флагом | по фиче | 7 | [ ] |
| **S** | **Веха: синтаксис на своём PSI; решение, идти ли в семантику** | — | 8, 9 | [ ] |
| 10 | Project model: исходники, `DefineConstants` по TFM, `LangVersion`, ссылки (MsBuildHost + индекс сборок) | 2–3 сессии | — | [ ] |
| 11 | Семантика по слоям со сверкой по Roslyn (11a–11f) | недели, по слою | S, 10 | [ ] |
| 12 | Отказ от `roslyn-language-server`: две вехи | вехи | 9, 11 | [ ] |

Критический путь: 0 → 3/4 → 5 → 6 → 7 → 8 → S → 11. Шаги 1–2 — сразу, они дешёвые. **Шаг 10 идёт параллельно с 3–6**:
парсеру он не нужен, а `DefineConstants` по TFM нужен лексеру для `#if` (до него — символы по умолчанию, шаг 4). Шаг 9
идёт по одной фиче, часть — параллельно с 10–11.

## Отказ от сервера: список задач (решение пользователя 2026-10-04)

Пользователь решил: идти до отказа от `roslyn-language-server`, не останавливаясь на гибриде вехи S. Порядок — синтаксический
остаток шага 9 и ссылки шага 10 параллельно, затем семантика 11a → 11b → 11c (после неё веха 12.1), затем 11d–11f и 12.2.
У каждой фичи — переключатель, проверка роботом в обоих режимах, NATIVE по умолчанию только после неё; сценарий в
`debug-playground`, строки в `docs/LIVE_CHECKS.md`. Статус — здесь (`[ ]` → `[x]`, дата, версия).

**A. Синтаксический остаток (шаг 9)**
- [x] A1 (2026-10-04, 0.1.49: робот — 4 сценария совпали с сервером, NATIVE по умолчанию; «None» больше не форматирует). `FORMATTING`: робот в обоих режимах (`reformat.js`, `Formatting.cs`), NATIVE по умолчанию; выбор форматтера «Built-in» /
  «dotnet format (on save)» — сделан в 0.1.49
- [x] A2. `NAVIGATION`, синтаксическая часть: Go to Declaration и Ctrl+наведение для локальных, параметров, параметров лямбд,
  меток, типов своего solution по имени (stub-индексы, шаг 8); подсветка использований в файле (`documentHighlight`) для них же
  (2026-10-04, 0.1.50; NATIVE по умолчанию с 0.1.60 после робота: `goto_batch.js`, 90 мест `Navigation.cs` / `LibraryNames.cs` —
  те же цели, что у сервера; `new T()` теперь к конструктору)
- [x] A3. `DIAGNOSTICS`, синтаксическая часть: ошибки парсера (узлы ошибок, пропущенные токены) — аннотатор с текстами Roslyn (CSxxxx);
  ошибки последней сборки остаются (2026-10-05, 0.1.54; NATIVE по умолчанию с 0.1.56 после робота, см. шаг 9)
- [x] A4. `SEMANTIC_COLORS`, синтаксическая часть: объявления (типы по виду, методы, поля, свойства, события, константы), локальные,
  параметры и их использования в теле; ссылки на чужие символы — 11c — сделано в 0.1.51
  (2026-10-04); NATIVE по умолчанию с 0.1.60 после робота (`highlight_keys.js` на 4 файлах: всё, что красит сервер, — тем же цветом
  или точнее; доделано: цвета сервера больше не остаются под встроенными, перегрузки с аргументом неизвестного типа, `group … into g`,
  тип запроса LINQ)
- [x] A5. `RENAME`, синтаксическая часть: локальные, параметры, параметры лямбд, метки — inplace rename по дереву — сделано в 0.1.53
  (2026-10-05) вместе с одним резолвером для A2 / A4 / A5; NATIVE по умолчанию с 0.1.56 после робота
- [x] A6. `COMPLETION`, синтаксическая часть: ключевые слова по месту, локальные и параметры в области, члены своего типа (без
  точки), `override` / `partial`; частые вызовы — `return Task.FromResult(|);` / `Task.CompletedTask` в не-`async` методе с
  `Task<T>` / `Task`, `async` в заголовок по набранному `await` — сделано в 0.1.55 (2026-10-05); NATIVE по умолчанию с 0.1.60 после
  робота (`complete_at_line.js` / `complete_select.js`, 26 мест: из списка сервера не теряется ничего, кроме `yield` вне итератора и
  `await` в геттере; доделаны ключевые слова, убраны двойные `override` сервера; у сервера починен `roslyn.client.completionComplexEdit`)
- [x] A7. Alt+Enter без сервера: `if` ↔ тернарный, block ↔ expression body, extract / inline variable, `var` ↔ явный тип (тип — 11b) —
  сделано в 0.1.64 (2026-10-05): `lang/NativeCSharpContextActions.kt`, переключатель `CONTEXT_ACTIONS` («Context actions», пока ROSLYN
  по умолчанию — до робота), тест `CSharpContextActionsTest`, сценарий `Console/Editor/ContextActions.cs` (`TYPE:ctx-*`). Тексты Rider:
  «Convert to '?:' expression», «Convert '?:' to 'if' statement», «To expression body» / «To block body», «Use explicit type» / «Use
  'var'», «Introduce variable» / «Inline variable»; строки сервера тех же действий при NATIVE убираются (`NativeCSharpServerActions`, по
  заголовкам и префиксам Roslyn). Тип для явного — C2 с коротким именем, если оно на этом месте значит тот же тип
  (`CSharpTypeFacts.written`). Не сделано: `var x = c ? a : b` → `if` (нужен тип для объявления без значения), «all occurrences» у
  Introduce, вынос из лямбды с выражением-телом
- [ ] A8. `using`: директивы, операторы и объявления `using` / `await using` (`lang/NativeCSharpUsings.kt`, тест `CSharpUsingsTest`,
  сценарий `debug-playground/Console/Editor/Usings.cs`, `TYPE:using-*`). Синтаксическая часть сделана в 0.1.61 (2026-10-05):
  - [x] completion (`COMPLETION`): в начале оператора `using var` и `await using var` (второй — где `await` законен или метод можно
    сделать `async`; выбор делает заголовок `async`, как `await`); в `using (` — локальные, `var`, `new`; в директиве `using ` —
    namespace solution (stub-индекс `csharp.namespace`) и сборок проекта (индекс сборок) по уровню после точки, `static` первым словом;
    после `using static ` / `using X = ` — ещё и типы namespace; `global using` вверху файла (до прочих директив), `global ` → `using`
  - [x] postfix `.awaitusing` рядом с `.using` (оба именуют переменную по типу `new T()` или по вызову: `BeginTransactionAsync()` →
    `transaction`, `CSharpUsingNames`); `.awaitusing` делает метод `async`
  - [x] «Make method async» (A6) и на `await using` / `await foreach`; при NATIVE строка сервера с тем же заголовком убирается
    (`NativeCSharpServerActions`, `RoslynCodeActionsSupport`)
  - [x] Alt+Enter: «Convert to 'using' declaration» (последний оператор блока, стопка `using (a) using (b)`, `await using`; за `EDITING`:
    при готовом сервере и Language server — его «Use simple 'using' statement», при Built-in — строка сервера убрана), «Convert to
    'using' statement», «Wrap in 'using' statement» (только `var x = new …;`), «Sort 'using' directives» (global → namespace →
    static → alias, `System` первым, без комментариев между директивами), «Convert to 'global using'» (в этот же файл, если в нём есть
    `global using`; иначе в `GlobalUsings.cs` проекта или первый файл проекта с `global using` не из `obj/`; нет — создаётся
    `GlobalUsings.cs` в папке проекта, как в Rider)
  - [ ] неиспользуемые директивы (серым, «Remove unused directives in file») — **ждёт C2/C3**: резолвер C1 не говорит, через какую
    директиву пришло имя (`Level.imports` смешивает директивы файла и global usings), а гадать по тексту нельзя
  - [x] по типам C2 (0.1.65, 2026-10-05; `NativeCSharpUsingChecks`, `CSharpTypeFacts.usingError`, тест `CSharpUsingTypesTest`, сценарий
    `Usings.cs`, `TYPE:using-cs1674` / `TYPE:using-list`): CS1674 (не `IDisposable`; CS8418 — если есть `IAsyncDisposable`), CS8410 / CS8417
    на `await using`, список `using (` / `using var x = ` без того, что точно не disposable. Только при полностью известном типе: нет
    ошибки на неизвестном типе, нерешённой базе, параметре типа, `DisposeAsync` (метод или extension) и `Dispose` у структуры (`ref struct`
    индекс не различает), без System.Runtime
  - [ ] ещё по C2: «Add await» для `IAsyncDisposable`, «Wrap in 'using'» на значениях вызовов (сейчас только `new`), проверка, что
    переменная `using` не переприсваивается в области
  - [ ] **ждёт D2**: «Remove unused directives» как fix с диагностикой (IDE0005 / CS8019) и Fix All по solution

**B. Ссылки и библиотеки (шаг 10)**
- [x] B1 (2026-10-04, 0.1.52). Формат индекса сборок до нужд семантики: типы с generic-параметрами, базовые типы и интерфейсы, все члены (не только
  статические) с сигнатурами, extension-методы, атрибуты, константы, XML-доки из `*.xml` рядом с dll
  (`Program.FormatVersion` / `AssemblyIndex.FORMAT_VERSION` +1, фикстуры `src/test/resources/index`). Сделано: формат 2, читатель
  `index/AssemblyIndex` + `IndexedTypeRef` + `AssemblyDocs`, резолвер `AssemblyIndexSet`; устройство и замеры — `indexer/README.md`
- [x] B2 (2026-10-04, 0.1.52). Ссылки проекта: `project.assets.json` (пакеты и их dll по TFM), reference packs SDK, `ProjectReference`; legacy —
  `HintPath`, reference assemblies net4x; модель «проект → набор сборок» с подпиской на restore. Сделано: `ProjectAssemblies.references`
  (`References`: сборки, проекты-исходники, их собранные сборки), `AssemblyIndexService.references` / `symbols`; пересчёт на restore,
  правку проекта, смену TFM в тулбаре
- [x] B3 (2026-10-05, 0.1.59). Library roots: сборки как внешние библиотеки платформы (дерево External Libraries, scope поиска).
  Сделано: `ProjectAssemblies.References.libraries` (пакет / reference pack / `.NETFramework` / сборка по `HintPath`, `packages.config` —
  пакетом по папке), `index/AssemblyLibraries` — `SyntheticLibrary` + `ItemPresentation` на библиотеку, корни — сами dll (не папки:
  XML-доки reference pack — 37 МБ текста на один TFM, их читали бы все текстовые индексы); пересчёт в `AssemblyIndexService.refresh`,
  `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged` только при изменении. Только API платформы (есть в GoLand / PyCharm).
  Замер на playground: 111 библиотек, 592 dll, VFS ≈ 1,1 с в фоне, индексация новых корней ≈ 0,5 с. Не сделано: связь узла
  Dependencies с библиотекой (Go to Class по типам сборок — B4)
- [x] B4 (2026-10-05, 0.1.62). Go to Class / Go to Symbol по сборкам и metadata view («как в Rider: метаданные сборки», без сервера и
  декомпилятора). Сделано: `index/AssemblyGotoContributors` — `ChooseByNameContributorEx` (+ `GotoClassContributor`) по всем индексам
  `AssemblyIndexService.allIndexes()`, только при non-project items (`scope.isSearchInLibraries`), дедупликация по (сборка, версия, doc id),
  имена — отсортированные таблицы на индекс (`AssemblyIndexNames`); `index/AssemblyMetadataText` — C# типа из индекса (заголовок сборки,
  `using`, generic-параметры, `where`, базы, атрибуты без аргументов, члены без тел, `///`, вложенные типы, enum-значения по умолчанию
  именами); `index/AssemblyNavigation` — файлы своей ФС `dotnet-metadata://v2/<mvid>/<тип>/<Имя>.cs` (`LightVirtualFile` с её
  `getFileSystem`: URL переживает перезапуск, индекс ищется по MVID у проектов или в кэше), `target(project, type / member)`,
  `declarationTargets(leaf)` — подключён в `CSharpGotoDeclarationHandler` после `NativeCSharpNavigation.targets` (при готовом сервере —
  null, его декомпилированный исходник). Тест `AssemblyGotoLibraryTest` (золотой файл `src/test/resources/metadata/IndexFixture.txt`; с
  `METADATA_INDEXES=<папка индексов>` — все типы 582 сборок площадки разбираются без ошибок, имена за 110 / 7 мс). Не сделано: резолвер
  внутри metadata view (крючок `AssemblyNavigation.assembliesOf` для `CSharpSemanticEnvironment.assemblies`), аргументы атрибутов (их
  нет в индексе), Find Usages от члена сборки

**C. Семантика (шаг 11) → веха 12.1**
- [x] C0 (2026-10-05, без версии: пользователю не видно). Оракул семантики: `roslyndump semantics` (символ каждого идентификатора,
  тип каждого выражения, диагностики по Roslyn), гейт `./gradlew semanticGate` на playground и срезе корпуса, базовые цифры
  синтаксического резолвера — шаг 11, «Оракул семантики»
- [x] C1 (2026-10-05, 0.1.57). 11a — разрешение имён: namespace, `using` / alias / `global using` / `using static`, вложенные типы, локальные,
  параметры, члены своего типа и базовых, типы из сборок (B1–B2). Сделано: `lang/semantic` хоста — `CSharpNameResolver` (порядок
  поиска C#, арность, суффикс `Attribute`, `global::`, члены после точки у namespace / типа / значения известного типа, унаследованные
  с подстановкой, перегрузки по числу и известным типам аргументов, extension-методы), `CSharpSemanticSession`, `CSharpGlobalUsingIndex`,
  stub-индекс `csharp.namespace`; `NativeCSharpSemanticModel` за `CSharpSemanticModel`; цвета и Go to Declaration берут его ответ там,
  где синтаксис молчит. Гейт — 98,4 % (см. шаг 11, «После C1»)
- [x] C2 (2026-10-05, 0.1.58). 11b — типы членов и простых выражений, `var`, `a.b.c`, `this` / `base`. Сделано: `lang/semantic/
  CSharpExpressionTypes` (тип любого выражения: литералы, имена, доступ к члену и `?.`, вызовы с выводом аргументов-типов generic-методов
  по аргументам и телам лямбд, `new`, приведения, `await`, индексаторы, операторы с продвижениями C# §12.4.7 и пользовательскими,
  `??`, `?:`, кортежи с именами, `switch`, `with`, деконструкция, `foreach`, `out var`, шаблоны, запросы LINQ, параметры лямбд),
  `CSharpTypeDisplay` (вид типа как у Roslyn), выбор перегрузки по лямбдам; `NativeCSharpSemanticModel.typeOf`. Цвета и Go to
  Declaration Built-in разрешают член после точки у любого выражения. Гейт типов — 99,0 % (было 4,9 %), см. шаг 11, «После C2»
- [ ] C3. 11c — completion после точки, Go to Declaration / Find Usages / rename по solution, цвета ссылок, quick documentation,
  parameter info; `CancellationToken` метода в вызов, `await Method` пунктом completion
  - [x] completion после точки (0.1.66, 2026-10-05; `COMPLETION`): `lang/semantic/CSharpMemberLookup` — что стоит за точкой по
    `Qualifier` резолвера: члены типа значения (solution — по частям и базам с подстановкой, сборки — `libraryMembers` с унаследованными),
    extension-методы в области (`CSharpNameResolver.extensionMethodsFor`: ключи сборок по супертипам + все имена stub-индекса, фильтр
    `receiverFits`), static-члены и вложенные типы после типа, namespace и типы после namespace (stub-индекс + `AssemblyIndexSet`),
    `a?.`, `A.B.` в типе, `this.` — и члены библиотечных баз; видимость C# по модификаторам (`Member.modifiers`), protected сборок — только
    через `this`, `EditorBrowsable(Never)` скрыт. Пункты — `lang/NativeCSharpMemberCompletion` (вид, хвост параметров, тип по
    `SemanticType.minimalDisplay`, `()` / `();`); место — `NativeCompletionKind.MEMBER_ACCESS`; дубли сервера убирает прежний merge A6.
    Попутно: extension-методы solution на `this string` (ключевое слово) не находились и резолвером. Тест `CSharpMemberCompletionTest`
  - [x] quick documentation и parameter info (0.1.66; `DOCUMENTATION` получил встроенную реализацию, по умолчанию сервер до робота):
    `lang/NativeCSharpDocumentation` — `DocumentationTargetProvider` перед провайдером LSP-клиента (первый с целями выигрывает), строка
    Quick Info Roslyn (`lang/semantic/CSharpSymbolText.quickInfo`), XML-доки из `///` (как `docTags` rename) и из `AssemblyDocs`;
    `lang/NativeCSharpParameterInfo` — перегрузки строками, выбранная резолвером отмечена; обработчик модуля `roslyn` молчит при Built-in.
    Тест `CSharpQuickDocTest`. Гейт не упал: имена 98,8 % (25 997 из 26 310, неверных 16), типы 99,0 % (38 791 из 39 181, неверных 8) — с `MemberCompletion.cs` площадки; baseline вырос
  - [ ] осталось: Find Usages и rename членов и типов по solution (нужен поиск кандидатов по слову + резолв каждого; Go to Declaration к
    членам solution уже есть с C1), цвета ссылок — уже по резолверу (C1/C2), сверить роботом; `<inheritdoc/>` в доках (база / интерфейс),
    `cref` ссылками в окне документации, доки `var` (тип выражения); completion: ожидаемый тип после точки выше, `await Method` и
    `CancellationToken` пунктами, именованные аргументы в Parameter Info; робот по E-80…E-84 и NATIVE по умолчанию для «Documentation»
- [ ] C4. Веха 12.1: сервер выключен по умолчанию, требование .NET 10 снято, замер памяти без сервера в CHANGELOG

**D. Полный отказ → веха 12.2**
- [ ] D1. 11d — перегрузки, вывод generic-аргументов, лямбды, extension-методы, LINQ
- [ ] D2. 11e — диагностики (неразрешённое имя, тип, число аргументов, недостижимый код) и их fixes
- [ ] D3. Анализаторы из NuGet и их code fixes — помощник с Roslyn по запросу (команда и фон на сохранении)
- [ ] D4. 11f — source generators через помощник
- [ ] D5. Удалить модуль `io.github.dotnetsupport.roslyn`, страницу Language Server, `LSP_PLAN.md` — в историю

**Сквозное:** шаг 0 — исходные замеры (время до первой подсказки, память) сейчас и после каждой вехи, `tools/ui-robot/baseline.py`.

## Шаги подробно

**0. Разведка.** Цель — откалибровать оценки шагов 3–6 до того, как в них вкладываться, и получить исходные цифры.
- Замеры текущего пути на `debug-playground` и на крупном solution (выборка `dotnet/aspnetcore`): время до первой
  подсветки после открытия, время до готовности сервера, память процесса сервера и IDE, задержка completion. Скрипт —
  в `tools/`, цифры — в этот файл (раздел «Исходные замеры») и в CHANGELOG.
- `tools/roslyndump` (как `tools/astdump` go-psi): консольное приложение на Roslyn с закреплённой версией пакета;
  режимы `tokens` (вид, диапазон), `tree` (виды узлов, диапазоны, отсутствующие и пропущенные токены, диагностики
  парсера), `walk` (весь корпус за один запуск, вывод потоком), позже `semantics`. Набор символов `#if` — параметром.
- Корпус: тесты парсера Roslyn (`*ParsingTests.cs`), `debug-playground`, `dotnet/runtime` и `dotnet/aspnetcore`
  (закреплённые теги, скачиваются задачей Gradle).
- Вертикальный срез: вручную (без генератора) перенести разбор **выражений** из `LanguageParser` на `PsiBuilder`
  (приоритеты, `is`/`as`, generic-неоднозначность `a < b > (c)`, лямбды, `switch`-выражения) и сверить с эталоном на
  тестах парсера Roslyn про выражения. Срез выбрасывается или становится основой шага 5.
- Итог шага: места, которые не ложатся на `PsiBuilder` (в `docs/GRAMMAR.md`), пересчитанные оценки 3–6, решение
  «отдельный репозиторий или здесь».

**1. Модули.** Как в Go-плагине: три Gradle-подпроекта, корень подключает их `pluginComposedModule(implementation(project(...)))`,
дескрипторы — `xi:include`. Модуль `io.github.dotnetsupport.roslyn` видит их классы, не наоборот.

**2. Переключатели.** `CSharpFeatures.native(feature)` по настройке; фичи: SYNTAX_TREE (structure / folding / breadcrumbs),
FORMATTING, EDITING (complete statement, ghost text, selection), USAGE_KINDS, NAVIGATION, COMPLETION, DOCUMENTATION,
DIAGNOSTICS, SEMANTIC_COLORS, RENAME. Умолчание — `ROSLYN` для всего, что сейчас даёт сервер; эвристики без сервера
остаются, как сегодня.

**3. Генератор из `Syntax.xml` и правила сверки.** В Roslyn все ~400 видов узлов описаны декларативно
(`src/Compilers/CSharp/Portable/Syntax/Syntax.xml`): имя, базовый тип, поля-токены и поля-узлы. Генератор (Gradle-задача
или скрипт в `tools/`) выдаёт `CSharpElementTypes`, интерфейсы PSI с аксессорами и реализации. Тег Roslyn закрепляется в
`gradle.properties`; переход на новый тег — шаг «новая версия C#» (ниже).

Тем же шагом генератор выдаёт **таблицу соответствия** видов узлов Roslyn ↔ наших и фиксируются **правила нормализации**,
без которых сверка «узел в узел» не определена:
- списки: у Roslyn `SyntaxList` / `SeparatedSyntaxList` — не узлы; у нас узел-список либо его нет — решение по виду,
  записанное в таблице, сверка их пропускает;
- отсутствующие токены Roslyn (нулевой ширины, `IsMissing`) ↔ наш `PsiErrorElement` в той же позиции;
- пропущенные токены (у Roslyn — trivia `SkippedTokensTrivia`) ↔ наш `PsiErrorElement` вокруг них;
- trivia (пробелы, комментарии, директивы) в сверку дерева не входят, сверяются отдельно как токены.

**4. Лексер.** Наш `CSharpLexer` приводится к тому, что ждёт парсер Roslyn: контекстные ключевые слова (`var`, `async`,
`record`, `required`, `field`, …) — идентификаторы; `>` всегда одиночный (парсер склеивает `>>` / `>>=` / `>>>` по
соседству без пробелов); интерполированные и raw-строки — **отдельными токенами внутри строки** (в отличие от Roslyn,
который лексирует строку целиком и перелексирует части в парсере: IntelliJ нужны токены для подсветки и набора);
doc-комментарии `///` — свой лексер XML внутри.

Директивы `#if/#elif/#else/#endif/#region/#nullable/#pragma` — отдельные токены, неактивные ветки — токен «disabled text».
**Все ветки активными не делать:** `#if X class A : B { #else class A { #endif` даёт несбалансированные скобки и дубли
объявлений, а в `dotnet/runtime` такого много. Набор символов:
- до шага 10 — символы по умолчанию для конфигурации Debug и первого TFM проекта (`DEBUG`, `TRACE`, `NET`,
  `NETCOREAPP`, `NETx_0`, `NETx_0_OR_GREATER`, …), вычисленные из TFM из файла проекта;
- после шага 10 — `DefineConstants` из MsBuildHost.

Multi-targeting: у файла разные символы под разные TFM. Активный TFM — тот же, что выбран в конфигурации сборки
(`build`, Debug/Release + TFM); его смена перепарсивает файлы проекта. Лексер IntelliJ контекста проекта не знает, поэтому
набор символов передаётся через user data файла / `FileViewProvider` и учитывается в ключе кэша дерева.

**5. Парсер.** Перенос `LanguageParser` (+ части про паттерны и интерполированные строки), `DirectiveParser`,
`DocumentationCommentParser` на `PsiBuilder`. Ложится почти механически: рекурсивный спуск, приоритеты операторов,
спекулятивный разбор (`ResetPoint`/`Reset` → `mark()`/`rollbackTo()`), методы `Scan*` над токенами, восстановление
(`SkipBadTokens`, `TerminatorState` → `builder.error()`), склейка `>>` — `remapCurrentToken`/`advanceLexer` по соседству.
Переделываются: места, где Roslyn перестраивает готовые узлы (перенос лишних токенов, ошибка на созданном узле), — по
смыслу через `precede()`/`done()`. Блендер Roslyn (инкрементальность) не переносится — вместо него ленивые тела методов,
аксессоров и лямбд (`ILazyParseableElementType`).

**Контекст ленивых тел.** Парсер Roslyn разбирает тело с учётом окружения: `await` — ключевое слово в `async`-члене,
`field` — в аксессорах свойства, `yield` — в итераторах, query-выражения, `value` в сеттере. При ленивом перепарсинге
тела этот контекст восстанавливается по объявлению-владельцу (модификаторы, вид аксессора) и передаётся парсеру; правка
модификатора владельца (добавили `async`) инвалидирует тело. Тест: дерево тела, разобранного лениво, совпадает с деревом
того же тела, разобранного целиком.

Проверки `LangVersion` (`CheckFeatureAvailability`) — позже, как предупреждения.

**6. Корпус.** `corpusTest`: сверка токенов лексера и дерева парсера с `roslyndump walk` по таблице и правилам шага 3
(как `GorootAstDiffCorpusTest` go-psi); мутационный фазз (0 исключений, `localityViolations` — метрика); бенчмарки
парсинга и инкрементального репарса тела с порогами. Метрики — `testData/metrics/*.json`, только улучшаются. Корпус: `debug-playground`, тесты парсера Roslyn (`*ParsingTests.cs` — источник образцов, включая ошибочный код),
выборка исходников `dotnet/runtime` и `dotnet/aspnetcore` (закреплённые теги, скачиваются задачей Gradle, в репозиторий
не кладутся; эталон снимается с тем же набором символов `#if`, что у нашего лексера). Гейт: 0 расхождений на корректном
коде; на ошибочном — 0 исключений и сходное восстановление (доля совпадений, порог). Время парсинга корпуса — в CHANGELOG.

**7. Подмена парсера (атомарно).** `CSharpParserDefinition` → новый парсер; мост: всё, что сегодня читает
`CSharpDeclarations` (Structure, breadcrumbs, folding, Go to Class, IL Viewer, ghost text, Go to Base, run-gutter), — через
PSI; `CSharpDeclaration`-узлы и сканер удаляются этим же шагом или оставляются фасадом до шага 9. Поднять версии стабов /
индексов. Гейт — шаг 5 Go-плагина: тесты, корпус, робот, живая проверка.

**Подготовка шага 7: фасад `CSharpSyntaxModel` (2026-10-04).** Все потребители эвристических объявлений спрашивают
один интерфейс `lang/CSharpSyntaxModel.kt`; реализация сегодня — `HeuristicCSharpSyntaxModel` (сканер
`CSharpDeclarations`, узлы `CSharpDeclaration`, поиск методов с атрибутами по токенам, перенесённый из `TestDiscovery`).
Подмена парсера меняет только `CSharpSyntaxModel.current` (выбор потом — по `CSharpFeature.SYNTAX_TREE`; он зависит только
от настроек приложения, поэтому потребителям без проекта тоже годится). Обход фасада ловит
`CSharpSyntaxSnapshotTest.testConsumersGoThroughTheFacade`.

API: `declarations(text)` / `declarations(file)` → `CSharpFileStructure` (дерево `CSharpDeclarationInfo`: вид, имя и его
диапазон, диапазон с атрибутами, тело `{…}`, параметры и тип как написаны, модификаторы, дети; блок `using`; `pathTo(offset)`,
`containersOf`, `qualifiedName`); `declarationOf(element)`, `childDeclarations(parent)`, `declarationElementAt(file, offset)` —
PSI-элементы объявлений для навигации; `attributedMethods(text, names)` — методы с атрибутами и CLR-имена типов файла.
На Roslyn ложится так: `BaseNamespaceDeclaration` / `BaseTypeDeclaration` / `DelegateDeclaration` / `MemberDeclaration` →
`CSharpDeclarationInfo`, `Block` → `body`, `UsingDirective`s `CompilationUnit` → `usings`. Упрощения модели, которые PSI
должен воспроизвести или поменять осознанно: деструктор — CONSTRUCTOR `~Name`, conversion operator — OPERATOR,
`record struct` — RECORD, `int a, b;` — одно поле по первому имени, локальные функции и top-level statements не видны.

| Потребитель | Что спрашивает | Источник до фасада |
|---|---|---|
| Structure view, File Structure (`CSharpStructureView.kt`) | дочерние объявления элемента, presentation, объявление под кареткой | узлы `CSharpDeclaration` |
| Breadcrumbs, sticky lines | объявление элемента: имя, есть ли параметры, вид | узлы |
| Folding (`CSharpFolding`) | тела объявлений, блок `using` (комментарии, `#region` — лексер) | `CSharpDeclarations.scan` |
| Go to Class / Symbol (`CSharpDeclarationIndex`, `CSharpGotoContributor`) | имена типов и членов текста; элементы с данным именем | скан + узлы |
| IL Viewer (`IlViewerLogic.names`) | путь у offset: namespace, вложенные типы (арность — по тексту), член, параметры, модификаторы | скан |
| Серый текст (`CSharpGhostText`) | объемлющий тип и его поля / свойства / конструкторы, член у каретки, типы текста без текущей строки | скан |
| Go to Base (`RoslynGotoSuper`, `RoslynBaseMembers`) | член у каретки и его тип, тело; типы файла базового типа и их члены | скан |
| Ctrl+наведение (`RoslynCtrlHover`) | объявление, чьё имя стоит в offset | узлы |
| Go to Implementation (`RoslynGotoImplementation`) | объявление у offset: presentation, контейнер, иконка | узлы |
| ▶ тестов, Unit Tests, консоль тестов (`TestDiscovery`) | методы с `[Fact]` / `[Test]` / …, имя типа с `+`, диапазоны имён типов | свой сканер токенов |
| Точки останова (`CSharpBreakpointLines`), inline values (`CSharpInlineValues`) | блок `using`, путь, тело / имя члена у строки | скан + лексер |
| Find Usages: вид и группировка (`CSharpUsageKinds`, `CSharpUsageGrouping`) | диапазоны имён объявлений; типы и член вокруг usage | скан |
| Типы в области (`CSharpScopeTypes`) | члены объемлющего типа, параметры метода | скан |
| Intentions, Move File, namespace (`CSharpIntentions`, `CSharpMoveFile`, `RoslynNamespaceAdjuster`) | типы верхнего уровня, единственный namespace и его диапазон | скан |
| Rename файла (`RoslynFileRename`) | типы файла | скан |
| `$CLASS$`, `///` (`CSharpTemplatesAndDocs`) | объемлющий тип, объявление после комментария: вид, параметры, тип | скан |
| Аллокации (`AllocationsService`) | методы / конструкторы / свойства и их диапазоны | скан |

Не за фасадом (свои сканеры, к объявлениям не относятся; на PSI — шагом 9 по фиче): `EndpointScanner` (`MapGet`, `[HttpGet]`),
`EfSources` (регулярки: классы `DbContext` / `Migration`, `[Migration]`), `TypeDeclarationScanner` (первый тип файла для New
Item и `partial`), токенные эвристики `CSharpCalls`, `CSharpExpressions`, `CSharpIndent`, `CSharpSelection` и др. — они живут
на лексере. Поиск `Bind` / `GetSection` для `appsettings` — в помощнике (`helpers/dotnethelper/AppSettings.cs`, синтаксис
Roslyn в процессе), в Kotlin его нет.

**Снимки.** `CSharpSyntaxSnapshotTest`: по файлу — объявления фасада, Structure view, folding, breadcrumbs, имена IL
Viewer и член Go to Base по строкам, строки точек останова, ▶ тестов, Go to Class / Symbol. Входы — замороженная копия
`debug-playground` (20 файлов) и синтетика (generics, вложенные и partial, records, file-scoped namespace, top-level
statements, атрибуты на членах, `#if`, строки с фигурными скобками) в `src/test/resources/syntaxSnapshots`, золотые
файлы рядом. Тот же тест на коде до фасада дал те же золотые файлы. Реализация на PSI сверяется с ними; расхождение
разбирается, потом обновляется золотой файл. Ошибки эвристик, видные в снимках (записаны как есть, не исправлены):
- `#if` / `#else` с двумя заголовками метода (`IfBranches.cs`): ветки сканируются обе, метод съедает остаток класса —
  `_debugOnly`, `Helper`, `After` пропадают из Structure, breadcrumbs `Run()` до конца класса;
- top-level `using (…) { … }` (`TopLevelStatements.cs`): сканер ищет `;` после `using` и проглатывает следующий
  `record Settings(…);` — записи нет ни в Structure, ни в Go to Class;
- `[assembly: …]` перед `namespace` входит в диапазон namespace (`AttributesOnMembers.cs`, breadcrumbs со строки 5);
- `int _first, _second;` и `public T Left, Right;` — видно только первое поле (`Generics.cs`);
- у `record Box<T>(T Value)` нет первичного конструктора в presentation (`Records.cs`), у `Person(…)` есть;
- строки точек останова: auto-property без инициализатора (`Friend { get; set; }`, `Note { get; init; }`) и `const`-поля
  помечены как исполняемые;
- атрибут enum-члена не входит в его диапазон (`[Obsolete] Second`), у остальных объявлений входит.

**8. Stub-индексы.** Типы, члены, extension-методы, атрибуты (`[Fact]`, `[Test]`) — стабами; `CSharpDeclarationIndex`
удаляется. Тесты с запретом загрузки AST там, где хватает стабов. Замер индексации `dotnet/runtime` — с этого шага.
(Решение 2026-10-04: `CSharpDeclarationIndex` не удалён, а сужен до эвристического дерева — пока жив переключатель SYNTAX_TREE = ROSLYN,
Go to Class там нужен индекс; уходит вместе с эвристическим деревом.)

**9. Синтаксические фичи на PSI**, каждая за флагом, по одной: форматирование и отступы (вместо `resources/csharpIndent/rules.json`
— форматтер платформы по дереву; правила, которые пользователь уже видел, — тестами из `examples` файла правил; правило в
`CLAUDE.md` «новые отступы — правилом в `rules.json`» меняется этим же шагом), Complete Statement и серый текст, Extend
Selection, Surround With, вид использования для Find Usages (заменяет эвристику `CSharpUsageKinds`), локальные
rename / extract / inline variable, `if` ↔ тернарный, block ↔ expression body, Structural Search. Наши «потребители»
эвристик: IL Viewer (член под курсором), `appsettings` (поиск `Bind` / `GetSection` — переезжает из помощника на PSI).

**Веха S — синтаксис на своём PSI.** Условие: 7–8 и основные фичи 9 на `NATIVE` прошли релиз без отката. Что получает
пользователь: всё синтаксическое работает сразу при открытии файла, без ожидания сервера. Сервер остаётся для семантики.
**Точка решения:** по замерам шага 0 и опыту шагов 3–9 решить, идти ли в шаг 11 целиком, только в 11a–11c (навигация и
completion) или остановиться на гибриде «свой синтаксис + сервер для семантики».

**10. Project model.** Из вычисления MsBuildHost (0.1.28): исходники проекта (`Compile`), `DefineConstants` по TFM и
конфигурации (для `#if`, шаг 4), `LangVersion`, `Nullable`, `ImplicitUsings` и `Using` items (global usings),
`RootNamespace`. Ссылки: `project.assets.json` и индекс сборок (`indexer/`) — формат расширяется до того, что нужно
семантике: сигнатуры с generics, атрибуты, extension-методы, иерархия типов, константы. Сборки — библиотеками платформы
(library roots), как GOROOT в Go-плагине. От парсера не зависит — ведётся параллельно с 3–6.

**11. Семантика по слоям**, каждый — со сверкой по Roslyn (метод помощника `semantics {file}`: символ каждого
идентификатора с его объявлением, тип каждого выражения) и порогом совпадений на корпусе:
- 11a — разрешение имён: namespace, `using` / alias / `global using` / `using static`, вложенные типы, локальные,
  параметры, поля и члены своего типа и базовых;
- 11b — типы членов и простых выражений, `var` из инициализатора, доступ к членам (`a.b.c`), `this` / `base`;
- 11c — фичи на 11a–b: completion после точки, Go to Declaration, Find Usages по solution, rename, семантические цвета,
  quick documentation, parameter info; подсказки для частых вызовов (ROADMAP, «Подсказки для частых вызовов», просьба
  пользователя 2026-10-04): `CancellationToken` метода в вызов, у которого он последний параметр, `await` у вызова, вернувшего
  `Task`. Синтаксическая часть — раньше, на шаге 9 (фича EDITING, серый текст): `return Task.FromResult(|);` / `Task.CompletedTask`
  в не-`async` методе с `Task<T>` / `Task`, `async` в заголовок по набранному `await`;
- 11d — перегрузки, вывод generic-аргументов, лямбды (в обе стороны), extension-методы, LINQ (запросы как вызовы),
  неявные преобразования, `dynamic` как «не проверять»;
- 11e — диагностики: неразрешённое имя, несовпадение типа, неверное число аргументов, недостижимый код; без претензии на
  полный паритет с компилятором;
- 11f — source generators: помощник запускает генераторы проекта и отдаёт сгенерированные файлы; PSI видит их как
  исходники только для чтения (как `obj/generated` при `EmitCompilerGeneratedFiles`), так что вызовы `[GeneratedRegex]`,
  `LoggerMessage`, `CommunityToolkit.Mvvm` разрешаются.

**Оракул семантики (задача C0, 2026-10-05).** Сверка слоёв 11 — не метод помощника у пользователя, а инструмент разработки,
как синтаксический оракул: команда `semantics` в `tools/csharp-psi/roslyndump` (тот же Roslyn 5.9.0, что у парсера; формат —
README инструмента, раздел «semantics»). Компиляцию строит так, как её должен видеть плагин: для проекта — командная строка
`csc` из design-time `Compile` (`CscCommandLineArgs`, ~1 с, ничего не собирается): те же исходники (с `GlobalUsings.g.cs` из
`obj`), ссылки, `DefineConstants`, `LangVersion`, `Nullable`; `ProjectReference` — компиляцией из исходников, не dll (символы
соседнего проекта объявлены в исходнике, как в solution плагина); генераторы исходников запускаются. Для корпуса — файлы
библиотеки одной компиляцией против свежего reference pack (`--assembly` убирает ref-сборку самой библиотеки). Вывод — по
идентификатору: роль (объявление / ссылка), символ (вид, doc-id или `Local:x`, места объявления `путь:смещение` / `asm:` / `ns`,
кандидаты с причиной), флаги (`acc` после точки, `own` / `inh` — член своего / базового типа, `kw` — `var`, `nameof`…); по
выражению — тип и приведённый тип; диагностики компилятора. Гейт — `./gradlew semanticGate` корневого проекта
(`CSharpSemanticGate`, `docs/csharp-psi/TESTING.md`, «Semantic gate»): кладёт исходники ввода в light-проект с `#if` и версией
языка из дампа и спрашивает интерфейс `CSharpSemanticModel` модуля `csharp-psi-semantic` (`symbolAt`, `typeOf`, `diagnostics`) —
его и будет реализовывать свой резолвер. Гейт в корневом проекте, а не в `-semantic`: резолверу нужно то, что даёт файлу хост
(опции разбора проекта, регистрация парсера, stub-индекс, дальше — модель проекта шага 10). Базовая линия — счётчики верных
ответов по вводу и категории в `src/test/resources/semanticGate/baseline.txt`, только растут. Сейчас за интерфейсом —
`SyntacticSemanticModel` (тесты): Go to Declaration нативного дерева (A2) + собственные объявления файла, типы только у
литералов, диагностик нет.

Исходные цифры (2026-10-05; ввод: playground Console + Web, runtime `System.Linq`, `System.Threading.Channels`,
`Microsoft.Extensions.Primitives` — 135 файлов, 32 314 привязанных Roslyn идентификаторов, 38 575 типизированных выражений):

| 11a, категория | всего | верно | неверно | % |
|---|---:|---:|---:|---:|
| локальные | 4196 | 4196 | 0 | 100 |
| параметры (с именованными аргументами) | 3623 | 3470 | 0 | 95,8 |
| локальные функции | 32 | 21 | 0 | 65,6 |
| метки | 16 | 16 | 0 | 100 |
| параметры типа | 4255 | 4255 | 0 | 100 |
| члены своего типа без точки | 2666 | 2455 | 207 | 92,1 |
| унаследованные члены | 303 | 0 | 2 | 0 |
| прочие члены (`using static` …) | 26 | 0 | 0 | 0 |
| типы solution | 2080 | 2049 | 0 | 98,5 |
| типы из сборок | 3205 | 0 | 2 | 0 |
| namespace | 1016 | 0 | 0 | 0 |
| после точки: члены solution | 1951 | 2 | 0 | 0,1 |
| после точки: члены сборок | 2355 | 0 | 0 | 0 |
| `var` и др. контекстные | 194 | 0 | 0 | 0 |
| кандидаты (Roslyn не выбрал) | 14 | 3 | 3 | 21 |
| **всё, кроме объявлений** | **25 932** | **16 467** | **214** | **63,5** |
| объявления (имя само себя) | 6382 | 6359 | 0 | 99,6 |

После единого резолвера (0.1.53, 2026-10-05: `NativeCSharpScopes` + `NativeCSharpResolver`, локальные функции до объявления, базовые
классы, `using static`, `Type.X`): всё, кроме объявлений, — **17 701 из 25 984 (68,1 %)**, неверных 282 (было 214: резолвер отвечает
чаще, прирост неверных — перегрузки членов своего типа). Console 51,6 %, Web 38,1 %, Linq 71,6 %, Channels 58,9 %, Primitives 64,0 %.

**После C1** (0.1.57, 2026-10-05; гейт индексирует сборки ввода индексатором плагина и подставляет их резолверу, модель гейта —
`ResolvingSemanticModel`; площадка с новым `LibraryNames.cs`): всё, кроме объявлений, — **25 619 из 26 042 (98,4 %)**, неверных **20**
(было 282), не разрешено 403. «До C1» — та же площадка без `LibraryNames.cs` (25 984 имени, 17 701 верно).

| 11a, категория | всего | до C1 | после C1 | неверно | % |
|---|---:|---:|---:|---:|---:|
| параметры (с именованными аргументами) | 3638 | 3480 | 3614 | 0 | 99,3 |
| члены своего типа без точки | 2674 | 2457 | 2582 | 0 | 96,6 |
| унаследованные члены | 303 | 296 | 301 | 2 | 99,3 |
| прочие члены (`using static` …) | 29 | 20 | 25 | 0 | 86,2 |
| типы solution | 2081 | 2074 | 2080 | 0 | 100 |
| типы из сборок | 3217 | 0 | 3217 | 0 | 100 |
| namespace | 1032 | 0 | 1032 | 0 | 100 |
| после точки: члены solution | 1955 | 855 | 1900 | 6 | 97,2 |
| после точки: члены сборок | 2377 | 0 | 2218 | 9 | 93,3 |
| `var` и др. контекстные | 203 | 0 | 118 | 2 | 58,1 |
| кандидаты (Roslyn не выбрал) | 14 | 3 | 13 | 1 | 92,9 |

Остаток: члены сборок — `Nullable<T>.HasValue` / `GetValueOrDefault` и `KeyValuePair.Key` у `var` из `foreach` и лямбд (тип элемента —
11b/11d), перегрузки с неизвестным типом аргумента (лямбда, `default`, интерполированная строка → `AssertInterpolatedStringHandler`),
ограничения generic-методов (`IndexOf<T> where T : IEquatable<T>`), `params ReadOnlySpan`. Члены своего типа — перегрузки с аргументами
неизвестного типа. `var` — тип выражений справа (11b) и `_`. `[OverloadResolutionPriority]` индекс хранит без значения: кандидат с ним
уступает остальным (так библиотеки его и используют).

По вводам (всё, кроме объявлений): Console 48,8 %, Web 35,7 %, Linq 66,7 %, Channels 54,7 %, Primitives 59,3 %. 11b: 4,9 %
(1885 из 38 575 — литералы, 92 % их; `default` / `null` без целевого типа не умеем). 11e: 0 из 45 ошибок и 12 предупреждений.
Что видно для C1: синтаксис уже закрывает локальные, параметры типа, метки и типы solution; почти все 207 неверных «членов своего
типа» — перегрузки (синтаксис отдаёт все одноимённые методы, Roslyn — выбранную), это 11d; именованные аргументы (`x: 1`) и
локальные функции, вызванные до объявления, — дыры синтаксиса; весь остальной объём C1 — namespace, типы из сборок,
унаследованные члены и доступ после точки (≈10 800 имён), C2 — типы выражений. Не засчитать никак: неявные символы без места
объявления (`args` верхнего уровня, 4 имени).

**После C2** (0.1.58, 2026-10-05; `ResolvingSemanticModel.typeOf` — `NativeCSharpSemanticModel.typeOf`, тип показан как у Roslyn;
площадка с новым `ExpressionTypes.cs`, поэтому «всего» чуть больше, чем «до»: «до C2» — 38 855 выражений, после — 39 060). Типы (11b):
**38 670 из 39 060 (99,0 %)**, было 1913 из 38 855 (4,9 %); неверных **8** (было 0: до C2 типов, кроме литералов, не было вовсе).

| 11b, категория | всего | до C2 | после C2 | неверно | % |
|---|---:|---:|---:|---:|---:|
| литералы | 2091 | 1913 | 2081 | 0 | 99,5 |
| имена | 21857 | 0 | 21698 | 0 | 99,3 |
| доступ к члену | 2222 | 0 | 2156 | 0 | 97,0 |
| вызовы | 3425 | 0 | 3335 | 7 | 97,4 |
| создание объектов | 554 | 0 | 549 | 0 | 99,1 |
| `this` / `base` | 105 | 0 | 105 | 0 | 100 |
| операторы | 4457 | 0 | 4418 | 0 | 99,1 |
| лямбды | 6 | 0 | 0 | 0 | 0 |
| синтаксис типов | 3910 | 0 | 3906 | 1 | 99,9 |
| прочее | 433 | 0 | 422 | 0 | 97,5 |

Имена (11a) от типов выражений выросли: всё, кроме объявлений, — **25 918 из 26 231 (98,8 %)**, было 25 688 из 26 113 (98,4 %), неверных
16 (было 20), не разрешено 297 (было 405). После точки: члены сборок 2335 (было 2227), члены solution 1929 (было 1900); `var` и контекстные
148 (было 121). Неверные типы: `TMinMax.Compare` (перегрузка static abstract члена интерфейса, 6), явная реализация `GetEnumerator`,
имя элемента кортежа `(bool _, T Priority)`. Не разрешено: лямбды (тип лямбды без целевого — natural type, C# 10), перегрузки с
`default` / интерполированной строкой, `IQueryable`-запросы, `stackalloc`, указатели. Скорость цветов на 1000 строках: без лямбд
15–35 мс, с LINQ и лямбдами в каждом методе 60–100 мс тёплым (кэши сессии на перегрузки, интерфейсы и extension-методы сборок).

**12. Отказ от `roslyn-language-server`: две вехи.**
- **12.1 — сервер опционален.** Условие: 9 и 11a–11c на `NATIVE` прошли релиз без отката. Сервер выключен по умолчанию
  для новых установок; включают ради анализаторов, code fixes и полного паритета диагностик. **Требование .NET 10 снимается**
  (остаётся SDK, которым собирается проект; помощники — dll на нём). Замер памяти IDE без сервера — в CHANGELOG.
- **12.2 — сервер удалён.** Условие: 11d–11f на `NATIVE`. Модуль `io.github.dotnetsupport.roslyn` удаляется; анализаторы
  из NuGet и их code fixes — через помощник с Roslyn по запросу (как линтеры в Go-плагине: по команде и в фоне на
  сохранении), не через LSP.

## Новая версия C# (каждый год)

Закрепить новый тег Roslyn → перегенерировать PSI (шаг 3) → перенести diff `LanguageParser` между тегами → корпусный
гейт (новые тесты парсера Roslyn входят в корпус) → семантика новых конструкций по слоям 11. Чтобы перенос diff был
процедурой, а не чтением всего файла: в шапке каждого перенесённого метода — имя исходного метода Roslyn, скрипт в
`tools/` по `git diff` двух тегов перечисляет изменённые методы и наши соответствующие. Оценка — несколько дней на
синтаксис, семантика — по конструкции.

## Риски

- **Объём больше, чем кажется по Go.** Снимается шагом 0 (оценки по замеренной скорости переноса) и вехой S с точкой
  решения: синтаксис полезен и без своей семантики.
- **Семантика C# велика** (перегрузки, вывод типов, лямбды, LINQ). Снимается слоями с порогом совпадений по эталону:
  фича не включается по умолчанию, пока её слой не прошёл гейт; сервер остаётся до вехи 12.1.
- **`#if` и multi-targeting** — дерево зависит от набора символов. Снимается символами по TFM с первого дня (шаг 4) и
  эталоном с тем же набором (шаг 6).
- **Производительность на больших solution** (индексы метаданных сборок, резолв). Замеры на `dotnet/runtime` с самого
  шага 8; пороги — как `benchmark` Go-плагина; исходная точка — шаг 0.
- **Два парсера в памяти** на время перехода (сервер и свой PSI). Терпимо до вехи 12.1; замер памяти — в каждом релизе.
- **Расхождение с компилятором** у пользователя заметнее, чем у Go (больше неявного). Поэтому диагностики (11e) идут
  последними и только тех видов, где совпадение по корпусу полное.
- **Source generators** без помощника не видны — 11f обязателен до вехи 12.2.

## Исходные замеры

Скрипт — `tools/ui-robot/baseline.py` (как запускать и что именно меряется — `tools/ui-robot/README.md`, «Замеры редактора»): песочница
`runIdeForUiTests`, времена снимаются внутри IDE, по 3 прогона на цель, completion — 10 повторов на вид в каждом прогоне. Цели:
`debug-playground` (открывается `Console/Scenarios.cs`, 284 строки) и выборка `dotnet/aspnetcore` v10.0.12 — `Microsoft.AspNetCore.Mvc.Core`
(536 файлов `.cs` с общими исходниками, собирается `tools/ui-robot/make-aspnetcore-sample.py` в обычный SDK-проект на
`Microsoft.AspNetCore.App`, 4 ошибки компиляции; открывается `src/ControllerBase.cs`, 2 842 строки). Путь ROSLYN: `roslyn-language-server`
5.12.0-1.26426.8 + эвристики плагина 0.1.40. Время — от команды открытия проекта (сервер, готовность) или от открытия файла (остальное).
В таблице медиана трёх прогонов, в скобках — все три.

**2026-10-04, ROSLYN, кэш токенов холодный** (снято агентом роботом; машина: Ryzen 5 8400F, 12 потоков, 32 ГБ, свободно 9–10 ГБ, загрузка
CPU 30–50 % от параллельных сборок Gradle в соседних worktree; песочница `-Xmx2048m`, `idea.is.internal`; вживую пользователем не сверено)

| Замер | debug-playground | aspnetcore Mvc.Core |
|---|---|---|
| Файл открыт, раскраска лексера, мс | 273 (386, 238, 273) | 349 (293, 349, 406) |
| Первые цвета идентификаторов на экране, мс | 5 531 (5 531, 5 649, 4 688) — эвристика не красила, первые цвета от сервера | 1 008 (962, 1 008, 1 177) — эвристика |
| Сервер: процесс запущен, мс от открытия проекта | 958 (629, 958, 1 561) | 615 (771, 565, 615) |
| Сервер готов (workspace loaded), мс от открытия проекта | 5 125 (4 914, 5 164, 5 125) | 3 510 (3 522, 3 317, 3 510) |
| Семантические токены сервера: ответ / на экране, мс | 5 421 / 5 531 | 3 935 / 4 036 |
| Диагностики сервера (`textDocument/diagnostic`): первый ответ, мс | 7 821 (7 821, 7 962, 6 554) | 31 346 (30 557, 31 346, 35 831) |
| Цвета идентификаторов пропадают целиком на время (замер раз в 100 мс), раз | 1 (0, 1, 1) | 4 (4, 4, 4) |
| Heap IDE после GC: готов / после правки, МБ | 650 / 658 | 628 / 633 |
| RSS IDE: готов / после правки, МБ | 2 994 / 2 639 | 2 601 / 2 609 |
| RSS сервера (`Microsoft.CodeAnalysis.LanguageServer`): готов / после правки, МБ | 484 / 893 | 964 / 1 904 |
| RSS сервера с дочерними процессами: готов / после правки, МБ | 533 / 944 | 1 014 / 1 955 |
| Completion после `.` (до списка с ожидаемым элементом), медиана / p90, мс | 60 / 147 | 103 / 162 |
| Completion по префиксу идентификатора, медиана / p90, мс | 73 / 97 | 161 / 191 |

Правка — 312–324 символа (4 раза по две строки) по одному в 60 мс в методе на якоре `measure-edit`, затем текст возвращён. Проблем
(warning и выше) в обоих файлах нет ни до, ни после сервера, поэтому «диагностики на экране» не видны по числу подсветок — в таблице только
время ответа. Для `NATIVE` — те же цели и тот же скрипт; цифры сравнивать в одной песочнице.

Замечено по ходу (причины не разбирались, вживую не сверено): на `debug-playground` эвристика плагина не раскрасила идентификаторы
`Scenarios.cs` до готовности сервера (0 подсветок с цветом до первых токенов сервера), на `ControllerBase.cs` — раскрасила за ~1 с;
при `--cache warm` (тот же путь копии, 3 прогона, 2026-10-04) кэш токенов плагина не отдал файл ни разу — времена как у холодного
(первые цвета 6 480 мс, сервер готов 5 733 мс); число подсветок с цветом несколько раз падает до нуля между обновлениями токенов
(строка «пропадают целиком»).

## Чек-лист текущего состояния

- [ ] Шаг 0 — разведка (в `../csharp-psi`, подробно — его `docs/PLAN.md`)
  - [x] 2026-10-04: репозиторий `../csharp-psi` 0.0.1 — модули, песочница с PsiViewer, `test buildPlugin` зелёный
  - [x] 2026-10-04: `tools/roslyndump` (`tokens`, `tree`, обход каталога) и `tools/fetch-roslyn.sh`; `src` Roslyn —
    252 файла, 1,6 млн узлов за ~4 с. Объём переноса: `LanguageParser` 14 765 строк + паттерны, интерполяция,
    директивы, doc-комментарии ≈ 18,7 тыс.; в `Syntax.xml` 252 класса узлов, 1018 видов
  - [ ] исходные замеры текущего пути (сервер + эвристики): 2026-10-04 скрипт `tools/ui-robot/baseline.py` и цифры роботом
    (раздел «Исходные замеры»); осталось — подтверждение пользователем вживую
  - [x] 2026-10-04: корпус `dotnet/runtime` и `dotnet/aspnetcore` на тегах `v10.0.12` (`tools/fetch-corpus.sh`):
    20 466 + 10 170 файлов, 261 + 62 МБ `.cs`, 30 + 7,6 млн узлов, `roslyndump tree` за ~40 + ~13 с; файлов с
    ошибками Roslyn без `--define` — 17 + 16 (`#error` в активной ветке `#if`, шаблоны, два неверных теста)
  - [x] 2026-10-04: вертикальный срез (csharp-psi 0.0.2): выражения, типы, паттерны, лямбды и минимум операторов
    на `PsiBuilder` — ≈6 800 строк Roslyn → 5 015 строк Kotlin за ≈1 ч работы агента; 0 расхождений с Roslyn на 714
    корректных и 368 ошибочных входах парсинг-тестов Roslyn. Все трудные места легли на `PsiBuilder` (без
    промежуточного дерева). Сверх плана — лексер шага 4 без `#if`: 0 расхождений на 42,5 млн токенов
    Roslyn/runtime/aspnetcore. Новые оценки: шаг 3 — 1 сессия, 4 — 0,5–1, 5 — 3–4, 6 — 1
- [x] Шаг 1 — модули (2026-10-04, 0.1.40): три пустых подпроекта с `package.kt` и `META-INF/csharp-psi-*.xml` — имена, пакеты
  и дескрипторы как в `../csharp-psi`, каталог версий `gradle/libs.versions.toml` тот же, так что перенос шага 7 — копия.
  `pluginComposedModule`: классы в основном jar плагина, дескрипторы — `xi:include` из `plugin.xml` (все три, пока пустые)
- [x] Шаг 2 — переключатели (2026-10-04, 0.1.40): `lang/CSharpFeatures.kt` (`CSharpFeature`, `CSharpFeatureSource`,
  `CSharpFeatures.native`), выбор — в `RoslynLanguageServerSettings.features` (только отличные от умолчания). **Что значит
  NATIVE до нативного кода:** ничего — у фичи флаг `hasNative`, пока он `false`, `native()` всегда ложь, сохранённый NATIVE
  игнорируется, а на странице Language Server строки нет (группа «Source of Features» появится с первой фичей, у которой есть
  реализация; тексты en/ru уже в бандле). ROSLYN — сегодняшний путь целиком: сервер, где он отвечает, эвристики, где нет.
  Правило: нет реализации → ROSLYN; индексация и фича на индексах → ROSLYN; сервер выключен → NATIVE; иначе — выбор.
  Модуль `roslyn` спрашивает `RoslynFeatures.serves` на каждый запрос (без рестарта): диагностики, rename, document
  highlights (NAVIGATION), семантические токены, форматирование, completion LSP и `RoslynLambdaCompletion` (COMPLETION),
  parameter info (DOCUMENTATION), Go to Super (NAVIGATION), серый текст лямбд (EDITING). Не подключено: hover, type / call
  hierarchy, Go to Implementation, code lens, on-type formatting, эвристики основной части (SYNTAX_TREE, EDITING) — их
  обработчики спрашивают переключатель вместе с нативной реализацией шага 9. Слот на язык (`codeInsight.gotoSuper`,
  parameter info) тогда — делегированием, как 8c в Go-плагине. Тест — `CSharpFeaturesTest`
- [x] Шаги 3–6 — в `../csharp-psi` до переноса (2026-10-04; подробно — `docs/csharp-psi/PLAN.md` и `CHANGELOG.md` csharp-psi 0.0.2–0.0.3 и
  Unreleased): PSI из `Syntax.xml` (`roslyndump gen-psi`), лексер с `#if`, весь `LanguageParser`, ленивые тела, версии языка до C# 7.3,
  doc-комментарии. Гейты на 0: деревья (Roslyn src, runtime, aspnetcore, playground, netfx C# 7.3), лексер, doc-комментарии, аксессоры PSI,
  фазз; мутационный гейт — 1 известный мутант; parsing tests — 0, кроме `ParseBigExpression`
- [x] Шаг 7 — подмена парсера (2026-10-04, 0.1.45; работа разбита на агентов: A — перенос, B1 — модель, B2 — переключатель, B3 — эвристики)
  - [x] 2026-10-04: фасад `CSharpSyntaxModel`, все потребители объявлений через него, снимки `CSharpSyntaxSnapshotTest`
    (инвентаризация и найденные ошибки эвристик — в описании шага 7)
  - [x] A — перенос: код csharp-psi целиком (раздел «Решение» выше). Один язык `C#` — объект ядра, в хосте `typealias CSharpLanguage` и
    `typealias CSharpFile` (эвристическое дерево — `HeuristicCSharpFile` от того же класса, так что `is CSharpFile` верно для обоих).
    `lang/CSharpParserDefinition` — переключатель между `HeuristicCSharpParserDefinition` и определением ядра; `createElement` — по типу
    элемента, наборы пробелов / комментариев / строк — объединение обоих деревьев. Ядро парсит своим определением явно (`NATIVE`), не через
    поиск по языку. Корпусные гейты в плагине — те же 0
  - [x] B1 — `lang/NativeCSharpSyntaxModel` поверх PSI; обе модели отвечают на элементы обоих деревьев (делегируют по классу). Поле с
    несколькими деклараторами — объявление на декларатор. В ядре только синтаксические крючки (`psi/CSharpDeclarationNames`: имя, `getName`,
    `getTextOffset` по имени, presentation через `ItemPresentationProviders`); presentation и иконки — хост (`NativeCSharpPresentation`).
    Снимки — в обоих режимах (`<вход>.native.txt`); 6 из 7 ошибок эвристик ушли, седьмая (строки точек останова у auto-property и `const`)
    не от модели — `CSharpBreakpointLines` по токенам, шаг 9. `CSharpDeclarationIndex.VERSION` = 2. Тест — `NativeCSharpSyntaxModelTest`
  - [x] B2 — переключатель SYNTAX_TREE предложен (`hasNative = true`; без сервера по правилу — встроенное). `CSharpSyntaxTrees.nativeTree()` = `CSharpFeatures.native(SYNTAX_TREE)` (только
    настройки приложения). Смена ответа (`RoslynLanguageServerSettings.CHANGED` → `CSharpSyntaxTreeSwitch`): закэшированные C#-файлы
    открытых проектов с деревом другого вида — `FileContentUtilCore.reparseFiles`, рестарт подсветки, `requestRebuild` индекса
    `CSharpDeclarationIndex`. Ключи `#if` / `LangVersion` на `VirtualFile` — `lang/CSharpParseOptions` (старт проекта — все C#-файлы
    в smart mode, открытие файла, `CompilationModel.CHANGED`; вне EDT, перепарс изменившихся только при встроенном дереве). Тест —
    `CSharpParseOptionsTest`, сценарий — `debug-playground/MultiTarget/ActiveBranch.cs` (`TYPE:active-branch`). Вживую не проверено
  - [x] B3 — эвристики, читавшие токены хоста в листьях PSI, — через `lang/CSharpLeaves` (знает оба набора): ▶ у `Main` и тестов,
    Ctrl+наведение, неимпортированное в строках и комментариях, вето rename, маркеры эндпоинтов и EF. `CSharpHighlightErrorFilter` скрывает
    `PsiErrorElement` в C# (синтаксические ошибки — шаг 9, `DIAGNOSTICS`). Тест — `CSharpTreeAgnosticTest` (оба дерева)
  - [x] Робот (`tools/ui-robot/scripts/syntax_tree.js`, песочница, копия `debug-playground`): `Console/Types.cs` на ROSLYN и NATIVE —
    Structure, breadcrumbs по 12 строкам, 21 регион folding совпали; ошибок 0; ▶ трёх тестов `Tests/PricingTests.cs` и `Main` у
    `NetFramework/LegacyConsole` на NATIVE есть. Нашлось: переключение из контекста без права записи (событие настроек из
    `invokeAndWait(any)`) — перепарс теперь синхронно только там, где запись разрешена, иначе `invokeLater`
  - [x] **Умолчание SYNTAX_TREE — NATIVE** (0.1.45, по просьбе пользователя «постепенно отключая Roslyn по умолчанию» после робота):
    у фичи своё `defaultSource`, в настройках хранится только отличие от него; кто выбрал ROSLYN явно, остаётся на эвристиках
  - [ ] Вживую пользователем: переключатель на странице Language Server, Structure / folding / breadcrumbs, `TYPE:active-branch`
- [x] Шаг 8 — stub-индексы (2026-10-04; устройство — `docs/csharp-psi/GRAMMAR.md`, раздел «Stubs»)
  - [x] Ядро: стабы через реестр платформы (`languageStubDefinition`, `stubElementRegistryExtension`) поверх тех же `SyntaxKind` —
    парсер, виды узлов и гейты деревьев не тронуты. `gen-psi` делает классы объявлений stub-based (`GenPsi.Stubbed`: compilation unit,
    namespace, типы, делегаты, extension-блоки, члены, enum-члены, объявление и деклараторы поля; база `CSharpStubElementImpl`), остальные
    классы — прежние. Стаб: имя (`CSharpDeclarationNames.name`), модификаторы битами, extension-метод, арность, параметры и базовые типы как
    написаны, простые имена атрибутов. Стабы — только у объявлений вне тел и только под объявлением со стабом (родитель стаба = родитель
    узла). Индексы: имена типов, имена членов, extension-методы, атрибуты (`Fact`, `Test`; без суффикса `Attribute`). `CSharpStubs.VERSION`
    = 1, золотой файл `testData/stubs/stubs.txt` падает, если стабы поменялись без новой версии. Индексатор разбирает файл с `#if` и версией
    его `VirtualFile`, как редактор. Тест — `CSharpStubTest` (дерево стабов по видам объявлений и версия, стабы = PSI после загрузки AST с
    той же идентичностью, индексы без загрузки AST, сериализация, правка документа, символы файла)
  - [x] Хост: Go to Class / Symbol на встроенном дереве — по stub-индексам, строки (текст, место, иконка) из стабов без загрузки AST
    (`NativeCSharpStubDeclarations`, `NativeCSharpPresentationProvider`); `CSharpDeclarationIndex` остаётся только для эвристического дерева
    (SYNTAX_TREE = ROSLYN, версия 3; на встроенном дереве пуст — файл не разбирается при индексации дважды). Смена переключателя
    перестраивает оба индекса; смена `#if`/версии файла (`CSharpParseOptions`) — переиндексация файлов, где разбор может отличаться. Снимки
    `CSharpSyntaxSnapshotTest` (раздел Go to Class / Symbol) — без изменений в обоих режимах. Тест — `CSharpStubNavigationTest` (Go to Class /
    Symbol при запрете загрузки AST; строки из стабов = строкам модели на всех входах снимков). ▶ тестов по индексу атрибутов — не сделано
  - [x] Замер (`CSharpStubBenchmark`, задача `benchmark`): 3000 файлов `runtime/src/libraries` (26 МБ) — ≈ 130 мс/МБ со сериализацией,
    почти всё — разбор (только разбор ≈ 105–115 мс/МБ); ≈ 3500 стабов и ≈ 110 КБ стабов на МБ исходника; весь `dotnet/runtime` (261 МБ) —
    ≈ 35 с в один поток. Ленивые тела для стабов не нужны
  - [ ] Вживую: Go to Class / Symbol на большом solution, индексация после переключения SYNTAX_TREE, файлы с `#if` после смены TFM
- [ ] Шаг 9 — синтаксические фичи на PSI, по одной за переключателем
  - Что из сервера каким шагом заменяется (страница Language Server, функции модуля `roslyn`, декомпиляция и Source Link без сервера,
    Go to Symbol по `AssemblyIndex`) — `docs/csharp-psi/SERVER_FEATURES_MAP.md` (2026-10-04)
  - [x] `USAGE_KINDS` — виды использований Find Usages (2026-10-04, 0.1.46): `lang/NativeCSharpUsageKinds` — лист в начале диапазона
    ссылки и его предки по типизированному PSI файла (тот же `CSharpUsageKind`, имена и смысл эвристики `CSharpUsageKinds`). Подключение —
    `CSharpUsages.analysis(file)`: NATIVE и у файла встроенное дерево (`compilationUnit != null`) → дерево, иначе токены; два кэша на файл,
    переключение действует сразу. `hasNative = true`, `needsIndexes = false` (читает только PSI своего файла, индексы не нужны — работает
    и во время индексации), `defaultSource` = NATIVE после робота (2026-10-04: `find_usages.js` + `feature_source.js` на `FindUsages.cs` —
    все 4 старых маркера совпали в обоих режимах, на `UsageTally` отличия ровно те, что в EXPECT). Лучше эвристики (на дереве): деконструкция `(a, b) = …` и `x! = 1` — запись;
    член с вложенным инициализатором (`Lines = { 1 }`, `Inner = { X = 1 }`) — чтение; текст интерполированной строки — строка; тип
    `out Order o` (аргумент и параметр) — тип объявления, не запись; тип в паттерне `switch` (`Order o =>`, `Order { … } =>`) — проверка
    типа; объявления без типа перед именем — переменные запроса (`from x`, `let y`), параметр лямбды без скобок, член анонимного типа,
    имя namespace. Как у эвристики: имя после `is` — проверка типа, даже если это константа (`x is Colors.Red`); ограничение `where T : X`,
    явный интерфейс `IFoo.Bar`, `default(X)` — чтение; `ref x` — запись. Тест — `CSharpFindUsagesTest`: каждый случай эвристики и все
    ответы сервера из `capture-5.12-references` через оба классификатора (совпадают), отличия — отдельным тестом, ломаный код на каждом
    смещении, переключатель. Сценарий — `debug-playground/Console/Editor/FindUsages.cs` (`TYPE:find-usages-native-field` /
    `-members` / `-type`). Робот — да (2026-10-04); вживую пользователем не проверено
  - [x] `EDITING` — помощь при наборе (2026-10-04, 0.1.48): `lang/NativeCSharpEditing`. Подключение — `NativeCSharpEditing.usable(file)`
    (NATIVE и встроенное дерево у файла) в `CSharpSelectioner`, `CSharpSmartEnterProcessor` и `CSharpGhostText.Context.tree` (провайдер серого
    текста: PSI файла, если документ закоммичен, иначе разбор текста — только после дешёвых проверок строки); иначе токены. `hasNative = true`,
    `needsIndexes = false`, `defaultSource` = NATIVE после робота (2026-10-04: `extend_selection.js`, `complete_statement.js`, `ghost_at_line.js` — все
    маркеры `ExtendSelection.cs` / `CompleteStatement.cs` совпали с EXPECT, токены — со строками «Tokens:»; Undo — 3 шага в обоих режимах).
    - Extend Selection (`NativeCSharpSelection`): каждый узел вокруг каретки, обрезанный до кода; у узла со скобками — содержимое (с
      комментариями, без переводов строк по краям) и со скобками; член / оператор с комментариями над ним (до пустой строки); литерал без
      кавычек (`@"`, `"""`, `u8`), интерполированная строка без `$"`/`"`. Токены давали только скобки, строки и комментарии.
    - Complete Statement (`NativeCSharpCompleteStatement.plan`): ближайший оператор / объявление; недостающие `)` `]` `}` `;` (пропущенные
      токены парсера) — в конец кода перед каждым; заголовок без тела (`if`, `while`, `for`, `foreach`, `lock`, `using`, `fixed`, `else`,
      `switch`, `catch` / `finally`, метод, локальная функция, тип) — блок с кареткой внутри, Allman или K&R по ближайшей `{` выше; метод
      abstract / extern / partial / интерфейса — `;`; поле — `;`; полный оператор — каретка в конец оператора, а не строки. Отказ (тогда
      отвечают токены): у узла или выше потеряна `}` при наличии `{` (парсер раздал скобки не тем), пропущенные токены-ошибки, незакрытая
      строка, пропущено что-то кроме закрывающих (`x = `, `Make(a, `), `try`, namespace, аксессоры, метки `case`.
    - Серая `;` (`NativeCSharpGhostText.needsSemicolon`): оператор, заканчивающийся у каретки, не имеет только `;`. Остальные правила серого
      текста — шаблоны набираемой строки (дерево недописанной строки ничего не добавляет) или объявления через `CSharpSyntaxModel` (уже
      по `SYNTAX_TREE`), их не трогали.
    - Отличия (токены → дерево): `Foo(a, b` — ничего → `Foo(a, b);`; `Make(a,`⏎`b)` на первой строке — разрыв вызова → новая строка после
      `b);`; `if (x)` / `foreach (…)` / `void N()` / `class B` — новая строка → блок; `public int X` — ничего → `;`; `x == y` — ничего → `;`;
      серая `;` после `.Where(...)` второй строки и `b)` многострочного вызова — нет → есть; после `Bar()` внутри незакрытого `Foo(a,` — есть → нет.
    - `RoslynLambdaGhost` (лямбды и аргументы по signature help сервера) спрашивает теперь `serves(DOCUMENTATION)`, а не `EDITING`: это
      parameter info, при Built-in у помощи при наборе лямбды не пропадают; метод этого же файла отвечается из текста в любом режиме.
    - Тест — `CSharpEditingTest`: последовательности Ctrl+W в обоих режимах, диапазоны токенов ⊂ диапазонов дерева на каждом смещении,
      безопасные случаи `needsSemicolon` совпадают, отличия парами, сценарии площадки, ломаный код на каждом смещении, переключатель.
      Сценарии — `debug-playground/Console/Editor/ExtendSelection.cs`, `CompleteStatement.cs`; робот — `tools/ui-robot/scripts/extend_selection.js`,
      `complete_statement.js`. Роботом и вживую не проверено
  - [x] `FORMATTING` — форматирование пробелов (2026-10-04, 0.1.49): `lang/NativeCSharpFormatter`. Первый срез — то, что делает
    `dotnet format whitespace` с умолчаниями .NET и опциями `.editorconfig` (`indent_size` / `indent_style` / `tab_width` через код-стиль
    платформы, `csharp_new_line_*`, `csharp_indent_*`, `csharp_preserve_single_line_*`, `csharp_space_*` — `CSharpEditorConfig`, `CSharpFormatOptions`).
    - Устройство: `lang.formatter` для C# (`NativeCSharpFormattingModelBuilder`, `CustomFormattingModelBuilder.isEngagedToFormat` =
      `NativeCSharpFormatting.engaged`), плоское дерево блоков: корень и лист на «единицу» (токен кода; строка doc-комментария / многострочного
      комментария; строка директивы; выключенная ветка `#if` целиком). Отступ — абсолютный (`Indent.getSpaceIndent`), пробелы — `Spacing`
      (переводы строк сохраняются, пустые — все). `DocumentBasedFormattingModel` с `shiftIndentInsideRange` = без сдвига (иначе платформа
      двигала строки verbatim-строк). Правила — `NativeCSharpLayout`: колонка по структуре (блоки, члены, `switch`, метки, `else`/`catch`,
      запросы под `from`, комментарии по следующему коду), продолжения строк сохраняют смещение от якоря (оператор / член) и сдвигаются с
      ним, как якоря Roslyn; многострочные инициализаторы коллекций не трогаются (как у Roslyn); переводы строк добавляются только там, где
      их добавляет Roslyn (вокруг `{` `}` многострочных конструкций, члены многострочного инициализатора объекта, `else`/`catch`/`finally`,
      пустая строка после `namespace X;`). Не меняет ничего, кроме пробелов: литералы, комментарии, текст интерполированных строк, выключенные ветки.
    - Подключение: отвечает, когда `FORMATTING` = NATIVE, у файла встроенное дерево и форматтер проекта — `dotnet format` (явно или «Auto»
      без CSharpier). Тогда `DotNetFormattingService.canFormat` = false, а модуль `roslyn` не отдаёт форматирование серверу; при ROSLYN — как было.
      По правилу `CSharpFeatures.native` без сервера (выключен) форматтер встроенный и при ROSLYN. Форматирование при наборе (on-type) сервера не трогали.
    - Оракул — `./gradlew formatOracle -PformatOracle.sample=N` (`src/test/kotlin/.../CSharpFormatOracle.kt`, вне `test`; обёртка
      `tools/csharp-psi/format-oracle.sh`): `.cs` площадки и равномерная выборка runtime + aspnetcore из `.corpus`, три варианта входа (как есть;
      «flat» — пробелы схлопнуты, отступы сняты, пробелы между токенами удвоены; «knr» — `{` подтянуты к предыдущей строке), сверка с
      `dotnet format whitespace --folder` (без символов `#if`, как `--folder`), плюс «меняется только пробел» и идемпотентность. Отчёт —
      `build/format-oracle/report.txt`. Замер 2026-10-04 (выборка 1000 → 1022 файла, сгенерированные
      пропущены, как их пропускает `dotnet format`): как есть — 1020/1022 совпали; knr — 1009/1010; flat — 927/1022; кода не изменено 0,
      не идемпотентных 0, исключений 0; весь прогон встроенного форматтера ≈ 19 с. Остаток: flat — смещения продолжений в цепочках вызовов
      внутри лямбд и аргументов (Roslyn берёт якорь иначе, когда отступа нет совсем — 90 файлов, на реальном коде не встречается); `}` `;`
      (пустой оператор после блока) Roslyn переносит на новую строку; `{` инициализатора массива внутри аргументов вызова; `/*MM*/if` —
      Roslyn переносит оператор после блочного комментария
    - Тест — `CSharpFormattingTest`: пары `resources/formatting/*/X.cs` → `X.after.cs` (ответ `dotnet format`, в т. ч. табы и опции
      `.editorconfig`), идемпотентность, «только пробелы», ломаный код без исключений, Reformat выделения, подключение (NATIVE / ROSLYN /
      CSharpier / None / без сервера). Сценарий — `debug-playground/Console/Editor/Formatting.cs` (`TYPE:format-*`); робот —
      `tools/ui-robot/scripts/reformat.js`. `defaultSource` — ROSLYN до проверки роботом. Роботом и вживую не проверено
  - [x] `NAVIGATION`, синтаксическая часть (2026-10-04, 0.1.50): `lang/NativeCSharpNavigation` — что значит имя под кареткой по синтаксису и
    областям видимости C#, без типов.
    - Разрешается: локальные (объявления, `out var`, паттерны, деконструкция, переменные `foreach` / `for` / `using` / `fixed` / `catch`),
      параметры (методы, конструкторы, первичные конструкторы класса / record, индексаторы, лямбды, анонимные методы, локальные функции),
      локальные функции, метки `goto`, переменные запросов (`from` / `let` / `join` / `into`, продолжение `into` скрывает прежние),
      параметры типов; члены объемлющих типов и их partial-частей по имени (части — по stub-индексу `TYPE_NAMES`: то же имя, арность,
      контейнеры), без разбора перегрузок — несколько целей, платформа предлагает выбор; `this.X`; типы solution по простому имени и
      арности (`TYPE_NAMES`) из namespace вокруг использования и из `using` файла и его namespace; атрибут `[Note]` → `NoteAttribute`.
      Ближайшая область побеждает (параметр лямбды скрывает локальную, локальная — поле). Переменные выражений «протекают» в блок там же,
      где у C# (объявление, выражение-оператор, `if`, `return`, `throw`, `yield`, `switch`), у `while` / `for` / `foreach` / `using` /
      `lock` / `fixed` остаются в операторе. В позиции типа (`Color Color`) ищутся только типы. Использование до объявления в том же
      блоке разрешается (C# считает это ошибкой, переходу не мешает).
    - Не разрешается (null, дальше — сервер, если готов): имя после точки (`a.B`, `A.B`, `?.B`), кроме `this.B`; именованные аргументы,
      члены инициализатора объекта, алиасы, `using static`, члены базовых типов, типы сборок, тип, которого `using` файла не видят
      (`global using` другого файла, полное имя).
    - Подключение: Go to Declaration / Ctrl+наведение — `lang/CSharpGotoDeclarationHandler` (`gotoDeclarationHandler`, `order="first"`):
      платформа спрашивает такие обработчики раньше ссылок, так что разрешённое имя до сервера не доходит, а неразрешённое уходит к
      ссылкам сервера (`LspImplicitReferenceProvider` при Ctrl+B, `RoslynCtrlHoverReferenceProvider` при наведении), как при ROSLYN.
      Подсветка — `CSharpHighlightUsagesHandlerFactory` (теперь `order="first"`, фабрика LSP платформы — `last`): при NATIVE для
      «локальных» символов (всё, кроме членов и типов: их использования через `a.B` дерево не видит) — использования по дереву в члене
      символа, запись (объявление, `=`, `++`, `out` / `ref` — виды `NativeCSharpUsageKinds`) отдельно от чтения; остальное — как при ROSLYN
      (сервер, пока не готов — совпадения текста). Поэтому `shouldAskServerForDocumentHighlights` больше не смотрит на переключатель, а Go
      to Super (`RoslynGotoSuperHandler`) работает при любом: NATIVE навигацию сервера не отнимает. `needsIndexes = true` (типы из
      stub-индекса): в dumb mode — путь ROSLYN.
    - Тест — `CSharpNavigationNativeTest` (маркеры `/*U:tag*/` → `/*D:tag*/`: каждый вид символа, затенение, лямбды, partial в двух файлах,
      типы с `using` и без, перегрузки → две цели, неразрешимое → null, ломаный код, ROSLYN, Ctrl+B в редакторе, подсветка чтение / запись).
      Сценарий — `debug-playground/Console/Editor/Navigation.cs` (`TYPE:nav-*`); робот — `tools/ui-robot/scripts/goto_declaration.js`.
      `defaultSource` — ROSLYN до проверки роботом. Роботом и вживую не проверено
  - [x] `SEMANTIC_COLORS` — цвета идентификаторов (2026-10-04, 0.1.51, задача A4): палитра Rider `lang/CSharpColors` (ключи
    `CSHARP_<вид>_IDENTIFIER` по дампу `docs/rider-analysis/dumps/color-keys-csharp.txt`, откат на прежние `CSHARP_TYPE` / `METHOD` / `MEMBER`
    и на Language Defaults; цвета Darcula / Default — `colorSchemes/CSharp*.xml`), токены сервера — в неё же (`RoslynPolicy.textAttributesKey`
    с модификаторами `static`, `ReassignedVariable`; объявление метода от вызова сервер не отличает — цвет вызова).
    - Области: `lang/NativeCSharpScopes` (один проход по файлу, кэш до изменения файла): локальные (`foreach`, `catch`, `using`, паттерны,
      `out var`, деконструкция, запросы), параметры (методов, лямбд, локальных функций, делегатов, индексаторов), primary-параметры (не видны
      во вложенном типе; у record — только объявление, в теле это свойство), локальные функции (видны во всём блоке), параметры типов, метки
      (по функции, `goto` вперёд). Запись — по `NativeCSharpUsageKinds` (присваивание, `++`, `ref` / `out`, деконструкция) → изменяемая.
      Упрощения: локальная видна с объявления; переменные выражений — во внутреннем блоке / `switch` / ветке.
    - Цвета: `lang/NativeCSharpSemanticColors` — объявления по синтаксису; имя без области → члены охватывающих типов (их partial-части
      и базовые классы solution — из стабов: `childrenStubs`, `baseTypes`, позиционные параметры record) → члены типов `using static` → типы
      файла и solution (`CSharpStubIndexKeys.TYPE_NAMES`, арность, предпочтение namespace файла и его `using`); `this.X` / `base.X` /
      `Type.X` / `Outer.Inner` / `new T { X = … }` — по членам того типа. Неразрешённое — без цвета, кроме места, где по грамматике только
      тип (`CSHARP_TYPE`), и имени атрибута. AST других файлов не грузится (тест с `setAssertOnFileLoadingFilter`).
    - Подключение: `NativeCSharpSemanticColorsAnnotator` — при NATIVE и встроенном дереве; эвристика `CSharpIdentifierAnnotator` тогда
      уступает; токены сервера — `serves(SEMANTIC_COLORS)`. `hasNative = true`, `needsIndexes = true` (во время индексации — эвристика),
      `defaultSource` — ROSLYN до робота. Переключение на странице перезапускает подсветку (`NativeCSharpSemanticColorsSwitch`).
    - Тест — `CSharpSemanticColorsTest` (все ключи, partial через файлы, затенение, изменяемые, ломаный код, сценарий площадки, страница
      цветов и схемы), `RoslynPolicyTest`. Сценарий — `debug-playground/Console/Editor/SemanticColors.cs` (+ `SemanticColorsPart.cs`,
      `TYPE:colors-*`); робот — `tools/ui-robot/scripts/highlight_keys.js`. Роботом и вживую не проверено
  - [x] Один резолвер имён (2026-10-05, 0.1.53): навигация (A2) и цвета (A4) писались отдельно и разрешали имена каждая по-своему;
    теперь у них, у подсветки использований и у rename один источник. `lang/NativeCSharpScopes` (кэш на файл) — локальные символы:
    локальная и локальная функция видны во всём блоке (объявляются заранее, использование до объявления — их), переменные выражений
    утекают в блок из объявления / выражения / `if` / `return` / `throw` / `yield` / `switch` и остаются в `while` / `do` / `for` /
    `foreach` / `using` / `lock` / `fixed` / вложенном операторе / секции `switch` / инициализаторе поля; продолжение `into` скрывает
    переменные запроса; у символа — `scope` (для конфликтов) и `isMember` (позиционный параметр record — свойство). `lang/NativeCSharpResolver`
    (на одно использование) — члены объемлющих типов, их partial-частей и базовых классов solution из стабов, `using static`, `this.` /
    `base.` / `Type.X` / инициализатор объекта, типы по имени и арности: для цветов — мягко (тип чужого namespace тоже красится), для
    навигации и rename — строго по видимым namespace; `declarations(leaf)` (цели Go to Declaration, несколько — перегрузки), `symbolAt(leaf)`,
    `references(symbol)`, `usages(symbol)` (чтение / запись). Навигация стала лучше (тест `CSharpNavigationNativeTest`): члены базовых
    классов, `base.X`, `Type.X`, `using static`, члены инициализатора объекта; позиционный параметр record больше не «локальный» для подсветки.
  - [x] `RENAME`, синтаксическая часть (2026-10-05, 0.1.53, задача A5): `lang/NativeCSharpRename` + `NativeCSharpRenameHandler`
    (`renameHandler order="first"`). При NATIVE обработчик доступен на любом идентификаторе файла встроенного дерева, а
    `LspRenameSupport.shouldRunRename` отвечает false (`NativeCSharpRename.serverRenames`), так что платформа не спрашивает, чей rename;
    то, что сам не переименовывает (члены, типы, позиционный параметр record, параметр-именованный аргумент в другом файле — индекс слов
    и деревья файлов с этим словом, параметры типов `partial`), отдаёт LSP-обработчику платформы напрямую (флаг на время вызова) при
    готовом сервере, иначе — подсказка. Inplace — шаблон над всеми вхождениями, как `VariableInplaceRenamer` (Start/FinishMarkAction —
    одно Ctrl+Z); по Enter вхождения возвращаются к старому имени и rename выполняется командой: проверка имени, `@` перед
    зарезервированным словом, конфликты — новый текст разбирается отдельно (`#if` и версия языка — оригинала) и области сравниваются:
    ссылка ушла к другому объявлению, чужое имя стало ссылкой на переименованное, второе объявление в охватывающей / вложенной области,
    член скрывает параметр первичного конструктора → `ConflictsDialog` (в тестах `ConflictsInTestsException`). Вместе с именем —
    именованные аргументы вызовов, которые резолвер связывает с функцией, и `<param>` / `<paramref>` / `<typeparam>` doc-комментария.
    `hasNative = true`, `needsIndexes = true`, `defaultSource` — ROSLYN до робота. Тест — `CSharpRenameNativeTest` (виды символов,
    конфликты, `@`, члены → сервер / подсказка, ROSLYN, ломаный код, Undo, inplace-шаблон, сценарий площадки). Сценарий —
    `debug-playground/Console/Editor/Rename.cs` (`TYPE:rename-*`); робот — `tools/ui-robot/scripts/inline_rename.js` (теперь печатает
    `NativeCSharpRenameHandler`). Роботом и вживую не проверено
    - Робот (2026-10-05, 0.1.56; `tools/ui-robot/scripts/rename_check.js`): `subtotal`, `price`, `discount` (вместе с `<param name>` —
      сервер его не трогает), `Scale`, `factor` с именованным аргументом, параметр первичного конструктора — как у сервера, одно Ctrl+Z;
      конфликт — диалог «Conflicts Detected», отмена ничего не меняет. Исправлено: проверка конфликтов и правка шли внутри
      `templateFinished` (TransactionGuard: «Write-unsafe context»), теперь — `invokeLater` с модальностью редактора. Умолчание — NATIVE.
  - [x] `COMPLETION`, синтаксическая часть (2026-10-05, 0.1.55, задача A6): `hasNative = true`, `needsIndexes = true`, `defaultSource` —
    ROSLYN до робота.
    - Место: `lang/NativeCSharpCompletionPlace` по предыдущему настоящему токену (без trivia и пропущенных токенов) и предкам
      `IntellijIdeaRulezzz`: начало оператора / выражение / тип / начало члена / верх файла / имя объявления / метка `goto` / ключевые
      слова (`when`, `where`, запрос) / `this.` и `base.` / атрибут. После другой точки — `null`, только сервер. Как разбирается
      заглушка (`partial |` — поле типа `partial`, `yield |` — объявление, законченный запрос — новый оператор) — в KDoc.
    - Пункты: `lang/NativeCSharpCompletion` — локальные (`NativeCSharpLocals`: ближняя тень, после объявления, не в своём декларатор),
      параметры, члены своего типа и его частей / баз решения (в static — только static), `using static`, типы решения (stub-индекс
      `TYPE_NAMES`, с арностью), ключевые слова (`lang/NativeCSharpKeywords`). Приоритеты: локальные 48, параметры 46, локальные функции
      44, поля / свойства 40, методы 30, параметры типов 22, типы 20, ключевые слова 0; ожидаемый тип (декларатор, `return`, аргумент своего
      метода, присваивание) +25, имя как у параметра +30 / +12. Метод вставляет `()` (`();` в конце строки), ключевое слово — `if (|)`,
      `typeof(|)`, `break;`, пробел.
    - `override` / `partial` (`NativeCSharpOverrides`): виртуальные / абстрактные члены баз решения, ещё не переопределённые, плюс `Equals`
      / `GetHashCode` / `ToString`; член целиком (Allman, отступ из стиля); `partial` — объявленные без тела. Имена — `CSharpVariableNames`
      (хвосты camel-hump, без `I`, множественное для коллекций, `_` у private-полей, Pascal у публичных).
    - Частые вызовы (`NativeCSharpCommonCalls`): `Task.FromResult` / `Task.CompletedTask` / `ValueTask` / `default` первыми после `return`
      в не-`async` методе; серый текст — правило `TASK_RETURN` в `CSharpGhostText` (при любом источнике); выбор `await` и intention «Make
      method async» добавляют `async` (`void` → `Task`, `T` → `Task<T>`, обработчик события — `async void`). `ConfigureAwait` не делался.
    - Слияние с сервером: `NativeCSharpCompletionContributor` (`order="first, after dotnetCaseInsensitive"`) кладёт свои пункты, затем
      `runRemainingContributors` и выбрасывает пункты сервера (`NativeCSharpCompletion.SERVER` / `LspCompletionObject`) с тем же именем,
      а там, где ключевые слова свои, — все ключевые слова сервера; пункты других контрибуторов не трогаются. Сервер работает при любом
      переключателе (`RoslynCompletionItems.shouldRunCodeCompletion` без `serves`).
    - Тест — `CSharpCompletionNativeTest` (места, порядок, тень, ожидаемый тип, вставки, `override` / `partial`, имена, `Task`, серый текст,
      `async`, слияние с поддельным сервером, ROSLYN, ломаный код). Сценарии — `debug-playground/Console/Editor/NativeCompletion.cs`,
      `CommonCalls.cs` (`TYPE:complete-*`); робот — `complete_at_line.js` в обоих режимах через `feature_source.js` (`COMPLETION`),
      эталон — `rider_complete.js`. Роботом и вживую не проверено
  - [x] `DIAGNOSTICS`, синтаксическая часть (2026-10-05, 0.1.54, задача A3): синтаксические ошибки Roslyn по своему дереву — коды, тексты
    и места как у `roslyndump` (`CS1002: ; expected` нулевой ширины в конце строки).
    - Ядро: парсер пишет диагностику в описание узла ошибки — `CSharpErrorCode.describe` (`CS1002: ; expected`, с якорем
      `CS1003@firstExpected: …`); перечисление `lang/diagnostics/CSharpErrorCode` — все коды `Parser/*.cs` Roslyn с текстами из
      `CSharpResources` (`roslyndump errors`); узлы, которые ничего не сообщают (пропуск ради чужой диагностики), — `SKIPPED`.
      `CSharpSyntaxDiagnostics.of(file, symbols)` — проход после разбора: места по правилам Roslyn (`GetDiagnosticSpanForMissingNodeOrToken`:
      перевод строки в хвостовой trivia предыдущего токена → нуль в его конце, иначе текущий токен, в дыре интерполяции — её конец; якоря
      `CSharpDiagnosticAnchor`: первый / второй токен, следующий, предыдущий, предыдущий узел…), ошибки лексера (`CSharpLexerDiagnostics.of`:
      литералы, `ERR_UnexpectedCharacter`, `@@`, raw-строки, незакрытый `/*`) и директив (препроцессор запускается заново с символами
      файла и собирает диагностики `DirectiveParser`: `#if` / `#elif` / `#else` / `#endif` / `#region` не к месту, выражение, лишнее в конце
      строки, `#define` после токена, `#error` / `#warning`, `#pragma`, `#nullable`, директива не в начале строки, незакрытые `#if` /
      `#region` в конце файла). Дерево не меняется (гейты корпуса те же).
    - Не сообщается (Roslyn только считает их в порте, `addError` на готовом узле): `CS0230` (`foreach` без типа), `CS8515` (`switch` без
      скобок), `CS8803` (операторы верхнего уровня после типов), `CS1519` на ключевом слове вместо имени члена, ошибки версии языка;
      директивы `#line`, `#r`, `#load`, `#pragma checksum`; правила отступов многострочных raw-строк; интерполированные строки.
    - Подключение: `lang/NativeCSharpDiagnostics` — `NativeCSharpDiagnosticsAnnotator` (dumb-aware, кэш до изменения PSI; нулевая ширина —
      символ после или за концом строки, как пустой узел ошибки у платформы; WRN_ — предупреждение). Узлы ошибок дерева скрыты всегда
      (`CSharpHighlightErrorFilter`). Сервер спрашивается при любом переключателе (`shouldAskServerForDiagnostics = workspace.isLoaded`):
      его семантические ошибки остаются, а синтаксическую он не показывает, если дерево сообщило тот же код на той же строке
      (`createAnnotation`, `NativeCSharpDiagnostics.shownNatively`). `hasNative = true`, `needsIndexes = false`, `defaultSource` — ROSLYN
      до робота; без сервера (выключен) — встроенные. Переключение перезапускает подсветку (`NativeCSharpDiagnosticsSwitch`).
    - Тест — `CSharpSyntaxDiagnosticsTest` (оракул: 33 фрагмента, среди них сценарий площадки с кодами и местами `roslyndump tree`, сообщения, предупреждения директив,
      исключённый текст, аннотатор при NATIVE / ROSLYN, уступка сервера, нулевая ширина). Сценарий — `debug-playground/Broken/SyntaxErrors.cs`
      (`TYPE:diag-*`, из компиляции `Broken` исключён); робот — `tools/ui-robot/scripts/errors_at.js`. Роботом и вживую не проверено
    - Робот (2026-10-05, 0.1.56; `errors_at.js` на файле проекта `Console`, `Broken` сервер не анализирует): Built-in и сервер — те
      же 18 отметок в тех же местах; у встроенных код (`CS1002: …`), пустые по ширине — на символ шире. Найдено и исправлено: платформа
      делает аннотации диагностик сервера один раз на ответ и не переделывает их ни при перезапуске подсветки, ни по
      `workspace/diagnostic/refresh` (обработчик клиента пустой), а `HighlightInfoFilter` LSP-проход не спрашивает, — поэтому после
      переключения ошибки сервера двоились или пропадали до первой правки. Теперь `RoslynWorkspace.diagnosticsSwitched` просит
      `LspHighlightingApplier.scheduleHighlightingRefresh` (internal в Kotlin — через reflection) для открытых `.cs`. Умолчание — NATIVE.
- [ ] Шаг 10 — project model
  - [x] 2026-10-04, 0.1.41: часть для парсера (`msbuild/CompilationModel`, `CompilationOptions`, `CompilationOptionsReader`,
    `FrameworkDefaults`). Файл → проект → конфигурация и TFM тулбара → `DefineConstants` (с неявными символами TFM: MsBuildHost
    выполняет таргет `AddImplicitDefineConstants` на копии вычисления, запрос `evaluate` с `targets`), `LangVersion` (умолчание SDK —
    `_MaxSupportedLangVersion` из `Microsoft.CSharp.Core.targets`: net4x / netstandard2.0 / netcoreapp < 3 → 7.3, netcoreapp3.x /
    netstandard2.1 → 8.0, net5 → 9.0 … net10 → 14.0, потолок `_MaxAvailableLangVersion` SDK), `Nullable`, `ImplicitUsings` + `Using`,
    `RootNamespace`, `Compile`. Без ответа помощника — статическое чтение проекта и ближайшего `Directory.Build.props` (условия
    `==`/`!=`/`and`/`or`, `Choose`, порядок props → SDK → проект → таргеты SDK); legacy-проекты — `DefineConstants` конфигурации,
    `LangVersion`, явные `<Compile Include>`. Подключение после шага 7 — по вызову на ключ:
    `file.putUserData(CSharpPreprocessorSymbols.KEY, model.symbolsFor(file))`,
    `file.putUserData(CSharpLanguageLevel.KEY, CSharpLanguageVersion.parse(model.languageVersionFor(file)))`, по `CompilationModel.CHANGED` —
    снова и перепарсить открытые файлы проектов. Тест — `CompilationOptionsTest` (ответы настоящего MsBuildHost на фикстурах SDK net10,
    net10.0;net48, netstandard2.0, `Directory.Build.props`, legacy Debug / Release, `debug-playground/MultiTarget`; статическое чтение
    сверяется с ними). Вживую в IDE не проверено (потребителя до шага 7 нет)
  - [x] 2026-10-04, 0.1.52: ссылки — `project.assets.json` и индекс сборок для семантики (формат 2), legacy — `HintPath`, reference
    assemblies net4x (B1–B2 списка выше; `indexer/README.md`, тесты `AssemblyIndexSemanticsTest`, `ProjectReferencesTest`)
  - [x] 2026-10-05, 0.1.59: library roots (B3 списка выше; тест `AssemblyLibraryRootsTest`)
  - [x] 2026-10-05, 0.1.62: Go to Class / Symbol по сборкам, metadata view (B4 списка выше; тест `AssemblyGotoLibraryTest`)
