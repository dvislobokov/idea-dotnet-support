# Собственный LSP-клиент: оценка и план

Статус: **план, работы не начаты** (2026-09-20). Краткая версия с чекбоксами — в `ROADMAP.md`, раздел «Собственный LSP-клиент».

## Задача
Дать плагину семантику языка (ошибки компилятора, completion, переходы, рефакторинги) через language server,
**не завязываясь** на сторонние плагины (LSP4IJ) и на встроенный LSP API платформы (в бесплатных IDE он доступен только
с 2026.2, целевая IDE — GoLand 2025.1.3). Клиент пишется с нуля и не привязан к одному серверу.

## Почему это реализуемо
- LSP — это JSON-RPC 2.0 поверх stdio с фреймингом `Content-Length`. Транспорт — несколько сотен строк; Gson уже есть в платформе.
- Позиции в протоколе — UTF-16, как смещения в Java-строках: конвертация не нужна.
- Вся интеграция делается через обычные публичные точки расширения, которые есть в любой IDE на платформе IntelliJ:
  `ExternalAnnotator`, `CompletionContributor`, `GotoDeclarationHandler`, `customUsageSearcher`,
  `AsyncDocumentFormattingService`, intentions, Structure view, inlay hints. Плоского PSI плагина для них достаточно:
  нужен элемент под кареткой, смысл приходит от сервера.
- Первый сервер — `roslyn-language-server` (официальный dotnet tool, MIT; у пользователя установлен глобально, версия 5.12,
  запуск `--stdio`, есть `--autoLoadProjects`).
- Сервер описывается **определением**: команда запуска, типы файлов, language id, особенности протокола. Позже так подключается
  `csharp-ls` или любой другой сервер.

## Решения по устройству
- **Без lsp4j.** Нужны ~35 методов протокола; свои DTO на Gson под них меньше и понятнее. lsp4j сэкономила бы пару дней ценой
  зависимости на 1,5 МБ.
- **Слои:** транспорт (JSON-RPC) → сессия сервера (жизненный цикл, capabilities, синхронизация документов) → функции редактора.
  Функции не знают о процессе и потоках; транспорт не знает об IDE. Это же позволяет заменить транспорт платформенным, если
  когда-нибудь понадобится.
- **Эвристики плагина остаются** (раскраска идентификаторов, обнаружение тестов, endpoints): они работают, когда сервера нет
  или solution ещё грузится. Сервер, когда готов, имеет приоритет.
- **Цель — не «весь LSP 3.17»**, а то, что отдаёт Roslyn. Notebook, moniker, linked editing и подобное для C# не нужны.
- Транспорт фазы 1 без изменений годится для **DAP** (netcoredbg): фрейминг одинаковый. Путь к отладчику становится вдвое короче.

## Фазы
Оценки — в днях работы разработчика.

### Фаза 0 — разведка (0,5–1 д). Решение «идём / не идём»
- Запустить сервер скриптом на тестовом solution, снять реальный трафик: `initialize`, открытие solution
  (`solution/open` / `project/open` / `--autoLoadProjects`), `workspace/projectInitializationComplete`, pull-диагностика,
  completion + resolve, динамическая регистрация capabilities.
- Замерить время загрузки solution, память процесса, требования к runtime (для 5.12 — .NET 10).
- Записать нестандартные методы Roslyn: `textDocument/_vs_onAutoInsert`, `workspace/_roslyn_restore`,
  `workspace/_roslyn_projectNeedsRestore`, вложенные code actions и Fix All.
- Результат: снятый трафик как тестовые фикстуры, список особенностей, уточнённые оценки.

**Сделано заранее (2026-09-21):** зонд `tools/roslyn-lsp/probe.py` и сводка в `tools/roslyn-lsp/README.md` — аргументы запуска сервера 5.12,
`capabilities`, 80 секций `workspace/configuration`, нестандартные методы (`solution/open`, `project/open`, `workspace/projectInitializationComplete`,
`workspace/_roslyn_restore`, …). Настройки и чистые функции для клиента уже есть: `lsp/RoslynLanguageServerSettings.kt`
(`RoslynLanguageServer.arguments(...)` — командная строка, `RoslynLanguageServer.configuration(...)` — ответ на `workspace/configuration`,
отступы берутся из Code Style через `RoslynCodeStyle`), страница Settings | Tools | .NET | Language Server, tool `DotNetTool.ROSLYN_LANGUAGE_SERVER`.

### Фаза 1 — транспорт и жизненный цикл (2–3 д)
- JSON-RPC 2.0: фрейминг, запросы / ответы / уведомления, `$/cancelRequest`, `$/progress` (work done → фоновая задача IDE), таймауты.
- Процесс сервера на проект: старт по требованию (есть solution или открыт `.cs`), перезапуск с backoff, stderr,
  `window/logMessage`, `window/showMessage`, `workspace/configuration`, `client/registerCapability` / `unregisterCapability`.
- URI ↔ путь (Windows: `file:///c%3A/...`, регистр буквы диска), позиция ↔ offset.
- UI: путь к серверу на странице Settings | Tools | .NET рядом с остальными tools, аргументы, уровень логов, выключатель;
  виджет статуса (loading / ready / crashed); действие Restart; окно с логом сервера и трассой JSON.
- Тесты: сценарный фейковый сервер на in-memory pipe.

### Фаза 2 — синхронизация документов и диагностика (2–3 д) — первая видимая польза
- `didOpen` / инкрементальный `didChange` / `didSave` / `didClose`, версии документов.
- `workspace/didChangeWatchedFiles` из VFS: файлы, созданные и удалённые вне редактора, в том числе нашими действиями New.
- Pull-диагностика (`textDocument/diagnostic`; Roslyn push не использует) → `ExternalAnnotator`: severity, тег `unnecessary`
  серым, `deprecated` зачёркнутым, код правила со ссылкой на документацию; ответы для устаревшей версии документа отбрасываются.
- Состояние «solution загружается»: без ложных ошибок до `projectInitializationComplete`.

### Фаза 3 — навигация и документация (2–3 д)
- Definition / type definition / implementation через `GotoDeclarationHandler`, включая metadata-as-source: сервер сам
  отдаёт декомпилированные исходники фреймворка — основная ценность аналога dotPeek приходит без отдельного кода.
- Hover → документация (markdown → HTML), подсветка вхождений (`textDocument/documentHighlight`).
- Find Usages через `customUsageSearcher`; Go to Symbol / Class через `workspace/symbol`.

### Фаза 4 — completion и signature help (3–4 д) — самое чувствительное к UX
- `CompletionContributor`: отмена запроса при наборе, `isIncomplete` и повторный запрос, `filterText` / `sortText` /
  `preselect`, commit characters, `itemDefaults`.
- `completionItem/resolve`: `textEdit` + `additionalTextEdits` (авто-`using`), сниппеты LSP → live template.
- Signature help на `(` и `,`.

### Фаза 5 — правки кода (3–4 д)
- Применение `WorkspaceEdit`: `documentChanges`, создание / переименование / удаление файлов, проверка версий, одна
  undo-команда; `workspace/applyEdit`.
- Rename (`prepareRename` + `rename`), форматирование файла и диапазона (`AsyncDocumentFormattingService`), on-type formatting,
  генерация `///` через `_vs_onAutoInsert`.
- Code actions: quick fix у диагностики и intentions под кареткой (предзагрузка, потому что intentions платформы синхронные),
  вложенные действия и Fix All Roslyn.

### Фаза 6 — семантическая полировка (2–3 д)
- Semantic tokens → палитра Rider, уже сделанная в плагине, вместо эвристического классификатора (тот остаётся фолбэком).
- Inlay hints, Structure view и breadcrumbs из `documentSymbol`, folding, code lens со ссылками.
- Call hierarchy / type hierarchy — по желанию.

### Фаза 7 — надёжность (2–3 д, частично по ходу остальных фаз)
- Большие solution, несколько solution в проекте, штормы перезапусков, батчинг `didChange`, dumb mode.
- Предупреждение, если тот же сервер уже подключён через LSP4IJ (двойной запуск: у пользователя LSP4IJ установлен).
- Второй сервер как проверка общности: `csharp-ls`; определения серверов в настройках.

## Итог по срокам
| Веха | Фазы | Срок | Что получает пользователь |
|---|---|---|---|
| MVP | 0–3 | 7–10 д | Ошибки компилятора в редакторе, переходы, документация, поиск использований |
| «Ощущается как IDE» | + 4 | 10–14 д | Completion с авто-`using`, signature help |
| Полный объём | 0–7 | 17–25 д | Рефакторинги, quick fixes, форматирование, семантическая раскраска, надёжность |

## Риски
1. **Синхронные API платформы против асинхронного сервера**: intentions, Structure view, parameter info. Решается кэшем
   последнего ответа с обновлением в фоне; здесь будет больше всего итераций по замечаниям из живой IDE.
2. **Нестандартные расширения Roslyn** плохо документированы и меняются между prerelease-версиями. Лечится фиксацией
   поддерживаемых версий сервера и хранением снятого трафика как фикстур.
3. **Ресурсы**: отдельный процесс на сотни мегабайт, загрузка большого solution — десятки секунд. Все функции должны
   корректно жить в состоянии «ещё грузится».
4. **Сопровождение**: LSP4IJ пишут годами, но у него сотни серверов и все языки; здесь один язык и один-два сервера.
5. **Узкое место — проверка, а не код**: редакторный UX headless-тестами не покрыть, после каждой фазы нужна проверка в IDE.

## Как проверять
- Транспорт и сессия — юнит-тесты на фейковом сервере с записанным трафиком.
- Функции редактора — платформенные тесты (`myFixture`) с тем же фейковым сервером.
- Сквозные пробы — реальный `roslyn-language-server` на тестовом solution во временной папке.
- UX — вручную в GoLand после каждой фазы.

## Следующий шаг
Фаза 0: за полдня даёт ответ «стоит ли» и уточняет оценки остальных фаз.
