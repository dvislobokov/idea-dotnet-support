# UI-робот: проверка плагина в живой IDE

К сборке плагина не относится. Позволяет управлять отдельным экземпляром IDE (песочницей) снаружи — агенту или скрипту: открыть проект,
поставить точку останова, запустить отладку, нажать кнопку в диалоге, прочитать тексты компонентов, снять картинку окна.

Основа — [JetBrains Remote Robot](https://github.com/JetBrains/intellij-ui-test-robot): плагин `robot-server` внутри IDE открывает HTTP-порт
`127.0.0.1:8583` (только локально). Подключён задачей `runIdeForUiTests` в `build.gradle.kts`; `robot-server` — единственное, что сборка скачивает
(из репозитория плагинов JetBrains), и только для этой задачи.

## Запуск

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
./gradlew.bat runIdeForUiTests          # в фоне: задача живёт, пока открыта IDE; на экране появляется второе окно IDE
python tools/ui-robot/robot.py wait     # дождаться порта (около 40 с на холодный старт)
```

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
В `sed`-подстановках — флаг `g`: плейсхолдер бывает в строке дважды. PID процесса — через PowerShell (`Get-Process`), `tasklist | grep` путает кодировка.

Работать на копии проекта (`build/ui-robot/debug-playground`, без `.idea`, `bin`, `obj`): каталог `.idea` у песочницы и у рабочей IDE общий,
вместе с точками останова и конфигурациями. Плагин в песочнице обновляется только перезапуском задачи. Лог песочницы —
`.intellijPlatform/sandbox/idea-dotnet-support/IU-*/log_runIdeForUiTests/idea.log` (дописывается между запусками), логи адаптера — рядом в `dotnet-debugger/`.

## Что важно знать

- **Картинки — только компонентов IDE.** `/screenshot` сервера снимает весь рабочий стол вместе со всем, что на нём открыто; обёртка им
  намеренно не пользуется. `shot` просит компонент нарисовать себя (`isPaintingMode=true`), чужие окна в кадр не попадают. Следствие: диалоги и
  всплывающие окна — отдельные компоненты, их снимают отдельно (`shot out.png "//div[@class='MyDialog']"`), а подсказки (hover) так не снять.
- Сценарии JS выполняются не на EDT; всё, что трогает UI или модель, обёрнуто в `invokeLater`. Команды возвращаются сразу — результата надо
  дождаться (`find` в цикле), а не считать, что он уже есть.
- Маршруты сервера: скрипты — `js/execute`, `js/retrieveAny`, для компонента — `{id}/js/execute` (без `/js` это маршрут для сериализованных
  лямбд Java-клиента); тексты компонента — `POST {id}/data`.
- Порт слушает только `127.0.0.1`, но даёт выполнять произвольный код внутри IDE: песочницу не оставлять запущенной без нужды.
