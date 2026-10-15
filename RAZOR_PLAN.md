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
- [ ] **R0b. Ложные ошибки ядра, найденные на корпусе Razor** — потом `errorsAfterGeneration = true`. Это правки ядра C# (`lang/semantic`),
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
- [ ] **R1. Язык и подсветка** (1–2). Типы файлов `.razor` / `.cshtml`, лексер Razor (переходы `@`, `@(…)`, `@{…}`, ключевые слова и
  директивы, `@* *@`, `@@`, `<text>`, email-эвристика `a@b`), HTML как template data, C#-куски лексером csharp-psi для цвета, folding
  (`@code`, `@{}`, `@section`, теги), парные скобки, комментарий Ctrl+/, структура (члены `@code`, секции). Сценарии площадки.
- [ ] **R2. Парсер и гейт дерева** (2–3). `razor-psi-core`: перенос парсера, оба вида файла, восстановление после ошибок как у
  компилятора (RZ1xxx синтаксиса). `tools/razor/razordump tree`. Гейты: 0 расхождений с `razordump tree` на корпусе (MudBlazor,
  nopCommerce, eShop, отдельные файлы тестов компилятора), метрики `testData/metrics/razor-*-tree.json`; мутационный фазз — 0
  исключений.
- [ ] **R3. Виртуальный C#-документ** (3–4).
  - R3a. Развилка (одна сессия, решение записать сюда): инъекция из многих кусков против своего `LightVirtualFile` с переносом позиций.
    Решает, работают ли `lang/semantic`, completion, navigation, rename на инъецированном файле: многое в хосте спрашивает
    `projectOf(file)`, `CompilationModel` по физическому `.cs` — нужен контекст хоста. Проба — completion после `@Model.` и
    Go to Declaration из разметки.
  - R3b. Генерация документа по образцу design-time режима компилятора, оба вида файла; `_Imports.razor` / `_ViewImports.cshtml`
    по иерархии папок, `@model` → `RazorPage<TModel>`, `@page` Razor Pages → `PageModel` из `.cshtml.cs`, partial-класс из `.razor.cs`.
  - Гейт: имена и типы C#-выражений в разметке против `roslyndump semantics` на выводе `razordump designtime` — тот же порог, что у
    `semanticGate` для `.cs`; 0 ложных ошибок на трёх solution.
- [ ] **R4. C# в разметке** (2). Completion (члены, `@` в разметке — выражения и директивы, параметры `@inject`), Go to Declaration,
  Find Usages и rename в обе стороны `.cs` ↔ `.razor`, цвета ссылок, quick documentation, parameter info, inlay hints. Учёт
  использований в разметке там, где хост решает «не используется» (серые члены, `CSharpSemanticWarnings`).
- [ ] **R5. Компоненты и tag helpers** (3). Теги компонентов в completion (solution и сборки, с `@using` в области), атрибуты —
  `[Parameter]` с типами, `@bind-Value` / `ValueChanged`, `EventCallback`, `RenderFragment` дочерним содержимым, generic-компоненты
  (`TItem` по аргументам), `@ref`; переход тег → `.razor` / тип сборки, Find Usages и rename компонента (файл и теги). Tag helpers
  для `.cshtml` после подъёма формата индекса: `asp-*`, свои, `@addTagHelper`. Гейт: `razordump` отдаёт привязки тегов
  (`.tspans` / tag helper descriptors) — наши совпадают на корпусе.
- [ ] **R6. Ошибки** (2). Синтаксис Razor (RZ1xxx) — свой парсер; RZ9xxx / RZ10xxx (неизвестный параметр, неизвестный компонент как
  предупреждение RZ10012, `@bind` без пары) — своими проверками, сверка с `razordump diagnostics`; C#-ошибки в разметке — та же
  семантика. Корпус `CompilerMessagesCoverageTest` пополнить RZ-кодами (примеры со страниц документации Razor) и
  `debug-playground/Broken`. На время до R6 — RZ-ошибки из вывода помощника (R0) по `#line`, после сохранения.
- [ ] **R7. Набор и формат** (2). Отступы (правила `csharpIndent` для C#-кусков, HTML — платформа), автозакрытие тегов компонентов,
  `@` → popup completion, Enter в `@code { }`, Reformat (C# — свой форматтер по кускам, HTML — платформа; сверка с форматтером
  Razor из сервера по снимкам `capture-5.12-razor` — по мере возможности).
- [ ] **R8. Отладчик** (1). Точки останова в `.razor` / `.cshtml` (pdb ссылается на них через `#line`; `canPutAt` по строкам
  C#-кусков), стек и шаги в `.razor`, Evaluate в контексте компонента. Hot Reload уже применяет «C# and Razor changes». Только
  Blazor Server / SSR / MVC; WASM — вне плана.
- [ ] **R9. Действия и навигация** (1–2). Extract Component, «Create component», Alt+Enter «Add @using», переход `.razor` ↔
  `.razor.cs` ↔ `.razor.css`, Razor Pages в Endpoints (`@page "/route"`), New → .NET уже есть.

Порядок: F0 → R0 → R0b → R1 → R2 → R3 → R4 / R5 / R6 параллельно → R7, R8, R9. Каждый шаг полезен сам по себе: после R0 — веб-проекты без
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
