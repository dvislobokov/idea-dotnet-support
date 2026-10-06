# UI-робот: проверка плагина в живой IDE

К сборке плагина не относится. Позволяет управлять отдельным экземпляром IDE (песочницей) снаружи — агенту или скрипту: открыть проект,
поставить точку останова, запустить отладку, нажать кнопку в диалоге, прочитать тексты компонентов, снять картинку окна.

Основа — [JetBrains Remote Robot](https://github.com/JetBrains/intellij-ui-test-robot): плагин `robot-server` внутри IDE открывает HTTP-порт
`127.0.0.1:8583` (только локально). Подключён задачей `runIdeForUiTests` в `build.gradle.kts`; `robot-server` — единственное, что сборка скачивает
(из репозитория плагинов JetBrains), и только для этой задачи.

## Запуск

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2026.1.4\jbr"
./gradlew.bat runIdeForUiTests --no-daemon   # в фоне: задача живёт, пока открыта IDE; на экране появляется второе окно IDE
python tools/ui-robot/robot.py wait          # дождаться порта (около 40 с на холодный старт)
```

- `--no-daemon`: песочница — дочерний процесс того, кто выполняет задачу. На общем демоне Gradle её убивает любой `--stop` или сборка из
  соседнего проекта, которая уронила демон (так пропала песочница 2026-09-30).
- Если в окружении есть `HTTP_PROXY`, робот не ответит (`451` или сброс соединения — это прокси, а не IDE): перед командами
  `export NO_PROXY=127.0.0.1` (Python читает и `no_proxy`).
- Песочница при старте открывает последний проект — обычно сам `debug-playground` с общим `.idea`; открыть копию (`open`), а исходный
  закрыть через `ProjectUtil.closeAndDispose` из `js`. Закрывать исходный первым нельзя: без проектов IDE уходит на Welcome-экран.

### Песочница в WSL: настоящая мышь и клавиатура (`wsl/`)

Песочница `runIdeForUiTests` открывается на рабочем столе пользователя, поэтому робот работает только через API IDE: настоящее движение
мыши (подсказки по наведению), набор с клавиатуры и запись видео мешали бы человеку. В WSL та же IDE (IDEA для Linux той же сборки) идёт
на невидимом экране Xvfb — там можно всё. Проверено 2026-10-05: наведение показывает документацию, набор `Console.Wri` открывает completion,
Ctrl+Z откатывает, видео пишется.

```sh
export MSYS_NO_PATHCONV=1 ROBOT_WSL=1          # Git Bash не должен превращать /home/... в C:/Program Files/Git/home/...
./gradlew.bat buildPlugin -q                   # zip плагина; robot-server берётся из песочницы одного runIdeForUiTests
wsl -d Ubuntu -- bash tools/ui-robot/wsl/start-ide.sh build/distributions/idea-dotnet-support-<версия>.zip   # порт 8596, экран :99
wsl -d Ubuntu -- bash tools/ui-robot/wsl/copy-playground.sh      # копия площадки в ~/robot/playground + restore
. tools/ui-robot/scripts/session.sh            # с ROBOT_WSL=1 $ROBOT и robot_js ходят через wsl/robot.sh
$ROBOT open /home/dvislobokov/robot/playground
robot_js choose_solution.js "s|__SOLUTION__|DebugPlayground.sln|g"     # повторять до loaded: true
robot_js screen_point.js "s|__FILE__|/home/.../Console/Program.cs|g" "s|__AT__|WriteLine|g" "s|__AFTER__|foreach|g"   # → x y строка:столбец
S="wsl -d Ubuntu -- bash tools/ui-robot/wsl/screen.sh"
$S move 939 355; $S move 943 356              # наведение (два движения: подсказка ждёт остановки мыши), затем через 2–3 с shot
$S click 840 443; $S type "Console.Wri"       # настоящий набор, по символу через 30 мс; key ctrl+space / alt+Return / Escape / ctrl+z
$S shot C:/tmp/screen.png                     # весь виртуальный экран (на нём только песочница)
$S record C:/tmp/typing.mp4 8                 # видео, в фоне параллельно с набором
robot_js exit_ide.js; wsl -d Ubuntu -- bash tools/ui-robot/wsl/stop-ide.sh
```

- Робот с Windows до WSL не достучаться: сервер слушает 127.0.0.1 внутри WSL, проброс localhost в режиме NAT его не видит. Поэтому `robot.py`
  запускает питон WSL (`wsl/robot.sh`), а временный скрипт `robot_js` кладёт в `build/ui-robot/`, где его видно из обеих систем.
- IDE обязательно на X11 (`-Dawt.toolkit.name=XToolkit`, без `WAYLAND_DISPLAY`): иначе через WSLg она выбирает Wayland и открывается
  на рабочем столе пользователя, а экран Xvfb остаётся чёрным.
- `robot_js` срезает начало ответа до первой буквы — числа в ответе скрипта ставить не первыми (`screen_point.js` печатает `at x y …`).
- Нужно в Ubuntu: IDEA Community для Linux в `~/ide` (`idea-<версия localIdePath>.tar.gz` из GitHub-релиза `idea/<версия>` у `JetBrains/intellij-community`, распаковывается в `idea-IC-<сборка>`; полная — `IDE_EDITION=IU`), .NET SDK в `~/.dotnet`
  (`dotnet-install.sh`, без sudo), `roslyn-language-server` той же версии, что на Windows (`~/.dotnet/dotnet tool install -g`), пакеты
  `xvfb x11-utils xdotool ffmpeg libxtst6 libxrender1 libxi6 libfreetype6 fontconfig fonts-dejavu` (sudo — один раз, пользователь).
  `unzip` не нужен: zip распаковывает `python3 -m zipfile`.
- Свои настройки и система — `~/robot/sandbox` в WSL. Площадку открывать копией в `~/robot/playground`: на `/mnt/c` `dotnet` и индексация
  медленные, а незаконченная правка пользователя в `CompletionRanking.cs` берётся из HEAD.
- Одна песочница на машину: перед запуском WSL-песочницы закрыть песочницу Windows (память общая).

Песочница — `.intellijPlatform/sandbox/idea-dotnet-support/IU-*`, свои настройки и свои проекты, рабочий экземпляр IDE не затрагивается. Лицензии в песочнице нет (в тулбаре
«Start Free Trial») — DAP-модуль и плагин при этом работают. Отладка идёт настоящим `dotnet` и установленным `dotnet-debugger`.

### Рабочий стол Windows: `desktop.ps1` (взят у idea-golang-support, 2026-10-05)
`powershell -File tools/ui-robot/desktop.ps1 <команда>`: `window <часть заголовка>` (выводит `x y w h`, выводит окно вперёд), `shot <out.png> <часть
заголовка>`, `click <x> <y> [right|double]`, `drag`, `wheel`, `key <SendKeys>`, `type <текст>`, `pos`. Настоящие мышь и клавиатура Windows для того,
что робот API не может: модальные диалоги, клики по ссылкам уведомлений, набор как у человека, вид окна в Windows (шрифты, масштаб).
- Только когда пользователь сказал, что не трогает мышь и клавиатуру: скрипт двигает его настоящий курсор и печатает в активное окно.
- Снимать **только окно песочницы** (`shot <out> <заголовок>`), не весь экран.
- Наведение так не срабатывает (Swing не показывает тултипы на впрыснутые движения) — наводить AWT-роботом внутри IDE: `scripts/hover.js`,
  `scripts/quickdoc.js` (взяты у Go-плагина, тексты под Go — править под C# при первом использовании); `scripts/gutter_click.js` — клик по иконке поля.
- Координаты — физические пиксели; границы компонентов из робота — логические координаты окна: начало и масштаб даёт `window`.

## Команды (`python tools/ui-robot/robot.py ...`, вывод в UTF-8: `PYTHONIOENCODING=utf-8`)

| Команда | Что делает |
|---|---|
| `wait` | ждёт, пока сервер ответит |
| `windows` | окна и диалоги IDE |
| `open DIR` | открыть проект в песочнице |
| `openfile FILE [LINE]` | открыть файл, каретка на строке (с 1) |
| `breakpoint FILE LINE` | переключить точку останова на строке (с 1) |
| `run "NAME" [Debug]` | запустить run configuration по имени |
| `action ID` | действие IDE по id: `StepOver`, `StepInto`, `StepOut`, `Resume`, `Pause`, `Stop`, `Exit`, `ViewBreakpoints`… |
| `find XPATH` | компоненты по XPath и их тексты |
| `click XPATH` | клик по первому совпадению |
| `shot OUT.png [XPATH]` | картинка компонента; без XPath — главное окно |
| `js FILE.js [--edt]` | выполнить JavaScript (Rhino) внутри IDE: доступен весь API платформы |
| `tree OUT.html` | дерево компонентов с готовыми XPath (то же показывает `http://127.0.0.1:8583` в браузере) |

Типовой сценарий:

```sh
P="C:/Users/dvislobokov/idea-dotnet-support/debug-playground"
python robot.py open "$P"
python robot.py click "//div[@class='MyDialog']//div[@text='Skip']"      # диалог «quick tour» первого запуска
python robot.py breakpoint "$P/Console/Scenarios.cs" 60
python robot.py run "Console: All" Debug
python robot.py find "//div[@class='XDebuggerFramesList']"               # ждать, пока в кадрах появится Scenarios
python robot.py shot out.png
python robot.py action Stop
python robot.py action Exit && python robot.py click "//div[@class='MyDialog']//div[@text='Exit']"
```

## Скрипты для проверок отладчика (`scripts/`)

`. tools/ui-robot/scripts/session.sh` (Git Bash, из корня репозитория) даёт функции поверх `robot.py js`:
`state`, `wait_state СЕКУНДЫ REGEX`, `evaluate "выражение" [сколько детей показать]`, `set_value имя/путь значение`, `stop_all`, `robot_js файл [sed-замены]`.
Скрипты-шаблоны с `__ПОДСТАНОВКАМИ__`: `state.js`, `evaluate.js`, `set_value.js` (через модификатор значения, как F2), `run_to.js` (Run to Cursor),
`add_exception_bp.js` / `default_bp.js` / `exception_bps.js` (точки на исключения), `debugger_settings.js`, `edit_unsaved.js`, `stop_all.js`.
Для этапов 4–5: `line_bp_extras.js` (hit count и log message точки), `line_bp_condition.js`, `test_configuration.js` (конфигурация `dotnet test` с
фильтром), `attach.js` (подключиться к PID, как Attach to Process), `attach_list.js` (`__NAME__` — часть имени exe: каким процессам
Attach to Process предлагает отладчик .NET — провайдер плагина спрашивается так же, как диалог), `sessions.js` (все отладочные сессии), `resume_all.js`, `show_settings.js`, `complete.js` (completion в поле Evaluate: текст → элементы списка).
Для языкового сервера: `editor_file.js` (файл выбранного редактора: путь, заголовок вкладки, можно ли править, баннеры, строка каретки),
`rename_via_server.js` (переименование как у LSP-клиента платформы, но без её inline-шаблона: `textDocument/rename` через клиент и применение
правки одной командой; `__AT__` / `__NAME__` / `__NEW__`), `inline_rename.js` (настоящий Shift+F6 с шаблоном — у робота ненадёжен, шаблону нужен фокус; при «Rename» = Built-in обработчик —
`NativeCSharpRenameHandler`, шаблон стартует сразу, без `prepareRename` сервера, `__WAIT__` можно ставить 500),
`lsp_timings.js` (строка состояния: загружен ли solution, сколько файлов раскрашено tokens из кэша; с `__TABLE__` = `yes` —
таблица времён запросов, как в меню .NET → Language Server Timings; опрашивать в цикле, чтобы увидеть, что происходит во время загрузки;
вывод `robot_js` срезает начало строк на `t` — `tDocument/…` это `textDocument/…`), `editor_action.js` (действие IDE с контекстом редактора — штатный `action` его не даёт, и Rename / Show Usages молчат),
`invoke_intention.js` (выполнить пункт Alt+Enter по тексту), `rename_handlers.js` (кому достанется Shift+F6), `ctrl_hover.js` (что видит Ctrl+наведение),
`lsp_command.js` (клиентская команда Roslyn, как клик по code lens), `lsp_capabilities.js` (capabilities платформы для `capture.py`), `lsp_policy.js` (кто красит и сворачивает: подсветки по ключам цвета, регионы folding и совпадающие диапазоны), `popup_at.js` (Ctrl+Q / Ctrl+P или другое действие на месте `__AT__` после `__AFTER__`, текст popup и его страницы `1/2`), `intentions.js` (необязательный якорь `__AFTER__`)
(что предлагает Alt+Enter с кареткой в `__AT__`), `type_text.js` (набрать текст посимвольно и после каждого символа показать, есть ли popup completion, сколько в нём элементов и фазу completion
платформы — для жалоб вида «подсказка была только на первом слове»; автопопапу нужен фокус окна песочницы, первый прогон после старта бывает пустым),
`type_char.js` (набрать символ как с клавиатуры — срабатывают typed handlers и onTypeFormatting),
`completion_behaviour.js` (0.1.91: строка под маркером `__MARKER__` в `__FILE__`, набрать `__TEXT__` посимвольно, `__EXPLICIT__` = `yes` — затем
Ctrl+Space; список: фокус — `UNFOCUSED` у режима подсказки, выбранный пункт, позиции `__FIND__`; `__DOC__` = `yes` — Quick Doc выбранного
пункта через провайдеры документации пунктов; затем набрать `__THEN__` (символы выбора) и показать строку; текст файла восстанавливается),
`editor_lines.js` (строки редактора с `__TEXT__`); список выбора в popup кликается штатным `clicktext "//div[@class='JBList']" "текст"`. `lsp_state.js` (клиент Roslyn: состояние, загружен ли workspace, что подсвечено в открытом редакторе; `__LIMIT__`) и
`lsp_editor.js` (каретка после `__AFTER__`, набрать `__TYPE__`, затем `__WHAT__` = `complete` — элементы списка, или `goto` — куда привёл Go to Declaration; `__WAIT__` мс).
Надёжнее него — `complete_at_line.js` (`__FILE__` с прямыми слэшами, `__LINE__` с 1, `__TYPE__`, `__LIMIT__` элементов, `__WAIT__` мс, `__UNDO__` = `yes`,
`__FIND__` — имена через запятую, для которых нужна позиция в списке): файл открывает сам (selected editor при сплите — тот, где фокус), окно
песочницы выводит на передний план (`ProjectUtil.focusProjectWindow`: без фокуса окна список пуст), элементы снимает опросом, пока popup жив;
пустой ответ — повторить, первый запрос после открытия файла бывает пустым. `ghost_at_line.js` (те же `__FILE__` / `__LINE__` / `__TYPE__` /
`__WAIT__` / `__UNDO__`) набирает как с клавиатуры и показывает серый inline-текст (`InlineCompletionContext.textToInsert`) и popup.
`settings_widths.js` (`__CONFIGURABLE__` — класс страницы настроек, `__WIDTH__` — ширина диалога, `__LIMIT__` — порог) открывает Settings на
странице, сжимает диалог и печатает компоненты шире порога с их preferred width — кто распирает страницу (так нашлись combo box и label
на 697 и 664 px). Диалог модальный: всё, что выполняется в нём, — через `invokeAndWait(..., ModalityState.any())`, иначе робот виснет до
закрытия диалога; закрыть — `click "//div[@class='MyDialog']//div[@text='Cancel']"`. Перезапуск песочницы при открытом модальном диалоге:
`action Exit` не сработает — убить процесс (`Stop-Process` по `runIdeForUiTests` в командной строке), иначе новая сборка упадёт в
`prepareSandbox` и робот ответит из старой.
`syntax_tree.js` (`__SOURCE__` = `NATIVE` / `ROSLYN` / `keep`, `__FILE__`, `__LINES__` — номера строк через запятую) переключает источник
«Structure, folding and breadcrumbs» (`CSharpFeature.SYNTAX_TREE`), как страница настроек, открывает файл и печатает: какое у него дерево (PSI
Roslyn-видов или эвристическое), Structure, breadcrumbs по строкам, регионы folding, ошибки и маркеры гаттера — снять в обоих режимах и сравнить
`diff`. Подсветке нужно время: после переключения или открытия повторить с `keep`; маркеры есть только у выбранного редактора.
`build_framework.js` (`__FRAMEWORK__` — `net10.0` или пусто для умолчания) выбирает TFM, как комбобокс тулбара: у C#-файлов другие символы
`#if` — вместе с `syntax_tree.js` проверяет `TYPE:active-branch` (`debug-playground/MultiTarget/ActiveBranch.cs`).
`feature_source.js` (`__FEATURE__` — имя из `CSharpFeature`, `__SOURCE__` — `NATIVE` / `ROSLYN` / `keep`) переключает источник фичи, как
Apply страницы Language Server, и печатает выбор и `CSharpFeatures.native`; с `find_usages.js` — сверка видов использований в обоих режимах.
Помощь при наборе (`CSharpFeature.EDITING`, 0.1.48) — тоже в обоих режимах через `feature_source.js` (`EDITING`, `NATIVE` / `ROSLYN`):
`extend_selection.js` (`__FILE__`, `__AT__` — каретка на втором символе первого вхождения, `__TIMES__`) жмёт Ctrl+W (`EditorSelectWord`) N раз
и печатает дерево файла (`CSharpFile` — встроенное, `HeuristicCSharpFile` — эвристика) и каждое выделение; сценарии и якоря — маркеры
`TYPE:extend-selection-*` в `debug-playground/Console/Editor/ExtendSelection.cs` (якорь — первое вхождение, а комментарий маркера его часто содержит;
проверено роботом: `name.Trim()) + 1`; строка — `'s|__AT__|\\"plain text here\\");|g'` (кавычка экранируется для JS); интерполяция —
`'s|__AT__|\\"total {|g'`; условие — ` > 1)` вместе с `"s|getText()).indexOf|getText()).lastIndexOf|"`; перевод строки и `|` в якоре через
`robot_js` не передаются). `complete_statement.js` (`__FILE__`, `__AT__` — маркер вида `// TYPE:complete-call `, `__TYPE__` — что набрать)
набирает текст на первой пустой строке после маркера с его отступом, жмёт Ctrl+Shift+Enter (`EditorCompleteStatement`), печатает строки с
`<caret>` и откатывает через Undo до исходного текста (3 шага в обоих режимах; пишет `undone` или `NOT undone`); с пустым `__TYPE__`
каретка встаёт в конец строки `__AT__` (так `TYPE:complete-two-lines`: `__AT__` = ` Make(a,` — с пробелом, мимо комментария). Серый `;`
(`TYPE:gray-semicolon`) — `ghost_at_line.js` после того, как с копии файла снят `);` у `.Where(x => x > 0);`. Сценарии — `debug-playground/Console/Editor/CompleteStatement.cs`.
Форматирование (`CSharpFeature.FORMATTING`, 0.1.49) — в обоих режимах через `feature_source.js` (`FORMATTING`, `NATIVE` / `ROSLYN`):
`reformat.js` (`__FILE__`; `__AT__` и `__END__` — выделение от начала строки первого `__AT__` до конца строки первого `__END__` после него;
пустой `__END__` — весь файл без выделения) жмёт Ctrl+Alt+L (`ReformatCode`), печатает, кто форматирует (`NativeCSharpFormatting.engaged`),
строки результата и откатывает Undo (`undone` / `NOT undone`); ответа сервера и `dotnet format` ждёт до 20 с. Сценарии — маркеры
`TYPE:format-*` в `debug-playground/Console/Editor/Formatting.cs`; якоря — однострочные (перевод строки через `robot_js` не передаётся):
метод `Sum` — `__AT__` = `public  int  Sum`, `__END__` = `// TYPE:format-file` (в выделение попадает и строка маркера, она не меняется).
Вживую роботом скрипт ещё не прогонялся.
`goto_declaration.js` (`__FILE__`; `__AT__` — якорь, начинающийся с имени, каретка на его первом символе) печатает, кто отвечает
(`NativeCSharpNavigation.serves`: встроенное дерево при `NAVIGATION` = NATIVE, иначе сервер), цели дерева (`файл:строка  текст строки`;
`tree: none` — имя уходит серверу), затем жмёт Ctrl+B (`GotoDeclaration`) и печатает, где каретка (`to: файл:строка  текст`), или что она
осталась на месте (список из нескольких целей — закрыть Escape — или переходить некуда); ответа сервера ждёт 1,5 с. Переключатель — `feature_source.js`
(`NAVIGATION`). Сценарии — маркеры `TYPE:nav-*` в `debug-playground/Console/Editor/Navigation.cs`; якорь — первое вхождение в файле, а
комментарии маркеров имена упоминают, так что брать текст строки кода: `total + parsed` (→ `var total`), `parsed + first` (→ `out var parsed`),
`x * 2;` (→ параметр лямбды), `Twice(3) +`, `retry;`, `doubled > 2`, `g.Key`, `Add(1)` (список из двух), `Reset();`, `UsageLog.Record`
(дерево — `UsageLog`), `Count;` (дерево — none, сервер). Вживую роботом скрипт ещё не прогонялся.
Цвета идентификаторов (`CSharpFeature.SEMANTIC_COLORS`, 0.1.51) — в обоих режимах через `feature_source.js` (`SEMANTIC_COLORS`, `NATIVE` /
`ROSLYN`): `highlight_keys.js` (`__FILE__`, `__LINE__` и `__END__` — строки с 1, пустой `__END__` — одна строка) открывает файл и печатает
каждый раскрашенный ключом палитры `CSHARP_*` диапазон строк: `строка: текст -> КЛЮЧ (daemon | markup)` — `daemon` от аннотаторов
(встроенные цвета и эвристика), `markup` — подсветки разметки редактора и документа (туда могут попадать semantic tokens сервера); ключи
лексера не печатаются. После открытия и переключения повторять, пока вывод не устоится; снять в обоих режимах и сравнить `diff`. Сценарии —
маркеры `TYPE:colors-*` в `debug-playground/Console/Editor/SemanticColors.cs` (строки под маркером до следующего маркера). Вживую роботом
скрипт ещё не прогонялся.
Цвета при открытии файла (0.1.84): `color_timing.js` (`__FILE__`; `__DIR__` — открыть сначала этот проект, пусто — последний открытый;
`__SHOTS__` — папка для снимков редактора, пусто — без них; `__CLOSE__` = `yes` — сначала закрыть файл) открывает файл и в потоке IDE каждые
несколько мс считает подсветки цветом идентификатора: `daemon` (разметка документа — аннотаторы), `zombies` (из них восстановленные кэшем
разметки платформы), `opening` (слой `CSharpOpeningColors` в разметке самого редактора); снимки — на первом тике EDT после открытия, на первом
цвете и в конце. Возвращается сразу, запись — `color_timing_status.js` (времена в мс от открытия, последняя строка `done`). Документ закрытого
файла живёт, пока его не соберёт GC: чтобы проверить открытие «с нуля», закрыть файл и вызвать `com.intellij.util.ref.GCUtil.tryGcSoftlyReachableObjects()`.
Ошибки и предупреждения (`CSharpFeature.DIAGNOSTICS`, синтаксическая часть, 0.1.54) — в обоих режимах через `feature_source.js` (`DIAGNOSTICS`,
`NATIVE` / `ROSLYN`): `errors_at.js` (`__FILE__`) открывает файл и печатает каждую подсветку уровня WARNING и выше в порядке текста:
`строка:колонка-строка:колонка [ERROR|WARNING] текст | описание`; текст `<eol>` — ошибка показана за концом строки, `<empty>` — нулевой
ширины. Описание — `CS1002: ; expected` и от дерева плагина, и от сервера, поэтому кто сообщил, видно только по разнице двух прогонов. Сценарии —
маркеры `TYPE:diag-*` в `debug-playground/Broken/SyntaxErrors.cs` (файл исключён из компиляции `Broken`). С `NATIVE` синтаксических ошибок
сервера не должно быть вторым экземпляром на той же строке. Вживую роботом скрипт ещё не прогонялся.
Completion (`CSharpFeature.COMPLETION`, синтаксическая часть, 0.1.55) — в обоих режимах через `feature_source.js` (`COMPLETION`,
`NATIVE` / `ROSLYN`): `complete_at_line.js` под маркерами `TYPE:complete-*` в `debug-playground/Console/Editor/NativeCompletion.cs` и
`CommonCalls.cs`, `__UNDO__` = `yes`; с `NATIVE` пункты есть до загрузки сервера, после — без повторов имён. Эталон — `rider_complete.js` на
том же месте. Вживую роботом скрипт ещё не прогонялся.
Postfix и live templates (0.1.89): `template_expand.js` (`__FILE__`, `__LINE__` с 1, `__TYPE__` — набрать в конце строки, `__BEFORE__` /
`__LINES__` — сколько строк показать до и после) жмёт Tab как редактор (`TemplateManager.startTemplate(editor, '\t')`: и live, и postfix),
печатает список у первой остановки шаблона, доводит шаблон до конца, печатает строки с `<caret>` и выделение и возвращает текст (`restored`).
Списки postfix по типу — `complete_at_line.js` с `__TYPE__` = `ready.` (пункты `.if`, `.foreach`…). Кавычки в `__TYPE__` ломают скрипт.
**Rider как эталон.** `rider/start-rider.ps1` поднимает установленный Rider под робота (порт 8594) в отдельной папке
`%USERPROFILE%\rider-robot` с копией настроек и лицензии, без хранилища паролей; рабочий Rider не трогается. `rider_complete.js`
(`__FILE__`, `__LINE__`, `__TYPE__`, `__KIND__` = `BASIC` / `SMART`, `__SYNC__` — пауза, пока правка дойдёт до бэкенда, `__LIMIT__`,
`__WAIT__`) печатает пункты completion с типом — им же можно снимать наш плагин. Снятое — `docs/RIDER_REFERENCE.md`. Если Rider успел
сохранить недонабранную строку, файл копии вернуть с диска (`FileDocumentManager.reloadFromDisk`).
`goto_names.js` (`__KIND__` — `class` / `symbol`, `__NAMES__` — имена через запятую) спрашивает Go to Class / Symbol только у контрибуторов
плагина (stub-индексы встроенного дерева, шаг 8) и печатает строки, как в попапе, с файлом и строкой; файлы, которые покрыл готовый сервер,
контрибуторы пропускают, поэтому сначала `server_enabled.js` (`__ENABLED__` = `false`). Вместе с `build_framework.js` — проверка веток `#if`
после смены TFM (`MultiTarget/ActiveBranch.cs`: `Net10Only` / `Net9Only`).
У площадки два solution: сервер ждёт выбора (`RoslynWorkspace.solutionChosen("DebugPlayground.sln")`, как в `baseline_start.js`).
Порт: старая песочница из соседнего worktree может держать 8583 или 8591 и отвечать вместо своей (видно по версии плагина в ошибке
`ClassNotFoundException`) — проверить `netstat -ano | grep LISTEN` и взять свободный `-ProbotPort`. Если `runIdeForUiTests` падает на
configuration cache («cannot serialize Gradle script object references») — запускать с `--no-configuration-cache`.
`hierarchy.js` (`__FILE__`, `__AT__`, `__ACTION__` = `TypeHierarchy` / `CallHierarchy` / `GotoSuperMethod`, `__WAIT__`) выполняет действие с
контекстом редактора и печатает, куда встала каретка и что в окне Hierarchy (дерево через `getUserObject` узлов пока печатается пусто —
смотреть `shot` компонента `RoslynTypeHierarchyBrowser` / `RoslynCallHierarchyBrowser` или `find` по нему: тексты строк в нём есть).
Ссылки по solution (0.1.73, C4b): `usages_compare.js` (`__ROOT__`, `__CASES__` — `путь:строка:имя;…` или `@файл` со списком,
`__MODE__` = `references` / `implementation`, `__WAIT__`) сверяет встроенный поиск (`ReferencesSearch` / `DefinitionsScopedSearch`) с
`textDocument/references` / `implementation` сервера и печатает места, которые есть только у одного; `rename_solution.js` (`__ROOT__`,
`__FILE__`, `__AT__`, `__NEW__`, `__HIERARCHY__` = ответ на вопрос про иерархию) переименовывает именем из контекста (без inplace и
диалогов), печатает изменившиеся строки всех `.cs` и перечитывает документы с диска — Undo тут не годится: отмена правки нескольких файлов
спрашивает пользователя модальным диалогом, и робот висит; `line_markers.js` (`__FILE__`, `__WAIT__`) печатает иконки gutter.
В `sed`-подстановках — флаг `g`: плейсхолдер бывает в строке дважды. PID процесса — через PowerShell (`Get-Process`), `tasklist | grep` путает кодировка.

Работать на копии проекта (`build/ui-robot/debug-playground`, без `.idea`, `bin`, `obj`): каталог `.idea` у песочницы и у рабочей IDE общий,
вместе с точками останова и конфигурациями. Плагин в песочнице обновляется только перезапуском задачи. Лог песочницы —
`.intellijPlatform/sandbox/idea-dotnet-support/IU-*/log_runIdeForUiTests/idea.log` (дописывается между запусками), логи адаптера — рядом в `dotnet-debugger/`.

Ошибки компилятора по файлу на код (0.1.108–0.1.115): `tools/diag/check_errors.py ide debug-playground/Broken/Errors --host debug-playground/ShopApi`
сверяет подсветку плагина с пометками `// ERROR CSxxxx`, с `--source roslyn` — подсветку сервера (включает его на время прогона и выключает), без
`ide` — `roslyn` — с `dotnet build`. Файлы проверяются копиями внутри открытого проекта: файл вне проектов solution не проверяет ни плагин, ни сервер.
Сервер в IDE выдаёт ещё и ошибки-следствия в файлах с ошибками объявлений (CS0229, CS0121, CS1729), которых нет у `dotnet build`: пометки — по `dotnet build`.

## Замеры редактора (`baseline.py`)

Исходные замеры шага 0 `CSHARP_PSI_MIGRATION.md` (раздел «Исходные замеры»); тем же скриптом потом меряется путь `NATIVE`.

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2026.1.4\jbr"
./gradlew.bat runIdeForUiTests --no-daemon -ProbotPort=8591      # в фоне; свой порт, если соседний worktree тоже держит песочницу
export ROBOT_PORT=8591 NO_PROXY=127.0.0.1 PYTHONIOENCODING=utf-8
python tools/ui-robot/robot.py wait
python tools/ui-robot/baseline.py --target playground --runs 3 --reps 10 --cache cold
python tools/ui-robot/make-aspnetcore-sample.py                    # один раз: выборка aspnetcore (Mvc.Core, 536 файлов)
python tools/ui-robot/baseline.py --target aspnetcore --runs 3 --reps 10 --cache cold
```

Прогон: свежая копия цели в `build/baseline/runs/<цель>` (без `.idea`, `bin`, `obj`, затем `dotnet restore`) открывается в песочнице,
измеряемый файл — сразу, как только открыт проект; дальше — память после готовности сервера, completion (`.` после переменной и
префикс идентификатора, по `--reps` раз, на якорях `// TYPE:measure-*` — `debug-playground/Console/Editor/Measurements.cs`), сессия
набора (`measure-edit`), память ещё раз; проект закрывается. Времена берутся **внутри IDE** (`scripts/baseline_start.js` пишет отметки
`System.nanoTime` из своего потока, опрос снаружи на них не влияет): этапы открытия проекта и файла, фаза `RoslynWorkspace`
(`serverProcess`, `ready` = workspace loaded), первые ответы сервера `semanticTokens/full` и `textDocument/diagnostic` (по
`RoslynRequestStats`; запросы платформы во время warm-up плагина считаются тоже), окончание прохода демона по файлу, изменения числа
подсветок с цветом (идентификаторы) и проблем в редакторе. Память: heap IDE после трёх `System.gc()`, RSS (`WorkingSet64`) процесса IDE,
процесса сервера (`Microsoft.CodeAnalysis.LanguageServer.exe`) и всего его дерева. Completion: от набора символа (`TypedAction`, как с
клавиатуры, срабатывает автопопап) до первого показанного lookup с элементами и до lookup, в котором есть ожидаемый элемент; медиана и p90.
Вывод — таблица Markdown (медиана по прогонам и значения каждого), сырые данные — `build/baseline/<цель>-<время>.json`, там же состояние машины.

- `--cache cold` стирает кэш семантических токенов плагина (`system/dotnet-support/lsp-cache`) перед каждым прогоном, `warm` оставляет:
  путь копии постоянный, так что во втором прогоне файл раскрашивается из кэша до готовности сервера.
- Первый прогон после старта песочницы — на холодной JVM (загрузка классов), остальные — на прогретой: смотреть столбец значений.
- Робот выводит окно песочницы на передний план (автопопапу completion нужен фокус): **на время замера машину не трогать** — набранное
  человеком уходит в песочницу (так однажды открылся Settings, и прогон встал). Модальный диалог останавливает демон: скрипт закрывает
  quick tour и IDE Internal Errors, о любом другом пишет в вывод — такой прогон повторить.
- Песочница: `-Didea.is.internal=true`, `-Xmx2048m` — память IDE не равна памяти рабочей IDE пользователя; сравнивать с замерами
  `NATIVE` в той же песочнице.

## Что важно знать

- **Картинки — только компонентов IDE.** `/screenshot` сервера снимает весь рабочий стол вместе со всем, что на нём открыто; обёртка им
  намеренно не пользуется. `shot` просит компонент нарисовать себя (`isPaintingMode=true`), чужие окна в кадр не попадают. Следствие: диалоги и
  всплывающие окна — отдельные компоненты, их снимают отдельно (`shot out.png "//div[@class='MyDialog']"`), а подсказки (hover) так не снять.
- У Rhino робота своя копия gson: `com.google.gson.*`, созданные в скрипте, для IDE не `JsonElement` — передавать в API плагина обычные String / Map.
- Popup-списки (выбор реализации, варианты действия) закрываются, когда окно песочницы теряет фокус, — между командами робота; кликать в той же команде
  или проверять только содержимое списка.
- Диалоги, которым нужен настоящий фокус окна (Rename), роботом не открыть: проверять обработчики программно и просить пользователя посмотреть.
- Rhino: `let` / `const`, объявленные внутри вложенного блока с циклом, держали первое значение на всех итерациях (шесть «одинаковых» подсветок
  оказались шестью разными) — переменные циклов объявлять на верхнем уровне скрипта и сверять вывод вторым способом.
- Сценарии JS выполняются не на EDT; всё, что трогает UI или модель, обёрнуто в `invokeLater`. Команды возвращаются сразу — результата надо
  дождаться (`find` в цикле), а не считать, что он уже есть.
- Маршруты сервера: скрипты — `js/execute`, `js/retrieveAny`, для компонента — `{id}/js/execute` (без `/js` это маршрут для сериализованных
  лямбд Java-клиента); тексты компонента — `POST {id}/data`.
- Порт слушает только `127.0.0.1`, но даёт выполнять произвольный код внутри IDE: песочницу не оставлять запущенной без нужды.
- Скрипт `js`, который завис (робот перестал отвечать, `timeout` на каждой команде), — повод снять дамп потоков:
  `"<JBR>/bin/jstack.exe" <pid песочницы>` и искать `dotnetsupport` в стеках. Так 2026-09-30 нашёлся дедлок `DotNetSettings`
  (сервис ждал сам себя из `toString()` enum-а), из-за которого в песочнице не стартовал сервер Roslyn.

### Сверка встроенных фич с сервером (0.1.60)

- `choose_solution.js` (`__SOLUTION__`) — выбрать solution, если IDE спрашивает, и сказать, загрузил ли его сервер (`loaded: true`).
- `goto_batch.js` (`__FILE__`, `__CASES__` = `строка:имя#n;…`, `__WAIT__`) — Go to Declaration по списку мест: кто отвечает (дерево или
  сервер), цели дерева и куда реально ушла каретка (`файл:строка:колонка`, список попапа или `(stayed)`). Режим — `feature_source.js`.
- `complete_select.js` (`__FILE__`, `__LINE__`, `__TYPE__`, `__ITEM__`, `__SHOWN__`, `__FROM__`, `__TO__`, `__WAIT__`) — набрать, выбрать
  пункт completion (по lookup string или по началу видимого текста `__SHOWN__`), показать строки с `<caret>` и вернуть текст файла.
- `complete_at_line.js`: `__PRESENT__=yes` — видимый текст пункта в `[ ]`, когда он не равен lookup string (именованный аргумент
  `amount:`, `override`-члены сервера с пустой lookup string).
- `exit_ide.js` — закрыть песочницу (`ApplicationManager.getApplication().exit(true, true, false)`).
- Сервер отдаёт около 1000 пунктов, а список платформы обрезан реестром `ide.completion.variant.limit` (1000): для сравнения списков
  поднять его в песочнице (Registry) до 20000.
- После `setText` скрипта документ иногда расходится с PSI (completion отдаёт один пункт `aw`): `reload_file.js` по файлу.
