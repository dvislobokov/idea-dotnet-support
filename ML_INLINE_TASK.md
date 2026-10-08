# Задание: серый текст (inline completion) от нашей нейросети в плагине C#

Написано 2026-10-07 для агента, который будет делать плагинную часть. Движок, модели и ядра готовы; в этом репозитории
нет ни одной строки, которая их вызывает. Цель задания — чтобы пользователь, собрав плагин с `-PmlEnabled=true`, увидел в
GigaIDE / IDEA серые подсказки целой строки в C#-файлах.

## 0. Что уже есть (ничего из этого переписывать не надо)

- **`ml-core/`** — модуль движка (копия `ml-core` из https://github.com/dvislobokov/idea-ml-completion, обновляется
  `tools/ml/sync-ml-core.sh ../idea-ml-completion`; **править только в движке**, здесь — не трогать). Подключён:
  `settings.gradle.kts` → `include(":ml-core")`, `csharp-psi-ide/build.gradle.kts` → `implementation(project(":ml-core"))`,
  в zip плагина попадает `lib/ml-core.jar`. Собирается JBR (без Vector API, его в движке больше нет).
- **Нативные SIMD-ядра** лежат в том же jar: `native/libcmlkernels-{linux-x64,windows-x64,macos-arm64,macos-x64}.*`.
  `NativeLib` сам распаковывает нужную во временный каталог, грузит `System.load`, прогоняет self-test и при любой
  неудаче откатывается на скалярный Kotlin (`NnKernels.best()`; причина в `NativeLib.status`). IDE стартует с
  `--enable-native-access=ALL-UNNAMED` (проверено в IDEA 2026.1.4 и GigaIDE) — предупреждений не будет. Детали:
  `docs/NATIVE-KERNELS.md` движка.
- **API для плагина** — `io.github.completionml.core.nn.NnCompletion` (документ `docs/NN-COMPLETION-API.md` движка —
  прочитать целиком, он короткий). Там уже реализовано всё, что измерялось: SPM-промпт, token healing у каретки,
  ограничение первых токенов типизированным остатком, greedy до конца строки, repetition guard, правило показа.
  Эскиз использования:
  ```kotlin
  val weights = NnFormat.read(File(dir, "cs31m-e2-lr2e3.cml"))          // mmap, ~20 ms
  val tok = BpeTokenizer.load(Path.of(dir, "cs-16384.bpe"))
  val model = NnModel(weights, nThreads = 4)                             // NnKernels.best(): native q8 или scalar
  val completion = NnCompletion(model, tok, NnCompletion.Options(showThreshold = 0.8))
  val session = model.newSession(2048)                                   // на редактор; хранить между нажатиями
  val r = completion.complete(path.toByteArray(), before.toByteArray(), after.toByteArray(), session)
  if (r.show) ghostText(r.textString, r.confProd)
  ```
  `before` — текст до каретки (хватает хвоста 40 KB), `after` — текст после каретки (остаток строки обязателен для
  healing, дальше ≤ 16 KB), `path` — путь файла относительно корня проекта (он входит в промпт как в обучении).
  `NnModel` **не реентерабелен**: один вызов за раз на модель. `NnSession` держит KV-кэш и при следующем вызове
  пересчитывает только хвост после общего префикса — поэтому сессия на редактор, а не на вызов.
- **Модели** — `ml-models/csharp/` (README там: что есть, sha256, качество, латентность). Для inline нужны два файла:
  `cs31m-e2-lr2e3.cml` + `cs-16384.bpe`. `e15-a.cml` (n-gram) и `e15-a-rank.cml` (proxy-ранкер) — для будущего ранкера,
  в это задание не входят; proxy-ранкер пользователям не показывать.
- **Образец интеграции** — Go-плагин `../idea-golang-support` (ветка `migration`): `build.gradle.kts` (блок `mlEnabled`:
  модели копируются в ресурсы `ml/go/`, сборка получает classifier `-ml`, обычный zip не меняется),
  `src/ml/resources/META-INF/go-ml.xml`, `go-psi-ide/src/main/kotlin/io/github/golangsupport/ml/GoMlModels.kt`
  (APP-сервис, фоновая загрузка один раз, состояние Idle/Loading/Ready, модели из ресурсов или из каталога в настройках),
  `GoMlSettings.kt` (страница настроек). Там ранкер списка, а не inline, но каркас (сервис, настройки, флаг сборки) тот же.

## 1. Что сделать

1. **Упаковка за флагом.** В корневом `build.gradle.kts` по образцу Go: при `-PmlEnabled=true` (или `MLENABLED=true`)
   `processResources` кладёт `ml-models/csharp/cs31m-e2-lr2e3.cml` и `cs-16384.bpe` в `ml/csharp/` и подключает
   `src/ml/resources/META-INF/csharp-ml.xml`; `buildPlugin` получает `archiveClassifier = "ml"`. Без флага — ни следа ML
   в zip. Проверка: `unzip -l build/distributions/*-ml.zip | grep ml/csharp`.
2. **Сервис моделей** (`csharp-psi-ide`, пакет `io.github.dotnetsupport.ml`, `@Service(APP)`): при первом обращении в
   фоне (не на EDT, не под read action) достать `.cml` и `.bpe` из ресурсов во временный файл (`NnFormat.read` делает
   mmap, ему нужен `File`; переиспользовать по sha, как делает `NativeLib`), построить `NnModel` (`nThreads` =
   `min(8, availableProcessors)`), `BpeTokenizer`, `NnCompletion`, один раз прогреть (`complete` на короткой заглушке —
   JIT и загрузка нативной библиотеки) и записать в лог `NativeLib.status` / имя ядер. До готовности провайдер молчит.
   Как в Go: альтернативный источник — каталог из настроек (для подмены модели без пересборки).
3. **Провайдер.** `class CSharpNnInlineCompletionProvider : com.intellij.codeInsight.inline.completion.InlineCompletionProvider`
   (jar `intellij.platform.lang.impl`), регистрация в `csharp-ml.xml`:
   `<extensions defaultExtensionNs="com.intellij"><inline.completion.provider implementation="…"/></extensions>`
   (EP `com.intellij.inline.completion.provider` есть в 2026.1.4). `isEnabled(event)`: файл C#, настройка включена,
   событие — печать (`InlineCompletionEvent.DocumentChange`) или явный вызов (`DirectCall`). `getSuggestion(request)`
   (suspend): под read action взять `before`/`after` из документа с ограничением по байтам и путь файла относительно
   `project.basePath`; затем на фоновом диспетчере (один выделенный поток на модель — `NnModel` не реентерабелен)
   вызвать `complete`; если `r.show` — вернуть suggestion с одним `InlineCompletionGrayTextElement(r.textString)`,
   иначе пустой. Сессия `NnSession` — на `Editor` (map editor → session, закрывать в `EditorFactoryListener.editorReleased`
   — она держит нативную память). Отмена: платформа отменяет корутину при следующем нажатии; `complete` блокирующий
   (100–200 мс), это приемлемо для пробы — просто не запускать новый вызов, пока идёт старый (очередь на том потоке).
4. **Настройки** (страница «C# | ML completion» или вкладка существующей): вкл/выкл, порог показа (`showThreshold`,
   по умолчанию 0.8), показывать ли одиночные закрыватели (`suppressPunctOnly`, по умолчанию не показывать), каталог
   моделей (пусто = из плагина), строка состояния: «модель загружена: cs31m-e2-lr2e3, ядра: native q8 avx512-vnni / scalar».
5. **Тесты.** (а) unit: провайдер при `show=true` отдаёт текст, при `show=false` — пусто (модель-заглушка через
   интерфейс сервиса); (б) в ML-сборке: сервис загружает модель из ресурсов и `complete` на фиксированном C#-фрагменте
   возвращает непустой результат; (в) `:ml-core:test` остаётся зелёным (его не менять).
6. **Лог и счётчики.** На уровне debug — латентность вызова и `confProd`; простые счётчики «показано / принято» в
   сервисе (пригодятся для измерения acceptance rate; принятие ловится через `InlineCompletionProvider` listener или
   insert handler платформы — если быстро не находится, отложить).

## 2. Критерии приёмки

- `./gradlew buildPlugin -PmlEnabled=true` даёт `idea-dotnet-support-<v>-ml.zip` с `lib/ml-core.jar` (с `native/*`) и `ml/csharp/*`;
  сборка без флага не изменилась.
- В IDE с ML-сборкой при печати в `.cs`-файле появляется серый текст до конца строки; `Tab` принимает; в логе есть
  строка про загруженные ядра.
- На строке `var list = new List<string>(` подсказка вида `);` не показывается при выключенных закрывателях, а
  например после `public override string ToString() => ` — показывается, если уверенность ≥ 0.8.
- Нет вызовов `NnCompletion` на EDT и под read action; нет утечки сессий при закрытии редакторов (проверить счётчиком).

## 3. Подводные камни

- Документ IDE хранит строки с `\n`; модель училась на сырых байтах файлов (в т. ч. CRLF) и к этому устойчива — отдавать
  `document.immutableCharSequence` как UTF-8 без преобразований. Не копировать весь документ на каждый вызов: `before` —
  хвост 40 KB, `after` — голова 16 KB.
- Первый вызов после старта IDE — 0.3–1 с (загрузка + прогрев). Делать прогрев при первом открытии `.cs`-файла, а не при
  первом нажатии.
- Windows: нативная DLL распаковывается в `%TEMP%` — антивирус может тормозить первую загрузку; при неудаче движок молча
  уйдёт в scalar (в 3 раза медленнее, но работает). В состоянии настроек это должно быть видно.
- `NnModel` один на приложение (~100 MB); не создавать на проект. Поток модели — один; обращения сериализовать.
- Пустые/очень короткие префиксы (начало файла) — модели нечего продолжать, `confProd` сам отсечёт.
- Не показывать внутри строковых литералов и комментариев можно решить позже — движок это не фильтрует.
- Ничего не требовать от пользователя: никаких vmoptions, флагов JVM, установки библиотек.

## 4. Чего не делать

- Не править `ml-core/` в этом репозитории (только `sync-ml-core.sh` из движка); не возвращать Vector API; не добавлять
  сторонних runtime-зависимостей; не тащить сторонние модели (Qwen и т. п.) — модель должна быть наша.
- Ранкер списка completion для C# — отдельная задача (нужен экспорт реальных списков из плагина, образец —
  `GoMlDatasetExport` в Go-плагине и `tools/psi/GO-HEADLESS-EXPORT.md` движка); proxy-ранкер из `ml-models` в прод не ставить.

## 5. Где читать

Движок (`../idea-ml-completion`): `docs/NN-COMPLETION-API.md` (API и правило показа), `docs/NATIVE-KERNELS.md` (загрузка
ядер, fallback, платформы), `docs/NN-FORMAT.md` (формат `.cml`), `docs/NEURAL-RU.md` §7 (что измерено), `CHANGELOG.md`
e16–e18 (цифры). Go-плагин: файлы из §0 как образец каркаса.

## Порог после точки (2026-10-07, измерено на 3000 позициях)
После `.` модель угадывает не хуже, но менее уверена: при пороге 0.7 показывается Go 37 % / C# 20 % позиций (точность 96 / 98 %), при 0.5 — Go 51 % / C# 30 % (92 / 96 %).
В провайдере: `showThreshold = 0.5`, если байт перед кареткой — `.` (в C# также `?.`, `::`, `->`), иначе 0.7 — через `Options.copy(showThreshold = …)` на вызов.

## Статус (2026-10-07, 0.1.132) — сделано

- Упаковка: `bash gradlew buildPlugin -PmlEnabled=true -PlocalIdePath=… [-Pml.models=<каталог> -Pml.big=true]` → `build/distributions/idea-dotnet-support-<v>-ml.zip`
  с `lib/ml-core.jar` (native/*) и `ml/csharp/{cs31m-e2-lr2e3.cml,cs-16384.bpe,e15-a.cml,e18-rank.cml[,cs50m-e3-lr2e3.cml]}`; `src/ml/resources/META-INF/csharp-ml.xml`
  подключён в plugin.xml через `xi:fallback`. Без флага zip не меняется (в plugin.xml постоянно есть только weigher `dotnetMlRanker`, без моделей он пустой).
- Код (`src/main/kotlin/io/github/dotnetsupport/ml/`): `CSharpMlSettings` (APP, `dotnet-support-ml.xml`), `CSharpMlModels` (APP-сервис: n-gram + ранкер
  в пуле, сеть + сессии на одном потоке, копия модели в `<system>/dotnet-support/ml/<sha256>/`, прогрев, `prefill`, счётчики, статус),
  `CSharpNnInline` (чистая часть: контекст 40/16 KB, порог после точки, trimOverlap, codeConfidence, certainPrefix, blankLine),
  `CSharpNnInlineCompletionProvider` (+ `CSharpNnEditorListener`: загрузка при первом редакторе, prefill при открытии, release при закрытии;
  `CSharpMlPreloadActivity`: загрузка при открытии проекта с .cs; `CSharpMlLogBridge`), `CSharpMlCompletionRanker` (Batch в контрибьюторе,
  признаки через `CSharpMlFeatures.languageBlock` + `FeatureExtractor`, декоратор « ML», `CSharpMlCompletionWeigher`), `CSharpMlConfigurable`.
- Тесты: `CSharpNnInlineTest` (чистая часть с заглушкой движка), `CSharpNnModelTest` (реальная модель из `ml-models/csharp`: healing, prefill+complete),
  `CSharpMlRankerParityTest` (экспорт на фикстуре ↔ признаки weigher'а побайтно, порядок по score, метка ML), `CSharpMlBundledModelsTest` (ML-сборка).
- Не сделано: живая проверка в IDE (на сервере нет дисплея); счётчик «принято» считает только Tab через insertHandler; подавление в строках/комментариях.

## Находки живой проверки (2026-10-08, 50m-модель в песочнице) — 0.1.139

- `return NotFound();` на пустой строке показывался как `return NotFound`: вся строка ниже порога 0.25, а `certainPrefix` резал только после
  слова или закрывающей скобки; `();` — один BPE-токен, кончается на `;`. Теперь `;` — допустимый конец среза.
- После `return NotFound()` сеть отвечает `;` (после `NotFound(` с парной `)` — `);`, движок срезает до `;`): это «одна пунктуация», скрытая
  опцией `inlineShowClosers`, а уверенность 0.56–0.57 (модель делит её между `;` и ` ;`) не проходит порог 0.7. Теперь конец оператора
  (`CSharpNnInline.statementEnd`) показывается независимо от опции, под порогом после точки (0.5), пробел перед `;` срезается.
- Набранное `i` → `if (customer == null)` без `{`: так и задумано — подсказка до конца строки, а сеть после `)` ждёт перевод строки (0.64,
  стиль Allman). Следующий шаг — многострочная подсказка: после заголовка `if/for/foreach/while/using` или `{` продолжать до закрытия блока,
  гейт по строкам. Не начато.
- `.WithMetrics(metrics => metrics` без скобок и `;` (0.1.140): файл пишет цепочки по строке, сеть копирует стиль и ставит перевод строки.
  Сделана узкая многострочность с чётким триггером — незакрытая скобка в конце строки (`CSharpNnInline.continueOpenBrackets`, вызов из
  `CSharpMlModels.complete`): следующие строки спрашиваются по одной с продолженным префиксом (KV-кэш сессии переиспользуется), каждая
  под порогом пустой строки, стоп — скобки закрылись / строка не прошла / 8 строк. `ml-core` не тронут. Вариант с `if (...)` и `{`
  по-прежнему не сделан: там триггера «скобка открыта» нет.
