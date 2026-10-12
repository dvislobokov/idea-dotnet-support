# Completion C#: что не хватает и план работ

Источник — исследование 2026-10-05 (агент на Fable): провайдеры completion Roslyn (`dotnet/roslyn`,
`src/Features/CSharp/Portable/Completion/CompletionProviders/*`, `EmbeddedLanguages/*`, `KeywordRecommenders`), документация
Rider/ReSharper (postfix, type-matching, double completion), Microsoft Learn «C# IntelliSense», снимки Rider `docs/rider-analysis/dumps/completion.txt`
(номера dump ниже — оттуда) и код плагина 0.1.83. Вживую ничего не проверено; «не уверен» — проверить тестом.

С 0.1.76 сервер Roslyn выключен по умолчанию, и всё, что жило только в модуле `roslyn` (лямбды, именованные аргументы и
неимпортированные extension-методы из списка сервера, статистика в `RoslynCompletionRanking`), для пользователя по умолчанию пропало.

Трудоёмкость: S — до дня, M — 1–3 дня, L — больше. Статус: ⏳ в работе, ⬜ не начато, ✅ сделано (с версией).

## Волна 1 (сделано 0.1.85–0.1.88, вживую не проверено)

| # | Работа | Версия | Статус | Оценка |
|---|---|---|---|---|
| 1.1 | `override ` / `public override ` — список членов баз, в т.ч. из сборок и файлов, сгенерированных сборкой (gRPC), вставка сигнатуры как в Rider | 0.1.85 | ✅ | M |
| 1.2 | Ctrl+O Override Members, Ctrl+I Implement Members; Alt+Enter «Override / Implement missing members» на имени класса, пустой строке в теле, базовом типе, CS0534/CS0535 | 0.1.85 | ✅ | M |
| 1.3 | Parameter Info сразу после выбора метода из нативного списка (`NativeCSharpCalls.callHandler` не зовёт `autoPopupParameterInfo`) | 0.1.86 | ✅ | S |
| 1.4 | Лямбды на месте делегата без сервера: `items.Where(` → `x => `, `(x, i) => `; `Changed += ` → `(sender, e) => {}`, «Create method» (dumps 15b, 24, 48) | 0.1.86 | ✅ | M |
| 1.5 | Именованные аргументы `quantity:` и у атрибутов `[Obsolete(DiagnosticId = ` (dump 5; Roslyn `NamedParameter…`, `AttributeNamedParameter…`) | 0.1.86 | ✅ | S |
| 1.6 | Типы подключённых namespace без порога 3 букв (`Li` → `List<>`) | 0.1.87 | ✅ | S–M |
| 1.7 | Типы solution из соседнего namespace с автоматическим `using` | 0.1.87 | ✅ | M |
| 1.8 | Неимпортированные extension-методы после точки + `using` (dump 27c; Roslyn `ExtensionMemberImportCompletionProvider`); неимпортированные атрибуты на `[` (dump 34) | 0.1.87 | ✅ | M |
| 1.9 | Инициализаторы `new X { `, `with { ` — только неприсвоенные члены; property pattern `is { ` (dumps 8, 8b, 13) | 0.1.88 | ✅ | M |
| 1.10 | Члены enum по ожидаемому типу: `== `, `case `, `switch {`, `is `, аргументы (dumps 7, 11, 12, 14) | 0.1.88 | ✅ | M |
| 1.11 | Пункт `await Method()` у Task-членов в async (dumps 38, 38b; Roslyn `AwaitCompletionProvider`) | 0.1.88 | ✅ | M |
| 1.12 | `new` по ожидаемому типу: `Order()` первым, наследники; `throw new` / `catch (` — исключения; фильтры мест типа (base list, `event`, constraints) (dumps 9, 26, 46) | 0.1.88 | ✅ | M |
| 1.13 | Smart completion Ctrl+Shift+Space — фильтр по ожидаемому типу | 0.1.88 | ✅ | M |

## Волна 2 (сделано 0.1.89–0.1.91, вживую не проверено)

| # | Работа | Где у Rider / Roslyn | Что у нас сейчас | Нужность | Оценка |
|---|---|---|---|---|---|
| 2.1 | Postfix по типу выражения: `.await` только у Task, `.foreach` только у коллекций, `.not`/`.if` у bool, `.for` с `.Count`/`.Length`; имя переменной `.var` → `orderTotal` | Rider postfix (dump 28d) | `CSharpPostfixTemplates.kt` смотрит только на токены, имя всегда `value` | средняя | M | ✅ 0.1.89
| 2.2 | Новые postfix: `.field`, `.prop`, `.to`, `.arg`, `.sel`, `.parse`, `.tryparse`, `.inject` | Rider (29 шаблонов) | 24 шаблона, этих нет | средняя | S–M | ✅ 0.1.89
| 2.3 | Препроцессор: `#` → директивы, `#if ` → символы `DefineConstants` (`CompilationModel`), `#pragma warning disable ` → коды | dump 36; Roslyn `PreprocessorCompletionProvider` | нет | средняя | S–M | ✅ 0.1.90
| 2.4 | XML-доки: `/// <` → теги, `<see cref="`, `<param name="`, `<typeparamref>`, `<exception cref>`, `<inheritdoc cref>` | dumps 35, 35b; Roslyn `XmlDocComment…`, `Cref…` | только генерация `///`; резолвер cref есть (0.1.72) | средняя | M | ✅ 0.1.90
| 2.5 | Regex внутри строк: инъекция языка RegExp платформы (`new Regex(`, `[GeneratedRegex]`, `// lang=regex`, `[StringSyntax]`) — подсветка и completion | dump 22; Roslyn `EmbeddedLanguages/RegularExpressions` | нет | средняя | M | ✅ 0.1.90
| 2.6 | Форматы `{x:N2}`, `ToString("…")`, `yyyy-MM-dd` по типу выражения | dump 20 | подсветка есть, completion нет | средняя | S–M | ✅ 0.1.90
| 2.7 | Completion внутри интерполяции `$"{…}"` — проверить и починить | dump 43 | не уверен, теста нет | высокая | S | ✅ 0.1.90
| 2.8 | Статистика выбора (`SuggestionStats`) в порядке нативного списка; preselect | VS MRU, Rider | только в `RoslynCompletionRanking` (сервер) | средняя | S | ✅ 0.1.91
| 2.9 | Символы, подтверждающие выбор: `.`, `,`, пробел, `=` (`CharFilter` для C#) | Rider/VS commit characters | только `(` и `;` у `return` | средняя | S | ✅ 0.1.91
| 2.10 | Авто-открытие списка: `#`, `(`/`,` у делегата (после 1.4), остальные места Rider | dumps 1, 10, 15, 23, 33, 36 | частично (`opensByItself`) | средняя | S | ✅ 0.1.91
| 2.11 | Quick Doc на пункте списка (символ в объекте пункта) | Rider/VS | не уверен: пункты строятся из строк | средняя | S–M | ✅ 0.1.91
| 2.12 | Suggestion mode: где пишут новое имя (`x => `, `foreach (var `, `out var `) Enter не подставляет пункт | Roslyn `CSharpSuggestionModeCompletionProvider` | частично (`CSharpPropertyNameConfidence`) | средняя | S | ✅ 0.1.91
| 2.13 | Generic-аргументы `new List<|`, `Dictionary<string, |` — проверить тестом | dump 19 | не уверен | средняя | S | ✅ 0.1.91
| 2.14 | Полнота ключевых слов по 150 рекомендерам Roslyn: `and`/`or` в паттернах, `with`, `init`, `field`, `scoped`, `allows`, `extension`, `assembly:`/`module:` в `[` | Roslyn `KeywordRecommenders` | частично (`NativeCSharpKeywords`) | средняя | S–M | ✅ 0.1.91
## Волна 3 (сделано: 3.1 — 0.1.89, 3.4 и 3.10 — 0.1.91; хвосты — 0.1.92, 3.2/3.3/3.5/3.6 — 0.1.94, 3.11/3.13/3.14 — 0.1.95, 3.7–3.9 — 0.1.93, 3.12 — 0.1.96)

| # | Работа | Нужность | Оценка |
|---|---|---|---|
| 3.1 | Live templates до набора Rider (83 против 33): `ctorf`, `propdp`, `indexer`, `equals`, `iterator`, `sim`, `attribute`, `exception`, `checked`/`unchecked`/`unsafe`, `#if`, `nguid`; макрос имени переменной по типу | низкая–средняя | S | ✅ 0.1.89 (кроме ASP.NET `hal`/`ua`/`rta`, `ctx`)
| 3.2 | Явная реализация интерфейса: `void IFoo.|` → члены (Roslyn `ExplicitInterfaceMember…`, `…Type…`) | низкая–средняя | S–M | ✅ 0.1.94 (кроме событий и индексаторов)
| 3.3 | Индексатор `[]` пунктом после точки у коллекций и строк (dump 1); операторы и преобразования после точки | низкая | S / M | ✅ 0.1.94 (`[]`; операторы и преобразования — нет)
| 3.4 | `nameof(` — члены и параметры, `typeof(` — только типы | низкая | S | ✅ 0.1.91
| 3.5 | Имена элементов кортежа, деконструкция (Roslyn `TupleName…`) | низкая | S | ✅ 0.1.94
| 3.6 | `partial class |` — partial-типы без этой части (Roslyn `PartialType…`) | низкая | S | ✅ 0.1.94
| 3.7 | Инъекции JSON и маршрутов (`[Route("/orders/{id}")]`, `MapGet`), `[StringSyntax]` | низкая–средняя | M | ✅ 0.1.93
| 3.8 | Плейсхолдеры логгера `_logger.LogInformation("{Name}", …)` | низкая | S | ✅ 0.1.93
| 3.9 | Ключи конфигурации `Configuration["…"]` по `appsettings.json`; DI `AddScoped<IService, |` → реализации | низкая–средняя | M | ✅ 0.1.93
| 3.10 | Middle matching (подстрока) в дополнение к CamelHumps | низкая | S | ✅ 0.1.91
| 3.11 | Настройка «Exclude from completion» (типы/namespace) | низкая | S–M | ✅ 0.1.95 (типы сборок и неимпортированные; типы solution в общем списке — нет)
| 3.12 | Double completion (второй Ctrl+Space: непубличные члены, нереференсные сборки, цепочки) | низкая | L | ✅ 0.1.96
| 3.13 | `$(Property)` / `@(Item)` / пути `Import` и `ProjectReference` в csproj | низкая–средняя | M | ✅ 0.1.95
| 3.14 | Редкое: `InternalsVisibleTo("…")`, `extern alias`, calling conventions `delegate*`, `#:package`, speculative `T` | низкая | S каждая | ✅ 0.1.95
## Не делаем

- ML-ранжирование и whole-line completion — отложено пользователем (`ML_COMPLETION_PLAN.md`).
- Razor/Blazor — вне рамок (решение 2026-09-30).

## Что у нас есть, чего нет в Rider

Серый текст по правилам без ML (авто-свойство, `new();`, присваивание в конструкторе, namespace по папке, тип по файлу, `ILogger<T>`, значение
по типу и имени), отчёт статистики подсказок (.NET → Suggestion Statistics), регистронезависимый префикс всегда, postfix `.cw` / `.str` /
`.nameof` / `.awaitusing`, completion пакетов NuGet и версий в csproj с проверкой фидов.
