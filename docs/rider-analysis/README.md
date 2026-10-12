# Анализ Rider 2026.2.3.1: меню, настройки, подсветка, completion, помощь при наборе

Снято UI-роботом 2026-10-04 с эталонного Rider **2026.2.3.1** (`RD-262.10968.170`). Rider запускался изолированно:
`start-rider.ps1`, копия настроек в `%USERPROFILE%\rider-robot-2026`, порт робота 8594. Проект — `build/ui-robot/rider-playground`,
копия `debug-playground`. В копию временно клали пробный файл `Console/Editor/RiderAnalysis.cs`, после съёмки удалили; копия
сверена с резервной и совпадает.

Условия съёмки:
- Раскладка клавиатуры (keymap) взята из пользовательского Rider: **Visual Studio 2022**. Отсюда Ctrl+T — Search Everywhere,
  Ctrl+R,R — Rename, F12 — Declaration.
- Тема — Islands Dark.
- Лицензия — trial.

Дополняет [`docs/RIDER_REFERENCE.md`](../RIDER_REFERENCE.md): там пробы completion в `RiderProbe.cs` (возврат `Task`,
`CancellationToken`), здесь их не повторяю.

**Как повторить.** Скрипты лежат в `tools/ui-robot/rider/analysis/`:
- `rj.sh` — помощники `rj`, `comp`, `pop`, `activate`, `escape`;
- драйверы `completions.sh`, `typing.sh`, `popups.sh`, `settings.sh`;
- JS для Rhino: `complete2.js`, `typing.js`, `popup.js`, `menu.js`, `settings_page.js`, `code_style_sections.js`, `highlights.js`,
  `codevision.js`, `colors.js`, `actions_tree.js`, `settings_tree.js`;
- пробный файл `RiderAnalysis.cs`.

Перед прогоном: `export RIDER_PID=<pid rider64.exe>`, затем скопировать `RiderAnalysis.cs` в `rider-playground/Console/Editor/`.

Подводные камни (проверено на практике):
1. Popup и completion приходят пустыми, пока окно Rider не на переднем плане. `focusProjectWindow` Windows блокирует, поэтому
   окно выводится вперёд через `WScript.Shell.AppActivate(pid)`.
2. Попапы Alt+Enter / Generate / Refactor This / Navigate To — «модальные popup» Rider (`RiderModalPopupCookie`). Если закрыть
   такой попап сменой каретки или фокуса, EDT однажды повис навсегда (в `ModalContext.dispose`), и Rider пришлось убить.
   Закрывать только клавишей Escape (`SendKeys`).
3. `ActionUtil.invokeAction` попап не открывает, а `ActionManager.tryToExecute(...)` открывает. Каретку нужно ставить отдельным
   вызовом за 2 с до действия.
4. Быстрая проба completion — не больше ~10 с. Восстанавливать файл точечной правкой (`replaceString`), а не `setText` всего
   документа: иначе после каждой пробы Rider заново анализирует весь файл.

## Файлы

| Файл | Что внутри |
|---|---|
| `dumps/main-menu.txt` | Всё дерево `MainMenu` из `ActionManager`: текст, id действия, сочетание клавиш, `[hidden]` / `[disabled]` в контексте редактора (1128 действий) |
| `dumps/main-menu-visible.txt` | Меню верхнего уровня, как их видит пользователь: собраны `createActionPopupMenu` с контекстом редактора |
| `dumps/toolbar.txt` | `MainToolbarLeft` / `Center` / `Right`, `MainToolbarNewUI`, `RunToolbarMainActionGroup`, quick actions |
| `dumps/editor-popup.txt`, `dumps/solution-explorer-popup.txt` | Статические группы ПКМ редактора, вкладки, гаттера и Solution Explorer (`SolutionExplorerPopupMenu*`) |
| `dumps/alt-enter-generate-refactor-navigate.txt` | Живые списки Alt+Enter в 12 местах, Generate (Alt+Insert), Refactor This, Navigate To, ПКМ редактора |
| `dumps/generate-refactor-navigate-static.txt` | Статические группы тех же действий; у Rider они почти пустые, списки строит бэкенд |
| `dumps/settings-tree.txt` | Всё дерево Settings: имя страницы, id, класс (416 страниц) |
| `dumps/settings-pages.txt` | Опции 28 страниц C# / .NET: флажки с состояниями, переключатели, значения combo |
| `dumps/settings-code-style-csharp.txt` | Code Style C# по всем 11 разделам: имя опции = значение |
| `dumps/color-keys-csharp.txt` | Все ключи цвета страниц «Language Defaults» и «C#»: имя ↔ `TextAttributesKey`, цвет в схеме, demo-текст |
| `dumps/highlight-*.txt` | Подсветки редактора по диапазонам: ключ, слой, подсказка; inlay hints; сводка ключей. Файлы: `RiderAnalysis.cs`, `Types.cs`, `Web/Program.cs`, `CompletionRanking.cs` |
| `dumps/code-vision-gutter.txt` | Тексты code vision по строкам с id провайдера; иконки гаттера с подсказками |
| `dumps/completion.txt` | 75 проб completion: что набрано, вид вызова, первые пункты с типом и иконкой, вставленный текст |
| `dumps/typing-assists.txt` | 21 проба набора: состояние строки после каждого символа |
| `img/*.png` | 47 картинок, только компоненты IDE: окно, тулбар, Solution, меню, попапы, редактор, lookup, страницы Settings |

## 1. Меню, тулбар, контекстные меню

Главное меню в новом UI (кнопка-гамбургер, `MainMenuWithButton`): **File, Edit, View, Navigate, Code, Refactor, Build, Run,
Tests, Tools, VCS, Window, Help**. Картинки — `img/10-menu-*.png`. Меню Refactor и Build Rider заполняет асинхронно (с бэкенда),
поэтому собранная вручную копия показывает «Nothing here». Их состав — в `main-menu.txt`.

| Меню | Что в нём для C# / .NET (видимые пункты) |
|---|---|
| Code | **Override Members**, **Implement Missing Members** (Ctrl+Shift+I), Generate Code… (Alt+Insert), Code Completion ▸, Inspect Code (Alt+F11), Analyze Code ▸, Insert Live Template (Ctrl+K,X), Surround with Template, Unwrap/Remove, Folding ▸, комментарии, **Reformat Code** (Ctrl+Alt+Enter), **Reformat and Cleanup…** (Ctrl+E,C), Silent Cleanup (Ctrl+E,F), Optimize Imports (Ctrl+R,G), Rearrange Code, Move Statement / Line Up/Down/Left/Right |
| Navigate | Search Everywhere, Class / File / Symbol / Text, Line:Column, **Endpoint…**, Next/Previous Highlighted Error, Last/Next Edit Location, Navigate in File ▸, Select In, Declaration or Usages, **Related Files**, File Member, **Navigate To…** (Alt+\`) |
| Tests | Unit Testing Quick List, Run / Debug / Cover / Profile Unit Test, Run / Cover / Profile Current Session и All Tests, Repeat / Rerun Failed, Run Until Fail, Append Tests to Session, Create Unit Test |
| Run | Run / Debug / Profile выбранной конфигурации, Attach to Process (в том числе Remote, Unity, Unstarted), Debug / Open Core Dump, Profile Running Process, Edit Configurations, Toggle Breakpoint ▸, Stop On Exception, Import Tests from File, Manage Coverage Reports |
| Tools | **NuGet ▸**, IL Viewer, Assembly Explorer, Open Roslyn Visualizer, C# Interactive ▸, Generate GUID, Entity Framework Core ▸, Architecture ▸, HTTP Client, Database, Qodana, Analyze Stack Trace |
| View | Tool Windows ▸, Quick Definition, Quick Type Definition, Parameter Information, Context Info, Recent Locations / Files / Changes, Compare With |
| Edit | Find ▸, Find Usages ▸, Column Selection, Extend / Shrink Selection, Join Lines, Duplicate Line, Sort / Reverse Lines, Macros, Bookmarks |

**Тулбар** (`img/02-main-toolbar.png`, `dumps/toolbar.txt`):
- слева — Back / Forward, виджет проекта, VCS-ветка;
- справа:
  - `BuildSolutionBar` — split-кнопка «Build whole solution» с меню;
  - Run Widget — выбор конфигурации (`Web: http`), Run, Debug, «⋮» с Profile;
  - JetBrains AI, Search Everywhere, Settings.

Отдельного комбобокса Debug/Release на тулбаре нет. Конфигурация solution выбирается из Build-виджета, а действие
`ShowSolutionConfigurationOnToolbarAction` по умолчанию выключено.

Tool windows: Explorer (id `Project`), Structure, Unit Tests, NuGet, Build, Problems View, Endpoints, IL, Assembly Explorer,
dotTrace / dotMemory / dotCover, Monitoring, Database, Services, Terminal, Bookmarks, TODO, Commit / Version Control.

**Контекстные меню, которые собирает бэкенд** (`alt-enter-generate-refactor-navigate.txt`, картинки `img/20…26`):

| Действие | Где | Пункты |
|---|---|---|
| Alt+Enter | `class Circle : ShapeBase` (ошибка) | **Implement missing members**, Make class abstract, Delegate implementation to new field, To internal, Move to 'Circle.cs', Create derived type, Add `<inheritdoc />`, Default constructor, Make partial, Create unit test, Copy type…, затем навигация: «has base types», Type Hierarchy, Navigate To / Refactor This / Inspect This / Generate Code |
| Alt+Enter | параметр конструктора `title` | **Introduce read-only field '_title'**, **Introduce get-only auto-property 'Title'**, Remove parameter, Check parameter for null, Create overload without parameter, Named arguments, Transform parameters, To factory method, Chop parameters list, Value Destination / Origin |
| Alt+Enter | `string.Format(...)` | Use string interpolation, Add exception documentation, Convert to concatenation, Import static members |
| Alt+Enter | `$"Order {…}"` | Capture class name, Split string, To verbatim / raw interpolation, To unicode escapes, To string.Format / StringBuilder / concatenation, Mark as Route template, Inject language |
| Alt+Enter | `var unused = 42` | Remove not accessed local variable, Use discard, Split declaration, CS0219 |
| Alt+Enter | `nullable.Length` | Check expression for null, Assert not null, Suppress with `!`, CS8602 |
| Alt+Enter | `if (...)`, `from o in …`, `(string)boxed` | Invert if, Convert to switch, Add braces; Convert LINQ to method chain; To safe cast |
| Alt+Enter | авто-свойство / метод | To property with backing field, To computed property, To private / abstract / virtual, Make partial, Change signature, Make static, Incoming / Outgoing Calls |
| Alt+Enter | `using` в начале файла | Remove unused directives, Sort usings, Convert to global using, список implicit usings из `GlobalUsings.g.cs`, Disable implicit usings |
| Generate (Alt+Insert) | внутри класса | Constructor, Read-only properties, Properties, Missing members, Overriding members, Delegating members, Partial members, Deconstructor, Equality members, Equality comparer, Relational members, Relational comparer, Formatting members, Dispose pattern, Unit Test |
| Refactor This (Ctrl+Shift+R) | метод `Place` | Change Signature, Inline Method, Rename, Safe Delete, Extract Interface, Extract Superclass, Extract Class, Extract Members to Partial, Push Members Down, Convert method to indexer, Make Static, Transform Parameters |
| Navigate To (Alt+\`) | символ | Declaration, Implementation, Base Symbols, Find Usages, Related Files, Type of Symbol, Derived Symbols, Extension Methods, Related Tests, Show Usages, Consuming APIs, Exposing APIs, Windows Explorer, Referenced Code |

У каждого пункта Alt+Enter справа есть превью правки (окно «Preview»). Действия ReSharper помечены
«Rider inspection: …», анализаторы — «Roslyn analyzer: …», ошибки компилятора — «Compiler warning: CSxxxx».

## 2. Настройки

Дерево целиком — в `dumps/settings-tree.txt`, опции — в `dumps/settings-pages.txt` и `dumps/settings-code-style-csharp.txt`,
картинки — `img/40…51`.

| Страница | Главное (значения по умолчанию у этой копии настроек) |
|---|---|
| Editor \| Code Style \| C# | 11 разделов: Tabs/Indents/Alignment, **Naming**, **Syntax Style**, Braces Layout, Blank Lines, Line Breaks and Wrapping, Spaces, Null Checking, XML documentation, File Layout, Other. Пример Syntax Style: `var` везде; `this.` не ставить; `new()` там, где тип очевиден; тело методов — block, свойств — expression; file-scoped namespace; `not null`-паттерн; braces у `if` «если требует любая часть». Справа — превью, кнопка «Auto-Detect Code Style Rules» |
| Editor \| Inlay Hints \| C# | Подстраницы: **Parameter Name Hints** (скрывать для не-литералов, но показывать для вызовов, лямбд, неясных `new`, констант и enum; скрывать для builder-методов, суффиксов-номеров, «понятно из имени метода»; xUnit `InlineData` / NUnit `TestCase`; список исключений по шаблонам), **Type Name Hints** (компактные имена, скрывать, если тип очевиден из имени; типы возврата в цепочках вызовов), Type Conversion Hints, Interceptor Hints, Other (code annotations, missing constructs, MustDisposeResource) |
| Editor \| Code Vision | Enable Code Vision; позиция Above / Next to declaration; лимит 5 метрик; провайдеры: Usages, Derived symbols, Exposing API, Extension methods, Covering tests, VCS Info / Code author, IL code, Rename / Change signature, LSP Code Lens |
| Editor \| General \| Typing Assistance | Auto-format на `;` и на `}`; форматирование при вставке (Indent); исправление типичных опечаток; structural remove; smart indent; авто-пары скобок и кавычек; обрамление выделения; авто-`}` при наборе `{`; Tab — структурная навигация; C#: аннотации nullability на `!` / `?`, null-check на `!`, **заготовка doc-комментария на `/`** |
| Editor \| General \| Code Completion | В 2026.2 страница общая: Popup (Match case: первая буква) и Inline. Отдельной C#-страницы, как раньше, нет |
| Editor \| General \| Auto Import | Popup «Import missing references», **import items в basic completion**, исключения и типы, требующие полного имени |
| Editor \| Live Templates \| C# | 83 шаблона, редактор бэкенда (`#if`, `prop`, `ctor`, `for`…; «Use in: Generation / Surround / Both», «Show in context action») |
| Editor \| Inspection Settings | Solution-wide analysis, чтение `.editorconfig` и ruleset, оптимистичный / пессимистичный value analysis, **Color identifiers**, подсветка спецсимволов строк, захваченных primary-параметров, usages под кареткой, точек выхода, пар async/await |
| Editor \| Inspection Severity \| C# | Дерево инспекций C# с уровнями (картинка `49`) |
| Editor \| Members Generation | Тело по умолчанию: `throw new NotImplementedException()` / default / некомпилируемое; свойства — авто / с полем / с телами; документация |
| Editor \| Color Scheme \| C# | 59 ключей — см. раздел 3 |
| Build \| Toolset and Build | Версия MSBuild, глобальные свойства, лог MSBuild в окно и в файл |
| Build \| NuGet | Prerelease / unlisted, размер страницы поиска 300, поведение с зависимостями, restore, Smart Restore on Build, формат по умолчанию |

## 3. Подсветка в редакторе

Rider красит C# **семантическими ключами бэкенда** `ReSharper.CSHARP_*` поверх лексера. Полный каталог — 59 ключей страницы
«C#» и общие «Language Defaults», с цветами Islands Dark — в `dumps/color-keys-csharp.txt`. Картинки — `img/30…33`.

| Вид | Ключ (`ReSharper.CSHARP_…`) | Замечено на площадке |
|---|---|---|
| Класс / static class / record / record struct / struct / interface / enum / delegate / type parameter / attribute / alias | `CLASS_`, `STATIC_CLASS_`, `RECORD_`, `RECORD_STRUCT_`, `STRUCT_`, `INTERFACE_`, `ENUM_`, `DELEGATE_`, `TYPE_PARAMETER_`, `ATTRIBUTE_`, `ALIAS_IDENTIFIER` | да, все виды типов, кроме alias |
| Namespace | `NAMESPACE_IDENTIFIER` | да |
| Метод: объявление / вызов, static-объявление / static-вызов, extension-объявление / extension-вызов, local function, accessor (`get` / `set`), overloaded operator | `METHOD_DECLARATION_`, `METHOD_CALL_`, `STATIC_METHOD_DECLARATION_`, `STATIC_METHOD_CALL_`, `EXTENSION_METHOD_DECLARATION_`, `EXTENSION_METHOD_CALL_`, `LOCAL_FUNCTION_`, `ACCESSOR_`, `OVERLOADED_OPERATOR` | да (кроме local function и operator) |
| Поле / static-поле / константа, свойство / static-свойство, событие | `FIELD_`, `STATIC_FIELD_`, `CONSTANT_` (жирный), `PROPERTY_`, `STATIC_PROPERTY_`, `EVENT_IDENTIFIER` | да |
| Локальная / **изменяемая локальная**, параметр, параметр primary-конструктора (и захваченный), имя элемента кортежа, `dynamic` | `LOCAL_VARIABLE_`, `MUTABLE_LOCAL_VARIABLE_` (подчёркнута), `PARAMETER_`, `PRIMARY_CONSTRUCTOR_PARAMETER_[CAPTURED_]`, `TUPLE_COMPONENT_NAME`, `LATE_BOUND_IDENTIFIER` | локальные, параметры и primary-параметры — да |
| Ключевые слова: обычные, control flow (`if` / `for`), control transfer (`return` / `break`), встроенные типы | `KEYWORD`, `CONTROL_FLOW_KEYWORD`, `CONTROL_TRANSFER_KEYWORD`, `BUILTIN_TYPE_KEYWORD` | контекстные (`var`, `async`, `await`, `init`, `from` / `where`…) — в разметке |
| Строки: escape-последовательности двух цветов, плейсхолдеры формата | `ESCAPE_CHARACTER_1` / `_2` (`\t` и `\n` разными цветами), `ReSharper.FORMAT_STRING_ITEM` / `_2` / `MATCHED_FORMAT_STRING_ITEM` | да: `{0}`, `{1:N2}`, дырки `$"…"` |
| Regex в строке `new Regex(@"…")` | `REGEXP.META`, `REGEXP.PARENTHS` (языковая инъекция) | да |
| Маршрут ASP.NET (`"/orders/{id}"`) | `ReSharper.ASP_NET_ROUTE_TEMPLATE_PARAMETER`, `DEFAULT_HIGHLIGHTED_REFERENCE` | да, `Web/Program.cs` |
| Doc-комментарии | `DOC_COMMENT`, `DOC_COMMENT_TAG_NAME`, `_ATTRIBUTE`, `_ATTRIBUTE_VALUE` | да |
| Препроцессор, неактивная ветка `#if`, checked-операции | `PREPROCESSOR_KEYWORD`, `PREPROCESSOR_INACTIVE_BRANCH`, `OVERFLOW_CHECKED_OPERATION` | — |
| Неиспользуемое (серым) | `NOT_USED_ELEMENT_ATTRIBUTES` | 36 мест в одном файле: using, члены, параметры, accessor `set` / `init`, `virtual` без переопределений |
| Ошибки / предупреждения / подсказки | `ERRORS_ATTRIBUTES`, `WARNING_ATTRIBUTES`, `SUGGESTION`, `ReSharper.HINT` (точки) | см. ниже |

**Инспекции, сработавшие на `RiderAnalysis.cs`** (подсказки — в `highlight-…RiderAnalysis.txt`):
- серым: «Using directive is not required», «Enum member / Method / Property / Class is never used», «Virtual method is never
  overridden», «Parameter is never used», «Auto-property accessor 'Id.set' is never used», «Auto-property can be made get-only»,
  «Field is assigned but its value is never used», «Event is never subscribed to»;
- warning: «Content of collection '_orders' is never updated», «Local variable is only assigned», «Dereference of a possibly
  null reference»;
- suggestion: «Class 'Order' is never instantiated», «Method can be made private», «Use string interpolation expression»,
  «async method lacks 'await'»;
- hint: «Use collection expression», «Method can be made static» (плюс CA1822), «Convert to GeneratedRegexAttribute», «Use discard
  assignment»;
- error: «Abstract inherited member … is not implemented».

**Inlay hints** (`FrontendInlineHintRenderer`):
- тип у `var` (`local:string`, `query:IEnumerable<string>`), в том числе у переменной диапазона LINQ (`o:Order`);
- имена параметров у литералов (`quantity: 3`, `customer: "acme"`, `express: true`) и у `this` / `EventArgs.Empty` в `Invoke`
  (`sender:`, `e:`);
- у `local = input.Trim()` подсказки имени параметра нет.

**Code vision** (`BlockCodeVisionInlayRenderer` над объявлением, `code-vision-gutter.txt`):
- «N own usages» [Usages], «N inheritors / implementation(s)» [Derived symbols], «N own exposing APIs» [Exposing API];
- пока считается — заглушка [CodeVisionBackendLoadingProvider];
- у `Web/Program.cs` (top-level statements) code vision нет.

**Гаттер:**
- `HasImplementations` — «Interface 'IShape' is implemented»;
- `Implements` — «Implements method from 'IShape'»;
- `HasOverrides` — «Class is inherited»;
- `Overrides` — «Overrides method from 'System.Object'»;
- `OverridesAndImplements` — «has base types»;
- `Namespace` на первой строке — «Global 'using' directives»;
- `RunActions` у top-level `Program.cs`;
- лампочка / `ContextAction` на строке каретки;
- на полосе прокрутки — метки и счётчик «1 error / 11 warnings / 4 ok».

## 4. Completion (75 проб, `dumps/completion.txt`)

Обозначения: AUTO — автопопап после набора символа; BASIC — Ctrl+Space; SMART — Ctrl+Alt+Space (в VS-раскладке); `*` —
пункт жирный. Жирные пункты — члены самого типа и собственного класса, унаследованные от `object` — обычным начертанием. Тип
справа есть всегда.

| Где / что набрано | Вызов | Что дал Rider | Замечания |
|---|---|---|---|
| `name.` (`string?`) | AUTO | 124 пункта: `[]`, `Count`, `Length`, затем методы **по алфавиту**, все жирные | индексатор `[]` — отдельный пункт; LINQ-расширения ниже |
| пустая строка в методе | BASIC | параметры (`items`, `order`, `name`…), поля, свойства, методы класса, затем `object.*`, события, ключевые слова | 500+ пунктов; первыми — локальные |
| то же | SMART | 22 пункта: только значения (параметры, поля, методы) | — |
| `string.` | AUTO | `Empty`, `Compare`, `Format`, `IsNullOrEmpty`…; `~Copy` зачёркнут (Obsolete); шаблон `typeof` | статические члены |
| `Place(qu` | BASIC | **`quantity:`** первым, потом типы `Queue`… | именованный аргумент по префиксу |
| `Place(3, "acme", ` | BASIC | `Equals`, `false` / `true` (ждут `bool`), затем всё | — |
| `Place(3, "acme", true, ` | SMART | `status`, `OrderStatus.Cancelled` / `New` / `Paid` / `Shipped` со значением справа (`: 3`) | enum по ожидаемому типу |
| `new Order { ` и `{ Id = 1, ` | BASIC | только оставшиеся свойства: `Customer`, `Id`, `Lines`, `Status`, `Total` | уже присвоенные убираются |
| `Order o = new ` | SMART | единственный вариант **вставлен сразу без списка**: `new Order();` | — |
| `Order o = new ` / `var o = new Ord` | BASIC | `Order()` первым, затем все типы с `()`, неимпортированные с «(in Namespace)» | выбор вставляет `new Order()` |
| `status = OrderStatus.` | AUTO | члены enum со значениями, `GetValuesAsUnderlyingType`, `TryParse` | — |
| `if (status == ` | SMART / BASIC | `OrderStatus.*` первыми и в BASIC | — |
| `switch (status) { case ` / `status switch { ` | BASIC | `OrderStatus.*` первыми, в switch-выражении ещё `_` | — |
| `order is { ` | BASIC | свойства `Order` | property pattern |
| `x => x.` (int) | AUTO | члены `int` + шаблон `switch` | тип параметра лямбды выведен |
| `items.Where(` | SMART | **`int => {}`**, `(int, int) => {}`, **Create method / local function `Predicate(int)`** | лямбда и генерация метода |
| `Changed += ` | BASIC | `(object?, EventArgs) => {}`, **Create local function / method `OnChanged`** | обработчик события |
| `from i in items ` / `… where i > 0 ` | BASIC | первые 15 — значения (`q`, `name`…), ключевые слова LINQ ниже | в топе их нет |
| `nameof(` / `typeof(` | BASIC | значения / встроенные типы | — |
| `new List<` / `Dictionary<string, ` | BASIC | встроенные типы-ключевые слова, затем типы | — |
| `$"{order.Total:` | BASIC | **форматы чисел с примером**: `0000 - custom: 0123`, `C - currency: ¤1,234.45`, `N2`, `P`… | по типу `decimal` |
| `DateTime.Now.ToString("` / `string.Format("{0:` | BASIC | пусто | робот не снял: нужен автопопап внутри строки |
| `new Regex(@"\` / `@"(?` | BASIC | 45 конструкций regex с описанием: `\d - Decimal digit character`, `(?<name>…)`… | — |
| `this.` / `base.` | AUTO | члены класса; у `base.` — члены базы | — |
| `StringBuilder ` / `Order ` | BASIC | имена переменных: `builder`, `stringBuilder` / `order` | `foreach (var ` — пусто |
| `throw new ` | SMART | только исключения (181) | — |
| `catch (` | BASIC | исключения первыми | — |
| `JsonSeri` | BASIC | **неимпортированные** уже в первом Ctrl+Space: `JsonSerializer (in System.Text.Json)` | выбор не добавил `using` в видимой части: импорт — при коммите документа |
| `items.AsParallel().WithDegree` | BASIC | `WithDegreeOfParallelism : ParallelQuery<int>` | — |
| `items.fo` | BASIC | **postfix** `for`, `foreach`, `forr` (иконка LiveTemplate) рядом с `ForEach` | — |
| `items.foreach` → выбор | BASIC | `foreach (var item in items) { }`, каретка на `var` | шаблон с полями |
| `name.notnull` / `order.Total.var` / `name.return` | BASIC | `if (name != null) { }`, `var orderTotal = order.Total;` (имя выведено), `return name;` | — |
| `client.GetStringAsync("u").await` | BASIC | postfix `await` | — |
| `for` / `foreach` / `cw` / `try` / `if` | BASIC | live templates первыми, с описанием («Simple "for" loop») | `for (int i = 0; i < UPPER; i++) {}`, `foreach (var VARIABLE in COLLECTION)`, `Console.WriteLine();`, `if (expr) {}` |
| `prop` / `ctor` (уровень класса) | BASIC | `prop`, `propg`; `ctor`, `ctorf` | `public TYPE Type { get; set; }`; `public AnalysisSamples() { }` |
| `pu` (уровень класса) | BASIC | `public`, **`public override Equals(object?) { … }`** и др. | генерация override в списке |
| `override ` / `public override ` | BASIC | `Area() { … } : double` (жирный, abstract), `OnChanged`, `Name { get … }`, `Equals`… | выбор: `public override double Area() { throw new NotImplementedException(); }` |
| `[Obs` | BASIC | `Obsolete`, `ObsoletedOSPlatform (in …)` | — |
| `#` | AUTO | `#if`, `#region` (шаблоны), `#define`, `#endif`, `#nullable`, `#pragma`… | — |
| `using System.Coll` | BASIC | `Collections` | — |
| `string s = ` в async | SMART | `_mutable`, `String.*`, **`await Highlights : Task<string>`** | `await` прямо в пункте |
| `return ` в `int` | SMART | `_created`, `Count`, `MaxCount`, `Int32.MaxValue`… | — |
| `Task.Delay(100, ` | BASIC | **`token`** первым, `new CancellationToken()` | — |
| `Console.WriteLine(` | SMART | 97 значений | — |
| `name.IsB` | BASIC | extension `IsBlank` | вставка `name.IsBlank()` |
| `va` | BASIC | `var` первым | — |
| `/// <`, `/// <see cref="` | AUTO / BASIC | пусто | робот не снял; doc-шаблон — в разделе 5 |

## 5. Помощь при наборе (`dumps/typing-assists.txt`)

| Что набрано | Что получилось |
|---|---|
| `(` после `Place` | `Place(|)` — пара |
| `"`, `[`, `$"…{` | пары `""`, `[]`, `{}` внутри интерполяции; закрывающий символ «перешагивается» |
| `List` + `<int>();` | `<` пару **не** ставит; `(` ставит `()` |
| `{` + Enter после `if (…)` / заголовка метода | блок развёрнут на три строки, каретка с отступом |
| `///` над методом | `<summary>`, пустая строка с кареткой, `<param name="…">` для каждого параметра |
| `}` в `if (true) {    Place(1,"a");` | **авто-формат на `}`**: блок переформатирован, пробел после запятой |
| `;` после вызова | `Place(1, "a");` |
| Backspace после `(` | пара удалена целиком |
| Enter внутри `"hello world"` | строка не разрезана (простой перевод строки) |
| `.` после `name.Trim` | `()` не вставляется |
| Ctrl+Shift+Enter | через `EditorCompleteStatement` эффекта нет: у Rider своё действие на бэкенде, роботом не снято |

## 6. Чего не хватает плагину (приоритеты)

«Как сделать»:
- **синтаксис** — свой PSI (`csharp-psi-core`) или токены;
- **индекс** — индекс сборок `.dnix`;
- **семантика** — шаг 11 `CSHARP_PSI_MIGRATION.md`;
- **сервер** — то, что уже отдаёт roslyn-language-server.

| # | Есть в Rider | У нас сейчас | Чего нет | Как сделать |
|---|---|---|---|---|
| 1 | ~59 семантических цветов C#: static / extension / mutable local / параметр / primary-параметр / виды типов / namespace / константа / событие / accessor | 3 ключа (TYPE / METHOD / MEMBER) поверх semantic tokens сервера; локальные, параметры и namespace не красятся | разделение по видам и модификаторам, ключи `CSHARP_*` с fallback на Language Defaults, страница Color Scheme с этими именами | **сервер сейчас** (у Roslyn есть типы и модификаторы `static`, `extensionMethod`, `parameter`, `local`, `namespace`, виды типов) → потом семантика 11c |
| 2 | Escape-последовательности, плейсхолдеры `{0}` / `{x:N2}`, regex-инъекция, маршруты ASP.NET | нет | подсветка внутри строк | **синтаксис**: escape и `{…}` в интерполяции — лексер / PSI; `string.Format` / `Regex` — по имени вызываемого метода (эвристика), затем семантика; regex — `MultiHostInjector` с RegExp |
| 3 | Completion форматов `{x:` (числа, даты) и regex | нет | lookup со спецификаторами и примером | **синтаксис + индекс** (тип выражения перед `:`), список форматов — статическая таблица |
| 4 | Postfix-шаблоны в общем списке с иконкой, вывод имени (`var orderTotal`) | 24 своих postfix (токены) | имя переменной по выражению, `await` / `notnull` по типу | **синтаксис** (имя по последнему идентификатору), тип — семантика |
| 5 | Override-completion `override ` / `public override` с генерацией тела; `pu` → `public override Equals…` | только сервер (если отдаёт), серый текст отложен | генерация `public override T M() { throw … }` | **индекс** (члены базового типа из сборок) + свой PSI для своих типов |
| 6 | Object initializer / property pattern: только оставшиеся свойства | сервер | фильтр уже присвоенных | **сервер** (проверить) / синтаксис для фильтра |
| 7 | SMART: enum по ожидаемому типу, лямбда `int => {}`, «Create method», `await Method` | ранжирование по ожидаемому типу (эвристика) | отдельный SMART-вызов, пункты «Create method / local function», `await X` | семантика 11c; пока — пункты из signatureHelp сервера |
| 8 | Имена переменных после типа (`StringBuilder ` → `builder`, `stringBuilder`) | нет | lookup имён | **синтаксис**: разбить имя типа (camelHumps), без семантики |
| 9 | Inlay hints: имена параметров с правилами скрытия, типы `var` и переменных LINQ | сервер (`inlayHint`) | правила Rider (builder-методы, суффиксы, «понятно из имени»), xUnit `InlineData` | настройки поверх ответа сервера; потом семантика |
| 10 | Code vision: usages / inheritors / implementations / exposing APIs | сервер: «N references» и тесты | «N inheritors», «N implementations» | **синтаксис + stub-индекс** (наследники в своём коде), usages — сервер |
| 11 | Гаттер: implements / overrides / is implemented / has overrides | нет (только Ctrl+U) | `LineMarkerProvider` с переходом | **stub-индекс** базовых типов (шаг 8) + индекс сборок для `System.Object` |
| 12 | Generate (Alt+Insert): 15 генераторов (equality, relational, formatting, dispose, deconstructor…) | список code actions сервера | свои генераторы | **синтаксис** для своих полей и свойств; constructor / properties / equality — по PSI класса |
| 13 | Alt+Enter: Introduce field / auto-property из параметра, Invert if, Convert to switch, LINQ → методы, interpolation ↔ Format | quick fixes сервера + 5 своих | эти контекстные действия | часть есть у Roslyn (проверить список сервера); Introduce field / Invert if — **синтаксис** (шаг 9) |
| 14 | Refactor This (Ctrl+Shift+R) и Navigate To (Alt+\`) — единые меню | нет | два попапа-агрегатора | **платформа**: группы действий из уже существующих (Rename, Change Signature, Go to…); дёшево |
| 15 | Авто-формат на `}` и `;` | onTypeFormatting сервера; NATIVE-форматтер есть, но выключен | авто-формат без сервера | **синтаксис**: `NativeCSharpFormatter` на `}` / `;` |
| 16 | Doc-шаблон `///` с `<param>` для каждого параметра | есть (`CSharpDocCommentTypedHandler`) | сверить: `<returns>`, `<typeparam>` | сверка вживую |
| 17 | Серые «never used» по всему solution, «can be made private / static / get-only» | диагностики сервера (Unnecessary) | инспекции ReSharper | сервер / анализаторы; свои — после семантики 11e |
| 18 | Live templates: 83 шаблона, описание в списке | 33 своих | описание как tail text в lookup; `ctorf`, `propg` и др. | **синтаксис**, дописать XML |
| 19 | Тулбар: Build-сплит-кнопка с меню | комбобокс Debug/Release + TFM | — | у нас уже ближе к VS; оставить |

Что проверить вживую и почему: робот не смог вызвать автопопап completion внутри строк (`ToString("`), в XML-doc и в
`foreach (var `. Ctrl+Shift+Enter (Complete Statement Rider) тоже не снят. Наведение (quick doc, подсказки инспекций) картинками не
снимается.
