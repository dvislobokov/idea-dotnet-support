# Avalonia: `.axaml` и проекты Avalonia

Составлен 2026-10-18 по просьбе пользователя. Цель — работа с проектами Avalonia как в Rider (плагин AvaloniaRider): редактор `.axaml`
со знанием контролов, свойств и привязок, переходы между разметкой и C#, ошибки XAML-компилятора у нас, предпросмотр окна.

Статус шага отмечать здесь (`[ ]` → `[x]` с датой и версией), сделанное — в `ROADMAP.md`, видимое пользователю — в `CHANGELOG.md`
(каждый видимый шаг — своя версия `0.1.x`). Модуль — `frameworks.avalonia` (рядом с ядром C#, как Razor и Unity: ядро о нём не знает,
говорит с ним через точки расширения `io.github.dotnetsupport.csharp*`).

## Что есть сейчас (0.1.281)

- `.axaml` — тип файла XML (`plugin.xml`, `extensions="xaml;axaml;…"`): подсветка, folding, Emmet; контролов и свойств IDE не знает.
- `AvaloniaCodeGenerators`: проект с пакетом `Avalonia` или с `.axaml` — «может иметь типы, которых IDE не видела»: семантика молчит о
  `InitializeComponent()` и полях `x:Name` в code-behind (не краснеет, но и не дополняет).
- Nesting `.axaml.cs` под `.axaml` (`view`, правило уже есть), свойства Avalonia в схеме MSBuild (`msbuildSchema`), шаблоны проектов
  из `Avalonia.Templates` через New Solution, сборка / запуск / отладка — как у любого .NET-проекта.

## Решение: свой редактор `.axaml` поверх XML платформы и индекса сборок

Схемы XSD у Avalonia нет; источник знания — сборки проекта (наш индекс, `index`) и исходники solution (stub-индекс C#): контролы —
типы-наследники `Avalonia.AvaloniaObject`, свойства — статические поля `StyledProperty<T>` / `DirectProperty<TOwner,T>` /
`AttachedProperty<T>` с CLR-обёртками, события — `RoutedEvent<T>` и CLR-события. Оракул ошибок — XAML-компилятор Avalonia из сборки
(`dotnet build`, ошибки `AVLN…`), как генератор Razor для R6. Языковой сервер не используем (решение 2026-10-17: сервера в плагине нет).

## Рамки

В плане: Avalonia 11.x (`.axaml`, compiled bindings, `x:DataType`, `x:CompileBindings`), проекты SDK-формата, стили и ресурсы
(`Styles`, `ResourceDictionary`, селекторы `Style Selector="…"`), `ControlTheme`, `DataTemplate`, `TemplateBinding`.

Не в плане (отдельным решением): Avalonia 0.10 и старше (другие пространства имён и XAML-загрузчик), WPF `.xaml` (свой модуль
`frameworks.xaml`, в нём только генерируемый C#), UWP / WinUI / MAUI XAML, hot reload (у Avalonia официального нет), визуальный
дизайнер с перетаскиванием (только предпросмотр).

## Шаги

### A0. Факты и площадка (0,5–1 день)

- [ ] Площадка `debug-playground/Avalonia`: `dotnet new avalonia.mvvm` (Avalonia 11.x, CommunityToolkit.Mvvm), окно с `Grid` / `StackPanel`,
  `x:Name`, `Click`, `{Binding}` с `x:DataType`, стили, ресурсы, `UserControl`, свой контрол со `StyledProperty`; в solution
  площадки не входит (тяжёлые пакеты) — отдельный `Avalonia.sln`, как `Unity`.
- [ ] Факты сборки, записать сюда: что именно генерирует `Avalonia.Generators` (Roslyn-генератор `InitializeComponent` и полей `x:Name`
  — в каком пакете, при каких свойствах MSBuild: `AvaloniaNameGeneratorIsEnabled`, `AvaloniaNameGeneratorBehavior`), что делает
  XAML-компилятор (`CompileAvaloniaXamlTask`, IL-weaving в сборку), где и в каком формате его ошибки (`AVLN2000`…), какие items
  (`AvaloniaXaml`, `AvaloniaResource`) и как `.axaml` попадает в `AdditionalFiles`.
- [ ] Проверить, что помощник `CodeAnalysisHelper` уже запускает `Avalonia.Generators` (это Roslyn-генератор пакета): если да —
  `InitializeComponent` и поля `x:Name` есть в выводе генераторов и в индексе; тогда в `AvaloniaCodeGenerators` —
  `readsOnlyAdditionalFiles` и `errorsAfterGeneration`, как у Razor (семантика code-behind перестаёт молчать после прогона).
- [ ] Корпус для сверки: 2–3 открытых проекта на Avalonia 11 (`.corpus/avalonia`, скрипт `tools/avalonia/fetch-corpus.sh`), собираются
  SDK 10 с 0 ошибок; кандидаты — примеры AvaloniaUI (ControlCatalog), Material.Avalonia / FluentAvalonia, приложение среднего размера.

### A1. Файлы и действия (1 день)

- [ ] Иконка `.axaml` (своя, не общая XML) и тип файла `Avalonia XAML` поверх XML (`LanguageFileType` с языком-наследником XML или
  `fileTypeDetector` по содержимому: корень с `xmlns="https://github.com/avaloniaui"`), чтобы инспекции и completion модуля
  включались только в `.axaml`.
- [ ] New → Avalonia ▸ Window, UserControl, Templated Control, Styles, Resource Dictionary — пара `.axaml` + `.axaml.cs` с `x:Class`
  по пространству имён папки (как Unity / Razor: группа видна только в проектах с Avalonia).
- [ ] Переход `.axaml` ↔ `.axaml.cs` (Related Symbol, Ctrl+Alt+Home), `x:Class` ↔ класс.
- [ ] Площадка: `Avalonia/Editor/Files.axaml`, маркеры `TYPE:axaml-new-*`.

### A2. Модель: контролы, свойства, события из индекса и исходников (2–3 дня)

- [ ] `AvaloniaTypes` (модуль): по набору сборок проекта (`AssemblyIndexService.symbols`) и исходникам solution — типы, которые можно
  написать тегом (наследники `AvaloniaObject` с открытым конструктором без параметров, плюс разметочные расширения
  `MarkupExtension`), их свойства: `XxxProperty` → `Xxx` (Styled / Direct / Attached), тип значения, владелец, attached-свойства
  других типов (`Grid.Row`), CLR-события и `RoutedEvent`, `[Content]` (содержимое по умолчанию).
- [ ] Индексу сборок могут понадобиться поля `static readonly` с типом и атрибуты (`[Content]`, `[TemplatePart]`, `[PseudoClasses]`) —
  проверить формат 7, при нехватке поднять `FORMAT_VERSION` вместе с индексатором.
- [ ] `xmlns`: `https://github.com/avaloniaui` → пространства имён по `[XmlnsDefinition]` сборок, `xmlns:local="using:App.Views"` и
  `clr-namespace:…;assembly=…` → CLR-пространства; префикс `x:` — встроенные (`x:Class`, `x:Name`, `x:DataType`, `x:Key`,
  `x:CompileBindings`, `x:Static`, `x:Type`, `x:Null`).
- [ ] Кэш по набору сборок и модификациям C# (как `RazorTagHelperService`), фон, пересчёт после правки.
- [ ] Тест на фикстуре-заглушке `Avalonia.Base` / `Avalonia.Controls` (`tools/index-fixture/avalonia` → `AvaloniaStandIn.dnix`, как MVC в
  R5e ч. 3) и гейт на корпусе: наши типы и свойства против рефлексии по настоящим сборкам (`tools/avalonia/avaloniadump`).

### A3. Редактор `.axaml`: completion и навигация (4–6 дней)

- [ ] Completion тегов: контролы в области `xmlns`, с префиксом (`local:MyControl`); вставка закрывающего тега / `/>` по `[Content]`.
- [ ] Completion атрибутов: свойства и события тега, attached-свойства (`Grid.`, `DockPanel.`), `x:` атрибуты; свойство-элемент
  (`<Button.Flyout>`).
- [ ] Completion значений: перечисления, `bool`, цвета (`Colors.*`, `#RRGGBB`), `Thickness` / `CornerRadius` — формат подсказкой,
  имена обработчиков в code-behind для событий (`Click="On|"` → методы с подходящей сигнатурой, плюс «создать обработчик»).
- [ ] Разметочные расширения: `{Binding Path}`, `{CompiledBinding}`, `{StaticResource Key}`, `{DynamicResource Key}`,
  `{TemplateBinding Prop}`, `{x:Static T.Member}`, `{x:Type}`, `{ReflectionBinding}` — свой разбор строки расширения, completion
  аргументов и путей.
- [ ] Привязки: путь `{Binding}` — по `x:DataType` (или `DataContext` из `Design.DataContext` / родителя), члены VM, вложенные пути
  (`User.Name`), индексаторы, `$parent[Type].Prop`, `#elementName.Prop`.
- [ ] Ресурсы: ключи `StaticResource` / `DynamicResource` из `Resources` элемента и предков, `App.axaml`, подключённых
  `ResourceInclude` / `StyleInclude` (`avares://…`).
- [ ] Стили: селекторы `Style Selector="Button.accent:pointerover /template/ ContentPresenter"` — свой разбор, completion типов, классов
  (`Classes="…"` в файлах), псевдоклассов (`[PseudoClasses]`), `/template/`; `Setter Property="…"` по целевому типу селектора.
- [ ] Ctrl+B: тег → класс (исходник / просмотр сборки), атрибут → свойство (`XxxProperty` и обёртка), обработчик → метод, путь
  привязки → член VM, ключ ресурса → объявление, `x:Class` → класс, `avares://` → файл.
- [ ] Find Usages и rename из разметки и в неё: обработчики, члены VM в путях привязок, `x:Name` (поле code-behind), ключи ресурсов,
  классы стилей; rename члена в `.cs` переписывает разметку (`csharpRenameParticipant`, как Razor).
- [ ] Quick Documentation по тегам и свойствам (XML-документация сборки, как в C#).
- [ ] Площадка: `Avalonia/Editor/Completion.axaml`, `Navigation.axaml`, `Bindings.axaml`, `Styles.axaml`; маркеры `TYPE:axaml-*`.
- [ ] Проверка роботом в WSL (Avalonia собирается и в Linux).

### A4. Code-behind: `InitializeComponent`, поля `x:Name` (1–2 дня, по фактам A0)

- [ ] Если `Avalonia.Generators` работает в помощнике — вывод генератора уже в индексе; добавить «класс сразу» при создании и
  переименовании `.axaml` и правке `x:Name` (как `RazorGeneratedAhead`: свой лёгкий генератор partial-класса с полями по разметке).
- [ ] Если генератор выключен (`AvaloniaNameGeneratorIsEnabled=false`, старые шаблоны с `this.FindControl<T>("name")`) — новая
  точка расширения ядра «фреймворк объявляет члены partial-класса» с первой реализацией в модуле; `FindControl<T>("…")` — completion
  и проверка имени по разметке.
- [ ] Семантика code-behind перестаёт молчать после прогона генератора (`errorsAfterGeneration`): неизвестный член — ошибка.

### A5. Ошибки (3–4 дня)

- [ ] Оракул: `tools/avalonia/avln-dump` — `dotnet build` проекта корпуса, ошибки `AVLN…` XAML-компилятора с файлом и позицией;
  `coverage.txt` по образцу `compilerMessages` (`ok` / `missing` / `extra`, extra хуже missing).
- [ ] Свои проверки: неизвестный тип тега, неизвестное свойство / событие, значение не того типа (enum, bool, число), обработчик,
  которого нет в code-behind (или не та сигнатура), неизвестный ключ `StaticResource`, путь compiled binding в несуществующий член
  при `x:DataType`, `x:DataType` не найден, дубликат `x:Name`, неизвестный селектор / класс в `Setter`.
- [ ] Общие XML-инспекции в `.axaml` глушатся там, где модуль знает лучше (неизвестные namespace URI, атрибуты без схемы), как
  `RazorHtmlInspectionSuppressor`.
- [ ] Площадка: `debug-playground/Broken/Avalonia/*.axaml` (вне solution), `Avalonia/Editor/Errors.axaml` — `TYPE:axaml-errors`.

### A6. Предпросмотр (2–3 недели)

- [ ] Факты: `Avalonia.Designer.HostApp` (в пакете `Avalonia`, `tools/…/Avalonia.Designer.HostApp.dll`) запускается с
  `--transport tcp-bson://127.0.0.1:<port>/ --method avalonia-remote --runtimeconfig … --depsfile … <сборка проекта>`; протокол —
  сообщения BSON (`UpdateXamlMessage`, `UpdateXamlResultMessage` с ошибкой, `FrameMessage` с картинкой, `ClientViewportAllocatedMessage`,
  `ClientRenderInfoMessage`, ввод мыши); записать сюда точную форму сообщений по исходникам `Avalonia.Remote.Protocol`.
- [ ] Клиент протокола на Kotlin (`frameworks/avalonia/preview`): TCP, BSON, приём кадров (BGRA) в `BufferedImage`.
- [ ] Окно предпросмотра: split-editor `.axaml` (текст | картинка), как у Markdown; масштаб, тема (Light / Dark через
  `RequestedThemeVariant`), размер из `d:DesignWidth` / `d:DesignHeight`.
- [ ] Обновление на каждую правку разметки (дебаунс 300 мс) без пересборки; пересборка проекта — когда изменился C# (кнопка и авто
  после Build); ошибка XAML — плашкой над картинкой с позицией.
- [ ] Выделение элемента в предпросмотре ↔ тег в тексте (по координатам: `ElementTreeMessage`, если протокол даёт; иначе — только
  текст → подсветка рамкой).
- [ ] Процесс-хост живёт, пока открыт редактор; закрытие, повторный запуск после сборки, утечки — замерить, как помощник.
- [ ] Платное или нет — решить с пользователем (кода изданий с 0.1.298 нет, см. «Издания» в CLAUDE.md).

### A7. Отладка и прочее (по мере нужды)

- [ ] Точки останова в code-behind — уже работают (обычный C#); в `.axaml` — нет (XAML компилируется в IL без `#line`), не планируется.
- [ ] Run configuration для Avalonia: Desktop-проект запускается обычным `dotnet run`; Browser (WASM) и Android / iOS — вне плана.
- [ ] `ROADMAP.md`, `docs/demo.html`, `docs/guide.html` (перегенерация), `debug-playground/README.md` — по ходу каждого шага.

## Порядок

A0 → A1 → A2 → A3 (основная ценность) → A4 → A5 → A6. A6 независим от A3–A5 и может идти параллельно после A0, если предпросмотр
важнее редактора. Каждый шаг после A1 полезен сам по себе: A3 без A5 — уже Rider-уровень набора и навигации.

## Риски

- Avalonia 11 меняется между минорными версиями (compiled bindings по умолчанию с 11.1, `ControlTheme`, генератор имён): модель
  берётся из сборок проекта, а не захардкожена; корпус — на двух минорных версиях.
- Протокол предпросмотра внутренний и без гарантий совместимости: клиент сверяется с версией пакета проекта, при неизвестной — честное
  «preview not supported for Avalonia x.y».
- Предпросмотру нужна собранная сборка проекта: до первой сборки — плашка «Build the project to see the preview».
- Пакеты Avalonia тяжёлые для CI-тестов: тесты — на заглушке-фикстуре индекса, настоящие сборки — только в гейте корпуса.
