# План устранения находок прогона 2026-10-08

Источник — `docs/ROBOT_REVIEW_2026-10-08.md`. Порядок — от самого простого и быстрого к самому сложному; оценка — чистое время
на правку с тестом, без прогона роботом. Каждый пункт — отдельная версия `0.1.x` с разделом в `CHANGELOG.md` (правило 2026-10-01),
кроме пунктов «инструменты», которые плагин не меняют.

| # | Что | Где | Оценка | Риск |
|---|---|---|---|---|
| 1 | `@string` среди предложений имени | `lang/CSharpVariableNames.kt:103` | 15 мин | нет |
| 2 | Текст ошибки сборки перед запуском — в журнал | `run/LaunchBuilds`, `run/BuildProjectBeforeRun.kt` | 20 мин | нет |
| 3 | Индексатор не запускать, если список сборок и их даты не менялись | `index/ImportCompletion.kt:205`, `index/IndexerTool` | 1 ч | низкий |
| 4 | Конфигурация по умолчанию — не `AspireHost` | `run/DotNetRunConfigurationGenerator.kt:68` | 1 ч | низкий |
| 5 | Страница Welcome — не на каждую версию | `welcome/WelcomePage.kt:49, 99` | 1 ч | нет |
| 6 | Счётчик Unit Tests Explorer | `testing/TestDiscovery.kt`, `TestExplorer.kt` | 1 ч | низкий |
| 7 | Контекстное меню проекта: два Rename, серые Cut/Paste, редкие пункты | `plugin.xml`, группа `DotNet.SolutionViewPopup` | 1–2 ч | низкий |
| 8 | Подсказки типа `var` там, где тип очевиден | `lang/NativeCSharpInlayHints.kt:233` | 2 ч | низкий |
| 9 | Ложная CS0121 на явной реализации интерфейса | `lang/semantic/CSharpOverloadChecks.kt` (`groups`), `CSharpNameResolver.kt:414` | 2–4 ч | средний |
| 10 | Find Usages: два вхождения в одной строке | `lang/NativeCSharpFindUsages.kt:72`, `CSharpUsageGrouping.kt` | 2–4 ч | средний |
| 11 | Имена элементов кортежа в отладчике | `debugger/DotNetDebugValues.kt` | 3–4 ч | средний |
| 12 | Файл проекта из незагруженного solution: сказать об этом | `index/ImportCompletion.kt`, баннер редактора | 2–3 ч | низкий |
| 13 | Серый текст ML после включения на лету | `ml/CSharpMlModels.kt`, провайдер inline | 2–8 ч | неизвестен |
| 14 | Файлы всех solution папки с библиотечной семантикой | `index/ImportCompletion.refresh`, `SolutionService` | 1–2 дня | высокий |
| И1 | Площадка не собирается в HEAD | `debug-playground` (3 файла) | 30 мин | — |
| И2 | Скрипты робота и README | `tools/ui-robot` | 1–2 ч | — |

## 1. `@string` среди предложений имени — 15 мин

`CSharpVariableNames.escaped` превращает имя-ключевое слово в `@string`. Для `StringBuilder` базовые слова дают `string`, `builder`,
`stringBuilder`; `@string` как имя переменной никто не пишет. Правка: имя, совпадающее с ключевым словом, выбрасывать, если есть другие
кандидаты, и экранировать только когда оно единственное (как Rider: `@string` не предлагает). Тест — существующий на `CSharpVariableNames`,
добавить случай `StringBuilder` → `builder`, `stringBuilder` без `@string`. Сценарий — `TYPE:complete-names` уже есть.

## 2. Текст ошибки сборки перед запуском — в журнал — 20 мин

В журнале только `Build Console.csproj: failed with 1 error (exit code 1)`, сам текст есть лишь в окне Build. В месте, где
`LaunchBuilds` получает исход сборки, писать в `PluginLog` первые 3 строки с `error` из вывода MSBuild (парсер `MsBuildOutputParser` их уже
выделяет). Без теста на UI; юнит-тест на формат строки.

## 3. Индексатор не запускать без изменений — 1 ч

`ImportCompletion.refresh` на каждое событие собирает список сборок всех проектов и вызывает `IndexerTool.index(all, …)`, который
запускает `dotnet AssemblyIndexer.dll` даже когда все 732 сборки уже проиндексированы (93 запуска за сеанс). Правка: перед запуском
сравнить ключ (отсортированные пути сборок + их `lastModified` + формат индекса) с ключом прошлого удачного запуска; совпал — вернуть
прошлый результат без процесса. Ключ хранить в памяти сервиса, сбрасывать при смене `dotnet`/TFM. Тест: `ImportCompletionTest` с
подсчётом вызовов фабрики процесса (подменить `IndexerTool` заглушкой) — два `refresh` подряд дают один запуск. Заодно `LOG.info`
«Index of assemblies» писать только при реальном запуске.

## 4. Конфигурация по умолчанию — 1 ч

Генератор выбирает первую созданную (`if (runManager.selectedConfiguration == null)`), а они идут по алфавиту — `AspireHost: https`.
Правка: после генерации выбирать по приоритету: проект, помеченный в `.sln` первым (`StartupProject` Rider берёт из `.sln`/`.slnx` —
первый `Project(...)` исполняемый), иначе `Microsoft.NET.Sdk.Web`/консольный `Exe` не-AppHost (`IsAspireHost`), иначе первый. Тест в
`RunConfigurationGeneratorTest`: solution с AppHost и Console → выбран Console. В `ROADMAP.md` упомянуть «стартовый проект».

## 5. Welcome — не на каждую версию — 1 ч

`isNewFor` сравнивает полную версию: при релизе на каждую фичу страница открывается после каждого обновления. Правка: открывать на
первой установке и при смене второго числа (`0.1` → `0.2`), на остальных обновлениях — уведомление «Updated to 0.1.149: What's New» с
ссылкой на страницу (раздел changelog уже есть в `<change-notes>`). Тест `WelcomePageTest.isNewFor` расширить.

## 6. Счётчик Unit Tests Explorer — 1 ч

`TestDiscovery` ищет методы с атрибутами по тексту: `[Theory]` с тремя `[InlineData]` — один метод, прогон — три теста; `[Fact(Skip=…)]`
тоже один. Два варианта: (а) честная подпись «10 test methods» вместо «10 tests» и после прогона — числа из `.trx` (`TrxParser` их
знает) рядом: «10 methods, 12 runs: 10 passed, 1 failed, 1 skipped»; (б) считать `InlineData`/`TestCase` атрибуты как отдельные
строки. (а) проще и честнее. Тест на подпись узла.

## 7. Контекстное меню проекта — 1–2 ч

В меню узла проекта 24 пункта: плагинные 20 плюс платформенные Cut/Copy/Paste (серые), `Analyze`, `Rename… Shift+F6` (рядом с
`Rename Project…`), `Refactor`, `Bookmarks`, `Delete`, `Repair IDE on File`. Правка в `plugin.xml`: серые Cut/Copy/Paste и платформенный
`Rename` для узлов solution/проекта убрать (`<remove-from-group>` или `update` с `isVisible = false` через свою обёртку), редкие
действия (`Run MSBuild Target`, `Analyze Upgrade`, `Calculate Code Metrics`, `Verify Formatting`, `Publish`) — в подменю `Tools`, как у
Rider: верхний уровень — Build/Rebuild/Clean, Run/Debug, Manage NuGet, Edit csproj, Properties, Add, Rename, Remove. Есть тест на
состав меню — обновить. Сверить с Rider по снимку.

## 8. Подсказки типа `var` там, где тип очевиден — 2 ч

`varHint` показывает тип всегда. Правило Rider («Hide hints for obvious types»): не показывать, когда инициализатор — `new T(...)` с
написанным типом, литерал (`"…"`, число, `true`), `default(T)`, `typeof`, приведение `(T)x`, член перечисления `Color.Green`, `nameof`.
Показывать для вызовов, LINQ, `await`, обращений к членам. Добавить переключатель на странице .NET («Hide type hints for obvious
initializers», по умолчанию включён) — вместе с ключом в обоих `DotNetBundle` и перегенерацией `docs/guide.html`. Тест
`CSharpInlayHintsTest`: `var when = new DateTime(…)` без подсказки, `var first = _orders.First()` — с.

## 9. Ложная CS0121 на явной реализации интерфейса — 2–4 ч

`IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();` — плагин сообщает неоднозначность между `GetEnumerator()` и
`IEnumerable.GetEnumerator()`. `CSharpNameResolver.isExplicitImplementation` уже исключает явные реализации из поиска по имени, но
(а) у stub-элементов она возвращает `false` («the stubs do not keep the interface»), (б) кандидаты для CS0121 в `CSharpOverloadChecks.groups`
собираются по-своему. Правка: в stub метода/свойства добавить бит «явная реализация» (`CSharpDeclarationIndex.VERSION` поднять), в
`groups` фильтровать по `isExplicitImplementation`. Пример в корпус — `src/test/resources/compilerMessages/cases/explicit-impl-probe.cs`
(класс с публичным и явным `GetEnumerator`, вызов из явной реализации и из `foreach`), оракул, `CompilerMessagesCoverageTest`:
`extra` должно остаться 0. Сценарий уже есть — `TYPE:implicit-enumerator`.

## 10. Find Usages: два вхождения в одной строке — 2–4 ч

`Counter + 1 + Counter.ToString().Length` даёт одно использование. Сначала понять, где теряется: `processUsages` (поиск ссылок)
или группировка `CSharpUsageGrouping` (схлопывание по строке). Проверка — тест в `CSharpFindUsagesTest` на строку с двумя вхождениями,
одно из которых приёмник вызова. Если теряется приёмник — поправить обход `CSharpMemberAccessExpression` в поиске; если группировка —
ключ группы должен включать смещение, не только строку. Ожидание маркера `find-usages-field` — 13, после правки прогнать роботом
`find_usages.js`.

## 11. Имена элементов кортежа в отладчике — 3–4 ч

Адаптер отдаёт `Item1`/`Item2`; имена `Id`/`Name` живут только в исходнике. В `DotNetDebugValues` при раскрытии значения типа
`System.ValueTuple<…>` найти объявление переменной в кадре (файл + строка кадра известны, `CSharpNameResolver.localType` даёт тип
локальной с именами элементов, `CSharpTypeDisplay` их печатает) и подставить имена детям; для вложенных кортежей — рекурсивно.
Без объявления (результат `Evaluate`) оставлять `Item1`. Тест на подстановку имён по `SemanticType`; сценарий `BP:variables`
(`tuple`), ожидание — `Id = 1`, `Name = "tuple"`.

## 12. Файл проекта из незагруженного solution — 2–3 ч

Папка с тремя solution: индекс строится только для выбранного, файлы других проектов остаются без библиотечной семантики и без
объяснения. Правка: баннер `EditorNotificationProvider` в `.cs` файле, чей проект не входит в загруженный solution: «This file belongs
to ShopApi.sln, which is not loaded: library members, completion and errors are off. [Switch to ShopApi.sln] [Don't show again]» —
переключение через уже существующий выбор solution (`RoslynWorkspace.solutionChosen`). Тест на условие показа. Полная поддержка —
п. 14.

## 13. Серый текст ML после включения на лету — 2–8 ч

После `inlineEnabled = true` + `reset()` модели загрузились, но на `i` серого текста нет при пороге 0.7. Сначала воспроизвести с
перезапуском IDE и с большой моделью (может быть просто порог); если воспроизводится — смотреть, что провайдер inline делает при
`enabled` в момент старта (регистрируется ли слушатель набора после переключения). Проверка роботом — `ghost_at_line.js` по
`TYPE:ml-if-header` после рестарта. Оценка неопределённая, поэтому в конце списка кода.

## 14. Файлы всех solution папки — 1–2 дня

Индексировать сборки всех solution папки (или всех `.csproj`, которые видит `SolutionService`), отдавать семантику по проекту-владельцу
файла (`DotNetProjects.findOwningProject`), а не по выбранному solution. Цена — память индексов и время первого запуска (ShopApi — ещё
~300 сборок) и расхождение с сервером Roslyn, который грузит один solution. Делать после п. 12 и только если несколько solution в папке
— реальный сценарий пользователей; иначе достаточно баннера.

## И1. Площадка не собирается в HEAD — 30 мин

`Console/Editor/CodeLens.cs:59` (`public override str`), `ShopApi/Controllers/CustomersController.cs:26`, `Web/Program.cs:25` — это
незаконченная работа пользователя, закоммичена в `c18131c` и вчера. Решить: либо дописать (`public override string Describe() => …`,
`id");` → `id);`, `;` после `LogError`), либо перенести недописанное в `Broken`. До этого чек-лист «файл компилируется как есть» и
отладочные сценарии не работают, `copy-playground.sh` берёт из HEAD только `CompletionRanking.cs`.

## И2. Скрипты робота и README — 1–2 ч

- README: «рабочий стол заблокирован (`Get-Process LogonUI`) — песочница Windows не показывает попапы, идти в WSL»;
  `test_configuration.js` принимает путь к `.csproj`, не имя; `robot_js` срезает первую `t` ответа — вывод не начинать с `t`.
- `complete_at_line.js` → заменить на `complete_poll.js` (опрос живого lookup, презентация строк) или починить снимок на показе.
- `popup_at.js` (ClassCastException в 2026.1.4), `rename_check.js` / `inline_rename.js` / `rename_solution.js` (`handler: none`) —
  починить или пометить как неработающие; rename проверять через `caret_at` + `screen.sh key shift+F6`.
- `screen_point.js` → использовать `hover_point.js` (координаты относительно рамки).
- `copy-playground.sh`: список файлов, берущихся из HEAD, вынести в одно место и дополнить по И1.
- Новые скрипты (`complete_poll.js`, `doc_at.js`, `inlays.js`, `show_tool_window.js`, `hover_point.js`, `all_highlights.js`) описать в
  таблице README.

## Порядок выпуска

0.1.149 — пп. 1–3 (мелочи и индексатор) · 0.1.150 — пп. 4–6 · 0.1.151 — п. 7 · 0.1.152 — п. 8 · 0.1.153 — п. 9 (с корпусом) ·
0.1.154 — п. 10 · 0.1.155 — п. 11 · 0.1.156 — п. 12 · далее 13–14 по результатам воспроизведения. И1 и И2 — без версии, вместе с первым выпуском.
