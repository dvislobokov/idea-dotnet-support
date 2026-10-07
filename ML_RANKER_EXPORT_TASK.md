# Задание e18: экспорт реальных списков completion из плагина C# → ранкер на реальных данных

Написано 2026-10-07. Это плагинная половина эксперимента e18 движка (https://github.com/dvislobokov/idea-ml-completion);
вторая половина (обучение `ml-train l1`, оценка, CHANGELOG) делается в движке за минуты, как только появятся данные.
Почему это нужно: ранкер, обученный на синтетических списках (`ml-models/csharp/e15-a-rank.cml`), на реальных списках
плагина не работает (на Go такой ранкер дал MRR 0.518 против 0.527 у правил плагина; ранкер на реальных списках — 0.808).

## 0. Что уже есть

- Готовый образец для Go — `../idea-golang-support` (ветка `migration`):
  - `go-psi-ide/src/test/kotlin/io/github/golangsupport/ml/GoMlDatasetExport.kt` — headless-экспорт (test-scope main,
    Gradle-таск `:go-psi-ide:mlDataset`): открывает репозитории как проекты, в выбранных файлах ставит каретку в выбранные
    позиции, вызывает **настоящее** completion плагина, записывает список кандидатов с признаками и ответ;
  - `go-psi-ide/src/main/kotlin/io/github/golangsupport/ml/GoMlFeatures.kt` — языковой блок признаков (17 штук: вид кандидата
    one-hot, уровень области видимости, нужен ли импорт, совпадение с ожидаемым типом, объявлен ли в файле, расстояние до
    объявления, ранг по правилам плагина, …) поверх 13 общих признаков `ml-core` (`FeatureSchema.BASE`: n-gram log-prob,
    частота/давность в файле, совпадение префикса, …);
  - `GoMlCompletionRanker.kt` — как тот же блок признаков считается в IDE при ранжировании (экспорт и IDE обязаны считать
    признаки одним кодом — иначе ранкер обучится не на том, что увидит).
- Контракт: `../idea-ml-completion/docs/ADAPTER.md` (§3 «Offline generator», §4 «Weigher»), формат шардов примеров —
  `ml-core` `ExampleShards` (писатель есть в `ml-core`, читает `ml-train l1 --shards`).
- Рецепт headless-запуска на сервере и два фикса, без которых экспорт падал: `../idea-ml-completion/tools/psi/GO-HEADLESS-EXPORT.md`,
  `go-plugin-headless-export.patch` (уникальный temp-каталог на репозиторий, try/catch по репозиторию,
  `-Dintellij.testFramework.rethrow.logged.errors=false`), `run-export.sh` / `launch-dataset.sh` (10 JVM параллельно).
- `ml-core` уже в этом репозитории (`include(":ml-core")`), n-gram модель для признака `lm_logprob` — `ml-models/csharp/e15-a.cml`.
- Корпус C# на сервере: `~/work/ml-data/csharp/repos/<owner>__<repo>/` (38 150 репозиториев), манифест с фолдами
  `~/work/ml-data/csharp/prepared/manifest.jsonl` (поле `fold`: lm / rank / test; экспортировать из **rank** и **test**,
  lm не трогать — на нём обучена n-грамма).

## 1. Что сделать в плагине

1. **`CSharpMlFeatures`** (`csharp-psi-ide`, пакет `io.github.dotnetsupport.ml`): языковой блок признаков по образцу
   Go, из того, что знает completion плагина о кандидате: вид (local / parameter / field / property / method / type /
   namespace / keyword / …, one-hot), статический ли член, уровень видимости/область (локальная → член типа → базовый тип →
   using-импорт → неимпортированный), нужен ли `using`, совпадение с ожидаемым типом (0 / assignable / identical), есть ли
   ожидаемый тип у списка, объявлен в файле, расстояние до объявления, ранг по правилам плагина, после точки ли позиция.
   Имена и порядок признаков — константы, одинаковые для экспорта и IDE.
2. **`CSharpMlDatasetExport`** (test-scope, Gradle-таск `:csharp-psi-ide:mlDataset`) по образцу `GoMlDatasetExport`:
   - вход: каталог репозиториев, список репозиториев (файл), выходной каталог, лимиты;
   - выборка как в e17: ≤ 120 файлов на репозиторий (по алфавиту, не тесты, 200 B–400 KB, generated пропускать),
     10 позиций на файл, префикс 0–2 символа уже набранного идентификатора, ≤ 100 кандидатов (ответ — идентификатор,
     который стоит в файле на этой позиции, — обязательно сохраняется, даже если плагин его не предложил: это recall);
   - на каждой позиции: вызвать completion плагина так, как его вызывает IDE (тот же contributor, тот же порядок), для
     каждого кандидата посчитать языковые признаки + общие признаки через `FeatureExtractor` из `ml-core` с n-граммой
     `e15-a.cml` (кэш λ = 0.3), записать `ExampleShards` + имена кандидатов + вид контекста (после точки, начало
     statement, аргумент, тип, правая часть присваивания, прочее);
   - устойчивость: уникальный temp-каталог на репозиторий, try/catch вокруг репозитория (один сломанный проект не должен
     ронять экспорт на 300), флаг `rethrow.logged.errors=false`;
   - сводка в конце: репозиториев, файлов, позиций, доля позиций со списком, recall (ответ в списке), среднее число кандидатов.
3. **Единый код признаков в IDE**: `CSharpMlCompletionRanker` (weigher по §4 контракта) использует тот же `CSharpMlFeatures`;
   в это задание входит минимум — класс признаков + экспорт; сам weigher можно подключить после обучения ранкера.
4. **Тест**: на маленьком фикстурном проекте экспорт даёт шард с ожидаемым числом позиций, признаки в диапазонах, ответ
   присутствует.

## 2. Что получит движок и что будет дальше

Два каталога шардов (`psi/rank`, `psi/test`, по 300 и 295 репозиториев, ~200 k и ~145 k позиций — так было в Go, 26 минут
на 10 JVM). Дальше в движке: `ml-train l1 --lang csharp --shards psi/rank --test-shards psi/test` (минута),
оценка MRR / top-1 против порядка плагина и против LM-only, CHANGELOG e18, `csharp/models/e18-rank.cml` → в `ml-models/csharp/`.

## 3. Подводные камни (из Go-опыта)

- Третьесторонние типы без NuGet-пакетов не резолвятся — их члены в списке не появятся (в Go это 11 % recall). Это нормально
  для первой версии; измерить и записать в сводке.
- Экспорт однопоточный на JVM — параллелить процессами (скрипт `run-export.sh` в движке), 6 GB heap на JVM.
- Headless test-editor просит шрифт (на сервере стоит fontconfig + DejaVu); IDEA Community 2026.1.4 на сервере — `/root/work/idea`,
  JBR как `JAVA_HOME`, `-PlocalIdePath=/root/work/idea`. Полная сборка Go-плагина на сервере не проходит (Ultimate-классы);
  .NET-плагин собирается.
- Прогресс/перезапуск: писать по одному шарду на репозиторий, пропускать уже готовые — экспорт должен быть возобновляемым.

## 4. Чего не делать

Не менять `ml-core/` здесь (только sync из движка); не придумывать свой формат — только `ExampleShards`; не считать
признаки в экспорте иначе, чем в IDE.

## 5. Статус (2026-10-07, 0.1.130)

Сделано в плагине:

- `csharp-psi-ide/src/main/kotlin/io/github/dotnetsupport/ml/CSharpMlFeatures.kt` — `CSharpMlLanguage` (`MlLanguage by CSharpLanguage`,
  `rankFeatures`), `CSharpMlCandidateKind`, `CSharpMlScope` (0 local … 5 unimported), `CSharpMlCandidate` (без типов ml-core и платформы),
  `CSharpMlFeatures` — 19 признаков языкового блока (`NAMES`, `schema`, `languageBlock(caret, afterDot, candidates)`, `contextKind`, `isAfterDot`).
- `src/main/kotlin/io/github/dotnetsupport/lang/NativeCSharpMlInfo.kt` — ключ `CSharpMlCandidate` на lookup-элементе, `attach` / `infoOf` /
  `fallback` / `candidateOf`. Инфо вешают `NativeCSharpCompletion.Builder` (locals, члены, типы, ключевые слова, предопределённые типы — TYPE,
  имена переменных), `NativeCSharpMemberCompletion` (после точки, scope RECEIVER, объявление для членов решения), `NativeCSharpImportCompletion`
  (`needs_using`, scope UNIMPORTED), `NativeCSharpExpectedCompletion.prioritized` (`expected_type_match = 2`). `expected_type_match` = 2 там, где
  правила плагина дают бонус `EXPECTED_TYPE` (`CSharpTypeNames.matches`), 1 («assignable») плагин не знает — всегда 0 или 2. Смещение объявления —
  только когда объявление в дополняемом файле (как в Go: чужой файл со стабов не грузится).
- Экспорт — **test-scope основной части**, не `csharp-psi-ide`: completion плагина живёт в основном модуле (`lang/NativeCSharp*`), а
  `csharp-psi-ide` его не видит (сборка композитная, зависимость только в одну сторону). `src/test/kotlin/io/github/dotnetsupport/ml/CSharpMlExporter.kt`
  (репозиторий → временный content root, выборка файлов и позиций, вызов `completeBasic()`, признаки, запись) и `CSharpMlDatasetExport.kt`
  (параметры, цикл по репозиториям, шард на репозиторий, пропуск готовых, try/catch, сводка). Выборка: ≤ 120 файлов по алфавиту, не тесты
  (каталоги `test*`, `*.Tests`, файлы `*Tests.cs` / `*Test.cs`), 200 B–400 KB, не generated (`<auto-generated`, `.g.cs`, `.Designer.cs`,
  `.generated.cs`, `AssemblyInfo.cs`), `bin`/`obj`/`packages`/`node_modules`/скрытые каталоги пропускаются; 10 позиций на файл, префикс 0/1/2
  (50/30/20 %), ≤ 100 кандидатов с сохранением ответа, дубликаты по lookup string убираются. Ответа нет в списке → позиция считается
  (`answer-missing`) и пропускается — это recall плагина.
- Gradle: `./gradlew.bat mlDataset -Pml.repos=<файл со списком> -Pml.lm=ml-models/csharp/e15-a.cml -Pml.data=<корень с repos/> -Pml.out=<каталог шардов>`
  `[-Pml.perFile=10 -Pml.maxFiles=120 -Pml.cache=0.3 -Pml.names=false -Pml.seed=7 -Pml.heap=6g]`; задача ставит
  `-Dintellij.testFramework.rethrow.logged.errors=false` и `-Didea.max.intellisense.filesize=20000`. На сервере — с `-PlocalIdePath=/root/work/idea`.
  Параллелить процессами по разным спискам репозиториев и разным `-Pml.out` (или одному: готовые шарды пропускаются, но два процесса на один
  репозиторий одновременно не ставить).
- Тест `CSharpMlDatasetExportTest`: экспорт на фикстурном репозитории (2 файла в деле, тесты / generated / `bin` пропущены, схема и язык шарда,
  ответ в каждом списке, признаки конечны и в диапазоне, one-hot ≤ 1, есть списки после точки с `AFTER_DOT`, `order.Total` дополняется из
  другого файла), выборка файлов, языковой блок на ручных кандидатах, `contextKind`, инфо на элементах native-completion (local / parameter /
  field / static property / method / keyword / type / predefined type, члены после точки — RECEIVER, строки ожидаемого типа — 2).

Не сделано / дальше:

- Weigher `CSharpMlCompletionRanker` (§4 контракта) — после того как движок обучит `e18-rank.cml`; сервис моделей — по `ML_INLINE_TASK.md`.
- Тест паритета weigher ↔ экспорт (обязателен по контракту) — вместе с weigher.
- Члены библиотечных типов в экспорте есть только при индексе сборок (`CSharpSemanticEnvironment`): в headless-прогоне без собранных проектов
  списки после точки у `string` / `List<T>` пустые или только из решения — измерить по `answer-missing` в сводке; индекс .NET runtime на сервере —
  отдельная работа.


## 6. Замечания с первого прогона на сервере (2026-10-07, 16 воркеров × git worktree, `-Pml.maxFiles=60 -Pml.names=true`)

- Скорость: 75–900 мс на позицию в зависимости от репозитория (медиана ~300), один репозиторий — 3–15 минут; на весь rank-набор
  (300 репо) уходит несколько часов даже в 16 процессов. Основная цена — не completion, а копирование и индексация всего репозитория
  ради 60 файлов. Идея на потом: ограничить копируемые файлы (например, ≤ 1500 `.cs` + все `.csproj`), чтобы большие репо не стоили 10+ минут.
- Падение репозитория целиком: `java.lang.AssertionError: Wrong line separators: '...\u0000...'` в `Document.setText` — в корпусе есть файлы
  с одиночным `\r` и с `\u0000`. Экспортёр нормализует только `\r\n`; надо нормализовать и одиночный `\r` → `\n` и пропускать файлы с NUL
  (или заменять), иначе один такой файл роняет весь репозиторий (try/catch его ловит, но репозиторий потерян).
- Параллельный запуск: 8 одновременных компиляций Kotlin в разных worktree падают с «Not enough memory to run compilation» — компилировать
  worktree по очереди (`~/work/cs-export-launch.sh` на сервере), потом стартовать воркеры.
- Сводки `ml: …` при `-q` не попадают в консоль — только в `build/test-results/mlDataset/*.xml` после окончания задачи; для живого
  прогресса удобнее писать их ещё и в файл `-Pml.out/progress.log` (по строке на репозиторий).


## 7. Ускорение и полнота headless-экспорта (2026-10-07 вечер, замеры на сервере)

Три изменения, каждое измерено на одном репозитории rank-fold'а (`vpenades__SharpGLTF`: 387 `.cs`, 24 проекта, 25 PackageReference,
net8/net10/netstandard/net471), `-Pml.maxFiles=60 -Pml.maxCopy=400 -Pml.names=true -Pml.seed=7`, один JVM, сервер под нагрузкой
16 воркеров основного экспорта (LA ≈ 21 из 32). Все цифры — строка `ml: TOTAL` из `build/test-results/mlDataset/*.xml`; wall — вся
Gradle-задача без демона (компиляция не считается, классы собраны заранее).

| конфигурация | wall / тело теста | мс/позиция | позиций | списков | recall | no-list | answer-missing | канд./список | после `.`: список / ответ в нём |
|---|---|---|---|---|---|---|---|---|---|
| baseline (0.1.130) | 316 с / 223 с | 334 | 599 | 241 | 0.707 | 258 | 100 | 56.2 | 128 / 117 из 211 |
| a) общий system/config (`-Pml.sandbox`), холодный / тёплый | 314 / 312 с | 335 / 340 | 599 | 241 | 0.707 | 258 | 100 | 56.2 | 128 / 117 |
| a+b) + `dotnet restore` + индекс сборок (`-Pml.restore=true -Pml.projects=…`), холодный / тёплый | 318 / 314 с | 340 / 330 | 599 | **343** | **0.807** | **174** | **82** | 51.7 | **206 / 206** |
| a+b+c₁) + снимок имён типов **на файл** + debug-лог теста выключен | 247 с / 146 с | 134 | 599 | 343 | 0.807 | 174 | 82 | 51.7 | 206 / 206 |
| a+b+c₂) снимок **на репозиторий** (итог, 0.1.131) | **76 с / ~30 с** | **45** | 599 | 344 | 0.808 | 173 | 82 | 52.1 | 207 / 207 |
| (справочно) a+b + только выключенный debug-лог, без снимка | 318 с | 294 | 599 | 343 | 0.807 | 174 | 82 | 51.7 | 206 / 206 |

Шарды a+b и a+b+c₁ побайтно одинаковы; c₂ отличается одним списком из 599 (снимок не включает типы дополняемого файла — они идут из PSI).
restore: 9 с холодный / 3–7 с тёплый кэш NuGet; индекс 206 сборок: 10 с при первом запуске (сборка индексатора + индекс всех dll), 0–1 с
потом — оба живут в `-Pml.helpers`. Разница wall − тело теста ≈ 45–90 с — Gradle без демона + старт IDE + tearDown.

Подтверждение на двух других репозиториях rank-fold'а (не из `sets/rank.txt`), те же настройки; «baseline» здесь = тот же код с
`-Pml.restore=false -Pml.snapshot=false` (debug-лог уже выключен, т.е. baseline чуть лучше 0.1.130):

| репозиторий | конфигурация | мс/позиция | позиций | списков | recall | no-list | answer-missing | после `.`: список / ответ |
|---|---|---|---|---|---|---|---|---|
| `jjrdk__ArchiMetrics` (4 проекта, 39 сборок) | baseline | 264 | 590 | 114 | 0.487 | 356 | 120 | 1 / 0 из 148 |
| | итог | **35** | 590 | **182** | **0.659** | 314 | 94 | **40 / 33** |
| `gishys__Hx.Workflow` (9 проектов, 502 сборки) | baseline | 2114 | 594 | 39 | 0.206 | 405 | 150 | 17 / 17 из 199 |
| | итог | **38** | 594 | **193** | **0.687** | 313 | 88 | **108 / 108** |

Оба репозитория вместе: baseline 1569 с wall, итог **102 с** (restore 1–2 с, индекс 0 с на тёплом кэше, снимок 0–3 с). У `Hx.Workflow` с
большим числом типов baseline — 2 с на позицию: скан стаб-индекса на каждую позицию растёт с размером решения, снимок это снимает.
`ArchiMetrics` после точки по-прежнему слаб (40 из 148): это Roslyn-анализатор, большая часть точек — на типах `Microsoft.CodeAnalysis`;
restore прошёл, но 39 сборок — видимо, старые target'ы; не разбирался.

Что оказалось не так, как думали:

- Общий system-каталог сам по себе (a) ничего не даёт: репозиторий каждый раз копируется в новый tmp-каталог, его стабы индексируются
  заново. Он нужен как место для индексатора и индексов сборок (b), и только там экономит (10 с → 0–1 с на репозиторий).
- Корпус `csharp/repos` не содержит `.csproj`/`.sln` вообще (снимки — только `*.cs` + LICENSE/README), так что `dotnet restore` без
  дополнительных файлов невозможен. `tools/ml-dataset/fetch-projects.sh <out> <owner__repo>…` качает tarball с codeload и оставляет
  только MSBuild-файлы (`*.csproj *.sln *.slnx *.props *.targets *.config global.json`) в `<out>/<owner__repo>/…`; экспортёр накладывает
  их поверх копии (`-Pml.projects=<out>`). Репозиторий без проектов получает синтетический `__ml_export.csproj` (net10.0, все `.cs` дерева) —
  тогда хотя бы BCL (`string`, `List<T>`) резолвится. SDK сам докачивает `Microsoft.NETFramework.ReferenceAssemblies.*` для net4x-проектов и
  `microsoft.netcore.app.ref` старых версий, плагин их видит через assets.
- Снимок `NativeCSharpTypeNames.Snapshot` в первой версии не работал (343 мс): completion идёт в копии файла, `file.viewProvider.virtualFile`
  там — LightVirtualFile, снимок с ключом по оригиналу не находился. Ключ по `file.originalFile` — 134 мс.
- Снимок на файл (полный проход по всем ключам стаб-индекса на каждый из 60 файлов) не попадал в «мс/позицию», но на `Hx.Workflow` стоил
  ~15 мин на репозиторий. Теперь он читается один раз на репозиторий (1–3 с) и при переходе к следующему файлу перечитываются только имена,
  объявленные в предыдущем файле (его стабы перестроены правками) и в новом; типы дополняемого файла в снимок не входят (их даёт PSI, а их
  стабы в снимке протухают после первой правки — `PsiInvalidElementAccessException: … onContentReload` в первой версии на `ArchiMetrics`).
- Главная статья времени после этого — не поиск в индексе, а **debug-лог тестового фреймворка**: `TestLoggerFactory` буферизует каждую
  debug-запись с форматированием даты (`String.format` → `Calendar`), а `FileBasedIndexImpl.runIfHaveNewUpdatesFor` пишет такую запись на
  каждый запрос к индексу после изменения документа — в профиле ~40 % времени completion-потока. `ApplicationManagerEx.setInStressTest(true)`
  в `setUp` выключает debug-уровень у `TestLogger` (в IDE debug и так выключен).
- EDT в экспорте в основном ждёт (`Unsafe.park`): сама completion бежит в пуле, профилировать надо не EDT, а `DefaultDispatcher-worker` с
  `fillCompletionVariants` в стеке; JFR с глубиной стека по умолчанию (64) режет нижние кадры — удобнее `jstack` раз в 1–2 с.
- Между двумя запусками одного и того же кода шард может отличаться в одном примере (base vs a): есть небольшая недетерминированность
  списков, не разбирался.

Рецепт для следующего полного экспорта (воркер `n`):

```sh
# один раз: MSBuild-файлы всех репозиториев набора (сеть, ~1–5 с на репозиторий, overlay вне корпуса)
tools/ml-dataset/fetch-projects.sh ~/work/ml-data/csharp/projects $(cat ~/work/ml-data/csharp/psi/sets/rank.txt)
# воркер: свой system-каталог (индексы IDE нельзя делить между процессами), общий каталог индексатора/индексов сборок
HOME=/root bash gradlew -q --no-daemon mlDataset -PlocalIdePath=/root/work/idea -Pml.repos=… -Pml.lm=… -Pml.data=… -Pml.out=… \
  -Pml.maxFiles=60 -Pml.maxCopy=400 -Pml.names=true -Pml.heap=6g \
  -Pml.sandbox=/root/work/dotnet-psi/w$n -Pml.helpers=/root/work/dotnet-psi/dotnet-support \
  -Pml.restore=true -Pml.projects=/root/work/ml-data/csharp/projects -Pml.restoreTimeout=300   # -Pml.snapshot=true по умолчанию
```

`HOME` обязателен под systemd (кэш NuGet `~/.nuget/packages`, общий для воркеров — NuGet сам блокирует). Свойства: `ml.restore` (restore +
индекс сборок, по умолчанию false), `ml.restoreTimeout` (секунд на репозиторий, 300; решения верхних двух уровней, иначе до 40 проектов),
`ml.projects` (overlay), `ml.snapshot` (true), `ml.sandbox` / `ml.helpers` (только Gradle: `-Didea.system.path`, `-Didea.config.path`,
`-Didea.log.path`, `-Ddotnet.support.root`). В сводке `ml: …` добавлены `after-dot=N listed=M found=K` (позиции после точки / из них со
списком / с ответом в нём), `projects= restored= assemblies=` и время `copy= restore= index= indexing= snapshot=`. Схема признаков не
менялась — шарды совместимы с текущим экспортом. Прикидка: 300 репозиториев rank-набора ≈ 300 × (~30 с экспорт + ~45 с Gradle/IDE на
процесс, но процесс один на список) — порядка 3–4 часов в один процесс против ~8 часов в 16.

Код: `CSharpMlExporter` (overlay, restore, `AssemblyIndexService.refreshForTests`, снимок на репозиторий, статистика после точки),
`CSharpMlDatasetExport` (свойства, stress-test режим), `AssemblyIndexService.refreshForTests` (синхронный refresh для заданных проектов —
в unit-test режиме `schedule()` не работает, а `SolutionService` ищет решения только в каталоге проекта IDE), `DotNetHelper.root()`
(`-Ddotnet.support.root`), `NativeCSharpTypeNames.Snapshot` + `NativeCSharpResolver.stubParts` (test-only снимок; в IDE путь прежний:
снимок никогда не установлен), `build.gradle.kts` (`ml.sandbox`, `ml.helpers`), `tools/ml-dataset/fetch-projects.sh`.
