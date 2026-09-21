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
- Значения перечислений: области диагностики — имена enum `BackgroundAnalysisScope` без учёта регистра (`openDocuments`, `fullSolution`, `none`);
  `type_members.*` — литералы `with_other_members_of_the_same_kind` / `at_the_end`, `prefer_throwing_properties` / `prefer_auto_properties`
  (найдены среди строк сборок сервера). У `completion.dotnet_completion_items_from_unimported_namespaces_commit_behavior` значения не выяснены —
  на странице её нет, задаётся вручную в «Other Settings of the Server».
- Умолчания на странице — как у расширения C# для VS Code (по памяти, не из кода сервера); каждая опция страницы всегда отдаётся серверу явным
  значением, так что показанное на странице и есть то, с чем сервер работает.
- В сборках есть и другие имена опций, которые 5.12 не запрашивает (`dotnet_trigger_completion_on_deletion`, `dotnet_lsp_max_completion_list_size`,
  `dotnet_return_key_completion_behavior`, `dotnet_snippets_behavior`, …) — часть из них сервер, возможно, начнёт запрашивать позже.

## Протокол

- `serverInfo.name` = `CSharpVisualBasicLanguageServerFactory`. От сервера после старта приходят запросы `client/registerCapability`,
  `workspace/configuration` и уведомление `window/logMessage`.
- `capabilities`: completion, signature help, hover, definition / typeDefinition / implementation, references, documentHighlight, documentSymbol,
  workspaceSymbol, codeAction, codeLens, formatting / rangeFormatting / onTypeFormatting, rename, foldingRange, selectionRange, semanticTokens,
  inlayHint, callHierarchy, typeHierarchy, executeCommand, плюс нестандартное `_vs_onAutoInsertProvider`.
- В сборках видны нестандартные методы загрузки проектов (`solution/open`, `project/open`, `workspace/projectInitializationComplete`) — нужны,
  когда сервер запущен без `--autoLoadProjects`. Их точные параметры зондом не проверялись: это фаза 0–1 `LSP_PLAN.md`.
