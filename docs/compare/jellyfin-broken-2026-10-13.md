# Поломанный Jellyfin: ошибки плагина против `dotnet build` (2026-10-13)

Плагин **0.1.176** (`ecc5998`), песочница `runIdeForUiTests`, анализ файла через робота (`tools/ui-robot/compare/file_analysis.js`).
Jellyfin — клон `build/compare/jellyfin` (`61f9a9d`). В нём сделано 49 поломок в 9 файлах 5 проектов (список — [`changes.md`](jellyfin-broken-dumps/changes.md),
дифф — [`breakages.diff`](jellyfin-broken-dumps/breakages.diff), скрипт — `break.py`), после замера клон откачен.

Как снят Roslyn: `dotnet build <проект> --no-dependencies -p:RunAnalyzers=false -p:TreatWarningsAsErrors=false` на каждый из пяти
проектов (зависимости — от чистой сборки, каскадов между проектами нет). csc не выдаёт семантических ошибок файла, в проекте которого есть
синтаксическая ошибка, поэтому `Jellyfin.Api` и `Emby.Server.Implementations` собраны второй раз без трёх синтаксических поломок, и
результаты объединены. Итог — [`dotnet-build.txt`](jellyfin-broken-dumps/dotnet-build.txt). Сверка — по (файл, строка, код CS), только
диагностики компилятора: CA / SA анализаторов плагин тоже показывает, но в сборке они выключены. Сырой вывод плагина —
[`plugin-raw.txt`](jellyfin-broken-dumps/plugin-raw.txt), сверка — [`compare.txt`](jellyfin-broken-dumps/compare.txt).

## Исправлено в 0.1.177

Все пункты раздела «Что чинить» сделаны; проверено стендом `JellyfinDiagnosticsHarnessTest` (настоящие исходники девяти проектов и
индексы сборок песочницы): на поломанном клоне плагин даёт все **45 из 45** ошибок Roslyn в тех же местах, лишних нет; на чистом
клоне — те же ошибки стенда, что и у 0.1.176 (артефакты: стенд берёт сборки всех версий из кэша, а проект ссылается на свои; новых нет).

Поправка к замеру: «4 × CS1503 extra» на `UserViewManager:191` — не ошибка плагина. Roslyn тоже сообщает CS1503 на всех пяти
аргументах (проверено оракулом на той же форме вызова), сверка по (файл, строка, код) схлопнула их в одну строку.

## Сводка

| Что | Число |
|---|---|
| Ошибок Roslyn (уникальных по файлу, строке, коду) | 45 |
| Из них плагин показал в том же месте с тем же кодом | **35** (78 %) |
| Плагин не показал (missing) | **10** |
| Предупреждений Roslyn (CS0162, CS8602, CS8618) | 6, все 6 совпали |
| Лишние ошибки плагина (extra) | **6 меток в 3 местах**: CS0029 + CS1503 после дубликата локала, 4 × CS1503 на одном вызове |
| Лишние предупреждения плагина | 2: CS0168 и CS0162 |
| Ложных ошибок в нетронутых местах файлов | **0** |
| Время до готовой подсветки файла | 0,6–3,2 с (проект уже был проиндексирован) |

Что работает: опечатки в именах (CS0103 / CS1061), лишние / недостающие аргументы методов (CS1501, CS7036 у экземплярных), CS1503,
CS0122, CS0026, CS0128, CS0139, CS0161, CS0165, CS0191, CS4032, синтаксис (CS1002, CS1026), и всё, что сделано в 0.1.176 по жалобе
пользователя: `enum` / `int` в условии `if`, `string` → `int?` в инициализаторе, `$"…"` → `int`, `long` → `int` (CS0266). Nullable:
CS8602 (два места) и CS8618 (на конструкторе и без конструктора, включая `static readonly`) — совпали.

## По поломкам

| № | Файл:строка | Поломка | Roslyn | Плагин |
|---|---|---|---|---|
| 1 | SearchController:106 | `Limit = searchTerm` в инициализаторе | CS0029 | ok |
| 2 | SearchController:120 | `isKidz` | CS0103 | ok |
| 3 | SearchController:127 | `new SearchHintResult(items)` без второго аргумента | CS7036 | **missing** |
| 4 | SearchController:159 | `if (item.MediaType)` | CS0029 | ok |
| 5 | SearchController:178 | `SeriesNam` | CS1061 | ok |
| 6 | SearchController:151 | `EndDate = item.Name` (string → DateTime?) | CS0029 | **missing** |
| 7 | SearchController:239 | пропущена `;` | CS1002 | ok |
| 8 | SearchController:215–216 | `GetItemById<string>` (`where T : BaseItem`) | CS0311 + CS1061 на 216 | CS1061 ok, CS0311 **missing** |
| 9 | UserController:98 | `Get(a, b, c)` без четвёртого аргумента | CS7036 | ok |
| 10 | UserController:142 | `return result.Name.Length;` в `ActionResult<UserDto>` | CS0029 | **missing** |
| 11 | UserController:161 | `NotFond()` | CS0103 | ok |
| 12 | UserController:201 | пропущена `)` | CS1026 | ok |
| 13 | UserController:111 | private `SearchController.GetSearchHintResult` | CS0122 | ok |
| 14 | DeviceManager:71 | `_capabilitiesMap[id] = deviceId` (string → класс, индексатор) | CS0029 | **missing** |
| 15 | DeviceManager:88 | `CustomName = deviceName.Length` | CS0029 + CS8602 | ok + ok |
| 16 | DeviceManager:98 | нет `return` в `async Task<Device>` | CS0161 | ok |
| 17 | DeviceManager:125 | `return new(1);` (target-typed) | CS1729 | **missing** |
| 18 | DeviceManager:154 | `int count = devices;` (IEnumerable → int) | CS0029 | **missing** |
| 19 | DeviceManager:177 | `ToDeviceInfo(device, 5)` | CS1503 | ok |
| 20 | DeviceManager:242 | `if (PermissionKind.EnableAllDevices)` | CS0029 | ok |
| 21 | DeviceManager:264 | `DateLastActivity = DateOnly.FromDateTime(…)` (DateOnly → DateTime?) | CS0029 | **missing** |
| 22 | DeviceManager:274 | `Id = $"{options.Id}"` | CS0029 | ok |
| 23–24 | DeviceManager:138–139 | `await` в не-async; `device.AppName` после `FirstOrDefault` | CS4032, CS8602 | ok, ok |
| 25 | VideoResolver:56 | `bool isStub = 0;` | CS0029 | ok |
| 26 | VideoResolver:85 | `int? year = "";` | CS0029 | ok |
| 27 | VideoResolver:91 | `Yeer` | CS1061 | ok |
| 28 | VideoResolver:158 | `CleanDateTimeParser.Clean(name)` без второго аргумента (статический метод) | CS7036 | **missing** |
| 29 | VideoResolver:147 | `this` в статическом методе | CS0026 | ok |
| 30 | VideoResolver:134 | код после `return false;` | CS0162 (warning) | ok |
| 31 | UserManager:156 | `IsNullOrWhiteSpace(name, true)` | CS1501 | ok |
| 32 | UserManager:201 | `Username = userId` (Guid → string) | CS0029 | ok |
| 33 | UserManager:344 | второй `newUser` в той же области | CS0128 | ok, **но extra** CS0168 :343, CS0029 :360, CS1503 :362 |
| 34 | UserManager:431 | `attempts++` неприсвоенной | CS0165 | ok |
| 35 | UserManager:422 | `break;` вне цикла | CS0139 | ok, **но extra** CS0162 :423 |
| 36 | UserManager:459 | `_eventManager = null!;` | CS0191 | ok |
| 37 | UserViewManager:102 | `if (parents.Count)` | CS0029 | ok |
| 38 | UserViewManager:93 | `list.Add(folder.Name)` | CS1503 | ok |
| 39 | UserViewManager:189–191 | `Guid uniqueId = parentId + "subview" + type;` | CS0029 :189 + CS1503 (аргумент 1) :191 | CS0029 **missing**; на 191 — CS1503 на **всех пяти** аргументах (4 extra) |
| 40 | UserViewManager:97 | `CollectionTyp` | CS0103 | ok |
| 41 | UserViewManager:177 | пропущена `)` | CS1026 | ok |
| 42 | UserViewManager:196 | `string name = null;` под `#nullable disable` | — | — (верно молчит) |
| 43 | StreamInfo:616 | `int totalSeconds = RunTimeTicks.Value;` | CS0266 | ok |
| 44 | StreamInfo:639 | `TargetVideoStreem` | CS0103 | ok |
| 45 | StreamInfo:659 | `IsDirectStream.Value` | CS1061 | ok |
| 46 | StreamInfo:683 | `return "false";` в `bool?` | CS0029 | ok |
| 47 | SearchHint:18 | не-nullable свойство, конструктор его не задаёт | CS8618 на конструкторе | ok |
| 48–49 | UpdateUserItemDataDto:16, 18 | свойство и `static readonly` поле, конструктора нет | CS8618 × 2 | ok |

## Что чинить (по важности; extra хуже missing)

1. **Extra: дубликат локала уводит имя на второе объявление** (UserManager:343–362). После `User newUser; string newUser = name;`
   Roslyn привязывает все использования к первому объявлению и говорит только CS0128. Плагин привязывает к второму (`string`) и даёт
   ложные CS0029 на `newUser = await CreateUserInternalAsync(…)`, CS1503 на `new UserCreatedEventArgs(newUser)` и CS0168 на первом.
   Исправление: при CS0128 имя резолвится в первое объявление области (и CS0168 на первое не ставится).
2. **Extra: CS1503 на всех аргументах после выбора не той перегрузки** (UserViewManager:191, `GetNamedView(name, parentId, type,
   sortName, uniqueId)`, где `uniqueId` стал `Guid`). Roslyn сообщает CS1503 только на первом несовпавшем аргументе лучшего кандидата
   (аргумент 1 → `User`), плагин — на пяти. Исправление: как Roslyn, одно сообщение на первый аргумент, который не конвертируется (или
   выбор кандидата по правилам «лучшей» перегрузки при отсутствии подходящей).
3. **Extra: CS0162 после ошибочного `break`** (UserManager:423). `break` вне цикла — ошибка CS0139, Roslyn не считает, что он обрывает поток,
   и CS0162 на следующем операторе не даёт. Исправление: `break` / `continue` без цикла не обрывают поток.
4. **Missing: преобразования, которые 0.1.176 ещё не покрыл** (5 мест):
   - в nullable-структуру: `string` → `DateTime?` (SearchController:151), `DateOnly` → `DateTime?` (DeviceManager:264) — правило
     «структура без операторов» сейчас пропускает nullable-цель;
   - из `string` в структуру без операторов: `string` → `Guid` (UserViewManager:189) — `string` исключён из «простых» источников;
   - из интерфейса в число: `IEnumerable<Device>` → `int` (DeviceManager:154);
   - в класс через индексатор: `_capabilitiesMap[id] = deviceId` (DeviceManager:71) — `checkAssignment` смотрит только имя / член слева,
     не `a[i]`; и цель-класс (`ClientCapabilities`) не проверяется вообще.
5. **Missing: CS7036 у статического метода и у конструктора** (VideoResolver:158 `CleanDateTimeParser.Clean(name)`, SearchController:127
   `new SearchHintResult(items)`). У экземплярных методов CS7036 есть (UserController:98) — значит, не доходит проверка статического вызова
   через имя типа и `new` типа из другого проекта solution.
6. **Missing: CS1729 у target-typed `new(…)`** (DeviceManager:125, `return new(1);` при `ClientCapabilities`): `new T(…)` проверяется,
   `new(…)` — нет.
7. **Missing: CS0311** — нарушение ограничения `where T : BaseItem` у аргумента-типа метода (`GetItemById<string>`, SearchController:215).
8. **Missing: CS0029 в `ActionResult<T>`** (UserController:142, `int` → `ActionResult<UserDto>`). У `ActionResult<T>` есть неявные
   операторы из `T` и `ActionResult`, поэтому проверка осторожно молчит; Roslyn знает, что из `int` ни один не подходит. Нужна проверка
   пользовательских операторов по типам их параметров, а не «есть операторы — молчим».

Отдельно (не компилятор): плагин показывает диагностики анализаторов Jellyfin (CA2007, SA1122, SA1201, SA1623) с severity ERROR — так в
проекте и настроено (`TreatWarningsAsErrors` + `AnalysisMode=AllEnabledByDefault`), это не ложные ошибки. Одно предупреждение
`Redundant character escape '\ ' in RegExp` (UserManager:119) — от инспекции RegExp платформы, к C# не относится.
