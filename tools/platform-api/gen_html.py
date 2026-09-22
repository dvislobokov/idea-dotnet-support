"""Builds docs/platform-lsp-dap.html from api.json (javap dump of IntelliJ IDEA 2026.1.4) and the hand-written analysis below."""
import html, io, json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'docs', 'platform-lsp-dap.html')
d = json.load(io.open(os.path.join(HERE, 'api.json'), encoding='utf-8'))
lsp_used = set(d['lspUsed']) | set(d['lspUsedApi'])
dap_used = set(d['dapUsed'])
e = html.escape

# evidence levels
JAR, LOG, MEM, PROBE = 'jar', 'log', 'mem', 'probe'
EV = {PROBE: ('проверено в работающей IDE', 'ok'), JAR: ('проверено по jar', 'ok'), LOG: ('проверено по логу IDE', 'ok'), MEM: ('по памяти, не проверено', 'warn')}


def badge(text, kind):
    return '<span class="b %s">%s</span>' % (kind, e(text))


def ev(level):
    text, kind = EV[level]
    return badge(text, kind)


def status(ok, yes='есть', no='нет'):
    return badge(yes, 'ok') if ok else badge(no, 'bad')


def table(headers, rows, cls=''):
    out = ['<div class="tw"><table class="%s"><thead><tr>' % cls]
    out += ['<th>%s</th>' % h for h in headers]
    out.append('</tr></thead><tbody>')
    for row in rows:
        out.append('<tr>' + ''.join('<td>%s</td>' % c for c in row) + '</tr>')
    out.append('</tbody></table></div>')
    return ''.join(out)


def code(text):
    return '<code>%s</code>' % e(text)


# ---------------------------------------------------------------- availability
availability = [
    ('IntelliJ IDEA 2026.1.4, с подпиской', status(True), status(True),
     'Оба модуля в <code>product-info.json</code> как <code>productModuleV2</code>; jar-файлы в <code>lib/</code>.', ev(JAR)),
    ('IntelliJ IDEA 2026.1.4, без лицензии', status(True), status(True),
     'Диагностика плагина (меню .NET → Probe Platform LSP / DAP API) в IDE без лицензии, модуль <code>com.intellij.modules.ultimate</code> отключён: '
     'обе точки расширения DAP и все три LSP зарегистрированы, ключи реестра на месте, сервис <code>LspClientManager</code> создаётся, 129 из 129 классов LSP и 98 из 98 классов DAP '
     'совпадают с эталоном. В LSP при этом работают штатные провайдеры JavaScript (Flow, TypeScript). В точках DAP расширений нет: штатный JS-запускатель на DAP выключен вместе с Ultimate — '
     'сам модуль это не затрагивает. Раннер <code>DebugAdapterRunner</code> зарегистрирован. Content-модуль плагина <code>io.github.dotnetsupport.dap</code> с зависимостью на <code>intellij.platform.dap</code> загрузился, его расширение принято в <code>launchArgumentsProvider</code>, все 98 классов DAP ему видны (основному загрузчику плагина — нет, как и задумано; классы LSP видны без зависимости). Реальную сессию отладки в этом режиме ещё не запускали.', ev(PROBE)),
    ('GoLand / PyCharm / WebStorm / Rider / CLion 2026.1', badge('вероятно есть', 'warn'), badge('неизвестно', 'warn'),
     'LSP входит в общий набор модулей <code>ide.common</code> (лежит в платформенном <code>intellij.platform.ide.impl.jar</code>). DAP подключён одной строкой как '
     '«direct module» набора <code>ide.ultimate</code> (<code>product-backend.jar</code>, источник <code>UltimateModuleSets.kt</code>). Собраны ли эти IDE на '
     '<code>ide.ultimate</code> — проверить можно только по их установке: есть ли <code>lib/intellij.platform.dap.jar</code>.', ev(JAR)),
    ('IDE на открытой платформе (Android Studio, сторонние)', badge('вероятно есть', 'warn'), badge('скорее нет', 'bad'),
     'В <code>ide.common</code> модуля DAP нет.', ev(JAR)),
    ('Любая IDE 2025.x (в т.ч. GoLand / Rider 2025.1.3 на этой машине)', badge('только коммерческие', 'warn'), status(False),
     'В GoLand 2025.1.3 нет ни <code>intellij.platform.dap.jar</code>, ни отдельного <code>intellij.platform.lsp.jar</code> в <code>lib/</code>. Плагин с <code>sinceBuild = 261</code> туда и не ставится.', ev(JAR)),
]

# ---------------------------------------------------------------- LSP features
# (feature, customizer getter, LSP method, lsp4j type that proves the request is sent, what Roslyn / we need)
lsp_features = [
    ('Синхронизация документов', '—', 'didOpen / didChange / didSave / didClose', 'DidChangeTextDocumentParams', 'Нужна как есть. <code>willSave</code> не используется (нам не нужен).'),
    ('Наблюдение за файлами', '—', 'workspace/didChangeWatchedFiles', 'DidChangeWatchedFilesParams', 'Roslyn по нему узнаёт о новых / удалённых <code>.cs</code> и правках <code>.csproj</code>. Есть.'),
    ('Диагностика (push)', 'getDiagnosticsCustomizer', 'textDocument/publishDiagnostics', 'PublishDiagnosticsParams', 'Roslyn push не использует.'),
    ('Диагностика (pull)', 'getDiagnosticsCustomizer', 'textDocument/diagnostic', 'DocumentDiagnosticParams', '<b>Критично для Roslyn</b> — он отдаёт ошибки только по запросу. Тип используется, <code>refreshDiagnostics</code> обрабатывается.'),
    ('Диагностика всего workspace', '—', 'workspace/diagnostic', 'WorkspaceDiagnosticParams', 'Ошибки закрытых файлов (аналог окна Problems по solution). Нет — останется наша диагностика сборки.'),
    ('Completion', 'getCompletionCustomizer', 'textDocument/completion + resolve', 'CompletionParams', 'Есть: snippet, resolve, insertReplace, labelDetails заявлены в capabilities. Авто-<code>using</code> идёт через <code>additionalTextEdits</code> в resolve — проверить на деле.'),
    ('Signature help', 'getSignatureHelpCustomizer', 'textDocument/signatureHelp', 'SignatureHelpParams', 'Есть.'),
    ('Hover / Quick Documentation', 'getHoverCustomizer', 'textDocument/hover', 'HoverParams', 'Есть.'),
    ('Go to Definition', 'getGoToDefinitionCustomizer', 'textDocument/definition', 'DefinitionParams', 'Есть. Декомпилированные исходники Roslyn отдаёт через свои URI / <code>metadata-as-source</code> — см. «чего не хватает».'),
    ('Go to Type Definition', 'getGoToTypeDefinitionCustomizer', 'textDocument/typeDefinition', 'TypeDefinitionParams', 'Есть.'),
    ('Go to Implementation', '—', 'textDocument/implementation', 'ImplementationParams', '<b>Нет.</b> Для C# (интерфейсы, абстрактные члены) это частое действие — придётся сделать самим через <code>LspClient.sendRequest</code>.'),
    ('Go to Declaration', '—', 'textDocument/declaration', 'DeclarationParams', 'Нет. Для C# не нужно.'),
    ('Find Usages', 'getFindReferencesCustomizer', 'textDocument/references', 'ReferenceParams', 'Есть.'),
    ('Подсветка вхождений', 'getDocumentHighlightsCustomizer', 'textDocument/documentHighlight', 'DocumentHighlightParams', 'Есть.'),
    ('Rename', 'getRenameCustomizer', 'prepareRename + rename', 'RenameParams', 'Есть, с <code>prepareSupport</code>.'),
    ('Code actions / quick fixes', 'getCodeActionsCustomizer', 'textDocument/codeAction + resolve', 'CodeActionParams', 'Есть: literal support, data / resolve. Часть действий Roslyn — «вложенные» (<code>_vs_nestedCodeActions</code>) — расширение VS, в чистом LSP приходят плоским списком.'),
    ('Команды сервера', 'getCommandsCustomizer', 'workspace/executeCommand', 'ExecuteCommandParams', 'Есть.'),
    ('Применение правок', '—', 'workspace/applyEdit', 'ApplyWorkspaceEditParams', 'Есть: <code>documentChanges</code> и <code>resourceOperations</code> заявлены.'),
    ('Форматирование файла / диапазона', 'getFormattingCustomizer', 'formatting / rangeFormatting', 'DocumentRangeFormattingParams', 'Есть. Закрывает «Ctrl+Alt+L на выделении», которого нет у CSharpier / dotnet format.'),
    ('Форматирование при наборе', 'getOnTypeFormattingCustomizer', 'textDocument/onTypeFormatting', 'DocumentOnTypeFormattingParams', 'Есть. Конкурирует с нашими JSON-правилами отступов — решить, кто главный (см. ниже).'),
    ('Optimize Imports', 'getOptimizeImportsCustomizer', 'codeAction source.organizeImports', 'CodeActionParams', 'Есть как отдельная точка.'),
    ('Semantic tokens', 'getSemanticTokensCustomizer', 'semanticTokens/full', 'SemanticTokensParams', 'Есть <code>full</code>; <code>range</code> и <code>delta</code> не используются (типы <code>SemanticTokensRangeParams</code> / <code>DeltaParams</code> не задействованы) — на больших файлах дороже.'),
    ('Inlay hints', 'getInlayHintCustomizer', 'textDocument/inlayHint', 'InlayHintParams', 'Есть.'),
    ('Code lens', 'getCodeLensCustomizer', 'textDocument/codeLens', 'CodeLensParams', 'Есть (счётчики ссылок у Roslyn).'),
    ('Folding', 'getFoldingRangeCustomizer', 'textDocument/foldingRange', 'FoldingRangeRequestParams', 'Есть. У нас уже свой folding — выбрать один.'),
    ('Structure view / breadcrumbs', 'getDocumentSymbolCustomizer', 'textDocument/documentSymbol', 'DocumentSymbolParams', 'Есть, иерархические символы заявлены.'),
    ('Go to Symbol / Class', 'getWorkspaceSymbolCustomizer', 'workspace/symbol', 'WorkspaceSymbolParams', 'Есть.'),
    ('Call hierarchy', 'getCallHierarchyCustomizer', 'callHierarchy/*', 'CallHierarchyPrepareParams', 'Есть.'),
    ('Type hierarchy', 'getTypeHierarchyCustomizer', 'typeHierarchy/*', 'TypeHierarchyPrepareParams', 'Есть (зависит от сервера).'),
    ('Extend selection', 'getSelectionRangeCustomizer', 'textDocument/selectionRange', 'SelectionRangeParams', 'Есть.'),
    ('Цвета и ссылки в документе', 'getDocumentColorCustomizer / getDocumentLinkCustomizer', 'documentColor / documentLink', 'DocumentLinkParams', 'Есть, для C# малополезно.'),
    ('Прогресс сервера', '—', '$/progress, window/workDoneProgress/create', 'WorkDoneProgressCreateParams', 'Есть: загрузка solution у Roslyn видна как прогресс.'),
    ('Конфигурация', 'getWorkspaceConfiguration(item)', 'workspace/configuration', 'ConfigurationParams', 'Есть (pull). <code>didChangeConfiguration</code> (push) не используется — Roslyn перечитывает настройки по <code>workspace/configuration</code>, ок.'),
    ('Файловые операции', '—', 'workspace/willRenameFiles и др.', 'RenameFilesParams', 'Нет. Переименование файла вместе с типом — сами.'),
    ('Workspace folders (изменение)', '—', 'workspace/didChangeWorkspaceFolders', 'DidChangeWorkspaceFoldersParams', 'Нет (начальные папки передаются). Для одной solution не нужно.'),
    ('Inline values (отладка)', '—', 'textDocument/inlineValue', 'InlineValueParams', 'Нет; <code>refreshInlineValues</code> принимается, но запрос не шлётся.'),
    ('Linked editing, moniker, notebooks', '—', '…', 'LinkedEditingRangeParams', 'Нет. Не нужно.'),
]

lsp_gaps = [
    ('Запуск и загрузка solution', 'Свой дескриптор', 'Roslyn LS нужен путь к solution: нестандартные уведомления <code>solution/open</code> / <code>project/open</code> после <code>initialized</code>. '
     'Делается через <code>getLsp4jServerClass()</code> (свой интерфейс сервера с этими методами) + <code>LspServerListener.serverInitialized</code>.', MEM, 'малый'),
    ('Ожидание загрузки проектов', 'Свой клиент', 'Сервер шлёт нестандартное <code>workspace/projectInitializationComplete</code>. Принять можно, расширив <code>Lsp4jClient</code> '
     '(<code>createLsp4jClient</code>, класс помечен <code>OverrideOnly</code>, стандартные методы <code>final</code>). До этого момента диагностика ложная — её надо гасить.', MEM, 'средний'),
    ('Транспорт', 'Проверить', 'Платформа умеет stdio и socket (<code>LspCommunicationChannel.StdIO / Socket</code>). Свежий Roslyn LS поддерживает <code>--stdio</code>; старые версии — только named pipe '
     '(тогда нужен свой <code>startServerProcess</code> и мост).', MEM, 'малый / средний'),
    ('Go to Implementation', 'Написать', 'Запроса <code>textDocument/implementation</code> в платформе нет. Свой <code>GotoDeclarationHandler</code> / действие поверх <code>LspClient.sendRequest</code>.', JAR, 'малый'),
    ('Декомпилированные исходники', 'Написать', 'Переход в metadata-as-source: Roslyn отдаёт URI не из файловой системы. Нужны <code>findFileByUri</code> / <code>getFileUri</code> в дескрипторе и, возможно, виртуальная ФС только для чтения.', MEM, 'средний'),
    ('Ошибки по всей solution', 'Оставить своё', '<code>workspace/diagnostic</code> нет — окно Problems по закрытым файлам не получить. Остаётся наша диагностика последней сборки (уже сделана).', JAR, '—'),
    ('Семантические токены → палитра Rider', 'Написать', 'Сопоставление типов токенов Roslyn с нашими ключами цветов через <code>LspSemanticTokensSupport</code>; эвристический раскрасчик оставить фолбэком.', JAR, 'малый'),
    ('Конкуренция со своим редактором', 'Решить', 'Folding, Structure view, Go to Class, отступы при наборе у нас уже есть без сервера. Нужна политика: сервер готов — отдаём ему (кроме отступа по Enter: он мгновенный), иначе — наши эвристики.', JAR, 'средний'),
    ('Установка сервера', 'Написать', 'Roslyn LS — пакет NuGet <code>Microsoft.CodeAnalysis.LanguageServer.&lt;rid&gt;</code>, не dotnet tool: свой загрузчик + строка на странице .NET Tools.', MEM, 'средний'),
    ('Устаревание API', 'Учесть', 'API переименован: актуальны <code>LspIntegrationProvider</code> / <code>LspClientDescriptor</code> / <code>LspClient</code> / <code>LspClientManager</code> и точка расширения '
     '<code>platform.lsp.integrationProvider</code>. Старые <code>LspServerSupportProvider</code> / <code>LspServerDescriptor</code> помечены Deprecated (а <code>LspServerDescriptor</code> — к удалению), '
     'хотя штатные плагины IDEA (TypeScript, Vue, Tailwind) всё ещё зарегистрированы через старую <code>serverSupportProvider</code>.', JAR, '—'),
]

# ---------------------------------------------------------------- DAP features
dap_features = [
    ('Запуск адаптера', 'launchDebugAdapter (abstract)', 'процесс / socket', True, 'Есть <code>CommandLineDebugAdapterHandle</code> и <code>SocketConnectionAdapterHandle</code>. <code>dotnet-debugger</code> говорит по stdio — подходит.'),
    ('initialize', 'createInitializeParams()', 'InitializeRequestArguments', 'InitializeRequestArguments' in dap_used, 'Заявляет <code>supportsVariablePaging</code>, <code>supportsVariableType</code>, <code>linesStartAt1</code>, <code>pathFormat</code>, <code>locale</code>, <code>clientID</code>.'),
    ('launch', 'DapLaunchArgumentsProvider → Map', 'launch', True, 'Аргументы — произвольная карта: <code>program</code>, <code>args</code>, <code>cwd</code>, <code>env</code> нашего адаптера передаются как есть.'),
    ('attach', 'DapStartRequest.Attach', 'attach', True, 'Есть вид запроса и таймаут <code>dap.timeout.attach</code>. UI выбора процесса (<code>XAttachDebuggerProvider</code>) — наш.'),
    ('Точки останова на строках', 'DapBreakpointManager, DapSourceBreakpointHandler', 'setBreakpoints', 'SetBreakpointsArguments' in dap_used, 'Есть. Тип точки останова (<code>XLineBreakpointType</code> для <code>.cs</code>) объявляем мы в <code>DapBreakpointsDescription</code>.'),
    ('Условие точки останова', 'SourceBreakpoint.setCondition', 'condition', True, 'Есть — <code>setCondition</code> вызывается в <code>DapBreakpointManagerImpl</code>.'),
    ('Hit count', '—', 'hitCondition', False, '<b>Нет</b>: <code>setHitCondition</code> нигде не вызывается.'),
    ('Logpoints', '—', 'logMessage', False, '<b>Нет</b>: <code>setLogMessage</code> нигде не вызывается. Платформенный «Log message» у точки останова до адаптера не доходит.'),
    ('Точки останова на исключениях', 'DapExceptionBreakpoint, doesExceptionMatchBreakpoint', 'setExceptionBreakpoints', 'SetExceptionBreakpointsArguments' in dap_used, 'Есть, с <code>ExceptionFilterOptions</code> и <code>exceptionInfo</code>. У нашего адаптера необработанные исключения сейчас не останавливают — ограничение адаптера, не платформы.'),
    ('Function / data / instruction breakpoints', '—', 'setFunctionBreakpoints …', False, 'Нет. Для .NET не критично.'),
    ('Потоки и стек', 'DapThread, DapStackFrame', 'threads, stackTrace', 'StackTraceArguments' in dap_used, 'Есть; стек грузится порциями (<code>dap.lazy.load.stack.trace.chunk.size</code>, 100 кадров).'),
    ('Области и переменные', 'DapScope, DapVariable, VariableBatch, VariableFilter', 'scopes, variables', 'VariablesArguments' in dap_used, '<b>Постранично</b>: <code>setStart</code> / <code>setCount</code> / <code>setFilter</code> вызываются. Это главное требование нашего адаптера (без страниц он зависает / падает) — закрыто.'),
    ('Evaluate, watches, hover', 'DapExpressionEvaluator, DefaultDapXDebuggerEvaluator', 'evaluate', 'EvaluateArguments' in dap_used, 'Есть, с <code>frameId</code>; таймаут <code>dap.timeout.evaluate</code> = 30 с.'),
    ('Completion в Evaluate', '—', 'completions', False, 'Нет.'),
    ('Set Value', '—', 'setVariable / setExpression', False, '<b>Нет</b>: типы <code>SetVariableArguments</code> / <code>SetExpressionArguments</code> не используются. Правка значения в окне Variables — самим (свой <code>XValueModifier</code> поверх <code>AbstractDapXValue</code>).'),
    ('Шаги', 'DapThread, SteppingTarget, StepSize', 'next, stepIn, stepOut, continue, pause', 'NextArguments' in dap_used, 'Есть, с <code>granularity</code> и <code>singleThread</code>. Есть даже <code>stepBack</code>.'),
    ('Выбор цели Step Into', '—', 'stepInTargets', False, 'Нет (Smart Step Into).'),
    ('Run to Cursor / Set Next Statement', '—', 'gotoTargets, goto', False, 'Нет типов <code>GotoTargets</code>. Run to Cursor платформа делает временной точкой останова — проверить на деле.'),
    ('Restart', '—', 'restart', False, 'Нет: перезапуск = остановить и запустить заново.'),
    ('Завершение', '—', 'terminate, disconnect', 'TerminateArguments' in dap_used, 'Есть, с таймаутами <code>dap.timeout.terminate</code> / <code>disconnect.*</code>. Принудительное убийство зависшего адаптера — проверить (у нашего долгий запрос блокирует и <code>disconnect</code>).'),
    ('Консоль программы', 'DapEventConsumer', 'событие output', 'OutputEventArguments' in dap_used, 'Есть.'),
    ('Запуск в терминале', '—', 'runInTerminal (обратный запрос)', False, 'Нет. Консольные приложения с вводом — через консоль IDE.'),
    ('Дочерние сессии', '—', 'startDebugging', False, 'Нет.'),
    ('Загруженные модули / исходники', '—', 'modules, loadedSources, source', False, 'Нет. Нет и запроса <code>source</code> — исходник без файла (декомпиляция) не открыть.'),
    ('Прогресс адаптера', '—', 'progressStart / Update / End', 'ProgressStartEventArguments' in dap_used, 'Есть.'),
    ('Отмена запроса', '—', 'cancel', False, 'Нет. Долгий <code>variables</code> / <code>evaluate</code> не отменить — только таймаут.'),
    ('Память, дизассемблер', '—', 'readMemory, disassemble', False, 'Нет. Не нужно.'),
    ('Сопоставление путей', 'DapVirtualFileResolver', '—', True, 'Есть точка расширения <code>createVirtualFileResolver()</code>.'),
    ('Свой протокол адаптера', 'getDebugAdapterServerClass(), createClient(...)', '—', True, 'Можно подменить интерфейс сервера lsp4j.debug и клиента: нестандартные запросы адаптера доступны.'),
    ('Свой XDebugProcess', 'createXDebugProcess(...)', '—', True, 'Можно унаследовать <code>DapXDebugProcess</code> и добавить недостающее (Set Value, свои действия).'),
]

dap_gaps = [
    ('Описание адаптера', 'Написать', '<code>DebugAdapterSupportProvider</code> + <code>DebugAdapterDescriptor</code>: запуск <code>dotnet-debugger</code> (он уже заведён как <code>DotNetTool.DEBUGGER</code>), '
     '<code>DapBreakpointsDescription</code>, резолвер файлов.', 'малый'),
    ('Аргументы launch', 'Написать', '<code>DapLaunchArgumentsProvider</code> для run configuration «.NET Project»: <code>program</code> (dll после нашей сборки), <code>args</code>, <code>cwd</code>, <code>env</code>, профиль launchSettings.', 'малый'),
    ('Тип точки останова для C#', 'Написать', '<code>XLineBreakpointType</code> для <code>.cs</code> (где можно ставить — по нашему лексеру / сканеру объявлений) и тип для исключений.', 'малый'),
    ('Сборка перед отладкой', 'Написать', 'Before-launch нашим Build, чтобы ошибки шли в окно Build и в редактор; Debug без сборки (<code>--no-build</code>).', 'малый'),
    ('Set Value', 'Дописать поверх платформы', 'Свой <code>XValueModifier</code> → <code>setVariable</code> / <code>setExpression</code> через свой интерфейс сервера.', 'средний'),
    ('Hit count и logpoints', 'Дописать или ждать платформу', 'Либо свой обработчик точек останова вместо <code>DapSourceBreakpointHandler</code>, либо смириться. Наш адаптер их поддерживает (по DAP_PLAN).', 'средний'),
    ('Attach to Process', 'Написать', '<code>XAttachDebuggerProvider</code> со списком .NET-процессов (у нас уже есть список для Monitor). Учесть: второй attach на Windows убивает процесс.', 'средний'),
    ('Отладка тестов', 'Написать', '<code>VSTEST_HOST_DEBUG=1</code> + attach по PID — поверх пункта выше.', 'средний'),
    ('Зависший адаптер', 'Проверить', 'Наш адаптер блокируется на долгих запросах (неявные <code>ToString()</code>, большие коллекции). Платформа даёт таймауты, но нет <code>cancel</code>. '
     'Нужно убедиться, что Stop убивает процесс адаптера; возможно — свой выключатель «не вычислять свойства» (есть на странице настроек под замком).', 'неизвестно'),
    ('Стабильность API', 'Риск', 'Весь пакет <code>com.intellij.platform.dap</code> — <code>@Experimental</code> (55 из 98 классов помечены явно). В 2026.2 (ветка 262) сигнатуры могли измениться: '
     'держать интеграцию в отдельном модуле плагина с необязательной зависимостью.', '—'),
    ('Продукты без модуля', 'Решить', 'Если в GoLand / PyCharm / WebStorm модуля нет — либо там отладки не будет, либо остаётся свой клиент из DAP_PLAN как запасной путь.', '—'),
]


def registry_keys(xml):
    rows = []
    for m in re.finditer(r'<registryKey\b([^>]*?)/?>', xml, re.S):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        if 'key' in attrs:
            rows.append((code(attrs['key']), code(attrs.get('defaultValue', '')), e(attrs.get('description', ''))))
    return rows


def extension_points(xml):
    rows = []
    for m in re.finditer(r'<extensionPoint\b([^>]*?)/?>', xml, re.S):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        rows.append((code(attrs.get('qualifiedName', attrs.get('name', ''))), code(attrs.get('interface', attrs.get('beanClass', ''))), e(attrs.get('dynamic', ''))))
    return rows


def short(decl):
    text = decl
    for prefix in ('com.intellij.platform.lsp.api.customization.', 'com.intellij.platform.lsp.api.', 'com.intellij.platform.dap.xdebugger.', 'com.intellij.platform.dap.connection.',
                   'com.intellij.platform.dap.', 'org.eclipse.lsp4j.debug.', 'org.eclipse.lsp4j.', 'com.intellij.openapi.project.', 'com.intellij.openapi.vfs.', 'java.lang.', 'java.util.concurrent.',
                   'java.util.', 'kotlin.coroutines.', 'kotlin.jvm.functions.', 'kotlin.', 'com.intellij.execution.runners.', 'com.intellij.execution.configurations.', 'com.intellij.execution.process.',
                   'com.intellij.execution.', 'com.intellij.xdebugger.breakpoints.', 'com.intellij.xdebugger.frame.', 'com.intellij.xdebugger.', 'com.intellij.openapi.editor.', 'kotlinx.coroutines.'):
        text = text.replace(prefix, '')
    return text


def marks_html(marks):
    kinds = {'Experimental': 'warn', 'Deprecated': 'bad', 'ScheduledForRemoval': 'bad', 'Internal': 'bad', 'OverrideOnly': 'info', 'NonExtendable': 'info', 'Obsolete': 'warn'}
    return ' '.join(badge(m, kinds.get(m, 'info')) for m in marks)


def reference(classes, strip):
    out = []
    skip = re.compile(r'\b(component\d+|copy|copy\$default|hashCode|equals|toString|getEntries|values|valueOf)\(')
    for c in sorted(classes, key=lambda c: c['name']):
        name = c['name'].replace(strip, '')
        if '$Companion' in name or name.endswith('Kt') and not c['members']:
            continue
        members = [m for m in c['members'] if not skip.search(m['decl']) and 'Companion' not in m['decl']]
        out.append('<details class="cls" data-name="%s"><summary><code>%s</code> <span class="kind">%s</span> %s <span class="n">%d</span></summary>' % (
            e(name.lower()), e(name), e(c['kind']), marks_html(c['marks']), len(members)))
        if members:
            out.append('<ul>')
            for m in members:
                out.append('<li>%s<code>%s</code>%s</li>' % ('<span class="abs">abstract</span> ' if m['abstract'] and c['kind'] != 'interface' else '', e(short(m['decl'])), (' ' + marks_html(m['marks'])) if m['marks'] else ''))
            out.append('</ul>')
        out.append('</details>')
    return ''.join(out)


def params(names, used):
    return ' '.join('<span class="t %s">%s</span>' % ('on' if n in used else 'off', e(n)) for n in names)


lsp_params = [t for t in d['lsp4jAll'] if t.endswith('Params') and not t.startswith(('TextDocumentPosition', 'WorkDone', 'PartialResult'))]
dap_args = [t for t in d['dap4jAll'] if t.endswith('Arguments')]
lsp_rows = [(e(f), code(c) if c != '—' else '—', code(m), status(t in lsp_used), n) for f, c, m, t, n in lsp_features]
dap_rows = [(e(f), code(c) if c != '—' else '—', code(m), status(bool(ok)), n) for f, c, m, ok, n in dap_features]
lsp_yes = sum(1 for r in lsp_features if r[3] in lsp_used)
dap_yes = sum(1 for r in dap_features if r[3])
lsp_classes = d['lsp']
dap_classes = d['dap']

css = """
:root{--bg:#f7f7f5;--fg:#1c1d21;--mut:#686b73;--card:#fff;--line:#e2e2de;--ok:#1d7a46;--okb:#e2f3e8;--bad:#b3261e;--badb:#fbe7e5;--warn:#8a5a00;--warnb:#fbefd2;--info:#2b5ea7;--infob:#e4edf9;--code:#f0f0ec;--acc:#5b3fd1}
@media (prefers-color-scheme:dark){:root{--bg:#1e1f22;--fg:#dfe1e5;--mut:#9da0a8;--card:#2b2d30;--line:#3a3c40;--ok:#6fcf97;--okb:#22382b;--bad:#ff8a80;--badb:#402524;--warn:#f0c674;--warnb:#3b3220;--info:#8ab4f8;--infob:#23324a;--code:#34363a;--acc:#b39dff}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,"Segoe UI",Roboto,sans-serif}
.wrap{max-width:1500px;margin:0 auto;padding:24px 20px 80px}h1{font-size:26px;margin:0 0 4px}h2{font-size:20px;margin:44px 0 10px;padding-top:12px;border-top:1px solid var(--line)}h3{font-size:15px;margin:26px 0 8px}
p{margin:8px 0;max-width:1100px}.sub{color:var(--mut)}code{font:12.5px/1.4 "JetBrains Mono",Consolas,monospace;background:var(--code);padding:1px 5px;border-radius:4px}
nav{position:sticky;top:0;z-index:5;background:var(--bg);padding:10px 0;border-bottom:1px solid var(--line);display:flex;gap:14px;flex-wrap:wrap;align-items:center}
nav a{color:var(--acc);text-decoration:none;font-weight:600}nav input{margin-left:auto;padding:7px 10px;border:1px solid var(--line);border-radius:6px;background:var(--card);color:var(--fg);min-width:260px;font:inherit}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:12px;margin:16px 0}.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
.card b{font-size:24px;display:block}.card span{color:var(--mut)}
.tw{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:var(--card);margin:10px 0}table{border-collapse:collapse;width:100%}th,td{text-align:left;vertical-align:top;padding:8px 12px;border-bottom:1px solid var(--line)}
th{font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:var(--mut);background:var(--bg);position:sticky;top:0}tr:last-child td{border-bottom:0}td:first-child{font-weight:600;min-width:190px}
.b{display:inline-block;padding:1px 8px;border-radius:20px;font-size:12px;font-weight:600;white-space:nowrap}.ok{color:var(--ok);background:var(--okb)}.bad{color:var(--bad);background:var(--badb)}.warn{color:var(--warn);background:var(--warnb)}.info{color:var(--info);background:var(--infob)}
.t{display:inline-block;margin:2px 3px 2px 0;padding:1px 7px;border-radius:4px;font:12px "JetBrains Mono",Consolas,monospace}.t.on{background:var(--okb);color:var(--ok)}.t.off{background:var(--badb);color:var(--bad)}
details.cls{background:var(--card);border:1px solid var(--line);border-radius:8px;margin:5px 0;padding:6px 12px}details.cls summary{cursor:pointer}details.cls ul{margin:8px 0 4px;padding-left:18px}details.cls li{margin:3px 0;word-break:break-word}
.kind{color:var(--mut);font-size:12px}.n{color:var(--mut);font-size:12px;float:right}.abs{color:var(--info);font-size:12px}.note{background:var(--card);border-left:4px solid var(--acc);padding:10px 14px;border-radius:0 8px 8px 0;margin:12px 0;max-width:1100px}
.hide{display:none}
"""

js = """
const q=document.getElementById('q');q.addEventListener('input',()=>{const v=q.value.trim().toLowerCase();
document.querySelectorAll('tbody tr').forEach(r=>r.classList.toggle('hide',v&&!r.textContent.toLowerCase().includes(v)));
document.querySelectorAll('details.cls').forEach(c=>{const hit=!v||c.textContent.toLowerCase().includes(v);c.classList.toggle('hide',!hit);c.open=!!v&&hit&&c.dataset.name.includes(v)});});
"""

parts = []
parts.append('<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LSP и DAP в платформе IntelliJ 2026.1</title><style>%s</style></head><body><div class="wrap">' % css)
parts.append('<h1>LSP и DAP в платформе IntelliJ 2026.1</h1><p class="sub">Что есть в <code>IntelliJ IDEA 2026.1.4</code> (сборка IU-261.26222.65), чего не хватает плагину C# Project Support и что придётся дописать. '
             'Данные сняты <code>javap</code> (JDK 25) с <code>lib/intellij.platform.lsp.jar</code>, <code>lsp.impl.jar</code>, <code>dap.jar</code> 21.09.2026. '
             'У каждого вывода указан уровень проверки.</p>')
parts.append('<nav><a href="#sum">Итог</a><a href="#avail">Доступность</a><a href="#lsp">LSP</a><a href="#dap">DAP</a><a href="#plan">Что писать самим</a><a href="#ref">Справочник API</a>'
             '<input id="q" placeholder="Фильтр по таблицам и классам: completion, Experimental, setVariable…"></nav>')

parts.append('<h2 id="sum">Итог</h2><div class="cards">')
for big, small in (('%d из %d' % (lsp_yes, len(lsp_features)), 'возможностей LSP из списка ниже платформа закрывает сама'),
                   ('%d из %d' % (dap_yes, len(dap_features)), 'возможностей DAP закрыто платформой'),
                   ('%d / %d' % (len(lsp_classes), len(dap_classes)), 'публичных классов в API LSP / DAP'),
                   ('%d из %d' % (len([t for t in lsp_params if t in lsp_used]), len(lsp_params)), 'типов запросов LSP (…Params) реально используются платформой'),
                   ('%d из %d' % (len([t for t in dap_args if t in dap_used]), len(dap_args)), 'типов запросов и событий DAP (…Arguments) используются')):
    parts.append('<div class="card"><b>%s</b><span>%s</span></div>' % (big, small))
parts.append('</div>')
parts.append('<div class="note"><b>LSP</b> — зрелый и общий для всех IDE слой: pull-диагностика (без неё Roslyn бесполезен), completion с resolve, rename, code actions, форматирование диапазона и при наборе, '
             'semantic tokens, inlay hints, иерархии — всё есть. Не хватает Go to Implementation, ошибок по закрытым файлам и всего, что специфично для Roslyn (открытие solution, ожидание загрузки, '
             'декомпилированные исходники, установка сервера). Свой JSON-RPC из <code>LSP_PLAN.md</code> писать не нужно.</div>')
parts.append('<div class="note"><b>DAP</b> — рабочий, но экспериментальный и подключён только в наборе модулей Ultimate. Закрывает запуск, точки останова с условием, исключения, стек, '
             '<b>постраничные</b> переменные (главное требование нашего адаптера), evaluate, шаги. Нет Set Value, hit count, logpoints, restart, runInTerminal, cancel. '
             'Слои 1–2 из <code>DAP_PLAN.md</code> заменяются описанием адаптера; слой 3 частично придётся дописывать поверх платформы.</div>')

parts.append('<h2 id="avail">Доступность: продукт и лицензия</h2>')
parts.append(table(['Где', 'LSP', 'DAP', 'На чём основан вывод', 'Проверка'], availability))
parts.append('<p>Как плагину зависеть от модулей (так сделано в штатных плагинах IDEA): для DAP — <code>&lt;dependencies&gt;&lt;module name="intellij.platform.dap"/&gt;&lt;/dependencies&gt;</code> '
             'в дескрипторе <b>отдельного модуля плагина</b> (так у Jupyter и JS-отладчика), чтобы без DAP плагин всё равно загружался. Для LSP есть псевдоним <code>com.intellij.modules.lsp</code>.</p>')

parts.append('<h2 id="lsp">LSP</h2><h3>Точки расширения и настройки</h3>')
parts.append(table(['Точка расширения', 'Интерфейс', 'dynamic'], extension_points(d['xml']['lsp.impl/intellij.platform.lsp.impl.xml'])))
parts.append(table(['Ключ реестра', 'По умолчанию', 'Описание'], registry_keys(d['xml']['lsp.impl/intellij.platform.lsp.impl.xml'])))
parts.append('<h3>Возможности: что платформа делает сама</h3><p>«Есть» означает: тип запроса lsp4j используется в коде платформы (проверено по константам классов). '
             'Столбец «Настройка» — метод <code>LspCustomization</code>, через который поведение включается, выключается или подстраивается (у каждого есть варианты <code>…Support</code> и <code>…Disabled</code>).</p>')
parts.append(table(['Возможность', 'Настройка', 'Запрос LSP', 'Платформа', 'Для Roslyn / для нас'], lsp_rows))
parts.append('<h3>Покрытие протокола</h3><p>Все типы <code>…Params</code> из lsp4j: зелёные платформа использует, красные — нет.</p><p>%s</p>' % params(lsp_params, lsp_used))
parts.append('<h3>Чего не хватает и что допиливать</h3>')
parts.append(table(['Что', 'Действие', 'Подробности', 'Проверка', 'Объём'], [(e(a), e(b), c, ev(lv), e(size)) for a, b, c, lv, size in lsp_gaps]))

parts.append('<h2 id="dap">DAP</h2><h3>Точки расширения и настройки</h3>')
parts.append(table(['Точка расширения', 'Интерфейс', 'dynamic'], extension_points(d['xml']['dap/intellij.platform.dap.xml'])))
parts.append(table(['Ключ реестра', 'По умолчанию', 'Описание'], registry_keys(d['xml']['dap/intellij.platform.dap.xml'])))
parts.append('<h3>Возможности</h3><p>Статус выведен из того, какие типы <code>lsp4j.debug</code> и какие сеттеры вызываются в классах модуля.</p>')
parts.append(table(['Возможность', 'API платформы', 'Запрос / событие DAP', 'Платформа', 'Замечания'], dap_rows))
parts.append('<h3>Покрытие протокола</h3><p>Все типы <code>…Arguments</code> из lsp4j.debug: зелёные используются, красные — нет. '
             '<code>LaunchRequestArguments</code> / <code>AttachRequestArguments</code> красные, потому что платформа шлёт запуск картой, а не типом lsp4j.</p><p>%s</p>' % params(dap_args, dap_used))
parts.append('<h3>Чего не хватает и что допиливать</h3>')
parts.append(table(['Что', 'Действие', 'Подробности', 'Объём'], [(e(a), e(b), c, e(size)) for a, b, c, size in dap_gaps]))

parts.append('<h2 id="plan">Что писать самим: сводка</h2>')
parts.append(table(['Направление', 'Было по планам', 'С платформой 2026.1', 'Главный риск'], [
    ('Отладчик', 'Свой DAP-клиент и мост в XDebugger: слои 1–3, 6–8 дней', 'Описание адаптера, аргументы launch, тип точки останова, сборка перед стартом: 1–2 дня до первого Debug; затем Set Value, attach, тесты',
     'API <code>@Experimental</code>; наличие модуля вне IDEA не проверено; поведение с зависающим адаптером'),
    ('C# через Roslyn', 'Свой JSON-RPC и все фичи: фазы 1–7, 3–4 недели', 'Дескриптор клиента + открытие solution + ожидание загрузки + установка сервера: ~1 неделя до диагностики, переходов и completion; затем Go to Implementation, metadata-as-source, цвета',
     'Нестандартные сообщения Roslyn описаны по памяти — нужна разведка (фаза 0 из LSP_PLAN) на живом сервере'),
    ('Свои эвристики редактора', '—', 'Остаются фолбэком: сканер объявлений, правила отступов, диагностика сборки, раскраска по токенам', 'Нужна политика «сервер готов / не готов», чтобы фичи не дублировались'),
]))

parts.append('<h2 id="ref">Справочник API</h2><p>Публичные и protected члены, как их показывает <code>javap</code>; пакеты сокращены. Фильтр сверху ищет и по классам.</p>')
parts.append('<h3>LSP — <code>com.intellij.platform.lsp.api</code> (%d классов)</h3>' % len(lsp_classes))
parts.append(reference(lsp_classes, 'com.intellij.platform.lsp.api.'))
parts.append('<h3>DAP — <code>com.intellij.platform.dap</code> (%d классов)</h3>' % len(dap_classes))
parts.append(reference(dap_classes, 'com.intellij.platform.dap.'))
parts.append('</div><script>%s</script></body></html>' % js)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
io.open(OUT, 'w', encoding='utf-8', newline='\n').write(''.join(parts))
print(OUT, os.path.getsize(OUT), 'bytes;', 'lsp features', lsp_yes, '/', len(lsp_features), '; dap', dap_yes, '/', len(dap_features))
