# Что подсказывает Rider — эталон для completion и серого текста плагина

Снято UI-роботом 2026-10-04 с Rider 2025.1.3 (бэкенд ReSharper), площадка `debug-playground` (`DebugPlayground.sln`).
Запуск Rider под робота — `tools/ui-robot/rider/start-rider.ps1` (отдельная копия настроек, рабочий Rider не трогается), скрипт —
`tools/ui-robot/scripts/rider_complete.js` (`__KIND__` = `BASIC` — Ctrl+Space, `SMART` — Ctrl+Shift+Space). Пробный файл
`Console/Editor/RiderProbe.cs` — только в копии площадки `build/ui-robot/rider-playground`; методы:
`Task<string> NotAsync(int id)`, `Task Plain()`, `async Task<int> WithToken(HttpClient client, CancellationToken cancellationToken)`,
`async Task<int> WithoutToken(Stream stream)`, `void Body(string? name, List<int> items)`, `async Task Awaits()`.
Повторить на свежем Rider — та же команда с `-Rider` / `-Config` новой версии, результаты дописать колонкой.

## Возврат из метода с `Task`

| Где, что набрано | Вызов | Первые пункты Rider |
|---|---|---|
| `Task<string> NotAsync` (не `async`), `return ` | SMART | `Task.FromCanceled`, `Task.FromException`, **`Task.FromResult`**, `Task.Run` — всё с типом `Task<string>` |
| то же | BASIC | `new Task<string>`, `id`, `null`, методы класса… (500 пунктов) |
| `Task Plain()`, `return ` | SMART | **`Task.CompletedTask`**, `Task.Delay`, `Task.FromCanceled`, `Task.FromException`, `Task.FromResult`, `Task.Run`, `Task.WhenAll`, `Task.WhenAny` |
| `return Task.FromResult(` в `NotAsync` | SMART | `String.Empty`, `String.Concat`, `String.Create`, `String.Format`… — по типу `string` |
| `return Task.Fr` | BASIC | `FromCanceled`, `FromException`, `FromResult` |

Вывод для плагина: подсказка «`return Task.FromResult(|)`» в Rider — это smart completion по ожидаемому типу, серым текстом сама
не появляется. У нас её можно дать раньше семантики: тип возврата и отсутствие `async` видны в заголовке.

## `CancellationToken`

| Где, что набрано | Вызов | Первые пункты Rider |
|---|---|---|
| `WithToken`: `await client.GetStringAsync(nameof(client), ` | BASIC | **`cancellationToken`**, `new CancellationToken`, `CancellationToken.None`, дальше всё остальное |
| то же | SMART | `cancellationToken`, `CancellationToken.None` — только они |
| `WithToken`: `await Task.Delay(100, ` | SMART | `cancellationToken`, `CancellationToken.None` |
| `WithoutToken` (токена нет): `await stream.ReadAsync(new byte[1], ` | SMART | **`CancellationToken.None`**, затем `Int32.*` (перегрузка `(byte[], int, int)`) |
| `ReadAsync(new byte[1], 0, 1, ` | SMART | пусто (робот: список не открылся) |

Вывод: Rider ставит переменную-токен метода первой, когда следующий параметр вызова — `CancellationToken`; без токена — `None`.
Серым текстом токен не дописывает. Нужна сигнатура вызываемого метода — у нас индекс сборок / signature help сервера, позже семантика.

## Позиция типа, частые вызовы

| Где, что набрано | Вызов | Первые пункты Rider |
|---|---|---|
| `new List<str` | BASIC | `string`, `Stream`, `StreamContent`, `StreamReader`, `StreamWriter`, `String`, `StringBuilder` — **только типы** (у плагина до 0.1.48 был `Conversion.Str`) |
| `WriteLi` (голое имя) | BASIC | `TextWriterTraceListener`, `XmlWriterTraceListener` — `Console.WriteLine` без импорта Rider на первом Ctrl+Space не даёт |
| `ArgumentNullException.` | BASIC | `ThrowIfNull`, `ThrowIfNullOrEmpty`, `ThrowIfNullOrWhiteSpace` |
| `if (string.` | BASIC | `Equals`, `IsNullOrEmpty`, `CompareOrdinal`, `IsNullOrWhiteSpace`, `Empty` — `bool` выше (ожидается условие) |
| `await Task.` (в `async Task`) | BASIC | `CompletedTask`, `Delay`, `WhenAll`, `FromCanceled`, `FromException`, `Run`, `WhenAny`, `Yield` |
| `Task.Run(() => 1).Configure` | BASIC | `ConfigureAwait` |
| `foreach (var item in ` | SMART | `items` (`List<int>`), `name` (`string?`), дальше `Enumerable.*` |
| `name.` (`string?`) | BASIC | `[]`, `Count`, `Length`, `Clone`, `CompareTo`… |

Вывод: порядок внутри типа Rider строит по ожидаемому типу (`if (string.` — сначала `bool`), а не по алфавиту.

## Rider 2026.2.3.1 — отличия от 2025.1.3

Тот же набор проб (`tools/ui-robot/rider/probes.sh`, 2026-10-04), полный вывод — повторить командой. Остальное совпало пункт в пункт.

| Проба | 2025.1.3 | 2026.2.3.1 |
|---|---|---|
| generic-методы в списке | `Task.FromResult`, `Enumerable.Empty` | `Task.FromCanceled<>`, `Enumerable.Empty<>` — с `<>`, когда аргумент типа не выводится; `Task.FromResult` без них (выводится) |
| BASIC `return ` в `NotAsync` | `new Task<string>` | `new Task<string>()`; члены своего класса **жирные** |
| BASIC аргумент-токен в `WithToken` | `cancellationToken`, `new CancellationToken`, `CancellationToken.None` | `cancellationToken`, **`client`**, `new CancellationToken()` — `None` ушёл ниже |
| SMART `ReadAsync(new byte[1], ` без токена | `CancellationToken.None`, `Int32.*` | `CancellationToken.None`, `Int32.MaxValue`, `Int32.Parse`, **`await WithToken`**, **`await WithoutToken`** — метод с `Task<int>` предлагается сразу с `await` там, где ждут `int` |
| неимпортированный тип | `TextWriterTraceListener` | `TextWriterTraceListener (in System.Diagnostics)` — пространство имён в строке |
| `await Task.` | пункты обычные | пункты типа `Task` жирные |

Ещё выводы для плагина: (1) `await` в пункт completion, когда ожидается результат `Task<T>`, — в ROADMAP («Подсказки для частых
вызовов», пункт про `await`) делать как в 2026.2: пунктом `await Method` в списке, а не только серым текстом; (2) пространство имён
неимпортированного типа — в хвосте строки, как у нас в `ImportCompletion` (сверить формат).

## Полный разбор Rider 2026.2.3.1

Меню, тулбар, Alt+Enter / Generate / Refactor This / Navigate To, Settings (C#, Inlay Hints, Code Vision, Typing Assistance…),
семантическая подсветка (59 ключей `ReSharper.CSHARP_*`), inlay hints, code vision, гаттер, 75 проб completion и помощь при
наборе — [`docs/rider-analysis/README.md`](rider-analysis/README.md) (сырые дампы — `rider-analysis/dumps`, картинки —
`rider-analysis/img`, скрипты — `tools/ui-robot/rider/analysis`).
