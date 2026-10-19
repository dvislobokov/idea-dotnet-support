# Razor и Blazor: `.razor`, `.cshtml`

Составлен 2026-10-15 по решению пользователя: тема возвращена в план (снято решение 2026-09-30 «вне плана»). Цель — работа с
Blazor-компонентами (`.razor`) и представлениями MVC / Razor Pages (`.cshtml`) как в Rider: подсветка, C# внутри разметки с той же
семантикой, что в `.cs`, компоненты и tag helpers, ошибки компилятора, точки останова.

Статус шага отмечать здесь (`[ ]` → `[x]` с датой и версией), сделанное — в `ROADMAP.md`, видимое пользователю — в `CHANGELOG.md`
(каждый шаг, который видит пользователь, — своя версия `0.1.x`, как везде).

## Решение: свой Razor на csharp-psi, Roslyn и генератор Razor — оракул

Не через `roslyn-language-server`, хотя он умеет C#-половину Razor целиком (факты — раздел «Razor» в `tools/roslyn-lsp/README.md`,
фикстуры `capture-5.12-razor`). Причины:
- сервер выключен по умолчанию с 0.1.76 и уходит совсем (D5 в `CSHARP_PSI_MIGRATION.md`); Razor через сервер — фича, которая
  исчезнет вместе с ним;
- HTML-половину сервер всё равно отдаёт клиенту (`razor/updateHtml` и запросы сервера к клиенту) — её пришлось бы делать самим;
- правило ошибок (CLAUDE.md, «Ошибки компилятора») требует наших диагностик в том же месте с тем же кодом — для `.razor` так же,
  как для `.cs`.

Устройство — как у миграции C#: свой парсер по образцу Razor-компилятора, сверка с ним на корпусе, метрики, которые могут только
улучшаться. Генератор Razor из SDK (через помощник `helpers/codeanalysis`) — источник правды о том, что компилятор сделает из файла:
типы компонентов для `.cs`, RZ-диагностики, эталон сгенерированного C#.

## Рамки

В плане:
- `.razor` — компоненты Blazor (`_Imports.razor`, `@code`, `@inject`, `@typeparam`, `@rendermode`, `@attribute`, `@layout`,
  `@namespace`, `@implements`, `@inherits`, `@page`, `@using`, `@preservewhitespace`), directive attributes (`@onclick`, `@bind-*`,
  `@ref`, `@key`, `@formname`, `:after` / `:get` / `:set`);
- `.cshtml` — MVC и Razor Pages (`@model`, `@page`, `@inject`, `@section`, `@functions` (`@helper` в ASP.NET Core нет), `_ViewImports.cshtml`,
  `_ViewStart.cshtml`, `@addTagHelper` / `@removeTagHelper` / `@tagHelperPrefix`), tag helpers (`asp-for`, `asp-action`, свои);
- семантика C# внутри — та же `lang/semantic`, что у `.cs`; связь в обе стороны: Find Usages / rename члена из `.cs` находит его в
  разметке, неиспользуемый член, нужный только разметке, не серый.

Не в плане (отдельным решением): отладка Blazor WebAssembly (свой адаптер в браузере), CSS-изоляция `.razor.css` и JS (CSS и
JavaScript — плагины IDE, в IDEA Community их нет; где они есть, HTML-часть Razor получит их сама), `.cshtml` проектов .NET Framework
(MVC 5, `System.Web.Razor`) — после основного, `.vbhtml` — нет.

## Факты (2026-10-15)

- **Корпус** — `tools/razor/README.md`: `tools/razor/fetch-corpus.sh` кладёт в `.corpus/razor/` MudBlazor (2051 `.razor`), nopCommerce
  (986 `.cshtml`), eShop (Blazor Web App, 40 `.razor` + 18 `.cshtml`) и компилятор Razor из `dotnet/roslyn` (`src/Razor/src/Compiler`;
  `dotnet/razor` в архиве). Все три solution собираются SDK 10.0.401 с 0 ошибок; с `-p:EmitCompilerGeneratedFiles=true` сборка
  оставляет в `obj/…/generated/Microsoft.CodeAnalysis.Razor.Compiler/` C# каждого файла с `#line`: 2384 + 779 + 50 файлов
  (в nopCommerce меньше, чем `.cshtml`: представления плагинов копируются, а не компилируются).
- **Эталоны тестов компилятора** (`razor-compiler/…/test/TestFiles/`): `.stree.txt` — дерево (2388), `.codegen.cs` — C# (828),
  `.mappings.txt` — соответствие позиций (527), `.diag.txt` / `.diagnostics.txt` — диагностики (~800), `.ir.txt`, `.cspans.txt`,
  `.tspans.txt`. Входы в основном строками в C# тестов; отдельными файлами — 182 `.cshtml` и 108 `.razor`.
- **Генератор Razor из SDK** — `sdk/<v>/Sdks/Microsoft.NET.Sdk.Razor/source-generators/Microsoft.CodeAnalysis.Razor.Compiler.dll`.
  Ссылается на тот же Roslyn, что `Roslyn/bincore` и `DotnetTools/dotnet-format` того же SDK: 10.0.401 — 5.9.0.0 у всех троих,
  9.0.301 — 4.14.0.0 у всех троих. Значит, причина в `helpers/codeanalysis/Program.cs:387` («built against a newer Roslyn than the SDK's»)
  верна только для смеси SDK: помощник собран против Roslyn одного SDK, а проект (через `global.json`) несёт генератор более нового.
  Старый генератор на новом Roslyn грузится.
- **Платформа IDEA Community 2026.1.4:** HTML (`HTMLLanguage` в `intellij.xml.parser`), Emmet, `TemplateLanguageFileViewProvider`,
  языковые инъекции — есть; CSS и JavaScript — нет.
- **Что в плагине сейчас:** иконки, шаблоны New → .NET, nesting `Foo.razor.cs` / `.css`; `RoslynFileWatcher` следит за `.razor` /
  `.cshtml`. В проектах с Razor семантика `.cs` молчит: `CSharpSemanticEnvironment.mayGenerateTypes` — да при SDK `*Razor*` / `*Blazor*`
  и при любом `.razor` / `.cshtml` в проекте (а это и `Microsoft.NET.Sdk.Web` с представлениями). Точки останова — только в
  `CSharpFileType` (`debugger/DotNetDebugBreakpoints.canPutAt`). Лексер csharp-psi уже знает `@:` (`RazorContentToken`) и `@* *@`.

## Архитектура

1. **Язык Razor** — Gradle-подпроект `razor-psi-core` (как `csharp-psi-core`: лексер, парсер, PSI, без иконок и текстов; тип файла
   регистрирует хост). Парсер — перенос парсера компилятора (`Microsoft.AspNetCore.Razor.Language/Legacy`: `HtmlMarkupParser`,
   `CSharpCodeParser`, разбор директив), два вида файла как у компилятора (`FileKinds.Component` для `.razor`, `Legacy` для `.cshtml`).
   Узлы — виды `RazorSyntaxTree`, чтобы сверка с эталоном была почти тривиальной.
2. **Файл с тремя языками.** `MultiplePsiFilesPerDocumentFileViewProvider` / `TemplateLanguageFileViewProvider`: базовый язык Razor,
   HTML — template data language (подсветка, теги, Emmet, автозакрытие — от платформы; в IDE с CSS / JS — и они внутри `<style>` /
   `<script>`). C# — **одна инъекция на файл из многих кусков** (`MultiHostInjector`, куски с prefix / suffix, как Java в JSP): куски
   склеиваются в виртуальный C#-документ, по форме — design-time вывод генератора Razor (класс компонента или страницы, члены `@code`,
   свойства `@inject`, `using` из `_Imports.razor` / `_ViewImports.cshtml`, выражения разметки как `__o = <expr>;`, лямбды обработчиков
   с типом аргумента события). Перенос позиций, подсветка, completion, rename по кускам — забота платформы. Развилка — шаг R3a.
3. **Модель компонентов и tag helpers.** Компонент solution — stub-индекс `.razor` (имя по файлу, namespace по папке / `@namespace` /
   `_Imports.razor`, `@typeparam`, `[Parameter]`, `[CascadingParameter]`, `EventCallback`, `RenderFragment`, `@inherits`); компонент
   сборки — тип из `AssemblyIndex`, реализующий `IComponent`. Tag helpers — типы `ITagHelper` с `[HtmlTargetElement]` и
   `[HtmlAttributeName]`: нужны аргументы атрибутов в индексе сборок — подъём формата (`Program.FormatVersion` и
   `AssemblyIndex.FORMAT_VERSION` вместе).
4. **Помощник** запускает генератор Razor вместе с остальными (D4): сгенерированный C# — в `CSharpSourceScope`, как у других
   генераторов (типы компонентов и страниц для `.cs`); RZ-диагностики — в редактор `.razor`.
5. **Оракул** — `tools/razor/razordump`: консольное приложение на компиляторе Razor из SDK (по образцу `tools/csharp-psi/roslyndump`),
   режимы `tree` (дерево), `designtime` (виртуальный C# с mappings), `diagnostics` — по всему корпусу за один запуск. Семантику C#
   внутри сверяет уже готовый `roslyndump semantics` на его design-time выводе с переносом позиций обратно.

## Модульность: фреймворки рядом с ядром C#, а не внутри

Решение пользователя 2026-10-15: Razor — первый из фреймворков (дальше Avalonia, Unity, UWP), и ни один не должен править парсер и
семантику обычного C#. Устройство:
- **Ядро** — `csharp-psi-*`, `lang` (в том числе `lang/semantic`), `codeanalysis`, `index`: о фреймворках не знает. Что фреймворк
  может сказать ядру — точки расширения ядра (`io.github.dotnetsupport.csharp*` в `plugin.xml`); остальное — точки платформы
  (типы файлов, языки, `MultiHostInjector`, `ImplicitUsageProvider`, `ReferencesSearch`, completion, line markers, run configurations).
- **Фреймворк** — content-модуль `io.github.dotnetsupport.frameworks.<имя>` (дескриптор `resources/io.github.dotnetsupport.frameworks.<имя>.xml`,
  классы строго в его пакете — свой загрузчик по префиксу, как у `roslyn`): ядро его классы не видит и в рантайме. Свои тесты,
  площадка, корпус.
- **Граница проверяется тестом** `FrameworkBoundaryTest`: код вне `frameworks/` не ссылается на `io.github.dotnetsupport.frameworks`;
  в коде `lang/semantic` нет имён фреймворков (Razor, Blazor, XAML, WPF, MAUI, Uno, Avalonia, gRPC, Unity — комментарии не в счёт);
  у каждого пакета `frameworks/<имя>` есть дескриптор и строка в `<content>`; точки расширения ядра видят модули и в тестах.
- **Точка расширения появляется вместе с первым, кому она нужна** (как опции настроек): новой фреймворк-модулю новая точка —
  маленькая осознанная правка ядра, не `if` в семантике. Ожидаемые: контекст C# вне `.cs` (R3a, Razor), подавление диагностик
  (Unity), источники C# из целей сборки (`BuildGeneratedSources` за точкой — с UWP).

- [x] **F0. Точки расширения и перенос того, что уже есть** (2026-10-15, без версии: пользователю не видно; `test` — 2033 из 2034, упал
  `AssemblyGotoLibraryTest`: MVID фикстуры против эталона, падает и без F0). `csharpCodeGenerators` — «у проекта могут быть типы, которых IDE
  не видела» (было: списки SDK, пакетов и расширений в `CSharpSemanticEnvironment.mayGenerateTypes`). Модули `frameworks.razor`
  (SDK Razor / Blazor, `.razor`, `.cshtml`), `frameworks.xaml` (WPF / MAUI / Uno / UWP: SDK WindowsDesktop и Maui, пакет `Uno.`, `.xaml`),
  `frameworks.avalonia` (пакет, `.axaml`), `frameworks.grpc` (`Grpc.Tools`, `.proto`); пакеты source generators («Generator»,
  CommunityToolkit.Mvvm, Refit…) остаются в ядре — их запускает помощник. Гейт: старая формула дословно в тесте против новой на всех
  сочетаниях признаков — совпадает; весь `test`, снимки, `CompilerMessagesCoverageTest` — без изменений эталонов; `FrameworkBoundaryTest`.

## Шаги Razor

Размер — в сессиях агента (как в `CSHARP_PSI_MIGRATION.md`: узкое место — гейты и проверка, не набор кода). R0 и дальше пишутся в
модуле `frameworks.razor`.

- [x] **R0. Генератор Razor в помощнике** (2026-10-15, 0.1.186; вживую не проверено). Сделано: генератор грузится (загрузчик помощника
  считал общим любой `Microsoft.CodeAnalysis.*` — теперь только то, что помощник несёт рядом с собой), вывод на MudBlazor (165 файлов) и
  Nop.Web (779) байт в байт как у сборки (SHA-256 у additional files, пути с `/` в именах Razor), правка `.razor` доходит до вывода
  (`FileText` — снимок, изменённый файл подменяется в драйвере; 0,13 с на eShop), в ответе `generate` — `additionalFiles` и `generators`;
  плагин перезапускает генераторы по сохранению любого additional file и считает вывод устаревшим при наборе в нём. Модуль
  `frameworks.razor` знает, что генератор отработал (`CSharpProjectFacts.ranGenerator`), но ошибки не включает
  (`errorsAfterGeneration = false`, решение пользователя 2026-10-15): харнесс на `.corpus/razor` (`SOLUTION_HARNESS_GENERATED=true`)
  показал, что все ошибки, зависевшие от Razor, ушли (eShop 5 → 0, MudBlazor 409 → 241 ошибка в `.cs`), но в проектах с Razor открываются
  ложные ошибки ядра, которые прятало молчание: MudBlazor 59, nopCommerce 64 (Nop.Web 28), eShop 3. Исходный план шага: Убрать исключение `IsRazor` в `helpers/codeanalysis` (генератор того же SDK, что
  помощник; при смеси SDK — лог и прежнее молчание); `.razor` / `.cshtml` — additional files с метаданными из сгенерированного
  `.editorconfig` design-time сборки (`build_metadata.AdditionalFiles.TargetPath`, `CssScope`, `build_property.RazorLangVersion`…).
  Вывод генератора — в кэш, `CSharpSourceScope`, Dependencies → Analyzers. `mayGenerateTypes`: Razor не повод молчать, когда вывод
  свежий (как XAML в 0.1.82). Гейт: `SolutionDiagnosticsHarnessTest` на MudBlazor, nopCommerce, eShop — 0 ошибок в `.cs` (все три
  собираются). Польза сама по себе: правильные ошибки и completion в `.cs` веб-проектов, ещё без редактора Razor.
- [x] **R0b (0.1.187–0.1.195; `errorsAfterGeneration = true` — 0.1.196, ошибки Razor-проектов включены). Ложные ошибки ядра, найденные на
  корпусе Razor** — на корпусе осталась одна (MudBlazor, явный член интерфейса по умолчанию `INumber<T>` — индексатор). Вживую (робот в
  WSL, 0.1.196): генератор отрабатывает за 4 с после открытия файла, дерево и Ctrl+B работают, но ошибок не было — любая несохранённая
  правка `.cs` делала проект «несвежим» (`CodeAnalysisService.documentChanged`/`editedSince`), и семантика молчала всё время набора.
  Исправлено в 0.1.198: `CSharpCodeGenerators.readsOnlyAdditionalFiles` — генератор Razor читает только `.razor`/`.cshtml`, правка `.cs`
  свежести не меняет (что он берёт из C# — `[Parameter]` code-behind для разметки другого компонента — подтянется при следующем
  сохранении `.razor` или сборке). Заодно пробел ядра: `nameof(X.Missing)` в инициализаторе поля не даёт CS0117 (Roslyn даёт) —
  закрыт в 0.1.204 (имена аргумента `nameof` проверяются, плюс CS8093; пример `compilerMessages/cases/plugin-nameof.cs`). Это правки ядра C# (`lang/semantic`),
  по одному классу ошибок с примером в корпусе `CompilerMessagesCoverageTest`. Найдено (харнесс, 2026-10-15):
  - группа методов как делегат: `Inject(..., InjectTraceContextIntoBasicProperties)` (локальная функция, параметр `IBasicProperties` к
    `Action<BasicProperties, …>`), `TrackingAction` / `Invoke` → `Func<Task>` — CS1503 «cannot convert from 'Task' / 'void'» (больше 100);
  - verbatim-идентификатор с `@`: `@Icons.Material…`, в выводе Razor `@Html`, `@Model` — CS0103;
  - вложенный тип через `using static Outer;` (`MyEnum` компонента) — CS0246;
  - вложенный класс partial-типа из другого файла (`DefaultConverter.Guid.cs` → `GuidConverter`, `NumberConverter<T>`) — CS0246;
  - диапазонные переменные LINQ (CS1930 в nopCommerce), лямбда в `EventCallback` (CS1660 в выводе Razor), `int?` → `decimal?`;
  - артефакты харнесса, не плагина: исходники всех проектов видны всем (CS0101 на одноимённых классах), файлы `<Compile Include="..\Shared\…">`
    вне папки проекта не берутся, тип и из исходника, и из сборки проекта (`KeyInterceptorService` → `KeyInterceptorService`).
  Гейт: в проектах с Razor на трёх solution — 0 ложных ошибок, кроме артефактов харнесса.
  Ход (2026-10-15, без версии; `RazorCorpusFalseErrorsTest`, полный `test` — 2047 из 2048, упал только `AssemblyGotoLibraryTest`, он
  падает и без правок): исправлены группа методов из локальной функции (`isInMethodGroup`), `@` в именах (поиск и локальные области;
  `class @Html` в объявлениях типов и членов — нет, это stub-индекс), вложенные типы `using static`, `join … into`, `int?` → `decimal?`
  и константа в `byte?`, срез `span[range]` по переменной типа `Range`, базовый класс partial-типа из другой части (интерфейс решения
  не база, `isBaseClass`), `a?.M()` как тело void-лямбды (CS1660 в выводе Razor), async-лямбда и лямбда с `Task<…>` к `Func<Task<T>>`
  против `Func<T>` (§10.7.1, выходной тип `Task<X>` при выводе, подсчёт в `refineByLambdas`). Харнесс, ошибки в `.cs` (до → после):
  MudBlazor 241 → 52, nopCommerce 88 → 7, eShop 15 → 13 (в eShop остались артефакты харнесса). Осталось в MudBlazor: 34 CS1503
  `KeyInterceptorService` → `KeyInterceptorService` (тип из исходника и из собранной сборки — проверить, артефакт ли), `T` → `T?` и
  `T` → `NullableObject<T>` (пользовательское преобразование generic-структуры), `(c ? Method : null)!` к `EventCallback`; в nopCommerce
  CS1678 / CS1661 у лямбды с параметром `_` и CS0029 `order.Id`.
- [x] **R1 (0.1.229–0.1.230). Язык и подсветка** (1–2). Типы файлов `.razor` / `.cshtml`, лексер Razor (переходы `@`, `@(…)`, `@{…}`, ключевые слова и
  директивы, `@* *@`, `@@`, `<text>`, email-эвристика `a@b`), HTML как template data, C#-куски лексером csharp-psi для цвета, folding
  (`@code`, `@{}`, `@section`, теги), парные скобки, комментарий Ctrl+/, структура (члены `@code`, секции). Сценарии площадки.
  Ход (2026-10-16, без версии; UI-робот в песочнице Windows — цвета, сворачивание, Ctrl+/, Structure; человеком не проверено): всё перечисленное сделано в модуле `frameworks.razor`. Лексер — свой
  (`RazorLexer`, без дерева: R2), по `HtmlMarkupParser` / `CSharpCodeParser`: разметка юнитами (текст, тег, HTML-комментарий,
  конструкция) с состоянием 0 только там, где перезапуск даёт те же токены; C# идёт лексером C# плагина (строки, комментарии), разметка
  в коде — `<` в начале оператора (любой, кроме `<<` / `<=` / `< `), `@:`, `@<`, `<text>`; незакрытая `(` — до `<`, как
  `AcceptUntil(LessThan)`. Факты: `@using Foo` у компилятора — Code-спан от `using`, а не директива; `<text>` — Transition-спан;
  в компоненте `@` в области атрибутов — имя атрибута (`AllowCSharpInMarkupAttributeArea` снят), в `.cshtml` — переход в C#.
  Гейт `RazorLexerGateTest` (посимвольно против `.cspans.txt` из `test/TestFiles/ParserTests` без tag helpers): 0.9241, без
  `RazorDirectivesTest` (свои директивы тестов) — 0.9822; остаток — директивы препроцессора поперёк блоков, конфликтные маркеры git
  и ошибки восстановления. Корпус трёх solution: 3305 файлов, 0 незакрытых блоков, перезапуск совпадает. Известные пробелы: значения
  параметров компонентов (`OnClick="Increment"`) — разметка до R5; `@bind:culture` и параметры кроме `event` / `format` — C#;
  выражения в `@{ #if … }` поперёк блоков — нет.
- [x] **R2 (0.1.251). Парсер и гейт дерева.** Подпроект `razor-psi-core` (пакеты `io.github.dotnetsupport.razor.*`, в jar как
  csharp-psi): порт парсера компилятора метод в метод — `HtmlMarkupParser`, `CSharpCodeParser`, `TokenizerBackedParser`, токенизаторы
  HTML и «родной» C#, директивы движка по видам файла (`RazorDirectives.forFileKind`), флаги версий (`RazorParserOptions`), диагностики
  RZ1000–RZ2005 с текстами, chunk generators и edit handlers (печатаются как у компилятора), узлы и виды дерева сгенерированы из
  `Syntax.xml` / `SyntaxKind.cs` (`tools/razor/gen_syntax.py`), дерево без PSI (`RazorNode` / `RazorToken` / `RazorList`, ширины по
  требованию), сериализатор в формате `.stree.txt`. Токенизатор Roslyn (`use-roslyn-tokenizer`, умолчание SDK 10) — `RoslynCSharpTokenizer`
  на лексере csharp-psi (`CSharpLexerCursor`: снимки контекста директив лексера, перезапуск при откатах парсера, новый блок C# лексится
  заново, как `SkipForwardTo`; строка директивы — один токен до перевода строки, интерполированная строка — один токен из частей,
  `///` берёт свой перевод строки). Проходы движка над деревом, которые видит тулинг: `WhitespaceRewriter` (отступ перед `@x` —
  разметка) и RZ2002 вложенных `@section`. Оракул — `tools/razor/razordump` (`tree`, `cases`; README там): компилятор установленного
  SDK, рефлексия, движок как у генератора. Гейты (`:razor-psi-core:corpusTest`): эталоны тестов компилятора `.stree.txt` —
  1245/1245 (`legacyTest` родным токенизатором с edit handlers, `test` Roslyn-токенизатором без них; вне гейта — tag helpers (R5),
  директивы самих тестов и 16 кейсов с опциями тестов, список в `RazorBaselineCorpusTest`); корпус против `razordump tree`: eShop
  58/58, nopCommerce 1193/1193, MudBlazor 2051/2054 — 3 расхождения это разница версий компилятора: исходники корпуса (dotnet/roslyn
  main) новее SDK 10.0.401, у нового `switch`-выражение с вложенными блоками разбирается как вложенные блоки кода
  (`ParseNestedBlocksAsMarkup`), у старого — одним литералом; порт следует исходникам; метрики `testData/metrics/*.json` только
  растут; мутационный фазз (`RazorParserFuzzCorpusTest`) — 0 исключений, текст дерева равен входу. Факты: эталоны тестов — CRLF
  (`LF` в первой строке), `K14` — число ключевых слов зависит от набора директив (нормализуется); у `test/` опции — Roslyn-токенизатор,
  без edit handlers; несколько файлов MudBlazor в UTF-16 и с двойным BOM (читать как .NET: `RazorTestUtil.readText`). Не сделано в R2:
  PSI IDE ещё на плоском дереве R1 — переключение редактора на это дерево (замена `RazorParser` хоста, токены по дереву) идёт с R3a.
- [x] **R3. Виртуальный C#-документ** (3–4; закрыт подшагами R3a, R3b, хвосты — в R4 / R5e, 0.1.286).
  - [x] R3a (0.1.252). **Решение: инъекция** (`MultiHostInjector` из многих кусков), не свой файл. Проба прошла: completion после `@cur`
    и `@Model.`, Go to Declaration из разметки в `@code` и в `.cs` проекта — и обработчиком на инъецированном листе, и действием на
    редакторе фрагмента (`RazorCSharpDocumentTest`). В ядре потребовалось два места, оба — платформенные понятия, не Razor:
    `CSharpSemanticEnvironment.projectOf` снимает `VirtualFileWindow` (проект фрагмента — проект файла-хоста), completion-контрибьюторы
    читали `parameters.editor.document` — у инъекции это документ хоста с другими смещениями, теперь `CompletionParameters.documentText`
    (документ файла, в котором идёт completion). Остальное — `originalFile`, `containingFile.virtualFile` — у инъекции и так своё.
    Заодно сделано из R2: **PSI хоста — дерево компилятора** (`RazorTree.kt`: типы элементов по `RazorSyntaxKind`, по два у литералов —
    C#-литерал языка `RazorCSharp`, токены с спаном MARKUP / CODE / META по chunk generator литерала; `RazorAstBuilder` строит AST
    напрямую, без `PsiBuilder`; `RazorFile.syntaxTree` — дерево с диагностиками для R6); HTML-файл представления — по дереву
    (`RazorTemplateLexer`: разметка одним прогоном, остальное outer); folding, структура, Ctrl+/ — по PSI. Лексер R1 остался только у
    подсветки редактора (инкрементальный) и у brace matcher. Документ (`RazorCSharpDocument`) — пока ручной эмиттер по кускам в порядке
    файла: `using` хостами до класса, заголовок класса текстом (`@inherits` / `@model` / `@typeparam` / `@implements` / `@attribute` /
    `@namespace` — из директив файла), `@inject` → свойство, `@code` / `@functions` → члены, выражения `__o = …;`, операторы как есть,
    `@section` → `DefineSection`, шаблоны `@<b>` → лямбда; разметка после `@code` — второй метод `__RazorRender2`. Его заменит R3b.
    Факт: `GotoDeclarationAction.findAllTargetElements` на редакторе хоста инъекцию не видит (IDE сама даёт `EditorWindow`), в тестах
    брать `InjectedLanguageEditorUtil.getEditorForInjectedLanguageNoCommit`.
  - [x] R3b (0.1.253). **Полный перенос конвейера генерации компилятора** (решение пользователя 2026-10-17: «вариант A, целевой,
    без приколов»). Факты, из-за которых план был переписан: design-time режима больше нет ни в исходниках корпуса, ни в dll SDK
    10.0.401 — тулинг живёт на runtime-выводе с `#line (строка,колонка)-(строка,колонка)` и `SourceMappings`; разметка понижается в
    `UnresolvedElement`-узлы, а компоненты и tag helpers разрешаются отдельной фазой по IR (`DefaultTagHelperResolutionPhase`), без неё
    разметка не разворачивается даже при нуле tag helpers; «расщепление» документа (`DefaultRazorMarkupSplitPhase`, decl / impl) есть
    только в исходниках корпуса, в движке выключено по умолчанию. Перенесено ≈ 31 тыс. строк C# класс в класс (razor-psi-core, пакеты
    `razor.lang.intermediate`, `razor.lang.codegen` с `extensions`, `mvc`, `components`): red-дерево над зелёным деревом R2 (`syntax/red`,
    генерируется `tools/razor/gen_syntax.py`, `RedSyntaxRewriter` строит зелёные узлы заново), IR с узлами, обходами, builder и
    форматтером, `RazorSourceDocument` / `RazorCodeDocument` / `RazorProjectEngine` с фазами и features как у компилятора, понижение,
    discovery контекста tag helpers, разрешение (`TagHelperResolution.kt`: legacy и компонентный резолверы), переписывание дерева
    (`TagHelperParseTreeRewriter`), классификаторы и проходы директив (`Extensions`, `Mvc` с view components, ~25 проходов компонентов),
    `CodeWriter` + runtime node writer + `ComponentNodeWriter`, расщепление, `Utf8WriteLiteralPhase` (без Roslyn — карта поддержки
    приходит извне, иначе выключена), `TypeNameRewriters` со своим разбором имён типов C# вместо Roslyn. Движок генератора SDK —
    `RazorGeneratorEngine` (как razordump его строит). **Гейты** (`:razor-psi-core:corpusTest`): эталоны файловых тестов компилятора
    `RazorCodegenBaselineCorpusTest` — 93/93 по `.codegen.cs`, `.ir.txt`, `.mappings.txt`, `.diagnostics.txt` (33 случая с дескрипторами
    из статического C# тестов — вне гейта до R5; сериализаторы IR и mappings тестов компилятора перенесены — `RazorIrSerializer`,
    `RazorMappingsSerializer`); корпус против `razordump codegen` dll SDK без tag helpers (`RazorCodegenCorpusTest`, версия языка 10.0 с
    обеих сторон — latest SDK) — eShop 58/58, nopCommerce 1193/1193, MudBlazor 2051/2054 (те же 3 файла новее SDK). Факты: контрольная
    сумма `#pragma checksum` считается по байтам потока вместе с BOM (`RazorSourceDocument.hasBom`); `ParseLeadingDirectives` движка
    ведущих директив — свои токенизаторы (`DirectiveHtmlTokenizer` / `DirectiveCSharpTokenizer`); SDK 10.0 пишет `@rendermode` без
    `#line`, main — с ним с Razor 11 (порт следует версии). **Дескрипторы — данные**: `TagHelperDescriptorJson` (`syntax/`) читает и
    пишет JSON, который `razordump tag-helpers` / `cases-codegen --tag-helpers-out` снимает с discovery компилятора
    (`testData/codegen-tagHelpers`, общий набор ссылочных пакетов + 6 своих); этим же форматом индекс плагина отдаст дескрипторы в R5.
    **Хост** (`RazorCSharpInjection.kt`): документ = вывод генератора на тексте редактора с `_Imports.razor` / `_ViewImports.cshtml` по
    иерархии папок проекта, RootNamespace и версией Razor по TFM (`HostFileSystem` читает документы IDE; копия файла для completion — через
    `originalFile`); хосты инъекции — source mappings, чьи спаны лежат в C#-литерале PSI, остальной текст — клей. Факт платформы:
    куски инъекции склеиваются в порядке хостов, и completion проверяет, что каретка между первым и последним хостом
    (`CompletionAssertions`, «startOffset after injected») — поэтому хосты обязаны идти в порядке файла. Решение: проход
    `RazorIdeLayoutPass` (только в IDE, не в гейтах) расставляет члены класса по позиции в файле, разрезая render-метод там, где между
    его операторами стоит член (`BuildRenderTree__2` — как делал design-time документ), а среди mappings хостами становится самая
    тяжёлая цепочка в порядке файла (`longestChain`), остальное — текст (например, маршрут `@page` перед `@using`). Эмиттер R3a убран.
    Не переносить: языковой сервер Razor (completion, форматтер — на Roslyn Workspaces; это даёт инъекция платформы).
  - Гейт: имена и типы C#-выражений в разметке против `roslyndump semantics` на выводе `razordump designtime` — тот же порог, что у
    `semanticGate` для `.cs`; 0 ложных ошибок на трёх solution.
- [x] **R4. C# в разметке** (2). Completion (члены, `@` в разметке — выражения и директивы, параметры `@inject`), Go to Declaration,
  Find Usages и rename в обе стороны `.cs` ↔ `.razor`, цвета ссылок, quick documentation, parameter info, inlay hints. Учёт
  использований в разметке там, где хост решает «не используется» (серые члены, `CSharpSemanticWarnings`).
  Состояние (2026-10-19, 0.1.286; проверено роботом в WSL): completion, Ctrl+B, цвета и quick documentation (Ctrl+Q на `@Step` —
  `int Counter.Step { get; set; }`) идут через инъекцию с R3; Find Usages / rename `.razor` ↔ `.cs` — с R5e ч. 2. Закрыто в 0.1.286:
  Find Usages члена `@code`, начатый в `.cs` (имя там — член вывода генератора), находит его использования в `@code` и разметке
  (`RazorTagHelperUsages.sourceTwin` — обратный `generatedTwin`); линза использований C# считает разметку (`RazorMarkupUsages` через
  `csharpImplicitUsage.usageNote`: «1 usage, 2 usages in markup», у tag helper всех элементов — элементы, к которым он применяется);
  автопопап в значении атрибута-C# (`asp-for="Na"`: обработчик набора XML держал список закрытым — `RazorAttributeValueAutoPopup`).
  Найдено в WSL: после превращения литералов разметки в хосты (0.1.281) имена атрибутов-параметров тоже стали C# (компилятор
  отображает на них `nameof(Counter.Step)`) — каждое использование атрибута находилось дважды; хост разметки теперь только значение
  атрибута. «Серые члены»: в ядре нет предупреждений о неиспользуемых членах (только локальные CS0168 / CS0219), учитывать нечего.
  0.1.288 (проверено роботом в WSL): inlay hints C# в разметке и `@code` — свои провайдеры языка Razor (платформа спрашивает провайдеры
  языка файла, C# впрыснутого документа не видит), подсказки C# по его правилам и настройкам, только на тексте пользователя: у клея
  генератора (`__builder.AddContent(…, count)` за `@count`, `TypeCheck<int>(step)`) свои вызовы, их `value:` не показываются
  (`RazorInlayHints.kt`, площадка — `TYPE:razor-inlay-hints`). Правка `.razor` (несохранённая тоже) пишет класс заранее, через ~0,7 с после
  набора (`RazorGeneratedAhead.applyEdit`), когда изменилось то, что видит C#: члены `@code`, директивы; правка одной разметки класс не
  трогает — «форма» класса без тел методов разметки (`#pragma warning disable/restore 1998`), `#line` и `#pragma checksum` всего файла.
  Найдено в WSL: обновление папки вывода не видит нового текста уже существующего файла — Ctrl+B на новый параметр вёл в никуда;
  обновляется сам файл. Члены partial-класса компонента семантика не проверяет, пока генераторы проекта не свежие (несохранённая правка) —
  так задумано (D4), поэтому `nameof(Counter.Nope)` в это время не красный.
  0.1.289 (найдено в WSL проверкой completion в `@code`): в проекте, для которого помощник запустил генератор (на деле — всегда),
  семантика `@code` молчала. Вывод генератора самого файла (`RazorCSharp_razor.g.cs`) резолвер брал второй частью partial-класса
  впрыснутого документа — каждый член `@code` был дважды («resolve Sum: no best of [Sum(int, int), Sum(int, int)]», «the type of argument
  `count` is not known»): после `Navigation.` только постфиксные шаблоны, `Navigation.Nope` и `count = true` без ошибок. Тесты этого не
  видели: в них вывода генератора нет. Теперь точка `csharpCodeGenerators` отвечает `documentOutput` — Razor называет вывод генератора
  самого файла, резолвер документа его частью не берёт (код-бихайнд `.razor.cs` — по-прежнему часть). Проверено в WSL: члены после
  `Navigation.`, CS1061 / CS0029 в `@code` как у Roslyn, `Increment()` — `Task`.
  Харнесс решений (`SolutionDiagnosticsHarnessTest`) теперь меряет и C# Razor-файлов: впрыснутый документ каждого `.razor` / `.cshtml` с
  дескрипторами проекта, проблемы на тексте пользователя со строкой Razor-файла; контрольный `HarnessControl.razor` (CS0103 + CS0029) —
  иначе прогон падает; генераторы считаются отработавшими (вывод SDK подложен), как в IDE после прогона помощника. Первый прогон нашёл на
  корпусе 1100 ложных ошибок, исправлено в 0.1.289: дословные имена членов (`@MudFormComponent_Required` генератора resx — 513 CS0117),
  ограничения partial-типа из другой части (`where T : struct` в `.razor.cs` — CS1503 «'T' to 'T?'»), `.cshtml` вне проекта
  (`<Content Remove="Plugins\**">` у Nop.Web: `ProjectContent` считал Razor-файлы `None`, а Web / Razor SDK — `Content`; C# файла, который
  сборка не компилирует, не проверяется — точка `csharpCodeGenerators.isDocumentCompiled`), `@inject` после блока кода резал метод
  разметки (`RazorIdeLayoutPass`: локальные блока не видны разметке после — теперь такой `@inject` идёт после метода без привязки к
  тексту). Итог на корпусе: eShop 48 документов, MudBlazor 2013, nopCommerce 1193 — ошибок нет, только скрытые CS8019 / CS8933.
  Не сделано: в окне Usages строка из разметки показывает текст куска (`Step`), а не строку файла.
  0.1.294 (проверка пользователя 2026-10-19 в песочнице, «completion для @inject и прочих вещей»): (1) после `@` в начале строки разметки —
  директивы вида файла (`RazorDirectives.forFileKind` + `using`, у `.cshtml` ещё `addTagHelper`…) и операторы (`if`, `foreach`…): C#
  документа видел в `@inj` выражение `inj` (`RazorCSharpCompletion`); (2) клей генератора не в списке — `__builder`, `BuildRenderTree` /
  `ExecuteAsync` и их части `__2` (после точки — только `__`-имена: у библиотеки может быть свой `ExecuteAsync`); (3) голова класса после
  члена — `@using`, `@implements`, `@inherits`, `@attribute`, `@layout`, `@rendermode` после `@inject`: генератор пишет их до членов,
  куски документа идут в порядке файла, и такой кусок становился клеем без completion. Теперь каждый — своя инъекция
  (`RazorCSharpDocument.headPlaces`: документ до головы класса с куском на месте и пустым телом), семантика её не проверяет
  (`isDocumentCompiled` = false: в её классе нет членов), ошибки этих строк по-прежнему не показываются; (4) имя после типа `@inject` —
  из типа по словам (`IJSRuntime` → `JSRuntime`, `Runtime`). Найдено попутно: `getInjectionHost` документа из многих кусков отдаёт первый
  хост (маршрут `@page`), хост каретки — по `injectedToHost`. Тест `RazorCompletionTest`, площадка — `RazorDirectives.razor`.
- [x] **R5. Компоненты и tag helpers** (3; 0.1.286 — последние хвосты R5e). Теги компонентов в completion (solution и сборки, с `@using` в области), атрибуты —
  `[Parameter]` с типами, `@bind-Value` / `ValueChanged`, `EventCallback`, `RenderFragment` дочерним содержимым, generic-компоненты
  (`TItem` по аргументам), `@ref`; переход тег → `.razor` / тип сборки, Find Usages и rename компонента (файл и теги). Tag helpers
  для `.cshtml` после подъёма формата индекса: `asp-*`, свои, `@addTagHelper`. Гейт: `razordump` отдаёт привязки тегов
  (`.tspans` / tag helper descriptors) — наши совпадают на корпусе.
  Подшаги (2026-10-17):
  - [x] **R5a. Порт поиска дескрипторов** (2026-10-17, без версии: пользователю не видно). Producers компилятора (`Language/TagHelpers/Producers`,
    `DefaultTagHelperDescriptorFactory`, view components MVC) класс в класс — `razor-psi-core`, пакет `razor.lang.discovery`: модель
    символов без Roslyn (`RazorSymbols.kt`: типы-ссылки над определениями, атрибуты с аргументами; вопросы к компиляции — преобразование
    строкового литерала, `Dictionary<string, object>` → тип, awaitable-делегат, `IDictionary<,>` типа — свойства члена, о котором спрашивают),
    написание типов в трёх форматах Roslyn (`RazorTypeDisplay`), builders с проверками имён (RZ3000–3017, RZ3500–3507), `RequiredAttributeParser`,
    `TagHelperDiscovery` (порядок producers как у движка генератора, поиск по сборке — то, что хост кэширует). Оракул — новый режим
    `razordump symbols` (символы той же компиляции, что у `tag-helpers`). Гейт `TagHelperDiscoveryCorpusTest` (`:razor-psi-core:corpusTest`):
    reference packs, eShop WebApp / WebAppComponents, MudBlazor, Nop.Web — без пакетов и как в сборке (`GlobalUsings.g.cs` + dll вывода) —
    4906 / 4906 дескрипторов байт в байт, ~57 тыс. написаний типов как у Roslyn. Факты: флаг `caseSensitive` атрибута — только явно заданный;
    `IsGenericType` у Roslyn истинен и для вложенного в generic типа; неразрешённый `Func<T, string>` — тоже generic; текст RZ3902 в ресурсах
    дважды экранирован (`Task&lt;T&gt;`); компилятор SDK 10.0.401 не знает `AcceptsUnmatchedAttributes`, `AcceptsAssetPath`,
    `AcceptsStringLiteral` (razordump пишет их умолчаниями и называет в `absent=`, гейт приводит наши так же; порт следует main).
  - [x] **R5b. Символы из индекса сборок** (2026-10-17, без версии). Формат индекса 7 (`indexer/README.md`): атрибуты с аргументами у типов,
    членов и generic-параметров (каждый экземпляр по порядку метаданных; конструктор и именованные; строки, числа, `typeof`, enum, массивы),
    `notnull` параметров типа, имена ссылок сборки, строка типа в TypeDef (компилятор обходит сборку по порядку метаданных, индекс сортирует по
    имени; от порядка зависит порядок дескрипторов) — +14 % на ASP.NET Core. Плагин: `IndexedAttribute`, `IndexRazorCompilation`
    (`frameworks/razor/IndexRazorSymbols.kt`: обход как у `TagHelperDiscoverer`, ссылки на типы в модель, вопросы к компиляции —
    по базам и интерфейсам индекса; документации нет — у компилятора для метаданных её тоже нет). Гейт `RazorTagHelperSemanticGate`
    (часть `semanticGate`: индексатор и razordump запускают `dotnet`): reference packs, `MudBlazor.dll` как пакет, dll вывода Nop.Web —
    2114 / 2114 дескрипторов байт в байт. Кэш дескрипторов сборки по MVID — вместе с R5d (там их спрашивает редактор).
  - [x] **R5c. Символы исходников solution** (2026-10-17, без версии). `SourceRazorSymbols` (`frameworks/razor`): типы C#-файлов проекта и
    объявления его компонентов `.razor` — их делает наш движок (`RazorCSharpDocument.declaration`, `ProcessDeclarationOnly`), лёгкий C#-файл
    с `originalFile` = `.razor` компилируется в проекте `.razor` (`RazorTagHelpers.declarationFile`); части partial-типа — объявление
    компонента первым, потом части индекса без вывода генератора Razor; базы, интерфейсы, типы атрибутов и свойств — своим резолвером там,
    где написаны (ограничения — в файле своего `where`, атрибуты параметров типа — со всех частей), аргументы атрибутов — вычислителем констант
    плюс `typeof`, массивы, `nameof`, свои `const` типа (`Attributes = FOR_ATTRIBUTE_NAME`); типы сборок — через `IndexRazorCompilation`;
    проекты-ссылки — из исходников со своим `AssemblyName`. `RazorTagHelpers.discover(project, projectFile)` — компиляция проекта целиком
    (её спросит редактор, R5d). Гейт — в харнессе (`SolutionDiagnosticsHarnessTest.tagHelperGate`): на каждый проект с Razor наш поиск
    против `razordump tag-helpers` той же компиляции (`--root-namespace`, `--assembly-name`, глобальные using, те же сборки, `--packs` —
    паки фреймворков проекта, `--define` — символы, с которыми разобраны файлы); документация не сравнивается (у компилятора `cref`
    разрешены). eShop 4 / 4, MudBlazor 7 / 7, nopCommerce 34 / 34 проектов без расхождений (свои дескрипторы MudBlazor — 1240, Docs — 2152);
    расходится только порядок между сборками. Факты: лёгкому файлу сборки харнесса выдавались по пути `viewProvider` — у него это путь
    без проекта, теперь по `originalFile`, как в IDE.
  - [x] **R5d. Дескрипторы в движок редактора** (2026-10-17, 0.1.264; вживую не проверено). `RazorTagHelperService` (проект): дескрипторы
    каждого проекта с Razor — в фоне (`ReadAction.nonBlocking`, один прогон за раз, пересчёт через 0,7 с после правки C# или Razor),
    дескрипторы сборок — в кэше на набор сборок (`RazorTagHelpers.Cache`); `RazorCSharpDocument` собирает документ с ними
    (`RazorProjectEngine.process(item, tagHelpers)`) и пересобирает, когда они меняются (`tracker`). Гейт кодогенерации
    (`RazorCodegenCorpusTest.test*TagHelpers`: проект как в сборке, дескрипторы — `razordump tag-helpers` той же компиляции) — eShop
    WebApp 18 / 18, WebAppComponents 3 / 3, MudBlazor 165 / 165, Nop.Web 986 / 986; с нашими дескрипторами из харнесса — так же (порядок
    дескрипторов между сборками у нас другой и на этих файлах ни на что не влияет; внутри сборки — как у компилятора, а он важен: у
    `FormTagHelper` и `RenderAtEndOfFormTagHelper` одного `<form>` от него зависит порядок полей). Тест `RazorTagHelpersTest` на фикстуре
    `AspNetCoreComponents.dnix` (`tools/index-fixture/components` — заглушка API компонентов под настоящим именем сборки). Найдено:
    движки документа держали `VirtualFile` корня проекта — после удаления и восстановления папки обращались к недействительному.
  - [x] **R5e. Редактор** (0.1.286; проверено роботом в WSL): completion тегов и атрибутов, переход тег → компонент, Find Usages и rename, tag helpers `.cshtml`, площадка.
    - [x] Часть 1 (0.1.265): `RazorTagHelperEditor` — контекст tag helpers документа (`TagHelperDocumentContext` из того же прогона движка,
      что и документ C#, хранится в `RazorCSharpDocument.Generated`), кандидаты для тега по правилам сопоставления (полное имя — ниже,
      `ChildContent` — только внутри родителя), completion тегов и атрибутов (HTML-контрибутор), Ctrl+B тег → `.razor` / класс C# / тип
      сборки (`AssemblyNavigation`), атрибут → свойство-параметр; имена в разметке не инъектированы как C#, цель одна. Тесты —
      `RazorTagHelpersTest`, площадка — `RazorComponents.razor` (`TYPE:razor-tag-completion`, `-attribute-completion`, `-tag-goto`).
      Проверено роботом в WSL; найдено (0.1.266): на повторном Ctrl+Space (`isExtendedCompletion`) `XmlCompletionContributor` дополняет
      имена тегов по схемам и зовёт `stopHere()` — в Razor без единого пункта; контрибутор зарегистрирован `first, before xml`. Площадка
      получила `@using Microsoft.AspNetCore.Components.Web`: `_Imports.razor` папки `Components` не доходит до `Editor`, и без него
      `@onclick` там не обработчик события — как у компилятора.
    - [x] Часть 2 (0.1.267): Find Usages и rename компонентов (файл и теги) и параметров. `RazorTagHelperUsages`: места разметки по
      дескрипторам документа, как у Ctrl+B — теги компонента (начальный и конечный, последнее имя у `<App.X.Counter>`), атрибуты параметра
      (`Step`, `Value` у `@bind-Value[:after]`, у наследников C#-класса тоже), теги `ChildContent` в своём компоненте; `ReferencesSearch`
      (`RazorMarkupReferencesSearcher`), Alt+F7 на теге / атрибуте (`RazorMarkupFindUsagesHandlerFactory`; на атрибуте цель даёт наследник
      `XmlTargetElementEvaluator`, зарегистрированный для XML: имя в разметке — токен XML, а платформенный атрибут целью не берёт), группы
      «Tag / Attribute in markup». Rename: Shift+F6 на теге / атрибуте / файле `.razor` (`RazorMarkupRenameHandler`; `XmlTagRenameHandler`
      отступает через `vetoRenameCondition`, иначе платформа спрашивала бы, какой rename), компонент `.razor` — файл и вложенные
      (`.razor.cs`, `.razor.css`), теги, code-behind, C#; параметр и класс C# — rename C# ядра, разметку пишет `CSharpRenameParticipant`
      (получил `occurrences` и `namedFiles`). Ядро: поиск C# читает C#, внедрённый в другие файлы (`CSharpInjections`: только текст кусков,
      склейка документа — `nameof(…)` параметров, класс вокруг — не использование; область поиска члена внедрённого файла — проект,
      `UseScopeEnlarger`), правки внедрённого C# пишутся в хост, вывод генераторов не правится (его сделают заново). Свойство `@code` связано
      со своим двойником в выводе генератора Razor (`Counter_razor.g.cs`): `.cs` называют члены компонента там. Тест —
      `RazorComponentUsagesTest`; площадка — `TYPE:razor-component-usages`, `-component-rename`, `-parameter-rename`. Find Usages
      члена `@code`, начатый из `.cs`, и Code Vision по разметке — сделаны в 0.1.286 (R4).
      Проверено роботом в WSL на площадке (0.1.268): Alt+F7 на `<Counter` — 2 тега и 4 места C# (`typeof`, `nameof`, параметр метода,
      `new Counter()` в разметке), на атрибуте `Step` — 6 мест; Shift+F6 на теге, на атрибуте, на `Step` в `@code` и на `Counter.razor` в
      Solution — без вопроса «какой rename», файл и все места, Ctrl+Z возвращает всё (и после Ctrl+S). Найдено и исправлено в 0.1.268:
      класс компонента в выводе генератора лежит в папке помощника вне проекта — разрешение из объявления `.razor` его не видит, ищется
      stub-индексом в `CSharpSourceScope`; член, объявленный и документом C# `.razor`, и выводом генератора, разрешался в оба — rename
      отказывал («not a symbol the built-in rename knows»), теперь берётся объявление своего файла (`CSharpSolutionSearch.targetAt`);
      rename в `@code` — платформа даёт `EditorWindow`, inplace-шаблон над кусками падал («Invalid offsets»), для внедрённого C# — диалог;
      гутер ставил «Overrides member» на `BuildRenderTree` склейки — ошибка IDE при каждом открытии компонента (маркеры только на тексте
      файла). Ограничение «второй rename того же компонента не находит C#» оказалось ошибкой плагина и исправлено в 0.1.269: переименованный
      (созданный, удалённый, перемещённый) `.razor` не доходил до помощника — генератор читал старый список дополнительных файлов, класса
      нового имени не было; теперь такой файл в папке проекта, чьи генераторы берут файлы его расширения (`takesFilesLike` по ответу
      помощника), перезагружает проект в помощнике. Проверено в WSL: `Counter` → `Tally` → `Counter`, вывод `Tally_razor.g.cs`, затем снова
      `Counter_razor.g.cs`, C# переименован оба раза. Там же — память помощника: на каждую загрузку проекта по копии всех сборок-ссылок
      (`MetadataReference.CreateFromFile`, неуправляемая память, освобождает только финализатор) и ссылок на анализаторы; теперь общий кэш
      по пути и метке файла (`MetadataCache`, `AnalyzerLoader.Reference`) и полная сборка мусора после запроса, сбросившего проекты.
      Площадка в WSL: база 584 → 527 МБ, восемь переименований в `Lib` (40 перезагрузок проектов) — 527 → 570 МБ (было 584 → 872 за два).
      0.1.280 (решение пользователя 2026-10-18: «как в Unity — сразу, не дожидаясь перекомпиляции»): класс созданного / переименованного /
      перемещённого `.razor` пишет сам плагин (`RazorGeneratedAhead`: `RazorCSharpDocument.compilerOutput` — движок без
      `RazorIdeLayoutPass`, имя файла по `GetIdentifierFromPath` генератора) в папку вывода генератора, вывод старого пути удаляется;
      помощник потом пишет тот же файл (текст может отличаться переводами строк и контрольной суммой — один переиндекс). Помощник:
      появившийся / ушедший / переименованный файл проекта больше не выбрасывает проект и ссылающиеся на него — design-time build,
      и если командная строка отличается только файлами, документы и дополнительные тексты драйвера правятся на месте, состояние
      генераторов остаётся (`UpdateFileSetAsync`; стенд — помощник по протоколу на MudBlazor: переименование `.cs` и `.razor` 15,7 с →
      3,7–5,1 с до свежего вывода трёх проектов, вывод файл в файл тот же).


    - [x] Часть 3 (0.1.281; проверено роботом в WSL): tag helpers `.cshtml` (`asp-*`). Заглушка MVC — `tools/index-fixture/mvc` (одна сборка под
      именем `Microsoft.AspNetCore.Mvc.TagHelpers`: `ITagHelper`, атрибуты discovery, `ModelExpression`, tag helpers `a`, `form`, `input`,
      `label`, `environment`, `partial` с настоящими целями и именами атрибутов) → `AspNetCoreMvc.dnix`. Discovery, контекст документа и
      completion атрибутов работали уже с R5d / R5e ч. 1 (дескрипторы вида `ITagHelper` ничем не отличались). Сделано: разрешение имён типов
      из `.cshtml` (`RazorTagHelpers.contextFile` — пустой лёгкий C#-файл проекта вида, у компонента — его объявление), Ctrl+B на атрибут
      tag helper всех элементов (`tooltip` — правило `*`), привязка разметки к tag helper по правилам компилятора с обязательными атрибутами
      (`RazorTagHelperEditor.applies`: `<a href>` — HTML, `<a asp-controller>` — `AnchorTagHelper`), `MarkupBinding.TagHelper` (Alt+F7 на теге
      ищет класс, Shift+F6 на теге — обычный rename HTML: имя тега не имя C#), атрибут tag helper — `MarkupBinding.Parameter` (Alt+F7, Shift+F6
      переименовывает свойство), `RazorTagHelperUsages`: теги и атрибуты tag helpers по их дескрипторам (`RazorTagHelperService.known()` и
      `tagHelpersNow` — дескрипторы проекта считаются по запросу, если ни один вид не открывали), слово индекса — самая длинная часть имени
      (`mail-to` → `mail`). Rename: точка ядра `CSharpRenameParticipant.Occurrence.spelling` — место пишет новое имя по-своему (`MailTo` →
      `mail-to` по `HtmlConventions.toHtmlCase`); атрибут с именем из `[HtmlAttributeName("…")]` и теги находятся, но не переименовываются.
      Тест — `RazorCshtmlTagHelpersTest`; площадка — `Blazor/Editor/CshtmlTagHelpers.cshtml` + `TagHelpers.cs` (`TYPE:cshtml-asp-completion`,
      `-own-completion`, `-goto`, `-usages`, `-rename`). Проверено роботом в WSL на площадке: completion `asp-*` (автопопап и Ctrl+Space),
      `<email`, `mail-to`, `tooltip`; Ctrl+B на теге, атрибутах и `asp-controller` (просмотр сборки); Alt+F7 на теге (4 имени тегов) и
      атрибутах; Shift+F6 на `mail-to` → `send-to` вместе со свойством, Ctrl+Z возвращает; `Text` с `[HtmlAttributeName]` — `tooltip`
      остаётся; Shift+F6 на теге — обычный rename HTML. Найдено и исправлено: инспекции HTML ругались «Unknown html tag email»,
      «Attribute tooltip is not allowed here», «Missing associated label» на `<input asp-for>` — `RazorHtmlInspectionSuppressor` теперь
      глушит по дескрипторам, которые привязаны к элементу (`applies`), а не по виду имени; в `RazorHighlighting.razor` XML считал
      `@bind:event` префиксом пространства имён («Namespace '@bind' is not bound», ошибка) — `XmlUnboundNsPrefix` тоже глушится. Члены модели в значении `asp-for`: генератор пишет
      `__model => __model.Name` с mapping на значение, но хостом инъекции был только C#-литерал — теперь `MarkupTextLiteral` тоже
      `RazorCodeLiteral` (хост, если mapping на него есть; `RazorCSharpDocument.hostOf` ищет лист в дереве Razor, а не `findElementAt`
      файла, который для разметки отвечает из HTML). Найдено по дороге: инъекция открытого файла, сделанная до прихода дескрипторов,
      у платформы живёт до правки файла (кэш по счётчику изменений PSI) — `RazorTagHelperService` при новых дескрипторах сбрасывает кэши
      инъекций открытых Razor-файлов и `dropPsiCaches`; `findElementAt` вида для разметки предпочитает дерево HTML, в чьём элементе
      инъекции нет — `RazorFileViewProvider.findElementAt` отдаёт лист Razor в значении атрибута, которое хостит C#
      (`hostsMarkupAt`; только значения, не имена атрибутов — те тоже отображены компилятором в `nameof(…)`, но остаются разметкой для
      Alt+F7 и Shift+F6); копия файла для completion несёт пробел после dummy-идентификатора, компилятор отображает значение с ним —
      хост без пробела. Проверено в WSL: в `asp-for="N"` Ctrl+Space даёт `Name [string]`, `N` — CS1061 как у компилятора, Ctrl+B на
      `Name` — свойство модели; `RazorComponents.razor` без ошибок.
      Хвосты закрыты в 0.1.286 (проверено роботом в WSL): автопопап в значении атрибута-C# (`RazorAttributeValueAutoPopup`: обработчик
      набора XML держал список закрытым — все `CompletionConfidence` в месте каретки отвечали «не знаю»); view components — дескрипторы
      были (`ViewComponentTagHelperProducer`), не работал completion: XML сравнивает только часть имени после `vc:` — префиксом стало
      всё набранное имя (`LatestNewsViewComponent` площадки: `<vc:latest-news count="3">`, Ctrl+B на класс; в тестах по-прежнему нет —
      в заглушке MVC их нет); дубли в completion — тег отдаёт HTML (его пункт со вставкой закрывающего тега), атрибут — мы (с типом;
      HTML предлагал неизвестному тегу атрибуты его других элементов в файле), tag helper элемента HTML по атрибутам (`<embed asp-…>`)
      тегов не предлагает; цвета — `RazorMarkupColorAnnotator`: тег компонента / view component / tag helper и привязанный атрибут
      жирные, в цветах класса и свойства Rider (`colorSchemes/RazorMarkup*.xml`; цвет класса платформы — цвет текста, тег было не
      отличить), ключи на странице цветов Razor; атрибуты-префиксы (`asp-route-id` → `RouteValues`) — использования в Find Usages, без
      переименования; Ctrl+B на компонент сборки (`<Router>` → `Router.cs` просмотра сборки) работал и так.

- [x] **R6. Ошибки** (2026-10-18, 0.1.282; проверено роботом в WSL). Своих проверок не понадобилось: порт компилятора (R2 парсер, R3b
  проходы, R5 дескрипторы) уже даёт диагностики компилятора — гейты сверяют их с ним (`RazorCodegenBaselineCorpusTest` по
  `.diagnostics.txt`, `RazorCodegenCorpusTest.test*TagHelpers` по разделу `### diagnostics` razordump на eShop / MudBlazor / Nop.Web).
  Это вместо корпуса `CompilerMessagesCoverageTest`: тот — по страницам документации C#, здесь оракул — сам компилятор Razor на
  корпусе. Показ: `RazorErrorsAnnotator` (`RazorErrors.kt`) — диагностики C#-документа файла с его id, текстом, серьёзностью и
  местом компилятора (RZ10012 — на весь элемент, как у компилятора); диагностики импортов — в их файле; пока дескрипторы проекта не
  найдены, только синтаксис парсера (RZ1000–RZ2999), иначе каждый компонент был бы RZ10012. Ошибки C# в разметке — инъекции (как и
  были). Найдено в WSL: семантика C# разметки молчала, пока не открыт `.cs` проекта (помощник запускал генераторы только на открытие
  C#) — теперь открытие любого файла проекта, у которого нет прогона, запускает генерацию (`CodeAnalysisService.opened`). Тест —
  `RazorErrorsTest`; площадка — `Blazor/Editor/Broken/RazorErrors.razor` (`TYPE:razor-errors`, `Editor/Broken` вне сборки).
  Старый `RazorEditorTest.testNoErrorsOfHtmlNextToRazor` получил честное RZ1006: `<span>` без `</span>` в блоке `@if` делает `}`
  разметкой — так и у компилятора.
- [x] **R7. Набор и формат** (2026-10-19, 0.1.283; проверено роботом в WSL). Проба в WSL: автозакрытие тегов (и компонентов) и
  автопопап на `@co` работали и так (HTML платформы, C# инъекции); Enter после `{` в `@code`, `@if` и методе давал отступ на уровень
  меньше, Reformat Code не делал ничего. Сделано: `RazorIndent` — C#-тень файла (те же строки: код блоков `@code` / `@{ }` / `@if { }`
  как есть, открывающий тег элемента — `x {`, закрывающий — `}`, тег без конца — `x;`, директивы, выражения разметки, текст и
  комментарии — пусто; ведущие пробелы строки — исходные), отступ строки по правилам C# плагина (`CSharpIndentRules`, `.editorconfig`).
  `RazorLineIndentProvider` — Enter (Razor, C# Razor и HTML файла Razor); `RazorFormattingService` — Reformat Code: переотступ строк
  сверху вниз (пустые строки без пробелов; строки внутри комментариев, `<pre>`, `<textarea>`, `<script>`, `<style>` не трогаются;
  дети элементов из Code Style | HTML «Do not indent children of» — `html`, `body`… — без отступа, как HTML IDE). Внутри строк текст
  не меняется (пробелы C#, переносы атрибутов — нет: только отступы). Тест — `RazorIndentTest`; площадка — `Blazor/Editor/RazorTyping.razor`
  (`TYPE:razor-enter-*`, `razor-new-code`, `razor-autoclose`, `razor-reformat`); Reformat на всех `.razor` / `.cshtml` площадки —
  без изменений, кроме детей `<body>` в `App.razor` (по настройке HTML).
- [x] **R8. Отладчик** (2026-10-19, 0.1.285; проверено роботом в WSL). Точки в `.razor` / `.cshtml`: отладчик шлёт их по пути файла,
  как у `.cs` (pdb отображает C# генератора на файл через `#line`), стек и позиции кадров по пути работали и так. Сделано: точка ядра
  `breakpointLines` (`BreakpointLinesSource`: строки файла фреймворка, где можно поставить точку) и `RazorBreakpointLinesSource`:
  строки, где начинается source mapping C# (выражение разметки, оператор `@if` / `@foreach`, код `@{ }`), кроме директив (`@page`,
  `@using`, `@inject`), а в `@code` / `@functions` — по правилам строк `.cs` (`CSharpBreakpointLines` над тенью блока). Проверено:
  точка на `<p>Current count: @count</p>` подтверждена адаптером ровно на `@count`, остановка на запрос страницы (пререндер),
  кадр `Counter.BuildRenderTree()` в `Counter.razor:4`, переменные `this` / `__builder`, Evaluate `count` → 0, повторная остановка
  после Resume. Показалось и не про Razor: «второй сеанс в WSL теряет `stopped`» — разобрано 2026-10-19, это робот запускал конфигурацию
  в обход «Stop and Rerun» платформы, и запрос уходил в старую программу на том же порту (`DAP_PLAN.md`, «Закрыто»). По дороге: остановку, которую не удалось показать, клиент глотал молча — теперь пишет в журнал
  и показывает как паузу. Тест — `RazorBreakpointsTest`; площадка — `BP:razor-markup` в `Blazor/Editor/RazorRelated.razor`. Hot Reload
  уже применяет «C# and Razor changes». Только Blazor Server / SSR / MVC; WASM — вне плана.
- [x] **R9. Действия и навигация** (2026-10-19, 0.1.284; проверено роботом в WSL). New → «Razor and Blazor» (Component, Component with
  Code-Behind, Razor Page с PageModel, View, Layout, `_Imports.razor`, `_ViewImports.cshtml`) уже было в ядре (`ItemTemplates`,
  категория `RAZOR`, видна в проектах с Razor SDK). Сделано: Ctrl+Alt+Home между `X.razor` / `.razor.cs` / `.razor.css` / `.razor.js`
  и `X.cshtml` / `.cshtml.cs` (`RazorGotoRelatedProvider`); маршруты в окне Endpoints — новая точка ядра `endpointSource`
  (`EndpointSource`, ядро сканирует C# само и спрашивает источники о прочих файлах) с первой реализацией `RazorEndpoints`: каждый
  `@page "/x"` компонента, у Razor Page — путь под `Pages/` (`Areas/<a>/Pages/…` → `/<a>/…`, `Index` — папка) плюс шаблон её `@page`
  (или он сам, если начинается с `/`); Alt+Enter на RZ10012 — «Add @using Ns» по компонентам проекта с таким именем (`RazorAddUsing`:
  строка после `@using` файла, иначе после ведущих директив); Refactor → Extract Component (`RazorExtractComponentAction`): выделенные
  строки разметки → `Name.razor` рядом без общего отступа, на их месте `<Name />`, одна команда с глобальным undo (найдено в WSL: файл
  создавался вне команды — Ctrl+Z его не убирал), выделение, которое режет комментарий / блок / тег, отклоняется (разбор куска своим
  парсером без синтаксических ошибок; тоже найдено в WSL). Параметры из C#, который читает разметка, не создаются (как ограничение:
  такие имена в новом компоненте красные). Тест — `RazorActionsTest`; площадка — `Blazor/Editor/RazorRelated.razor` (+ `.razor.cs`,
  `.razor.css`) и `Blazor/Pages/Privacy.cshtml` (`TYPE:razor-related`, `-endpoints`, `-add-using`, `-extract`).

Порядок: F0 → R0 → R0b → D6e `CSHARP_PSI_MIGRATION.md` (упрощение семантики: трасса «почему», харнесс по файлу, закат старых правил —
решение 2026-10-15, до включения ошибок Razor и до R1) → R1 → R2 → R3 → R4 / R5 / R6 параллельно → R7, R8, R9. Каждый шаг полезен сам по себе: после R0 — веб-проекты без
ложного молчания в `.cs`; после R1 — читаемый Razor; после R3–R4 — основная ценность.

## Площадка

`debug-playground/Blazor` (Blazor Web App, net10.0: компоненты с `@code`, generic-компонент, `@bind`, `EventCallback`, cascading,
`.razor.cs`, `_Imports.razor`) и `debug-playground/Mvc` (контроллер + представления с `@model`, layout, section, partial, tag helpers,
Razor Pages с `PageModel`) — в solution, собираются с 0 ошибок; маркеры `// TYPE:` в C#-кусках и `@* TYPE: … *@` в разметке,
`// BP:` в `@code` и в выражениях разметки. Ошибки — `debug-playground/Broken/Razor`. Чек-лист — в `debug-playground/README.md`.

## Риски

- **Инъекция и хост.** Семантика хоста рассчитана на физический `.cs` проекта; если R3a покажет, что инъекция требует переделки
  половины `lang/semantic`, — свой виртуальный файл и перенос позиций своими руками (дороже в редакторе, дешевле в семантике).
- **Внутренние API компилятора Razor** для `razordump`: дерево и design-time вывод частично `internal` — отражение или
  `IgnoresAccessChecksToAttribute`, как это решено у `roslyndump`.
- **Генератор и Roslyn помощника** при смеси SDK (проект на более новом SDK, чем помощник): молчать по-старому и говорить об этом в
  логе, а не падать.
- **Производительность**: виртуальный документ пересобирается на каждое изменение — по кускам, с кэшем по `modificationStamp`;
  бенчмарк на самом большом файле MudBlazor.
- **Tag helpers** требуют аргументов атрибутов в индексе сборок — формат индекса меняется для всех пользователей (переиндексация).
