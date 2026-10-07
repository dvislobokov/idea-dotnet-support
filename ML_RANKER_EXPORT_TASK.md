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

