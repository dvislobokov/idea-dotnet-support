# UI-робот: проверка плагина в живой IDE

К сборке плагина не относится. Позволяет управлять отдельным экземпляром IDE (песочницей) снаружи — агенту или скрипту: открыть проект,
поставить точку останова, запустить отладку, нажать кнопку в диалоге, прочитать тексты компонентов, снять картинку окна.

Основа — [JetBrains Remote Robot](https://github.com/JetBrains/intellij-ui-test-robot): плагин `robot-server` внутри IDE открывает HTTP-порт
`127.0.0.1:8583` (только локально). Подключён задачей `runIdeForUiTests` в `build.gradle.kts`; `robot-server` — единственное, что сборка скачивает
(из репозитория плагинов JetBrains), и только для этой задачи.

## Запуск

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
./gradlew.bat runIdeForUiTests --no-daemon   # в фоне: задача живёт, пока открыта IDE; на экране появляется второе окно IDE
python tools/ui-robot/robot.py wait          # дождаться порта (около 40 с на холодный старт)
```

- `--no-daemon`: песочница — дочерний процесс того, кто выполняет задачу. На общем демоне Gradle её убивает любой `--stop` или сборка из
  соседнего проекта, которая уронила демон (так пропала песочница 2026-09-30).
- Если в окружении есть `HTTP_PROXY`, робот не ответит (`451` или сброс соединения — это прокси, а не IDE): перед командами
  `export NO_PROXY=127.0.0.1` (Python читает и `no_proxy`).
- Песочница при старте открывает последний проект — обычно сам `debug-playground` с общим `.idea`; открыть копию (`open`), а исходный
  закрыть через `ProjectUtil.closeAndDispose` из `js`. Закрывать исходный первым нельзя: без проектов IDE уходит на Welcome-экран.

Песочница — `.intellijPlatform/sandbox/idea-dotnet-support/IU-*`, свои настройки и свои проекты, рабочий экземпляр IDE не затрагивается. Лицензии в песочнице нет (в тулбаре
«Start Free Trial») — DAP-модуль и плагин при этом работают. Отладка идёт настоящим `dotnet` и установленным `dotnet-debugger`.

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
фильтром), `attach.js` (подключиться к PID, как Attach to Process), `sessions.js` (все отладочные сессии), `resume_all.js`, `show_settings.js`, `complete.js` (completion в поле Evaluate: текст → элементы списка).
Для языкового сервера: `editor_file.js` (файл выбранного редактора: путь, заголовок вкладки, можно ли править, баннеры, строка каретки),
`rename_via_server.js` (переименование как у LSP-клиента платформы, но без её inline-шаблона: `textDocument/rename` через клиент и применение
правки одной командой; `__AT__` / `__NAME__` / `__NEW__`), `inline_rename.js` (настоящий Shift+F6 с шаблоном — у робота ненадёжен, шаблону нужен фокус),
`lsp_timings.js` (строка состояния: загружен ли solution, сколько файлов раскрашено tokens из кэша; с `__TABLE__` = `yes` —
таблица времён запросов, как в меню .NET → Language Server Timings; опрашивать в цикле, чтобы увидеть, что происходит во время загрузки;
вывод `robot_js` срезает начало строк на `t` — `tDocument/…` это `textDocument/…`), `editor_action.js` (действие IDE с контекстом редактора — штатный `action` его не даёт, и Rename / Show Usages молчат),
`invoke_intention.js` (выполнить пункт Alt+Enter по тексту), `rename_handlers.js` (кому достанется Shift+F6), `ctrl_hover.js` (что видит Ctrl+наведение),
`lsp_command.js` (клиентская команда Roslyn, как клик по code lens), `lsp_capabilities.js` (capabilities платформы для `capture.py`), `lsp_policy.js` (кто красит и сворачивает: подсветки по ключам цвета, регионы folding и совпадающие диапазоны), `intentions.js`
(что предлагает Alt+Enter с кареткой в `__AT__`), `type_text.js` (набрать текст посимвольно и после каждого символа показать, есть ли popup completion, сколько в нём элементов и фазу completion
платформы — для жалоб вида «подсказка была только на первом слове»; автопопапу нужен фокус окна песочницы, первый прогон после старта бывает пустым),
`type_char.js` (набрать символ как с клавиатуры — срабатывают typed handlers и onTypeFormatting),
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
`hierarchy.js` (`__FILE__`, `__AT__`, `__ACTION__` = `TypeHierarchy` / `CallHierarchy` / `GotoSuperMethod`, `__WAIT__`) выполняет действие с
контекстом редактора и печатает, куда встала каретка и что в окне Hierarchy (дерево через `getUserObject` узлов пока печатается пусто —
смотреть `shot` компонента `RoslynTypeHierarchyBrowser` / `RoslynCallHierarchyBrowser` или `find` по нему: тексты строк в нём есть).
В `sed`-подстановках — флаг `g`: плейсхолдер бывает в строке дважды. PID процесса — через PowerShell (`Get-Process`), `tasklist | grep` путает кодировка.

Работать на копии проекта (`build/ui-robot/debug-playground`, без `.idea`, `bin`, `obj`): каталог `.idea` у песочницы и у рабочей IDE общий,
вместе с точками останова и конфигурациями. Плагин в песочнице обновляется только перезапуском задачи. Лог песочницы —
`.intellijPlatform/sandbox/idea-dotnet-support/IU-*/log_runIdeForUiTests/idea.log` (дописывается между запусками), логи адаптера — рядом в `dotnet-debugger/`.

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
