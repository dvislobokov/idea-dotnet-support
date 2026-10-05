# ML-completion для C# и Go: общий движок, предобучение на open source, дообучение на проекте

Статус: **план на ревью** (2026-10-04), ничего не начато. Общий для двух плагинов: `idea-dotnet-support` (C#, свой PSI
`csharp-psi-core`, `CSHARP_PSI_MIGRATION.md`) и `../idea-golang-support` (Go, свой PSI go-psi, его `MIGRATION.md`; там лежит только
указатель на этот файл). В Go-репозитории уже есть `docs/ML.md` (оценка 2026-10-01: ранжирование, имена, инспекции, inline-эксперимент)
и `tools/ml` (Python, отбор и скачивание корпуса модулей) — этот план **заменяет его части про completion** (ML-1, ML-4) и опирается на
готовые куски: EP `GoCompletionRanker`, `tools/ml`. Имена (ML-2) и инспекции (ML-3) остаются в `docs/ML.md`.

Запрос пользователя: (1) «небольшая нейросеть на Kotlin внутри плагина для smart completion, анализирует проект(ы), обучается,
учитывает язык, универсальна для Go и C#»; (2) «предобучить на паре сотен популярных больших open source проектов с GitHub, потом
подробнее разбирать проект; для C# и Go отдельно».

## 0. Решения в двух строках

- **Один движок, два адаптера.** Общая Kotlin-библиотека (модели, обучение, хранение, оценка) + тонкий адаптер в каждом плагине
  (лексер, признаки контекста и кандидата из своего PSI, генератор примеров). Всё на JVM, без Python в рантайме, без сети в рантайме.
- **Три яруса, по возрастанию цены:** L1 — learning-to-rank пунктов completion (логистическая регрессия → GBDT / маленький MLP);
  L2 — токенный n-gram LM (Kneser–Ney, глобальная модель ⊕ проект ⊕ кэш правок) для серого текста и как признак L1;
  L3 — маленький трансформер, только если L1+L2 упрутся (критерии в §3.3).
- **Предобучение отдельно по языкам** на ~200 репозиториях с разрешительными лицензиями, обучение на CPU (кроме L3), веса — ресурс
  плагина с версией формата; на машине пользователя — дообучение на его проекте в фоне, ничего не уходит с машины.
- **Своя интеграция, а не платформенный ML-ranking** (почему — §4): Go — через готовый EP `GoCompletionRanker`, C# — через приоритет в
  `RoslynCompletionItems` (позже — в нативном completion шага 11c); серый текст — источник внутри уже зарегистрированных inline-провайдеров.

## 1. Где живёт общий код

| Вариант | Плюсы | Минусы |
|---|---|---|
| A. Подпроект-копия в каждом репо | просто, `--offline` | две копии разъезжаются, правки дважды |
| B. Отдельный репо + `includeBuild("../ide-completion-ml")` | один источник | сборка плагина требует соседний checkout; ломает CI и `BUILD-OFFLINE` Go |
| C. Отдельный репо, артефакт в Maven / GitHub Packages | версии явные | публикация, токены, сеть при сборке; Go собирается `--offline` |
| **D. Отдельный репо — источник истины, в плагины через `git subtree`** | каждый плагин собирается сам, история правок общая | дисциплина: править только в `../ide-completion-ml`, `subtree pull` в оба |

**Выбор — D.** Опыт go-psi / csharp-psi: оба начинали отдельными репо и переехали внутрь плагина ради атомарных правок; здесь
адаптеры и так внутри плагинов, а общая часть меняется реже и одинаково для двух языков. Правило: в `completion-ml/` плагина руками
не править (проверка — `tools/ml/check-subtree.sh` сравнивает с закреплённым коммитом из `gradle.properties` `completionMlCommit`).

Репозиторий `../ide-completion-ml` (Kotlin 2.3, `apiVersion = 2.3`, как у обоих плагинов; stdlib из платформы):

```
ml-core/     чистый Kotlin, без IntelliJ: токены, словарь, n-gram, LR/GBDT/MLP-инференс, схема признаков, формат модели, метрики
ml-train/    JVM CLI (без IntelliJ): обучение L1/L2 из шардов примеров, экспорт .cml, отчёт; запускается Gradle-задачей `run`
ml-ide/      клей к платформе (intellij.platform.module): хранилище в system dir, фоновый тренер, слияние global ⊕ project, база weigher'а
             и inline-источника — только абстрактные классы, **никаких регистраций в plugin.xml** (их делает каждый плагин своими именами)
tools/corpus Python/uv (офлайн, как `tools/ml` Go): отбор репо, sparse clone, манифест, дедуп, лицензии; общий для обоих языков
```

Пакеты — `io.github.completionml.{core,train,ide}`. Оба плагина могут стоять вместе: каждый несёт свою копию классов в своём
загрузчике; конфликтовать могут только имена `@State`, сервисов, EP и папок в system dir — их задаёт адаптер (`ownerId = "dotnet" | "go"`).

### 1.1. SPI языка (`ml-core`, `io.github.completionml.core.spi`)

```kotlin
interface MlLanguage {
    val id: String                                    // "csharp" | "go" — имя папки моделей и ключ в хранилище
    val tokenizer: MlTokenizer                        // lexer-only, без PSI: L2 и предобучение L2 на голом тексте
    val schema: FeatureSchema                         // имена/типы признаков L1 этого языка + общие; hash идёт в заголовок модели
}
interface MlTokenizer { fun tokens(text: CharSequence): List<MlToken> }   // kind: KEYWORD/IDENT/PUNCT/LITERAL; литералы → <STR>/<NUM>, комментарии выкинуты
class MlContext(val kind: ContextKind, val features: FloatArray)          // ContextKind: AFTER_DOT, STATEMENT_START, ARGUMENT, TYPE_POSITION, ASSIGN_RHS, ...
class MlCandidate(val name: String, val features: FloatArray)
class TrainingExample(val context: MlContext, val candidates: List<MlCandidate>, val chosen: Int, val repo: String, val file: String)
fun interface ExampleSink { fun accept(e: TrainingExample) }
```

В плагинах (адаптеры, на PSI, в `ml-ide`-терминах):

| | C# (`idea-dotnet-support`) | Go (`idea-golang-support`) |
|---|---|---|
| пакет | `io.github.dotnetsupport.ml` | `io.github.golangsupport.ml` |
| токенайзер | `csharp.lang.lexer.CSharpLexer` (JFlex `_CSharpLexer`) | лексер go-psi-core (`GoLexer`) |
| контекст | `CSharpMlContext` из `CSharpExpectations` / `CSharpScopeTypes` (сейчас), семантики шага 11 (потом) | `GoCompletionContext.Kind`, `expectedType` из `GoCompletionRankingContext` |
| кандидат | `CSharpMlCandidate` из LSP `CompletionItem` (kind, `isUnimported`, preselect) + PSI-сигналы `RoslynCompletionRanking` | `GoCompletionCandidate` (kind, `scopeLevel`, `expectedTypeMatch`, `element`) |
| генератор примеров | `CSharpExampleGenerator` (§5.4) | `GoExampleGenerator` поверх `GoCompletionContributor` headless |
| точка встраивания L1 | `RoslynCompletionItems` (вместо ручного `bonus`) | `GoMlCompletionRanker : GoCompletionRanker` |
| серый текст | источник в `CSharpGhostTextProvider` | источник в `GoInlineIdiomsProvider` |

**Паритет обучения и подачи** — главный риск такого рода систем: признаки на обучении и в IDE считает **один и тот же код адаптера**.
Тест `*MlFeatureParityTest`: на фикстуре признаки из генератора примеров == признаки из живого completion (`myFixture.completeBasic()`).

## 2. Признаки L1

Общие (`CommonFeatures`, считает `ml-ide`): длина префикса; совпадение префикса (начало / camelHump / середина); длина имени;
сходство имени кандидата с ожидаемым именем (параметр, левая часть присваивания: токены camelCase, Jaccard) — то, что сейчас
`CSharpNameLikeness`; частота имени в файле / проекте (из счётчиков L2-проекта); давность последнего выбора этого имени (accept-лог);
сколько раз выбирали этот пункт в этом виде контекста (`SuggestionStats.labelCount` у C#); n-gram log-prob кандидата после контекста (L2);
«нужен импорт» (C#: `RoslynCompletionPolicy.isUnimported`, Go: `scopeLevel == 6`); вид кандидата (one-hot: local, parameter, field,
property, method, type, keyword, snippet…); вид контекста (one-hot); число кандидатов в списке.

Языковые: ожидаемый тип совпал (2 точно / 1 присваиваемо / 0 — у Go есть сейчас, у C# — частично через signature help, полностью с 11b);
расстояние области (Go `scopeLevel`; C# — local/param/member/type/namespace/imported из `CSharpScopeTypes`); static / instance;
статистика «тип-приёмник → член» из корпуса (`string.` → `IsNullOrEmpty`, `Task.` → `WhenAll`, `errors.` → `Is`) — для статических
приёмников-типов известна из синтаксиса, для экземпляров нужна семантика (C# 11b; у Go есть); объявлен строкой выше; `async`-контекст / `await`
(C#), `err`-контекст после вызова с `error` (Go).

Бюджет: признаки + инференс **< 5 мс на список до 500 пунктов** (p99), без загрузки AST чужих файлов, без блокировок на EDT.

## 3. Модели по ярусам

### 3.1. L1 — ранжирование (первым)

- [ ] v0: логистическая регрессия pairwise/listwise (softmax по списку), L2-регуляризация; веса — `float[]` в `.cml`. Обучение —
  `ml-train` (SGD/L-BFGS на чистом Kotlin, минуты на 5 млн списков).
- [ ] v1: GBDT (100–300 деревьев глубины 6, LambdaMART) — своя реализация обучения в `ml-train` (гистограммный алгоритм, ~800 строк) **или**
  обучение офлайн LightGBM'ом (`uvx`) + экспорт деревьев в `.cml`; инференс в любом случае свой (`ml-core/TreeEnsemble`, микросекунды).
  Выбор по итогам v0: если LR уже даёт приёмку — дерево не нужно.
- [ ] v1-альтернатива: MLP 2×64 (ReLU) на тех же признаках — пишем в `ml-core` вручную (forward + backprop, ~300 строк); нужен, если
  дообучение на проекте (§6) пойдёт лучше дифференцируемой моделью, чем деревьями.
- Встраивание: оценка модели **заменяет** ручные веса (`RoslynCompletionRanking.TYPE/NAME_EXACT/...`, `Weight.ranker` у Go), но
  жёсткие правила остаются над ней: у Go — `expectedTypeMatch` первым ключом `GoCompletionWeigher`, у C# — `RoslynCompletionPolicy.priority`
  по виду. Переставлять только верх списка (top-N = 10–15), низ — детерминированный порядок, чтобы список не «прыгал».

### 3.2. L2 — токенный n-gram LM

- Порядок 4–5, **modified Kneser–Ney** с отсечением (count ≥ 2 для порядков ≥ 3, n-граммы порядка ≥ 4 — только встреченные в ≥ 3 репо).
  Словарь: ключевые слова и пунктуация целиком, идентификаторы — top-50k по числу репо, прочие → `<ID>`; литералы → `<STR>/<NUM>/<CHAR>`.
- Хранение: хэш-таблица `long(hash n-граммы) → (logprob: half, backoff: half)` с открытой адресацией; цель — **≤ 15 МБ на язык** в ZIP
  (сжатый `.cml`); больше — догружать с GitHub Release по запросу с SHA-256 (настройка, по умолчанию выключено) — решение после замера.
- Смесь: `P = λ_g·P_global ⊕ λ_p·P_project ⊕ λ_c·P_cache`; кэш — последние ~2000 токенов правок/открытых файлов (как `CacheModel` в SLP);
  λ подбираются на проекте EM'ом по 10% файлов (§6), по умолчанию 0.5 / 0.35 / 0.15.
- Применения: (а) признак L1 (log-prob кандидата после 4 предыдущих токенов); (б) серый текст: beam/greedy на 1–8 токенов, но **только
  из множества, которое видит PSI** (идентификатор в продолжении должен быть в области видимости или ключевым словом/пунктуацией); показывать
  при уверенности ≥ порога, подобранного на §7 (точность показанного ≥ 60%).
- Реализация своя (~600 строк). Образец — SLP-Core (Hellendoorn, MIT), который платформа держит внутри `intellij.platform.ml.impl.jar`
  (`com.intellij.completion.ngram.slp.modeling.ngram.{JMModel,ADModel,WBModel}`, `dynamic.CacheModel`, `mix.MixModel`): классы
  внутренние, глобальный приор им не подать — не опираемся, но формулы и тесты сверяем.

### 3.3. L3 — маленький трансформер (позже и только по критериям)

- 10–50 млн параметров, decoder-only, BPE-словарь ~16k на язык, контекст 512–1024 токена, int8. Обучение — GPU (вне плагина, Python/PyTorch
  офлайн в `ide-completion-ml/tools/l3`), инференс — ONNX Runtime Java (`com.microsoft.onnxruntime`, натив ~30–60 МБ на набор платформ;
  загрузка натива в загрузчике плагина и в split mode не проверены — `docs/ML.md` Go, §4) **или** свой инференс на Kotlin. Свой без
  Vector API (он incubator) — ≈ 1 MAC/параметр/токен ⇒ 30M параметров ≈ 15–40 мс/токен на ноутбуке: для 10 токенов это уже много.
- Модель скачивается по запросу (Settings → кнопка «Download»), не в ZIP; checksum, версия, удаление.
- **Критерии перехода** (все сразу): L1+L2 приняты и отгружены; на held-out exact-match L2 для продолжений ≥ 3 токенов < 25% и плато
  после двух итераций признаков; прототип ONNX грузится в IDEA и GoLand-подобной IDE и в split mode; p99 ≤ 100 мс на 10 токенов на 4-ядерном
  ноутбуке; размер ≤ 60 МБ; пользователь подтвердил объём. Оценка обучения: 6·N·D = 6·3·10⁷·2·10⁹ ≈ 3.6·10¹⁷ FLOP ≈ 3–5 ч на одной RTX 4090.

## 4. Что даёт платформа 2026.1 (проверено по jar'ам IDEA 2026.1.4)

| Что | Где | Вывод |
|---|---|---|
| EP `com.intellij.completion.ml.model` → `com.intellij.internal.ml.completion.RankingModelProvider` (`getModel(): DecisionFunction`, `isLanguageSupported(Language)`, `getDisplayNameInSettings`, `isEnabledByDefault`) | `intellij.platform.lang.impl.jar`, EP в `product-backend.jar` | Плагин **может** зарегистрировать модель для `C#`/`Go`, но `DecisionFunction.predict(double[])` получает **платформенный** вектор признаков (`getFeaturesOrder(): FeatureMapper[]`): `CommonLocationFeatures`, `ContextSimilarityFeatures`, `RecentPlacesFeatures`, VCS… Их нельзя посчитать офлайн над корпусом — обучать не на чем. Пакет `internal`. |
| EP `completion.ml.elementFeatures` / `completion.ml.contextFeatures` → `com.intellij.codeInsight.completion.ml.{ElementFeatureProvider,ContextFeatureProvider}` (`calculateFeatures(LookupElement, CompletionLocation, ContextFeatures)`) | `intellij.platform.lang.jar` | Публичны. Полезны только вместе с моделью выше; позже можно отдать свои признаки, если JetBrains-модель когда-нибудь появится. |
| `MLCompletionWeigher` (`weigher key="completion" id="ml_weigh" order="last"`), `RankingSupport`, `LocalZipModelProvider`, `CatBoost`/`RandomForest` локальные модели, реестр `completion.ml.reorder.only.top.items` | плагин `com.intellij.completion.ml.ranking` (`plugins/completionMlRanking`) | Это **бандл-плагин, его можно выключить**; работает только при модели для языка. Идею «переставлять только верх» берём себе. |
| `NGramModelRunnerManager` / `NGramFeatureProvider`, `CompletionFeaturesPolicy.useNgramModel()` | тот же плагин + `intellij.platform.ml.impl.jar` | n-gram по токенам открытых файлов проекта, без глобального приора, внутреннее API — не используем. |
| `CompletionWeigher`, `PrioritizedLookupElement`, `LookupArranger` / `CompletionLookupArranger` | `intellij.platform.analysis(.impl).jar` | Публичный путь. Go уже имеет `GoCompletionWeigher` (`order="after priority, before prefix"`), C# — `PrioritizedLookupElement.withPriority` в `RoslynCompletionItems`. Свой `LookupArranger` не нужен. |
| EP `inline.completion.provider` → `com.intellij.codeInsight.inline.completion.InlineCompletionProvider`, `DebouncedInlineCompletionProvider` | `intellij.platform.lang.impl.jar` | Уже используем: `lang.CSharpGhostTextProvider`, `lang.GoInlineIdiomsProvider`. ML-продолжение — **ещё один источник внутри них** с приоритетом ниже правил (правило `;`/`break;`/`err != nil` всегда выигрывает): два провайдера на одно событие спорили бы. |

Итог: **L1 и L2 — свои, на публичных `CompletionWeigher`/`PrioritizedLookupElement`/`InlineCompletionProvider`.** Эксперимент
«`RankingModelProvider` для C#/Go» — отдельной строкой в §8, только ради настройки в Settings | Editor | Code Completion | ML, без опоры на него.

## 5. Корпус для предобучения (отдельно по языкам)

### 5.1. Отбор (~200 репо на язык) — `tools/corpus select --lang csharp|go`

- [ ] C#: GitHub Search API `language:"C#" fork:false archived:false stars:>1000 pushed:>2025-01-01` → для каждого `/repos/{o}/{r}` и
  `/languages`: доля C# ≥ 60%, размер 1 МБ–2 ГБ, `license.spdx_id ∈ {MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause}` (+ проверка файла
  `LICENSE` в корне); ≤ 5 репо на организацию (кроме `dotnet/*` — до 15); квоты по областям: web/ASP.NET, библиотеки, desktop (WPF/Avalonia/
  MAUI), инструменты/CLI, игры (Unity ≤ 10 — другой стиль), тесты-фреймворки. Уже лежат в `.corpus/`: `roslyn`, `runtime`, `aspnetcore` — входят.
- [ ] Go: **готовый** `idea-golang-support/tools/ml` (`gopsi-corpus select/fetch/manifest`: deps.dev, proxy.golang.org, лицензии из
  deps.dev, сплит по хэшу модуля, флаги generated/test/duplicate) — взять как есть, `--top-dependents 150 --top-stars 100` ≈ 200–250
  модулей + GOROOT. Перенос в общий `tools/corpus` — только когда C#-часть устоится; до этого две реализации с одним форматом манифеста.
- Выход: `data/<lang>/repos.lock` (`repo,commit,license,stars,size,reason`) — фиксирует корпус; без него модель не публикуется.

### 5.2. Скачивание и хранение

- [ ] `git clone --depth 1 --filter=blob:none --no-checkout` + `git sparse-checkout set --no-cone '*.cs' '*.csproj' '*.props' '/LICENSE*' '/NOTICE*'`
  + `git checkout <commit>`; Go — zip модуля с прокси (уже так). Возобновляемо, отчёт `fetch-report.csv`.
- Оценка: C# ~200 репо → 4–8 ГБ исходников (до дедупа; runtime/aspnetcore/roslyn — около трети), Go → 3–5 ГБ; шарды примеров L1 —
  1–3 ГБ, счётчики n-грамм на время обучения — до 10–20 ГБ на диске. Всё в `data/` (вне git), путь — `-Pml.data=<dir>`.

### 5.3. Чистка

- Исключить: `bin/ obj/ vendor/ third_party/ external/ node_modules/ packages/`, сгенерированное (`*.g.cs`, `*.g.i.cs`, `*.Designer.cs`,
  `// <auto-generated`, `Migrations/*Snapshot.cs`; Go — `// Code generated ... DO NOT EDIT.`), файлы > 1 МБ и с долей не-ASCII > 30%.
- **Тесты — оставить с флагом**: в них много типичного использования API (`Assert.`, `require.`), но они перекашивают частоты; вес 0.5 для
  L2, полный для L1; решение проверить на §7 (вариант «без тестов» — одна строка в отчёте).
- Дедуп: точный (SHA-256 нормализованного текста) + почти-дубли MinHash (5-граммы токенов, 128 перестановок, порог Jaccard 0.8) —
  остаётся один экземпляр, у дубля `duplicate_of`. Дубли между train и eval убираются из eval.
- Сплит **по репозиторию**: 80/10/10 по стабильному хэшу `repo` (Go — по модулю, как `tools/ml`); eval-репо никогда не видны при обучении.
  Плюс отдельный held-out «как у пользователя»: `debug-playground/` (C#), `playground/` (Go).

### 5.4. Разбор и примеры (JVM, headless)

- PSI обоих языков зависит от платформы (`PsiBuilder`, `IElementType`) — нужен headless-Application. Готовая дорожка: задачи
  `intellijPlatformTesting.testIde`, как `:csharp-psi-core:corpusTest` и `:go-psi-core:corpusTest`. Новая задача **`mlDataset`** в каждом
  плагине (`*MlDatasetExport`, не тест-гейт): читает `manifest`, пишет шарды `examples-<split>-NNN.bin` + `summary.csv.gz` (для duckdb).
- L2 нужен **только лексер**: `_CSharpLexer`/`GoLexer` работают как `FlexLexer` без Application — счётчики n-грамм считает `ml-train`
  напрямую по текстам (быстро, параллельно по файлам).
- Точки примеров: каждый идентификатор после `.` (AFTER_DOT), первый идентификатор оператора (STATEMENT_START), аргумент вызова (ARGUMENT),
  тип в объявлении (TYPE_POSITION), правая часть `=`/`:=`; префикс 0 и 1–3 символа (случайно). «Ответ» — фактический токен файла.
- Кандидаты: **Go** — `GoCompletionContributor` headless над go-psi-semantic (expected type уже есть). **C#** до шага 11 своих кандидатов
  после `.` нет; офлайн-источник — Roslyn через оракул: новая команда `tools/csharp-psi/roslyndump completion <file> <offsets>`
  (`CompletionService`/`Recommender` того же коммита Roslyn, что `roslynCommit`) → тот же набор, что даёт `roslyn-language-server` в IDE,
  т.е. паритет с подачей в режиме ROSLYN. С 11c — переключиться на нативный completion и **переобучить** (§8, риск).
- Субсэмплинг: ≤ 200 примеров на файл, стратификация по `ContextKind`; цель 3–5 млн списков на язык.

### 5.5. Обучение, экспорт, лицензии, запоминание

- [ ] `ml-train l1 --lang csharp --train data/csharp/examples-train-* --valid ...` (CPU: LR — минуты, GBDT — до часа на 8 ядрах);
  `ml-train l2 --lang go --manifest ...` (подсчёт 4–5-грамм по ~0.5–1 млрд токенов — 1–2 ч, память — шардирование по хэшу).
- [ ] Экспорт `src/main/resources/completionMl/<lang>/{rank.cml,lm.cml,manifest.json,MODEL_LICENSES.csv}`: заголовок `.cml` — magic,
  `ModelFormat.VERSION`, язык, вид модели, `FeatureSchema.hash`, дата, `repos.lock` SHA-256; `manifest.json` — метрики §7 на момент сборки.
  Меняешь формат или схему признаков — поднять `ModelFormat.VERSION` / версию схемы и переобучить (тест сверяет hash схемы в ресурсе с кодом).
- [ ] `MODEL_LICENSES.csv` (repo, commit, SPDX, copyright из LICENSE) рядом с моделью и строка в `NOTICE.md` плагина: модель — статистика,
  а не код, но атрибуция Apache-2.0/BSD дешевле спора.
- [ ] Защита от запоминания (L2/L3): n-граммы порядка ≥ 4 — только из ≥ 3 разных репо; литералы и комментарии не моделируются; серый
  текст ≤ 8 токенов; L3 — Bloom-фильтр 50-токенных шинглов корпуса и запрет показа продолжения, совпадающего с ним целиком; фильтр
  секретов (ключи, токены по regex) на стадии чистки.

## 6. Дообучение на проекте (на машине пользователя)

- [ ] Когда: после индексации (`DumbService.runWhenSmart`), в корутине сервиса проекта на `Dispatchers.Default.limitedParallelism(1)`,
  порциями через отменяемый `readAction {}` (уступает записи), не в dumb mode, пауза в Power Save Mode, без модального прогресса
  (фоновая задача с отменой). Бюджет — ≤ 1 ядро, первый проход ~100k строк за ≤ 1–2 мин.
- [ ] Что учится: (а) L2-проект — счётчики n-грамм по токенам файлов проекта (тот же лексер); (б) L1 — тёплый старт с глобальных весов,
  несколько эпох SGD на примерах из файлов проекта (тот же генератор, что офлайн) с регуляризацией **к глобальным весам**
  (`‖w − w_global‖²`), а не к нулю; для GBDT — до 20 добавочных деревьев с малым шагом; (в) accept-лог: что пользователь выбрал в списке
  (C# — уже есть `suggest.CompletionAcceptListener` / `SuggestionStats`; Go — завести такой же) — самый сильный сигнал, вес ×3.
- [ ] Смесь: λ L2 и вес проектной поправки L1 подбираются на 10% файлов проекта (held-out проекта); защита — не хуже глобальной модели
  на этих файлах, иначе проектная часть выключается до следующего прохода.
- [ ] Инкрементально: `BulkFileListener`/`AsyncFileListener` по `.cs`/`.go` → очередь с дебаунсом 30 с; у каждого файла хранится массив id
  токенов (`int[]`, ~4 Б/токен) — при правке вычесть старые n-граммы, прибавить новые; L1 — переобучение раз в N изменённых файлов.
- [ ] Хранение: `PathManager.getSystemDir()/completion-ml/<ownerId>/<lang>/<project location hash>/` — `meta.json` (`ModelFormat.VERSION`,
  hash схемы, версия глобальной модели), `lm-project.bin`, `rank-delta.bin`, `accept-log.bin`. Несовпадение версии → удалить и пересчитать.
  Лимиты: ≤ 64 МБ на проект на диске, ≤ 32 МБ кучи (LRU по проектам, выгрузка при закрытии проекта).
- [ ] Приватность: ничего не уходит с машины, нет телеметрии, нет «улучшить модель отправкой данных». Модели нескольких проектов не смешиваются.
- [ ] Настройки (C#: Settings | .NET → «Code completion», ключи в `DotNetBundle.properties` и `_ru`, перегенерировать `docs/guide.html`;
  Go: страница настроек плагина): «Rank completion items with a model» (вкл.), «Learn from this project» (вкл.), «Suggest continuations
  (gray text)» (выкл. до приёмки §7), кнопка «Reset Learned Data». Опция появляется вместе с реализацией (правило «без заглушек»).

## 7. Оценка

- [ ] Стенд `ml-train eval --lang ... --model ... --baseline ...` по шардам eval-репо + playground'ам; отчёт `build/ml/eval-<lang>.md|.json`.
- Метрики по каждому `ContextKind` и в сумме: top-1, top-5, MRR, «на какой позиции был ответ при префиксе 0/1/2»; для серого текста —
  доля показов, точность показанного (exact-match первых k токенов), сохранённые символы; задержка p50/p99 на список и на продолжение;
  память модели и проектного состояния; время дообучения на 100k строк.
- Базовая линия (воспроизводится тем же стендом из тех же признаков):
  - C#: `RoslynCompletionPolicy.priority(kind, preselect, isUnimported) + RoslynCompletionRanking.bonus(...)` (сборка в
    `roslyn/RoslynCompletionItems.kt`, правила — `roslyn/RoslynCompletionRanking.kt`, тест `CompletionRankingTest`), импорт-completion
    `index/ImportCompletion.kt` (`ImportCompletionContributor`), регистр — `lang/CSharpCaseInsensitiveCompletion`; затем платформенные weigher'ы.
  - Go: `go-psi-ide/.../ide/completion/GoCompletionWeigher.kt` (expected type → ranker → scope level) + `GoAlphabeticalWeigher`, кандидаты —
    `GoCompletionContributor` / `GoLookupElementFactory`; режим gopls — `lsp/GoplsCompletion.kt`.
- **Приёмка (отгружаем ярус, только если всё выполнено на eval-репо):**
  - L1: MRR +5% относительно базы, top-1 +3 п.п., ни один `ContextKind` не хуже базы больше чем на 1 п.п.; p99 < 5 мс на 500 пунктов;
    модель ≤ 2 МБ; с дообучением на проекте — не хуже глобальной на held-out проекта.
  - L2 (серый текст): точность показанного ≥ 60% при доле показов ≥ 10% мест STATEMENT_START/AFTER_DOT; p99 < 30 мс; ≤ 15 МБ в ZIP;
    0 совпадений > 20 токенов с корпусом на 1000 случайных показах.
  - Вживую: сценарии `debug-playground/Console/Editor/MlCompletion.cs` (маркеры `// TYPE:ml-*`, `EXPECT: …`) и аналог в `playground/` Go;
    строки в `docs/LIVE_CHECKS.md`; пока не проверено — так и писать.

## 8. Дорожная карта

Объём — в сессиях, как в других планах. Каждая отгружаемая фаза — новая версия `0.1.x`, раздел в `CHANGELOG.md`, пункт в `ROADMAP.md`.

| # | Фаза | Результат | Объём | Зависит от |
|---|---|---|---|---|
| M0 | Каркас: репо `ide-completion-ml`, `ml-core` (схема, `.cml`, метрики), SPI, subtree в оба плагина, пустые адаптеры | сборка обоих плагинов с подпроектом, тест паритета на заглушке | 1–2 | — |
| M1 | Стенд оценки + базовые линии на playground'ах (без корпуса) | `eval-*.md` для текущего ранжирования обоих плагинов | 1 | M0 |
| M2 | Корпус C# (`tools/corpus`), Go — прогон готового `tools/ml`; `repos.lock` на ревью пользователю | ~200 репо на язык, манифест, дедуп | 1–2 | M0 |
| M3 | L2 n-gram: обучение по лексеру, `.cml`, признак L1, оценка perplexity/exact-match | модели L2, отчёт | 2 | M2 |
| M4 | L1 Go: `GoExampleGenerator`, `GoMlCompletionRanker` через `GoCompletionRanker`, LR → GBDT, приёмка | Go-релиз с ML-ранжированием | 2 | M1–M3 |
| M5 | L1 C#: `roslyndump completion`, `CSharpExampleGenerator`, модель в `RoslynCompletionItems` вместо ручных весов | C#-релиз | 2–3 | M1–M3 |
| M6 | Дообучение на проекте (§6), настройки, Reset, accept-лог для Go | релизы обоих | 2 | M4/M5 |
| M7 | Серый текст из L2 в `CSharpGhostTextProvider` / `GoInlineIdiomsProvider`, порог по §7 | релиз за выключенной по умолчанию опцией | 1–2 | M3, M6 |
| M8 | C# после шага 11c: нативные кандидаты, признаки типов (11b), переобучение и повторная приёмка | релиз | 1–2 | шаг 11 |
| M9 | L3 — только по критериям §3.3: прототип ONNX (загрузчик, split mode), затем обучение | go/no-go | 3+ | M7 |

- **До семантики C# (шаг 11)** можно всё, кроме M8: L2 (только лексер), L1 на кандидатах Roslyn (оракул офлайн, сервер в IDE), статические
  приёмники-типы (`string.`, `Task.`, `ArgumentNullException.` — тип виден синтаксически), признаки `CSharpExpectations` / signature help.
  **Нужны типы:** члены экземпляров (`x.` → что выбирают у `List<T>`), `expectedTypeMatch` у C#, нативный список после `.` (11c).
  У Go ожидаемые типы уже есть — поэтому Go идёт первым (M4 до M5).
- Пересечения с `ROADMAP.md` (раздел «Подсказки для частых вызовов», 2026-10-04): пункт «Частые продолжения из статистики (`suggest/`)…
  `RoslynCompletionRanking` + статистика» **становится частью M5** — статистика «тип → член» из корпуса как признак L1 и как первый пункт
  completion; правиловые пункты того же раздела (`Task.FromResult`, `CancellationToken`, `await`, `async`) остаются правилами и стоят **над**
  моделью в сером тексте — модель их не заменяет, а L2 закрывает «длинный хвост» без правил. Сценарий — тот же `CommonCalls.cs` (по ROADMAP его ещё нет, создаётся с первой подсказкой).
  У Go — то же с `GoInlineIdiomsProvider` и `docs/INLINE-SUGGESTIONS.md`.

## 9. Риски

- **Расхождение обучения и подачи** (признаки считаются по-разному; C#: Roslyn-кандидаты сейчас → нативные после 11c) — один код
  признаков, тест паритета, hash схемы в модели, обязательное переобучение на M8.
- **Скачущий список**: модель меняет порядок от символа к символу — переставлять только top-N, стабильный tie-break, детерминированные правила выше.
- **Размер ZIP и память**: L2 > 15 МБ → загрузка по запросу; лимиты проектного состояния; выгрузка моделей при закрытии проекта.
- **Фризы**: всё обучение — фон, отменяемые `readAction`; инференс L1 — в потоке completion (фоновое чтение), никогда на EDT; бенчмарк
  с порогом в `testData/benchmark` (Go) / `csharp-psi-core` benchmark (C#).
- **Лицензии и запоминание**: allowlist, `repos.lock`, `MODEL_LICENSES.csv`, правила §5.5; L3 — отдельный юридический пересмотр.
- **Ценность ниже ожиданий**: ранжирование уже неплохо закрыто правилами (`expectedTypeMatch`, `RoslynCompletionRanking`) — поэтому
  стенд и базовые линии (M1) идут **до** обучения: если база на playground'ах уже top-1 ≥ 70%, выигрыш L1 будет в длинном хвосте контекстов.
- **Платформенный ML-ranking** (`com.intellij.completion.ml.ranking`, `ml_weigh order="last"`) включится только при модели для языка;
  проверить на M4/M5, что он не переставляет наш верх (в C#/Go модели JetBrains нет; если появится — наш weigher стоит раньше).
- **Kotlin 2.3 / stdlib платформы**: не тянуть сторонние ML-библиотеки в рантайм (размер, конфликт загрузчиков); `ml-train` может
  использовать что угодно, это не плагин.
- **Две копии через subtree разъедутся** — `check-subtree.sh` в обеих сборках, правки только в `../ide-completion-ml`.

## 10. Команды (план; ничего ещё нет)

```sh
# корпус (офлайн-инструменты, uv)
cd ../ide-completion-ml/tools/corpus && uv run corpus select --lang csharp --limit 200 && uv run corpus fetch --lang csharp --jobs 8
cd ../idea-golang-support/tools/ml && uv run gopsi-corpus select --top-dependents 150 --top-stars 100 && uv run gopsi-corpus fetch && uv run gopsi-corpus manifest
# примеры (headless IDE, как corpusTest)
./gradlew.bat mlDataset -Pml.data=../ml-data/csharp          # idea-dotnet-support
./gradlew.bat mlDataset -Pml.data=../ml-data/go --offline    # idea-golang-support
# обучение и оценка (чистый JVM)
cd ../ide-completion-ml && ./gradlew.bat :ml-train:run --args="l2 --lang go --data ../ml-data/go"
./gradlew.bat :ml-train:run --args="l1 --lang csharp --data ../ml-data/csharp --model gbdt"
./gradlew.bat :ml-train:run --args="eval --lang csharp --data ../ml-data/csharp --split test --baseline"
duckdb -c "select context_kind, avg(rr) mrr, avg(top1) from '../ml-data/csharp/eval.csv.gz' group by all"
```

## 11. Решить на ревью

- [ ] Вариант D (отдельный репо + subtree) вместо подпроекта-копии.
- [ ] Тесты в корпусе — с весом 0.5 (L2) или без.
- [ ] Порог «L2 в ZIP или по запросу» (15 МБ) и вообще допустима ли загрузка моделей с GitHub Release.
- [ ] Обучение GBDT — своё на Kotlin или LightGBM офлайн через `uvx` с экспортом (в рантайме в обоих случаях только Kotlin).
- [ ] Список ~200 C#-репо (`repos.lock`) — смотрит пользователь до скачивания.
