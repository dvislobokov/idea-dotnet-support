# roslyn-language-server: что у него снаружи

К сборке плагина не относится. Здесь зонд (`probe.py`) и то, что он выяснил про сервер **5.12.0-1.26426.8** (dotnet tool
`roslyn-language-server`, внутри — `Microsoft.CodeAnalysis.LanguageServer`, .NET 10). На этих фактах стоит страница настроек
Settings | Tools | .NET | Language Server (`src/main/kotlin/io/github/dotnetsupport/lsp`) и будет стоять клиент из `LSP_PLAN.md`.

```sh
python tools/roslyn-lsp/probe.py <папка с solution> --out tools/roslyn-lsp/out/probe.json   # PYTHONIOENCODING=utf-8
```

Зонд запускает сервер с `--stdio`, делает `initialize` / `initialized`, шлёт `workspace/didChangeConfiguration`, на `workspace/configuration`
отвечает `null` на каждый пункт и пишет в JSON: `serverInfo`, `capabilities`, динамические регистрации, запрошенные секции настроек, методы
запросов и уведомлений сервера. Свой сервер — переменной `ROSLYN_LANGUAGE_SERVER`.

`capture.py` — **снятый трафик**: по одному запросу каждого типа (diagnostic, completion + resolve, hover, signature help, definition / type definition /
implementation / references, symbols, folding, selection range, semantic tokens, inlay hints, code lens + resolve, code actions + resolve + вложенные +
Fix All, rename, форматирование трёх видов, call / type hierarchy), каждый — файлом `{method, params, result | error, ms}` в `out/capture/`. Capabilities
в `initialize` — настоящие от LSP-клиента IntelliJ (`client-capabilities.json`; обновить: `tools/ui-robot/scripts/lsp_capabilities.js` в живой песочнице).
Записи `cache …` — факты для кэша ответов (фаза 3): completion после пробела, детерминизм tokens, tokens большого файла, целый ли первый completion по имени.
`--fixtures src/test/resources/roslyn/capture-5.12` пишет обезличенную копию для `RoslynCapturedTrafficTest`. С этого начинать работу с новой версией сервера
и любую новую возможность клиента: формы Roslyn не угадываются.

`bench.py` — замер скорости: открывает solution (`solution/open`), ждёт `workspace/projectInitializationComplete`, меряет completion (после `.`,
на голом имени, при наборе по букве), resolve, hover, signature help, semantic tokens, диагностику, символы. Файл на диске не трогает.
Итоги и выводы про кэш — в `LSP_PLAN.md`.

```sh
python tools/roslyn-lsp/bench.py debug-playground/DebugPlayground.sln   # --file Console/Scenarios.cs --line 60 --out tools/roslyn-lsp/out/bench.json
```

## Установка и запуск

- `dotnet tool install --global roslyn-language-server`. Пакет привязан к runtime, поэтому на Windows в `~/.dotnet/tools` лежит
  **`roslyn-language-server.cmd`**, а не `.exe` (сам exe — в `.store/.../tools/net10.0/win-x64`). Поиск tools плагина это учитывает.
- Без `--stdio` или `--pipe <имя>` сервер не стартует вовсе («Expected either --stdio or --pipe»); справка — `--stdio --help`.

| Аргумент | Что делает |
|---|---|
| `--stdio` / `--pipe <name>` | транспорт; плагину нужен `--stdio` |
| `--logLevel <None\|Critical\|Error\|Warning\|Information\|Debug\|Trace>` | подробность лога |
| `--extensionLogDirectory <dir>` | куда писать файлы лога |
| `--autoLoadProjects [N]` | сервер сам находит и грузит проекты из workspace folders; N — предел числа проектов (без N — рекомендованный сервером). Без аргумента клиент обязан сам назвать solution / проекты (`solution/open`, `project/open`) |
| `--sourceGeneratorExecutionPreference <Automatic\|Balanced>` | генераторы на каждое изменение или на сохранение / сборку |
| `--clientProcessId <pid>` | сервер завершится, когда не станет процесса клиента |
| `--telemetryLevel <all\|crash\|error\|off>`, `--sessionId` | телеметрия Dev Kit, по умолчанию `off` |
| `--extension <dll>`, `--devKitDependencyPath`, `--csharpDesignTimePath`, `--brokeredServicePipeName` | расширения и интеграция с C# Dev Kit; плагину не нужны |
| `--debug` | ждать отладчик при старте |
| `--daemon`, `--daemonKeepAlive <сек>` | внутренний многоклиентский режим тонкого клиента; редакторам передавать не положено |

## Настройки: `workspace/configuration`

Сервер не читает настройки из `initializationOptions`: после `initialized` и после каждого `workspace/didChangeConfiguration` он **сам запрашивает**
значения списком секций. Ответ — массив той же длины; `null` = «оставь своё умолчание».

- Имя секции: `csharp|<группа>.<имя>` и то же с `visual_basic|`; часть секций без языка (`projects.*`, `navigation.*`,
  `code_style.formatting.new_line.insert_final_newline`), плюс `razor.*` и `html.*`.
- Версия 5.12 запрашивает **80 уникальных секций** (160 с повторами: список приходит дважды). Полный список — в
  `src/test/resources/roslyn/configuration-sections-5.12.txt`, тест сверяет с ним каталог опций страницы.
- Группы: `background_analysis` (области диагностики компилятора и анализаторов), `projects` (авто-restore, file-based programs, binlog),
  `completion`, `quick_info`, `navigation`, `symbol_search`, `highlighting`, `inlay_hints` (13 штук), `code_lens`, `auto_insert`,
  `formatting`, `type_members`, `code_style.formatting.*` (отступы — их клиент должен брать из Code Style IDE, а не со страницы сервера).
- Значения перечислений: области диагностики — имена enum `BackgroundAnalysisScope` без учёта регистра (`openFiles`, `fullSolution`, `none`; `openDocuments` сервер 5.12 отвергает: «Failed to parse ... to type BackgroundAnalysisScope» — видно в логе живой IDE);
  `type_members.*` — литералы `with_other_members_of_the_same_kind` / `at_the_end`, `prefer_throwing_properties` / `prefer_auto_properties`
  (найдены среди строк сборок сервера). У `completion.dotnet_completion_items_from_unimported_namespaces_commit_behavior` значения не выяснены —
  на странице её нет, задаётся вручную в «Other Settings of the Server».
- Умолчания на странице — как у расширения C# для VS Code (по памяти, не из кода сервера); каждая опция страницы всегда отдаётся серверу явным
  значением, так что показанное на странице и есть то, с чем сервер работает.
- В сборках есть и другие имена опций, которые 5.12 не запрашивает (`dotnet_trigger_completion_on_deletion`, `dotnet_lsp_max_completion_list_size`,
  `dotnet_return_key_completion_behavior`, `dotnet_snippets_behavior`, …) — часть из них сервер, возможно, начнёт запрашивать позже.

## Протокол

- **URI диска — с обычным двоеточием.** На `file:///c%3A/...` (так пишут VS Code и LSP-клиент платформы IntelliJ) сервер 5.12 отвечает, но считает
  документ посторонним: нет ошибок компилятора (`CS…`), все `using` «лишние» (IDE0005); папку workspace в таком виде `--autoLoadProjects` не находит
  («Workspace folder path /c:/... does not exist, skipping», 0 проектов). `file:///c:/...` и `file:///C:/...` работают одинаково.
- С `--autoLoadProjects` сервер сам находит единственный `.sln` папки и по окончании тоже шлёт `workspace/projectInitializationComplete`.
- `textDocument/diagnostic` без `identifier` отдаёт всё сразу (компилятор + анализаторы); с `identifier` — по источникам: `DocumentCompilerSemantic`
  (CS…), `DocumentAnalyzerSemantic` (IDE…), `syntax`, `DocumentAnalyzerSyntax`, `NonLocal`.
- В `tags` каждой диагностики — числа Visual Studio (`2147483642`, `2147483645`, …) рядом со стандартными (`1` = Unnecessary). Клиент, который
  разбирает теги в enum (lsp4j) и возвращает диагностику в `textDocument/codeAction`, шлёт `null` — сервер отвечает ошибкой
  «The JSON value could not be converted to DiagnosticTag» на весь запрос.
- **Команды клиента.** `roslyn.client.peekReferences [uri, position]` (code lens «N references»), `roslyn.client.nestedCodeAction [{NestedCodeActions: [...]}]`
  (действие с вариантами; вариант несёт `data`, правку даёт `codeAction/resolve`), `roslyn.client.fixAllCodeAction [{FixAllFlavors: [...]}]` (правку даёт
  нестандартный `codeAction/resolveFixAll {title, data, scope}`). Сервер их только описывает: отправленные в `workspace/executeCommand`, они не делают ничего.
- `completionItem/resolve` для элемента без `data` отменяет запрос **и останавливает сервер** («Server was requested to shut down» на всё дальнейшее). `data`
  сервер кладёт в `itemDefaults`, когда клиент объявил `completionList.itemDefaults: [..., "data"]` (платформа IntelliJ объявляет и сама подставляет `data` обратно).
- Определение символа фреймворка — настоящий файл на диске: `%TEMP%/MetadataAsSource/<id>/DecompilationMetadataAsSourceFileProvider/<id>/Console.cs`.
- При включённом авто-restore сервер сам запускает `dotnet restore` во время `solution/open` и клиента не спрашивает (`_roslyn_projectNeedsRestore` не приходит).
- Completion после пробела (в теле класса, trigger-символ `" "`) — `null`. Первый completion по имени, если он — самая первая работа сервера, урезан
  (154 элемента из 630, без `Console`) и всё равно `isIncomplete: false`; после pull-диагностики — целый. Дальше в пределах слова список один и тот же
  (`isIncomplete: false`), клиенту перезапрашивать незачем.
- `textDocument/implementation` отдаёт точки на именах реализаций (пустой range). Rename типа правит только текст — файл `<Тип>.cs` сервер не
  переименовывает (записи `phase7 …`).
- Декомпилированный файл: `#region Assembly System.Console, Version=9.0.0.0, …`, затем `// <путь к dll>`. Открытый клиентом, он понятен серверу:
  символы, раскраска, hover, переход дальше (в следующий декомпилят), диагностика пустая. Новый переход — новая папка с GUID в `MetadataAsSource`.
- Semantic tokens детерминированы; файл в 4 154 строки — 170–500 мс и 375 КБ JSON на запрос `full`.
- Язык сообщений и code lens — из `locale` в `initialize` (без него — язык ОС); `DOTNET_CLI_UI_LANGUAGE` на это не влияет.

- `serverInfo.name` = `CSharpVisualBasicLanguageServerFactory`. От сервера после старта приходят запросы `client/registerCapability`,
  `workspace/configuration` и уведомление `window/logMessage`.
- `capabilities`: completion, signature help, hover, definition / typeDefinition / implementation, references, documentHighlight, documentSymbol,
  workspaceSymbol, codeAction, codeLens, formatting / rangeFormatting / onTypeFormatting, rename, foldingRange, selectionRange, semanticTokens,
  inlayHint, callHierarchy, typeHierarchy, executeCommand, плюс нестандартное `_vs_onAutoInsertProvider`.
- В сборках видны нестандартные методы загрузки проектов (`solution/open`, `project/open`, `workspace/projectInitializationComplete`) — нужны,
  когда сервер запущен без `--autoLoadProjects`. `bench.py` проверил: `solution/open` — уведомление с `{"solution": "<uri>"}`, конец загрузки — уведомление `workspace/projectInitializationComplete`.
